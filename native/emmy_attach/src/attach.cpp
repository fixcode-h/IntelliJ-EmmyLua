// emmy_attach.dll：协议 v3 的 Windows 本机附加。
//
// AttachInit 由 emmy_tool 用远程线程调用（每次附加 / 重新附加都调一次）：
//   1. 第一次：在已加载模块里找导出 lua_* 的模块，按导出判断 Lua 版本，加载 lua5X\emmy_debugger_core.dll，
//      用这个模块的导出表给核心解析 Lua 函数，挂 lua_pcall / lua_pcallk / lua_resume / lua_close，监听端口；
//   2. 每次：更新 token（IDE 每个会话一个）。
// 挂钩的入口只负责在 Lua 线程上把 lua_State 报给核心（Emmy_RegisterLuaVm），其余都在核心里。
// 目标进程之后才加载的 Lua 模块不处理：附加时 Lua 必须已经在跑（附加到运行中的游戏就是这样）。
#include <Windows.h>
#include <Psapi.h>
#include <TlHelp32.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <unordered_map>
#include <unordered_set>

#include "easyhook.h"
#include "emmy/emmy_core.h"
#include "shared/attach_result.h"
#include "shared/shme.h"

extern HINSTANCE g_attachInstance;

namespace {

// ---- 核心（按版本动态加载的 emmy_debugger_core.dll） ----
struct CoreApi {
	decltype(&Emmy_SetLuaApiResolver) setLuaApiResolver = nullptr;
	decltype(&Emmy_LastError) lastError = nullptr;
	decltype(&Emmy_SetProcessInfo) setProcessInfo = nullptr;
	decltype(&Emmy_SetToken) setToken = nullptr;
	decltype(&Emmy_ListenTcp) listenTcp = nullptr;
	decltype(&Emmy_RegisterLuaVm) registerLuaVm = nullptr;
	decltype(&Emmy_UnregisterLuaVm) unregisterLuaVm = nullptr;
};

SRWLOCK g_initLock = SRWLOCK_INIT;
bool g_initialized = false;
uint32_t g_initResult = emmy_attach::kNoLua;
CoreApi g_core;
std::unordered_map<std::string, void*> g_luaSymbols; // 选中的 Lua 模块的导出

// ---- PE 导出表（只用裸指针，__try 里不能有要析构的 C++ 对象） ----
struct RawExport {
	const char* name;
	DWORD rva;
};

size_t ReadLuaExports(const uint8_t* base, RawExport* out, size_t capacity) {
	size_t count = 0;
	__try {
		const auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
		if (dos->e_magic != IMAGE_DOS_SIGNATURE) return 0;
		const auto* nt = reinterpret_cast<const IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
		if (nt->Signature != IMAGE_NT_SIGNATURE) return 0;
		const auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT];
		if (dir.VirtualAddress == 0 || dir.Size == 0) return 0;
		const auto* exp = reinterpret_cast<const IMAGE_EXPORT_DIRECTORY*>(base + dir.VirtualAddress);
		const auto* names = reinterpret_cast<const DWORD*>(base + exp->AddressOfNames);
		const auto* ordinals = reinterpret_cast<const WORD*>(base + exp->AddressOfNameOrdinals);
		const auto* functions = reinterpret_cast<const DWORD*>(base + exp->AddressOfFunctions);
		for (DWORD i = 0; i < exp->NumberOfNames && count < capacity; ++i) {
			const char* name = reinterpret_cast<const char*>(base + names[i]);
			if (name[0] != 'l' || name[1] != 'u' || name[2] != 'a') continue; // lua_* / luaL_* / luaJIT_*
			const DWORD ordinal = ordinals[i];
			if (ordinal >= exp->NumberOfFunctions) continue;
			const DWORD rva = functions[ordinal];
			if (rva >= dir.VirtualAddress && rva < dir.VirtualAddress + dir.Size) continue; // 转发导出
			out[count].name = name;
			out[count].rva = rva;
			++count;
		}
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return 0;
	}
	return count;
}

bool IsSystemModule(const char* path) {
	char windowsDir[MAX_PATH];
	const UINT n = GetWindowsDirectoryA(windowsDir, MAX_PATH);
	return n > 0 && _strnicmp(path, windowsDir, n) == 0;
}

// 找导出了 Lua API 的模块；同一进程有多个时取第一个导出最全的（一般只有一个）
HMODULE FindLuaModule(std::unordered_map<std::string, void*>& symbols) {
	HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
	if (snapshot == INVALID_HANDLE_VALUE) return nullptr;
	MODULEENTRY32 entry{};
	entry.dwSize = sizeof(entry);
	HMODULE best = nullptr;
	static RawExport exports[1024];
	for (BOOL more = Module32First(snapshot, &entry); more; more = Module32Next(snapshot, &entry)) {
		if (entry.hModule == g_attachInstance || IsSystemModule(entry.szExePath)) continue;
		if (_stricmp(entry.szModule, "emmy_debugger_core.dll") == 0 || _stricmp(entry.szModule, "EasyHook.dll") == 0) continue;
		const auto* base = reinterpret_cast<const uint8_t*>(entry.hModule);
		const size_t n = ReadLuaExports(base, exports, sizeof(exports) / sizeof(exports[0]));
		std::unordered_map<std::string, void*> found;
		for (size_t i = 0; i < n; ++i) found[exports[i].name] = const_cast<uint8_t*>(base) + exports[i].rva;
		if (!found.count("lua_gettop") || !(found.count("lua_pcall") || found.count("lua_pcallk"))) continue;
		if (found.size() > symbols.size()) {
			symbols.swap(found);
			best = entry.hModule;
		}
	}
	CloseHandle(snapshot);
	return best;
}

const char* DetectLuaVersion(const std::unordered_map<std::string, void*>& s) {
	if (s.count("luaJIT_setmode")) return nullptr;
	if (s.count("lua_newuserdatauv")) return "54";
	if (s.count("lua_rotate")) return "53";
	if (s.count("lua_callk")) return "52";
	return "51";
}

void* ResolveLua(const char* name, void*) {
	auto it = g_luaSymbols.find(name);
	return it == g_luaSymbols.end() ? nullptr : it->second;
}

bool LoadCore(const char* version) {
	wchar_t path[MAX_PATH];
	const DWORD n = GetModuleFileNameW(g_attachInstance, path, MAX_PATH);
	if (n == 0 || n >= MAX_PATH) return false;
	std::wstring dir(path, n);
	dir = dir.substr(0, dir.find_last_of(L"\\/") + 1);
	std::wstring corePath = dir + L"lua" + std::wstring(version, version + strlen(version)) + L"\\emmy_debugger_core.dll";
	HMODULE core = LoadLibraryExW(corePath.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
	if (!core) return false;
#define EMMY_LOAD(field, name) g_core.field = reinterpret_cast<decltype(g_core.field)>(GetProcAddress(core, #name)); if (!g_core.field) return false;
	EMMY_LOAD(setLuaApiResolver, Emmy_SetLuaApiResolver)
	EMMY_LOAD(lastError, Emmy_LastError)
	EMMY_LOAD(setProcessInfo, Emmy_SetProcessInfo)
	EMMY_LOAD(setToken, Emmy_SetToken)
	EMMY_LOAD(listenTcp, Emmy_ListenTcp)
	EMMY_LOAD(registerLuaVm, Emmy_RegisterLuaVm)
	EMMY_LOAD(unregisterLuaVm, Emmy_UnregisterLuaVm)
#undef EMMY_LOAD
	return true;
}

// ---- 挂钩的入口 ----
// 每个 lua_State 只上报一次：线程本地缓存最近一个，之外查一个集合。lua_close 时整代作废，
// 避免关掉的 state 地址被新 state 复用后漏报。
SRWLOCK g_vmLock = SRWLOCK_INIT;
std::unordered_set<void*> g_seen;
std::atomic<uint32_t> g_generation{1};
thread_local void* t_lastState = nullptr;
thread_local uint32_t t_lastGeneration = 0;

void OnLuaEntry(lua_State* L) {
	const uint32_t gen = g_generation.load(std::memory_order_acquire);
	if (L == t_lastState && gen == t_lastGeneration) return;
	t_lastState = L;
	t_lastGeneration = gen;
	AcquireSRWLockShared(&g_vmLock);
	const bool seen = g_seen.count(L) != 0;
	ReleaseSRWLockShared(&g_vmLock);
	if (seen) return;
	AcquireSRWLockExclusive(&g_vmLock);
	g_seen.insert(L);
	ReleaseSRWLockExclusive(&g_vmLock);
	// 协程也会走到这里，核心按 global_State 合并成同一个 VM
	g_core.registerLuaVm(L, "windows-attach");
}

// lua_KContext 在 5.2 是 int、5.3/5.4 是 intptr_t：按 intptr_t 转发，x86/x64 的调用约定下对 int 也成立
typedef int (*PcallFn)(lua_State*, int, int, int);
typedef int (*PcallkFn)(lua_State*, int, int, int, intptr_t, void*);
typedef int (*Resume54Fn)(lua_State*, lua_State*, int, int*);
typedef int (*Resume52Fn)(lua_State*, lua_State*, int);
typedef int (*Resume51Fn)(lua_State*, int);
typedef void (*CloseFn)(lua_State*);

template <typename Fn>
Fn Original() {
	void* p = nullptr;
	LhBarrierGetCallback(&p);
	return reinterpret_cast<Fn>(p);
}

int PcallHook(lua_State* L, int a, int r, int e) {
	auto orig = Original<PcallFn>();
	OnLuaEntry(L);
	return orig(L, a, r, e);
}
int PcallkHook(lua_State* L, int a, int r, int e, intptr_t ctx, void* k) {
	auto orig = Original<PcallkFn>();
	OnLuaEntry(L);
	return orig(L, a, r, e, ctx, k);
}
int Resume54Hook(lua_State* L, lua_State* from, int n, int* nres) {
	auto orig = Original<Resume54Fn>();
	OnLuaEntry(L);
	return orig(L, from, n, nres);
}
int Resume52Hook(lua_State* L, lua_State* from, int n) {
	auto orig = Original<Resume52Fn>();
	OnLuaEntry(L);
	return orig(L, from, n);
}
int Resume51Hook(lua_State* L, int n) {
	auto orig = Original<Resume51Fn>();
	OnLuaEntry(L);
	return orig(L, n);
}
void CloseHook(lua_State* L) {
	auto orig = Original<CloseFn>();
	g_core.unregisterLuaVm(L);
	g_generation.fetch_add(1, std::memory_order_acq_rel);
	AcquireSRWLockExclusive(&g_vmLock);
	g_seen.clear();
	ReleaseSRWLockExclusive(&g_vmLock);
	orig(L);
}

bool InstallHook(const char* name, void* hook) {
	auto it = g_luaSymbols.find(name);
	if (it == g_luaSymbols.end()) return false;
	auto* handle = new HOOK_TRACE_INFO();
	// callback 参数传原函数地址，入口里用 LhBarrierGetCallback 取回再调用
	if (LhInstallHook(it->second, hook, it->second, handle) != 0) {
		delete handle;
		return false;
	}
	ULONG acl[1] = {0};
	LhSetExclusiveACL(acl, 0, handle); // 空的排除列表 = 所有线程都走钩子
	return true;
}

uint32_t Initialize() {
	if (!FindLuaModule(g_luaSymbols)) return emmy_attach::kNoLua;
	const char* version = DetectLuaVersion(g_luaSymbols);
	if (!version) return emmy_attach::kLuaJit;
	if (!LoadCore(version)) return emmy_attach::kCoreLoadFailed;
	g_core.setLuaApiResolver(&ResolveLua, nullptr);
	if (g_core.lastError()[0] != '\0') return emmy_attach::kLuaApiMissing;

	char err[256] = {0};
	const int port = emmy_attach::PortForPid(static_cast<int>(GetCurrentProcessId()));
	// 只听回环地址：同机 IDE 连，局域网不可达
	if (g_core.listenTcp("127.0.0.1", port, err, sizeof(err)) != 0) return emmy_attach::kListenFailed;

	int hooked = 0;
	hooked += InstallHook("lua_pcall", reinterpret_cast<void*>(&PcallHook)) ? 1 : 0;
	hooked += InstallHook("lua_pcallk", reinterpret_cast<void*>(&PcallkHook)) ? 1 : 0;
	if (!strcmp(version, "54")) hooked += InstallHook("lua_resume", reinterpret_cast<void*>(&Resume54Hook)) ? 1 : 0;
	else if (!strcmp(version, "51")) hooked += InstallHook("lua_resume", reinterpret_cast<void*>(&Resume51Hook)) ? 1 : 0;
	else hooked += InstallHook("lua_resume", reinterpret_cast<void*>(&Resume52Hook)) ? 1 : 0;
	if (hooked == 0) return emmy_attach::kHookFailed;
	InstallHook("lua_close", reinterpret_cast<void*>(&CloseHook));
	return emmy_attach::kOk;
}

} // namespace

// emmy_tool 的远程线程入口。param 为 RemoteThreadParam*（可能为空）。返回 emmy_attach::Result。
DWORD WINAPI AttachInit(LPVOID param) {
	AcquireSRWLockExclusive(&g_initLock);
	if (!g_initialized) {
		g_initResult = Initialize();
		// 还没找到 Lua 时允许下次附加再找（目标可能稍后才加载 Lua）；其余失败不重试，避免挂两遍钩子
		g_initialized = g_initResult != emmy_attach::kNoLua;
		if (!g_initialized) g_luaSymbols.clear();
	}
	const uint32_t result = g_initResult;
	if (result == emmy_attach::kOk) {
		const auto* p = static_cast<const RemoteThreadParam*>(param);
		// 每次附加（含重连）都换成 IDE 这次给的 token；空串表示不校验
		g_core.setToken(p ? p->authToken : "");
	}
	ReleaseSRWLockExclusive(&g_initLock);
	return result;
}
