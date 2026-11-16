// richVariables（docs/design/variable-view.md 第 3–7、10、13、14 节）：
// 变量的形态（kind）、类名推断、智能排序、键快照分页、方法 / 继承 / 元表 / 内置分组。
// 只用 rawget / lua_next / rawlen / lua_getinfo / lua_getlocal / lua_getupvalue，不触发元方法，不执行目标进程的代码。
#include "inspector.h"

#include "host_value.h"
#include "inspector_util.h"
#include "vm.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_set>

namespace emmy {

using namespace detail;

namespace {

// 一页 + 分组合计的字节上限（variable-view 10.3）
constexpr size_t kResponseBudget = 256 * 1024;
// 单个表最多收多少个键（防止百万级表把暂停卡住；超出部分不显示）
constexpr size_t kMaxKeys = 200000;
// __index 链最多走几层（6.2）
constexpr int kMaxInheritDepth = 16;

const char* const kKeywords[] = {
	"and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
	"local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
};

const char* const kMetamethods[] = {
	"__index", "__newindex", "__call", "__tostring", "__len", "__eq", "__lt", "__le", "__add", "__sub",
	"__mul", "__div", "__mod", "__pow", "__unm", "__idiv", "__band", "__bor", "__bxor", "__shl", "__shr",
	"__bnot", "__concat", "__gc", "__mode", "__close", "__metatable", "__name", "__pairs", "__ipairs",
};

// 7.4：标准库和框架全局，收进「内置」分组
const char* const kBuiltinGlobals[] = {
	"_G", "_VERSION", "_ENV", "string", "table", "math", "coroutine", "io", "os", "debug", "package", "utf8",
	"bit32", "bit", "jit", "ffi", "assert", "collectgarbage", "dofile", "error", "getmetatable", "ipairs",
	"load", "loadfile", "loadstring", "next", "pairs", "pcall", "print", "rawequal", "rawget", "rawlen",
	"rawset", "require", "select", "setmetatable", "tonumber", "tostring", "type", "unpack", "xpcall",
	"module", "setfenv", "getfenv", "gcinfo", "newproxy", "warn", "UE", "UE4", "UnLua", "emmy", "emmyHelper",
	"emmyHelperInit",
};

// 6.3：常见 OO 库放类名的字段
const char* const kClassNameFields[] = {"__cname", "__className", "className", "__type"};

bool InList(const char* const* list, size_t n, const char* s, size_t len) {
	for (size_t i = 0; i < n; ++i) {
		if (std::strlen(list[i]) == len && std::memcmp(list[i], s, len) == 0) return true;
	}
	return false;
}

template <size_t N>
bool InList(const char* const (&list)[N], const char* s, size_t len) {
	return InList(list, N, s, len);
}

bool IsKeyword(const char* s, size_t len) { return InList(kKeywords, s, len); }

bool IsMetamethod(const std::string& s) { return InList(kMetamethods, s.data(), s.size()); }

// 字符串摘要：转义、按字节上限截断（不切开 UTF-8 字符），截断时末尾写总长（4.2）
std::string QuoteString(const char* s, size_t len, size_t maxLen) {
	std::string out = "\"";
	bool cut = false;
	for (size_t i = 0; i < len && !cut; ++i) {
		if (out.size() >= maxLen) {
			cut = true;
			break;
		}
		const unsigned char c = static_cast<unsigned char>(s[i]);
		switch (c) {
		case '"': out += "\\\""; break;
		case '\\': out += "\\\\"; break;
		case '\n': out += "\\n"; break;
		case '\r': out += "\\r"; break;
		case '\t': out += "\\t"; break;
		default:
			if (c < 0x20 || c == 0x7f) {
				char buf[8];
				std::snprintf(buf, sizeof(buf), "\\x%02X", c);
				out += buf;
			} else if (c >= 0x80) {
				// 整个 UTF-8 字符一起放，放不下就停
				size_t n = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
				if (i + n > len) n = len - i;
				if (out.size() + n > maxLen) {
					cut = true;
					break;
				}
				out.append(s + i, n);
				i += n - 1;
			} else {
				out += static_cast<char>(c);
			}
		}
	}
	if (cut) out += "…\"（共 " + std::to_string(len) + " 字节）";
	else out += '"';
	return out;
}

void Truncate(std::string& s, size_t maxLen) {
	if (s.size() <= maxLen) return;
	size_t cut = maxLen;
	while (cut > 0 && (static_cast<unsigned char>(s[cut]) & 0xC0) == 0x80) --cut; // 不切开 UTF-8 字符
	s.resize(cut);
	s += "…";
}

// 自然排序、不分大小写；完全相同时大写在前（5.1）
int NaturalCompare(const std::string& a, const std::string& b) {
	size_t i = 0, j = 0;
	while (i < a.size() && j < b.size()) {
		const unsigned char ca = static_cast<unsigned char>(a[i]);
		const unsigned char cb = static_cast<unsigned char>(b[j]);
		if (std::isdigit(ca) && std::isdigit(cb)) {
			size_t si = i, sj = j;
			while (si < a.size() && a[si] == '0') ++si;
			while (sj < b.size() && b[sj] == '0') ++sj;
			size_t ei = si, ej = sj;
			while (ei < a.size() && std::isdigit(static_cast<unsigned char>(a[ei]))) ++ei;
			while (ej < b.size() && std::isdigit(static_cast<unsigned char>(b[ej]))) ++ej;
			if (ei - si != ej - sj) return ei - si < ej - sj ? -1 : 1;
			int c = a.compare(si, ei - si, b, sj, ej - sj);
			if (c) return c < 0 ? -1 : 1;
			i = ei;
			j = ej;
			continue;
		}
		const int la = std::tolower(ca), lb = std::tolower(cb);
		if (la != lb) return la < lb ? -1 : 1;
		++i;
		++j;
	}
	if (i < a.size()) return 1;
	if (j < b.size()) return -1;
	return a < b ? -1 : (a == b ? 0 : 1);
}

// 排序用的键信息
struct SortKey {
	int cat = 4; // 0 标识符 / 1 其他字符串 / 2 数字 / 3 boolean / 4 其他
	std::string s;
	double num = 0;
	const void* ptr = nullptr;
	size_t pos = 0; // 原 keys 里的位置
};

bool SmartLess(const SortKey& a, const SortKey& b, int mode) {
	if (a.cat != b.cat) return a.cat < b.cat;
	switch (a.cat) {
	case 0:
		if (mode == 0) {
			int c = NaturalCompare(a.s, b.s);
			if (c) return c < 0;
			return a.pos < b.pos;
		}
		return a.s < b.s;
	case 1: return a.s < b.s;
	case 2:
		if (a.num != b.num) return a.num < b.num;
		return a.pos < b.pos;
	case 3: return a.num < b.num;
	default: return std::less<const void*>()(a.ptr, b.ptr);
	}
}

std::string BaseName(const char* path) {
	const char* p = path;
	for (const char* c = path; *c; ++c) {
		if (*c == '/' || *c == '\\') p = c + 1;
	}
	return p;
}

std::string LastSegment(const std::string& name) {
	size_t p = name.find_last_of("./");
	return p == std::string::npos ? name : name.substr(p + 1);
}

std::string ChildEval(const std::string& parent, bool root, lua_State* L, int keyIdx) {
	if (parent.empty() && !root) return "";
	const int kt = lua_type(L, keyIdx);
	if (kt == LUA_TSTRING) {
		size_t kl;
		const char* ks = lua_tolstring(L, keyIdx, &kl);
		if (IsIdentifier(ks, kl) && !IsKeyword(ks, kl)) return root ? std::string(ks, kl) : parent + "." + std::string(ks, kl);
		if (root) return "";
		std::string out = parent + "[";
		AppendEscaped(out, ks, kl, 4096);
		return out + "]";
	}
	if (kt == LUA_TNUMBER && !root) {
		lua_Number k = lua_tonumber(L, keyIdx);
		if (k == static_cast<lua_Number>(static_cast<int64_t>(k))) {
			return parent + "[" + std::to_string(static_cast<int64_t>(k)) + "]";
		}
	}
	return "";
}

// 只读 rawget 一个字符串字段，栈不变
std::string RawGetString(lua_State* L, int t, const char* field) {
	std::string out;
	lua_pushstring(L, field);
	lua_rawget(L, t);
	if (lua_type(L, -1) == LUA_TSTRING) out = lua_tostring(L, -1);
	lua_pop(L, 1);
	return out;
}

} // namespace

// ---------------------------------------------------------------- 类名推断（6.3）

const std::string& Inspector::LoadedName(lua_State* L, int idx) {
	static const std::string kEmpty;
	idx = lua::AbsIndex(L, idx);
	if (!loadedBuilt_) {
		loadedBuilt_ = true;
		lua_getfield(L, LUA_REGISTRYINDEX, "_LOADED");
		if (lua_type(L, -1) == LUA_TTABLE) {
			const int loaded = lua_gettop(L);
			lua_pushnil(L);
			size_t n = 0;
			while (lua_next(L, loaded) && ++n < 100000) {
				if (lua_type(L, -2) == LUA_TSTRING && lua_type(L, -1) == LUA_TTABLE) {
					std::string name = lua_tostring(L, -2);
					if (name != "_G") loadedNames_.emplace(lua_topointer(L, -1), std::move(name));
				}
				lua_pop(L, 1);
			}
		}
		lua_pop(L, 1);
	}
	auto it = loadedNames_.find(lua_topointer(L, idx));
	return it == loadedNames_.end() ? kEmpty : it->second;
}

std::string Inspector::ClassName(lua_State* L, int idx) {
	idx = lua::AbsIndex(L, idx);
	if (!lua_getmetatable(L, idx)) return "";
	const int mt = lua_gettop(L);
	const void* key = lua_topointer(L, mt);
	auto it = classNames_.find(key);
	if (it != classNames_.end()) {
		lua_pop(L, 1);
		return it->second;
	}
	std::string name = RawGetString(L, mt, "__name");
	lua_pushstring(L, "__index");
	lua_rawget(L, mt);
	const int index = lua_gettop(L);
	const bool indexTable = lua_type(L, index) == LUA_TTABLE;
	for (const char* f : kClassNameFields) {
		if (!name.empty()) break;
		name = RawGetString(L, mt, f);
		if (name.empty() && indexTable) name = RawGetString(L, index, f);
	}
	if (name.empty()) name = LastSegment(LoadedName(L, mt));
	if (name.empty() && indexTable) name = LastSegment(LoadedName(L, index));
	lua_pop(L, 2);
	classNames_.emplace(key, name);
	return name;
}

// ---------------------------------------------------------------- 摘要

std::string Inspector::NestedSummary(lua_State* L, int idx) {
	idx = lua::AbsIndex(L, idx);
	const int t = lua_type(L, idx);
	switch (t) {
	case LUA_TSTRING: {
		size_t len;
		const char* s = lua_tolstring(L, idx, &len);
		std::string out;
		AppendEscaped(out, s, len, 32);
		if (out.back() != '"') out += '"';
		return out;
	}
	case LUA_TNUMBER: return FormatNumber(L, idx);
	case LUA_TTABLE: {
		std::string cn = ClassName(L, idx);
		if (!cn.empty()) return cn + "{…}";
		size_t n = lua::RawLen(L, idx);
		if (n > 0) return "#" + std::to_string(n);
		return "{…}";
	}
	case LUA_TFUNCTION: return "ƒ";
	case LUA_TUSERDATA: {
		std::string mn = MetaName(L, idx);
		return mn.empty() ? "userdata" : mn;
	}
	default: return Summarize(L, idx, 32);
	}
}

std::string Inspector::RichKeyName(lua_State* L, int keyIdx) {
	keyIdx = lua::AbsIndex(L, keyIdx);
	const int kt = lua_type(L, keyIdx);
	if (kt == LUA_TSTRING) {
		size_t kl;
		const char* ks = lua_tolstring(L, keyIdx, &kl);
		if (IsIdentifier(ks, kl) && !IsKeyword(ks, kl)) return std::string(ks, kl);
		std::string out = "[";
		AppendEscaped(out, ks, kl, 256);
		if (out.back() != '"') out += '"';
		return out + "]";
	}
	if (kt == LUA_TNUMBER || kt == LUA_TBOOLEAN) return "[" + Summarize(L, keyIdx, 64) + "]";
	std::string label;
	if (kt == LUA_TTABLE) {
		label = ClassName(L, keyIdx);
		if (label.empty()) label = "table";
	} else if (kt == LUA_TUSERDATA) {
		label = MetaName(L, keyIdx);
		if (label.empty()) label = "userdata";
	} else {
		label = lua_typename(L, kt);
	}
	return "[" + label + " " + PtrString(lua_topointer(L, keyIdx)) + "]";
}

void Inspector::RichFunction(lua_State* L, int idx, VariableItem& item, const std::string& field, const std::string& owner) {
	idx = lua::AbsIndex(L, idx);
	item.objectId = PtrString(lua_topointer(L, idx));
	lua_Debug ar;
	lua_pushvalue(L, idx);
	lua_getinfo(L, ">Su", &ar);
	const bool isC = lua_iscfunction(L, idx) != 0;
	item.kind = isC ? "cfunction" : "function";
	if (isC) {
		item.value = field.empty() ? "C function " + item.objectId : "C function " + field;
	} else {
		std::vector<std::string> params;
		bool vararg = false;
		bool known = false;
#if EMMY_LUA_VERSION_NUM >= 502
		known = true;
		vararg = ar.isvararg != 0;
		lua_pushvalue(L, idx);
		for (int i = 1; i <= ar.nparams && i <= 32; ++i) {
			const char* n = lua_getlocal(L, nullptr, i);
			params.emplace_back(n ? n : "?");
		}
		lua_pop(L, 1);
#endif
		std::string name = field;
		if (!name.empty() && !owner.empty()) {
			if (!params.empty() && params.front() == "self") {
				name = owner + ":" + name;
				params.erase(params.begin());
			} else {
				name = owner + "." + name;
			}
		}
		std::string out = "function";
		if (!name.empty()) out += " " + name;
		out += "(";
		if (!known) {
			out += "…";
		} else {
			for (size_t i = 0; i < params.size(); ++i) {
				if (i) out += ", ";
				out += params[i];
			}
			if (vararg) out += params.empty() ? "..." : ", ...";
		}
		out += ")";
		item.value = std::move(out);
		if (ar.source && ar.linedefined > 0) {
			item.locationChunk = ar.source;
			item.locationLine = ar.linedefined;
		}
	}
	if (ar.nups > 0) {
		item.ref = NewRef(RefKind::Value, 0, Anchor(L, idx), "");
	}
}

void Inspector::RichTable(lua_State* L, int idx, VariableItem& item) {
	idx = lua::AbsIndex(L, idx);
	item.objectId = PtrString(lua_topointer(L, idx));
	const size_t n = lua::RawLen(L, idx);
	// 一遍 lua_next：数量、函数个数、摘要候选字段（最多 16 个，排序后取 5 个）
	int64_t total = 0, inArray = 0, funcs = 0;
	struct Sample { SortKey key; std::string text; };
	std::vector<Sample> samples;
	lua_pushnil(L);
	while (lua_next(L, idx)) {
		++total;
		const int kt = lua_type(L, -2);
		bool arrayKey = false;
		if (kt == LUA_TNUMBER && n > 0) {
			lua_Number k = lua_tonumber(L, -2);
			arrayKey = k >= 1 && k <= static_cast<lua_Number>(n) && k == static_cast<lua_Number>(static_cast<int64_t>(k));
		}
		if (arrayKey) {
			++inArray;
		} else if (lua_type(L, -1) == LUA_TFUNCTION) {
			++funcs;
		} else if (samples.size() < 16) {
			Sample s;
			if (kt == LUA_TSTRING) {
				size_t kl;
				const char* ks = lua_tolstring(L, -2, &kl);
				s.key.s.assign(ks, kl);
				s.key.cat = IsIdentifier(ks, kl) && !IsKeyword(ks, kl) ? 0 : 1;
			} else if (kt == LUA_TNUMBER) {
				s.key.cat = 2;
				s.key.num = lua_tonumber(L, -2);
			} else {
				s.key.cat = kt == LUA_TBOOLEAN ? 3 : 4;
			}
			s.key.pos = samples.size();
			s.text = (s.key.cat == 0 ? s.key.s : RichKeyName(L, -2)) + "=" + NestedSummary(L, -1);
			samples.push_back(std::move(s));
		}
		lua_pop(L, 1);
		if (total >= 1000000) {
			lua_pop(L, 1);
			break;
		}
	}
	const int64_t named = total - inArray;
	item.indexedCount = static_cast<int64_t>(n);
	item.namedCount = named;

	// 形态（4.1）
	lua_pushstring(L, "__index");
	lua_rawget(L, idx);
	const bool selfIndex = lua_rawequal(L, -1, idx) != 0;
	lua_pop(L, 1);
	const std::string& loaded = LoadedName(L, idx);
	bool indexIsTable = false;
	if (lua_getmetatable(L, idx)) {
		lua_pushstring(L, "__index");
		lua_rawget(L, -2);
		indexIsTable = lua_type(L, -1) == LUA_TTABLE;
		lua_pop(L, 2);
	}
	auto fields = [&](std::string& out) {
		std::sort(samples.begin(), samples.end(), [&](const Sample& a, const Sample& b) { return SmartLess(a.key, b.key, 0); });
		out += "{";
		size_t shown = 0;
		for (size_t i = 1; i <= n && shown < 5; ++i) {
			lua_rawgeti(L, idx, static_cast<lua_Integer>(i));
			if (!lua_isnil(L, -1)) {
				if (shown++) out += ", ";
				out += NestedSummary(L, -1);
			}
			lua_pop(L, 1);
		}
		for (const Sample& s : samples) {
			if (shown >= 5) break;
			if (shown++) out += ", ";
			out += s.text;
		}
		if (static_cast<int64_t>(shown) < total - funcs || (shown == 0 && total > 0)) out += shown ? ", …" : "…";
		out += "}";
	};
	std::string cn;
	if (selfIndex || (!loaded.empty() && named > 0 && funcs * 2 >= named)) {
		item.kind = "class";
		for (const char* f : kClassNameFields) {
			if (!cn.empty()) break;
			cn = RawGetString(L, idx, f);
		}
		if (cn.empty()) cn = LastSegment(loaded);
		item.value = "class " + (cn.empty() ? std::string("table") : cn) + " (" + std::to_string(funcs) + " 个方法)";
	} else if (!loaded.empty()) {
		item.kind = "module";
		cn = loaded;
		item.value = "module " + loaded;
	} else if (indexIsTable) {
		item.kind = "object";
		cn = ClassName(L, idx);
		std::string out = cn;
		fields(out);
		item.value = std::move(out);
	} else {
		cn = ClassName(L, idx); // 只有 __name 之类的元表也算类型名
		if (n > 0 && named == 0) {
			item.kind = "array";
		} else {
			item.kind = "record";
		}
		std::string out = cn;
		if (total == 0) out += "{}";
		else fields(out);
		item.value = std::move(out);
	}
	item.typeName = cn;
	Truncate(item.value, view_.maxValueLength);
	bool hasMeta = lua_getmetatable(L, idx) != 0;
	if (hasMeta) lua_pop(L, 1);
	if (total > 0 || hasMeta) {
		item.ref = NewRef(RefKind::Value, 0, Anchor(L, idx), item.evaluateName);
		RefEntry& e = refs_[item.ref];
		e.owner = item.kind == "module" ? LastSegment(cn) : cn;
	}
}

void Inspector::MakeItemRich(lua_State* L, int idx, VariableItem& item, const std::string& evaluateName,
	const std::string& field, const std::string& owner) {
	idx = lua::AbsIndex(L, idx);
	lua_checkstack(L, 16);
	const int t = lua_type(L, idx);
	item.type = lua_typename(L, t);
	// 宿主（引擎）认领的对象：摘要和子项都听宿主的
	if ((t == LUA_TUSERDATA || t == LUA_TTABLE) && host::HasProviders() && HostDescribe(L, idx, item, evaluateName)) {
		if (item.objectId.empty()) item.objectId = PtrString(lua_topointer(L, idx));
		return;
	}
	item.evaluateName = evaluateName;
	switch (t) {
	case LUA_TNIL: item.value = "nil"; break;
	case LUA_TBOOLEAN: item.value = lua_toboolean(L, idx) ? "true" : "false"; break;
	case LUA_TNUMBER:
		item.value = FormatNumber(L, idx);
#if EMMY_LUA_VERSION_NUM >= 503
		item.kind = lua::IsInteger(L, idx) ? "integer" : "float";
#endif
		break;
	case LUA_TSTRING: {
		size_t len;
		const char* s = lua_tolstring(L, idx, &len);
		item.value = QuoteString(s, len, view_.maxValueLength);
		item.length = static_cast<int64_t>(len);
		break;
	}
	case LUA_TTABLE: RichTable(L, idx, item); break;
	case LUA_TFUNCTION: RichFunction(L, idx, item, field, owner); break;
	case LUA_TTHREAD: {
		lua_State* co = lua_tothread(L, idx);
		item.kind = "coroutine";
		item.objectId = PtrString(co);
		const char* status;
		if (co == L) status = "running";
		else if (lua_status(co) == LUA_YIELD) status = "suspended";
		else {
			lua_Debug ar;
			if (lua_getstack(co, 0, &ar)) status = "normal";
			else status = lua_gettop(co) == 0 ? "dead" : "suspended";
		}
		std::string out = status;
		lua_Debug ar;
		if (co != L && std::strcmp(status, "dead") != 0 && lua_getstack(co, 0, &ar) && lua_getinfo(co, "Sl", &ar)) {
			if (ar.source && ar.currentline > 0) {
				item.locationChunk = ar.source;
				item.locationLine = ar.currentline;
				out += "  " + BaseName(ar.short_src) + ":" + std::to_string(ar.currentline);
			}
		}
		item.value = std::move(out);
		break;
	}
	case LUA_TUSERDATA: {
		item.kind = "userdata";
		item.objectId = PtrString(lua_touserdata(L, idx));
		std::string mn = MetaName(L, idx);
		item.typeName = mn;
		item.value = (mn.empty() ? std::string("userdata") : mn) + " " + item.objectId;
		if (lua_getmetatable(L, idx)) {
			lua_pop(L, 1);
			item.ref = NewRef(RefKind::Value, 0, Anchor(L, idx), evaluateName);
		}
		break;
	}
	case LUA_TLIGHTUSERDATA: item.value = PtrString(lua_touserdata(L, idx)); break;
	default: item.value = lua_typename(L, t); break;
	}
}

// ---------------------------------------------------------------- 键快照和分组

void Inspector::SortKeys(lua_State* L, std::vector<RichKey>& keys, bool& sorted) {
	if (view_.sort == 2 || keys.size() < 2) return;
	if (static_cast<int>(keys.size()) > view_.maxSortKeys) {
		sorted = false;
		return;
	}
	std::vector<SortKey> sk(keys.size());
	for (size_t i = 0; i < keys.size(); ++i) {
		SortKey& k = sk[i];
		k.pos = i;
		PushAnchored(L, keys[i].keySlot);
		const int kt = lua_type(L, -1);
		if (kt == LUA_TSTRING) {
			size_t kl;
			const char* ks = lua_tolstring(L, -1, &kl);
			k.s.assign(ks, kl);
			k.cat = IsIdentifier(ks, kl) && !IsKeyword(ks, kl) ? 0 : 1;
		} else if (kt == LUA_TNUMBER) {
			k.cat = 2;
			k.num = lua_tonumber(L, -1);
		} else if (kt == LUA_TBOOLEAN) {
			k.cat = 3;
			k.num = lua_toboolean(L, -1);
		} else {
			k.ptr = lua_topointer(L, -1);
		}
		lua_pop(L, 1);
	}
	// 元方法排最前（只在元表分组里有这个标记）
	const int mode = view_.sort;
	std::stable_sort(sk.begin(), sk.end(), [&](const SortKey& a, const SortKey& b) {
		const bool ma = keys[a.pos].attrs & kAttrMetamethod, mb = keys[b.pos].attrs & kAttrMetamethod;
		if (ma != mb) return ma;
		return SmartLess(a, b, mode);
	});
	std::vector<RichKey> out;
	out.reserve(keys.size());
	for (const SortKey& k : sk) out.push_back(std::move(keys[k.pos]));
	keys.swap(out);
}

uint64_t Inspector::NewGroup(int sourceSlot, std::vector<RichKey> keys, bool sorted) {
	uint64_t ref = NewRef(RefKind::Group, 0, sourceSlot, "");
	RefEntry& e = refs_[ref];
	e.keys = std::move(keys);
	e.keysBuilt = true;
	e.sorted = sorted;
	return ref;
}

namespace {

VariableItem GroupItem(const std::string& name, const std::string& value, uint64_t ref, int64_t count) {
	VariableItem g;
	g.name = name;
	g.nameType = "string";
	g.type = "table";
	g.kind = "group";
	g.value = value;
	g.ref = ref;
	if (count >= 0) g.namedCount = count;
	return g;
}

} // namespace

// 收集 t 的非数组键（数组段由调用方处理）。funcs 收函数值的键，data 收其他
static void CollectFields(lua_State* L, int t, size_t arrayLen, const std::function<void(int keyIdx, bool isFunc)>& fn) {
	lua_pushnil(L);
	size_t seen = 0;
	while (lua_next(L, t)) {
		if (lua_type(L, -2) == LUA_TNUMBER && arrayLen > 0) {
			lua_Number k = lua_tonumber(L, -2);
			if (k >= 1 && k <= static_cast<lua_Number>(arrayLen) && k == static_cast<lua_Number>(static_cast<int64_t>(k))) {
				lua_pop(L, 1);
				continue;
			}
		}
		fn(lua_gettop(L) - 1, lua_type(L, -1) == LUA_TFUNCTION);
		lua_pop(L, 1);
		if (++seen >= kMaxKeys) {
			lua_pop(L, 1);
			break;
		}
	}
}

void Inspector::BuildTableKeys(RefEntry& entry, int obj) {
	lua_State* L = pausedL_;
	entry.keysBuilt = true;
	entry.keys.clear();
	entry.groups.clear();
	const bool globals = entry.kind == RefKind::Globals;
	const size_t n = lua::RawLen(L, obj);
	entry.arrayLen = static_cast<int64_t>(n);
	// 数组段（5.3）：1..rawlen，空洞不显示
	for (size_t i = 1; i <= n && entry.keys.size() < kMaxKeys; ++i) {
		lua_rawgeti(L, obj, static_cast<lua_Integer>(i));
		const bool nil = lua_isnil(L, -1);
		lua_pop(L, 1);
		if (nil) continue;
		RichKey k;
		k.index = static_cast<int64_t>(i);
		k.indexed = true;
		k.name = "[" + std::to_string(i) + "]";
		k.nameType = "number";
		if (!entry.evaluateName.empty()) k.evaluateName = entry.evaluateName + "[" + std::to_string(i) + "]";
		entry.keys.push_back(std::move(k));
	}
	entry.arrayKeys = entry.keys.size();
	// 字段段
	std::vector<RichKey> data, funcs, builtins;
	std::vector<std::string> ownKeys;
	CollectFields(L, obj, n, [&](int keyIdx, bool isFunc) {
		RichKey k;
		const int kt = lua_type(L, keyIdx);
		k.keySlot = Anchor(L, keyIdx);
		k.name = RichKeyName(L, keyIdx);
		k.nameType = lua_typename(L, kt);
		k.evaluateName = ChildEval(entry.evaluateName, globals, L, keyIdx);
		if (kt == LUA_TSTRING) {
			size_t kl;
			const char* ks = lua_tolstring(L, keyIdx, &kl);
			k.field.assign(ks, kl);
			ownKeys.push_back(k.field);
			if (globals && view_.hideStdGlobals && InList(kBuiltinGlobals, ks, kl)) {
				builtins.push_back(std::move(k));
				return;
			}
		}
		(isFunc ? funcs : data).push_back(std::move(k));
	});
	bool sorted = true;
	// 有数据也有函数时函数收进「方法」分组（5.1）；全是函数（模块 / 类表）时直接列出。全局作用域不分
	const bool splitMethods = view_.groupMethods && !globals && !data.empty() && !funcs.empty();
	if (!splitMethods) {
		for (RichKey& k : funcs) data.push_back(std::move(k));
		funcs.clear();
	}
	SortKeys(L, data, sorted);
	entry.sorted = sorted;
	for (RichKey& k : data) entry.keys.push_back(std::move(k));
	if (splitMethods) {
		bool s = true;
		SortKeys(L, funcs, s);
		const int64_t cnt = static_cast<int64_t>(funcs.size());
		uint64_t ref = NewGroup(entry.slot, std::move(funcs), s);
		refs_[ref].owner = entry.owner;
		entry.groups.push_back(GroupItem("方法", "", ref, cnt));
	}
	if (!builtins.empty()) {
		bool s = true;
		SortKeys(L, builtins, s);
		const int64_t cnt = static_cast<int64_t>(builtins.size());
		uint64_t ref = NewGroup(entry.slot, std::move(builtins), s);
		entry.groups.push_back(GroupItem("内置", "标准库和框架全局", ref, cnt));
	}
	BuildGroups(entry, obj, ownKeys);
}

void Inspector::BuildGroups(RefEntry& entry, int obj, const std::vector<std::string>& ownKeys) {
	lua_State* L = pausedL_;
	const int top = lua_gettop(L);
	// 宿主认领的表（UnLua 实例）：引擎对象的成员放进一个分组（6.4）
	if (entry.providerId && lua_type(L, obj) == LUA_TTABLE) {
		uint64_t ref = NewRef(RefKind::HostList, 0, entry.slot, "");
		refs_[ref].providerId = entry.providerId;
		entry.groups.push_back(GroupItem("UE 对象", entry.hostSummary, ref, -1));
	}
	if (!lua_getmetatable(L, obj)) return;
	const int mt = lua_gettop(L);
	// 继承成员（6.2）
	if (view_.showInherited) {
		std::unordered_set<std::string> closer(ownKeys.begin(), ownKeys.end());
		std::vector<const void*> visited{lua_topointer(L, obj)};
		lua_pushvalue(L, mt);
		for (int depth = 0; depth < kMaxInheritDepth; ++depth) {
			const int cur = lua_gettop(L); // 当前这层的元表
			lua_pushstring(L, "__index");
			lua_rawget(L, cur);
			const int index = lua_gettop(L);
			const int it = lua_type(L, index);
			if (it == LUA_TFUNCTION) {
				VariableItem fn;
				fn.name = "__index";
				fn.nameType = "string";
				MakeItemRich(L, index, fn, "", "", "");
				uint64_t ref = NewRef(RefKind::Static, 0, 0, "");
				RefEntry& e = refs_[ref];
				VariableItem g = GroupItem("__index 是函数", fn.value, ref, 1);
				g.locationChunk = fn.locationChunk;
				g.locationLine = fn.locationLine;
				g.hint = "通过函数取值的成员不会自动读取（避免执行代码）";
				e.richChildren.push_back(std::move(fn));
				e.richBuilt = true;
				entry.groups.push_back(std::move(g));
				break;
			}
			if (it != LUA_TTABLE) break;
			const void* p = lua_topointer(L, index);
			if (std::find(visited.begin(), visited.end(), p) != visited.end()) break;
			visited.push_back(p);
			const std::string& loaded = LoadedName(L, index);
			std::string cn;
			for (const char* f : kClassNameFields) {
				if (!cn.empty()) break;
				cn = RawGetString(L, index, f);
			}
			if (cn.empty()) cn = LastSegment(loaded);
			if (cn.empty()) cn = RawGetString(L, cur, "__name");
			if (cn.empty()) cn = ClassName(L, index);
			std::vector<RichKey> data, funcs;
			std::vector<std::string> levelKeys;
			CollectFields(L, index, 0, [&](int keyIdx, bool isFunc) {
				RichKey k;
				const int kt = lua_type(L, keyIdx);
				if (kt == LUA_TSTRING) {
					size_t kl;
					const char* ks = lua_tolstring(L, keyIdx, &kl);
					k.field.assign(ks, kl);
					if (k.field == "__index") return; // 类表上的 __index = 自己，只是噪音
					levelKeys.push_back(k.field);
					if (closer.count(k.field)) k.attrs |= kAttrShadowed;
				}
				k.attrs |= kAttrInherited;
				k.keySlot = Anchor(L, keyIdx);
				k.name = RichKeyName(L, keyIdx);
				k.nameType = lua_typename(L, kt);
				(isFunc ? funcs : data).push_back(std::move(k));
			});
			bool s = true;
			SortKeys(L, data, s);
			SortKeys(L, funcs, s);
			for (RichKey& k : funcs) data.push_back(std::move(k));
			const int64_t cnt = static_cast<int64_t>(data.size());
			const std::string label = cn.empty() ? "table" : cn;
			uint64_t ref = NewGroup(Anchor(L, index), std::move(data), s);
			refs_[ref].owner = label;
			VariableItem g = GroupItem("继承自 " + label, loaded.empty() ? label : "module " + loaded, ref, cnt);
			entry.groups.push_back(std::move(g));
			for (std::string& k : levelKeys) closer.insert(std::move(k));
			// 下一层：这个 table 的元表
			if (!lua_getmetatable(L, index)) break;
			lua_replace(L, cur);
			lua_settop(L, cur);
		}
		lua_settop(L, mt);
	}
	// 元表（6.1）
	if (view_.showMetatable) {
		std::vector<RichKey> keys;
		CollectFields(L, mt, 0, [&](int keyIdx, bool) {
			RichKey k;
			const int kt = lua_type(L, keyIdx);
			if (kt == LUA_TSTRING) {
				size_t kl;
				const char* ks = lua_tolstring(L, keyIdx, &kl);
				k.field.assign(ks, kl);
				if (IsMetamethod(k.field)) k.attrs |= kAttrMetamethod;
			}
			k.keySlot = Anchor(L, keyIdx);
			k.name = RichKeyName(L, keyIdx);
			k.nameType = lua_typename(L, kt);
			keys.push_back(std::move(k));
		});
		bool s = true;
		SortKeys(L, keys, s);
		std::string mn = RawGetString(L, mt, "__name");
		if (mn.empty()) mn = ClassName(L, obj);
		const int64_t cnt = static_cast<int64_t>(keys.size());
		uint64_t ref = NewGroup(Anchor(L, mt), std::move(keys), s);
		refs_[ref].owner = mn;
		VariableItem g = GroupItem(mn.empty() ? "元表" : "元表 " + mn, PtrString(lua_topointer(L, mt)), ref, cnt);
		g.objectId = g.value;
		entry.groups.push_back(std::move(g));
	}
	lua_settop(L, top);
}

void Inspector::BuildRichFrame(RefEntry& entry) {
	lua_State* L = pausedL_;
	entry.richBuilt = true;
	entry.richChildren.clear();
	entry.groups.clear();
	FrameRef f;
	lua_Debug ar;
	ProtocolError err;
	if (!GetFrame(entry.frameId, f, ar, err)) return;
	lua_State* T = f.thread;
	const int top = lua_gettop(L);
	if (entry.kind == RefKind::Locals) {
		// 声明顺序；同名的外层变量灰显（7.1）；内部临时值默认不显示
		std::vector<std::pair<std::string, int>> names;
		std::vector<int> temps;
		for (int i = 1;; ++i) {
			const char* n = lua_getlocal(T, &ar, i);
			if (!n) break;
			lua_pop(T, 1);
			if (n[0] == '(') temps.push_back(i);
			else names.emplace_back(n, i);
		}
		for (size_t k = 0; k < names.size(); ++k) {
			bool shadowed = false;
			for (size_t j = k + 1; j < names.size(); ++j) {
				if (names[j].first == names[k].first) { shadowed = true; break; }
			}
			lua_getlocal(T, &ar, names[k].second);
			if (T != L) lua_xmove(T, L, 1);
			VariableItem item;
			item.name = names[k].first;
			item.nameType = "string";
			MakeItemRich(L, -1, item, shadowed ? "" : names[k].first, "", "");
			if (shadowed) {
				item.attrs.push_back("shadowed");
				item.hint = "被后面声明的同名局部变量遮蔽";
			}
			lua_pop(L, 1);
			entry.richChildren.push_back(std::move(item));
		}
		if (view_.showTemporaries && !temps.empty()) {
			uint64_t ref = NewRef(RefKind::Static, 0, 0, "");
			std::vector<VariableItem> items;
			for (int i : temps) {
				const char* n = lua_getlocal(T, &ar, i);
				if (!n) continue;
				if (T != L) lua_xmove(T, L, 1);
				VariableItem item;
				item.name = n;
				item.nameType = "string";
				MakeItemRich(L, -1, item, "", "", "");
				lua_pop(L, 1);
				items.push_back(std::move(item));
			}
			RefEntry& e = refs_[ref];
			e.richChildren = std::move(items);
			e.richBuilt = true;
			entry.groups.push_back(GroupItem("临时值", "", ref, static_cast<int64_t>(e.richChildren.size())));
		}
	} else if (entry.kind == RefKind::Varargs) {
#if EMMY_LUA_VERSION_NUM >= 502
		for (int i = 1;; ++i) {
			const char* n = lua_getlocal(T, &ar, -i);
			if (!n) break;
			if (T != L) lua_xmove(T, L, 1);
			VariableItem item;
			item.name = "...[" + std::to_string(i) + "]";
			item.nameType = "number";
			item.indexed = true;
			MakeItemRich(L, -1, item, "", "", "");
			lua_pop(L, 1);
			entry.richChildren.push_back(std::move(item));
		}
#endif
	} else {
		lua_getinfo(T, "fu", &ar);
		if (T != L) lua_xmove(T, L, 1);
		const int fn = lua_gettop(L);
		for (int i = 1; i <= ar.nups; ++i) {
			const char* n = lua_getupvalue(L, fn, i);
			if (!n) break;
			if (std::strcmp(n, "_ENV") == 0) { // 7.2：_ENV 就是全局作用域
				lua_pop(L, 1);
				continue;
			}
			VariableItem item;
			item.name = n[0] ? n : "?";
			item.nameType = "string";
			MakeItemRich(L, -1, item, n[0] ? n : "", "", "");
			lua_pop(L, 1);
			entry.richChildren.push_back(std::move(item));
		}
	}
	lua_settop(L, top);
}

// ---------------------------------------------------------------- 分页

bool Inspector::RichVariables(RefEntry& entry, int start, int count, const std::string& filter, json& body) {
	lua_State* L = pausedL_;
	const int top = lua_gettop(L);
	lua_checkstack(L, 32);
	json vars = json::array();
	size_t bytes = 0;
	bool truncated = false;
	auto add = [&](const VariableItem& item) {
		const size_t sz = item.name.size() + item.value.size() + item.typeName.size() + item.evaluateName.size() + 96;
		if (!vars.empty() && bytes + sz > kResponseBudget) {
			truncated = true;
			return false;
		}
		bytes += sz;
		vars.push_back(item.ToJson());
		return true;
	};
	int64_t total = 0;
	bool sorted = true;
	const std::vector<VariableItem>* groups = nullptr;

	auto pageItems = [&](const std::vector<VariableItem>& items) {
		for (const VariableItem& item : items) {
			if (filter == "named" && item.indexed) continue;
			if (filter == "indexed" && !item.indexed) continue;
			if (total >= start && total < static_cast<int64_t>(start) + count && !truncated) add(item);
			++total;
		}
	};

	switch (entry.kind) {
	case RefKind::Locals:
	case RefKind::Upvalues:
	case RefKind::Varargs:
		if (!entry.richBuilt) BuildRichFrame(entry);
		pageItems(entry.richChildren);
		groups = &entry.groups;
		break;
	case RefKind::Static:
		pageItems(entry.richChildren);
		break;
	case RefKind::Value:
	case RefKind::Globals:
	case RefKind::Group: {
		PushAnchored(L, entry.slot);
		const int obj = lua_gettop(L);
		const int t = lua_type(L, obj);
		if (t == LUA_TFUNCTION && entry.kind == RefKind::Value) {
			if (!entry.richBuilt) {
				entry.richBuilt = true;
				for (int i = 1;; ++i) {
					const char* n = lua_getupvalue(L, obj, i);
					if (!n) break;
					VariableItem item;
					item.name = n[0] ? n : ("(upvalue " + std::to_string(i) + ")");
					item.nameType = "string";
					MakeItemRich(L, -1, item, "", "", "");
					lua_pop(L, 1);
					entry.richChildren.push_back(std::move(item));
				}
			}
			pageItems(entry.richChildren);
			break;
		}
		if (t == LUA_TUSERDATA && entry.kind == RefKind::Value) {
			if (!entry.keysBuilt) {
				entry.keysBuilt = true;
				entry.groups.clear();
				BuildGroups(entry, obj, {});
			}
			groups = &entry.groups;
			break;
		}
		if (t != LUA_TTABLE) {
			lua_settop(L, top);
			return false;
		}
		if (!entry.keysBuilt) BuildTableKeys(entry, obj);
		groups = &entry.groups;
		sorted = entry.sorted;
		size_t from = 0, to = entry.keys.size();
		int64_t skip = start;
		if (filter == "indexed") {
			// 按下标区间分页（10.1）：start=0 对应 [1]
			to = entry.arrayKeys;
			total = entry.arrayLen;
			const int64_t lo = static_cast<int64_t>(start) + 1;
			from = std::lower_bound(entry.keys.begin(), entry.keys.begin() + to, lo,
				[](const RichKey& k, int64_t v) { return k.index < v; }) - entry.keys.begin();
			skip = 0;
		} else if (filter == "named") {
			from = entry.arrayKeys;
			total = static_cast<int64_t>(to - from);
		} else {
			total = static_cast<int64_t>(to);
		}
		int64_t taken = 0;
		for (size_t i = from + static_cast<size_t>(std::max<int64_t>(0, skip)); i < to && taken < count; ++i) {
			const RichKey& k = entry.keys[i];
			if (filter == "indexed" && k.index > static_cast<int64_t>(start) + count) break;
			if (k.indexed) {
				lua_rawgeti(L, obj, static_cast<lua_Integer>(k.index));
			} else {
				PushAnchored(L, k.keySlot);
				lua_rawget(L, obj);
			}
			VariableItem item;
			item.name = k.name;
			item.nameType = k.nameType;
			item.indexed = k.indexed;
			MakeItemRich(L, -1, item, k.evaluateName, k.field, entry.owner);
			lua_pop(L, 1);
			if (k.attrs & kAttrInherited) item.attrs.push_back("inherited");
			if (k.attrs & kAttrShadowed) {
				item.attrs.push_back("shadowed");
				item.hint = "被更近一层的同名成员覆盖";
			}
			if (k.attrs & kAttrMetamethod) item.attrs.push_back("metamethod");
			if (!add(item)) break;
			++taken;
		}
		break;
	}
	default:
		lua_settop(L, top);
		return false;
	}
	lua_settop(L, top);
	body = {{"variables", std::move(vars)}, {"total", total}};
	if (groups && start == 0 && filter != "indexed" && !groups->empty()) {
		json g = json::array();
		for (const VariableItem& item : *groups) g.push_back(item.ToJson());
		body["groups"] = std::move(g);
	}
	body["sorted"] = sorted;
	if (truncated) body["truncated"] = true;
	return true;
}

} // namespace emmy
