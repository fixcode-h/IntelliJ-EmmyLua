// 调试核心（emmy_debugger_core）还没编进来时的占位实现：让 agent 能单独编译、跑通注入和 hook 链路，
// 并在状态文件里明确报错，而不是静默失败。核心合入后 CMake 自动改为链接真正的实现。
#include <cstdio>

#include "agent_log.h"
#include "emmy_core_abi.h"

extern "C" {

void Emmy_SetLuaApiResolver(void* (*)(const char*, void*), void*) {}

int Emmy_ListenAbstract(const char* name, const char*, char* err, size_t errLen) {
    if (err != nullptr && errLen > 0) {
        std::snprintf(err, errLen, "这个 agent 没有编入调试核心（emmy_debugger_core），无法监听 %s", name);
    }
    return -1;
}

uint64_t Emmy_RegisterLuaVm(lua_State* L, const char* discovery) {
    AGENT_LOGW("占位核心：收到 VM %p（%s），未挂调试钩子", static_cast<void*>(L), discovery);
    return 0;
}

void Emmy_UnregisterLuaVm(lua_State*) {}

void Emmy_SetProcessInfo(const char*, const char*) {}
}
