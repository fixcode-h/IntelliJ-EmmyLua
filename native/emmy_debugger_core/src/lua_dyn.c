/* 动态 Lua API 的函数指针表和解析，见 lua_dyn.h。 */
#include "lua_dyn.h"

#include <stdio.h>
#include <string.h>

#if !defined(_WIN32)
#  include <dlfcn.h>
#endif

#define EMMY_DYN_DEFINE(name) EMMY_DYN_TYPEOF(name) emmy_dyn_##name = 0;
EMMY_LUA_API_LIST(EMMY_DYN_DEFINE)
#undef EMMY_DYN_DEFINE

/* 核心（含自带 lauxlib）真正会调用的函数。不在本版本 API 里的名字（比如 5.1 没有 lua_pcallk）自动跳过；
 * 其余缺了就拒绝附加，而不是等到调用时崩溃。 */
static const char* const kRequired[] = {
	"lua_newstate", "lua_close", "lua_newthread", "lua_status", "lua_resume", "lua_xmove",
	"lua_absindex", "lua_gettop", "lua_settop", "lua_pushvalue", "lua_rotate", "lua_insert", "lua_remove",
	"lua_replace", "lua_checkstack", "lua_type", "lua_typename", "lua_toboolean", "lua_tolstring",
	"lua_tonumberx", "lua_tonumber", "lua_tointegerx", "lua_tointeger", "lua_isinteger", "lua_topointer",
	"lua_tothread", "lua_touserdata", "lua_rawlen", "lua_objlen", "lua_rawequal",
	"lua_pushnil", "lua_pushnumber", "lua_pushinteger", "lua_pushlstring", "lua_pushstring",
	"lua_pushvfstring", "lua_pushfstring", "lua_pushcclosure", "lua_pushboolean", "lua_pushthread",
	"lua_pushlightuserdata", "lua_concat",
	"lua_getfield", "lua_getglobal", "lua_gettable", "lua_rawget", "lua_rawgeti", "lua_createtable",
	"lua_getmetatable", "lua_setfield", "lua_rawset", "lua_rawseti", "lua_setmetatable", "lua_next",
	"lua_settable", "lua_setglobal", "lua_getfenv", "lua_setfenv",
	"lua_callk", "lua_call", "lua_pcallk", "lua_pcall", "lua_load", "lua_error",
	"lua_getstack", "lua_getinfo", "lua_getlocal", "lua_setlocal", "lua_getupvalue", "lua_setupvalue",
	"lua_sethook", "lua_gethook", "lua_gethookmask", "lua_gethookcount",
	"lua_newuserdata", "lua_newuserdatauv",
};

static int g_ready = 0;

#define EMMY_DYN_NAME(name) #name,
static const char* const kAll[] = {EMMY_LUA_API_LIST(EMMY_DYN_NAME)};
#undef EMMY_DYN_NAME

static int InApi(const char* name) {
	size_t i;
	for (i = 0; i < sizeof(kAll) / sizeof(kAll[0]); ++i) {
		if (strcmp(kAll[i], name) == 0) return 1;
	}
	return 0;
}

static void* Lookup(void* (*resolve)(const char*, void*), void* ud, const char* name) {
	if (resolve) return resolve(name, ud);
#if !defined(_WIN32)
	return dlsym(RTLD_DEFAULT, name);
#else
	return 0;
#endif
}

static void* Current(const char* name);

int emmy_dyn_resolve(void* (*resolve)(const char* name, void* ud), void* ud, char* err, size_t errLen) {
	size_t i, used = 0;
	int missing = 0;
	/* 函数指针和 void* 互转在 POSIX 上是允许的（dlsym 本身就这么用） */
#define EMMY_DYN_FILL(name) emmy_dyn_##name = (EMMY_DYN_TYPEOF(name))Lookup(resolve, ud, #name);
	EMMY_LUA_API_LIST(EMMY_DYN_FILL)
#undef EMMY_DYN_FILL
	if (err && errLen) err[0] = '\0';
	for (i = 0; i < sizeof(kRequired) / sizeof(kRequired[0]); ++i) {
		const char* n = kRequired[i];
		if (!InApi(n) || Current(n)) continue;
		++missing;
		if (err && errLen > used + 1) {
			int w = snprintf(err + used, errLen - used, "%s%s", used ? ", " : "找不到 Lua 函数：", n);
			if (w > 0) used += (size_t)w < errLen - used ? (size_t)w : errLen - used - 1;
		}
	}
	g_ready = missing == 0;
	return missing;
}

int emmy_dyn_ready(void) {
	return g_ready;
}

static void* Current(const char* name) {
#define EMMY_DYN_GET(n) if (strcmp(name, #n) == 0) return (void*)emmy_dyn_##n;
	EMMY_LUA_API_LIST(EMMY_DYN_GET)
#undef EMMY_DYN_GET
	return 0;
}
