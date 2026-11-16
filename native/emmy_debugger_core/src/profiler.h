// 采样性能分析（协议 v3 第 20 节，能力 profiler）。
//
// 每个 VM 一个 Profiler：开始采样时经 HookMux 注册一个 COUNT 客户端（每 N 条 VM 指令回调一次），
// 回调里用 lua_getstack / lua_getinfo("S") 取当前协程的调用栈（最多 64 层），聚合成
//   - 函数（chunkName + linedefined，C 函数按名字）→ 自身样本数 / 总样本数
//   - 折叠栈（根 → 叶的函数序列）→ 样本数
// 不采样时不注册客户端，HookMux 里没有它的掩码，零开销。
//
// 为什么只用 lua_getstack / lua_getinfo：dynamic 模式下宿主的 lua_Debug 布局（尤其 i_ci 之后）可能和我们编译时的不同
// （UnLua 等改过的分支），核心不读 lua_Debug 里 Lua 私有的字段，也不走 lua_internal 里按 CallInfo 偏移读的快速路径。
#pragma once

#include "hook_mux.h"
#include "lua_shim.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace emmy {

class Vm;

struct ProfileFunctionRow {
	std::string key;    // 合并多个 VM 结果时用：source + "\n" + line + "\n" + name
	std::string fn;     // 显示名（函数名；没有名字时 <匿名>、<主 chunk>）
	std::string source; // chunkName 原样（C 函数为 "=[C]"）
	int line = 0;       // linedefined；C 函数为 -1，主 chunk 为 0
	uint64_t selfSamples = 0;
	uint64_t totalSamples = 0;
};

struct ProfileSnapshot {
	bool running = false;
	uint64_t samples = 0;
	uint64_t pausedSkipped = 0;  // 暂停在断点时到点的采样（不计入）
	uint64_t truncatedStacks = 0; // 深度超过上限、根部被截掉的样本数
	uint64_t otherSamples = 0;    // 不同栈超过上限后合并进 <其他> 的样本数
	int64_t durationMs = 0;
	int intervalInstructions = 0;
	double overheadMs = 0;        // 采样回调本身耗的时间（量开销用）
	std::vector<ProfileFunctionRow> functions;
	std::vector<std::pair<std::string, uint64_t>> stacks; // 折叠栈（"a;b;c"）→ 样本数，未排序
};

class Profiler {
public:
	static constexpr int kMaxDepth = 64;
	static constexpr size_t kMaxStacks = 10000;
	static constexpr size_t kMaxFunctions = 20000;
	static constexpr int kDefaultInterval = 1000;
	static constexpr int kMinInterval = 100;
	static constexpr int kMaxInterval = 10000000;

	Profiler(Vm& vm, HookMux& mux) : vm_(vm), mux_(mux) {}
	~Profiler();

	// 任意线程。已经在采样时清空数据重新开始。durationMs > 0 时到点自动停（在下一次采样回调里判断）。
	void Start(int intervalInstructions, int durationMs);
	// 任意线程。没在采样时什么都不做
	void Stop();
	bool running() const { return handle_.load(std::memory_order_acquire) != 0; }
	// 任意线程：当前数据的副本（采样中也可以取）
	ProfileSnapshot Snapshot();

	static void SampleEntry(lua_State* L, lua_Debug* ar, void* ud);

private:
	struct Func {
		std::string source;
		std::string name;
		int line = 0;
		bool isC = false;
		bool named = true;   // false：还没取到名字（第一次见到时是从 C 调进来的），之后的样本再试几次
		uint8_t nameTries = 0;
		uint64_t self = 0;
		uint64_t total = 0;
		uint64_t lastSample = 0; // 同一个样本里递归出现多次只算一次 total
	};

	void Sample(lua_State* L);
	uint32_t Intern(lua_State* L, lua_Debug* ar);
	std::string Label(const Func& f) const;
	void StopLocked(std::chrono::steady_clock::time_point now);

	Vm& vm_;
	HookMux& mux_;
	std::atomic<uint64_t> handle_{0};
	std::atomic<int64_t> deadlineNs_{0}; // steady_clock 纳秒；0 表示不限时

	std::mutex mutex_; // 采样回调（VM 线程）和取结果（网络线程）之间
	std::vector<Func> funcs_;
	std::unordered_map<uint64_t, std::vector<uint32_t>> funcIndex_; // 哈希 → 函数下标（同哈希的少量冲突线性比较）
	std::unordered_map<std::string, uint64_t> stacks_;              // 键是 uint32 函数下标序列的原始字节，根在前
	std::string keyBuf_;
	std::vector<uint32_t> frameBuf_;
	uint64_t samples_ = 0;
	uint64_t pausedSkipped_ = 0;
	uint64_t truncated_ = 0;
	uint64_t other_ = 0;
	int interval_ = kDefaultInterval;
	int64_t overheadNs_ = 0;
	std::chrono::steady_clock::time_point startedAt_{};
	std::chrono::steady_clock::time_point stoppedAt_{};
	bool everStarted_ = false;
};

} // namespace emmy
