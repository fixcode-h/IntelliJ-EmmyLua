#include "inspector.h"

#include "core.h"
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

namespace {

int64_t NowMs() {
	return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string PtrString(const void* p) {
	char buf[32];
	std::snprintf(buf, sizeof(buf), "0x%" PRIxPTR, reinterpret_cast<uintptr_t>(p));
	return buf;
}

void AppendEscaped(std::string& out, const char* s, size_t len, size_t maxLen) {
	out += '"';
	for (size_t i = 0; i < len; ++i) {
		if (out.size() >= maxLen) {
			out += "…";
			return;
		}
		char c = s[i];
		switch (c) {
		case '"': out += "\\\""; break;
		case '\\': out += "\\\\"; break;
		case '\n': out += "\\n"; break;
		case '\r': out += "\\r"; break;
		case '\t': out += "\\t"; break;
		default:
			if (static_cast<unsigned char>(c) < 0x20) {
				char buf[8];
				std::snprintf(buf, sizeof(buf), "\\x%02x", static_cast<unsigned char>(c));
				out += buf;
			} else {
				out += c;
			}
		}
	}
	out += '"';
}

bool IsIdentifier(const char* s, size_t len) {
	if (len == 0) return false;
	if (!(std::isalpha(static_cast<unsigned char>(s[0])) || s[0] == '_')) return false;
	for (size_t i = 1; i < len; ++i) {
		if (!(std::isalnum(static_cast<unsigned char>(s[i])) || s[i] == '_')) return false;
	}
	return true;
}

// metatable.__name（5.3+ luaL_newmetatable 会设置；UnLua 等也常用）
std::string MetaName(lua_State* L, int idx) {
	idx = lua::AbsIndex(L, idx);
	std::string name;
	if (lua_getmetatable(L, idx)) {
		lua_pushstring(L, "__name");
		lua_rawget(L, -2);
		if (lua_type(L, -1) == LUA_TSTRING) name = lua_tostring(L, -1);
		lua_pop(L, 2);
	}
	return name;
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
	return j;
}

Inspector::Inspector(Vm& vm) : vm_(vm) {}

// ---------------------------------------------------------------- 暂停生命周期

void Inspector::BeginPause(lua_State* L) {
	pausedL_ = L;
	lua_newtable(L);
	anchorRef_ = luaL_ref(L, LUA_REGISTRYINDEX);
	nextSlot_ = 1;
	frames_.clear();
	refs_.clear();
	ids_.clear();
	frameOrder_.clear();
}

void Inspector::EndPause(lua_State* L) {
	if (anchorRef_ != LUA_NOREF) {
		luaL_unref(L, LUA_REGISTRYINDEX, anchorRef_);
		anchorRef_ = LUA_NOREF;
	}
	Core::Get().ReleaseObjectIds(ids_);
	ids_.clear();
	frames_.clear();
	refs_.clear();
	frameOrder_.clear();
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
	idx = lua::AbsIndex(L, idx);
	lua_rawgeti(L, LUA_REGISTRYINDEX, anchorRef_);
	lua_pushvalue(L, idx);
	int slot = nextSlot_++;
	lua_rawseti(L, -2, slot);
	lua_pop(L, 1);
	return slot;
}

void Inspector::PushAnchored(lua_State* L, int slot) {
	lua_rawgeti(L, LUA_REGISTRYINDEX, anchorRef_);
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

bool Inspector::StackTrace(int start, int count, json& body, ProtocolError& err) {
	lua_State* L = pausedL_;
	if (!L) {
		err = {"NOT_PAUSED", "Lua VM 正在运行，先暂停或等断点命中。"};
		return false;
	}
	if (start < 0) start = 0;
	if (count <= 0) count = 20;
	if (count > 200) count = 200;
	json frames = json::array();
	int total = 0;
	lua_Debug ar;
	for (int level = 0; lua_getstack(L, level, &ar); ++level) {
		++total;
		if (level < start || level >= start + count) continue;
		lua_getinfo(L, "nSl", &ar);
		json f;
		f["frameId"] = NewFrameId(L, level);
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
	if (!L || !lua_getstack(L, 0, &ar)) return nullptr;
	lua_getinfo(L, "nSl", &ar);
	json f;
	f["frameId"] = NewFrameId(L, 0);
	f["name"] = ar.name ? ar.name : "?";
	f["chunkName"] = ar.source ? ar.source : "=?";
	f["line"] = ar.currentline;
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
	idx = lua::AbsIndex(L, idx);
	int t = lua_type(L, idx);
	item.type = lua_typename(L, t);
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

bool Inspector::Variables(uint64_t ref, int start, int count, const std::string& filter, json& body, ProtocolError& err) {
	auto it = refs_.find(ref);
	if (it == refs_.end()) {
		err = {"STALE_REFERENCE", "这个变量引用已失效（游戏已继续运行），请重新获取调用栈和变量。"};
		return false;
	}
	RefEntry& entry = it->second;
	if (!entry.materialized) Materialize(entry);
	if (start < 0) start = 0;
	if (count <= 0) count = 100;
	if (count > 1000) count = 1000;
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
	body = {{"value", item.value}, {"type", item.type}, {"ref", item.ref}};
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
