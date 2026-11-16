// 需要 Lua 内部结构的少数操作，按版本隔离在 lua_internal.cpp。
// 动态 API 模式下这里换成按偏移读取（旧实现的 native/emmy_debugger/src/api/lua_state/ 已删除，见 git 历史）。
#pragma once

#include <functional>

struct lua_State;
struct lua_Debug;

namespace emmy::lua {

// 同一个 VM 的所有线程共享同一个 global_State，用它作为 VM 的身份（不占用宿主的 extraspace）
const void* GlobalStateOf(lua_State* L);
lua_State* MainThreadOf(lua_State* L);
// 遍历 VM 里所有协程（不含主线程）。只能在 VM 线程、VM 没在执行 GC 时调用（钩子里可以）。
void ForEachCoroutine(lua_State* L, const std::function<void(lua_State*)>& fn);

// 钩子热路径用：不经 lua_getinfo（它会拷贝 short_src 等），直接读 CallInfo 里的函数原型。
struct FuncInfo {
	const char* source = nullptr; // 与 lua_getinfo("S").source 是同一个指针
	int lineDefined = 0;
	int lastLineDefined = 0;
	bool isC = false;
};
// ar 是钩子回调收到的 lua_Debug：取它对应的函数（caller=false）或其调用者（caller=true）。
// 没有调用者（栈底）时返回 false。
bool HookFuncInfo(lua_State* L, lua_Debug* ar, bool caller, FuncInfo& out);

} // namespace emmy::lua
