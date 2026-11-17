// 每个 Lua VM 一个调试器实例：钩子状态机、源码缓存、单步、暂停循环、命令队列。
// 热路径（OnHook）只在 VM 线程运行，不加锁；跨线程只通过原子标志和命令队列。
#pragma once

#include "breakpoints.h"
#include "hook_mux.h"
#include "inspector.h"
#include "lua_internal.h"
#include "lua_shim.h"
#include "profiler.h"
#include "nlohmann/json.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace emmy {

using json = nlohmann::json;
class Session;

enum VmFlag : uint32_t {
	kFlagCommands = 1u << 0,
	kFlagPause = 1u << 1,
	kFlagRefresh = 1u << 2,  // 断点或选项变了
	kFlagSession = 1u << 3,  // 会话变了（连上 / 断开）
	kFlagHelpers = 1u << 4,
	kFlagDetach = 1u << 5,   // 断开：暂停中的 VM 立即恢复
	kFlagEntry = 1u << 6,
};

enum class ResumeKind { None, Continue, StepIn, StepOver, StepOut, Detach };

class Vm : public MuxDebugger, public std::enable_shared_from_this<Vm> {
public:
	using Command = std::function<void(lua_State* L)>;

	Vm(uint64_t id, lua_State* L, std::string discovery);
	~Vm() override;

	// 断点诊断（任意线程）：当前这批断点下发后还没匹配上任何执行过的脚本时，返回给用户看的说明
	// （执行过的 chunk 原文、钩子事件数），每批断点只返回一次；已匹配或已报过返回空串。
	std::string BreakpointDiagnostics(const void* currentIndex);

	void Install();   // VM 线程
	void Uninstall(); // VM 线程

	uint64_t id() const { return id_; }
	lua_State* mainState() const { return mainL_; }
	const void* globalState() const { return g_; }
	HookMux& mux() { return mux_; }
	Inspector& inspector() { return inspector_; }
	Profiler& profiler() { return profiler_; }

	// ---- 任意线程 ----
	void SetName(const std::string& name);
	std::string name();
	uint64_t sourceEpoch() const { return sourceEpoch_.load(); }
	void ResetContext(const std::string& reason);
	json Info();
	uint64_t currentPauseId() const { return currentPauseId_.load(std::memory_order_acquire); }
	void Post(Command cmd);                 // 投递到 VM 线程（暂停中立刻处理，运行中在下一次钩子事件处理）
	void SetFlags(uint32_t flags);
	void RequestPause(const char* reason);
	void Kick() { mux_.Kick(); }

	// ---- VM 线程（命令里调用） ----
	void Resume(ResumeKind kind, std::function<void()> afterResumed);
	void SetRunToLine(std::shared_ptr<Breakpoint> bp);
	void BreakHere(lua_State* L);
	// 出错即停：在错误处理函数里调用（栈还没展开）。msgIdx 是错误对象的绝对位置
	void OnLuaError(lua_State* L, int msgIdx, bool uncaught);
	// 当前正在执行的是不是 Lua 自己的 pcall / xpcall（5.1 的 luaB_pcall 也走 lua_pcall，要和宿主的区分开）
	bool IsInsideLuaPcall(lua_State* L);
	// 按 options.exceptions 装上 / 拆掉 pcall 包装（VM 线程；暂停中也要马上生效，所以由 setOptions 直接 Post）
	void SyncErrorWrappers(lua_State* L);
	void LoadHelpers(lua_State* L, json& result);
	bool paused() const { return paused_; }
	lua_State* pausedThread() const { return pausedL_; }

	// ---- MuxDebugger ----
	int OnHook(lua_State* L, lua_Debug* ar) override;
	int BaseMask() const override;
	int CountInterval() const override { return countInterval_.load(std::memory_order_relaxed); }
	bool WantsHook() const override;
	bool InEval(lua_State* L, bool& timedOut) override;
	bool HasPendingWork() const override;

	// 输出捕获（print）
	void EmitOutput(lua_State* L, const std::string& text);

private:
	struct SourceEntry {
		std::string chunk;
		uint64_t stamp = 0;
		bool hot = false;
		ChunkMatch match;
		bool rtl = false;
		int rtlLine = 0;
		std::vector<int> lines; // 断点行（含 runToLine），升序
		bool HasLine(int line) const { return std::binary_search(lines.begin(), lines.end(), line); }
	};

	struct StepState {
		ResumeKind kind = ResumeKind::None;
		lua_State* thread = nullptr;
		int depth = 0;
		int anchorRef = LUA_NOREF; // 防止被跟踪的协程在单步期间被回收
	};

	void ProcessPending(lua_State* L);
	void RunCommands(lua_State* L);
	void ResetSessionState(lua_State* L);
	void RefreshIndex();
	SourceEntry* Lookup(const char* source, size_t len);
	bool IsHotFunc(const lua::FuncInfo& f);
	bool IsHotIn(const SourceEntry* e, const lua::FuncInfo& f);
	bool AnyHotFrame(lua_State* L, lua_Debug* ar);
	void ReportMatch(const SourceEntry& entry);
	void OnBreakLine(lua_State* L, lua_Debug* ar, SourceEntry* entry);
	// 一个断点在当前行命中后的共同处理（条件、命中次数、日志、采集、breakpointHit）；返回是否要暂停
	bool HandleBreakpointHit(lua_State* L, const Breakpoint* bp, int line, const std::string& chunk);

	// ---- 函数断点（func_breakpoints.cpp，协议 7.7） ----
	struct FuncKey {
		const char* source = nullptr; // Proto 的 source 字符串指针：Proto 被引用着就不会被复用
		int lineDefined = 0;
		bool operator==(const FuncKey& o) const { return source == o.source && lineDefined == o.lineDefined; }
	};
	struct FuncKeyHash {
		size_t operator()(const FuncKey& k) const {
			return std::hash<const void*>()(k.source) ^ (static_cast<size_t>(k.lineDefined) * 0x9E3779B97F4A7C15ull);
		}
	};
	// 在 CALL 事件里调用（只有本 VM 有函数断点时）：命中就记下来、返回 true（调用方给这个函数开 LINE，第一行时停）
	bool OnFunctionCall(lua_State* L, lua_Debug* ar, const lua::FuncInfo& f);
	// 在 LINE 事件里调用：是 OnFunctionCall 记下的那个函数的第一行就处理断点。返回是否暂停过
	bool OnFunctionEntry(lua_State* L, lua_Debug* ar, const lua::FuncInfo& f);
	void ResolveFunctionBreakpoints(lua_State* L);
	void ReleaseFunctionTargets(lua_State* L);
	bool FuncSourceOk(const Breakpoint& bp, const char* source);
	const std::string& FuncBaseName(const char* source);
	bool StepShouldStop(lua_State* L);
	void EnterPause(lua_State* L, const char* reason, std::vector<std::string> bpIds, const char* description,
		json exception = nullptr, int skipLevels = 0);
	void InstallErrorWrappers(lua_State* L, bool enable);
	void ClearStep(lua_State* L);
	void AdaptCount();
	void InstallOutputCapture(lua_State* L, bool enable);
	void ApplyHelpers(lua_State* L);
	static int StackDepthAtLeast(lua_State* L, int level);

	const uint64_t id_;
	lua_State* const mainL_;
	const void* const g_;
	const std::string discovery_;
	HookMux mux_;
	Inspector inspector_;
	Profiler profiler_; // 采样分析（协议 v3 第 20 节）：只在采样期间往 mux_ 注册 COUNT 客户端

	std::mutex infoMutex_;
	std::string name_;
	std::atomic<uint64_t> sourceEpoch_{1};

	// 跨线程
	std::atomic<uint32_t> pending_{0};
	std::mutex cmdMutex_;
	std::condition_variable cmdCv_;
	std::deque<Command> commands_;
	std::atomic<uint64_t> currentPauseId_{0};
	std::atomic<int> countInterval_{100000};
	std::atomic<int> pauseReason_{0}; // 0 pause 1 entry
	std::atomic<bool> active_{false};
	std::atomic<bool> anyBreakpoints_{false};
	std::atomic<bool> anyLineBreakpoints_{false};
	std::atomic<bool> anyFuncBreakpoints_{false};

	// 断点诊断：VM 线程写（只在 Lookup 慢路径和 RefreshIndex），诊断时别的线程读
	std::mutex diagMutex_;
	const void* diagIndex_ = nullptr;     // 诊断数据对应的断点索引
	std::vector<std::string> diagSources_; // 这批断点生效后执行过的 chunk（去重，最多 kDiagMaxSources 个）
	size_t diagSourceTotal_ = 0;
	bool diagMatched_ = false;
	bool diagReported_ = false;
	std::atomic<uint64_t> hookEvents_{0}; // 只有 VM 线程写
	bool abiChecked_ = false;              // 只有 VM 线程读写：第一次钩子事件时做 ABI 自检
	uint64_t diagEventsBase_ = 0;
	static constexpr size_t kDiagMaxSources = 20;
	void DiagRecordSource(const SourceEntry& e);

	// 只在 VM 线程
	uint64_t sessionGen_ = 0;
	std::shared_ptr<const BreakpointIndex> index_;
	uint64_t cacheStamp_ = 1;
	uint64_t seenEpoch_ = 1;
	std::unordered_map<const char*, SourceEntry> cache_;
	const char* lastSource_ = nullptr;
	SourceEntry* lastEntry_ = nullptr;
	// 行事件的快速路径：上一个行事件所在函数的判断
	const char* lineSource_ = nullptr;
	int lineDefined_ = -1;
	int lineLastDefined_ = -1;
	uint64_t lineStamp_ = 0;
	SourceEntry* lineEntry_ = nullptr;
	bool lineHot_ = false;
	std::unordered_map<uint64_t, int64_t> hitCounts_;
	std::unordered_map<uint64_t, std::string> reported_;
	std::shared_ptr<Breakpoint> runToLine_;
	StepState step_;
	bool pauseAtLine_ = false;
	const char* pauseAtLineReason_ = "pause";
	bool inPauseLoop_ = false;
	bool paused_ = false;
	lua_State* pausedL_ = nullptr;
	uint64_t pauseCounter_ = 0;
	ResumeKind resumeKind_ = ResumeKind::None;
	std::vector<std::function<void()>> afterResumed_;
	std::chrono::steady_clock::time_point lastCount_{};
	bool outputCaptured_ = false;
	bool errorsWrapped_ = false;
	std::unordered_map<uint64_t, int64_t> hitWindowStart_;
	std::unordered_map<uint64_t, int> hitWindowCount_;
	std::unordered_map<uint64_t, int> hitDropped_;

	// 函数断点（只在 VM 线程）
	bool funcDirty_ = true;              // 断点表 / 上下文变了，下一次 CALL 先重新解析
	uint32_t funcCallTick_ = 0;          // 每 1024 次 CALL 看一次时间，超过 1 秒重新解析（热重载换了函数）
	std::chrono::steady_clock::time_point funcResolvedAt_{};
	std::vector<BreakpointPtr> funcBps_; // 本 VM 的函数断点
	std::unordered_map<FuncKey, std::vector<BreakpointPtr>, FuncKeyHash> funcTargets_; // 解析出的函数 → 断点
	std::vector<BreakpointPtr> funcUnresolved_; // 还没解析到函数值的：按调用处的名字（lua_getinfo "n"）兜底
	std::vector<int> funcRefs_;          // 解析到的函数在 registry 里的引用（钉住 Proto，FuncKey 才唯一）
	std::unordered_map<const char*, std::string> funcBaseNames_;
	struct FuncPending {
		lua_State* L = nullptr;
		FuncKey key;
		std::vector<BreakpointPtr> bps;
	} funcPending_;
};

} // namespace emmy
