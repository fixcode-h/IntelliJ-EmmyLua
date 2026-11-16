// ShadowHook 后端。intercept 在函数入口拿到完整寄存器上下文，回调运行在调用方线程的普通上下文里
// （不是信号处理函数），可以安全地调用核心登记 VM。
#include "hook_backend.h"

#include <new>

#include "shadowhook.h"

namespace emmy_agent {

namespace {

struct InterceptContext {
    HookBackend::EntryCallback cb;
    void* data;
    void* stub;
};

void OnIntercept(shadowhook_cpu_context_t* ctx, void* data) {
    auto* ic = static_cast<InterceptContext*>(data);
    // arm64 的 x0 / arm 的 r0 都是第一个参数
    void* first = reinterpret_cast<void*>(static_cast<uintptr_t>(ctx->regs[0]));
    ic->cb(first, ic->data);
}

class ShadowHookBackend final : public HookBackend {
public:
    const char* Name() const override { return "shadowhook"; }

    bool Init(std::string& err) override {
        // SHARED 模式：游戏或别的 SDK 也 hook 同一地址时可以共存
        int r = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
        if (r != 0) {
            err = std::string("ShadowHook 初始化失败：") + shadowhook_to_errmsg(r);
            return false;
        }
        return true;
    }

    void* InterceptEntry(void* func, EntryCallback cb, void* data, std::string& err) override {
        auto* ic = new (std::nothrow) InterceptContext{cb, data, nullptr};
        if (ic == nullptr) return nullptr;
        void* stub = shadowhook_intercept_func_addr(func, OnIntercept, ic, SHADOWHOOK_INTERCEPT_DEFAULT);
        if (stub == nullptr) {
            err = std::string("intercept 失败：") + shadowhook_to_errmsg(shadowhook_get_errno());
            delete ic;
            return nullptr;
        }
        ic->stub = stub;
        return ic;
    }

    bool RemoveIntercept(void* handle) override {
        auto* ic = static_cast<InterceptContext*>(handle);
        if (ic == nullptr) return false;
        bool ok = shadowhook_unintercept(ic->stub) == 0;
        // 不 delete ic：可能还有线程正在回调里，agent 常驻进程，泄漏几十字节无所谓
        return ok;
    }

    void* Replace(void* func, void* replacement, void** original, std::string& err) override {
        void* stub = shadowhook_hook_func_addr(func, replacement, original);
        if (stub == nullptr) err = std::string("hook 失败：") + shadowhook_to_errmsg(shadowhook_get_errno());
        return stub;
    }

    bool RemoveReplace(void* handle) override { return shadowhook_unhook(handle) == 0; }
};

} // namespace

HookBackend* CreateDefaultHookBackend() {
    static ShadowHookBackend backend;
    return &backend;
}

} // namespace emmy_agent
