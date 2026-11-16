#include "lua_capture.h"

#include "agent_log.h"
#include "emmy_core_abi.h"

namespace emmy_agent {

namespace {

LuaCapture* g_capture = nullptr;

// lua_newstate / luaL_newstate 的替身：要拿返回值，所以用 Replace 而不是 intercept。
using NewStateFn = lua_State* (*)(void*, void*);
using LNewStateFn = lua_State* (*)();
NewStateFn g_origNewState = nullptr;
LNewStateFn g_origLNewState = nullptr;

lua_State* ProxyNewState(void* alloc, void* ud) {
    lua_State* L = g_origNewState(alloc, ud);
    if (L != nullptr && g_capture != nullptr) g_capture->OnNewState(L, "android-attach:lua_newstate");
    return L;
}

lua_State* ProxyLNewState() {
    lua_State* L = g_origLNewState();
    if (L != nullptr && g_capture != nullptr) g_capture->OnNewState(L, "android-attach:luaL_newstate");
    return L;
}

struct EntryTag {
    LuaCapture* capture;
    const char* via;
};

void OnCaptureCallback(void* first, void* data) {
    auto* tag = static_cast<EntryTag*>(data);
    tag->capture->OnCaptureEntry(static_cast<lua_State*>(first), tag->via);
}

void OnCloseCallback(void* first, void* data) {
    static_cast<LuaCapture*>(data)->OnClose(static_cast<lua_State*>(first));
}

uint64_t RegisterVmWrapper(lua_State* L, const char* discovery) { return Emmy_RegisterLuaVm(L, discovery); }
void UnregisterVmWrapper(lua_State* L) { Emmy_UnregisterLuaVm(L); }

} // namespace

CoreApi DefaultCoreApi() {
    CoreApi api{};
    api.registerVm = RegisterVmWrapper;
    api.unregisterVm = UnregisterVmWrapper;
    api.waitForClient = Emmy_WaitForClient; // 弱符号，可能为空
    return api;
}

LuaCapture::LuaCapture(HookBackend& backend, CoreApi core) : backend_(backend), core_(core) {}

bool LuaCapture::Install(const std::map<std::string, uintptr_t>& functions, bool waitForIde, std::string& err) {
    waitForIde_ = waitForIde;
    g_capture = this;
    auto find = [&](const char* name) -> void* {
        auto it = functions.find(name);
        return it == functions.end() ? nullptr : reinterpret_cast<void*>(it->second);
    };

    // 抓 L 的入口：5.2+ 的 lua_pcall 是宏，实体是 lua_pcallk；5.1 / LuaJIT 是 lua_pcall。
    // lua_resume 兜底：只跑协程、不走 pcall 的宿主也能抓到。
    static const char* kCaptureTargets[] = {"lua_pcallk", "lua_pcall", "lua_resume"};
    static EntryTag tags[3];
    std::string lastErr;
    for (int i = 0; i < 3; ++i) {
        void* fn = find(kCaptureTargets[i]);
        if (fn == nullptr) continue;
        tags[i] = EntryTag{this, kCaptureTargets[i]};
        std::string e;
        void* h = backend_.InterceptEntry(fn, OnCaptureCallback, &tags[i], e);
        if (h == nullptr) {
            lastErr = std::string(kCaptureTargets[i]) + "：" + e;
            AGENT_LOGW("%s", lastErr.c_str());
            continue;
        }
        captureHandles_.push_back(h);
        hookedNames_.push_back(kCaptureTargets[i]);
    }
    if (captureHandles_.empty()) {
        err = lastErr.empty() ? "符号表里没有 lua_pcallk / lua_pcall / lua_resume，无法抓到 lua_State"
                              : "无法 hook 抓取入口（" + lastErr + "）";
        return false;
    }
    captureActive_ = true;

    // 常驻：VM 生命周期。失败只告警，不影响首次附加。
    if (void* fn = find("lua_close")) {
        std::string e;
        if (void* h = backend_.InterceptEntry(fn, OnCloseCallback, this, e)) {
            persistentHandles_.push_back(h);
            hookedNames_.push_back("lua_close");
        } else {
            AGENT_LOGW("lua_close：%s（VM 关闭后 IDE 可能看到失效的 VM）", e.c_str());
        }
    }
    if (void* fn = find("lua_newstate")) {
        std::string e;
        if (void* h = backend_.Replace(fn, reinterpret_cast<void*>(ProxyNewState), reinterpret_cast<void**>(&g_origNewState), e)) {
            persistentHandles_.push_back(h);
            hookedNames_.push_back("lua_newstate");
        } else {
            AGENT_LOGW("lua_newstate：%s（之后新建的 VM 要等它执行 pcall 才能被发现）", e.c_str());
        }
    }
    if (void* fn = find("luaL_newstate")) {
        std::string e;
        if (void* h = backend_.Replace(fn, reinterpret_cast<void*>(ProxyLNewState), reinterpret_cast<void**>(&g_origLNewState), e)) {
            persistentHandles_.push_back(h);
            hookedNames_.push_back("luaL_newstate");
        }
    }
    return true;
}

void LuaCapture::RegisterLocked(lua_State* L, const char* discovery) {
    if (!known_.insert(L).second) return;
    uint64_t vmId = core_.registerVm(L, discovery);
    AGENT_LOGI("登记 VM %p（%s）→ vmId %llu", static_cast<void*>(L), discovery, static_cast<unsigned long long>(vmId));
}

void LuaCapture::OnCaptureEntry(lua_State* L, const char* via) {
    if (L == nullptr || !captureActive_.load(std::memory_order_relaxed)) return;
    bool shouldWait = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (known_.count(L) != 0) return;
        // 协程的 L 也会进来：核心按主线程合并成同一个 VM，这里只负责把看到的 L 都交过去
        std::string discovery = std::string("android-attach:") + via;
        RegisterLocked(L, discovery.c_str());
        if (!firstCaptured_) {
            firstCaptured_ = true;
            firstCaptureAt_ = Clock::now();
            shouldWait = waitForIde_ && !waitedForIde_;
            waitedForIde_ = true;
        }
    }
    // "启动并暂停"：在 Lua 线程上等 IDE 连上，之后第一行 Lua 就能停在断点。锁外等待，避免卡住其他线程的回调。
    if (shouldWait && core_.waitForClient != nullptr) {
        AGENT_LOGI("等待 IDE 连接（最多 %d 秒）", kWaitForIdeMs / 1000);
        if (core_.waitForClient(kWaitForIdeMs) != 0) AGENT_LOGW("等待 IDE 超时，游戏继续运行");
    }
}

void LuaCapture::OnNewState(lua_State* L, const char* via) {
    std::lock_guard<std::mutex> lock(mutex_);
    RegisterLocked(L, via);
}

void LuaCapture::OnClose(lua_State* L) {
    bool known;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        known = known_.erase(L) != 0;
    }
    // 不认识的 L 也通知核心：可能是核心自己通过协程等途径发现的 VM
    core_.unregisterVm(L);
    if (known) AGENT_LOGI("VM %p 关闭", static_cast<void*>(L));
}

void LuaCapture::Tick(Clock::time_point now) {
    std::vector<void*> toRemove;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!captureActive_ || !firstCaptured_ || now - firstCaptureAt_ < kCaptureWindow) return;
        captureActive_ = false;
        toRemove.swap(captureHandles_);
    }
    for (void* h : toRemove) backend_.RemoveIntercept(h);
    AGENT_LOGI("已撤掉临时 intercept（%zu 个），之后的新 VM 由 lua_newstate hook 发现", toRemove.size());
}

size_t LuaCapture::KnownVmCount() {
    std::lock_guard<std::mutex> lock(mutex_);
    return known_.size();
}

std::string LuaCapture::Describe() {
    std::string s;
    for (const auto& n : hookedNames_) {
        if (!s.empty()) s += ",";
        s += n;
    }
    return s;
}

} // namespace emmy_agent
