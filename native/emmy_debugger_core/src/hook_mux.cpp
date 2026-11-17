#include "hook_mux.h"

#include "core.h"
#include "lua_internal.h"

#include <algorithm>
#include <cstdio>
#include <exception>
#include <stdexcept>

#if defined(_WIN32)
#include <windows.h>
#endif

namespace emmy {

namespace {

constexpr int kMaxVms = 256;

struct MuxSlot {
	std::atomic<const void*> g{nullptr};
	std::atomic<HookMux*> mux{nullptr};
};

MuxSlot g_slots[kMaxVms];
std::mutex g_slotsMutex;

void AdoptedTrampoline(lua_State* L, lua_Debug* ar, void* ud) {
	auto hook = reinterpret_cast<lua_Hook>(ud);
	if (hook) hook(L, ar);
}

constexpr int kAllMasks = LUA_MASKCALL | LUA_MASKRET | LUA_MASKLINE | LUA_MASKCOUNT;

} // namespace

void RegisterMux(const void* globalState, HookMux* mux) {
	std::lock_guard<std::mutex> lock(g_slotsMutex);
	for (auto& slot : g_slots) {
		if (slot.g.load(std::memory_order_relaxed) == nullptr) {
			slot.mux.store(mux, std::memory_order_relaxed);
			slot.g.store(globalState, std::memory_order_release);
			return;
		}
	}
}

void UnregisterMux(const void* globalState) {
	std::lock_guard<std::mutex> lock(g_slotsMutex);
	for (auto& slot : g_slots) {
		if (slot.g.load(std::memory_order_relaxed) == globalState) {
			slot.g.store(nullptr, std::memory_order_release);
			slot.mux.store(nullptr, std::memory_order_relaxed);
		}
	}
}

HookMux* FindMux(const void* globalState) {
	// 每个线程记住上一次的结果：同一线程几乎总是跑同一个 VM
	thread_local const void* lastG = nullptr;
	thread_local HookMux* lastMux = nullptr;
	if (globalState == lastG && lastMux) {
		return lastMux;
	}
	for (auto& slot : g_slots) {
		if (slot.g.load(std::memory_order_acquire) == globalState) {
			lastG = globalState;
			lastMux = slot.mux.load(std::memory_order_relaxed);
			return lastMux;
		}
	}
	return nullptr;
}

HookMux::HookMux(lua_State* mainL) : mainL_(mainL) {}

HookMux::~HookMux() = default;

void HookMux::LuaHookEntry(lua_State* L, lua_Debug* ar) {
	HookMux* mux = FindMux(lua::GlobalStateOf(L));
	if (mux && mux->faulted()) {
		mux->DropHook(L);
		return;
	}
	if (mux) {
		MuxDebugger* dbg = mux->debugger();
		bool timedOut = false;
		if (dbg && dbg->InEval(L, timedOut)) {
			if (timedOut) {
				lua_pushstring(L, "求值超时，表达式可能有死循环");
				lua_error(L);
			}
			return;
		}
		mux->GuardedDispatch(L, ar);
	} else {
		// VM 已经注销但钩子还在（协程上残留）：摘掉
		lua_sethook(L, nullptr, 0, 0);
	}
}

bool HookMux::DispatchCatchingCxx(lua_State* L, lua_Debug* ar, std::string& what) {
	// 只接 std::exception：Lua 按 C++ 编译时（UnLua）lua_error 抛的是 lua_longjmp 指针，不能在这里吞掉
	try {
		if (injectFault_.exchange(false, std::memory_order_acq_rel)) throw std::runtime_error("测试注入的内部错误");
		Dispatch(L, ar);
		return true;
	} catch (const std::exception& e) {
		what = e.what();
		return false;
	}
}

#if defined(_WIN32)
namespace {
// 只接硬件异常；C++ 异常（0xE06D7363）、longjmp 的展开（STATUS_LONGJUMP）照常往外走
int FaultFilter(unsigned long code) {
	switch (code) {
	case EXCEPTION_ACCESS_VIOLATION:
	case EXCEPTION_ARRAY_BOUNDS_EXCEEDED:
	case EXCEPTION_DATATYPE_MISALIGNMENT:
	case EXCEPTION_ILLEGAL_INSTRUCTION:
	case EXCEPTION_IN_PAGE_ERROR:
	case EXCEPTION_INT_DIVIDE_BY_ZERO:
	case EXCEPTION_PRIV_INSTRUCTION:
		return EXCEPTION_EXECUTE_HANDLER;
	default:
		return EXCEPTION_CONTINUE_SEARCH;
	}
}

// __try 所在的函数里不能有要析构的 C++ 对象，所以字符串放在调用方
bool SehGuard(HookMux* mux, lua_State* L, lua_Debug* ar, std::string* what, bool (HookMux::*fn)(lua_State*, lua_Debug*, std::string&),
	unsigned long* code) {
	__try {
		return (mux->*fn)(L, ar, *what);
	} __except (FaultFilter(*code = GetExceptionCode())) {
		return false;
	}
}
} // namespace
#endif

bool HookMux::GuardedDispatch(lua_State* L, lua_Debug* ar) {
	std::string what;
#if defined(_WIN32)
	unsigned long code = 0;
	if (SehGuard(this, L, ar, &what, &HookMux::DispatchCatchingCxx, &code)) return true;
	if (what.empty()) {
		char buf[64];
		std::snprintf(buf, sizeof(buf), "硬件异常 0x%08lX", code);
		what = buf;
	}
#else
	if (DispatchCatchingCxx(L, ar, what)) return true;
#endif
	OnFault(L, what);
	return false;
}

void HookMux::OnFault(lua_State* L, const std::string& what) {
	if (faulted_.exchange(true, std::memory_order_acq_rel)) {
		DropHook(L);
		return;
	}
	DropHook(L);
	// 其他协程上的钩子在它们下一次事件时由入口摘掉（faulted 检查）
	Core::Get().Log("console", "error",
		"调试器内部错误，已停止这个 Lua VM 的调试（游戏继续运行）：" + what +
			"。断点、单步在这个 VM 上不再生效；重新附加可以恢复。请把这条信息和 IDE 日志（帮助 → 显示日志）反馈给我们。");
}

void HookMux::DropHook(lua_State* L) {
	// 主线程上接管过的宿主钩子放回去，其余线程直接摘掉
	if (L == mainL_ && adoptedHook_) {
		lua_sethook(L, adoptedHook_, adoptedMask_, adoptedCount_);
	} else {
		lua_sethook(L, nullptr, 0, 0);
	}
}

void HookMux::AdoptExistingHook() {
	lua_Hook existing = lua_gethook(mainL_);
	if (existing && existing != &HookMux::LuaHookEntry) {
		adoptedHook_ = existing;
		adoptedMask_ = lua_gethookmask(mainL_);
		adoptedCount_ = lua_gethookcount(mainL_);
		Register(&AdoptedTrampoline, adoptedMask_, adoptedCount_, reinterpret_cast<void*>(existing));
	}
}

uint64_t HookMux::Register(MuxCallback cb, int mask, int count, void* ud) {
	uint64_t handle;
	{
		std::lock_guard<std::mutex> lock(mutex_);
		handle = nextHandle_++;
		Client c;
		c.handle = handle;
		c.cb = cb;
		c.mask = mask & kAllMasks;
		c.count = (mask & LUA_MASKCOUNT) ? std::max(count, 1) : 0;
		c.ud = ud;
		pending_.push_back(c);
		int m = 0, cnt = 0;
		for (const Client& x : pending_) {
			m |= x.mask;
			if (x.count > 0) cnt = cnt == 0 ? x.count : std::min(cnt, x.count);
		}
		staticMask_.store(m, std::memory_order_relaxed);
		staticCount_.store(cnt, std::memory_order_relaxed);
		clientsVersion_.fetch_add(1, std::memory_order_release);
	}
	Kick();
	return handle;
}

void HookMux::Unregister(uint64_t handle) {
	std::lock_guard<std::mutex> lock(mutex_);
	pending_.erase(std::remove_if(pending_.begin(), pending_.end(), [handle](const Client& c) { return c.handle == handle; }),
		pending_.end());
	int m = 0, cnt = 0;
	for (const Client& x : pending_) {
		m |= x.mask;
		if (x.count > 0) cnt = cnt == 0 ? x.count : std::min(cnt, x.count);
	}
	staticMask_.store(m, std::memory_order_relaxed);
	staticCount_.store(cnt, std::memory_order_relaxed);
	clientsVersion_.fetch_add(1, std::memory_order_release);
	// 掩码收窄不需要立刻生效：下一次事件时 Apply 会摘掉多余的位
}

int HookMux::DebuggerBaseMask() const {
	if (!debugger_ || !debugger_->WantsHook()) return 0;
	return debugger_->BaseMask() | LUA_MASKCOUNT;
}

int HookMux::EffectiveCount(int debuggerMask) const {
	int count = (debuggerMask & LUA_MASKCOUNT) && debugger_ ? debugger_->CountInterval() : 0;
	int sc = staticCount_.load(std::memory_order_relaxed);
	if (sc > 0) count = count > 0 ? std::min(count, sc) : sc;
	return count > 0 ? count : 1000;
}

void HookMux::Kick() {
	if (faulted()) return;
	spreadPending_.store(true, std::memory_order_release);
	int mask = staticMask_.load(std::memory_order_relaxed) | DebuggerBaseMask();
	if (mask == 0 && !(debugger_ && debugger_->WantsHook() && debugger_->HasPendingWork())) {
		return;
	}
	// 跨线程 lua_sethook 是 Lua 允许的（lua.c 在信号处理里就这么用）：只改几个字段，下一条指令起生效。
	// 带上 COUNT（短间隔），保证即使主线程当前没有调用/返回也能尽快进到 Dispatch 完成扩散。
	lua_sethook(mainL_, &HookMux::LuaHookEntry, mask | LUA_MASKCOUNT, 1000);
}

void HookMux::EnsureHook(lua_State* L) {
	if (faulted()) return;
	if (lua_gethook(L) != nullptr) return; // 已经是我们的，或者宿主自己的钩子：不动
	const int dbgMask = DebuggerBaseMask();
	const int mask = staticMask_.load(std::memory_order_relaxed) | dbgMask;
	if (mask == 0) return;
	lua_sethook(L, &HookMux::LuaHookEntry, mask, EffectiveCount(dbgMask));
}

void HookMux::SyncClients() {
	uint32_t v = clientsVersion_.load(std::memory_order_acquire);
	if (v == syncedVersion_) return;
	std::lock_guard<std::mutex> lock(mutex_);
	// 保留已有客户端的 COUNT 累计值
	std::vector<Client> next = pending_;
	for (Client& c : next) {
		for (const Client& old : clients_) {
			if (old.handle == c.handle) c.countAcc = old.countAcc;
		}
	}
	clients_.swap(next);
	syncedVersion_ = clientsVersion_.load(std::memory_order_relaxed);
}

void HookMux::SpreadToCoroutines(lua_State* L) {
	int dbgMask = DebuggerBaseMask();
	int mask = staticMask_.load(std::memory_order_relaxed) | dbgMask;
	int count = EffectiveCount(dbgMask);
	auto apply = [&](lua_State* co) {
		if (co == L) return;
		if (mask == 0) lua_sethook(co, nullptr, 0, 0);
		else lua_sethook(co, &HookMux::LuaHookEntry, mask, count);
	};
	apply(lua::MainThreadOf(L));
	lua::ForEachCoroutine(L, apply);
}

void HookMux::Dispatch(lua_State* L, lua_Debug* ar) {
	SyncClients();
	if (spreadPending_.load(std::memory_order_relaxed) && spreadPending_.exchange(false, std::memory_order_acq_rel)) {
		SpreadToCoroutines(L);
	}
	int event = ar->event;
	int eventMask = 1 << (event == 4 ? LUA_HOOKCALL : event); // TAILCALL(5.2+)/TAILRET(5.1) 归到 CALL
#if EMMY_LUA_VERSION_NUM == 501
	if (event == 4) eventMask = LUA_MASKRET; // 5.1 的 TAILRET 是返回
#endif
	int dbgMask = 0;
	if (debugger_) {
		dbgMask = debugger_->OnHook(L, ar);
	}
	if (!clients_.empty()) DispatchClients(L, ar, eventMask);
	Apply(L, dbgMask, debugger_ ? debugger_->CountInterval() : 0);
}

void HookMux::DispatchClients(lua_State* L, lua_Debug* ar, int eventMask) {
	int currentCount = lua_gethookcount(L);
	for (Client& c : clients_) {
		if (!(c.mask & eventMask)) continue;
		if (eventMask == LUA_MASKCOUNT) {
			c.countAcc += currentCount;
			if (c.countAcc < c.count) continue;
			c.countAcc = 0;
		}
		c.cb(L, ar, c.ud);
	}
}

void HookMux::Apply(lua_State* L, int debuggerMask, int debuggerCount) {
	int mask = staticMask_.load(std::memory_order_relaxed) | debuggerMask;
	int count = 0;
	if (mask & LUA_MASKCOUNT) {
		int sc = staticCount_.load(std::memory_order_relaxed);
		count = (debuggerMask & LUA_MASKCOUNT) ? debuggerCount : 0;
		if (sc > 0) count = count > 0 ? std::min(count, sc) : sc;
		if (count <= 0) count = 1000;
	}
	(void)debuggerCount;
	// Kick() 可能在别的线程刚设好 COUNT 就被我们这里覆盖；有待处理的事就保住 COUNT，避免丢通知
	if (debugger_ && !(mask & LUA_MASKCOUNT) && debugger_->HasPendingWork()) {
		mask |= LUA_MASKCOUNT;
		count = 1000;
	}
	if (mask == 0) {
		if (lua_gethook(L) != nullptr) lua_sethook(L, nullptr, 0, 0);
		return;
	}
	// 热路径：掩码没变且不含 COUNT 就什么都不做（钩子函数一定是我们的：事件就是从这里进来的）
	if (lua_gethookmask(L) == mask && (!(mask & LUA_MASKCOUNT) || lua_gethookcount(L) == count)) {
		return;
	}
	{
		lua_sethook(L, &HookMux::LuaHookEntry, mask, count);
	}
}

void HookMux::Uninstall() {
	if (adoptedHook_) {
		lua_sethook(mainL_, adoptedHook_, adoptedMask_, adoptedCount_);
	} else {
		lua_sethook(mainL_, nullptr, 0, 0);
	}
}

} // namespace emmy
