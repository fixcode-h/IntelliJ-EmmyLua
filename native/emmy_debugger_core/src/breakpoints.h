// 断点与不可变断点索引（设计文档 5.2 第 2、3 条）。
// 索引在网络线程构建、整体替换后原子发布；VM 线程只读，不加锁。
#pragma once

#include "path_match.h"

#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace emmy {

struct HitCondition {
	enum class Op { None, Eq, Ge, Gt, Mod };
	Op op = Op::None;
	int64_t n = 0;

	bool Check(int64_t count) const;
	// 语法见协议 7.3；失败时 err 给中文原因
	static bool Parse(std::string_view text, HitCondition& out, std::string& err);
};

struct Breakpoint {
	uint64_t serial = 0; // 进程内唯一，每次 upsert 都是新对象、新 serial（命中计数、验证事件按它去重）
	std::string id;
	std::string path;
	std::string chunkName;
	std::string sha256;
	int line = 0;
	std::string condition;
	std::string hitConditionText;
	HitCondition hitCondition;
	std::string logMessage;
	std::vector<std::string> capture;
	bool autoContinue = false;
	uint64_t vmId = 0;

	NormalizedPath normPath;
	NormalizedPath normChunk; // chunkName 给了才有
	bool HasChunkName() const { return !chunkName.empty(); }
	bool Pauses() const { return !autoContinue && logMessage.empty(); }
};

using BreakpointPtr = std::shared_ptr<const Breakpoint>;

uint64_t NextBreakpointSerial();

// 某个 VM 里某个 chunk 的匹配结果
struct ChunkMatch {
	std::vector<const Breakpoint*> bps; // 按行号排序
	std::vector<const Breakpoint*> ambiguous;
	bool HasLine(int line) const;
};

class BreakpointIndex {
public:
	BreakpointIndex(uint64_t revision, std::vector<BreakpointPtr> bps, bool caseSensitive);

	uint64_t revision() const { return revision_; }
	bool empty() const { return bps_.empty(); }
	bool caseSensitive() const { return caseSensitive_; }
	// 对 vmId 是否有任何断点（vmId 过滤后）
	bool AnyFor(uint64_t vmId) const;
	const std::vector<BreakpointPtr>& all() const { return bps_; }

	ChunkMatch Match(std::string_view chunkSource, uint64_t vmId) const;

private:
	struct Group {
		bool byChunk = false;
		const NormalizedPath* key = nullptr;
		std::vector<const Breakpoint*> bps;
	};
	uint64_t revision_;
	bool caseSensitive_;
	std::vector<BreakpointPtr> bps_;
	std::vector<Group> groups_;
};

// 单个 runToLine 的临时断点用同一套匹配逻辑
bool MatchSingle(const Breakpoint& bp, std::string_view chunkSource, bool caseSensitive);

} // namespace emmy
