// HookMux：一个 lua_State 只能挂一个钩子，调试器和宿主其他工具（性能统计、快照点……）通过它共享。
// 设计文档 5.4「钩子共存」。注册前已经存在的钩子会被接管为一个普通客户端，继续收到事件。
#pragma once

#include "lua_shim.h"

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace emmy {

using MuxCallback = void (*)(lua_State* L, lua_Debug* ar, void* ud);

// 调试器自己是 HookMux 的「内置客户端」：每次事件先交给它，由它决定这个线程上调试器需要的掩码
class MuxDebugger {
public:
	virtual ~MuxDebugger() = default;
	// 处理事件；返回调试器希望这个线程上开启的掩码（不含其他客户端）
	virtual int OnHook(lua_State* L, lua_Debug* ar) = 0;
	// 没有具体线程信息时（扩散到所有协程时）用的基础掩码
	virtual int BaseMask() const = 0;
	virtual int CountInterval() const = 0;
	// 调试器当前是否需要钩子（IDE 已连接）。为 true 时至少保留 COUNT，用来响应暂停和执行投递的命令
	virtual bool WantsHook() const = 0;
	// 调试器正在执行求值（L 是求值用的协程）时返回 true，钩子入口直接返回；timedOut 为 true 时由入口抛错。
	// 抛错必须在没有 C++ 对象存活的入口函数里做（lua_error 是 longjmp）。
	virtual bool InEval(lua_State* L, bool& timedOut) = 0;
	// 有跨线程投递过来、还没在 VM 线程处理的事（命令、暂停请求、断点变化）
	virtual bool HasPendingWork() const = 0;
};

class HookMux {
public:
	explicit HookMux(lua_State* mainL);
	~HookMux();

	void SetDebugger(MuxDebugger* dbg) { debugger_ = dbg; }

	// 接管当前已经挂在主线程上的钩子（如果不是我们自己的）
	void AdoptExistingHook();

	uint64_t Register(MuxCallback cb, int mask, int count, void* ud);
	void Unregister(uint64_t handle);

	// 任意线程可调：让主线程立刻挂上钩子，并在下一次事件时把掩码扩散到所有协程。
	// 用于 IDE 刚连上、宿主刚注册客户端这类「当前可能完全没有钩子」的时刻。
	void Kick();
	// 在 L 所在的线程上调用：L 没有任何钩子而调试器需要时挂上（新协程、遍历时漏掉的协程）
	void EnsureHook(lua_State* L);

	// 只在 VM 线程调用
	void Dispatch(lua_State* L, lua_Debug* ar);
	void Apply(lua_State* L, int debuggerMask, int debuggerCount);
	// 卸掉所有钩子（VM 关闭前），恢复成接管前的钩子
	void Uninstall();

	lua_State* mainThread() const { return mainL_; }
	MuxDebugger* debugger() const { return debugger_; }

	static void LuaHookEntry(lua_State* L, lua_Debug* ar);

	// 出过一次内部错误（C++ 异常；Windows 上还有访问违例）后整个 VM 不再挂调试钩子：
	// 调试器跑在用户的游戏进程里，宁可这个 VM 断开调试，也不能把游戏带崩。
	bool faulted() const { return faulted_.load(std::memory_order_acquire); }
	// 在 VM 线程调用：主动停掉这个 VM 的调试（ABI 自检没过等），message 原样报给 IDE
	void Disable(lua_State* L, const std::string& message);
	// 测试用：下一次 Dispatch 抛一个 C++ 异常
	void InjectFaultForTest() { injectFault_.store(true, std::memory_order_release); }

private:
	struct Client {
		uint64_t handle = 0;
		MuxCallback cb = nullptr;
		int mask = 0;
		int count = 0;
		void* ud = nullptr;
		int64_t countAcc = 0;
	};

	void SyncClients();
	void DispatchClients(lua_State* L, lua_Debug* ar, int eventMask);
	int DebuggerBaseMask() const;
	int EffectiveCount(int debuggerMask) const;
	void SpreadToCoroutines(lua_State* L);
	// Dispatch 外面包一层兜底；出错时置 faulted_、摘掉 L 上的钩子并报告。返回 false 表示这次出错了
	bool GuardedDispatch(lua_State* L, lua_Debug* ar);
	bool DispatchCatchingCxx(lua_State* L, lua_Debug* ar, std::string& what);
	void OnFault(lua_State* L, const std::string& message);
	void DropHook(lua_State* L);

	std::atomic<bool> faulted_{false};
	std::atomic<bool> injectFault_{false};

	lua_State* mainL_;
	MuxDebugger* debugger_ = nullptr;

	std::mutex mutex_;
	std::vector<Client> pending_;          // 受 mutex_ 保护，Register/Unregister 改这里
	std::atomic<uint32_t> clientsVersion_{0};
	std::atomic<int> staticMask_{0};
	std::atomic<int> staticCount_{0};
	std::atomic<bool> spreadPending_{false};
	uint64_t nextHandle_ = 1;

	// 以下只在 VM 线程访问
	std::vector<Client> clients_;
	uint32_t syncedVersion_ = 0;
	lua_Hook adoptedHook_ = nullptr;
	int adoptedMask_ = 0;
	int adoptedCount_ = 0;
};

// global_State → HookMux 的查找表（钩子回调里用，无锁）。
void RegisterMux(const void* globalState, HookMux* mux);
void UnregisterMux(const void* globalState);
HookMux* FindMux(const void* globalState);

} // namespace emmy
