// 附加端到端测试的驱动：扮演 IDE，走一遍用户在 IDE 里「附加到进程」的完整链路（和 LocalAttachTool.kt 同样的命令行）。
//   1. 起 attach_host.exe（只链接 lua54.dll 的「游戏」）
//   2. emmy_tool.exe attach -p <pid> -dir <工具目录> -auth-token <token> -protocol 3，取 stdout 最后一行 JSON 的端口
//   3. 连 runtime、hello（带 token）、下断点、等暂停、看栈、继续、断开
//   4. 再附加一次（reinjected:true，换新 token），断点照样能停（验收清单 A11 / A12）
//   5. 给了 attach_host_late.exe 时：起一个延迟加载 Lua 的宿主，先附加（waiting:true），再让它加载 Lua，
//      断点要停在 VM 一创建就执行、只执行一次的顶层代码上（UE 里先附加再开始 PIE、断在 ReceiveInit 的情况）
// 全部通过返回 0；任何一步失败打印原因返回 1。
#include "test_client.h"

#include <winsock2.h>
#include <windows.h>

#include <chrono>
#include <cstdio>
#include <string>
#include <thread>

using emmy::test::TestClient;
using json = nlohmann::json;

namespace {

int g_failures = 0;

void Check(bool ok, const std::string& what) {
	std::printf("%s %s\n", ok ? "[ OK ]" : "[FAIL]", what.c_str());
	std::fflush(stdout);
	if (!ok) ++g_failures;
}

std::string RunCapture(const std::string& cmd, int& exitCode) {
	std::string out;
	FILE* p = _popen(cmd.c_str(), "r");
	if (!p) {
		exitCode = -1;
		return out;
	}
	char buf[4096];
	while (std::fgets(buf, sizeof(buf), p)) out += buf;
	exitCode = _pclose(p);
	return out;
}

json LastJsonLine(const std::string& text) {
	size_t end = text.size();
	while (end > 0) {
		size_t start = text.rfind('\n', end - 1);
		start = start == std::string::npos ? 0 : start + 1;
		std::string line = text.substr(start, end - start);
		while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
		if (!line.empty() && line[0] == '{') {
			try {
				return json::parse(line);
			} catch (...) {
				return nullptr;
			}
		}
		if (start == 0) break;
		end = start - 1;
	}
	return nullptr;
}

bool Ok(const json& r) {
	return r.is_object() && r.value("ok", false);
}

json Attach(const std::string& tools, DWORD pid, const std::string& token) {
	const std::string cmd = "\"\"" + tools + "\\emmy_tool.exe\" attach -p " + std::to_string(pid) + " -dir \"" + tools +
		"\" -dll emmy_hook.dll -auth-token " + token + " -protocol 3\"";
	int exitCode = 0;
	const std::string out = RunCapture(cmd, exitCode);
	std::printf("emmy_tool（退出码 %d）：\n%s\n", exitCode, out.c_str());
	json result = LastJsonLine(out);
	Check(exitCode == 0 && result.is_object() && result.value("ok", false), "emmy_tool attach 成功");
	return result;
}

bool ConnectRuntime(TestClient& c, const json& attach, const std::string& token) {
	const int port = attach.value("port", 0);
	// waiting:true 时 Lua 加载后才监听；这里宿主一开始就有 Lua，最多等几秒
	bool connected = false;
	for (int i = 0; i < 20 && !connected; ++i) {
		connected = c.Connect(attach.value("host", std::string("127.0.0.1")), port, 1000);
		if (!connected) std::this_thread::sleep_for(std::chrono::milliseconds(500));
	}
	Check(connected, "连上 runtime 端口 " + std::to_string(port));
	if (!connected) return false;
	json hello = c.Request("hello", {{"protocol", 3}, {"client", {{"name", "attach-e2e"}, {"version", "0"}}}, {"token", token}});
	Check(Ok(hello), "hello（带 token）");
	if (!Ok(hello)) {
		std::printf("hello 回复：%s\n", hello.dump().c_str());
		return false;
	}
	Check(Ok(c.Request("configurationDone")), "configurationDone");
	return true;
}

void BreakOnce(TestClient& c, uint64_t revision, const char* label) {
	json bp = {{"id", "e2e"}, {"source", {{"path", "D:/proj/game/main.lua"}}}, {"line", 4}};
	Check(Ok(c.Request("setBreakpoints", {{"revision", revision}, {"replaceAll", true}, {"upserts", json::array({bp})}})),
		std::string(label) + "：下断点 main.lua:4");
	json paused = c.WaitEvent("paused", 10000);
	Check(paused.is_object(), std::string(label) + "：10 秒内停在断点");
	if (!paused.is_object()) return;
	const json& body = paused["body"];
	json stack = c.Request("stackTrace", {{"vmId", body["vmId"]}, {"pauseId", body["pauseId"]}});
	bool atLine = false;
	if (Ok(stack) && !stack["body"]["frames"].empty()) {
		atLine = stack["body"]["frames"][0].value("line", 0) == 4;
	}
	Check(atLine, std::string(label) + "：栈顶在第 4 行");
	Check(Ok(c.Request("setBreakpoints", {{"revision", revision + 1}, {"replaceAll", true}, {"upserts", json::array()}})),
		std::string(label) + "：清掉断点");
	Check(Ok(c.Request("continue", {{"vmId", body["vmId"]}, {"pauseId", body["pauseId"]}})), std::string(label) + "：继续");
}

// 晚加载 Lua 的宿主：附加时还没有 Lua；Lua 加载后第一个 VM 要等 IDE 下完断点再跑
void LateLoadScenario(const std::string& tools, const std::string& hostExe) {
	std::printf("\n== 先附加、后加载 Lua ==\n");
	SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
	HANDLE stdinRead = nullptr, stdinWrite = nullptr;
	if (!CreatePipe(&stdinRead, &stdinWrite, &sa, 0)) {
		Check(false, "建标准输入管道");
		return;
	}
	SetHandleInformation(stdinWrite, HANDLE_FLAG_INHERIT, 0);
	STARTUPINFOA si{};
	si.cb = sizeof(si);
	si.dwFlags = STARTF_USESTDHANDLES;
	si.hStdInput = stdinRead;
	si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
	si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
	PROCESS_INFORMATION pi{};
	std::string cmdLine = "\"" + hostExe + "\"";
	if (!CreateProcessA(nullptr, cmdLine.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
		Check(false, "启动晚加载宿主");
		CloseHandle(stdinRead);
		CloseHandle(stdinWrite);
		return;
	}
	CloseHandle(stdinRead);
	std::this_thread::sleep_for(std::chrono::milliseconds(500));

	const std::string token = "e2e00000000000000000000000000003";
	json attach = Attach(tools, pi.dwProcessId, token);
	Check(attach.is_object() && attach.value("waiting", false), "附加时还没有 Lua：waiting:true");
	if (attach.is_object() && attach.value("ok", false)) {
		// 让宿主加载 Lua；它的第一个 VM 会等我们下完断点
		DWORD written = 0;
		WriteFile(stdinWrite, "GO\n", 3, &written, nullptr);
		const auto started = std::chrono::steady_clock::now();
		TestClient c;
		bool connected = false;
		for (int i = 0; i < 50 && !connected; ++i) {
			connected = c.Connect(attach.value("host", std::string("127.0.0.1")), attach.value("port", 0), 1000);
			if (!connected) std::this_thread::sleep_for(std::chrono::milliseconds(200));
		}
		Check(connected, "Lua 加载后连上 runtime");
		if (connected) {
			Check(Ok(c.Request("hello", {{"protocol", 3}, {"client", {{"name", "attach-e2e"}, {"version", "0"}}}, {"token", token}})),
				"hello（带 token）");
			json bp = {{"id", "late"}, {"source", {{"path", "D:/proj/game/init.lua"}}}, {"line", 3}};
			Check(Ok(c.Request("setBreakpoints", {{"revision", 1}, {"replaceAll", true}, {"upserts", json::array({bp})}})),
				"下断点 init.lua:3（只在加载时执行一次）");
			Check(Ok(c.Request("configurationDone")), "configurationDone");
			json paused = c.WaitEvent("paused", 10000);
			Check(paused.is_object(), "停在加载时就执行的第 3 行");
			if (paused.is_object()) {
				const json& body = paused["body"];
				json stack = c.Request("stackTrace", {{"vmId", body["vmId"]}, {"pauseId", body["pauseId"]}});
				Check(Ok(stack) && !stack["body"]["frames"].empty() && stack["body"]["frames"][0].value("line", 0) == 3, "栈顶在第 3 行");
				Check(Ok(c.Request("continue", {{"vmId", body["vmId"]}, {"pauseId", body["pauseId"]}})), "继续");
			}
			const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
			std::printf("从让宿主加载 Lua 到停住：%lld 毫秒\n", static_cast<long long>(ms));
			c.Request("disconnect");
		}
	}
	CloseHandle(stdinWrite);
	TerminateProcess(pi.hProcess, 0);
	CloseHandle(pi.hThread);
	CloseHandle(pi.hProcess);
}

} // namespace

int main(int argc, char** argv) {
	SetConsoleOutputCP(CP_UTF8);
	if (argc < 3) {
		std::fprintf(stderr, "usage: attach_driver <tools dir> <attach_host.exe> [attach_host_late.exe]\n");
		return 2;
	}
	const std::string tools = argv[1];
	const std::string hostExe = argv[2];
	WSADATA wsa;
	WSAStartup(MAKEWORD(2, 2), &wsa);

	STARTUPINFOA si{};
	si.cb = sizeof(si);
	PROCESS_INFORMATION pi{};
	std::string cmdLine = "\"" + hostExe + "\"";
	if (!CreateProcessA(nullptr, cmdLine.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
		std::printf("[FAIL] 启动宿主失败（%lu）\n", GetLastError());
		return 1;
	}
	std::this_thread::sleep_for(std::chrono::seconds(1));
	std::printf("宿主 pid %lu\n", pi.dwProcessId);

	{
		const std::string token = "e2e00000000000000000000000000001";
		json first = Attach(tools, pi.dwProcessId, token);
		if (first.is_object() && first.value("ok", false)) {
			Check(!first.value("reinjected", false), "第一次附加不是复用");
			TestClient c;
			if (ConnectRuntime(c, first, token)) {
				BreakOnce(c, 1, "第一次附加");
				// 断开时游戏不能卡住（A11）
				c.Request("disconnect");
			}
		}
	}
	std::this_thread::sleep_for(std::chrono::milliseconds(500));
	DWORD code = 0;
	Check(GetExitCodeProcess(pi.hProcess, &code) && code == STILL_ACTIVE, "断开后游戏还在运行");

	{
		const std::string token = "e2e00000000000000000000000000002";
		json second = Attach(tools, pi.dwProcessId, token);
		if (second.is_object() && second.value("ok", false)) {
			Check(second.value("reinjected", false), "第二次附加复用已注入的组件");
			TestClient c;
			if (ConnectRuntime(c, second, token)) {
				BreakOnce(c, 10, "再次附加");
				c.Request("disconnect");
			}
		}
	}

	TerminateProcess(pi.hProcess, 0);
	CloseHandle(pi.hThread);
	CloseHandle(pi.hProcess);
	if (argc >= 4) LateLoadScenario(tools, argv[3]);
	std::printf("\n%s：%d 项失败\n", g_failures ? "失败" : "通过", g_failures);
	return g_failures ? 1 : 0;
}
