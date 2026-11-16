// 动态 Lua API 的函数指针表和解析，见 lua_dyn.h。
//
// 函数分两档：
//   必需：核心（含自带 lauxlib 里实际会走到的部分）离不开的，缺一个就拒绝注册 VM（Emmy_LastError 说明缺了哪些）；
//   可选：宿主的 so 用 --gc-sections 之类裁剪时常被裁掉的少用函数。缺了就换成下面的替代实现，
//         只关掉或降级对应功能（例如改局部变量），连上 IDE 时用 output warn 说明。
#include "lua_dyn.h"

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>

#if !defined(_WIN32)
#  include <dlfcn.h>
#endif

#define EMMY_DYN_DEFINE(name) EMMY_DYN_TYPEOF(name) emmy_dyn_##name = nullptr;
EMMY_LUA_API_LIST(EMMY_DYN_DEFINE)
#undef EMMY_DYN_DEFINE

namespace {

// 不在本版本 API 里的名字（比如 5.1 没有 lua_pcallk）自动跳过
const char* const kRequired[] = {
	"lua_newstate", "lua_close", "lua_atpanic", "lua_newthread", "lua_status", "lua_resume", "lua_xmove",
	"lua_absindex", "lua_gettop", "lua_settop", "lua_pushvalue", "lua_rotate", "lua_insert", "lua_remove",
	"lua_replace", "lua_checkstack", "lua_type", "lua_typename", "lua_toboolean", "lua_tolstring",
	"lua_tonumberx", "lua_tonumber", "lua_tointegerx", "lua_tointeger", "lua_touserdata", "lua_tothread",
	"lua_rawlen", "lua_objlen",
	"lua_pushnil", "lua_pushnumber", "lua_pushinteger", "lua_pushlstring", "lua_pushstring",
	"lua_pushcclosure", "lua_pushboolean", "lua_pushthread",
	"lua_getfield", "lua_getglobal", "lua_gettable", "lua_rawget", "lua_rawgeti", "lua_createtable",
	"lua_getmetatable", "lua_setfield", "lua_rawset", "lua_rawseti", "lua_setmetatable", "lua_next",
	"lua_settable", "lua_setglobal", "lua_getfenv", "lua_setfenv",
	"lua_callk", "lua_call", "lua_pcallk", "lua_pcall", "lua_load", "lua_error",
	"lua_getstack", "lua_getinfo", "lua_getlocal", "lua_getupvalue", "lua_setupvalue",
	"lua_sethook",
};

// ---- 可选函数的替代实现（只用必需函数） ----

const char* FallbackSetlocal(lua_State* L, const lua_Debug*, int) {
	// 不改，弹掉要设的值；调用方按「没有这个局部变量」报错
	lua_settop(L, -2);
	return nullptr;
}

void FallbackConcat(lua_State* L, int n) {
	std::string s;
	const int top = lua_gettop(L);
	for (int i = top - n + 1; i <= top; ++i) {
		size_t len = 0;
		const char* p = lua_tolstring(L, i, &len);
		if (p) s.append(p, len);
	}
	lua_settop(L, top - n);
	lua_pushlstring(L, s.data(), s.size());
}

const char* FallbackPushvfstring(lua_State* L, const char* fmt, va_list ap) {
	// Lua 自己的 %I（lua_Integer）、%U（UTF-8 码点）C 库不认识，换成 %lld / %ld 再交给 vsnprintf
	std::string f;
	for (const char* p = fmt; *p; ++p) {
		if (p[0] == '%' && p[1] == 'I') { f += "%lld"; ++p; }
		else if (p[0] == '%' && p[1] == 'U') { f += "%ld"; ++p; }
		else f += *p;
	}
	char buf[512];
	va_list copy;
	va_copy(copy, ap);
	int n = std::vsnprintf(buf, sizeof(buf), f.c_str(), copy);
	va_end(copy);
	if (n < 0) n = 0;
	if (static_cast<size_t>(n) >= sizeof(buf)) n = sizeof(buf) - 1;
	lua_pushlstring(L, buf, static_cast<size_t>(n));
	return lua_tolstring(L, -1, nullptr);
}

const char* FallbackPushfstring(lua_State* L, const char* fmt, ...) {
	va_list ap;
	va_start(ap, fmt);
	const char* r = FallbackPushvfstring(L, fmt, ap);
	va_end(ap);
	return r;
}

const void* FallbackTopointer(lua_State*, int) { return nullptr; }
void FallbackPushlightuserdata(lua_State* L, void*) { lua_pushnil(L); }
int FallbackRawequal(lua_State*, int, int) { return 0; }
lua_Hook FallbackGethook(lua_State*) { return nullptr; }
int FallbackGethookmask(lua_State*) { return -1; } // 永远「不一样」：每次都重新 sethook，正确但稍慢
int FallbackGethookcount(lua_State*) { return 0; }
#if LUA_VERSION_NUM >= 503
int FallbackIsinteger(lua_State*, int) { return 0; }
#endif
#if LUA_VERSION_NUM >= 504
void FallbackSetwarnf(lua_State*, lua_WarnFunction, void*) {}
#endif

struct Optional {
	const char* name;
	const char* effect; // 缺了以后的影响，给用户看
	void (*install)();
};

#define EMMY_OPT(fn, fallback, effect) \
	{#fn, effect, [] { if (!emmy_dyn_##fn) emmy_dyn_##fn = &fallback; }}

const Optional kOptional[] = {
	EMMY_OPT(lua_setlocal, FallbackSetlocal, "不能修改局部变量"),
	EMMY_OPT(lua_concat, FallbackConcat, "无"),
	EMMY_OPT(lua_pushvfstring, FallbackPushvfstring, "部分错误信息格式化降级"),
	EMMY_OPT(lua_pushfstring, FallbackPushfstring, "部分错误信息格式化降级"),
	EMMY_OPT(lua_topointer, FallbackTopointer, "表 / 函数不显示地址"),
	EMMY_OPT(lua_pushlightuserdata, FallbackPushlightuserdata, "无"),
	EMMY_OPT(lua_rawequal, FallbackRawequal, "无"),
	EMMY_OPT(lua_gethook, FallbackGethook, "不能和宿主已有的钩子共存"),
	EMMY_OPT(lua_gethookmask, FallbackGethookmask, "钩子开销略增"),
	EMMY_OPT(lua_gethookcount, FallbackGethookcount, "无"),
#if LUA_VERSION_NUM >= 503
	EMMY_OPT(lua_isinteger, FallbackIsinteger, "整数和浮点数显示不区分"),
#endif
#if LUA_VERSION_NUM >= 504
	EMMY_OPT(lua_setwarnf, FallbackSetwarnf, "无"),
#endif
};
#undef EMMY_OPT

bool g_ready = false;
std::string g_warnings;

#define EMMY_DYN_NAME(name) #name,
const char* const kAll[] = {EMMY_LUA_API_LIST(EMMY_DYN_NAME)};
#undef EMMY_DYN_NAME

bool InApi(const char* name) {
	for (const char* n : kAll) {
		if (std::strcmp(n, name) == 0) return true;
	}
	return false;
}

void* Lookup(void* (*resolve)(const char*, void*), void* ud, const char* name) {
	if (resolve) return resolve(name, ud);
#if !defined(_WIN32)
	return dlsym(RTLD_DEFAULT, name);
#else
	return nullptr;
#endif
}

void* Current(const char* name) {
#define EMMY_DYN_GET(n) if (std::strcmp(name, #n) == 0) return reinterpret_cast<void*>(emmy_dyn_##n);
	EMMY_LUA_API_LIST(EMMY_DYN_GET)
#undef EMMY_DYN_GET
	return nullptr;
}

void Append(char* err, size_t errLen, size_t& used, const char* prefix, const char* text) {
	if (!err || errLen <= used + 1) return;
	int w = std::snprintf(err + used, errLen - used, "%s%s", used ? "、" : prefix, text);
	if (w > 0) used += static_cast<size_t>(w) < errLen - used ? static_cast<size_t>(w) : errLen - used - 1;
}

} // namespace

extern "C" int emmy_dyn_resolve(void* (*resolve)(const char* name, void* ud), void* ud, char* err, size_t errLen) {
	size_t used = 0;
	int missing = 0;
	// 函数指针和 void* 互转：POSIX 上 dlsym 本身就这么用，Windows 上 GetProcAddress 同理
#define EMMY_DYN_FILL(name) emmy_dyn_##name = reinterpret_cast<EMMY_DYN_TYPEOF(name)>(Lookup(resolve, ud, #name));
	EMMY_LUA_API_LIST(EMMY_DYN_FILL)
#undef EMMY_DYN_FILL
	if (err && errLen) err[0] = '\0';
	for (const char* n : kRequired) {
		if (!InApi(n) || Current(n)) continue;
		++missing;
		Append(err, errLen, used, "找不到 Lua 函数：", n);
	}
	g_warnings.clear();
	for (const Optional& o : kOptional) {
		if (Current(o.name)) continue;
		o.install();
		if (!g_warnings.empty()) g_warnings += "；";
		g_warnings += std::string(o.name) + "（" + o.effect + "）";
	}
	if (!g_warnings.empty()) g_warnings = "宿主的 Lua 缺少可选函数，已用替代实现：" + g_warnings;
	g_ready = missing == 0;
	return missing;
}

extern "C" int emmy_dyn_ready(void) {
	return g_ready ? 1 : 0;
}

extern "C" const char* emmy_dyn_warnings(void) {
	return g_warnings.c_str();
}
