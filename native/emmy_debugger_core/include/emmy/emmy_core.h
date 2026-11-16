/*
 * emmy_debugger_core 的 C ABI。宿主（内嵌 runtime、PC 附加注入、Android 真机附加 agent）只通过这里接入。
 * 协议见 docs/design/protocol-v3.md。
 *
 * 线程约定：
 *  - Emmy_RegisterLuaVm / Emmy_UnregisterLuaVm / Emmy_BreakHere 必须在该 VM 执行 Lua 的线程上调用。
 *  - 其余函数线程安全，可在任意线程调用。
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct lua_State lua_State;
typedef struct lua_Debug lua_Debug;

#if defined(_WIN32)
#  if defined(EMMY_CORE_BUILDING_DLL)
#    define EMMY_API __declspec(dllexport)
#  else
#    define EMMY_API
#  endif
#else
#  define EMMY_API __attribute__((visibility("default")))
#endif

/* ---------- 进程与 Lua API ---------- */

/* 运行时解析 Lua 符号（dynamic 模式，附加场景用，取代 dlsym）。在 Emmy_RegisterLuaVm 之前设置；不设则退回 dlsym(RTLD_DEFAULT)。
 * source 模式（核心自带 Lua）忽略它。 */
typedef void* (*Emmy_LuaApiResolver)(const char* name, void* ud);
EMMY_API void Emmy_SetLuaApiResolver(Emmy_LuaApiResolver resolve, void* ud);
/* 最近一次失败的原因（中文），例如 Emmy_RegisterLuaVm 返回 0 时缺了哪些 Lua 函数。没有错误时为空串。 */
EMMY_API const char* Emmy_LastError(void);

/* welcome.process 里的 package / buildId（Android 用），可为 NULL。 */
EMMY_API void Emmy_SetProcessInfo(const char* packageName, const char* buildId);

/* 握手 token（配对码）。NULL 或空串表示不校验；监听非回环地址时宿主必须设置。 */
EMMY_API void Emmy_SetToken(const char* token);

/* ---------- 传输 ---------- */

/* 返回 0 成功；失败时 err 写入中文原因。 */
EMMY_API int Emmy_ListenTcp(const char* host, int port, char* err, size_t errLen);
/* Linux / Android 的 abstract unix socket（name 不含前导 \0，例如 "emmylua-12345"）。不需要 INTERNET 权限。 */
EMMY_API int Emmy_ListenAbstract(const char* name, const char* token, char* err, size_t errLen);
/* runtime 主动连 IDE（建连后仍由 IDE 先发 hello）。失败会每秒重试直到 Emmy_Stop。 */
EMMY_API int Emmy_ConnectTcp(const char* host, int port, char* err, size_t errLen);
/* 宿主自己 accept 好的连接（例如 agent 自己管 socket），核心接管 fd 的读写和关闭。 */
EMMY_API int Emmy_AttachConnectedFd(intptr_t fd);
/* 停止所有监听和连接，恢复所有暂停的 VM。 */
EMMY_API void Emmy_Stop(void);

/* 阻塞当前线程直到 IDE 发出 configurationDone 或超时；返回 1 表示 IDE 已就绪。 */
EMMY_API int Emmy_WaitIDE(int timeoutMs);
/* 阻塞到 IDE 握手完成（不等 configurationDone）；成功返回 0，超时返回 1。timeoutMs < 0 表示一直等。 */
EMMY_API int Emmy_WaitForClient(int timeoutMs);
EMMY_API int Emmy_IsConnected(void);

/* ---------- VM ---------- */

/* 在 L 所在线程调用；L 应为主线程（传协程也行，核心按 global_State 识别 VM）。
 * discovery 是给人看的来源说明（"host"、"attach"、"agent" 等）。返回 vmId，0 表示失败。 */
EMMY_API uint64_t Emmy_RegisterLuaVm(lua_State* L, const char* discovery);
EMMY_API void Emmy_UnregisterLuaVm(lua_State* L);
EMMY_API void Emmy_SetVmName(lua_State* L, const char* name);
/* 宿主重置了 VM 上下文（PIE 重启、热重载）：sourceEpoch + 1，断点重新匹配。 */
EMMY_API void Emmy_ResetVmContext(lua_State* L, const char* reason);
/* 登记 chunk 的内容哈希（小写 hex SHA-256），用于 breakpointStatus 的 mismatch 判断。 */
EMMY_API void Emmy_RegisterLuaSource(lua_State* L, const char* chunkName, const char* sha256);
/* 在 Lua 代码里调用等同于 emmy.breakHere()：已连接时立即暂停（reason = hostBreak）。 */
EMMY_API void Emmy_BreakHere(lua_State* L);
/* ---------- 出错即停（能力 exceptions） ----------
 * IDE 设了 setOptions.exceptions = uncaught / all 时，错误在还没展开栈的时候暂停（reason = exception）。
 * 「没被接住」只有宿主知道，所以要宿主在自己的错误处理函数（lua_pcall 的 msgh）里通知核心：
 *  - 没有自己的 msgh：直接把 Emmy_LuaErrorHandler 当 msgh 用（lua_pushcfunction 后传给 lua_pcall），它原样返回错误对象；
 *  - 已有 msgh（例如 UnLua 的报错函数）：在里面先调 Emmy_NotifyLuaError(L, 错误对象的位置)；
 *  - 挂 lua_pcall 的注入方式（Windows 附加、Android agent）：调用原函数前 Emmy_PrepareHostPcall，返回非 0 时把它当 errfunc
 *    传给原函数，返回后 Emmy_FinishHostPcall 把插进去的处理函数移走。带续延（k）的 lua_pcallk 不要用，Lua 自己的
 *    pcall 也不要（核心会认出来并返回 0）。
 * exceptions = all 时核心另外把全局 pcall / xpcall 换成会通知的版本，被接住的错误也会停。 */
EMMY_API int Emmy_LuaErrorHandler(lua_State* L);
EMMY_API void Emmy_NotifyLuaError(lua_State* L, int msgIdx);
/* nargs、errfunc 同 lua_pcall 的参数。返回插入的处理函数的绝对位置；不需要插时返回 0（原样调用即可）。 */
EMMY_API int Emmy_PrepareHostPcall(lua_State* L, int nargs, int errfunc);
EMMY_API void Emmy_FinishHostPcall(lua_State* L, int handlerIdx);
/* 宿主日志转发到 IDE（output.category = host）。level: 0 debug 1 info 2 warn 3 error。 */
EMMY_API void Emmy_Log(int level, const char* text);

/* ---------- HookMux：和宿主其他钩子共存 ---------- */

/* 一个 lua_State 只能挂一个钩子。宿主的其他工具（性能统计、快照点……）通过 HookMux 注册，
 * 不要直接 lua_sethook，否则会把调试器的钩子顶掉。注册前已经存在的钩子会被自动接管并继续收到事件。
 * mask 用 Lua 的 LUA_MASKCALL / LUA_MASKRET / LUA_MASKLINE / LUA_MASKCOUNT；count 只在含 COUNT 时有意义。 */
typedef void (*Emmy_HookCallback)(lua_State* L, lua_Debug* ar, void* ud);
EMMY_API uint64_t Emmy_HookMux_Register(lua_State* L, Emmy_HookCallback cb, int mask, int count, void* ud);
EMMY_API void Emmy_HookMux_Unregister(lua_State* L, uint64_t handle);

#ifdef __cplusplus
}
#endif
