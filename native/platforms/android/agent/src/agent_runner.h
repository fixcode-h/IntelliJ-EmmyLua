// agent 主流程：读配置 → 等模块并校验 Build ID → 监听 → 装 hook → 让核心解析 Lua API → 开始抓 lua_State。
#pragma once

#include <string>

namespace emmy_agent {

class HookBackend;

struct RunOptions {
    std::string configPath;
    std::string statusPath;          // 空表示不写状态文件
    HookBackend* backend = nullptr;  // 空表示用平台默认（Android 上是 ShadowHook）
    int moduleWaitSeconds = 180;
};

// 阻塞运行，直到临时 intercept 撤掉（或出错）。在后台线程调用。
void RunAgent(const RunOptions& options);

} // namespace emmy_agent
