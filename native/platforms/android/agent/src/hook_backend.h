// 内联 hook 抽象：Android 上用 ShadowHook（MIT，静态编进 agent，不依赖游戏里有没有）；
// 主机测试用假实现直接触发回调。
#pragma once

#include <string>

namespace emmy_agent {

class HookBackend {
public:
    // 在函数入口回调，参数是函数的第一个参数（对 lua_pcallk / lua_resume / lua_close 就是 lua_State*）。
    using EntryCallback = void (*)(void* firstArg, void* data);

    virtual ~HookBackend() = default;
    virtual const char* Name() const = 0;
    virtual bool Init(std::string& err) = 0;
    // 只观察入口、不改行为（ShadowHook intercept）。返回句柄，失败返回 nullptr。
    virtual void* InterceptEntry(void* func, EntryCallback cb, void* data, std::string& err) = 0;
    virtual bool RemoveIntercept(void* handle) = 0;
    // 替换整个函数，*original 是调用原函数的入口（要拿返回值时用，如 lua_newstate）。
    virtual void* Replace(void* func, void* replacement, void** original, std::string& err) = 0;
    virtual bool RemoveReplace(void* handle) = 0;
};

// 当前平台的默认实现；没有可用的内联 hook 时返回的实现 Init 会失败并说明原因。
HookBackend* CreateDefaultHookBackend();

} // namespace emmy_agent
