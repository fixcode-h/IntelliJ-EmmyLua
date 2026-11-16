// agent.status：IDE 注入后轮询它拿结果和错误（格式见 AgentConfig.kt 的 AgentStatus）。
#pragma once

#include <string>
#include <utility>
#include <vector>

namespace emmy_agent {

class StatusFile {
public:
    void SetPath(const std::string& path) { path_ = path; }
    // 整体重写：先写临时文件再 rename，IDE 不会读到半截内容。
    void Write(const std::string& state, const std::vector<std::pair<std::string, std::string>>& extra = {});
    void Error(const std::string& message);
    const std::string& Path() const { return path_; }

private:
    std::string path_;
};

} // namespace emmy_agent
