// emmy_attach.dll 的入口：和 emmy_hook.dll 一样把初始化函数地址写进共享内存，emmy_tool 读到后用远程线程调用。
// DllMain 在 loader lock 下执行，这里只写共享内存，真正的初始化在 AttachInit（远程线程）里做。
#include <Windows.h>

#include "shared/shme.h"

DWORD WINAPI AttachInit(LPVOID param);

HINSTANCE g_attachInstance = nullptr;
static SharedFile g_file;

BOOL WINAPI DllMain(HINSTANCE hModule, DWORD reason, LPVOID) {
	if (reason == DLL_PROCESS_ATTACH) {
		g_attachInstance = hModule;
		DisableThreadLibraryCalls(hModule);
		const DWORD processId = GetCurrentProcessId();
		if (!CreateMemFile(&g_file, processId)) {
			// 共享内存名被占用：多半是旧版 emmy_hook.dll 已经注入过，emmy_tool 会提示重启目标进程
			return FALSE;
		}
		TSharedData data;
		memset(g_file.lpMemFile, 0, SHMEMSIZE);
		data.processId = processId;
		data.hModule = reinterpret_cast<uintptr_t>(hModule);
		data.lpInit = reinterpret_cast<uintptr_t>(&AttachInit);
		data.dwOffset = data.lpInit - data.hModule;
		memcpy(g_file.lpMemFile, &data, sizeof(TSharedData));
	} else if (reason == DLL_PROCESS_DETACH) {
		CloseMemFile(&g_file);
	}
	return TRUE;
}
