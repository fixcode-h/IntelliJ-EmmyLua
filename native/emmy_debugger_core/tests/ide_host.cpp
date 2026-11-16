// IDE 端到端测试用的宿主：起一个「游戏线程」反复调用脚本里的 tick(i)，在 127.0.0.1:<port> 等 IDE 连接。
// IntelliJ 侧的 debugger-engine 用它跑真 runtime 的行为用例（modules/debugger-engine 的 NativeRuntimeE2eTest）。
//
// 用法：emmy_core_ide_host <port> <脚本文件> <chunkName>
// 准备好后在标准输出打印一行 READY；标准输入关闭（父进程退出）时结束。
#include "emmy/emmy_core.h"
#include "lua_shim.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>

int main(int argc, char** argv) {
	if (argc < 4) {
		std::fprintf(stderr, "usage: %s <port> <script> <chunkName>\n", argv[0]);
		return 2;
	}
	const int port = std::atoi(argv[1]);
	std::ifstream in(argv[2], std::ios::binary);
	if (!in) {
		std::fprintf(stderr, "cannot read %s\n", argv[2]);
		return 2;
	}
	const std::string script((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
	const std::string chunkName = argv[3];

	char err[256] = {0};
	if (Emmy_ListenTcp("127.0.0.1", port, err, sizeof(err)) != 0) {
		std::fprintf(stderr, "listen failed: %s\n", err);
		return 3;
	}

	std::atomic<bool> stop{false};
	std::atomic<bool> ready{false};
	std::thread game([&] {
		lua_State* L = luaL_newstate();
		luaL_openlibs(L);
		Emmy_RegisterLuaVm(L, "ide-host");
		Emmy_SetVmName(L, "GameVM");
		if (emmy::lua::LoadBuffer(L, script.data(), script.size(), chunkName.c_str()) != LUA_OK || lua_pcall(L, 0, 1, 0) != LUA_OK) {
			std::fprintf(stderr, "script error: %s\n", lua_tostring(L, -1));
			std::exit(4);
		}
		const int mod = lua_gettop(L);
		ready = true;
		lua_Integer i = 0;
		while (!stop) {
			lua_getfield(L, mod, "tick");
			lua_pushinteger(L, ++i);
			if (lua_pcall(L, 1, 0, 0) != LUA_OK) {
				std::fprintf(stderr, "lua error: %s\n", lua_tostring(L, -1));
				lua_pop(L, 1);
			}
			std::this_thread::sleep_for(std::chrono::milliseconds(2));
		}
		Emmy_UnregisterLuaVm(L);
		lua_close(L);
	});
	while (!ready) std::this_thread::sleep_for(std::chrono::milliseconds(1));
	std::printf("READY\n");
	std::fflush(stdout);

	// 父进程（测试）退出或关掉管道时结束，避免留下孤儿进程
	while (std::getchar() != EOF) {
	}
	stop = true;
	game.join();
	Emmy_Stop();
	return 0;
}
