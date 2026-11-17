// Lua 错误聚合（能力 errorReport）：按「VM + 出错位置 + 消息」去重计数，给 IDE 的「Lua 错误」页和 MCP errors 用。
// 被 pcall 接住的错误往往每帧都来一次，逐条转发会刷屏、拖慢游戏，所以 runtime 侧先聚合：
// 新出现的错误马上推一条 luaError（带栈），之后同一条每秒最多推一次计数；IDE 要精确值时发 errors 命令拉全量。
#pragma once

#include "nlohmann/json.hpp"

#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace emmy {

class ErrorStats {
public:
	static constexpr size_t kMaxEntries = 500;     // 超出的新错误只计入 dropped
	static constexpr int64_t kEmitIntervalMs = 1000; // 同一条错误两次推送的最小间隔
	static constexpr size_t kMaxMessage = 1024;

	// 记一次错误。返回要推送的 luaError 事件体；不需要推送时返回 null。
	// traceback 只在第一次见到这条错误时才调用（取栈比查表贵得多）。
	nlohmann::json Record(uint64_t vmId, const std::string& chunkName, int line, const std::string& message, bool uncaught,
		int64_t nowMs, const std::function<std::string()>& traceback);

	// 按最近出现时间倒序的全部条目 + dropped
	nlohmann::json Snapshot() const;
	void Clear();

private:
	struct Entry {
		uint64_t id = 0;
		uint64_t vmId = 0;
		std::string chunkName;
		int line = 0;
		std::string message;
		std::string traceback;
		bool uncaught = false;
		uint64_t count = 0;
		int64_t firstMs = 0;
		int64_t lastMs = 0;
		int64_t lastEmitMs = 0;
	};
	static nlohmann::json ToJson(const Entry& e, bool withTraceback);

	mutable std::mutex mutex_;
	std::unordered_map<std::string, Entry> entries_;
	uint64_t nextId_ = 1;
	uint64_t dropped_ = 0;
};

} // namespace emmy
