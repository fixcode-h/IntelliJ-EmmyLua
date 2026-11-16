// 显示规则（docs/design/variable-view.md 11.1）和 helper v2 formatter（11.2）在 richVariables 里的接入。
// 规则只用 rawget，不执行目标进程的代码；formatter 是 IDE 下发的 helper 脚本，在私有协程里、带 pcall 和
// 时间预算（helperBudgetMs）执行，出错只影响这一项。
#include "inspector.h"

#include "inspector_util.h"
#include "view_rules.h"

#include <algorithm>
#include <cstring>

namespace emmy {

using namespace detail;

namespace {

// emmy_main 返回的 emmy.__dispatch 放在 registry 的这个键下（vm.cpp 加载 helper 时写入）
const char* const kDispatchKey = "emmy.formatter.dispatch";

// 只读 rawget 一个字段，栈不变；字符串和数字都转成文本
std::string RawField(lua_State* L, int t, const char* field) {
	std::string out;
	lua_pushstring(L, field);
	lua_rawget(L, t);
	const int vt = lua_type(L, -1);
	if (vt == LUA_TSTRING || vt == LUA_TNUMBER) {
		size_t len;
		const char* s = lua_tolstring(L, -1, &len); // 栈上的是副本，数字转字符串不影响原表
		if (s) out.assign(s, len);
	}
	lua_pop(L, 1);
	return out;
}

// 字段存在且不是 nil
bool HasField(lua_State* L, int t, const char* field) {
	lua_pushstring(L, field);
	lua_rawget(L, t);
	const bool has = !lua_isnil(L, -1);
	lua_pop(L, 1);
	return has;
}

// 字段是 boolean 时返回它，否则返回 def
bool BoolField(lua_State* L, int t, const char* field, bool def) {
	lua_pushstring(L, field);
	lua_rawget(L, t);
	const bool v = lua_type(L, -1) == LUA_TBOOLEAN ? lua_toboolean(L, -1) != 0 : def;
	lua_pop(L, 1);
	return v;
}

void TruncateUtf8(std::string& s, size_t maxLen) {
	if (s.size() <= maxLen) return;
	size_t cut = maxLen;
	while (cut > 0 && (static_cast<unsigned char>(s[cut]) & 0xC0) == 0x80) --cut;
	s.resize(cut);
	s += "…";
}

bool PushDispatch(lua_State* L) {
	lua_pushstring(L, kDispatchKey);
	lua_rawget(L, LUA_REGISTRYINDEX);
	if (lua_type(L, -1) == LUA_TFUNCTION) return true;
	lua_pop(L, 1);
	return false;
}

} // namespace

// ---------------------------------------------------------------- 显示规则

const TypeViewRule* Inspector::RuleFor(const std::string& type) const {
	return view_.rules ? view_.rules->Find(type) : nullptr;
}

bool Inspector::IsRuleBuiltinGlobal(const char* s, size_t len) const {
	if (!view_.rules) return false;
	for (const std::string& g : view_.rules->builtinGlobals) {
		if (g.size() == len && std::memcmp(g.data(), s, len) == 0) return true;
	}
	return false;
}

std::string Inspector::RuleClassName(lua_State* L, int t) {
	if (!view_.rules || lua_type(L, t) != LUA_TTABLE) return "";
	t = lua::AbsIndex(L, t);
	for (const std::string& f : view_.rules->classNameFields) {
		lua_pushlstring(L, f.data(), f.size());
		lua_rawget(L, t);
		if (lua_type(L, -1) == LUA_TSTRING) {
			std::string out = lua_tostring(L, -1);
			lua_pop(L, 1);
			if (!out.empty()) return out;
		} else {
			lua_pop(L, 1);
		}
	}
	return "";
}

std::string Inspector::RenderRuleSummary(lua_State* L, int idx, const TypeViewRule& rule) {
	idx = lua::AbsIndex(L, idx);
	const int top = lua_gettop(L);
	std::string out;
	if (rule.hasSummary) {
		for (const TypeViewRule::Part& p : rule.summary) {
			if (!p.isPath) {
				out += p.text;
				continue;
			}
			// 沿路径 rawget；中途不是 table 就写 ?（不触发 __index）
			lua_pushvalue(L, idx);
			bool ok = true;
			for (const PathSegment& seg : p.path) {
				if (lua_type(L, -1) != LUA_TTABLE) {
					ok = false;
					break;
				}
				if (seg.kind == PathSegment::Kind::Integer) {
					lua_rawgeti(L, -1, static_cast<lua_Integer>(seg.integer));
				} else {
					lua_pushlstring(L, seg.text.data(), seg.text.size());
					lua_rawget(L, -2);
				}
				lua_remove(L, -2);
			}
			if (ok && !lua_isnil(L, -1)) {
				// 字符串直接放原文（模板里一般就是要名字本身），其他按嵌套写法
				if (lua_type(L, -1) == LUA_TSTRING) {
					size_t len;
					const char* s = lua_tolstring(L, -1, &len);
					std::string v(s, len);
					TruncateUtf8(v, 64);
					out += v;
				} else {
					out += NestedSummary(L, -1);
				}
			} else {
				out += "?";
			}
			lua_settop(L, top);
		}
		return out;
	}
	// 只有 pin：摘要优先列固定字段
	out = "{";
	size_t shown = 0;
	for (const std::string& f : rule.pin) {
		if (shown >= 5) break;
		lua_pushlstring(L, f.data(), f.size());
		lua_rawget(L, idx);
		if (!lua_isnil(L, -1)) {
			if (shown++) out += ", ";
			out += f + "=" + NestedSummary(L, -1);
		}
		lua_pop(L, 1);
	}
	out += shown ? ", …}" : "…}";
	return out;
}

void Inspector::ApplyRuleToKeys(const TypeViewRule& rule, std::vector<RichKey>& keys) {
	if (!rule.hide.empty()) {
		keys.erase(std::remove_if(keys.begin(), keys.end(), [&](const RichKey& k) {
			return !k.indexed && !k.field.empty() && rule.IsHidden(k.field);
		}), keys.end());
	}
	if (rule.pin.empty()) return;
	// 固定的按 pin 里的顺序放最前，其余保持原来的（已排好的）顺序
	std::stable_sort(keys.begin(), keys.end(), [&](const RichKey& a, const RichKey& b) {
		const int pa = a.indexed || a.field.empty() ? -1 : rule.PinIndex(a.field);
		const int pb = b.indexed || b.field.empty() ? -1 : rule.PinIndex(b.field);
		if ((pa >= 0) != (pb >= 0)) return pa >= 0;
		return pa >= 0 && pa < pb;
	});
	for (RichKey& k : keys) {
		if (!k.indexed && !k.field.empty() && rule.PinIndex(k.field) >= 0) k.attrs |= kAttrPinned;
	}
}

// ---------------------------------------------------------------- helper v2 formatter

bool Inspector::CallFormatter(lua_State* L, int nargs, std::string& err) {
	return RunOnEvalThread(L, nargs, std::max(1, view_.helperBudgetMs), err);
}

bool Inspector::FormatterDescribe(lua_State* L, int idx, VariableItem& item, const std::string& evaluateName) {
	if (!pausedL_ || !L) return false; // formatter 只在暂停里跑（要锚表、要预算）
	idx = lua::AbsIndex(L, idx);
	const int top = lua_gettop(L);
	if (fmtPause_ != pauseIdForHost_) {
		fmtPause_ = pauseIdForHost_;
		fmtState_ = 0;
		fmtDynamic_ = false;
		fmtTypes_.clear();
		fmtErrors_.clear();
	}
	if (fmtState_ == 0) {
		// 每次暂停问一次注册了哪些类型；没有 formatter 时之后完全不进 Lua
		fmtState_ = 1;
		if (!PushDispatch(L)) return false;
		lua_pushstring(L, "types");
		std::string err;
		if (CallFormatter(L, 1, err) && lua_type(L, -1) == LUA_TTABLE) {
			const int res = lua_gettop(L);
			fmtDynamic_ = BoolField(L, res, "dynamic", false);
			lua_pushstring(L, "names");
			lua_rawget(L, res);
			if (lua_type(L, -1) == LUA_TTABLE) {
				const size_t n = lua::RawLen(L, -1);
				for (size_t i = 1; i <= n && i <= 10000; ++i) {
					lua_rawgeti(L, -1, static_cast<lua_Integer>(i));
					if (lua_type(L, -1) == LUA_TSTRING) fmtTypes_.emplace(lua_tostring(L, -1), true);
					lua_pop(L, 1);
				}
			}
			if (fmtDynamic_ || !fmtTypes_.empty()) fmtState_ = 2;
		}
		lua_settop(L, top);
	}
	if (fmtState_ != 2) return false;
	const int t = lua_type(L, idx);
	const std::string tn = t == LUA_TTABLE ? ClassName(L, idx) : MetaName(L, idx);
	if (!fmtDynamic_ && (tn.empty() || !fmtTypes_.count(tn))) return false;
	int& errors = fmtErrors_[tn];
	if (errors >= 3) return false;
	if (!PushDispatch(L)) return false;
	lua_pushstring(L, "describe");
	lua_pushvalue(L, idx);
	if (tn.empty()) lua_pushnil(L);
	else lua_pushlstring(L, tn.data(), tn.size());
	std::string err;
	if (!CallFormatter(L, 3, err)) {
		++errors;
		lua_settop(L, top);
		// 退回默认显示，行尾标出错（11.2）
		item.attrs.push_back("helperError");
		TruncateUtf8(err, 200);
		item.hint = "helper 出错：" + err;
		return false;
	}
	errors = 0;
	if (lua_type(L, -1) != LUA_TTABLE) {
		lua_settop(L, top);
		return false;
	}
	const int res = lua_gettop(L);
	item.evaluateName = evaluateName;
	item.objectId = PtrString(lua_topointer(L, idx));
	std::string typeName = RawField(L, res, "typeName");
	item.typeName = typeName.empty() ? tn : typeName;
	if (!BoolField(L, res, "valid", true)) {
		item.value = "<已失效>";
		item.attrs.push_back("invalid");
		lua_settop(L, top);
		return true;
	}
	item.kind = RawField(L, res, "kind");
	if (item.kind.empty()) item.kind = t == LUA_TTABLE ? "object" : "userdata";
	item.hint = RawField(L, res, "hint");
	item.value = RawField(L, res, "summary");
	if (item.value.empty()) item.value = item.typeName.empty() ? lua_typename(L, t) : item.typeName;
	TruncateUtf8(item.value, view_.maxValueLength);
	const bool expandable = BoolField(L, res, "expandable", false);
	lua_settop(L, top);
	if (expandable) {
		item.ref = NewRef(RefKind::Value, 0, Anchor(L, idx), evaluateName);
		RefEntry& e = refs_[item.ref];
		e.formatter = true;
		e.fmtType = tn;
	}
	return true;
}

bool Inspector::FormatterChildren(RefEntry& entry, int start, int count, std::vector<VariableItem>& out, int64_t& total) {
	lua_State* L = pausedL_;
	const int top = lua_gettop(L);
	lua_checkstack(L, 16);
	PushAnchored(L, entry.slot);
	const int obj = lua_gettop(L);
	std::string err;
	bool ok = PushDispatch(L);
	if (ok) {
		lua_pushstring(L, "children");
		lua_pushvalue(L, obj);
		if (entry.fmtType.empty()) lua_pushnil(L);
		else lua_pushlstring(L, entry.fmtType.data(), entry.fmtType.size());
		lua_pushinteger(L, static_cast<lua_Integer>(start));
		lua_pushinteger(L, static_cast<lua_Integer>(count));
		ok = CallFormatter(L, 5, err);
	} else {
		err = "helper 已卸载";
	}
	if (!ok || lua_type(L, -1) != LUA_TTABLE) {
		VariableItem e;
		e.name = "(错误)";
		e.nameType = "string";
		e.type = "string";
		e.value = ok ? "formatter.children 没有返回表" : err;
		e.attrs.push_back("helperError");
		out.push_back(std::move(e));
		total = 1;
		lua_settop(L, top);
		return true;
	}
	const int res = lua_gettop(L);
	lua_pushstring(L, "total");
	lua_rawget(L, res);
	const bool hasTotal = lua_type(L, -1) == LUA_TNUMBER;
	const int64_t reported = hasTotal ? static_cast<int64_t>(lua_tonumber(L, -1)) : -1;
	lua_pop(L, 1);
	lua_pushstring(L, "items");
	lua_rawget(L, res);
	size_t n = 0;
	if (lua_type(L, -1) == LUA_TTABLE) {
		const int items = lua_gettop(L);
		n = lua::RawLen(L, items);
		if (n > static_cast<size_t>(count)) n = static_cast<size_t>(count);
		for (size_t i = 1; i <= n; ++i) {
			lua_rawgeti(L, items, static_cast<lua_Integer>(i));
			const int it = lua_gettop(L);
			VariableItem vi;
			if (lua_type(L, it) != LUA_TTABLE) {
				vi.name = "[" + std::to_string(start + static_cast<int64_t>(i)) + "]";
				MakeItemRich(L, it, vi, "", "", "");
			} else {
				vi.name = RawField(L, it, "name");
				if (vi.name.empty()) vi.name = "[" + std::to_string(start + static_cast<int64_t>(i)) + "]";
				if (HasField(L, it, "summary")) {
					// 直接指定显示（不可展开）
					vi.value = RawField(L, it, "summary");
					TruncateUtf8(vi.value, view_.maxValueLength);
					vi.typeName = RawField(L, it, "typeName");
					vi.kind = RawField(L, it, "kind");
					vi.type = vi.typeName.empty() ? "string" : "userdata";
				} else {
					lua_pushstring(L, "value");
					lua_rawget(L, it);
					MakeItemRich(L, -1, vi, "", "", "");
					lua_pop(L, 1);
				}
			}
			vi.nameType = "string";
			out.push_back(std::move(vi));
			lua_settop(L, it - 1);
		}
	}
	total = hasTotal ? std::max<int64_t>(reported, start + static_cast<int64_t>(n))
	                 : start + static_cast<int64_t>(n) + (n >= static_cast<size_t>(count) ? 1 : 0);
	lua_settop(L, top);
	return true;
}

} // namespace emmy
