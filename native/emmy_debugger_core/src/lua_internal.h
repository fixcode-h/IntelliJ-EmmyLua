// 需要 Lua 内部结构的少数操作，按版本隔离在 lua_internal.cpp。
// 动态 API 模式下这里换成按偏移读取（参考原 native/emmy_debugger/src/api/lua_state/）。
#pragma once

#include <functional>

struct lua_State;

namespace emmy::lua {

// 同一个 VM 的所有线程共享同一个 global_State，用它作为 VM 的身份（不占用宿主的 extraspace）
const void* GlobalStateOf(lua_State* L);
lua_State* MainThreadOf(lua_State* L);
// 遍历 VM 里所有协程（不含主线程）。只能在 VM 线程、VM 没在执行 GC 时调用（钩子里可以）。
void ForEachCoroutine(lua_State* L, const std::function<void(lua_State*)>& fn);

} // namespace emmy::lua
