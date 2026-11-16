#include "core.h"

#include "emmy/emmy_core.h"
#include "hook_mux.h"
#include "lua_internal.h"
#include "session.h"
#include "transport.h"
#include "vm.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

namespace emmy {

// ---------------------------------------------------------------- Options

bool Options::CaseSensitive() const {
	int v = caseSensitive.load();
	if (v >= 0) return v == 1;
#if defined(_WIN32) || defined(__ANDROID__) || defined(__APPLE__)
	return false;
#else
	return true;
#endif
}

void Options::Reset() {
	stopOnEntry = false;
	captureOutput = false;
	allowLuaEval = true;
	valuePathInvokeIndex = false;
	evalTimeoutMs = 1000;
	caseSensitive = -1;
	pauseLatencyMs = 20;
}

// ---------------------------------------------------------------- Core

Core& Core::Get() {
	// 故意泄漏：进程退出时静态析构顺序不可控，钩子可能还在跑
	static Core* core = new Core();
	return *core;
}

uint64_t Core::RegisterVm(lua_State* L, const std::string& discovery) {
	if (!L) return 0;
	const void* g = lua::GlobalStateOf(L);
	std::shared_ptr<Vm> vm;
	{
		std::lock_guard<std::mutex> lock(vmMutex_);
		// 同一个 VM（同一 global_State）的不同协程重复注册：合并
		for (auto& kv : vms_) {
			if (kv.second->globalState() == g) return kv.first;
		}
		uint64_t id = nextVmId_++;
		vm = std::make_shared<Vm>(id, L, discovery);
		vms_[id] = vm;
	}
	vm->Install();
	if (connected()) Emit("vmAdded", vm->Info());
	return vm->id();
}

void Core::UnregisterVm(lua_State* L) {
	if (!L) return;
	const void* g = lua::GlobalStateOf(L);
	std::shared_ptr<Vm> vm;
	{
		std::lock_guard<std::mutex> lock(vmMutex_);
		for (auto it = vms_.begin(); it != vms_.end(); ++it) {
			if (it->second->globalState() == g) {
				vm = it->second;
				vms_.erase(it);
				break;
			}
		}
	}
	if (!vm) return;
	vm->Uninstall();
	if (connected()) Emit("vmClosed", {{"vmId", vm->id()}, {"reason", "closed"}});
}

std::shared_ptr<Vm> Core::FindVm(uint64_t vmId) {
	std::lock_guard<std::mutex> lock(vmMutex_);
	auto it = vms_.find(vmId);
	return it == vms_.end() ? nullptr : it->second;
}

std::shared_ptr<Vm> Core::FindVmByState(lua_State* L) {
	const void* g = lua::GlobalStateOf(L);
	std::lock_guard<std::mutex> lock(vmMutex_);
	for (auto& kv : vms_) {
		if (kv.second->globalState() == g) return kv.second;
	}
	return nullptr;
}

std::vector<std::shared_ptr<Vm>> Core::Vms() {
	std::lock_guard<std::mutex> lock(vmMutex_);
	std::vector<std::shared_ptr<Vm>> out;
	for (auto& kv : vms_) out.push_back(kv.second);
	return out;
}

void Core::RegisterSourceHash(const std::string& chunkName, const std::string& sha256) {
	std::lock_guard<std::mutex> lock(vmMutex_);
	sourceHashes_[chunkName] = sha256;
}

std::string Core::SourceHashOf(const std::string& chunkName) {
	std::lock_guard<std::mutex> lock(vmMutex_);
	auto it = sourceHashes_.find(chunkName);
	return it == sourceHashes_.end() ? std::string() : it->second;
}

bool Core::Activate(const std::shared_ptr<Session>& session) {
	{
		std::lock_guard<std::mutex> lock(sessionMutex_);
		if (active_ && active_.get() != session.get()) return false;
		active_ = session;
		options_.Reset();
		lastClientRevision_ = 0;
		configured_ = false;
		sessionGen_.fetch_add(1);
		connected_.store(true, std::memory_order_release);
	}
	ClearBreakpoints();
	{
		std::lock_guard<std::mutex> lock(helperMutex_);
		helpers_.reset();
	}
	NotifyVms(kFlagSession | kFlagRefresh);
	for (auto& vm : Vms()) vm->Kick();
	return true;
}

void Core::Deactivate(Session* session) {
	{
		std::lock_guard<std::mutex> lock(sessionMutex_);
		if (!active_ || active_.get() != session) return;
		active_.reset();
		connected_.store(false, std::memory_order_release);
		configured_ = false;
		sessionGen_.fetch_add(1);
	}
	configuredCv_.notify_all();
	ClearBreakpoints();
	// 断开：暂停中的 VM 立刻恢复；运行中的 VM 在下一次钩子事件里摘掉调试器的钩子
	NotifyVms(kFlagSession | kFlagDetach | kFlagRefresh);
}

std::shared_ptr<Session> Core::ActiveSession() {
	std::lock_guard<std::mutex> lock(sessionMutex_);
	return active_;
}

void Core::Emit(const char* event, json body) {
	auto s = ActiveSession();
	if (s && s->handshaken()) s->Emit(event, std::move(body));
}

void Core::Log(const std::string& category, const std::string& level, const std::string& text) {
	Emit("output", {{"category", category}, {"level", level}, {"text", text + "\n"}});
}

void Core::ConfigurationDone() {
#if defined(EMMY_CORE_LUA_DYNAMIC)
	// 宿主裁掉了部分可选 Lua 函数时，连上就告诉用户哪些功能降级了
	if (const char* w = emmy_dyn_warnings(); w && *w) Log("runtime", "warn", w);
#endif
	configured_.store(true, std::memory_order_release);
	{
		std::lock_guard<std::mutex> lock(sessionMutex_);
	}
	configuredCv_.notify_all();
	if (options_.stopOnEntry.load()) {
		for (auto& vm : Vms()) {
			vm->RequestPause("entry");
			vm->Kick();
		}
	}
}

bool Core::WaitConfigured(int timeoutMs) {
	std::unique_lock<std::mutex> lock(sessionMutex_);
	auto pred = [&] { return configured_.load(); };
	if (timeoutMs < 0) {
		configuredCv_.wait(lock, pred);
		return true;
	}
	return configuredCv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), pred);
}

bool Core::ApplyBreakpoints(BreakpointUpdate&& update, std::string& err) {
	{
		std::lock_guard<std::mutex> lock(bpMutex_);
		if (update.revision <= lastClientRevision_) {
			err = "revision " + std::to_string(update.revision) + " 不大于当前值 " + std::to_string(lastClientRevision_) + "，断点请求过期。";
			return false;
		}
		lastClientRevision_ = update.revision;
		if (update.replaceAll) bps_.clear();
		else for (const std::string& id : update.removes) bps_.erase(id);
		for (auto& bp : update.upserts) bps_[bp->id] = bp;
	}
	RebuildIndex();
	return true;
}

void Core::RebuildIndex() {
	bool cs = options_.CaseSensitive();
	{
		std::lock_guard<std::mutex> lock(bpMutex_);
		std::vector<BreakpointPtr> list;
		list.reserve(bps_.size());
		for (auto& kv : bps_) {
			// 大小写选项变了，规范化结果也要重算
			if (!index_ || index_->caseSensitive() != cs) {
				kv.second->normPath = NormalizePath(kv.second->path, cs);
				if (!kv.second->chunkName.empty()) kv.second->normChunk = NormalizePath(kv.second->chunkName, cs);
			}
			list.push_back(kv.second);
		}
		uint64_t rev = bpRevision_.load() + 1;
		index_ = std::make_shared<const BreakpointIndex>(rev, std::move(list), cs);
		bpRevision_.store(rev, std::memory_order_release);
	}
	NotifyVms(kFlagRefresh);
	// 新增断点后运行中的线程需要 CALL/RET 钩子：主线程立刻挂上，协程在下一次事件里扩散
	for (auto& vm : Vms()) vm->Kick();
}

void Core::ClearBreakpoints() {
	{
		std::lock_guard<std::mutex> lock(bpMutex_);
		bps_.clear();
	}
	RebuildIndex();
}

std::shared_ptr<const BreakpointIndex> Core::CurrentIndex() const {
	std::lock_guard<std::mutex> lock(bpMutex_);
	return index_;
}

void Core::SetHelpers(std::vector<HelperScript> scripts, std::string mainName) {
	std::lock_guard<std::mutex> lock(helperMutex_);
	helpers_ = std::make_shared<const std::pair<std::vector<HelperScript>, std::string>>(std::move(scripts), std::move(mainName));
}

std::shared_ptr<const std::pair<std::vector<HelperScript>, std::string>> Core::Helpers() const {
	std::lock_guard<std::mutex> lock(helperMutex_);
	return helpers_;
}

uint64_t Core::AllocObjectId(uint64_t vmId) {
	std::lock_guard<std::mutex> lock(objMutex_);
	uint64_t id = nextObjId_++;
	objOwner_[id] = vmId;
	return id;
}

void Core::ReleaseObjectIds(const std::vector<uint64_t>& ids) {
	std::lock_guard<std::mutex> lock(objMutex_);
	for (uint64_t id : ids) objOwner_.erase(id);
}

uint64_t Core::VmOfObject(uint64_t id) {
	std::lock_guard<std::mutex> lock(objMutex_);
	auto it = objOwner_.find(id);
	return it == objOwner_.end() ? 0 : it->second;
}

void Core::SetProcessInfo(const char* package, const char* buildId) {
	std::lock_guard<std::mutex> lock(infoMutex_);
	package_ = package ? package : "";
	buildId_ = buildId ? buildId : "";
}

json Core::ProcessInfo() {
	std::lock_guard<std::mutex> lock(infoMutex_);
	json j = {{"name", ""}, {"package", nullptr}, {"buildId", nullptr}};
	if (!package_.empty()) j["package"] = package_;
	if (!buildId_.empty()) j["buildId"] = buildId_;
	return j;
}

void Core::SetToken(const char* token) {
	std::lock_guard<std::mutex> lock(infoMutex_);
	token_ = token ? token : "";
}

std::string Core::token() {
	std::lock_guard<std::mutex> lock(infoMutex_);
	return token_;
}

void Core::AddTransport(std::shared_ptr<Transport> t) {
	std::lock_guard<std::mutex> lock(transportMutex_);
	transports_.push_back(std::move(t));
}

void Core::StopAll() {
	std::vector<std::shared_ptr<Transport>> ts;
	{
		std::lock_guard<std::mutex> lock(transportMutex_);
		ts.swap(transports_);
	}
	for (auto& t : ts) t->Stop();
	auto s = ActiveSession();
	if (s) s->Close();
}

void Core::NotifyVms(uint32_t flags) {
	for (auto& vm : Vms()) vm->SetFlags(flags);
}

namespace {

void HandleConnection(std::shared_ptr<Connection> conn) {
	auto session = std::make_shared<Session>(std::move(conn));
	session->Start();
	// Session 的生命周期由连接回调里的 weak_ptr 和 Core 的 active_ 共同维持；握手前先挂在这里
	static std::mutex pendingMutex;
	static std::vector<std::shared_ptr<Session>> pending;
	std::lock_guard<std::mutex> lock(pendingMutex);
	pending.erase(std::remove_if(pending.begin(), pending.end(), [](const std::shared_ptr<Session>& s) {
		return s->isClosed();
	}), pending.end());
	pending.push_back(session);
}

void CopyErr(const std::string& msg, char* err, size_t errLen) {
	if (err && errLen > 0) {
		size_t n = std::min(errLen - 1, msg.size());
		std::memcpy(err, msg.data(), n);
		err[n] = '\0';
	}
}

} // namespace

} // namespace emmy

// ---------------------------------------------------------------- C ABI

using emmy::Core;

extern "C" {

namespace {
std::mutex g_lastErrorMutex;
std::string g_lastError;

void SetLastError(std::string e) {
	std::lock_guard<std::mutex> lk(g_lastErrorMutex);
	g_lastError = std::move(e);
}

// dynamic 模式下 Lua 函数指针还没填好就不能碰任何 lua_*；宿主没调 Emmy_SetLuaApiResolver 时退回 dlsym
bool EnsureLuaApi() {
#if defined(EMMY_CORE_LUA_DYNAMIC)
	if (emmy_dyn_ready()) return true;
	char err[512];
	if (emmy_dyn_resolve(nullptr, nullptr, err, sizeof(err)) != 0) {
		SetLastError(std::string("Lua API 未就绪（先调 Emmy_SetLuaApiResolver）：") + err);
		return false;
	}
#endif
	return true;
}
} // namespace

void Emmy_SetLuaApiResolver(Emmy_LuaApiResolver resolve, void* ud) {
#if defined(EMMY_CORE_LUA_DYNAMIC)
	char err[512];
	if (emmy_dyn_resolve(resolve, ud, err, sizeof(err)) != 0) SetLastError(err);
#else
	// source 模式直接链接 Lua，不需要解析
	(void)resolve;
	(void)ud;
#endif
}

const char* Emmy_LastError(void) {
	// 返回的指针在下一次出错前有效；只给宿主写日志 / 状态文件用
	static thread_local std::string copy;
	std::lock_guard<std::mutex> lk(g_lastErrorMutex);
	copy = g_lastError;
	return copy.c_str();
}

void Emmy_SetProcessInfo(const char* packageName, const char* buildId) {
	Core::Get().SetProcessInfo(packageName, buildId);
}

void Emmy_SetToken(const char* token) {
	Core::Get().SetToken(token);
}

int Emmy_ListenTcp(const char* host, int port, char* err, size_t errLen) {
	std::string e;
	auto t = emmy::ListenTcp(host ? host : "127.0.0.1", port, &emmy::HandleConnection, e);
	if (!t) {
		emmy::CopyErr(e, err, errLen);
		return -1;
	}
	Core::Get().AddTransport(t);
	return 0;
}

int Emmy_ListenAbstract(const char* name, const char* token, char* err, size_t errLen) {
	// token 为空表示不校验；非空等同于先调 Emmy_SetToken
	if (token && *token) Core::Get().SetToken(token);
	std::string e;
	auto t = emmy::ListenAbstract(name ? name : "", &emmy::HandleConnection, e);
	if (!t) {
		emmy::CopyErr(e, err, errLen);
		return -1;
	}
	Core::Get().AddTransport(t);
	return 0;
}

int Emmy_ConnectTcp(const char* host, int port, char* err, size_t errLen) {
	std::string e;
	auto t = emmy::ConnectTcp(host ? host : "127.0.0.1", port, &emmy::HandleConnection, e);
	if (!t) {
		emmy::CopyErr(e, err, errLen);
		return -1;
	}
	Core::Get().AddTransport(t);
	return 0;
}

int Emmy_AttachConnectedFd(intptr_t fd) {
	emmy::HandleConnection(emmy::WrapFd(fd));
	return 0;
}

void Emmy_Stop(void) {
	Core::Get().StopAll();
}

int Emmy_WaitIDE(int timeoutMs) {
	return Core::Get().WaitConfigured(timeoutMs) ? 1 : 0;
}

int Emmy_WaitForClient(int timeoutMs) {
	auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs < 0 ? 0 : timeoutMs);
	while (!Core::Get().connected()) {
		if (timeoutMs >= 0 && std::chrono::steady_clock::now() >= deadline) return 1;
		std::this_thread::sleep_for(std::chrono::milliseconds(10));
	}
	return 0;
}

int Emmy_IsConnected(void) {
	return Core::Get().connected() ? 1 : 0;
}

uint64_t Emmy_RegisterLuaVm(lua_State* L, const char* discovery) {
	if (!L || !EnsureLuaApi()) return 0;
	return Core::Get().RegisterVm(L, discovery ? discovery : "");
}

void Emmy_UnregisterLuaVm(lua_State* L) {
	Core::Get().UnregisterVm(L);
}

void Emmy_SetVmName(lua_State* L, const char* name) {
	auto vm = Core::Get().FindVmByState(L);
	if (vm && name) {
		vm->SetName(name);
		if (Core::Get().connected()) Core::Get().Emit("vmStateChanged", {{"vmId", vm->id()}, {"state", vm->currentPauseId() ? "paused" : "running"},
			{"sourceEpoch", vm->sourceEpoch()}, {"name", name}, {"reason", "renamed"}});
	}
}

void Emmy_ResetVmContext(lua_State* L, const char* reason) {
	auto vm = Core::Get().FindVmByState(L);
	if (vm) vm->ResetContext(reason ? reason : "");
}

void Emmy_RegisterLuaSource(lua_State*, const char* chunkName, const char* sha256) {
	if (chunkName && sha256) Core::Get().RegisterSourceHash(chunkName, sha256);
}

void Emmy_BreakHere(lua_State* L) {
	auto vm = Core::Get().FindVmByState(L);
	if (vm) vm->BreakHere(L);
}

void Emmy_Log(int level, const char* text) {
	static const char* kLevels[] = {"debug", "info", "warn", "error"};
	if (level < 0) level = 0;
	if (level > 3) level = 3;
	Core::Get().Emit("output", {{"category", "host"}, {"level", kLevels[level]}, {"text", std::string(text ? text : "") + "\n"}});
}

uint64_t Emmy_HookMux_Register(lua_State* L, Emmy_HookCallback cb, int mask, int count, void* ud) {
	auto vm = Core::Get().FindVmByState(L);
	if (!vm || !cb) return 0;
	return vm->mux().Register(reinterpret_cast<emmy::MuxCallback>(cb), mask, count, ud);
}

void Emmy_HookMux_Unregister(lua_State* L, uint64_t handle) {
	auto vm = Core::Get().FindVmByState(L);
	if (vm) vm->mux().Unregister(handle);
}

} // extern "C"
