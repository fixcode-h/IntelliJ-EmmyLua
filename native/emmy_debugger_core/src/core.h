// 进程内唯一的核心状态：VM 表、当前 IDE 会话、断点索引、选项。
#pragma once

#include "breakpoints.h"
#include "nlohmann/json.hpp"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

struct lua_State;

namespace emmy {

class Vm;
class Session;
class Transport;
using json = nlohmann::json;

struct Options {
	std::atomic<bool> stopOnEntry{false};
	std::atomic<bool> captureOutput{false};
	std::atomic<bool> allowLuaEval{true};
	std::atomic<bool> valuePathInvokeIndex{false};
	std::atomic<int> evalTimeoutMs{1000};
	std::atomic<int> caseSensitive{-1}; // -1 按平台默认
	std::atomic<int> pauseLatencyMs{20};

	bool CaseSensitive() const;
	void Reset();
};

struct HelperScript {
	std::string name;
	std::string source;
};

class Core {
public:
	static Core& Get();

	Options& options() { return options_; }

	// ---- VM ----
	uint64_t RegisterVm(lua_State* L, const std::string& discovery);
	void UnregisterVm(lua_State* L);
	std::shared_ptr<Vm> FindVm(uint64_t vmId);
	std::shared_ptr<Vm> FindVmByState(lua_State* L);
	std::vector<std::shared_ptr<Vm>> Vms();
	void RegisterSourceHash(const std::string& chunkName, const std::string& sha256);
	std::string SourceHashOf(const std::string& chunkName);

	// ---- 会话 ----
	// hello 通过后调用；已有活动会话时返回 false（回 BUSY）
	bool Activate(const std::shared_ptr<Session>& session);
	void Deactivate(Session* session);
	bool connected() const { return connected_.load(std::memory_order_acquire); }
	uint64_t sessionGeneration() const { return sessionGen_.load(std::memory_order_acquire); }
	std::shared_ptr<Session> ActiveSession();
	void Emit(const char* event, json body);
	void Log(const std::string& category, const std::string& level, const std::string& text);

	void ConfigurationDone();
	bool configured() const { return configured_.load(std::memory_order_acquire); }
	bool WaitConfigured(int timeoutMs);

	// ---- 断点 ----
	// 在网络线程调用；构建新索引后原子发布
	struct BreakpointUpdate {
		uint64_t revision = 0;
		bool replaceAll = false;
		std::vector<std::shared_ptr<Breakpoint>> upserts;
		std::vector<std::string> removes;
	};
	bool ApplyBreakpoints(BreakpointUpdate&& update, std::string& err);
	std::shared_ptr<const BreakpointIndex> CurrentIndex() const;
	uint64_t breakpointRevision() const { return bpRevision_.load(std::memory_order_acquire); }
	void RebuildIndex();
	void ClearBreakpoints();

	// ---- helpers ----
	void SetHelpers(std::vector<HelperScript> scripts, std::string mainName);
	std::shared_ptr<const std::pair<std::vector<HelperScript>, std::string>> Helpers() const;

	// ---- 引用路由：frameId / ref → vmId ----
	uint64_t AllocObjectId(uint64_t vmId);
	void ReleaseObjectIds(const std::vector<uint64_t>& ids);
	uint64_t VmOfObject(uint64_t id);

	// ---- 进程信息 ----
	void SetProcessInfo(const char* package, const char* buildId);
	json ProcessInfo();
	void SetToken(const char* token);
	std::string token();

	// ---- 传输 ----
	void AddTransport(std::shared_ptr<Transport> t);
	void StopAll();

	// 通知所有 VM：断点/选项/会话变了，下次钩子事件里刷新
	void NotifyVms(uint32_t flags);

private:
	Core() = default;

	Options options_;

	std::mutex vmMutex_;
	std::map<uint64_t, std::shared_ptr<Vm>> vms_;
	uint64_t nextVmId_ = 1;
	std::unordered_map<std::string, std::string> sourceHashes_;

	std::mutex sessionMutex_;
	std::shared_ptr<Session> active_;
	std::atomic<bool> connected_{false};
	std::atomic<uint64_t> sessionGen_{0};
	std::atomic<bool> configured_{false};
	std::condition_variable configuredCv_;

	mutable std::mutex bpMutex_;
	std::map<std::string, std::shared_ptr<Breakpoint>> bps_;
	std::shared_ptr<const BreakpointIndex> index_;
	std::atomic<uint64_t> bpRevision_{0};
	uint64_t lastClientRevision_ = 0;

	mutable std::mutex helperMutex_;
	std::shared_ptr<const std::pair<std::vector<HelperScript>, std::string>> helpers_;

	std::mutex objMutex_;
	std::unordered_map<uint64_t, uint64_t> objOwner_;
	uint64_t nextObjId_ = 1;

	std::mutex infoMutex_;
	std::string package_;
	std::string buildId_;
	std::string token_;

	std::mutex transportMutex_;
	std::vector<std::shared_ptr<Transport>> transports_;
};

} // namespace emmy
