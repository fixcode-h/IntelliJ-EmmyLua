#include "status_file.h"

#include <cstdio>
#include <unistd.h>

#include "agent_log.h"
#include "agent_version.h"

namespace emmy_agent {

void StatusFile::Write(const std::string& state, const std::vector<std::pair<std::string, std::string>>& extra) {
    if (path_.empty()) return;
    std::string tmp = path_ + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "w");
    if (f == nullptr) {
        AGENT_LOGW("无法写状态文件 %s", tmp.c_str());
        return;
    }
    std::fprintf(f, "version=%s\nstate=%s\npid=%d\n", EMMY_AGENT_VERSION, state.c_str(), static_cast<int>(getpid()));
    for (const auto& kv : extra) {
        // 值里不允许换行，否则 IDE 按行解析会错位
        std::string v = kv.second;
        for (char& c : v) if (c == '\n' || c == '\r') c = ' ';
        std::fprintf(f, "%s=%s\n", kv.first.c_str(), v.c_str());
    }
    std::fclose(f);
    std::rename(tmp.c_str(), path_.c_str());
}

void StatusFile::Error(const std::string& message) {
    AGENT_LOGE("%s", message.c_str());
    Write("error", {{"error", message}});
}

} // namespace emmy_agent
