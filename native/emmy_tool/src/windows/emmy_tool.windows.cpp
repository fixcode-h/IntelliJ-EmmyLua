#include "emmy_tool.h"
#include <string>
#include <cstdlib>
#include <cstdio>
#include "utility.h"	
#include "Psapi.h"
#include <thread>
#include "shared/attach_result.h"


void translateText(std::string &text) {
	for (auto &c: text) {
		if (c == '\n') {
			c = ' ';
		}
	}
}

namespace {
std::string JsonString(const std::string& s) {
	std::string out = "\"";
	for (char c : s) {
		if (c == '"' || c == '\\') { out += '\\'; out += c; }
		else if (c == '\n') out += "\\n";
		else if (static_cast<unsigned char>(c) < 0x20) out += ' ';
		else out += c;
	}
	return out + "\"";
}
} // namespace

namespace {
std::string AuthToken(const CommandLine& cmd) {
	std::string authToken = cmd.Get<std::string>("auth-token");
	if (authToken.empty()) {
		const char* environmentToken = std::getenv("EMMY_ATTACH_AUTH_TOKEN");
		if (environmentToken != nullptr) authToken = environmentToken;
	}
	return authToken;
}

int Fail(int pid, const std::string& message) {
	printf("\n{\"ok\":false,\"protocol\":3,\"pid\":%d,\"error\":%s}\n", pid, JsonString(message).c_str());
	return 1;
}

// 注入 emmy_attach.dll 并在 stdout 最后一行输出结果 JSON；extra 是追加在 JSON 末尾的字段（以逗号开头）
int InjectAndReport(int pid, const std::string& dir, const std::string& authToken, const std::string& extra) {
	// 旧版 emmy_hook.dll 和 emmy_attach.dll 共用同一块共享内存，不能同时在一个进程里
	if (IsBeingInjected(pid, "emmy_hook.dll")) {
		return Fail(pid, "目标进程里已经有旧版调试器（emmy_hook.dll），请重启目标进程后再附加");
	}
	bool alreadyAttached = false;
	DWORD initCode = 0xffffffff;
	const bool ok = InjectDll(pid, dir.c_str(), "emmy_attach.dll", false, authToken, &alreadyAttached, &initCode);
	if (!ok && initCode == emmy_attach::kWaitingForLua) {
		// 还没有 Lua：emmy_attach 已经在后台等，Lua 加载后才开始监听 port，IDE 在这之前连不上是正常的
		printf("\n{\"ok\":true,\"protocol\":3,\"host\":\"127.0.0.1\",\"port\":%d,\"pid\":%d,\"reinjected\":%s,\"waiting\":true,\"message\":%s%s}\n",
			emmy_attach::PortForPid(pid), pid, alreadyAttached ? "true" : "false",
			JsonString(emmy_attach::ResultMessage(initCode)).c_str(), extra.c_str());
		return 0;
	}
	if (!ok) {
		if (initCode != 0xffffffff && initCode != emmy_attach::kOk) return Fail(pid, emmy_attach::ResultMessage(initCode));
		return Fail(pid, alreadyAttached ? "重新配置已注入的调试器失败" : "注入 emmy_attach.dll 失败（权限不足、目标进程位数不符或被安全软件拦截）");
	}
	printf("\n{\"ok\":true,\"protocol\":3,\"host\":\"127.0.0.1\",\"port\":%d,\"pid\":%d,\"reinjected\":%s%s}\n",
		emmy_attach::PortForPid(pid), pid, alreadyAttached ? "true" : "false", extra.c_str());
	return 0;
}

std::wstring EnvW(const wchar_t* name) {
	const DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
	if (size == 0) return std::wstring();
	std::wstring value(size, L'\0');
	const DWORD written = GetEnvironmentVariableW(name, &value[0], size);
	value.resize(written);
	return value;
}

std::wstring Widen(const std::string& s) {
	if (s.empty()) return std::wstring();
	const int n = MultiByteToWideChar(CP_ACP, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
	std::wstring out(n, L'\0');
	MultiByteToWideChar(CP_ACP, 0, s.data(), static_cast<int>(s.size()), &out[0], n);
	return out;
}

std::string LastErrorText(DWORD error) {
	char buffer[512] = {0};
	if (FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, error, 0, buffer, sizeof(buffer), nullptr) == 0) {
		return "错误码 " + std::to_string(error);
	}
	std::string text = buffer;
	while (!text.empty() && (text.back() == '\r' || text.back() == '\n' || text.back() == ' ')) text.pop_back();
	return text + "（" + std::to_string(error) + "）";
}

// 启动到入口点的最长时间：UE 编辑器启动时要加载几百个静态依赖的 DLL，慢机器上要几十秒
const DWORD kLaunchToEntryTimeoutMs = 90000;
} // namespace

// 协议 v3 的附加：注入 emmy_attach.dll，stdout 最后一行输出结果 JSON（约定见 docs/design/protocol-v3.md 第 16 节）
int EmmyTool::AttachV3() {
	const int pid = _cmd.Get<int>("p");
	return InjectAndReport(pid, _cmd.Get<std::string>("dir"), AuthToken(_cmd), std::string());
}

// 启动并附加（协议 v3 第 16 节）：以调试方式启动程序，停在 exe 入口点（静态依赖的 DLL 都已加载、程序自己的代码一行没跑），
// 摘掉调试器、挂起主线程注入 emmy_attach.dll，再恢复主线程。这样程序里第一个 Lua VM 建出来时调试组件一定已经就位，
// 第一行 Lua 也能断住（第一个 VM 会等 IDE 下完断点再跑）。
// 不用 CREATE_SUSPENDED 直接注入：那时进程的加载器还没初始化，远程线程里调 LoadLibrary 不可靠。
int EmmyTool::Launch() {
	std::wstring exe = EnvW(L"EMMY_LAUNCH_EXE");
	if (exe.empty()) exe = Widen(_cmd.Get<std::string>("exe"));
	std::wstring work = EnvW(L"EMMY_LAUNCH_WORK");
	if (work.empty()) work = Widen(_cmd.Get<std::string>("work"));
	const std::wstring args = EnvW(L"EMMY_LAUNCH_ARGS");
	// 这几个变量只是给我们传参的，别让被启动的程序继承
	SetEnvironmentVariableW(L"EMMY_LAUNCH_EXE", nullptr);
	SetEnvironmentVariableW(L"EMMY_LAUNCH_WORK", nullptr);
	SetEnvironmentVariableW(L"EMMY_LAUNCH_ARGS", nullptr);
	if (exe.empty()) return Fail(0, "没有指定要启动的程序");

	std::wstring commandLine = L"\"" + exe + L"\"";
	if (!args.empty()) commandLine += L" " + args;
	STARTUPINFOW si{};
	si.cb = sizeof(si);
	PROCESS_INFORMATION pi{};
	DWORD flags = DEBUG_ONLY_THIS_PROCESS;
	if (_cmd.Get<bool>("create-new-window")) {
		flags |= CREATE_NEW_CONSOLE;
	} else {
		// 不把我们的标准输入输出给程序：IDE 读 emmy_tool 的输出要等管道关闭，控制台程序即使不继承句柄，
		// 系统也会把父进程的标准句柄复制给它，程序就一直拿着管道，IDE 要等到程序退出才读完
		si.dwFlags |= STARTF_USESTDHANDLES;
		si.hStdInput = nullptr;
		si.hStdOutput = nullptr;
		si.hStdError = nullptr;
	}
	// 不继承其他句柄
	if (!CreateProcessW(exe.c_str(), &commandLine[0], nullptr, nullptr, FALSE, flags, nullptr,
		work.empty() ? nullptr : work.c_str(), &si, &pi)) {
		return Fail(0, "启动程序失败：" + LastErrorText(GetLastError()));
	}
	const int pid = static_cast<int>(pi.dwProcessId);
	auto giveUp = [&](const std::string& message) {
		DebugSetProcessKillOnExit(TRUE);
		TerminateProcess(pi.hProcess, 1);
		CloseHandle(pi.hThread);
		CloseHandle(pi.hProcess);
		return Fail(pid, message);
	};

	LPVOID entry = nullptr;
	BYTE original = 0;
	HANDLE mainThread = nullptr;
	const DWORD deadline = GetTickCount() + kLaunchToEntryTimeoutMs;
	while (mainThread == nullptr) {
		DEBUG_EVENT ev{};
		if (!WaitForDebugEvent(&ev, 500)) {
			if (static_cast<LONG>(GetTickCount() - deadline) > 0) return giveUp("程序启动 90 秒还没跑到入口点，已结束它");
			continue;
		}
		DWORD status = DBG_CONTINUE;
		switch (ev.dwDebugEventCode) {
		case CREATE_PROCESS_DEBUG_EVENT:
			if (ev.u.CreateProcessInfo.hFile != nullptr) CloseHandle(ev.u.CreateProcessInfo.hFile);
			entry = reinterpret_cast<LPVOID>(ev.u.CreateProcessInfo.lpStartAddress);
			if (entry != nullptr) SetBreakpoint(pi.hProcess, entry, true, &original);
			break;
		case LOAD_DLL_DEBUG_EVENT:
			if (ev.u.LoadDll.hFile != nullptr) CloseHandle(ev.u.LoadDll.hFile);
			break;
		case EXCEPTION_DEBUG_EVENT: {
			const EXCEPTION_RECORD& record = ev.u.Exception.ExceptionRecord;
			if (record.ExceptionCode == EXCEPTION_BREAKPOINT && entry != nullptr && record.ExceptionAddress == entry) {
				// 到入口点了：恢复原来的指令、把指令指针拨回去，挂起主线程等注入完
				SetBreakpoint(pi.hProcess, entry, false, &original);
				HANDLE thread = OpenThread(THREAD_GET_CONTEXT | THREAD_SET_CONTEXT | THREAD_SUSPEND_RESUME, FALSE, ev.dwThreadId);
				if (thread == nullptr) return giveUp("取不到程序主线程：" + LastErrorText(GetLastError()));
				CONTEXT context{};
				context.ContextFlags = CONTEXT_CONTROL;
				if (!GetThreadContext(thread, &context)) {
					CloseHandle(thread);
					return giveUp("读取主线程状态失败：" + LastErrorText(GetLastError()));
				}
#ifdef _WIN64
				context.Rip = reinterpret_cast<DWORD64>(entry);
#else
				context.Eip = reinterpret_cast<DWORD>(entry);
#endif
				SetThreadContext(thread, &context);
				SuspendThread(thread);
				mainThread = thread;
			} else if (record.ExceptionCode != EXCEPTION_BREAKPOINT) {
				// 程序自己的异常交还给它处理（加载器初始化时的那次断点我们自己吃掉）
				status = DBG_EXCEPTION_NOT_HANDLED;
			}
			break;
		}
		case EXIT_PROCESS_DEBUG_EVENT: {
			const DWORD code = ev.u.ExitProcess.dwExitCode;
			ContinueDebugEvent(ev.dwProcessId, ev.dwThreadId, DBG_CONTINUE);
			CloseHandle(pi.hThread);
			CloseHandle(pi.hProcess);
			char hex[16];
			snprintf(hex, sizeof(hex), "0x%08lX", static_cast<unsigned long>(code));
			return Fail(pid, std::string("程序还没跑到入口点就退出了（退出码 ") + hex + "），常见原因是缺 DLL 或参数不对");
		}
		default:
			break;
		}
		ContinueDebugEvent(ev.dwProcessId, ev.dwThreadId, status);
	}
	// 摘掉调试器（程序继续活着）；之后注入时远程线程的事件不用我们处理
	DebugSetProcessKillOnExit(FALSE);
	if (!DebugActiveProcessStop(pi.dwProcessId)) {
		CloseHandle(mainThread);
		return giveUp("摘除调试器失败：" + LastErrorText(GetLastError()));
	}
	const int result = InjectAndReport(pid, _cmd.Get<std::string>("dir"), AuthToken(_cmd), ",\"launched\":true");
	if (result != 0) {
		// 注入失败就别留一个没法调试的程序（IDE 会报这一步的错误）
		TerminateProcess(pi.hProcess, 1);
	} else {
		ResumeThread(mainThread);
	}
	CloseHandle(mainThread);
	CloseHandle(pi.hThread);
	CloseHandle(pi.hProcess);
	return result;
}

// 只剩协议 v3 的附加；-protocol / -dll 参数为兼容旧调用保留，不再使用
int EmmyTool::Attach() {
	return AttachV3();
}

int EmmyTool::Capabilities() {
	printf("{\"schemaVersion\":2,\"tool\":\"emmy_tool\",\"protocol\":3,\"attachAuthTokenEnv\":\"EMMY_ATTACH_AUTH_TOKEN\"}\n");
	return 0;
}

int EmmyTool::ListProcesses() {
	std::vector<Process> list;
	GetProcesses(list);

	for (auto &value: list) {
		printf("%d\n", value.id);
		// title 中可能出现\n 所以title中的\n全部转为' '
		translateText(value.title);

		printf("%s\n", value.title.c_str());

		translateText(value.path);

		printf("%s\n", value.path.c_str());
		printf("----\n");
	}
	return 0;
}

int EmmyTool::ArchFile() {
	ExeInfo info;
	if (GetExeInfo(_cmd.GetArg(2).c_str(), info)) {
		printf("%d", info.i386);
		return info.i386;
	}
	return -1;//file not exist
}

int EmmyTool::ArchPid() {
	const DWORD processId = std::stoi(_cmd.GetArg(2));
	char fileName[_MAX_PATH];
	HANDLE process = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, processId);
	GetModuleFileNameEx(process, nullptr, fileName, _MAX_PATH);

	ExeInfo info;
	if (GetExeInfo(fileName, info)) {
		printf("%d", info.i386);
		return info.i386;
	}
	return -1;
}
