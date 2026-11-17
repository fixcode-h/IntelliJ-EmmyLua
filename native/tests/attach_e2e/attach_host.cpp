// 附加端到端测试的「游戏」：只链接 lua54.dll，不链接也不加载任何调试代码。
// 启动后在标准输出打印 READY，然后每 2 毫秒调一次 tick(i)，最多跑 3 分钟（驱动程序出问题时也会自己退出）。
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
	"local total = 0\n"                 // 2
	"function M.add(a, b)\n"            // 3
	"  local sum = a + b\n"             // 4
	"  return sum\n"                    // 5
	"end\n"                             // 6
	"function M.tick(i)\n"              // 7
	"  local r = M.add(i, 1)\n"         // 8
	"  total = total + r\n"             // 9
	"  return r\n"                      // 10
	"end\n"                             // 11
	"return M\n";                       // 12

int main() {
	lua_State* L = luaL_newstate();
	luaL_openlibs(L);
	if (luaL_loadbuffer(L, kScript, std::strlen(kScript), "@D:/proj/game/main.lua") != LUA_OK || lua_pcall(L, 0, 1, 0) != LUA_OK) {
		std::fprintf(stderr, "script error: %s\n", lua_tostring(L, -1));
		return 2;
	}
	const int mod = lua_gettop(L);
	std::printf("READY\n");
	std::fflush(stdout);
	const auto deadline = std::chrono::steady_clock::now() + std::chrono::minutes(3);
	lua_Integer i = 0;
	while (std::chrono::steady_clock::now() < deadline) {
		lua_getfield(L, mod, "tick");
		lua_pushinteger(L, ++i);
		if (lua_pcall(L, 1, 1, 0) != LUA_OK) {
			std::fprintf(stderr, "lua error: %s\n", lua_tostring(L, -1));
		}
		lua_pop(L, 1);
		std::this_thread::sleep_for(std::chrono::milliseconds(2));
	}
	lua_close(L);
	return 0;
}
