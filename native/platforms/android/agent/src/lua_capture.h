// 抓 lua_State：临时 intercept lua_pcallk / lua_pcall / lua_resume，在 Lua 所属线程上拿到 L 交给核心挂钩子，
// 抓到后过一个短窗口（让同时存在的其他 VM 也被看到）就撤掉，热路径恢复原样。
// lua_newstate / luaL_newstate / lua_close 常驻 hook，跟踪之后新建和关闭的 VM（UE 切图、PIE 重启会重建 VM）。
#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "hook_backend.h"

struct lua_State;

namespace emmy_agent {

// 核心接口做成函数指针，单测里换成假实现。
struct CoreApi {
    uint64_t (*registerVm)(lua_State* L, const char* discovery);
    void (*unregisterVm)(lua_State* L);
    int (*waitForClient)(int timeoutMs); // 可为空
};

CoreApi DefaultCoreApi();

class LuaCapture {
public:
    using Clock = std::chrono::steady_clock;

    LuaCapture(HookBackend& backend, CoreApi core);

    // functions：函数名 → 运行时地址。至少要有 lua_pcallk / lua_pcall / lua_resume 之一。
    bool Install(const std::map<std::string, uintptr_t>& functions, bool waitForIde, std::string& err);

    // 由 worker 线程周期调用：捕获窗口结束后撤掉临时 intercept。
    void Tick(Clock::time_point now = Clock::now());

    // hook 回调（也供单测直接调用）。
    void OnCaptureEntry(lua_State* L, const char* via);
    void OnNewState(lua_State* L, const char* via);
    void OnClose(lua_State* L);

    bool CaptureActive() const { return captureActive_.load(); }
    size_t KnownVmCount();
    std::string Describe();

    // 抓到第一个 L 后保留 intercept 的时间。
    static constexpr std::chrono::milliseconds kCaptureWindow{3000};
    static constexpr int kWaitForIdeMs = 60000;

private:
    void RegisterLocked(lua_State* L, const char* discovery);

    HookBackend& backend_;
    CoreApi core_;
    bool waitForIde_ = false;
    std::mutex mutex_;
    std::set<lua_State*> known_;
    std::vector<void*> captureHandles_;
    std::vector<void*> persistentHandles_;
    std::vector<std::string> hookedNames_;
    std::atomic<bool> captureActive_{false};
    bool firstCaptured_ = false;
    bool waitedForIde_ = false;
    Clock::time_point firstCaptureAt_;
};

} // namespace emmy_agent
