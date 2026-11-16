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

} // namespace emmy::lua
