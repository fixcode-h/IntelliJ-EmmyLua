// agent 用到的调试核心 C ABI（和「原生调试核心」约定，正式头文件是
// native/emmy_debugger_core/include/emmy/emmy_core.h）。核心还没合入时用这里的声明，签名必须保持一致。
#pragma once

#include <cstddef>
#include <cstdint>

#if defined(EMMY_AGENT_HAS_CORE_HEADER)
#include <emmy/emmy_core.h>
#else
struct lua_State;

extern "C" {
// 在任何 Lua 调用之前设置：核心按名字取 Lua 函数地址，取代 dlsym（设备上的 so 被裁剪过，dlsym 找不到）。
void Emmy_SetLuaApiResolver(void* (*resolve)(const char* name, void* ud), void* ud);
// 监听 Unix 抽象 socket（不需要 INTERNET 权限），name 不含前导 \0。成功返回 0。
int Emmy_ListenAbstract(const char* name, const char* token, char* err, size_t errLen);
// 在 Lua 线程调用：登记 VM 并通过 HookMux 挂钩子，返回 vmId（0 表示失败）。
uint64_t Emmy_RegisterLuaVm(lua_State* L, const char* discovery);
// lua_close 之前调用。
void Emmy_UnregisterLuaVm(lua_State* L);
// 给 welcome 消息用的进程信息。
void Emmy_SetProcessInfo(const char* packageName, const char* buildId);
}
#endif

extern "C" {
// 可选：阻塞到 IDE 连上并完成握手，超时返回非 0。核心没有提供时（弱符号为空）agent 不等待。
int Emmy_WaitForClient(int timeoutMs) __attribute__((weak));
// 可选：最近一次失败的原因（例如 Emmy_RegisterLuaVm 返回 0 时缺了哪些 Lua 函数）。
const char* Emmy_LastError(void) __attribute__((weak));
}
