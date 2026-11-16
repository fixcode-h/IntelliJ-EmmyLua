#include "inspector.h"

#include "core.h"
#include "host_value.h"
#include "inspector_util.h"
#include "lua_internal.h"
#include "value_path.h"
#include "vm.h"

#include <algorithm>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <cctype>
#include <cstdlib>

namespace emmy {

using namespace detail;

namespace {

int64_t NowMs() {
	return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

} // namespace

json VariableItem::ToJson() const {
	json j = {
		{"name", name}, {"nameType", nameType}, {"value", value}, {"type", type}, {"ref", ref},
	};
	if (!typeName.empty()) j["typeName"] = typeName;
	if (namedCount >= 0) j["namedCount"] = namedCount;
	if (indexedCount >= 0) j["indexedCount"] = indexedCount;
	if (!evaluateName.empty()) j["evaluateName"] = evaluateName;
	if (!kind.empty()) j["kind"] = kind;
	if (!hint.empty()) j["hint"] = hint;
	if (!attrs.empty()) j["attrs"] = attrs;
	if (length >= 0) j["length"] = length;
	if (!objectId.empty()) j["objectId"] = objectId;
	if (!locationChunk.empty()) j["location"] = {{"chunkName", locationChunk}, {"line", locationLine}};
	return j;
}

Inspector::Inspector(Vm& vm) : vm_(vm) {}

// ---------------------------------------------------------------- 暂停生命周期

void Inspector::BeginPause(lua_State* L, int skipLevels) {
	pausedL_ = L;
	skip_ = skipLevels;
	usedProviders_.clear();
	classNames_.clear();
	loadedNames_.clear();
	loadedBuilt_ = false;
	toStringFails_.clear();
	pauseIdForHost_ = vm_.currentPauseId();
	lua_newtable(L);
	anchorRef_ = luaL_ref(L, LUA_REGISTRYINDEX);
	nextSlot_ = 1;
	frames_.clear();
	refs_.clear();
	ids_.clear();
	frameOrder_.clear();
}

void Inspector::EndPause(lua_State* L) {
	if (!usedProviders_.empty()) {
		host::ReleasePause(vm_.id(), pauseIdForHost_, usedProviders_);
		usedProviders_.clear();
	}
	if (anchorRef_ != LUA_NOREF) {
		luaL_unref(L, LUA_REGISTRYINDEX, anchorRef_);
		anchorRef_ = LUA_NOREF;
	}
	Core::Get().ReleaseObjectIds(ids_);
	ids_.clear();
	frames_.clear();
	refs_.clear();
	frameOrder_.clear();
	classNames_.clear();
	loadedNames_.clear();
	loadedBuilt_ = false;
	toStringFails_.clear();
	pausedL_ = nullptr;
}

uint64_t Inspector::NewFrameId(lua_State* thread, int level) {
	for (uint64_t id : frameOrder_) {
		const FrameRef& f = frames_[id];
		if (f.thread == thread && f.level == level) return id;
	}
	uint64_t id = Core::Get().AllocObjectId(vm_.id());
	ids_.push_back(id);
	frames_[id] = FrameRef{thread, level};
	frameOrder_.push_back(id);
	return id;
}

uint64_t Inspector::NewRef(RefKind kind, uint64_t frameId, int slot, const std::string& evaluateName) {
	uint64_t id = Core::Get().AllocObjectId(vm_.id());
	ids_.push_back(id);
	RefEntry e;
	e.kind = kind;
	e.frameId = frameId;
	e.slot = slot;
	e.evaluateName = evaluateName;
	refs_[id] = std::move(e);
	return id;
}

int Inspector::Anchor(lua_State* L, int idx) {
	// 不在暂停里没有锚表：rawseti 到 nil 上会让 Lua 读坏内存，直接不锚（返回 0，PushAnchored 压 nil）
	if (anchorRef_ == LUA_NOREF || anchorRef_ == LUA_REFNIL) return 0;
	idx = lua::AbsIndex(L, idx);
	lua_rawgeti(L, LUA_REGISTRYINDEX, anchorRef_);
	lua_pushvalue(L, idx);
	int slot = nextSlot_++;
	lua_rawseti(L, -2, slot);
	lua_pop(L, 1);
	return slot;
}

void Inspector::PushAnchored(lua_State* L, int slot) {
	if (slot <= 0 || anchorRef_ == LUA_NOREF || anchorRef_ == LUA_REFNIL) {
		lua_pushnil(L);
		return;
	}
	lua_rawgeti(L, LUA_REGISTRYINDEX, anchorRef_);
	if (lua_type(L, -1) != LUA_TTABLE) { // 锚表丢了（不该发生）：当成取不到，不对 nil 做 rawgeti
		lua_pop(L, 1);
		lua_pushnil(L);
		return;
	}
	lua_rawgeti(L, -1, slot);
	lua_remove(L, -2);
}

bool Inspector::GetFrame(uint64_t frameId, FrameRef& out, lua_Debug& ar, ProtocolError& err) {
	auto it = frames_.find(frameId);
	if (it == frames_.end()) {
		err = {"STALE_REFERENCE", "这个栈帧已失效（游戏已继续运行），请重新获取调用栈。"};
		return false;
	}
	out = it->second;
	if (!lua_getstack(out.thread, out.level, &ar)) {
		err = {"STALE_REFERENCE", "这个栈帧已经不存在，请重新获取调用栈。"};
		return false;
	}
	return true;
}

// ---------------------------------------------------------------- 栈

bool Inspector::StackTrace(int start, int count, json& body, ProtocolError& err, const std::string& threadId) {
	lua_State* L = pausedL_;
	if (!L) {
		err = {"NOT_PAUSED", "Lua VM 正在运行，先暂停或等断点命中。"};
		return false;
	}
	int skip = skip_;
	if (!threadId.empty() && threadId != "t" + PtrString(L).substr(2)) {
		lua_State* found = nullptr;
		lua_State* mainL = vm_.mainState();
		if (threadId == "t" + PtrString(mainL).substr(2)) found = mainL;
		else {
			lua::ForEachCoroutine(L, [&](lua_State* co) {
				if (!found && threadId == "t" + PtrString(co).substr(2)) found = co;
			});
		}
		if (!found) {
			err = {"INVALID_ARGS", "找不到协程 " + threadId + "（可能已经结束），请重新取 threads。"};
			return false;
		}
		L = found;
		skip = 0;
	}
	if (start < 0) start = 0;
	if (count <= 0) count = 20;
	if (count > 200) count = 200;
	json frames = json::array();
	int total = 0;
	lua_Debug ar;
	for (int real = skip; lua_getstack(L, real, &ar); ++real) {
		const int level = real - skip;
		++total;
		if (level < start || level >= start + count) continue;
		lua_getinfo(L, "nSl", &ar);
		json f;
		f["frameId"] = NewFrameId(L, real);
		f["level"] = level;
		f["threadId"] = "t" + PtrString(L).substr(2);
		std::string name = ar.name ? ar.name : "";
		if (name.empty()) {
			if (ar.what && std::strcmp(ar.what, "main") == 0) name = "main chunk";
			else name = "?";
		}
		f["name"] = name;
		f["what"] = ar.what ? ar.what : "Lua";
		f["chunkName"] = ar.source ? ar.source : "=?";
		f["line"] = ar.currentline;
		f["lineDefined"] = ar.linedefined;
		f["lastLineDefined"] = ar.lastlinedefined;
		frames.push_back(std::move(f));
	}
	body = {{"frames", std::move(frames)}, {"total", total}};
	return true;
}

json Inspector::TopFrame() {
	lua_State* L = pausedL_;
	lua_Debug ar;
	if (!L || !lua_getstack(L, skip_, &ar)) return nullptr;
	lua_getinfo(L, "nSl", &ar);
	json f;
	f["frameId"] = NewFrameId(L, skip_);
	f["name"] = ar.name ? ar.name : (ar.what && std::strcmp(ar.what, "main") == 0 ? "main chunk" : "?");
	f["what"] = ar.what ? ar.what : "Lua";
	f["chunkName"] = ar.source ? ar.source : "=?";
	f["line"] = ar.currentline;
	f["lineDefined"] = ar.linedefined;
	f["lastLineDefined"] = ar.lastlinedefined;
	return f;
}

// ---------------------------------------------------------------- 作用域

bool Inspector::Scopes(uint64_t frameId, json& body, ProtocolError& err) {
	FrameRef f;
	lua_Debug ar;
	if (!GetFrame(frameId, f, ar, err)) return false;
	lua_State* L = f.thread;
	json scopes = json::array();
	int locals = 0;
	for (int i = 1;; ++i) {
		const char* n = lua_getlocal(L, &ar, i);
		if (!n) break;
		lua_pop(L, 1);
		if (n[0] != '(') ++locals;
	}
	scopes.push_back({{"name", "locals"}, {"ref", NewRef(RefKind::Locals, frameId, 0, "")}, {"count", locals}});
	lua_getinfo(L, "fu", &ar);
	int nups = ar.nups;
	lua_pop(L, 1);
	if (nups > 0) {
		scopes.push_back({{"name", "upvalues"}, {"ref", NewRef(RefKind::Upvalues, frameId, 0, "")}, {"count", nups}});
	}
#if EMMY_LUA_VERSION_NUM >= 502
	int varargs = 0;
	for (int i = 1;; ++i) {
		const char* n = lua_getlocal(L, &ar, -i);
		if (!n) break;
		lua_pop(L, 1);
		++varargs;
	}
	if (varargs > 0) {
		scopes.push_back({{"name", "varargs"}, {"ref", NewRef(RefKind::Varargs, frameId, 0, "")}, {"count", varargs}});
	}
#endif
	PushFrameEnv(L, L, ar);
	int slot = Anchor(L, -1);
	lua_pop(L, 1);
	scopes.push_back({{"name", "globals"}, {"ref", NewRef(RefKind::Globals, frameId, slot, "")}});
	body = {{"scopes", std::move(scopes)}};
	return true;
}

void Inspector::PushFrameEnv(lua_State* L, lua_State* thread, lua_Debug& ar) {
	// 结果压在 L 上（L 与 thread 同一个 VM）
	lua_getinfo(thread, "fu", &ar);
	if (thread != L) lua_xmove(thread, L, 1);
	int fn = lua::AbsIndex(L, -1);
#if EMMY_LUA_VERSION_NUM == 501
	if (lua_iscfunction(L, fn)) {
		lua_pop(L, 1);
		lua::PushGlobalTable(L);
		return;
	}
	lua_getfenv(L, fn);
	lua_remove(L, fn);
#else
	for (int i = 1; i <= ar.nups; ++i) {
		const char* n = lua_getupvalue(L, fn, i);
		if (!n) break;
		if (std::strcmp(n, "_ENV") == 0) {
			lua_remove(L, fn);
			return;
		}
		lua_pop(L, 1);
	}
	lua_pop(L, 1);
	lua::PushGlobalTable(L);
#endif
}

// ---------------------------------------------------------------- 变量

std::string Inspector::FormatNumber(lua_State* L, int idx) {
	char buf[64];
	if (lua::IsInteger(L, idx)) {
		std::snprintf(buf, sizeof(buf), "%lld", static_cast<long long>(lua_tointeger(L, idx)));
	} else {
		lua_Number n = lua_tonumber(L, idx);
		std::snprintf(buf, sizeof(buf), "%.14g", static_cast<double>(n));
		// 5.3+ 的浮点数在 Lua 里打印带 .0，保持一致，便于区分整数
		if (EMMY_LUA_VERSION_NUM >= 503 && std::strpbrk(buf, ".eEn") == nullptr) {
			size_t len = std::strlen(buf);
			if (len + 2 < sizeof(buf)) std::memcpy(buf + len, ".0", 3);
		}
	}
	return buf;
}

std::string Inspector::TableSummary(lua_State* L, int idx, size_t maxLen) {
	idx = lua::AbsIndex(L, idx);
	std::string out = "{";
	int shown = 0;
	bool more = false;
	size_t n = lua::RawLen(L, idx);
	for (size_t i = 1; i <= n; ++i) {
		if (shown >= 5) { more = true; break; }
		lua_rawgeti(L, idx, static_cast<lua_Integer>(i));
		if (shown) out += ", ";
		int t = lua_type(L, -1);
		out += (t == LUA_TTABLE) ? "{…}" : Summarize(L, -1, 64);
		lua_pop(L, 1);
		++shown;
	}
	if (!more) {
		lua_pushnil(L);
		while (lua_next(L, idx)) {
			bool arrayKey = false;
			if (lua_type(L, -2) == LUA_TNUMBER) {
				lua_Number k = lua_tonumber(L, -2);
				arrayKey = k >= 1 && k <= static_cast<lua_Number>(n) && k == static_cast<lua_Number>(static_cast<int64_t>(k));
			}
			if (arrayKey) { lua_pop(L, 1); continue; }
			if (shown >= 5) { more = true; lua_pop(L, 2); break; }
			if (shown) out += ", ";
			if (lua_type(L, -2) == LUA_TSTRING) {
				size_t kl;
				const char* ks = lua_tolstring(L, -2, &kl);
				if (IsIdentifier(ks, kl)) out.append(ks, kl);
				else { out += "["; AppendEscaped(out, ks, kl, 40); out += "]"; }
			} else {
				out += "[" + Summarize(L, -2, 32) + "]";
			}
			out += "=";
			out += (lua_type(L, -1) == LUA_TTABLE) ? "{…}" : Summarize(L, -1, 64);
			lua_pop(L, 1);
			++shown;
			if (out.size() > maxLen) { more = true; lua_pop(L, 1); break; }
		}
	}
	if (more) out += shown ? ", …" : "…";
	out += "}";
	return out;
}

std::string Inspector::Summarize(lua_State* L, int idx, size_t maxLen, std::string* typeName) {
	idx = lua::AbsIndex(L, idx);
	int t = lua_type(L, idx);
	switch (t) {
	case LUA_TNIL: return "nil";
	case LUA_TBOOLEAN: return lua_toboolean(L, idx) ? "true" : "false";
	case LUA_TNUMBER: return FormatNumber(L, idx);
	case LUA_TSTRING: {
		size_t len;
		const char* s = lua_tolstring(L, idx, &len);
		std::string out;
		AppendEscaped(out, s, len, maxLen);
		return out;
	}
	case LUA_TTABLE: {
		std::string mn = MetaName(L, idx);
		if (typeName && !mn.empty()) *typeName = mn;
		std::string s = TableSummary(L, idx, maxLen);
		if (s.size() > maxLen) s = s.substr(0, maxLen) + "…";
		return s;
	}
	case LUA_TFUNCTION: {
		std::string out = "function: " + PtrString(lua_topointer(L, idx));
		lua_Debug ar;
		lua_pushvalue(L, idx);
		if (lua_getinfo(L, ">S", &ar) && ar.source && ar.source[0] == '@') {
			out += " (" + std::string(ar.source + 1) + ":" + std::to_string(ar.linedefined) + ")";
		}
		return out;
	}
	case LUA_TUSERDATA: {
		std::string mn = MetaName(L, idx);
		if (typeName && !mn.empty()) *typeName = mn;
		return (mn.empty() ? std::string("userdata") : mn) + ": " + PtrString(lua_touserdata(L, idx));
	}
	case LUA_TLIGHTUSERDATA: return "lightuserdata: " + PtrString(lua_touserdata(L, idx));
	case LUA_TTHREAD: return "thread: " + PtrString(lua_tothread(L, idx));
	default: return lua_typename(L, t);
	}
}

void Inspector::MakeItem(lua_State* L, int idx, VariableItem& item, const std::string& evaluateName) {
	if (view_.rich) {
		MakeItemRich(L, idx, item, evaluateName, "", "");
		return;
	}
	idx = lua::AbsIndex(L, idx);
	int t = lua_type(L, idx);
	item.type = lua_typename(L, t);
	// 宿主（引擎）认领的对象：摘要和子项都听宿主的，失效对象也不再调 __tostring
	if ((t == LUA_TUSERDATA || t == LUA_TTABLE) && host::HasProviders() && HostDescribe(L, idx, item, evaluateName)) return;
	item.value = Summarize(L, idx, 1024, &item.typeName);
	item.evaluateName = evaluateName;
	bool expandable = false;
	if (t == LUA_TTABLE) {
		expandable = true;
		size_t n = lua::RawLen(L, idx);
		item.indexedCount = static_cast<int64_t>(n);
		int64_t named = 0;
		lua_pushnil(L);
		while (lua_next(L, idx)) {
			++named;
			lua_pop(L, 1);
			if (named > 1000000) break;
		}
		item.namedCount = named - static_cast<int64_t>(n) > 0 ? named - static_cast<int64_t>(n) : 0;
	} else if (t == LUA_TUSERDATA) {
		expandable = lua_getmetatable(L, idx) != 0;
		if (expandable) lua_pop(L, 1);
	} else if (t == LUA_TFUNCTION) {
		lua_Debug ar;
		lua_pushvalue(L, idx);
		lua_getinfo(L, ">u", &ar);
		expandable = ar.nups > 0;
	}
	if (expandable) {
		int slot = Anchor(L, idx);
		item.ref = NewRef(RefKind::Value, 0, slot, evaluateName);
	}
}

namespace {

std::string ChildEvalName(const std::string& parent, lua_State* L, int keyIdx) {
	if (parent.empty()) return "";
	int kt = lua_type(L, keyIdx);
	if (kt == LUA_TSTRING) {
		size_t kl;
		const char* ks = lua_tolstring(L, keyIdx, &kl);
		if (IsIdentifier(ks, kl)) return parent + "." + std::string(ks, kl);
		std::string out = parent + "[";
		AppendEscaped(out, ks, kl, 4096);
		return out + "]";
	}
	if (kt == LUA_TNUMBER) {
		lua_Number k = lua_tonumber(L, keyIdx);
		if (k == static_cast<lua_Number>(static_cast<int64_t>(k))) {
			return parent + "[" + std::to_string(static_cast<int64_t>(k)) + "]";
		}
	}
	return "";
}

} // namespace

void Inspector::Materialize(RefEntry& entry) {
	lua_State* L = pausedL_;
	entry.materialized = true;
	entry.children.clear();
	int top = lua_gettop(L);
	lua_checkstack(L, 16);
	if (entry.kind == RefKind::Locals || entry.kind == RefKind::Upvalues || entry.kind == RefKind::Varargs) {
		FrameRef f;
		lua_Debug ar;
		ProtocolError err;
		if (!GetFrame(entry.frameId, f, ar, err)) return;
		lua_State* T = f.thread;
		if (entry.kind == RefKind::Locals) {
			// 同名局部变量只显示最内层（后声明的覆盖前面的）
			std::vector<std::pair<std::string, int>> names;
			for (int i = 1;; ++i) {
				const char* n = lua_getlocal(T, &ar, i);
				if (!n) break;
				lua_pop(T, 1);
				if (n[0] == '(') continue;
				names.emplace_back(n, i);
			}
			for (size_t k = 0; k < names.size(); ++k) {
				bool shadowed = false;
				for (size_t j = k + 1; j < names.size(); ++j) {
					if (names[j].first == names[k].first) { shadowed = true; break; }
				}
				if (shadowed) continue;
				lua_getlocal(T, &ar, names[k].second);
				if (T != L) lua_xmove(T, L, 1);
				VariableItem item;
				item.name = names[k].first;
				item.nameType = "string";
				MakeItem(L, -1, item, names[k].first);
				lua_pop(L, 1);
				entry.children.push_back(std::move(item));
			}
		} else if (entry.kind == RefKind::Varargs) {
#if EMMY_LUA_VERSION_NUM >= 502
			for (int i = 1;; ++i) {
				const char* n = lua_getlocal(T, &ar, -i);
				if (!n) break;
				if (T != L) lua_xmove(T, L, 1);
				VariableItem item;
				item.name = "[" + std::to_string(i) + "]";
				item.nameType = "number";
				item.indexed = true;
				MakeItem(L, -1, item, "");
				lua_pop(L, 1);
				entry.children.push_back(std::move(item));
			}
#endif
		} else {
			lua_getinfo(T, "fu", &ar);
			if (T != L) lua_xmove(T, L, 1);
			int fn = lua_gettop(L);
			for (int i = 1; i <= ar.nups; ++i) {
				const char* n = lua_getupvalue(L, fn, i);
				if (!n) break;
				VariableItem item;
				item.name = (n[0] ? n : "?");
				item.nameType = "string";
				MakeItem(L, -1, item, n[0] ? n : "");
				lua_pop(L, 1);
				entry.children.push_back(std::move(item));
			}
		}
		lua_settop(L, top);
		return;
	}

	PushAnchored(L, entry.slot);
	int obj = lua_gettop(L);
	int t = lua_type(L, obj);
	if (t == LUA_TTABLE) {
		size_t n = lua::RawLen(L, obj);
		for (size_t i = 1; i <= n; ++i) {
			lua_pushinteger(L, static_cast<lua_Integer>(i));
			lua_rawgeti(L, obj, static_cast<lua_Integer>(i));
			VariableItem item;
			item.name = "[" + std::to_string(i) + "]";
			item.nameType = "number";
			item.indexed = true;
			MakeItem(L, -1, item, ChildEvalName(entry.evaluateName, L, -2));
			lua_pop(L, 2);
			entry.children.push_back(std::move(item));
		}
		lua_pushnil(L);
		while (lua_next(L, obj)) {
			int kt = lua_type(L, -2);
			if (kt == LUA_TNUMBER && lua::RawLen(L, obj) > 0) {
				lua_Number k = lua_tonumber(L, -2);
				if (k >= 1 && k <= static_cast<lua_Number>(n) && k == static_cast<lua_Number>(static_cast<int64_t>(k))) {
					lua_pop(L, 1);
					continue;
				}
			}
			VariableItem item;
			item.nameType = lua_typename(L, kt);
			if (kt == LUA_TSTRING) {
				size_t kl;
				const char* ks = lua_tolstring(L, -2, &kl);
				item.name.assign(ks, kl);
			} else if (kt == LUA_TNUMBER) {
				item.name = "[" + FormatNumber(L, -2) + "]";
			} else {
				item.name = "[" + Summarize(L, -2, 64) + "]";
			}
			MakeItem(L, -1, item, ChildEvalName(entry.evaluateName, L, -2));
			lua_pop(L, 1);
			entry.children.push_back(std::move(item));
			if (entry.children.size() >= 200000) { lua_pop(L, 1); break; }
		}
	} else if (t == LUA_TFUNCTION) {
		for (int i = 1;; ++i) {
			const char* n = lua_getupvalue(L, obj, i);
			if (!n) break;
			VariableItem item;
			item.name = n[0] ? n : ("(upvalue " + std::to_string(i) + ")");
			item.nameType = "string";
			MakeItem(L, -1, item, "");
			lua_pop(L, 1);
			entry.children.push_back(std::move(item));
		}
	}
	if (t == LUA_TTABLE && entry.providerId) {
		// 宿主认领的表（例如 UnLua 实例表）：自己的 Lua 字段照常列，再加一个分组放引擎对象的成员
		VariableItem group;
		group.name = "UE 对象";
		group.nameType = "string";
		group.type = "table";
		group.kind = "group";
		group.value = entry.hostSummary;
		group.ref = NewRef(RefKind::HostList, 0, entry.slot, "");
		refs_[group.ref].providerId = entry.providerId;
		entry.children.push_back(std::move(group));
	}
	if (t == LUA_TTABLE || t == LUA_TUSERDATA) {
		if (lua_getmetatable(L, obj)) {
			VariableItem item;
			item.name = "(metatable)";
			item.nameType = "string";
			MakeItem(L, -1, item, "");
			lua_pop(L, 1);
			entry.children.push_back(std::move(item));
		}
	}
	lua_settop(L, top);
}

// ---------------------------------------------------------------- 宿主值提供者（variable-view 13.6）

namespace {
// 调宿主期间屏蔽断点和单步：宿主可能回调 Lua（例如 UnLua 取实例表），不能在里面再停下来
struct HostCallGuard {
	bool& flag;
	bool prev;
	explicit HostCallGuard(bool& f) : flag(f), prev(f) { flag = true; }
	~HostCallGuard() { flag = prev; }
};

void NoteProvider(std::vector<uint64_t>& used, uint64_t id) {
	if (std::find(used.begin(), used.end(), id) == used.end()) used.push_back(id);
}
} // namespace

bool Inspector::HostDescribe(lua_State* L, int idx, VariableItem& item, const std::string& evaluateName) {
	// 宿主（UELink）只在暂停循环里、游戏线程上被调；暂停之外（条件断点、日志断点求值）一律不问宿主
	if (!pausedL_ || !L) return false;
	host::Described d;
	bool claimed;
	{
		HostCallGuard guard(evaluating_);
		const int64_t prevDeadline = evalDeadlineMs_;
		evalDeadlineMs_ = 0; // 宿主是 C++，不能从钩子里抛 Lua 错误穿过它
		claimed = host::Describe(L, idx, vm_.id(), pauseIdForHost_, 1024, d);
		evalDeadlineMs_ = prevDeadline;
	}
	if (!claimed) return false;
	NoteProvider(usedProviders_, d.providerId);
	item.typeName = d.typeName;
	item.kind = d.kind;
	item.hint = d.hint;
	if (!d.valid) {
		item.value = "<已失效>";
		item.attrs.push_back("invalid");
		return true;
	}
	item.value = d.summary.size() > 1024 ? d.summary.substr(0, 1024) : d.summary;
	const bool isTable = lua_type(L, idx) == LUA_TTABLE;
	if (isTable || d.expandable) {
		int slot = Anchor(L, idx);
		item.ref = NewRef(RefKind::Value, 0, slot, evaluateName);
		RefEntry& e = refs_[item.ref];
		e.providerId = d.providerId;
		e.hostSummary = item.value;
	}
	return true;
}

bool Inspector::IsHostListed(const RefEntry& entry) {
	if (entry.kind == RefKind::HostList) return true;
	if (entry.kind != RefKind::Value || !entry.providerId) return false;
	PushAnchored(pausedL_, entry.slot);
	const bool ud = lua_type(pausedL_, -1) == LUA_TUSERDATA;
	lua_pop(pausedL_, 1);
	return ud;
}

int64_t Inspector::HostChildren(lua_State* L, int idx, const RefEntry& entry, int start, int count, std::vector<VariableItem>& out) {
	json result;
	std::string err;
	bool ok;
	{
		HostCallGuard guard(evaluating_);
		const int64_t prevDeadline = evalDeadlineMs_;
		evalDeadlineMs_ = 0;
		ok = host::ListChildren(entry.providerId, L, idx, entry.hostRef, vm_.id(), pauseIdForHost_,
			static_cast<uint32_t>(start), static_cast<uint32_t>(count), 1024, result, err);
		evalDeadlineMs_ = prevDeadline;
	}
	NoteProvider(usedProviders_, entry.providerId);
	if (!ok) {
		VariableItem e;
		e.name = "(错误)";
		e.nameType = "string";
		e.type = "string";
		e.value = err;
		e.attrs.push_back("helperError");
		out.push_back(std::move(e));
		return 1;
	}
	if (!result.value("valid", true)) {
		VariableItem e;
		e.name = "(已失效)";
		e.nameType = "string";
		e.type = "nil";
		e.value = "<已失效>";
		e.attrs.push_back("invalid");
		out.push_back(std::move(e));
		return 1;
	}
	auto str = [](const json& j, const char* k) {
		auto it = j.find(k);
		return it != j.end() && it->is_string() ? it->get<std::string>() : std::string();
	};
	const json& children = result.contains("children") && result["children"].is_array() ? result["children"] : json::array();
	for (const json& c : children) {
		if (!c.is_object()) continue;
		VariableItem item;
		item.name = str(c, "name");
		item.nameType = "string";
		item.type = str(c, "type");
		if (item.type.empty()) item.type = "userdata";
		item.value = str(c, "value");
		item.typeName = str(c, "typeName");
		item.kind = str(c, "kind");
		item.hint = str(c, "hint");
		if (c.contains("attrs") && c["attrs"].is_array()) {
			for (const json& a : c["attrs"]) if (a.is_string()) item.attrs.push_back(a.get<std::string>());
		}
		if (c.contains("length") && c["length"].is_number_integer()) item.length = c["length"].get<int64_t>();
		const std::string childRef = str(c, "childRef");
		const bool expandable = c.value("expandable", false) || item.kind == "group";
		if (expandable && !childRef.empty()) {
			item.ref = NewRef(RefKind::HostList, 0, 0, "");
			RefEntry& e = refs_[item.ref];
			e.providerId = entry.providerId;
			e.hostRef = childRef;
		}
		out.push_back(std::move(item));
	}
	if (result.contains("total") && result["total"].is_number_integer()) return result["total"].get<int64_t>();
	return start + static_cast<int64_t>(children.size());
}

bool Inspector::Variables(uint64_t ref, int start, int count, const std::string& filter, json& body, ProtocolError& err,
	const json& expand) {
	if (!PageRef(ref, start, count, filter, body, err)) return false;
	if (view_.rich && expand.is_array() && !expand.empty()) {
		size_t budget = 256 * 1024;
		ExpandInline(body, expand, 0, budget);
	}
	return true;
}

bool Inspector::PageRef(uint64_t ref, int start, int count, const std::string& filter, json& body, ProtocolError& err) {
	auto it = refs_.find(ref);
	if (it == refs_.end()) {
		err = {"STALE_REFERENCE", "这个变量引用已失效（游戏已继续运行），请重新获取调用栈和变量。"};
		return false;
	}
	RefEntry& entry = it->second;
	if (start < 0) start = 0;
	if (count <= 0) count = 100;
	if (count > 1000) count = 1000;
	if (IsHostListed(entry)) {
		// 宿主列的子项不缓存：分页原样交给宿主（大 TArray、属性很多的类）
		lua_State* L = pausedL_;
		const int top = lua_gettop(L);
		lua_checkstack(L, 8);
		int idx = 0;
		if (entry.hostRef.empty()) {
			PushAnchored(L, entry.slot);
			idx = lua_gettop(L);
		}
		std::vector<VariableItem> items;
		int64_t total = HostChildren(L, idx, entry, start, count, items);
		lua_settop(L, top);
		json vars = json::array();
		for (const VariableItem& item : items) vars.push_back(item.ToJson());
		body = {{"variables", std::move(vars)}, {"total", total}};
		return true;
	}
	if (view_.rich && RichVariables(entry, start, count, filter, body)) return true;
	if (!entry.materialized) Materialize(entry);
	json vars = json::array();
	int64_t total = 0;
	for (const VariableItem& item : entry.children) {
		if (filter == "named" && item.indexed) continue;
		if (filter == "indexed" && !item.indexed) continue;
		if (total >= start && total < start + count) vars.push_back(item.ToJson());
		++total;
	}
	body = {{"variables", std::move(vars)}, {"total", total}};
	return true;
}

// evaluate / setVariable 的返回带上 13.2 的字段（有 richVariables 时）
void Inspector::RichEvalFields(const VariableItem& item, json& body) {
	json j = item.ToJson();
	for (const char* k : {"kind", "attrs", "objectId", "length", "location", "hint", "namedCount", "indexedCount"}) {
		auto it = j.find(k);
		if (it != j.end()) body[k] = *it;
	}
}

bool Inspector::Threads(json& body, ProtocolError& err) {
	lua_State* L = pausedL_;
	if (!L) {
		err = {"NOT_PAUSED", "Lua VM 正在运行，先暂停。"};
		return false;
	}
	json threads = json::array();
	lua_State* mainL = vm_.mainState();
	threads.push_back({{"threadId", "t" + PtrString(mainL).substr(2)}, {"name", "main"}, {"status", L == mainL ? "running" : "normal"}});
	lua::ForEachCoroutine(L, [&](lua_State* co) {
		const char* status;
		if (co == L) status = "running";
		else if (lua_status(co) == LUA_YIELD) status = "suspended";
		else {
			lua_Debug ar;
			if (lua_getstack(co, 0, &ar)) status = "normal";
			else status = lua_gettop(co) == 0 ? "dead" : "suspended";
		}
		threads.push_back({{"threadId", "t" + PtrString(co).substr(2)}, {"status", status}});
	});
	body = {{"threads", std::move(threads)}};
	return true;
}

// ---------------------------------------------------------------- 求值

bool Inspector::EvalTimedOut() {
	if (evaluating_ && evalDeadlineMs_ > 0 && NowMs() > evalDeadlineMs_) {
		evalDeadlineMs_ = 0; // 只报一次，避免错误处理本身再被打断
		return true;
	}
	return false;
}

bool Inspector::RunOnEvalThread(lua_State* L, int nargs, int timeoutMs, std::string& err) {
	// 栈：..., fn, args...  → 成功时 ..., result（只保留第一个返回值）
	// 在新协程里跑：钩子回调里 L 自己的钩子被禁用，新线程上的 COUNT 钩子才能做超时
	int base = lua_gettop(L) - nargs - 1;
	lua_State* co = lua_newthread(L);
	lua_insert(L, base + 1); // ..., co, fn, args
	lua_xmove(L, co, nargs + 1);
	lua_sethook(co, &HookMux::LuaHookEntry, LUA_MASKCOUNT, 1000);
	bool prevEvaluating = evaluating_;
	int64_t prevDeadline = evalDeadlineMs_;
	evaluating_ = true;
	evalDeadlineMs_ = timeoutMs > 0 ? NowMs() + timeoutMs : 0;
	int nres = 0;
	int status = lua::ResumeThread(co, L, nargs, &nres);
	evaluating_ = prevEvaluating;
	evalDeadlineMs_ = prevDeadline;
	bool ok = status == LUA_OK;
	if (!ok) {
		if (status == LUA_YIELD) err = "求值时不能 yield";
		else {
			const char* m = lua_tostring(co, -1);
			err = m ? m : "未知错误";
		}
		lua_settop(L, base);
		return false;
	}
	if (nres > 0) {
		lua_settop(co, 1);
		lua_xmove(co, L, 1);
	} else {
		lua_pushnil(L);
	}
	lua_remove(L, base + 1); // 移除 co
	return true;
}

bool Inspector::BuildEvalEnv(lua_State* L, lua_State* thread, int level) {
	lua_Debug ar;
	if (!lua_getstack(thread, level, &ar)) return false;
	lua_newtable(L);
	int env = lua_gettop(L);
	// upvalue 先放，局部变量后放（局部变量遮蔽同名 upvalue）
	lua_getinfo(thread, "fu", &ar);
	if (thread != L) lua_xmove(thread, L, 1);
	int fn = lua_gettop(L);
	for (int i = 1; i <= ar.nups; ++i) {
		const char* n = lua_getupvalue(L, fn, i);
		if (!n) break;
		if (n[0] && std::strcmp(n, "_ENV") != 0) lua_setfield(L, env, n);
		else lua_pop(L, 1);
	}
	lua_pop(L, 1);
	for (int i = 1;; ++i) {
		const char* n = lua_getlocal(thread, &ar, i);
		if (!n) break;
		if (thread != L) lua_xmove(thread, L, 1);
		if (n[0] != '(') lua_setfield(L, env, n);
		else lua_pop(L, 1);
	}
	// 其余名字回落到帧的 _ENV；赋值也写到 _ENV
	lua_createtable(L, 0, 2);
	PushFrameEnv(L, thread, ar);
	lua_pushvalue(L, -1);
	lua_setfield(L, -3, "__index");
	lua_setfield(L, -2, "__newindex");
	lua_setmetatable(L, env);
	return true;
}

bool Inspector::EvalLuaAt(lua_State* L, int level, const std::string& expr, int timeoutMs, std::string& err, int) {
	int top = lua_gettop(L);
	std::string code = "return " + expr;
	if (lua::LoadBuffer(L, code.data(), code.size(), "=(eval)") != LUA_OK) {
		lua_pop(L, 1);
		if (lua::LoadBuffer(L, expr.data(), expr.size(), "=(eval)") != LUA_OK) {
			const char* m = lua_tostring(L, -1);
			err = m ? m : "语法错误";
			lua_settop(L, top);
			return false;
		}
	}
	if (!BuildEvalEnv(L, L, level)) {
		err = "栈帧不存在";
		lua_settop(L, top);
		return false;
	}
	lua::SetFunctionEnv(L, -2);
	if (!RunOnEvalThread(L, 0, timeoutMs, err)) {
		lua_settop(L, top);
		return false;
	}
	return true;
}

bool Inspector::LookupRoot(lua_State* L, lua_State* thread, int level, const std::string& name) {
	lua_Debug ar;
	if (!lua_getstack(thread, level, &ar)) return false;
	int found = 0;
	for (int i = 1;; ++i) {
		const char* n = lua_getlocal(thread, &ar, i);
		if (!n) break;
		lua_pop(thread, 1);
		if (name == n) found = i;
	}
	if (found) {
		lua_getlocal(thread, &ar, found);
		if (thread != L) lua_xmove(thread, L, 1);
		return true;
	}
	lua_getinfo(thread, "fu", &ar);
	if (thread != L) lua_xmove(thread, L, 1);
	int fn = lua_gettop(L);
	for (int i = 1; i <= ar.nups; ++i) {
		const char* n = lua_getupvalue(L, fn, i);
		if (!n) break;
		if (name == n) {
			lua_remove(L, fn);
			return true;
		}
		lua_pop(L, 1);
	}
	lua_pop(L, 1);
	PushFrameEnv(L, thread, ar);
	lua_pushlstring(L, name.data(), name.size());
	lua_rawget(L, -2);
	lua_remove(L, -2);
	return true;
}

bool Inspector::IndexValue(lua_State* L, const PathSegment& seg, bool invokeIndex, std::string& err) {
	// 栈顶是容器；替换为取到的值
	int t = lua_type(L, -1);
	if (t != LUA_TTABLE && !(invokeIndex && t == LUA_TUSERDATA)) {
		err = t == LUA_TUSERDATA ? "userdata 的字段需要调用 __index，当前设置不允许（setOptions.valuePathInvokeIndex）"
								 : std::string("不能对 ") + lua_typename(L, t) + " 取字段";
		return false;
	}
	if (seg.kind == PathSegment::Kind::Integer) lua_pushinteger(L, static_cast<lua_Integer>(seg.integer));
	else lua_pushlstring(L, seg.text.data(), seg.text.size());
	if (!invokeIndex) {
		lua_rawget(L, -2);
	} else {
		// 通过 __index 取值：放到求值线程里，受超时保护
		lua_pushcfunction(L, [](lua_State* S) -> int {
			lua_gettable(S, 1);
			return 1;
		});
		lua_insert(L, -3); // fn, container, key
		if (!RunOnEvalThread(L, 2, 200, err)) return false;
		return true;
	}
	lua_remove(L, -2);
	return true;
}

bool Inspector::EvalValuePathAt(lua_State* L, int level, const std::string& expr, bool invokeIndex, std::string& err) {
	std::vector<PathSegment> segs;
	std::string code;
	if (!ParseValuePath(expr, segs, code)) {
		err = "valuePath 语法不对（" + code + "），只支持 a.b[1][\"c\"] 这类取值路径";
		return false;
	}
	int top = lua_gettop(L);
	if (!LookupRoot(L, L, level, segs[0].text)) {
		err = "栈帧不存在";
		lua_settop(L, top);
		return false;
	}
	for (size_t i = 1; i < segs.size(); ++i) {
		if (!IndexValue(L, segs[i], invokeIndex, err)) {
			lua_settop(L, top);
			return false;
		}
	}
	return true;
}

bool Inspector::Evaluate(uint64_t frameId, const std::string& expr, const std::string& mode, const EvalOptions& opt, json& body,
	ProtocolError& err) {
	lua_State* L = pausedL_;
	if (!L) {
		err = {"NOT_PAUSED", "Lua VM 正在运行，v3.0 只支持暂停时求值。"};
		return false;
	}
	FrameRef f{L, 0};
	if (frameId != 0) {
		lua_Debug ar;
		if (!GetFrame(frameId, f, ar, err)) return false;
	}
	int top = lua_gettop(L);
	std::string e;
	bool ok;
	if (mode == "lua") {
		if (!opt.allowLua) {
			err = {"EVAL_FORBIDDEN", "当前不允许执行任意 Lua，请在 IDE 设置中开启，或改用 valuePath。"};
			return false;
		}
		ok = EvalLuaAt(L, f.level, expr, opt.timeoutMs, e);
	} else {
		ok = EvalValuePathAt(L, f.level, expr, opt.invokeIndex, e);
		if (!ok && e.rfind("valuePath 语法", 0) == 0) {
			err = {"INVALID_ARGS", e};
			lua_settop(L, top);
			return false;
		}
	}
	if (!ok) {
		err = {e.find("超时") != std::string::npos ? "TIMEOUT" : "EVAL_ERROR", e};
		lua_settop(L, top);
		return false;
	}
	VariableItem item;
	MakeItem(L, -1, item, mode == "lua" ? "" : expr);
	if (lua_type(L, -1) == LUA_TSTRING && opt.maxLength > 1024) {
		size_t len;
		const char* s = lua_tolstring(L, -1, &len);
		item.value.clear();
		AppendEscaped(item.value, s, len, opt.maxLength);
	}
	lua_settop(L, top);
	body = {{"value", item.value}, {"type", item.type}, {"ref", item.ref}};
	if (!item.typeName.empty()) body["typeName"] = item.typeName;
	if (view_.rich) RichEvalFields(item, body);
	return true;
}

bool Inspector::SetVariable(uint64_t ref, const std::string& name, const std::string& value, const std::string& mode,
	const EvalOptions& opt, json& body, ProtocolError& err) {
	lua_State* L = pausedL_;
	auto it = refs_.find(ref);
	if (!L || it == refs_.end()) {
		err = {"STALE_REFERENCE", "这个变量引用已失效，请重新获取变量。"};
		return false;
	}
	RefEntry& entry = it->second;
	int top = lua_gettop(L);
	// 新值在哪个帧求值：作用域引用用它的帧，其他用栈顶帧
	int level = 0;
	lua_State* thread = L;
	if (entry.frameId) {
		lua_Debug ar;
		FrameRef f;
		if (!GetFrame(entry.frameId, f, ar, err)) return false;
		level = f.level;
		thread = f.thread;
	}
	std::string e;
	bool ok;
	if (mode == "lua") {
		if (!opt.allowLua) {
			err = {"EVAL_FORBIDDEN", "当前不允许执行任意 Lua，请在 IDE 设置中开启。"};
			return false;
		}
		ok = EvalLuaAt(L, level, value, opt.timeoutMs, e);
	} else {
		if (IsLiteral(value)) {
			std::string code = "return " + value;
			ok = lua::LoadBuffer(L, code.data(), code.size(), "=(literal)") == LUA_OK && lua_pcall(L, 0, 1, 0) == LUA_OK;
			if (!ok) e = lua_tostring(L, -1) ? lua_tostring(L, -1) : "字面量无效";
		} else {
			ok = EvalValuePathAt(L, level, value, opt.invokeIndex, e);
		}
	}
	if (!ok) {
		err = {"EVAL_ERROR", e};
		lua_settop(L, top);
		return false;
	}
	int valIdx = lua_gettop(L);
	bool done = false;
	if (entry.kind == RefKind::Locals || entry.kind == RefKind::Upvalues || entry.kind == RefKind::Varargs) {
		lua_Debug ar;
		lua_getstack(thread, level, &ar);
		if (entry.kind == RefKind::Locals) {
			int found = 0;
			for (int i = 1;; ++i) {
				const char* n = lua_getlocal(thread, &ar, i);
				if (!n) break;
				lua_pop(thread, 1);
				if (name == n) found = i;
			}
			if (found) {
				lua_pushvalue(L, valIdx);
				if (thread != L) lua_xmove(L, thread, 1);
				lua_setlocal(thread, &ar, found);
				done = true;
			}
		} else if (entry.kind == RefKind::Varargs) {
#if EMMY_LUA_VERSION_NUM >= 502
			if (name.size() > 2 && name.front() == '[' && name.back() == ']') {
				int n = std::atoi(name.substr(1, name.size() - 2).c_str());
				lua_pushvalue(L, valIdx);
				if (thread != L) lua_xmove(L, thread, 1);
				done = lua_setlocal(thread, &ar, -n) != nullptr;
				if (!done) lua_pop(thread, 1);
			}
#endif
		} else {
			lua_getinfo(thread, "fu", &ar);
			if (thread != L) lua_xmove(thread, L, 1);
			int fn = lua_gettop(L);
			for (int i = 1; i <= ar.nups; ++i) {
				const char* n = lua_getupvalue(L, fn, i);
				if (!n) break;
				lua_pop(L, 1);
				if (name == n) {
					lua_pushvalue(L, valIdx);
					lua_setupvalue(L, fn, i);
					done = true;
					break;
				}
			}
		}
	} else {
		PushAnchored(L, entry.slot);
		int obj = lua_gettop(L);
		if (lua_type(L, obj) == LUA_TTABLE) {
			if (name.size() > 2 && name.front() == '[' && name.back() == ']') {
				std::string inner = name.substr(1, name.size() - 2);
				char* endp = nullptr;
				double d = std::strtod(inner.c_str(), &endp);
				if (endp && *endp == '\0') {
					if (d == static_cast<double>(static_cast<int64_t>(d))) lua_pushinteger(L, static_cast<lua_Integer>(d));
					else lua_pushnumber(L, d);
				} else {
					lua_pushlstring(L, inner.data(), inner.size());
				}
			} else {
				lua_pushlstring(L, name.data(), name.size());
			}
			lua_pushvalue(L, valIdx);
			lua_rawset(L, obj);
			done = true;
		} else if (lua_type(L, obj) == LUA_TFUNCTION) {
			for (int i = 1;; ++i) {
				const char* n = lua_getupvalue(L, obj, i);
				if (!n) break;
				lua_pop(L, 1);
				if (name == n) {
					lua_pushvalue(L, valIdx);
					lua_setupvalue(L, obj, i);
					done = true;
					break;
				}
			}
		}
	}
	if (!done) {
		lua_settop(L, top);
		err = {"INVALID_ARGS", "找不到要修改的变量 " + name + "。"};
		return false;
	}
	VariableItem item;
	MakeItem(L, valIdx, item, "");
	lua_settop(L, top);
	// 缓存的子项已经过时，下次重新取
	entry.materialized = false;
	entry.keysBuilt = false;
	entry.richBuilt = false;
	entry.richChildren.clear();
	entry.groups.clear();
	body = {{"value", item.value}, {"type", item.type}, {"ref", item.ref}};
	if (!item.typeName.empty()) body["typeName"] = item.typeName;
	if (view_.rich) RichEvalFields(item, body);
	return true;
}

// ---------------------------------------------------------------- 条件断点

bool Inspector::EvalCondition(lua_State* L, uint64_t serial, const std::string& expr, int timeoutMs, bool& result, std::string& err) {
	int top = lua_gettop(L);
	if (condCacheRef_ == LUA_NOREF) {
		lua_newtable(L);
		condCacheRef_ = luaL_ref(L, LUA_REGISTRYINDEX);
	}
	lua_rawgeti(L, LUA_REGISTRYINDEX, condCacheRef_);
	int cache = lua_gettop(L);
	lua_pushnumber(L, static_cast<lua_Number>(serial));
	lua_rawget(L, cache);
	if (lua_type(L, -1) != LUA_TFUNCTION) {
		lua_pop(L, 1);
		std::string code = "return " + expr;
		if (lua::LoadBuffer(L, code.data(), code.size(), "=(condition)") != LUA_OK) {
			err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "条件表达式语法错误";
			lua_settop(L, top);
			return false;
		}
		lua_pushnumber(L, static_cast<lua_Number>(serial));
		lua_pushvalue(L, -2);
		lua_rawset(L, cache);
	}
	if (!BuildEvalEnv(L, L, 0)) {
		lua_settop(L, top);
		err = "栈帧不存在";
		return false;
	}
	lua::SetFunctionEnv(L, -2);
	if (!RunOnEvalThread(L, 0, timeoutMs, err)) {
		lua_settop(L, top);
		return false;
	}
	result = lua_toboolean(L, -1) != 0;
	lua_settop(L, top);
	return true;
}

void Inspector::ResetConditionCache(lua_State* L) {
	if (condCacheRef_ != LUA_NOREF) {
		luaL_unref(L, LUA_REGISTRYINDEX, condCacheRef_);
		condCacheRef_ = LUA_NOREF;
	}
}

} // namespace emmy
