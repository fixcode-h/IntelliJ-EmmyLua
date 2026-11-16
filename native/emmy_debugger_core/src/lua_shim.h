// Lua API 的统一入口。核心代码只 include 这个文件，版本差异集中在这里。
// 目前是「带 Lua 源码编译」方式；动态 API（附加场景）以后在这里切换实现，核心其他代码不用改。
#pragma once

extern "C" {
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
}

#include <cstddef>

// 直接看 lua.h 的 LUA_VERSION_NUM：不依赖编译定义，测试、bench 等目标 include 时也一致
#if LUA_VERSION_NUM >= 504
#  define EMMY_LUA_VERSION_NUM 504
#elif LUA_VERSION_NUM == 503
#  define EMMY_LUA_VERSION_NUM 503
#elif LUA_VERSION_NUM == 502
#  define EMMY_LUA_VERSION_NUM 502
#else
#  define EMMY_LUA_VERSION_NUM 501
#endif

#ifndef LUA_OK
#  define LUA_OK 0
#endif

namespace emmy::lua {

inline const char* VersionString() {
#if EMMY_LUA_VERSION_NUM == 501
	return "5.1";
#elif EMMY_LUA_VERSION_NUM == 502
	return "5.2";
#elif EMMY_LUA_VERSION_NUM == 503
	return "5.3";
#else
	return "5.4";
#endif
}

inline int AbsIndex(lua_State* L, int idx) {
#if EMMY_LUA_VERSION_NUM == 501
	return (idx > 0 || idx <= LUA_REGISTRYINDEX) ? idx : lua_gettop(L) + idx + 1;
#else
	return lua_absindex(L, idx);
#endif
}

inline void PushGlobalTable(lua_State* L) {
#if EMMY_LUA_VERSION_NUM == 501
	lua_pushvalue(L, LUA_GLOBALSINDEX);
#else
	lua_pushglobaltable(L);
#endif
}

inline size_t RawLen(lua_State* L, int idx) {
#if EMMY_LUA_VERSION_NUM == 501
	return lua_objlen(L, idx);
#else
	return lua_rawlen(L, idx);
#endif
}

inline bool IsInteger(lua_State* L, int idx) {
#if EMMY_LUA_VERSION_NUM >= 503
	return lua_isinteger(L, idx) != 0;
#else
	(void)L; (void)idx;
	return false;
#endif
}

inline int LoadBuffer(lua_State* L, const char* buf, size_t size, const char* chunkName) {
#if EMMY_LUA_VERSION_NUM == 501
	return luaL_loadbuffer(L, buf, size, chunkName);
#else
	return luaL_loadbufferx(L, buf, size, chunkName, "t");
#endif
}

// 把栈顶的 table 设为 idx 处函数的环境（5.1 setfenv；5.2+ 设第一个 upvalue _ENV）。弹出栈顶。
inline void SetFunctionEnv(lua_State* L, int funcIdx) {
	funcIdx = AbsIndex(L, funcIdx);
#if EMMY_LUA_VERSION_NUM == 501
	lua_setfenv(L, funcIdx);
#else
	if (!lua_setupvalue(L, funcIdx, 1)) {
		lua_pop(L, 1);
	}
#endif
}

inline int ResumeThread(lua_State* co, lua_State* from, int nargs, int* nres) {
#if EMMY_LUA_VERSION_NUM == 501
	(void)from;
	int r = lua_resume(co, nargs);
	*nres = lua_gettop(co);
	return r;
#elif EMMY_LUA_VERSION_NUM <= 503
	int r = lua_resume(co, from, nargs);
	*nres = lua_gettop(co);
	return r;
#else
	return lua_resume(co, from, nargs, nres);
#endif
}

// 5.1 没有 TAILCALL（只有 TAILRET）；统一成「是否为一次调用」
inline bool IsCallEvent(int ev) {
#if EMMY_LUA_VERSION_NUM == 501
	return ev == LUA_HOOKCALL;
#else
	return ev == LUA_HOOKCALL || ev == LUA_HOOKTAILCALL;
#endif
}

} // namespace emmy::lua
