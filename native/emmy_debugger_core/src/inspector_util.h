// inspector.cpp 和 rich_view.cpp 共用的小工具。只在 VM 线程使用。
#pragma once

#include "lua_shim.h"

#include <cinttypes>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <string>

namespace emmy::detail {

inline std::string PtrString(const void* p) {
	char buf[32];
	std::snprintf(buf, sizeof(buf), "0x%" PRIxPTR, reinterpret_cast<uintptr_t>(p));
	return buf;
}

inline void AppendEscaped(std::string& out, const char* s, size_t len, size_t maxLen) {
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

inline bool IsIdentifier(const char* s, size_t len) {
	if (len == 0) return false;
	if (!(std::isalpha(static_cast<unsigned char>(s[0])) || s[0] == '_')) return false;
	for (size_t i = 1; i < len; ++i) {
		if (!(std::isalnum(static_cast<unsigned char>(s[i])) || s[i] == '_')) return false;
	}
	return true;
}

// metatable.__name（5.3+ luaL_newmetatable 会设置；UnLua 等也常用）
inline std::string MetaName(lua_State* L, int idx) {
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

} // namespace emmy::detail
