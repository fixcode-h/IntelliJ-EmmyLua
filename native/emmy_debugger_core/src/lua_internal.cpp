#include "lua_internal.h"

extern "C" {
#include "lua.h"
#include "lstate.h"
#include "lobject.h"
}

namespace emmy::lua {

const void* GlobalStateOf(lua_State* L) {
	return G(L);
}

lua_State* MainThreadOf(lua_State* L) {
	return G(L)->mainthread;
}

void ForEachCoroutine(lua_State* L, const std::function<void(lua_State*)>& fn) {
	global_State* g = G(L);
#if defined(EMMY_CORE_LUA_51)
	for (GCObject* o = g->rootgc; o != nullptr; o = o->gch.next) {
		if (o->gch.tt == LUA_TTHREAD) fn(gco2th(o));
	}
#elif defined(EMMY_CORE_LUA_52)
	for (GCObject* o = g->allgc; o != nullptr; o = gch(o)->next) {
		if (gch(o)->tt == LUA_TTHREAD) fn(gco2th(o));
	}
#elif defined(EMMY_CORE_LUA_53)
	for (GCObject* o = g->allgc; o != nullptr; o = o->next) {
		if (o->tt == LUA_TTHREAD) fn(gco2th(o));
	}
#else
	for (GCObject* o = g->allgc; o != nullptr; o = o->next) {
		if (o->tt == LUA_VTHREAD) fn(gco2th(o));
	}
#endif
}

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

} // namespace emmy::lua
