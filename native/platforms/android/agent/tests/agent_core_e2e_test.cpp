// 主机端到端测试：真 Lua 5.4（编成符号隐藏的 libfakeunreal.so，dlsym 找不到 lua_*，和设备上裁剪过的 libUnreal.so 一样）
// + agent 主流程（RunAgent：读配置、按 Build ID 找模块、偏移转地址、监听抽象 socket、装 hook、让核心解析 Lua API）
// + 真调试核心 + 测试客户端扮演 IDE 走协议 v3 下断点。
// 唯一替换掉的是内联 hook：主机上没有 ShadowHook，由假后端在游戏线程调 lua_pcallk 前触发入口回调。
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "agent_runner.h"
#include "check.h"
#include "hook_backend.h"
#include "module_info.h"
#include "test_client.h"

using emmy::test::TestClient;
using json = nlohmann::json;
using namespace emmy_agent;

namespace {

const char* kModule = "libfakeunreal.so";
const char* kToken = "0123456789abcdef0123456789abcdef";
const char* kScript = R"(local M = {}
function M.tick(i)
  local doubled = i * 2
  if FAIL then FAIL = nil; local t = nil; return t.x end
  return doubled
end
return M
)";

// ---- 模拟 IDE 的符号提取：读未裁剪 so 的 .symtab（和 Kotlin 的 LuaSymbolResolver 同样的规则） ----
struct SymbolFile {
    std::string buildId;
    std::map<std::string, uint64_t> functions; // 所有 lua_* 以及 luaL_*（测试的"游戏"自己要用 luaL_*）
};

SymbolFile ReadSymbols(const char* path) {
    SymbolFile out;
    std::ifstream f(path, std::ios::binary);
    std::stringstream ss;
    ss << f.rdbuf();
    std::string data = ss.str();
    const auto* eh = reinterpret_cast<const Elf64_Ehdr*>(data.data());
    const auto* sh = reinterpret_cast<const Elf64_Shdr*>(data.data() + eh->e_shoff);
    for (int i = 0; i < eh->e_shnum; ++i) {
        if (sh[i].sh_type == SHT_NOTE && out.buildId.empty())
            out.buildId = ParseGnuBuildId(reinterpret_cast<const uint8_t*>(data.data() + sh[i].sh_offset), sh[i].sh_size);
        if (sh[i].sh_type != SHT_SYMTAB) continue;
        const auto* syms = reinterpret_cast<const Elf64_Sym*>(data.data() + sh[i].sh_offset);
        const char* strtab = data.data() + sh[sh[i].sh_link].sh_offset;
        for (size_t k = 0; k < sh[i].sh_size / sizeof(Elf64_Sym); ++k) {
            const char* name = strtab + syms[k].st_name;
            if (ELF64_ST_TYPE(syms[k].st_info) != STT_FUNC || syms[k].st_shndx == SHN_UNDEF) continue;
            if (std::strncmp(name, "lua_", 4) == 0 || std::strncmp(name, "luaL_", 5) == 0) out.functions[name] = syms[k].st_value;
        }
    }
    return out;
}

std::string WriteConfig(const SymbolFile& syms, const std::string& dir) {
    std::string path = dir + "/agent.cfg";
    std::ofstream f(path);
    f << "format=1\ntoken=" << kToken << "\nbuildId=" << syms.buildId << "\nmodule=" << kModule
      << "\npackage=com.example.e2e\nlua=54\nwaitIde=0\n";
    for (const auto& kv : syms.functions) {
        // IDE 只交 lua_* 和 hook 点 luaL_newstate；其他 luaL_* 核心自带
        if (kv.first.compare(0, 4, "lua_") == 0 || kv.first == "luaL_newstate") {
            char hex[32];
            std::snprintf(hex, sizeof(hex), "0x%llx", static_cast<unsigned long long>(kv.second));
            f << "fn." << kv.first << "=" << hex << "\n";
        }
    }
    return path;
}

std::string ReadFile(const std::string& path) {
    std::ifstream f(path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

// ---- 假 hook 后端：记录 intercept，游戏线程调 lua_pcallk 之前手动触发 ----
struct FakeBackend : HookBackend {
    struct Entry {
        void* func;
        EntryCallback cb;
        void* data;
        std::atomic<bool> removed{false};
    };
    std::vector<Entry*> entries;
    std::mutex mutex;
    const char* Name() const override { return "fake"; }
    bool Init(std::string&) override { return true; }
    void* InterceptEntry(void* func, EntryCallback cb, void* data, std::string&) override {
        std::lock_guard<std::mutex> lock(mutex);
        auto* e = new Entry{func, cb, data};
        entries.push_back(e);
        return e;
    }
    bool RemoveIntercept(void* h) override {
        static_cast<Entry*>(h)->removed = true;
        return true;
    }
    // 主机上没有内联 hook：记下「替身」，游戏通过 Target() 取函数地址时拿到替身，原函数入口就是函数本身
    std::map<void*, void*> replaced;
    void* Replace(void* func, void* replacement, void** original, std::string&) override {
        std::lock_guard<std::mutex> lock(mutex);
        *original = func;
        replaced[func] = replacement;
        return func;
    }
    bool RemoveReplace(void* h) override {
        std::lock_guard<std::mutex> lock(mutex);
        return replaced.erase(h) != 0;
    }
    template <typename T>
    T Target(T func) {
        std::lock_guard<std::mutex> lock(mutex);
        auto it = replaced.find(reinterpret_cast<void*>(func));
        return it == replaced.end() ? func : reinterpret_cast<T>(it->second);
    }
    void Fire(void* func, void* arg) {
        std::lock_guard<std::mutex> lock(mutex);
        for (auto* e : entries)
            if (e->func == func && !e->removed) e->cb(arg, e->data);
    }
    bool CaptureRemoved(void* func) {
        std::lock_guard<std::mutex> lock(mutex);
        for (auto* e : entries)
            if (e->func == func) return e->removed;
        return false;
    }
};

// ---- 游戏：只通过「基址 + 偏移」拿到的函数指针调用 Lua ----
struct lua_State;
using NewState = lua_State* (*)();
using OpenLibs = void (*)(lua_State*);
using LoadBufferX = int (*)(lua_State*, const char*, size_t, const char*, const char*);
using CallK = void (*)(lua_State*, int, int, intptr_t, void*);
using PCallK = int (*)(lua_State*, int, int, int, intptr_t, void*);
using GetField = int (*)(lua_State*, int, const char*);
using PushInteger = void (*)(lua_State*, long long);
using SetTop = void (*)(lua_State*, int);
using GetTop = int (*)(lua_State*);
using Close = void (*)(lua_State*);
using PushBoolean = void (*)(lua_State*, int);
using SetGlobal = void (*)(lua_State*, const char*);

FakeBackend g_backend;
SymbolFile g_syms;
uintptr_t g_bias = 0;
std::atomic<bool> g_stop{false};
std::atomic<int64_t> g_ticks{0};
std::atomic<bool> g_triggerError{false}; // 让下一次 tick 抛一个没人接的错误

template <typename T>
T Fn(const char* name) {
    auto it = g_syms.functions.find(name);
    return it == g_syms.functions.end() ? nullptr : reinterpret_cast<T>(g_bias + it->second);
}

void GameLoop() {
    lua_State* L = g_backend.Target(Fn<NewState>("luaL_newstate"))();
    Fn<OpenLibs>("luaL_openlibs")(L);
    Fn<LoadBufferX>("luaL_loadbufferx")(L, kScript, std::strlen(kScript), "@game/main.lua", nullptr);
    Fn<CallK>("lua_callk")(L, 0, 1, 0, nullptr);
    int mod = Fn<GetTop>("lua_gettop")(L);
    // 真机上 ShadowHook 改的是函数入口，游戏代码照常调原地址；主机上用 Target() 模拟「调用进了替身」
    auto pcallk = g_backend.Target(Fn<PCallK>("lua_pcallk"));
    int64_t i = 0;
    while (!g_stop) {
        Fn<GetField>("lua_getfield")(L, mod, "tick");
        Fn<PushInteger>("lua_pushinteger")(L, ++i);
        if (g_triggerError.exchange(false)) {
            Fn<PushBoolean>("lua_pushboolean")(L, 1);
            Fn<SetGlobal>("lua_setglobal")(L, "FAIL");
        }
        // 宿主自己不给 errfunc：错误「没被接住」，出错即停靠 agent 在替身里插的处理函数
        pcallk(L, 1, 1, 0, 0, nullptr);
        Fn<SetTop>("lua_settop")(L, mod);
        g_ticks = i;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    Fn<Close>("lua_close")(L);
}

json Hello(TestClient& c, const char* token) {
    json args = {{"protocol", 3}, {"client", {{"name", "e2e"}, {"version", "0"}}}, {"token", token}};
    return c.Request("hello", args);
}

bool Ok(const json& r) { return r.is_object() && r.value("ok", false); }

std::string g_statusPath;
std::string g_socket;

} // namespace

TEST_CASE("agent_e2e.dlsym_cannot_see_hidden_lua") {
    // 前提：和设备上裁剪过的 libUnreal.so 一样，按名字找不到 lua_*
    CHECK(dlsym(RTLD_DEFAULT, "lua_pcallk") == nullptr);
    CHECK(!g_syms.functions.empty());
}

TEST_CASE("agent_e2e.status_listening") {
    std::string status;
    for (int i = 0; i < 100; ++i) {
        status = ReadFile(g_statusPath);
        if (status.find("state=listening") != std::string::npos) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    CHECK(status.find("state=listening") != std::string::npos);
    CHECK(status.find("socket=" + g_socket) != std::string::npos);
    CHECK(status.find("hooks=lua_pcallk") != std::string::npos);
}

TEST_CASE("agent_e2e.wrong_token_rejected") {
    TestClient c;
    CHECK(c.ConnectAbstract(g_socket));
    json r = Hello(c, "not-the-token");
    CHECK(r.is_object() && r.contains("error"));
    CHECK(!Ok(r));
    if (r.is_object() && r.contains("error")) CHECK_EQ(r["error"]["code"].get<std::string>(), std::string("AUTH_FAILED"));
}

TEST_CASE("agent_e2e.breakpoint_over_abstract_socket") {
    TestClient c;
    CHECK(c.ConnectAbstract(g_socket));
    json hello = Hello(c, kToken);
    CHECK(Ok(hello));
    if (!Ok(hello)) return;
    // 只有游戏的 VM：核心自己的临时 state（语法检查）不能出现在这里
    CHECK_EQ(hello["body"]["vms"].size(), static_cast<size_t>(1));
    json bp = {{"id", "bp-3"}, {"source", {{"path", "D:/proj/game/main.lua"}}}, {"line", 3},
               {"condition", "i > 2"}}; // 带条件：会用到核心的语法检查 state
    json r = c.Request("setBreakpoints", {{"revision", 1}, {"replaceAll", true}, {"upserts", json::array({bp})}, {"removes", json::array()}});
    CHECK(Ok(r));
    CHECK(Ok(c.Request("configurationDone")));
    json p = c.WaitEvent("paused", 5000);
    CHECK(p.is_object());
    if (!p.is_object()) return;
    CHECK_EQ(p["body"]["top"]["line"].get<int>(), 3);
    json e = c.Request("evaluate", {{"frameId", p["body"]["top"]["frameId"]}, {"expression", "i"}});
    CHECK(Ok(e));
    json vms = c.Request("vms");
    CHECK(Ok(vms));
    CHECK(Ok(c.Request("setBreakpoints", {{"revision", 2}, {"replaceAll", true}, {"upserts", json::array()}, {"removes", json::array()}})));
    CHECK(Ok(c.Request("continue", {{"vmId", p["body"]["vmId"]}, {"pauseId", p["body"]["pauseId"]}})));
    int64_t before = g_ticks;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    CHECK(g_ticks > before); // 游戏继续跑
    c.Request("disconnect");
}

TEST_CASE("agent_e2e.uncaught_error_stops") {
    TestClient c;
    CHECK(c.ConnectAbstract(g_socket));
    json args = {{"protocol", 3}, {"client", {{"name", "e2e"}, {"version", "0"}}}, {"token", kToken},
                 {"caps", json::array({"valuePath", "luaEval", "exceptions"})}};
    CHECK(Ok(c.Request("hello", args)));
    CHECK(Ok(c.Request("setOptions", {{"exceptions", "uncaught"}})));
    CHECK(Ok(c.Request("configurationDone")));
    g_triggerError = true;
    json ex = c.WaitEvent("paused", 5000);
    CHECK(ex.is_object());
    if (!ex.is_object()) return;
    CHECK_EQ(ex["body"]["reason"].get<std::string>(), std::string("exception"));
    CHECK_EQ(ex["body"]["top"]["line"].get<int>(), 4);
    CHECK(ex["body"]["exception"]["uncaught"].get<bool>());
    CHECK(Ok(c.Request("setOptions", {{"exceptions", "none"}})));
    CHECK(Ok(c.Request("continue", {{"vmId", ex["body"]["vmId"]}, {"pauseId", ex["body"]["pauseId"]}})));
    int64_t before = g_ticks;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    CHECK(g_ticks > before); // 宿主 pcall 照常返回错误，游戏继续跑
    CHECK(ReadFile(g_statusPath).find("errorStop=1") != std::string::npos);
    c.Request("disconnect");
}

TEST_CASE("agent_e2e.capture_intercept_removed_after_window") {
    // lua_pcallk 是常驻替身；临时 intercept 只剩 lua_resume，窗口结束后撤掉
    void* resume = reinterpret_cast<void*>(g_bias + g_syms.functions["lua_resume"]);
    bool removed = false;
    for (int i = 0; i < 60 && !removed; ++i) {
        removed = g_backend.CaptureRemoved(resume);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    CHECK(removed);
}

int main() {
    void* h = dlopen(FAKE_UNREAL_PATH, RTLD_NOW | RTLD_LOCAL);
    if (h == nullptr) {
        std::printf("dlopen failed: %s\n", dlerror());
        return 2;
    }
    g_syms = ReadSymbols(FAKE_UNREAL_PATH);
    ModuleInfo m = FindModule(kModule);
    g_bias = m.loadBias;
    char dirTmpl[] = "/tmp/emmyagent_e2eXXXXXX";
    std::string dir = mkdtemp(dirTmpl);
    g_statusPath = dir + "/agent.status";
    g_socket = "emmylua-" + std::to_string(getpid());

    RunOptions options;
    options.configPath = WriteConfig(g_syms, dir);
    options.statusPath = g_statusPath;
    options.backend = &g_backend;
    options.moduleWaitSeconds = 5;
    std::thread agent([options] { RunAgent(options); });
    // 等 agent 进入 listening（hook 装好、Lua API 解析完）再开始跑游戏
    for (int i = 0; i < 100 && ReadFile(g_statusPath).find("state=listening") == std::string::npos; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    std::thread game(GameLoop);

    int rc = emmy::test::RunAll();
    g_stop = true;
    game.join();
    agent.join();
    std::printf("%s\n", ReadFile(g_statusPath).c_str());
    std::remove(options.configPath.c_str());
    std::remove(g_statusPath.c_str());
    rmdir(dir.c_str());
    return rc;
}
