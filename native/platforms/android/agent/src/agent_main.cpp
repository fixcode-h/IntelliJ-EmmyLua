// agent 入口。三种加载方式走同一个初始化：
//   Android 9+ 运行中附加：cmd activity attach-agent <pid> <so>=<cfg>  → Agent_OnAttach(options=cfg 路径)
//   Android 9+ 启动时附加：am start --attach-agent <so>=<cfg>          → Agent_OnAttach
//   Android 8 及以下：JDWP 让应用 System.load(<so>)                    → JNI_OnLoad，cfg 取 so 同目录的 agent.cfg
// 入口里只解析参数、起后台线程，立刻返回：ART 在加载 agent 时持有锁，耗时操作会卡住应用。
#include <atomic>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <thread>
#include <unistd.h>

#include "agent_log.h"
#include "agent_version.h"
#include "agent_runner.h"
#include "status_file.h"

namespace emmy_agent {

namespace {

std::atomic<bool> g_started{false};

std::string SelfDirectory() {
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(&SelfDirectory), &info) == 0 || info.dli_fname == nullptr) return std::string();
    std::string path = info.dli_fname;
    size_t slash = path.rfind('/');
    return slash == std::string::npos ? std::string() : path.substr(0, slash);
}

void Start(const char* options, const char* entry) {
    std::string dir = SelfDirectory();
    if (g_started.exchange(true)) {
        // 同一进程再次附加：IDE 会先检查 maps 并复用，正常走不到这里
        AGENT_LOGW("agent 已在运行，忽略第二次加载（%s）", entry);
        return;
    }
    std::string configPath = (options != nullptr && options[0] != '\0') ? options : dir + "/agent.cfg";
    AGENT_LOGI("%s，配置 %s", entry, configPath.c_str());
    RunOptions run;
    run.configPath = configPath;
    run.statusPath = dir.empty() ? std::string() : dir + "/agent.status";
    try {
        std::thread(RunAgent, run).detach();
    } catch (const std::exception& e) {
        StatusFile status;
        status.SetPath(run.statusPath);
        status.Error(std::string("无法创建 agent 线程：") + e.what());
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
