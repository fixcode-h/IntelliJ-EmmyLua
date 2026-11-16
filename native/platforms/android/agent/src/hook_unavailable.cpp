// 没有编入 ShadowHook 的构建（主机测试、未下载第三方库）用这个实现，Init 明确报错。
#include "hook_backend.h"

namespace emmy_agent {

namespace {
class UnavailableBackend final : public HookBackend {
public:
    const char* Name() const override { return "none"; }
    bool Init(std::string& err) override {
        err = "这个 agent 构建没有编入内联 hook（ShadowHook），无法抓取 lua_State";
        return false;
    }
    void* InterceptEntry(void*, EntryCallback, void*, std::string& err) override {
        err = "没有内联 hook";
        return nullptr;
    }
    bool RemoveIntercept(void*) override { return false; }
    void* Replace(void*, void*, void**, std::string& err) override {
        err = "没有内联 hook";
        return nullptr;
    }
    bool RemoveReplace(void*) override { return false; }
};
} // namespace

HookBackend* CreateDefaultHookBackend() {
    static UnavailableBackend backend;
    return &backend;
}

} // namespace emmy_agent
