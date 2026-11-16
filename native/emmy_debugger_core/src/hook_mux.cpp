#include "hook_mux.h"

#include "lua_internal.h"

#include <algorithm>

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
		mux->Dispatch(L, ar);
	} else {
		// VM 已经注销但钩子还在（协程上残留）：摘掉
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
	spreadPending_.store(true, std::memory_order_release);
	int mask = staticMask_.load(std::memory_order_relaxed) | DebuggerBaseMask();
	if (mask == 0) {
		return;
	}
	// 跨线程 lua_sethook 是 Lua 允许的（lua.c 在信号处理里就这么用）：只改几个字段，下一条指令起生效。
	// 带上 COUNT（短间隔），保证即使主线程当前没有调用/返回也能尽快进到 Dispatch 完成扩散。
	lua_sethook(mainL_, &HookMux::LuaHookEntry, mask | LUA_MASKCOUNT, 1000);
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
	if (spreadPending_.exchange(false, std::memory_order_acq_rel)) {
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
	Apply(L, dbgMask, debugger_ ? debugger_->CountInterval() : 0);
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
	if (mask == 0) {
		if (lua_gethook(L) != nullptr) lua_sethook(L, nullptr, 0, 0);
		return;
	}
	if (lua_gethookmask(L) != mask || lua_gethook(L) != &HookMux::LuaHookEntry ||
		((mask & LUA_MASKCOUNT) && lua_gethookcount(L) != count)) {
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
