#include "lua_internal.h"

#include <vector>

extern "C" {
#include "lua.h"
#include "lstate.h"
#include "lobject.h"
}

namespace emmy::lua {

const void* GlobalStateOf(lua_State* L) {
	return G(L);
}

#if defined(EMMY_CORE_LUA_DYNAMIC) && !defined(EMMY_CORE_LUA_51)
// 动态模式：宿主的 Lua 可能是同一大版本的另一个小版本（UnLua 自带 5.4.x、改过 luaconf），global_State 里
// mainthread 之前的字段在小版本间变过，不能按我们编译时的偏移读。注册表里的主线程是公开接口，一定对。
lua_State* MainThreadOf(lua_State* L) {
	lua_rawgeti(L, LUA_REGISTRYINDEX, LUA_RIDX_MAINTHREAD);
	lua_State* main = lua_tothread(L, -1);
	lua_pop(L, 1);
	return main ? main : L;
}
#else
lua_State* MainThreadOf(lua_State* L) {
	return G(L)->mainthread;
}
#endif

namespace {

// 按 allgc 链表收集协程。动态模式下布局不一定对，所以：
//   - 先核对 g->mainthread 和注册表里的主线程是同一个，对不上就不遍历（只是已有协程收不到钩子，新协程会继承）；
//   - 每个线程对象再核对 l_G，链表最多走 kMaxObjects 个；
//   - MSVC 上用 SEH 兜底，读到非法地址也只是放弃遍历。
// 这个函数里不能有需要析构的 C++ 对象（__try 的限制），结果写进调用方给的数组。
constexpr size_t kMaxObjects = 4u * 1024u * 1024u;

size_t CollectThreadsRaw(global_State* g, lua_State** out, size_t cap, bool* overflow) {
	size_t n = 0;
	size_t visited = 0;
#if defined(EMMY_CORE_LUA_51)
	for (GCObject* o = g->rootgc; o != nullptr && visited < kMaxObjects; o = o->gch.next, ++visited) {
		if (o->gch.tt != LUA_TTHREAD) continue;
#elif defined(EMMY_CORE_LUA_52)
	for (GCObject* o = g->allgc; o != nullptr && visited < kMaxObjects; o = gch(o)->next, ++visited) {
		if (gch(o)->tt != LUA_TTHREAD) continue;
#elif defined(EMMY_CORE_LUA_53)
	for (GCObject* o = g->allgc; o != nullptr && visited < kMaxObjects; o = o->next, ++visited) {
		if (o->tt != LUA_TTHREAD) continue;
#else
	for (GCObject* o = g->allgc; o != nullptr && visited < kMaxObjects; o = o->next, ++visited) {
		if (o->tt != LUA_VTHREAD) continue;
#endif
		lua_State* co = gco2th(o);
		if (G(co) != g) break; // 布局不对：读到的不是这个 VM 的线程
		if (n == cap) {
			*overflow = true;
			break;
		}
		out[n++] = co;
	}
	return n;
}

size_t CollectThreads(lua_State* L, lua_State** out, size_t cap, bool* overflow) {
	global_State* g = G(L);
#if defined(EMMY_CORE_LUA_DYNAMIC)
#  if !defined(EMMY_CORE_LUA_51)
	lua_State* main = MainThreadOf(L);
#  endif
#  if defined(_MSC_VER)
	__try {
#  endif
#  if !defined(EMMY_CORE_LUA_51)
		if (g->mainthread != main) return 0;
#  endif
		return CollectThreadsRaw(g, out, cap, overflow);
#  if defined(_MSC_VER)
	} __except (1) {
		return 0;
	}
#  endif
#else
	return CollectThreadsRaw(g, out, cap, overflow);
#endif
}

} // namespace

void ForEachCoroutine(lua_State* L, const std::function<void(lua_State*)>& fn) {
	std::vector<lua_State*> threads(256);
	for (;;) {
		bool overflow = false;
		const size_t n = CollectThreads(L, threads.data(), threads.size(), &overflow);
		if (overflow && threads.size() < (1u << 20)) {
			threads.resize(threads.size() * 4);
			continue;
		}
		for (size_t i = 0; i < n; ++i) fn(threads[i]);
		return;
	}
}

#if defined(EMMY_CORE_LUA_DYNAMIC)

// 动态模式：lua_Debug 末尾的 i_ci 在 short_src[LUA_IDSIZE] 之后，宿主改过 LUA_IDSIZE 时偏移就不对了，
// CallInfo 的布局在小版本之间也可能变。只用公开接口：钩子的 ar 交给宿主自己的 lua_getinfo 去解释。
bool HookFuncInfo(lua_State* L, lua_Debug* ar, bool caller, FuncInfo& out) {
	lua_Debug parent;
	lua_Debug* target = ar;
	if (caller) {
		// 钩子里第 0 层是正在执行（或正在返回）的函数，第 1 层是它的调用者
		if (!lua_getstack(L, 1, &parent)) return false;
		target = &parent;
	}
	if (!lua_getinfo(L, "S", target)) return false;
	out.source = target->source ? target->source : "=?";
	out.lineDefined = target->linedefined;
	out.lastLineDefined = target->lastlinedefined;
	out.isC = target->what != nullptr && target->what[0] == 'C';
	if (out.isC) out.source = "=[C]";
	return true;
}

#else

namespace {

#if defined(EMMY_CORE_LUA_51)
void FillFromFunc(StkId func, FuncInfo& out) {
	if (!ttisfunction(func) || clvalue(func)->c.isC) {
		out.isC = true;
		out.source = "=[C]";
		return;
	}
	Proto* p = clvalue(func)->l.p;
	out.source = p->source ? getstr(p->source) : "=?";
	out.lineDefined = p->linedefined;
	out.lastLineDefined = p->lastlinedefined;
}
#else
void FillFromFunc(const TValue* func, FuncInfo& out) {
	if (!ttisLclosure(func)) {
		out.isC = true;
		out.source = "=[C]";
		return;
	}
	Proto* p = clLvalue(func)->p;
	out.source = p->source ? getstr(p->source) : "=?";
	out.lineDefined = p->linedefined;
	out.lastLineDefined = p->lastlinedefined;
}
#endif

} // namespace

bool HookFuncInfo(lua_State* L, lua_Debug* ar, bool caller, FuncInfo& out) {
#if defined(EMMY_CORE_LUA_51)
	CallInfo* ci = L->base_ci + ar->i_ci;
	if (caller) {
		if (ci <= L->base_ci) return false;
		ci = ci - 1;
		if (ci == L->base_ci) return false;
	}
	FillFromFunc(ci->func, out);
	return true;
#else
	CallInfo* ci = ar->i_ci;
	if (caller) {
		ci = ci->previous;
		if (ci == nullptr || ci == &L->base_ci) return false;
	}
#  if defined(EMMY_CORE_LUA_54)
	FillFromFunc(s2v(ci->func.p), out);
#  else
	FillFromFunc(ci->func, out);
#  endif
	return true;
#endif
}

#endif // EMMY_CORE_LUA_DYNAMIC

} // namespace emmy::lua
