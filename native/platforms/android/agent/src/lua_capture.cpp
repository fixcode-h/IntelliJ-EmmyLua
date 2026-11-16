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

// lua_pcallk（5.2+）/ lua_pcall（5.1）的替身：抓 L，并给出错即停插错误处理函数。写法和 Windows 附加（emmy_attach）一致。
using PcallkFn = int (*)(lua_State*, int, int, int, intptr_t, void*);
using PcallFn = int (*)(lua_State*, int, int, int);
PcallkFn g_origPcallk = nullptr;
PcallFn g_origPcall = nullptr;

int ProxyPcallk(lua_State* L, int nargs, int nresults, int errfunc, intptr_t ctx, void* k) {
    LuaCapture* c = g_capture;
    if (c == nullptr) return g_origPcallk(L, nargs, nresults, errfunc, ctx, k);
    c->OnCaptureEntry(L, "lua_pcallk");
    // 带续延的调用可能 yield 出去，返回时栈布局不由我们控制，不插
    const int h = k != nullptr ? 0 : c->PrepareHostPcall(L, nargs, errfunc);
    if (h == 0) return g_origPcallk(L, nargs, nresults, errfunc, ctx, k);
    const int rc = g_origPcallk(L, nargs, nresults, h, ctx, k);
    c->FinishHostPcall(L, h);
    return rc;
}

int ProxyPcall(lua_State* L, int nargs, int nresults, int errfunc) {
    LuaCapture* c = g_capture;
    if (c == nullptr) return g_origPcall(L, nargs, nresults, errfunc);
    c->OnCaptureEntry(L, "lua_pcall");
    const int h = c->PrepareHostPcall(L, nargs, errfunc);
    if (h == 0) return g_origPcall(L, nargs, nresults, errfunc);
    const int rc = g_origPcall(L, nargs, nresults, h);
    c->FinishHostPcall(L, h);
    return rc;
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
    api.lastError = Emmy_LastError;          // 弱符号，可能为空
    api.prepareHostPcall = Emmy_PrepareHostPcall; // 弱符号，可能为空
    api.finishHostPcall = Emmy_FinishHostPcall;
    return api;
}

LuaCapture::LuaCapture(HookBackend& backend, CoreApi core) : backend_(backend), core_(core) {}

bool LuaCapture::Install(const std::map<std::string, uintptr_t>& functions, bool waitForIde, std::string& err) {
    waitForIde_ = waitForIde;
    g_capture = this;
    // 进程里只装一次；清掉是为了 OriginalFor 只对这次真正替换成功的函数返回 trampoline
    g_origPcallk = nullptr;
    g_origPcall = nullptr;
    g_origNewState = nullptr;
    g_origLNewState = nullptr;
    auto find = [&](const char* name) -> void* {
        auto it = functions.find(name);
        return it == functions.end() ? nullptr : reinterpret_cast<void*>(it->second);
    };

    // 抓 L 的入口：5.2+ 的 lua_pcall 是宏，实体是 lua_pcallk；5.1 / LuaJIT 是 lua_pcall。
    // pcall 两个先试常驻 Replace（还能做出错即停），不行再临时 intercept；lua_resume 兜底：只跑协程、不走 pcall 的宿主也能抓到。
    static const char* kCaptureTargets[] = {"lua_pcallk", "lua_pcall", "lua_resume"};
    static EntryTag tags[3];
    std::string lastErr;
    bool anyEntry = false;
    for (int i = 0; i < 3; ++i) {
        void* fn = find(kCaptureTargets[i]);
        if (fn == nullptr) continue;
        std::string e;
        if (i < 2) {
            void* proxy = i == 0 ? reinterpret_cast<void*>(ProxyPcallk) : reinterpret_cast<void*>(ProxyPcall);
            void** orig = i == 0 ? reinterpret_cast<void**>(&g_origPcallk) : reinterpret_cast<void**>(&g_origPcall);
            if (void* h = backend_.Replace(fn, proxy, orig, e)) {
                persistentHandles_.push_back(h);
                hookedNames_.push_back(kCaptureTargets[i]);
                pcallReplaced_ = true;
                anyEntry = true;
                continue;
            }
            AGENT_LOGW("%s 替换失败（%s），改用临时 intercept，出错即停不可用", kCaptureTargets[i], e.c_str());
            e.clear();
        }
        tags[i] = EntryTag{this, kCaptureTargets[i]};
        void* h = backend_.InterceptEntry(fn, OnCaptureCallback, &tags[i], e);
        if (h == nullptr) {
            lastErr = std::string(kCaptureTargets[i]) + "：" + e;
            AGENT_LOGW("%s", lastErr.c_str());
            continue;
        }
        captureHandles_.push_back(h);
        hookedNames_.push_back(kCaptureTargets[i]);
        anyEntry = true;
    }
    if (!anyEntry) {
        err = lastErr.empty() ? "符号表里没有 lua_pcallk / lua_pcall / lua_resume，无法抓到 lua_State"
                              : "无法 hook 抓取入口（" + lastErr + "）";
        return false;
    }
    // 先不开始抓：等 Arm()
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

void LuaCapture::Arm() { armed_ = true; }

void* LuaCapture::OriginalFor(const std::string& name) const {
    if (name == "lua_newstate") return reinterpret_cast<void*>(g_origNewState);
    if (name == "luaL_newstate") return reinterpret_cast<void*>(g_origLNewState);
    // 核心自己的 pcall（求值、条件断点）不经过替身，免得被当成宿主调用插错误处理函数
    if (name == "lua_pcallk") return reinterpret_cast<void*>(g_origPcallk);
    if (name == "lua_pcall") return reinterpret_cast<void*>(g_origPcall);
    return nullptr;
}

void LuaCapture::RegisterLocked(lua_State* L, const char* discovery) {
    if (!known_.insert(L).second) return;
    // 不管从哪条路发现的 VM（pcall / resume / lua_newstate），第一次发现都开始计捕获窗口
    if (!firstCaptured_) {
        firstCaptured_ = true;
        firstCaptureAt_ = Clock::now();
    }
    uint64_t vmId = core_.registerVm(L, discovery);
    if (vmId == 0) {
        const char* e = core_.lastError != nullptr ? core_.lastError() : nullptr;
        lastError_ = std::string("登记 VM 失败：") + (e != nullptr && e[0] != '\0' ? e : "未知原因");
        AGENT_LOGE("%s", lastError_.c_str());
        return;
    }
    AGENT_LOGI("登记 VM %p（%s）→ vmId %llu", static_cast<void*>(L), discovery, static_cast<unsigned long long>(vmId));
}

void LuaCapture::OnCaptureEntry(lua_State* L, const char* via) {
    if (L == nullptr || !armed_.load(std::memory_order_acquire) || !captureActive_.load(std::memory_order_relaxed)) return;
    bool shouldWait = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // 协程的 L 也会进来：核心按主线程合并成同一个 VM，这里只负责把看到的 L 都交过去
        if (known_.count(L) == 0) {
            std::string discovery = std::string("android-attach:") + via;
            RegisterLocked(L, discovery.c_str());
        }
        // "启动并暂停"只在 Lua 真正开始执行（第一次 pcall）时等：VM 可能先由 lua_newstate 登记，那时还没有脚本可停
        shouldWait = waitForIde_ && !waitedForIde_;
        waitedForIde_ = true;
    }
    // 在 Lua 线程上等 IDE 连上，之后第一行 Lua 就能停在断点。锁外等待，避免卡住其他线程的回调。
    if (shouldWait && core_.waitForClient != nullptr) {
        AGENT_LOGI("等待 IDE 连接（最多 %d 秒）", kWaitForIdeMs / 1000);
        if (core_.waitForClient(kWaitForIdeMs) != 0) AGENT_LOGW("等待 IDE 超时，游戏继续运行");
    }
}

int LuaCapture::PrepareHostPcall(lua_State* L, int nargs, int errfunc) {
    if (!armed_.load(std::memory_order_acquire) || core_.prepareHostPcall == nullptr) return 0;
    return core_.prepareHostPcall(L, nargs, errfunc);
}

void LuaCapture::FinishHostPcall(lua_State* L, int handlerIdx) {
    if (handlerIdx != 0 && core_.finishHostPcall != nullptr) core_.finishHostPcall(L, handlerIdx);
}

void LuaCapture::OnNewState(lua_State* L, const char* via) {
    if (!armed_.load(std::memory_order_acquire)) return;
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
    AGENT_LOGI("抓取窗口结束，撤掉临时 intercept %zu 个，之后的新 VM 由 lua_newstate hook 发现", toRemove.size());
}

size_t LuaCapture::KnownVmCount() {
    std::lock_guard<std::mutex> lock(mutex_);
    return known_.size();
}

std::string LuaCapture::LastError() {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
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
