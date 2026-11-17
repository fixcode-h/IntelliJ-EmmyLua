// 函数断点（协议 7.7）：按「Module.func」「Class:Method」或全局函数名停在函数第一行。
//
// 两条路：
//   1. 按值：在 VM 里按名字找到函数值（全局表 → package.loaded[模块名] → package.loaded 里按模块名后缀找），
//      记下它的原型（source 指针 + 定义行），CALL 事件里查哈希表。宿主从 C 调进来的（UnLua 的 ReceiveBeginPlay 等，
//      调用处没有名字）也能停。函数值在 registry 里引用着，原型不会被回收，source 指针不会被别的函数复用。
//   2. 按名字兜底：还找不到函数值的（local 模块表、还没 require 的模块），CALL 事件里用 lua_getinfo "n"
//      取调用处的名字比对；带限定名的先比文件名（便宜），对上了才取 "n"（要分析调用者的字节码）。
// 只有本 VM 有函数断点时 OnHook 才调进来（BaseMask 才有 CALL）；没有函数断点时不多花任何代价。
#include "vm.h"

#include "core.h"

#include <cctype>
#include <cstring>

namespace emmy {

namespace {

constexpr int kMaxIndexDepth = 8;
constexpr size_t kMaxBaseNames = 4096;

// t[name]：rawget，不调元方法；找不到时沿元表的 __index（是 table 时）往上找，最多 kMaxIndexDepth 层。结果压栈（找不到为 nil）
void RawGetChain(lua_State* L, int t, const char* name) {
	t = lua::AbsIndex(L, t);
	lua_pushvalue(L, t);
	for (int depth = 0; depth < kMaxIndexDepth; ++depth) {
		if (lua_type(L, -1) != LUA_TTABLE) break;
		lua_pushstring(L, name);
		lua_rawget(L, -2);
		if (lua_type(L, -1) != LUA_TNIL) {
			lua_remove(L, -2);
			return;
		}
		lua_pop(L, 1);
		if (!lua_getmetatable(L, -1)) break;
		lua_pushstring(L, "__index");
		lua_rawget(L, -2); // tbl, mt, __index
		lua_remove(L, -2);
		lua_remove(L, -2);
	}
	lua_pop(L, 1);
	lua_pushnil(L);
}

// 从 idx 处的值按点分路径往下取，结果压栈
void WalkPath(lua_State* L, int idx, const std::string& dotted) {
	idx = lua::AbsIndex(L, idx);
	lua_pushvalue(L, idx);
	size_t start = 0;
	while (start <= dotted.size()) {
		size_t end = dotted.find('.', start);
		std::string seg = dotted.substr(start, end == std::string::npos ? std::string::npos : end - start);
		RawGetChain(L, -1, seg.c_str());
		lua_remove(L, -2);
		if (end == std::string::npos) break;
		start = end + 1;
	}
}

bool EqualsCase(const std::string& a, const std::string& b, bool caseSensitive) {
	if (a.size() != b.size()) return false;
	if (caseSensitive) return a == b;
	for (size_t i = 0; i < a.size(); ++i) {
		if (std::tolower(static_cast<unsigned char>(a[i])) != std::tolower(static_cast<unsigned char>(b[i]))) return false;
	}
	return true;
}

// 模块名 key（"UI.BagPanel"、"UI/BagPanel"）以 qualifier（点分）结尾，并且在段边界上
bool ModuleKeyEndsWith(std::string key, const std::string& qualifier, bool caseSensitive) {
	for (char& c : key) {
		if (c == '/' || c == '\\') c = '.';
	}
	if (key.size() < qualifier.size()) return false;
	const size_t off = key.size() - qualifier.size();
	if (off > 0 && key[off - 1] != '.') return false;
	return EqualsCase(key.substr(off), qualifier, caseSensitive);
}

// 按限定名找表：全局 → package.loaded[限定名] → package.loaded 里模块名以它结尾的（第一个是 table 的）。结果压栈
void PushQualifier(lua_State* L, const std::string& qualifier, bool caseSensitive) {
	lua::PushGlobalTable(L);
	WalkPath(L, -1, qualifier);
	lua_remove(L, -2);
	if (lua_type(L, -1) == LUA_TTABLE) return;
	lua_pop(L, 1);
	lua_getfield(L, LUA_REGISTRYINDEX, "_LOADED");
	if (lua_type(L, -1) != LUA_TTABLE) {
		lua_pop(L, 1);
		lua_pushnil(L);
		return;
	}
	const int loaded = lua_gettop(L);
	lua_pushstring(L, qualifier.c_str());
	lua_rawget(L, loaded);
	if (lua_type(L, -1) == LUA_TTABLE) {
		lua_remove(L, loaded);
		return;
	}
	lua_pop(L, 1);
	lua_pushnil(L);
	while (lua_next(L, loaded)) {
		// 不对 key 调 lua_tostring（会改 key，打乱 lua_next）
		if (lua_type(L, -2) == LUA_TSTRING && lua_type(L, -1) == LUA_TTABLE) {
			size_t len = 0;
			const char* k = lua_tolstring(L, -2, &len);
			if (k && ModuleKeyEndsWith(std::string(k, len), qualifier, caseSensitive)) {
				lua_remove(L, -2); // 去掉 key
				lua_remove(L, loaded);
				return;
			}
		}
		lua_pop(L, 1);
	}
	lua_pop(L, 1);
	lua_pushnil(L);
}

std::string LastSegment(const std::string& dotted) {
	size_t dot = dotted.rfind('.');
	return dot == std::string::npos ? dotted : dotted.substr(dot + 1);
}

} // namespace

void Vm::ReleaseFunctionTargets(lua_State* L) {
	for (int ref : funcRefs_) luaL_unref(L, LUA_REGISTRYINDEX, ref);
	funcRefs_.clear();
	funcTargets_.clear();
	funcUnresolved_.clear();
	funcBaseNames_.clear();
	funcPending_ = FuncPending{};
	funcDirty_ = true;
}

bool Vm::FuncSourceOk(const Breakpoint& bp, const char* source) {
	if (!bp.HasSource()) return true;
	return source && MatchSingle(bp, source, Core::Get().options().CaseSensitive());
}

const std::string& Vm::FuncBaseName(const char* source) {
	auto it = funcBaseNames_.find(source);
	if (it != funcBaseNames_.end()) return it->second;
	if (funcBaseNames_.size() >= kMaxBaseNames) funcBaseNames_.clear();
	// "@Content/Script/UI/BagPanel.lua" → "BagPanel"；模块名式的 chunk（"UI.BagPanel"）取最后一段
	std::string s = source ? source : "";
	if (!s.empty() && s[0] == '@') s.erase(0, 1);
	for (const char* ext : {".lua", ".luac", ".txt", ".bytes"}) {
		const size_t n = std::strlen(ext);
		if (s.size() > n && EqualsCase(s.substr(s.size() - n), ext, false)) {
			s.resize(s.size() - n);
			break;
		}
	}
	size_t cut = s.find_last_of("/\\");
	if (cut == std::string::npos) cut = s.rfind('.');
	if (cut != std::string::npos) s.erase(0, cut + 1);
	return funcBaseNames_.emplace(source, std::move(s)).first->second;
}

void Vm::ResolveFunctionBreakpoints(lua_State* L) {
	// 先放掉上一次的引用（funcBps_ 不动），再按当前的 VM 状态重新找
	std::vector<BreakpointPtr> bps = funcBps_;
	ReleaseFunctionTargets(L);
	funcDirty_ = false;
	funcResolvedAt_ = std::chrono::steady_clock::now();
	const bool cs = Core::Get().options().CaseSensitive();
	const int top = lua_gettop(L);
	for (const BreakpointPtr& bp : bps) {
		if (bp->funcQualifier.empty()) {
			lua::PushGlobalTable(L);
			RawGetChain(L, -1, bp->funcName.c_str());
			lua_remove(L, -2);
		} else {
			PushQualifier(L, bp->funcQualifier, cs);
			RawGetChain(L, -1, bp->funcName.c_str());
			lua_remove(L, -2);
		}
		bool resolved = false;
		if (lua_type(L, -1) == LUA_TFUNCTION) {
			lua_pushvalue(L, -1);
			lua_Debug ar;
			std::memset(&ar, 0, sizeof(ar));
			// C 函数没有「第一行」，不支持；只认 Lua 函数
			if (lua_getinfo(L, ">S", &ar) && ar.what && std::strcmp(ar.what, "C") != 0 && ar.source &&
				FuncSourceOk(*bp, ar.source)) {
				FuncKey key{ar.source, ar.linedefined};
				funcTargets_[key].push_back(bp);
				funcRefs_.push_back(luaL_ref(L, LUA_REGISTRYINDEX)); // 引用着函数，原型不会被回收
				resolved = true;
				auto& reported = reported_[bp->serial];
				if (reported != "verified") {
					reported = "verified";
					Core::Get().Emit("breakpointStatus", {{"id", bp->id}, {"vmId", id_}, {"state", "verified"},
						{"chunkName", ar.source}, {"line", ar.linedefined}});
				}
			}
		}
		lua_settop(L, top);
		if (!resolved) funcUnresolved_.push_back(bp);
	}
	funcBps_ = std::move(bps);
}

bool Vm::OnFunctionCall(lua_State* L, lua_Debug* ar, const lua::FuncInfo& f) {
	// 热重载会换掉函数：每 1024 次调用看一次时间，超过 1 秒就重新找一遍
	if (funcDirty_) {
		ResolveFunctionBreakpoints(L);
	} else if ((++funcCallTick_ & 1023u) == 0 && std::chrono::steady_clock::now() - funcResolvedAt_ > std::chrono::seconds(1)) {
		ResolveFunctionBreakpoints(L);
	}
	if (f.isC || !f.source) return false;
	std::vector<BreakpointPtr> hits;
	auto it = funcTargets_.find(FuncKey{f.source, f.lineDefined});
	if (it != funcTargets_.end()) hits = it->second;
	if (!funcUnresolved_.empty()) {
		const bool cs = Core::Get().options().CaseSensitive();
		bool needName = false;
		for (const BreakpointPtr& bp : funcUnresolved_) {
			if (bp->funcQualifier.empty() || EqualsCase(FuncBaseName(f.source), LastSegment(bp->funcQualifier), cs)) {
				needName = true;
				break;
			}
		}
		if (needName && lua_getinfo(L, "n", ar) && ar->name) {
			const char* nw = ar->namewhat ? ar->namewhat : "";
			const bool asGlobal = !std::strcmp(nw, "global") || !std::strcmp(nw, "local") || !std::strcmp(nw, "upvalue");
			const bool asField = !std::strcmp(nw, "field") || !std::strcmp(nw, "method");
			for (const BreakpointPtr& bp : funcUnresolved_) {
				if (bp->funcName != ar->name) continue;
				if (bp->funcQualifier.empty()) {
					if (!asGlobal) continue;
				} else if (!asField || !EqualsCase(FuncBaseName(f.source), LastSegment(bp->funcQualifier), cs)) {
					continue;
				}
				if (!FuncSourceOk(*bp, f.source)) continue;
				hits.push_back(bp);
				auto& reported = reported_[bp->serial];
				if (reported != "verified") {
					reported = "verified";
					Core::Get().Emit("breakpointStatus", {{"id", bp->id}, {"vmId", id_}, {"state", "verified"},
						{"chunkName", f.source}, {"line", f.lineDefined}});
				}
			}
		}
	}
	if (hits.empty()) return false;
	funcPending_.L = L;
	funcPending_.key = FuncKey{f.source, f.lineDefined};
	funcPending_.bps = std::move(hits);
	return true;
}

bool Vm::OnFunctionEntry(lua_State* L, lua_Debug* ar, const lua::FuncInfo& f) {
	FuncPending pend = std::move(funcPending_);
	funcPending_ = FuncPending{};
	// CALL 之后这个线程的第一个行事件一定在被调函数里（执行任何指令前先有行事件）；对不上就是被打断了，放弃
	if (f.isC || !f.source || !(pend.key == FuncKey{f.source, f.lineDefined})) return false;
	const int line = ar->currentline;
	const std::string chunk = f.source;
	std::vector<std::string> ids;
	for (const BreakpointPtr& bp : pend.bps) {
		if (HandleBreakpointHit(L, bp.get(), line, chunk)) ids.push_back(bp->id);
	}
	if (ids.empty()) return false;
	EnterPause(L, "breakpoint", std::move(ids), nullptr);
	return true;
}

} // namespace emmy
