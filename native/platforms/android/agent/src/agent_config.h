// IDE 写的 agent.cfg：一行一个 key=value，格式见 modules/debugger-android 的 AgentConfig.kt。
#pragma once

#include <cstdint>
#include <map>
#include <string>

namespace emmy_agent {

struct AgentConfig {
    int format = 0;
    std::string token;
    std::string buildId;
    std::string moduleName;
    std::string packageName;
    std::string luaVersion;
    bool waitForIde = false;
    // 函数名 → 未裁剪 so 里的 st_value（armv7 Thumb 函数带最低位 1，原样加基址）
    std::map<std::string, uint64_t> functions;
};

// 解析成功返回 true；失败时 err 是给用户看的中文原因。
bool ParseAgentConfig(const std::string& text, AgentConfig& out, std::string& err);
bool LoadAgentConfig(const std::string& path, AgentConfig& out, std::string& err);

} // namespace emmy_agent
