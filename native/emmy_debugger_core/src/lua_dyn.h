/* 动态 Lua API 模式（EMMY_CORE_LUA_MODE=dynamic）的强制包含头：每个核心源文件和自带的 lauxlib.c 编译时最先包含它。
 *
 * 附加到别人的进程（Android agent 等）时，核心不能自带一份 Lua 实现：必须调用宿主那份 Lua，
 * 而宿主的 so 往往被裁剪过，dlsym 找不到符号，只能由 agent 按名字给出地址（Emmy_SetLuaApiResolver）。
 * 做法：
 *   - lua_* 调用全部改成经函数指针（emmy_lua_dyn_calls.h 里的同名函数式宏），指针在 emmy_dyn_resolve 里填；
 *   - luaL_* 用自带的 lauxlib.c 编进核心，改名为 emmy_luaL_*（emmy_lua_dyn_list.h），只依赖上面的 lua_*，
 *     宿主裁没裁 lauxlib 都不影响，也不和宿主或测试程序里的 luaL_* 冲突。
 * 头文件（以及内部结构布局）用编译时选的 Lua 版本，宿主必须是同一大版本、默认 luaconf。
 */
#ifndef EMMY_LUA_DYN_H
#define EMMY_LUA_DYN_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif
#include "emmy_lua_dyn_list.h" /* luaL_* 改名必须在 lauxlib.h 之前 */
/* 宿主可能改过 LUA_IDSIZE（lua_Debug.short_src 的长度）。我们在自己栈上放的 lua_Debug 交给宿主的
 * lua_getstack / lua_getinfo 去填，宿主按它的长度写 short_src 和后面的 i_ci，比我们的大就会写坏栈。
 * 所以这里把长度放大（luaconf.h 无条件定义它，只能先包含再改），并且核心不读 i_ci（见 lua_internal.cpp）。 */
#include "luaconf.h"
#undef LUA_IDSIZE
#define LUA_IDSIZE 512
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#ifdef __cplusplus
}
#  define EMMY_DYN_TYPEOF(x) decltype(&x)
extern "C" {
#else
#  define EMMY_DYN_TYPEOF(x) __typeof__(&x)
#endif

#define EMMY_DYN_DECLARE(name) extern EMMY_DYN_TYPEOF(name) emmy_dyn_##name;
EMMY_LUA_API_LIST(EMMY_DYN_DECLARE)
#undef EMMY_DYN_DECLARE

/* 按名字填全部函数指针。resolve 为空时用 dlsym(RTLD_DEFAULT)。
 * 必需函数缺任何一个都返回缺的个数，并把名字写进 err；可选函数缺了换成替代实现（见 emmy_dyn_warnings）。
 * 可以重复调用（后一次覆盖）。 */
int emmy_dyn_resolve(void* (*resolve)(const char* name, void* ud), void* ud, char* err, size_t errLen);
/* 已经成功解析过返回 1 */
int emmy_dyn_ready(void);
/* 缺了哪些可选函数（已换成替代实现）及影响，没有时为空串 */
const char* emmy_dyn_warnings(void);

#ifdef __cplusplus
}
#endif

/* 放最后：上面的声明里要用函数的原名 */
#include "emmy_lua_dyn_calls.h"

#endif
