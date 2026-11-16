#include "agent_config.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>

namespace emmy_agent {

namespace {
constexpr int kSupportedFormat = 1;

std::string Trim(const std::string& s) {
    size_t b = 0, e = s.size();
    while (b < e && (s[b] == ' ' || s[b] == '\t' || s[b] == '\r')) ++b;
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' || s[e - 1] == '\r')) --e;
    return s.substr(b, e - b);
}

bool ParseHex(const std::string& v, uint64_t& out) {
    if (v.size() < 3 || v[0] != '0' || (v[1] != 'x' && v[1] != 'X')) return false;
    char* end = nullptr;
    errno = 0;
    unsigned long long n = std::strtoull(v.c_str() + 2, &end, 16);
    if (errno != 0 || end == nullptr || *end != '\0') return false;
    out = static_cast<uint64_t>(n);
    return true;
}
} // namespace

bool ParseAgentConfig(const std::string& text, AgentConfig& out, std::string& err) {
    out = AgentConfig();
    std::istringstream in(text);
    std::string line;
    while (std::getline(in, line)) {
        line = Trim(line);
        if (line.empty() || line[0] == '#') continue;
        size_t eq = line.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        std::string key = line.substr(0, eq);
        std::string value = line.substr(eq + 1);
        if (key == "format") out.format = std::atoi(value.c_str());
        else if (key == "token") out.token = value;
        else if (key == "buildId") out.buildId = value;
        else if (key == "module") out.moduleName = value;
        else if (key == "package") out.packageName = value;
        else if (key == "lua") out.luaVersion = value;
        else if (key == "waitIde") out.waitForIde = value == "1";
        else if (key.compare(0, 3, "fn.") == 0) {
            uint64_t addr = 0;
            if (!ParseHex(value, addr)) {
                err = "配置里函数偏移格式不对：" + line;
                return false;
            }
            out.functions[key.substr(3)] = addr;
        }
        // 不认识的键忽略：新版 IDE 可以多写字段
    }
    if (out.format != kSupportedFormat) {
        err = "agent 配置格式版本不匹配（" + std::to_string(out.format) + "），请更新插件后重新附加";
        return false;
    }
    if (out.token.empty() || out.buildId.empty() || out.moduleName.empty()) {
        err = "agent 配置缺少 token / buildId / module";
        return false;
    }
    if (out.functions.empty()) {
        err = "agent 配置里没有 Lua 函数偏移";
        return false;
    }
    return true;
}

bool LoadAgentConfig(const std::string& path, AgentConfig& out, std::string& err) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        err = "读不到配置文件 " + path + "：" + std::strerror(errno);
        return false;
    }
    std::stringstream ss;
    ss << f.rdbuf();
    return ParseAgentConfig(ss.str(), out, err);
}

} // namespace emmy_agent
