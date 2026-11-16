// agent 主流程（和入口分开，主机端到端测试直接调用它，换上假的 hook 后端）。
#include "agent_runner.h"

#include <chrono>
#include <map>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "agent_config.h"
#include "agent_log.h"
#include "agent_version.h"
#include "emmy_core_abi.h"
#include "lua_capture.h"
#include "module_info.h"
#include "status_file.h"

namespace emmy_agent {

namespace {

StatusFile g_status;
AgentConfig g_config;
ModuleInfo g_module;
std::map<std::string, uintptr_t> g_addresses;

constexpr const char* kSocketPrefix = "emmylua-";

LuaCapture* g_capture = nullptr;

void* ResolveLuaApi(const char* name, void*) {
    if (g_capture != nullptr) {
        if (void* orig = g_capture->OriginalFor(name)) return orig;
    }
    auto it = g_addresses.find(name);
    return it == g_addresses.end() ? nullptr : reinterpret_cast<void*>(it->second);
}

} // namespace

void RunAgent(const RunOptions& options) {
    std::string err;
    g_status.SetPath(options.statusPath);
    const std::string& configPath = options.configPath;
    if (!LoadAgentConfig(configPath, g_config, err)) {
        g_status.Error(err);
        return;
    }
    AGENT_LOGI("agent %s 启动，包 %s，等待 %s", EMMY_AGENT_VERSION, g_config.packageName.c_str(), g_config.moduleName.c_str());

    // 启动时附加时 libUnreal.so 还没加载：轮询等它出现（游戏 Activity onCreate 里才 loadLibrary）
    g_status.Write("waiting-module", {{"module", g_config.moduleName}});
    for (int i = 0; i < options.moduleWaitSeconds * 10; ++i) {
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

    std::string socketName = kSocketPrefix + std::to_string(getpid());
    char listenErr[256] = {0};
    if (Emmy_ListenAbstract(socketName.c_str(), g_config.token.c_str(), listenErr, sizeof(listenErr)) != 0) {
        g_status.Error(std::string("监听 ") + socketName + " 失败：" + listenErr);
        return;
    }

    HookBackend* backend = options.backend != nullptr ? options.backend : CreateDefaultHookBackend();
    if (!backend->Init(err)) {
        g_status.Error(err);
        return;
    }
    static LuaCapture capture(*backend, DefaultCoreApi());
    g_capture = &capture;
    if (!capture.Install(g_addresses, g_config.waitForIde, err)) {
        g_status.Error(err);
        return;
    }
    // 顺序要紧：核心在 Emmy_SetLuaApiResolver 里一次性解析全部 Lua 函数，
    // 必须在 hook 装好之后，才能把 lua_newstate 等解析成 trampoline（核心自己的临时 state 不被当成游戏 VM）；
    // 解析完才 Arm，之前到达的 hook 回调直接忽略
    Emmy_SetLuaApiResolver(ResolveLuaApi, nullptr);
    if (Emmy_LastError != nullptr && Emmy_LastError()[0] != '\0') {
        g_status.Error(std::string("调试核心无法使用游戏里的 Lua：") + Emmy_LastError());
        return;
    }
    capture.Arm();
    g_status.Write("listening", {{"socket", socketName}, {"module", g_module.path}, {"hooks", capture.Describe()},
                                 {"hookBackend", backend->Name()}, {"errorStop", capture.HostPcallHooked() ? "1" : "0"}});
    AGENT_LOGI("监听 localabstract:%s，hook：%s", socketName.c_str(), capture.Describe().c_str());

    // 抓到 L 后过了捕获窗口就停止抓取、撤掉临时 intercept（pcall 替身常驻，出错即停要用）。agent 常驻进程（JVMTI agent 不能卸载），线程不退出。
    while (capture.CaptureActive()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        capture.Tick();
    }
    std::vector<std::pair<std::string, std::string>> fields = {
        {"socket", socketName}, {"module", g_module.path}, {"vms", std::to_string(capture.KnownVmCount())},
        {"hookBackend", backend->Name()}, {"errorStop", capture.HostPcallHooked() ? "1" : "0"}};
    std::string vmErr = capture.LastError();
    if (!vmErr.empty()) fields.emplace_back("warning", vmErr);
    g_status.Write("listening", fields);
}

} // namespace emmy_agent
