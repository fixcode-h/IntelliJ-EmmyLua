// 附加端到端测试的「晚加载 Lua 的游戏」：和 UE 里的 UnLua 一样，lua54.dll 是延迟加载的（/DELAYLOAD），
// 第一次调 lua_* 时才加载，紧接着建 VM 并执行一次模块顶层代码（像 GameInstance 的 ReceiveInit，只跑一次）。
// 启动后打印 READY，从标准输入读到一行 GO 才开始碰 Lua；之后每 2 毫秒调一次 tick(i)，最多跑 3 分钟。
// 带参数 --go 时不等标准输入、马上碰 Lua（「启动并附加」的测试：程序一启动就建 VM）。
extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
}

#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>

static const char kScript[] =
	"local M = {}\n"                    // 1
	"local inited = 0\n"                // 2
	"inited = inited + 1\n"             // 3  只在加载时执行一次
	"function M.tick(i)\n"              // 4
	"  return i + inited\n"             // 5
	"end\n"                             // 6
	"return M\n";                       // 7

int main(int argc, char** argv) {
	std::printf("READY\n");
	std::fflush(stdout);
	char line[64] = {0};
	const bool go = argc > 1 && std::strcmp(argv[1], "--go") == 0;
	if (!go && (!std::fgets(line, sizeof(line), stdin) || std::strncmp(line, "GO", 2) != 0)) return 3;
	// 这里第一次调 lua_* → 延迟加载 lua54.dll
	lua_State* L = luaL_newstate();
	luaL_openlibs(L);
	if (luaL_loadbuffer(L, kScript, std::strlen(kScript), "@D:/proj/game/init.lua") != LUA_OK || lua_pcall(L, 0, 1, 0) != LUA_OK) {
		std::fprintf(stderr, "script error: %s\n", lua_tostring(L, -1));
		return 2;
	}
	const int mod = lua_gettop(L);
	const auto deadline = std::chrono::steady_clock::now() + std::chrono::minutes(3);
	lua_Integer i = 0;
	while (std::chrono::steady_clock::now() < deadline) {
		lua_getfield(L, mod, "tick");
		lua_pushinteger(L, ++i);
		if (lua_pcall(L, 1, 1, 0) != LUA_OK) std::fprintf(stderr, "lua error: %s\n", lua_tostring(L, -1));
		lua_pop(L, 1);
		std::this_thread::sleep_for(std::chrono::milliseconds(2));
	}
	lua_close(L);
	return 0;
}
