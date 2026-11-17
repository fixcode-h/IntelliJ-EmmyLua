// emmy_attach.dll：协议 v3 的 Windows 本机附加。
//
// AttachInit 由 emmy_tool 用远程线程调用（每次附加 / 重新附加都调一次）：
//   1. 第一次：在已加载模块里找导出 lua_* 的模块，按导出判断 Lua 版本，加载 lua5X\emmy_debugger_core.dll，
//      用这个模块的导出表给核心解析 Lua 函数，挂 lua_pcall / lua_pcallk / lua_resume / lua_close，监听端口；
//   2. 每次：更新 token（IDE 每个会话一个）。
// 挂钩的入口只负责在 Lua 线程上把 lua_State 报给核心（Emmy_RegisterLuaVm），其余都在核心里。
// 附加时还没有 Lua 模块（UE 编辑器里 Game 还没启动）：返回 kWaitingForLua，后台线程每秒找一次，找到后照第 1 步初始化，
// token 用最近一次附加给的。UnLua 等把 Lua 当 C++ 编译时导出名是修饰过的（?lua_gettop@@...），按 @@ 前的名字认。
// 等的同时挂 ntdll 的 LdrLoadDll（所有 LoadLibrary* 和延迟加载最后都走它；没有时退到 LoadLibraryExW / ExA）：
// UnLua 的 Lua.dll 是延迟加载的，第一次调 lua_* 时才加载，
// 紧接着就建 VM、跑模块顶层代码。只靠每秒找一次会晚上最多 1 秒，这些代码（如 GameInstance 的 ReceiveInit）就错过了；
// 挂上之后 Lua 模块一加载完、在同一线程上返回给调用方之前就完成初始化。
// 附加后第一次遇到新的 Lua VM 时，在 Lua 线程上最多等 kHoldNewVmMs 毫秒让 IDE 连上并下完断点（configurationDone），
// 再放它往下跑；IDE 已经就绪时不等。这样「先附加再开始 PIE」时，VM 一创建就执行的代码也能停住。
// 环境变量 EMMY_ATTACH_TRACE 指向一个文件时，把附加、等 Lua、初始化、VM 登记的时间点追加写进去，排查时序问题用。
#include <Windows.h>
#include <Psapi.h>
#include <TlHelp32.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
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

// ---- 跟踪日志（EMMY_ATTACH_TRACE=文件路径 时才写） ----
// 不用 C++ 流和堆：可能在 LdrLoadDll 钩子里、加载器刚放锁的时候调用
void Trace(const char* fmt, ...) {
	static const DWORD envLen = GetEnvironmentVariableA("EMMY_ATTACH_TRACE", nullptr, 0);
	if (envLen == 0) return;
	char path[MAX_PATH];
	if (GetEnvironmentVariableA("EMMY_ATTACH_TRACE", path, MAX_PATH) == 0) return;
	char line[512];
	int n = _snprintf_s(line, sizeof(line), _TRUNCATE, "%lu [%lu] ", GetTickCount(), GetCurrentThreadId());
	if (n < 0) n = 0;
	va_list ap;
	va_start(ap, fmt);
	int m = _vsnprintf_s(line + n, sizeof(line) - n - 2, _TRUNCATE, fmt, ap);
	va_end(ap);
	if (m < 0) m = static_cast<int>(strlen(line + n));
	n += m;
	line[n++] = '\r';
	line[n++] = '\n';
	HANDLE f = CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
	if (f == INVALID_HANDLE_VALUE) return;
	DWORD written = 0;
	WriteFile(f, line, static_cast<DWORD>(n), &written, nullptr);
	CloseHandle(f);
}

// ---- 核心（按版本动态加载的 emmy_debugger_core.dll） ----
struct CoreApi {
	decltype(&Emmy_SetLuaApiResolver) setLuaApiResolver = nullptr;
	decltype(&Emmy_LastError) lastError = nullptr;
	decltype(&Emmy_SetProcessInfo) setProcessInfo = nullptr;
	decltype(&Emmy_SetToken) setToken = nullptr;
	decltype(&Emmy_ListenTcp) listenTcp = nullptr;
	decltype(&Emmy_RegisterLuaVm) registerLuaVm = nullptr;
	decltype(&Emmy_UnregisterLuaVm) unregisterLuaVm = nullptr;
	decltype(&Emmy_PrepareHostPcall) prepareHostPcall = nullptr;
	decltype(&Emmy_FinishHostPcall) finishHostPcall = nullptr;
	decltype(&Emmy_SyncThreadHook) syncThreadHook = nullptr; // 可选：旧核心没有
	decltype(&Emmy_WaitIDE) waitIDE = nullptr;               // 可选：没有时不等 IDE
};

// IDE 从发起附加到下完断点：emmy_tool 返回后连端口（等 Lua 时每 200 毫秒试一次）+ 握手 + 断点，一般不到 1 秒
constexpr int kHoldNewVmMs = 5000;

SRWLOCK g_initLock = SRWLOCK_INIT;
bool g_initialized = false;
std::atomic<bool> g_initDone{false}; // = g_initialized，给不拿锁的 LoadLibrary 钩子看
std::atomic<bool> g_holdNewVm{false}; // 附加后还没放行过新 VM：下一个新 VM 先等 IDE 就绪
thread_local bool t_initializing = false; // 本线程正在初始化（加载核心时会再进 LoadLibrary 钩子）
uint32_t g_initResult = emmy_attach::kNoLua;
CoreApi g_core;
std::unordered_map<std::string, void*> g_luaSymbols; // 选中的 Lua 模块的导出

// ---- PE 导出表（只用裸指针，__try 里不能有要析构的 C++ 对象） ----
struct RawExport {
	const char* name; // 指向模块的导出名表；C++ 修饰名时已跳过开头的 '?'
	size_t length;    // 名字长度（修饰名只取到 "@@" 之前）
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
			size_t length = 0;
			if (name[0] == '?') {
				// C++ 修饰名：?lua_gettop@@YAHPEAUlua_State@@@Z → lua_gettop（Lua 当 C++ 编译时的全局函数）
				++name;
				if (name[0] != 'l' || name[1] != 'u' || name[2] != 'a') continue;
				while (name[length] != '\0' && !(name[length] == '@' && name[length + 1] == '@')) ++length;
				if (name[length] == '\0') continue;
			} else {
				if (name[0] != 'l' || name[1] != 'u' || name[2] != 'a') continue; // lua_* / luaL_* / luaJIT_*
				length = strlen(name);
			}
			const DWORD ordinal = ordinals[i];
			if (ordinal >= exp->NumberOfFunctions) continue;
			const DWORD rva = functions[ordinal];
			if (rva >= dir.VirtualAddress && rva < dir.VirtualAddress + dir.Size) continue; // 转发导出
			out[count].name = name;
			out[count].length = length;
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
		for (size_t i = 0; i < n; ++i) {
			// 同名的 C 导出和修饰名都有时取先出现的
			found.emplace(std::string(exports[i].name, exports[i].length), const_cast<uint8_t*>(base) + exports[i].rva);
		}
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
	EMMY_LOAD(prepareHostPcall, Emmy_PrepareHostPcall)
	EMMY_LOAD(finishHostPcall, Emmy_FinishHostPcall)
#undef EMMY_LOAD
	g_core.syncThreadHook = reinterpret_cast<decltype(g_core.syncThreadHook)>(GetProcAddress(core, "Emmy_SyncThreadHook"));
	g_core.waitIDE = reinterpret_cast<decltype(g_core.waitIDE)>(GetProcAddress(core, "Emmy_WaitIDE"));
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
	const uint64_t vmId = g_core.registerLuaVm(L, "windows-attach");
	Trace("new lua_State %p -> vm %llu", static_cast<void*>(L), static_cast<unsigned long long>(vmId));
	// 附加后的第一个新 VM：在它跑任何 Lua 之前等 IDE 下完断点（IDE 已就绪时立刻返回）
	if (g_holdNewVm.exchange(false) && g_core.waitIDE) {
		const int ready = g_core.waitIDE(kHoldNewVmMs);
		Trace("hold new vm: ide %s", ready ? "ready" : "not ready after timeout");
	}
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

// 出错即停：宿主自己的 lua_pcall 没给错误处理函数时，插一个会通知核心的（核心判断要不要插，Lua 自己的 pcall 不插）
int PcallHook(lua_State* L, int a, int r, int e) {
	auto orig = Original<PcallFn>();
	OnLuaEntry(L);
	const int h = g_core.prepareHostPcall(L, a, e);
	if (!h) return orig(L, a, r, e);
	const int rc = orig(L, a, r, h);
	g_core.finishHostPcall(L, h);
	return rc;
}
int PcallkHook(lua_State* L, int a, int r, int e, intptr_t ctx, void* k) {
	auto orig = Original<PcallkFn>();
	OnLuaEntry(L);
	// 带续延的调用可能 yield 出去，返回时栈布局不由我们控制，不插
	const int h = k ? 0 : g_core.prepareHostPcall(L, a, e);
	if (!h) return orig(L, a, r, e, ctx, k);
	const int rc = orig(L, a, r, h, ctx, k);
	g_core.finishHostPcall(L, h);
	return rc;
}
int Resume54Hook(lua_State* L, lua_State* from, int n, int* nres) {
	auto orig = Original<Resume54Fn>();
	OnLuaEntry(L);
	// 协程创建时调试器可能还没挂钩子（lua_newthread 继承的是创建者当时的钩子），恢复前补上
	if (g_core.syncThreadHook) g_core.syncThreadHook(L);
	return orig(L, from, n, nres);
}
int Resume52Hook(lua_State* L, lua_State* from, int n) {
	auto orig = Original<Resume52Fn>();
	OnLuaEntry(L);
	// 协程创建时调试器可能还没挂钩子（lua_newthread 继承的是创建者当时的钩子），恢复前补上
	if (g_core.syncThreadHook) g_core.syncThreadHook(L);
	return orig(L, from, n);
}
int Resume51Hook(lua_State* L, int n) {
	auto orig = Original<Resume51Fn>();
	OnLuaEntry(L);
	// 协程创建时调试器可能还没挂钩子（lua_newthread 继承的是创建者当时的钩子），恢复前补上
	if (g_core.syncThreadHook) g_core.syncThreadHook(L);
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
	Trace("lua %s: listening on %d, hooked %d entry points", version, port, hooked);
	if (hooked == 0) return emmy_attach::kHookFailed;
	InstallHook("lua_close", reinterpret_cast<void*>(&CloseHook));
	return emmy_attach::kOk;
}

// ---- 等 Lua 加载 ----
bool g_waiting = false;    // 后台线程在找 Lua（g_initLock 保护）
std::string g_pendingToken; // 等待期间最近一次附加给的 token（g_initLock 保护）

// 调用前持有 g_initLock
void TryInitializeLocked() {
	t_initializing = true;
	g_initResult = Initialize();
	t_initializing = false;
	Trace("initialize -> %u", g_initResult);
	// 还没找到 Lua 时之后再找；其余失败不重试，避免挂两遍钩子
	g_initialized = g_initResult != emmy_attach::kNoLua;
	g_initDone.store(g_initialized, std::memory_order_release);
	if (!g_initialized) g_luaSymbols.clear();
	// 每次附加（含重连）都换成 IDE 这次给的 token；空串表示不校验
	if (g_initResult == emmy_attach::kOk) g_core.setToken(g_pendingToken.c_str());
}

// 有模块加载完：还在等 Lua 就马上找一次（在加载它的线程上、返回调用方之前）
// 只看刚加载的这个模块有没有导出 Lua API（UE 启动时会加载几百个模块，不能每次都扫全部）。
// 被别的模块隐式依赖带进来的 Lua 模块看不到，由后台线程每秒一次兜底。
bool ExportsLua(HMODULE module) {
	RawExport exports[512]; // 不能用静态的：几个线程可能同时在加载模块
	const size_t n = ReadLuaExports(reinterpret_cast<const uint8_t*>(module), exports, sizeof(exports) / sizeof(exports[0]));
	for (size_t i = 0; i < n; ++i) {
		if (exports[i].length == 10 && strncmp(exports[i].name, "lua_gettop", 10) == 0) return true;
	}
	return false;
}

void OnModuleLoaded(HMODULE module) {
	if (!module || t_initializing || g_initDone.load(std::memory_order_acquire)) return;
	if (!ExportsLua(module)) return;
	Trace("lua module loaded at %p, initializing on loader thread", static_cast<void*>(module));
	AcquireSRWLockExclusive(&g_initLock);
	if (!g_initialized) TryInitializeLocked();
	if (g_initialized) g_waiting = false;
	ReleaseSRWLockExclusive(&g_initLock);
}

struct UnicodeStringView {
	USHORT Length;
	USHORT MaximumLength;
	PWSTR Buffer;
};
typedef LONG(NTAPI* LdrLoadDllFn)(PWSTR, PULONG, UnicodeStringView*, PVOID*);
typedef HMODULE(WINAPI* LoadLibraryExWFn)(LPCWSTR, HANDLE, DWORD);
typedef HMODULE(WINAPI* LoadLibraryExAFn)(LPCSTR, HANDLE, DWORD);

// LoadLibrary*、延迟加载（delayimp 的 LoadLibraryExA）、LdrLoadDll 直调最后都到这里；加载器锁在返回前已经放掉
LONG NTAPI LdrLoadDllHook(PWSTR searchPath, PULONG characteristics, UnicodeStringView* name, PVOID* base) {
	auto orig = Original<LdrLoadDllFn>();
	const LONG status = orig(searchPath, characteristics, name, base);
	if (status >= 0 && base) OnModuleLoaded(static_cast<HMODULE>(*base));
	return status;
}
HMODULE WINAPI LoadLibraryExWHook(LPCWSTR name, HANDLE file, DWORD flags) {
	auto orig = Original<LoadLibraryExWFn>();
	HMODULE module = orig(name, file, flags);
	// 只当数据 / 资源映射的不是可执行模块
	if (!(flags & (LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_DATAFILE_EXCLUSIVE | LOAD_LIBRARY_AS_IMAGE_RESOURCE))) OnModuleLoaded(module);
	return module;
}
HMODULE WINAPI LoadLibraryExAHook(LPCSTR name, HANDLE file, DWORD flags) {
	auto orig = Original<LoadLibraryExAFn>();
	HMODULE module = orig(name, file, flags);
	if (!(flags & (LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_DATAFILE_EXCLUSIVE | LOAD_LIBRARY_AS_IMAGE_RESOURCE))) OnModuleLoaded(module);
	return module;
}

bool HookExport(HMODULE module, const char* name, void* hook) {
	void* target = module ? reinterpret_cast<void*>(GetProcAddress(module, name)) : nullptr;
	if (!target) return false;
	auto* handle = new HOOK_TRACE_INFO();
	if (LhInstallHook(target, hook, target, handle) != 0) {
		delete handle;
		Trace("hook %s failed: %ls", name, RtlGetLastErrorString());
		return false;
	}
	ULONG acl[1] = {0};
	LhSetExclusiveACL(acl, 0, handle); // 空的排除列表 = 所有线程都走钩子
	return true;
}

// 优先挂 ntdll!LdrLoadDll：kernelbase 的 LoadLibraryExA/W 在不同 Windows 版本上互相转调的方式不同，
// 延迟加载辅助函数也可能绕开它们；挂不上时退到 kernelbase（没有时 kernel32）的 LoadLibraryExW / ExA。挂过就不再挂。
bool g_loadHooksInstalled = false; // g_initLock 保护
void InstallLoadLibraryHooksLocked() {
	if (g_loadHooksInstalled) return;
	g_loadHooksInstalled = true;
	if (HookExport(GetModuleHandleW(L"ntdll.dll"), "LdrLoadDll", reinterpret_cast<void*>(&LdrLoadDllHook))) {
		Trace("waiting for lua: hooked LdrLoadDll");
		return;
	}
	HMODULE kb = GetModuleHandleW(L"kernelbase.dll");
	if (!kb) kb = GetModuleHandleW(L"kernel32.dll");
	const bool w = HookExport(kb, "LoadLibraryExW", reinterpret_cast<void*>(&LoadLibraryExWHook));
	const bool a = HookExport(kb, "LoadLibraryExA", reinterpret_cast<void*>(&LoadLibraryExAHook));
	Trace("waiting for lua: hooked LoadLibraryExW=%d LoadLibraryExA=%d", w, a);
}

DWORD WINAPI WaitForLuaThread(LPVOID) {
	for (;;) {
		Sleep(1000);
		AcquireSRWLockExclusive(&g_initLock);
		if (!g_initialized) TryInitializeLocked();
		const bool done = g_initialized;
		if (done) g_waiting = false;
		ReleaseSRWLockExclusive(&g_initLock);
		if (done) return 0;
	}
}

} // namespace

// emmy_tool 的远程线程入口。param 为 RemoteThreadParam*（可能为空）。返回 emmy_attach::Result。
DWORD WINAPI AttachInit(LPVOID param) {
	const auto* p = static_cast<const RemoteThreadParam*>(param);
	AcquireSRWLockExclusive(&g_initLock);
	g_pendingToken = p ? p->authToken : "";
	// 每次附加都是一个 IDE 要来：之后第一个新 VM 等它下完断点
	g_holdNewVm.store(true, std::memory_order_release);
	if (!g_initialized) {
		TryInitializeLocked();
	} else if (g_initResult == emmy_attach::kOk) {
		g_core.setToken(g_pendingToken.c_str());
	}
	uint32_t result = g_initResult;
	Trace("attach: initialized=%d result=%u", g_initialized ? 1 : 0, g_initResult);
	if (!g_initialized) {
		// 还没有 Lua：留在进程里等。Lua 模块一加载就初始化（LoadLibrary 钩子），后台线程每秒再找一次兜底。
		// 线程只开一个，重复附加只更新 token
		InstallLoadLibraryHooksLocked();
		if (!g_waiting) {
			HANDLE thread = CreateThread(nullptr, 0, &WaitForLuaThread, nullptr, 0, nullptr);
			if (thread) {
				CloseHandle(thread);
				g_waiting = true;
			}
		}
		if (g_waiting) result = emmy_attach::kWaitingForLua;
	}
	ReleaseSRWLockExclusive(&g_initLock);
	return result;
}
