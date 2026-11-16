// emmy_attach.dll（协议 v3 的 Windows 本机附加）和 emmy_tool 之间的约定：初始化远程线程的退出码、监听端口。
#pragma once

#include <cstdint>

namespace emmy_attach {

enum Result : uint32_t {
	kOk = 0,
	kNoLua = 1,          // 进程里没有导出 Lua API 的模块
	kLuaJit = 2,         // LuaJIT（内部结构不同，新核心不支持）
	kCoreLoadFailed = 3, // lua5X/emmy_debugger_core.dll 加载失败
	kLuaApiMissing = 4,  // 核心要用的 lua_* 在模块里找不到
	kListenFailed = 5,   // 端口被占用等
	kHookFailed = 6,     // 一个 Lua 入口都没挂上
};

inline const char* ResultMessage(uint32_t code) {
	switch (code) {
	case kOk: return "";
	case kNoLua: return "目标进程里没有找到导出 Lua API 的模块（Lua 可能是静态链接且没有导出符号，或者还没加载）";
	case kLuaJit: return "目标进程用的是 LuaJIT，新调试核心暂不支持";
	case kCoreLoadFailed: return "加载调试核心 emmy_debugger_core.dll 失败：检查插件目录下 x86/x64 的 lua5X 子目录是否完整";
	case kLuaApiMissing: return "目标进程的 Lua 模块缺少调试需要的函数（可能被裁剪或改名）";
	case kListenFailed: return "调试端口监听失败（可能被占用），请重启目标进程后再附加";
	case kHookFailed: return "没能挂上任何 Lua 入口函数（lua_pcall / lua_pcallk / lua_resume）";
	default: return "附加初始化失败";
	}
}

// 调试端口：沿用旧的「按 PID 派生」公式，IDE 以 emmy_tool 输出的 port 为准
inline int PortForPid(int pid) {
	int port = pid;
	while (port > 0xffff) port -= 0xffff;
	while (port < 0x400) port += 0x400;
	return port >= 0xffff ? 0x401 : port + 1;
}

} // namespace emmy_attach
