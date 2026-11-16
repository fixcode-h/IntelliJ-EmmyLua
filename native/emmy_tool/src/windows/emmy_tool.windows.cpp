#include "emmy_tool.h"
#include <string>
#include <cstdlib>
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

// 协议 v3 的附加：注入 emmy_attach.dll，stdout 最后一行输出结果 JSON（约定见 docs/design/protocol-v3.md 第 16 节）
int EmmyTool::AttachV3() {
	const int pid = _cmd.Get<int>("p");
	const std::string dir = _cmd.Get<std::string>("dir");
	std::string authToken = _cmd.Get<std::string>("auth-token");
	if (authToken.empty()) {
		const char* environmentToken = std::getenv("EMMY_ATTACH_AUTH_TOKEN");
		if (environmentToken != nullptr) authToken = environmentToken;
	}
	auto fail = [&](const std::string& message) {
		printf("\n{\"ok\":false,\"protocol\":3,\"pid\":%d,\"error\":%s}\n", pid, JsonString(message).c_str());
		return 1;
	};
	// 旧版 emmy_hook.dll 和 emmy_attach.dll 共用同一块共享内存，不能同时在一个进程里
	if (IsBeingInjected(pid, "emmy_hook.dll")) {
		return fail("目标进程里已经有旧版调试器（emmy_hook.dll），请重启目标进程后再附加");
	}
	bool alreadyAttached = false;
	DWORD initCode = 0xffffffff;
	const bool ok = InjectDll(pid, dir.c_str(), "emmy_attach.dll", false, authToken, &alreadyAttached, &initCode);
	if (!ok) {
		if (initCode != 0xffffffff && initCode != emmy_attach::kOk) return fail(emmy_attach::ResultMessage(initCode));
		return fail(alreadyAttached ? "重新配置已注入的调试器失败" : "注入 emmy_attach.dll 失败（权限不足、目标进程位数不符或被安全软件拦截）");
	}
	printf("\n{\"ok\":true,\"protocol\":3,\"host\":\"127.0.0.1\",\"port\":%d,\"pid\":%d,\"reinjected\":%s}\n",
		emmy_attach::PortForPid(pid), pid, alreadyAttached ? "true" : "false");
	return 0;
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
