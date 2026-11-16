// agent 入口。三种加载方式走同一个初始化：
//   Android 9+ 运行中附加：cmd activity attach-agent <pid> <so>=<cfg>  → Agent_OnAttach(options=cfg 路径)
//   Android 9+ 启动时附加：am start --attach-agent <so>=<cfg>          → Agent_OnAttach
//   Android 8 及以下：JDWP 让应用 System.load(<so>)                    → JNI_OnLoad，cfg 取 so 同目录的 agent.cfg
// 入口里只解析参数、起后台线程，立刻返回：ART 在加载 agent 时持有锁，耗时操作会卡住应用。
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <dlfcn.h>
#include <map>
#include <pthread.h>
#include <string>
#include <thread>
#include <unistd.h>

#include "agent_config.h"
#include "agent_log.h"
#include "agent_version.h"
#include "emmy_core_abi.h"
#include "hook_backend.h"
#include "lua_capture.h"
#include "module_info.h"
#include "status_file.h"

namespace emmy_agent {

namespace {

std::atomic<bool> g_started{false};
StatusFile g_status;
AgentConfig g_config;
ModuleInfo g_module;
std::map<std::string, uintptr_t> g_addresses;

constexpr const char* kSocketPrefix = "emmylua-";
constexpr int kModuleWaitSeconds = 180;

std::string SelfDirectory() {
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(&SelfDirectory), &info) == 0 || info.dli_fname == nullptr) return std::string();
    std::string path = info.dli_fname;
    size_t slash = path.rfind('/');
    return slash == std::string::npos ? std::string() : path.substr(0, slash);
}

void* ResolveLuaApi(const char* name, void*) {
    auto it = g_addresses.find(name);
    return it == g_addresses.end() ? nullptr : reinterpret_cast<void*>(it->second);
}

void RunAgent(std::string configPath) {
    std::string err;
    if (!LoadAgentConfig(configPath, g_config, err)) {
        g_status.Error(err);
        return;
    }
    AGENT_LOGI("agent %s 启动，包 %s，等待 %s", EMMY_AGENT_VERSION, g_config.packageName.c_str(), g_config.moduleName.c_str());

    // 启动时附加时 libUnreal.so 还没加载：轮询等它出现（游戏 Activity onCreate 里才 loadLibrary）
    g_status.Write("waiting-module", {{"module", g_config.moduleName}});
    for (int i = 0; i < kModuleWaitSeconds * 10; ++i) {
        g_module = FindModule(g_config.moduleName);
        if (g_module.found) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    if (!g_module.found) {
        g_status.Error("进程里一直没有加载 " + g_config.moduleName + "：确认这是游戏进程");
        return;
    }
    if (g_module.buildId.empty()) {
        g_status.Error("设备上的 " + g_config.moduleName + " 没有 Build ID，无法确认和本地符号是同一次构建");
        return;
    }
    if (g_module.buildId != g_config.buildId) {
        g_status.Error("本地 so 和设备上的不是同一次构建（本地 " + g_config.buildId.substr(0, 12) + "…，设备 " +
                       g_module.buildId.substr(0, 12) + "…），请指定这次安装包对应的未裁剪 so");
        return;
    }
    for (const auto& kv : g_config.functions) {
        g_addresses[kv.first] = g_module.loadBias + static_cast<uintptr_t>(kv.second);
    }

    Emmy_SetProcessInfo(g_config.packageName.c_str(), g_module.buildId.c_str());
    Emmy_SetLuaApiResolver(ResolveLuaApi, nullptr);

    std::string socketName = kSocketPrefix + std::to_string(getpid());
    char listenErr[256] = {0};
    if (Emmy_ListenAbstract(socketName.c_str(), g_config.token.c_str(), listenErr, sizeof(listenErr)) != 0) {
        g_status.Error(std::string("监听 ") + socketName + " 失败：" + listenErr);
        return;
    }

    HookBackend* backend = CreateDefaultHookBackend();
    if (!backend->Init(err)) {
        g_status.Error(err);
        return;
    }
    static LuaCapture capture(*backend, DefaultCoreApi());
    if (!capture.Install(g_addresses, g_config.waitForIde, err)) {
        g_status.Error(err);
        return;
    }
    g_status.Write("listening", {{"socket", socketName}, {"module", g_module.path}, {"hooks", capture.Describe()},
                                 {"hookBackend", backend->Name()}});
    AGENT_LOGI("监听 localabstract:%s，hook：%s", socketName.c_str(), capture.Describe().c_str());

    // 抓到 L 后过了捕获窗口就撤掉临时 intercept。agent 常驻进程（JVMTI agent 不能卸载），线程不退出。
    while (capture.CaptureActive()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        capture.Tick();
    }
    g_status.Write("listening", {{"socket", socketName}, {"module", g_module.path}, {"vms", std::to_string(capture.KnownVmCount())},
                                 {"hookBackend", backend->Name()}});
}

void Start(const char* options, const char* entry) {
    std::string dir = SelfDirectory();
    if (!dir.empty()) g_status.SetPath(dir + "/agent.status");
    if (g_started.exchange(true)) {
        // 同一进程再次附加：IDE 会先检查 maps 并复用，正常走不到这里
        AGENT_LOGW("agent 已在运行，忽略第二次加载（%s）", entry);
        return;
    }
    std::string configPath = (options != nullptr && options[0] != '\0') ? options : dir + "/agent.cfg";
    AGENT_LOGI("%s，配置 %s", entry, configPath.c_str());
    try {
        std::thread(RunAgent, configPath).detach();
    } catch (const std::exception& e) {
        g_status.Error(std::string("无法创建 agent 线程：") + e.what());
    }
}

} // namespace

} // namespace emmy_agent

// ---- 导出的入口，只有这三个符号对外可见（见 exports.map） ----


extern "C" __attribute__((visibility("default"))) int Agent_OnAttach(void*, char* options, void*) {
    emmy_agent::Start(options, "Agent_OnAttach");
    return 0; // JNI_OK
}

extern "C" __attribute__((visibility("default"))) int Agent_OnLoad(void*, char* options, void*) {
    emmy_agent::Start(options, "Agent_OnLoad");
    return 0;
}

extern "C" __attribute__((visibility("default"))) int JNI_OnLoad(void*, void*) {
    emmy_agent::Start(nullptr, "JNI_OnLoad");
    return 0x00010006; // JNI_VERSION_1_6
}
