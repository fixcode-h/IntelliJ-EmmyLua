// agent 平台无关部分的主机单测：配置解析、模块查找与 Build ID、抓 lua_State 的状态机、状态文件。
// 内联 hook 本身（ShadowHook）只能在真机上验证，见 native/platforms/android/README.md 的验收步骤。
#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <fstream>
#include <link.h>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

#include "agent_config.h"
#include "emmy_core_abi.h"
#include "hook_backend.h"
#include "lua_capture.h"
#include "module_info.h"
#include "status_file.h"

using namespace emmy_agent;

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

// ---- 假核心：记录调用 ----
static std::vector<std::string> g_coreCalls;
static int g_waitCalls = 0;
extern "C" {
void Emmy_SetLuaApiResolver(void* (*)(const char*, void*), void*) {}
int Emmy_ListenAbstract(const char*, const char*, char*, size_t) { return 0; }
uint64_t Emmy_RegisterLuaVm(lua_State* L, const char* discovery) {
    char buf[128];
    std::snprintf(buf, sizeof(buf), "reg %p %s", static_cast<void*>(L), discovery);
    g_coreCalls.push_back(buf);
    return reinterpret_cast<uintptr_t>(L);
}
void Emmy_UnregisterLuaVm(lua_State* L) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "unreg %p", static_cast<void*>(L));
    g_coreCalls.push_back(buf);
}
void Emmy_SetProcessInfo(const char*, const char*) {}
}
static int FakeWait(int) {
    ++g_waitCalls;
    return 0;
}

// ---- 假 hook 后端：记下注册的回调，测试里手动触发 ----
struct FakeBackend : HookBackend {
    struct Intercept {
        void* func;
        EntryCallback cb;
        void* data;
        bool removed = false;
    };
    struct Replacement {
        void* func;
        void* replacement;
        void** original;
    };
    std::vector<Intercept> intercepts;
    std::vector<Replacement> replacements;
    bool failReplace = false; // 模拟 Replace 不可用：pcall 退回临时 intercept
    const char* Name() const override { return "fake"; }
    bool Init(std::string&) override { return true; }
    void* InterceptEntry(void* func, EntryCallback cb, void* data, std::string&) override {
        intercepts.push_back({func, cb, data});
        return reinterpret_cast<void*>(intercepts.size());
    }
    bool RemoveIntercept(void* h) override {
        intercepts[reinterpret_cast<uintptr_t>(h) - 1].removed = true;
        return true;
    }
    void* Replace(void* func, void* replacement, void** original, std::string& err) override {
        if (failReplace) {
            err = "模拟失败";
            return nullptr;
        }
        replacements.push_back({func, replacement, original});
        return reinterpret_cast<void*>(replacements.size());
    }
    bool RemoveReplace(void*) override { return true; }
    void Fire(void* func, void* arg) {
        for (auto& i : intercepts)
            if (i.func == func && !i.removed) i.cb(arg, i.data);
    }
};

static lua_State* FakeOrigNewState(void*, void*) { return reinterpret_cast<lua_State*>(0x5000); }

// 被替换的 lua_pcallk 的「原函数」：记下实际传进来的 errfunc
static int g_lastErrfunc = -1;
static int g_pcallCalls = 0;
static int FakeOrigPcallk(lua_State*, int, int, int errfunc, intptr_t, void*) {
    g_lastErrfunc = errfunc;
    ++g_pcallCalls;
    return 0;
}
using PcallkFn = int (*)(lua_State*, int, int, int, intptr_t, void*);

// 假核心的出错即停接口：宿主没给 errfunc 时插在位置 5
static std::vector<std::string> g_errorStopCalls;
static int FakePrepare(lua_State*, int nargs, int errfunc) {
    g_errorStopCalls.push_back("prepare " + std::to_string(nargs) + " " + std::to_string(errfunc));
    return errfunc == 0 ? 5 : 0;
}
static void FakeFinish(lua_State*, int h) { g_errorStopCalls.push_back("finish " + std::to_string(h)); }

static void TestConfig() {
    AgentConfig c;
    std::string err;
    std::string text = "format=1\ntoken=abc\nbuildId=60e5\nmodule=libUnreal.so\npackage=com.a.b\nlua=54\nwaitIde=1\n"
                       "fn.lua_sethook=0x25ac\nfn.lua_pcallk=0x1d21\nunknown=1\n";
    CHECK(ParseAgentConfig(text, c, err));
    CHECK(c.token == "abc" && c.buildId == "60e5" && c.moduleName == "libUnreal.so" && c.waitForIde);
    CHECK(c.functions.size() == 2 && c.functions["lua_sethook"] == 0x25ac && c.functions["lua_pcallk"] == 0x1d21);
    CHECK(!ParseAgentConfig("format=2\ntoken=a\nbuildId=b\nmodule=m\nfn.x=0x1\n", c, err) && err.find("格式版本") != std::string::npos);
    CHECK(!ParseAgentConfig("format=1\ntoken=a\nbuildId=b\nmodule=m\nfn.x=12\n", c, err));
    CHECK(!ParseAgentConfig("format=1\nbuildId=b\nmodule=m\nfn.x=0x1\n", c, err));
    CHECK(!LoadAgentConfig("/nonexistent/agent.cfg", c, err) && err.find("读不到") != std::string::npos);
}

static void TestModuleNames() {
    CHECK(ModuleNameMatches("/data/app/x/lib/arm64/libUnreal.so", "libUnreal.so"));
    CHECK(ModuleNameMatches("/data/app/x/base.apk!/lib/arm64-v8a/libUnreal.so", "libUnreal.so"));
    CHECK(ModuleNameMatches("libUnreal.so", "libUnreal.so"));
    CHECK(!ModuleNameMatches("/data/app/x/lib/arm64/libFooUnreal.so", "libUnreal.so"));
    CHECK(!ModuleNameMatches(nullptr, "libUnreal.so"));
    CHECK(!ModuleNameMatches("/a/libUnreal.so.1", "libUnreal.so"));
}

// 直接读磁盘上的 ELF：取 Build ID 和某个符号的 st_value，和 agent 从内存里得到的结果对照
static bool ReadFileSymbol(const char* path, const char* name, uint64_t& value, std::string& buildId) {
    std::ifstream f(path, std::ios::binary);
    std::stringstream ss;
    ss << f.rdbuf();
    std::string data = ss.str();
    if (data.size() < sizeof(Elf64_Ehdr)) return false;
    const auto* eh = reinterpret_cast<const Elf64_Ehdr*>(data.data());
    const auto* sh = reinterpret_cast<const Elf64_Shdr*>(data.data() + eh->e_shoff);
    bool found = false;
    for (int i = 0; i < eh->e_shnum; ++i) {
        if (sh[i].sh_type == SHT_NOTE && buildId.empty())
            buildId = ParseGnuBuildId(reinterpret_cast<const uint8_t*>(data.data() + sh[i].sh_offset), sh[i].sh_size);
        if (sh[i].sh_type != SHT_SYMTAB && sh[i].sh_type != SHT_DYNSYM) continue;
        const auto* syms = reinterpret_cast<const Elf64_Sym*>(data.data() + sh[i].sh_offset);
        const char* strtab = data.data() + sh[sh[i].sh_link].sh_offset;
        for (size_t k = 0; k < sh[i].sh_size / sizeof(Elf64_Sym); ++k) {
            if (std::strcmp(strtab + syms[k].st_name, name) == 0 && syms[k].st_value != 0) {
                value = syms[k].st_value;
                found = true;
            }
        }
    }
    return found;
}

static void TestFindModule() {
    void* h = dlopen(FIXTURE_PATH, RTLD_NOW);
    CHECK(h != nullptr);
    if (h == nullptr) return;
    ModuleInfo m = FindModule("libemmyagentfixture.so");
    CHECK(m.found);
    uint64_t stValue = 0;
    std::string fileBuildId;
    CHECK(ReadFileSymbol(FIXTURE_PATH, "fixture_function", stValue, fileBuildId));
    CHECK(!m.buildId.empty());
    CHECK(m.buildId == fileBuildId);
    // 核心等式：运行地址 = dlpi_addr + st_value（agent 就是这样从偏移表算出 lua_* 地址的）
    void* real = dlsym(h, "fixture_function");
    CHECK(reinterpret_cast<uintptr_t>(real) == m.loadBias + stValue);
    using Fn = int (*)(int);
    CHECK(reinterpret_cast<Fn>(m.loadBias + stValue)(2) == 7);
    CHECK(!FindModule("libnotloaded.so").found);
}

static void TestCapture() {
    g_coreCalls.clear();
    g_waitCalls = 0;
    FakeBackend backend;
    CoreApi api = DefaultCoreApi();
    api.waitForClient = FakeWait;
    LuaCapture capture(backend, api);
    std::map<std::string, uintptr_t> fns = {
        {"lua_pcallk", 0x1000}, {"lua_resume", 0x2000}, {"lua_close", 0x3000}, {"lua_newstate", 0x4000}, {"lua_gettop", 0x10}};
    std::string err;
    CHECK(capture.Install(fns, true, err));
    CHECK(backend.intercepts.size() == 2); // resume（临时）+ close
    CHECK(backend.replacements.size() == 2); // pcallk（常驻替身）+ newstate
    CHECK(capture.HostPcallHooked());
    CHECK(capture.Describe() == "lua_pcallk,lua_resume,lua_close,lua_newstate");
    *backend.replacements[0].original = reinterpret_cast<void*>(FakeOrigPcallk);
    auto pcallk = reinterpret_cast<PcallkFn>(backend.replacements[0].replacement);

    // Arm 之前（核心还没解析 Lua API）到达的回调一律忽略，替身原样转给原函数
    pcallk(reinterpret_cast<lua_State*>(0x900), 0, 0, 0, 0, nullptr);
    CHECK(g_coreCalls.empty());
    CHECK(g_pcallCalls == 1);
    // 被替换的函数交给核心的是 trampoline：核心自己的临时 state、自己的 pcall 都不经过替身
    *backend.replacements[1].original = reinterpret_cast<void*>(FakeOrigNewState);
    CHECK(capture.OriginalFor("lua_newstate") == reinterpret_cast<void*>(FakeOrigNewState));
    CHECK(capture.OriginalFor("lua_pcallk") == reinterpret_cast<void*>(FakeOrigPcallk));
    CHECK(capture.OriginalFor("lua_gettop") == nullptr);
    capture.Arm();

    auto* L1 = reinterpret_cast<lua_State*>(0x100);
    auto* L2 = reinterpret_cast<lua_State*>(0x200);
    pcallk(L1, 0, 0, 0, 0, nullptr);
    pcallk(L1, 0, 0, 0, 0, nullptr); // 同一个 L 不重复登记
    backend.Fire(reinterpret_cast<void*>(0x2000), L2);
    CHECK(g_coreCalls.size() == 2);
    CHECK(g_coreCalls[0].find("android-attach:lua_pcallk") != std::string::npos);
    CHECK(g_coreCalls[1].find("android-attach:lua_resume") != std::string::npos);
    CHECK(g_waitCalls == 1); // 只在第一次抓到时等 IDE

    // 窗口内不撤 intercept；窗口过后撤掉，之后的调用不再登记，pcall 替身常驻
    auto now = LuaCapture::Clock::now();
    capture.Tick(now);
    CHECK(capture.CaptureActive());
    capture.Tick(now + LuaCapture::kCaptureWindow + std::chrono::milliseconds(10));
    CHECK(!capture.CaptureActive());
    CHECK(backend.intercepts[0].removed);
    CHECK(!backend.intercepts[1].removed); // lua_close 常驻
    pcallk(reinterpret_cast<lua_State*>(0x300), 0, 0, 0, 0, nullptr);
    CHECK(g_coreCalls.size() == 2);

    // lua_newstate 替身：调原函数并登记新 VM
    using NewState = lua_State* (*)(void*, void*);
    lua_State* created = reinterpret_cast<NewState>(backend.replacements[1].replacement)(nullptr, nullptr);
    CHECK(created == reinterpret_cast<lua_State*>(0x5000));
    CHECK(g_coreCalls.size() == 3 && g_coreCalls[2].find("lua_newstate") != std::string::npos);

    // lua_close：通知核心并忘掉这个 L
    backend.Fire(reinterpret_cast<void*>(0x3000), L1);
    CHECK(g_coreCalls.back().find("unreg") == 0);
    CHECK(capture.KnownVmCount() == 2);
}

// 出错即停：宿主 pcall 没有 errfunc 时插核心的处理函数，返回后移走；带续延的、已有 errfunc 的不插
static void TestHostPcallErrorStop() {
    g_errorStopCalls.clear();
    FakeBackend backend;
    CoreApi api = DefaultCoreApi();
    api.prepareHostPcall = FakePrepare;
    api.finishHostPcall = FakeFinish;
    LuaCapture capture(backend, api);
    std::string err;
    CHECK(capture.Install({{"lua_pcallk", 0x1000}}, false, err));
    *backend.replacements[0].original = reinterpret_cast<void*>(FakeOrigPcallk);
    auto pcallk = reinterpret_cast<PcallkFn>(backend.replacements[0].replacement);
    auto* L = reinterpret_cast<lua_State*>(0x100);
    pcallk(L, 2, 1, 0, 0, nullptr); // Arm 之前不插
    CHECK(g_errorStopCalls.empty() && g_lastErrfunc == 0);
    capture.Arm();
    pcallk(L, 2, 1, 0, 0, nullptr);
    CHECK(g_lastErrfunc == 5);
    CHECK(g_errorStopCalls.size() == 2 && g_errorStopCalls[0] == "prepare 2 0" && g_errorStopCalls[1] == "finish 5");
    g_errorStopCalls.clear();
    pcallk(L, 1, 0, 3, 0, nullptr); // 宿主已有 errfunc：核心返回 0，原样调用
    CHECK(g_lastErrfunc == 3 && g_errorStopCalls.size() == 1);
    g_errorStopCalls.clear();
    int dummy = 0;
    pcallk(L, 1, 0, 0, 7, &dummy); // 带续延：不插
    CHECK(g_lastErrfunc == 0 && g_errorStopCalls.empty());
}

// Replace 不可用时 pcall 退回临时 intercept：还能抓 L，只是没有出错即停
static void TestPcallFallsBackToIntercept() {
    g_coreCalls.clear();
    FakeBackend backend;
    backend.failReplace = true;
    LuaCapture capture(backend, DefaultCoreApi());
    std::string err;
    CHECK(capture.Install({{"lua_pcallk", 0x1000}}, false, err));
    CHECK(!capture.HostPcallHooked());
    CHECK(backend.intercepts.size() == 1);
    CHECK(capture.OriginalFor("lua_pcallk") == nullptr);
    capture.Arm();
    backend.Fire(reinterpret_cast<void*>(0x1000), reinterpret_cast<lua_State*>(0x100));
    CHECK(g_coreCalls.size() == 1);
}

static uint64_t FailRegister(lua_State*, const char*) { return 0; }
static const char* FakeLastError() { return "找不到 Lua 函数：lua_setlocal"; }

static void TestRegisterFailureIsReported() {
    FakeBackend backend;
    CoreApi api = DefaultCoreApi();
    api.registerVm = FailRegister;
    api.lastError = FakeLastError;
    LuaCapture capture(backend, api);
    std::string err;
    CHECK(capture.Install({{"lua_pcallk", 0x1000}}, false, err));
    capture.Arm();
    *backend.replacements[0].original = reinterpret_cast<void*>(FakeOrigPcallk);
    reinterpret_cast<PcallkFn>(backend.replacements[0].replacement)(reinterpret_cast<lua_State*>(0x100), 0, 0, 0, 0, nullptr);
    CHECK(capture.LastError().find("lua_setlocal") != std::string::npos);
}

static void TestCaptureNeedsEntry() {
    FakeBackend backend;
    LuaCapture capture(backend, DefaultCoreApi());
    std::string err;
    CHECK(!capture.Install({{"lua_gettop", 0x10}}, false, err));
    CHECK(err.find("lua_pcallk") != std::string::npos);
}

static void TestStatusFile() {
    char tmpl[] = "/tmp/emmyagent_statusXXXXXX";
    int fd = mkstemp(tmpl);
    CHECK(fd >= 0);
    close(fd);
    StatusFile s;
    s.SetPath(tmpl);
    s.Write("listening", {{"socket", "emmylua-1"}, {"error", "a\nb"}});
    std::ifstream f(tmpl);
    std::stringstream ss;
    ss << f.rdbuf();
    std::string text = ss.str();
    CHECK(text.find("state=listening\n") != std::string::npos);
    CHECK(text.find("socket=emmylua-1\n") != std::string::npos);
    CHECK(text.find("error=a b\n") != std::string::npos); // 换行被替换，不破坏按行解析
    CHECK(text.find("version=") == 0);
    std::remove(tmpl);
}

static void TestUnavailableBackend() {
    std::string err;
    CHECK(!CreateDefaultHookBackend()->Init(err));
    CHECK(err.find("ShadowHook") != std::string::npos);
}

int main() {
    TestConfig();
    TestModuleNames();
    TestFindModule();
    TestCapture();
    TestCaptureNeedsEntry();
    TestRegisterFailureIsReported();
    TestHostPcallErrorStop();
    TestPcallFallsBackToIntercept();
    TestStatusFile();
    TestUnavailableBackend();
    if (g_failures == 0) std::printf("agent_host_test: all passed\n");
    return g_failures == 0 ? 0 : 1;
}
