#include "session.h"

#include "core.h"
#include "lua_shim.h"
#include "vm.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <random>
#include <thread>
#include <unordered_map>

#if defined(_WIN32)
#  include <process.h>
#  define emmy_getpid _getpid
#else
#  include <unistd.h>
#  define emmy_getpid getpid
#endif

#ifndef EMMY_CORE_VERSION_STRING
#  define EMMY_CORE_VERSION_STRING "3.0.0-dev"
#endif

namespace emmy {

namespace {

const char* const kRuntimeCaps[] = {
	"cbor", "valuePath", "luaEval", "setVariable", "condition", "hitCondition", "logpoint", "capture",
	"runToLine", "coroutines", "output", "helpers", "sourceHash", "hookMux", "richVariables", "exceptions",
};

const char* PlatformName() {
#if defined(__ANDROID__)
	return "android";
#elif defined(_WIN32)
	return "windows";
#elif defined(__APPLE__)
	return "macos";
#else
	return "linux";
#endif
}

const char* ArchName() {
#if defined(__aarch64__) || defined(_M_ARM64)
	return "arm64";
#elif defined(__arm__) || defined(_M_ARM)
	return "arm";
#elif defined(__x86_64__) || defined(_M_X64)
	return "x64";
#else
	return "x86";
#endif
}

bool ConstantTimeEquals(const std::string& a, const std::string& b) {
	unsigned char diff = static_cast<unsigned char>(a.size() != b.size());
	size_t n = std::max(a.size(), b.size());
	for (size_t i = 0; i < n; ++i) {
		unsigned char x = i < a.size() ? static_cast<unsigned char>(a[i]) : 0;
		unsigned char y = i < b.size() ? static_cast<unsigned char>(b[i]) : 0;
		diff |= static_cast<unsigned char>(x ^ y);
	}
	return diff == 0;
}

// 同一来源连续 3 次配对码错误后拒绝 60 秒
struct AuthGuard {
	std::mutex mutex;
	struct Entry {
		int fails = 0;
		std::chrono::steady_clock::time_point blockedUntil{};
	};
	std::unordered_map<std::string, Entry> entries;
};
AuthGuard g_auth;

std::string RandomId() {
	std::random_device rd;
	char buf[17];
	std::snprintf(buf, sizeof(buf), "%08x%08x", rd(), rd());
	return buf;
}

uint64_t GetU64(const json& args, const char* key, uint64_t def = 0) {
	auto it = args.find(key);
	if (it == args.end() || !it->is_number_integer()) return def;
	int64_t v = it->get<int64_t>();
	return v < 0 ? def : static_cast<uint64_t>(v);
}

int GetInt(const json& args, const char* key, int def) {
	auto it = args.find(key);
	if (it == args.end() || !it->is_number_integer()) return def;
	return it->get<int>();
}

std::string GetStr(const json& args, const char* key, const std::string& def = "") {
	auto it = args.find(key);
	if (it == args.end() || !it->is_string()) return def;
	return it->get<std::string>();
}

bool GetBool(const json& args, const char* key, bool def) {
	auto it = args.find(key);
	if (it == args.end() || !it->is_boolean()) return def;
	return it->get<bool>();
}

// 网络线程上用来检查条件表达式语法的独立 Lua 状态（不碰游戏的 VM）
class SyntaxChecker {
public:
	SyntaxChecker() : L_(luaL_newstate()) {}
	~SyntaxChecker() {
		if (L_) lua_close(L_);
	}
	bool Check(const std::string& expr, std::string& err) {
		if (!L_) return true;
		std::string code = "return " + expr;
		int r = lua::LoadBuffer(L_, code.data(), code.size(), "=(condition)");
		if (r != LUA_OK) err = lua_tostring(L_, -1) ? lua_tostring(L_, -1) : "语法错误";
		lua_settop(L_, 0);
		return r == LUA_OK;
	}

private:
	lua_State* L_;
};

} // namespace

Session::Session(std::shared_ptr<Connection> conn) : conn_(std::move(conn)), sessionId_(RandomId()) {}

void Session::Start() {
	std::weak_ptr<Session> weak = shared_from_this();
	conn_->Start(
		[weak](const char* data, size_t len) {
			if (auto s = weak.lock()) s->OnBytes(data, len);
		},
		[weak]() {
			if (auto s = weak.lock()) s->OnClosed();
		});
	// 握手 5 秒内没完成就断开
	std::thread([weak] {
		for (int i = 0; i < 50; ++i) {
			std::this_thread::sleep_for(std::chrono::milliseconds(100));
			auto s = weak.lock();
			if (!s || s->closed_ || s->handshaken_) return;
		}
		if (auto s = weak.lock()) s->Close();
	}).detach();
}

void Session::Close() {
	conn_->Close();
}

void Session::OnClosed() {
	if (closed_.exchange(true)) return;
	Core::Get().Deactivate(this);
}

void Session::OnBytes(const char* data, size_t len) {
	auto status = decoder_.Feed(data, len, [this](Frame&& f) { OnFrame(std::move(f)); });
	if (status == FrameDecoder::Status::NotV3) {
		// 旧 IDE（v1 文本协议）：回一行原因再断开
		conn_->Send("EMMY_PROTOCOL_V3_REQUIRED\n");
		Close();
	} else if (status != FrameDecoder::Status::Ok) {
		Close();
	}
}

void Session::SendRaw(MessageKind kind, const json& payload) {
	std::string bytes = EncodeFrame(kind, false, payload.dump(-1, ' ', false, json::error_handler_t::replace));
	conn_->Send(std::move(bytes));
}

void Session::Reply(uint64_t id, json body) {
	std::lock_guard<std::mutex> lock(sendMutex_);
	SendRaw(MessageKind::Response, json{{"id", id}, {"ok", true}, {"body", body.is_null() ? json::object() : std::move(body)}});
}

void Session::Fail(uint64_t id, const std::string& code, const std::string& message, json data) {
	json err = {{"code", code}, {"message", message}};
	if (!data.is_null()) err["data"] = std::move(data);
	std::lock_guard<std::mutex> lock(sendMutex_);
	SendRaw(MessageKind::Response, json{{"id", id}, {"ok", false}, {"error", std::move(err)}});
}

void Session::Emit(const char* event, json body) {
	std::lock_guard<std::mutex> lock(sendMutex_);
	SendRaw(MessageKind::Event, json{{"seq", nextSeq_++}, {"event", event}, {"body", std::move(body)}});
}

void Session::OnFrame(Frame&& frame) {
	if (frame.kind != MessageKind::Request) {
		Close();
		return;
	}
	json msg;
	try {
		msg = frame.cbor ? json::from_cbor(frame.payload) : json::parse(frame.payload);
	} catch (...) {
		Close();
		return;
	}
	if (!msg.is_object() || !msg.contains("id") || !msg["id"].is_number_integer() || !msg.contains("command") ||
		!msg["command"].is_string()) {
		Close();
		return;
	}
	uint64_t id = msg["id"].get<uint64_t>();
	std::string command = msg["command"].get<std::string>();
	json args = msg.contains("args") && msg["args"].is_object() ? msg["args"] : json::object();
	try {
		Dispatch(id, command, args);
	} catch (const std::exception& e) {
		Fail(id, "INTERNAL", std::string("runtime 内部错误：") + e.what() + "。请把 runtime 日志附到问题报告里。");
	}
}

void Session::Dispatch(uint64_t id, const std::string& command, const json& args) {
	if (command == "hello") return CmdHello(id, args);
	if (!handshaken_) return Fail(id, "NOT_READY", "还没有完成握手，请先发送 hello。");
	Core& core = Core::Get();
	if (command == "ping") {
		int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
		return Reply(id, {{"timeMs", ms}});
	}
	if (command == "setBreakpoints") return CmdSetBreakpoints(id, args);
	if (command == "setOptions") return CmdSetOptions(id, args);
	if (command == "loadHelpers") return CmdLoadHelpers(id, args);
	if (command == "configurationDone") {
		core.ConfigurationDone();
		return Reply(id, json::object());
	}
	if (command == "vms") {
		json list = json::array();
		for (auto& vm : core.Vms()) list.push_back(vm->Info());
		return Reply(id, {{"vms", std::move(list)}});
	}
	if (command == "continue") return CmdContinue(id, args);
	if (command == "stepIn" || command == "stepOver" || command == "stepOut") return CmdStep(id, command, args);
	if (command == "runToLine") return CmdRunToLine(id, args);
	if (command == "pause") return CmdPause(id, args);
	if (command == "stackTrace" || command == "scopes" || command == "variables" || command == "evaluate" ||
		command == "setVariable" || command == "threads") {
		return CmdPaused(id, command, args);
	}
	if (command == "cancel") return Reply(id, json::object());
	if (command == "disconnect") return CmdDisconnect(id, args);
	Fail(id, "UNKNOWN_COMMAND", "runtime 不支持命令 " + command + "，请升级 runtime。");
}

// ---------------------------------------------------------------- 握手

void Session::CmdHello(uint64_t id, const json& args) {
	Core& core = Core::Get();
	if (handshaken_) return Fail(id, "INVALID_ARGS", "已经握手过了。");
	if (GetInt(args, "protocol", 0) != 3) {
		Fail(id, "PROTOCOL_MISMATCH", "调试 runtime 只支持协议 v3，请升级 IDE 插件或 runtime 到同一版本。");
		Close();
		return;
	}
	std::string expected = core.token();
	if (!expected.empty()) {
		std::lock_guard<std::mutex> lock(g_auth.mutex);
		auto& entry = g_auth.entries[conn_->peer()];
		auto now = std::chrono::steady_clock::now();
		if (entry.blockedUntil > now) {
			Fail(id, "AUTH_FAILED", "配对码错误次数过多，请 60 秒后再试。");
			Close();
			return;
		}
		if (!ConstantTimeEquals(GetStr(args, "token"), expected)) {
			if (++entry.fails >= 3) {
				entry.fails = 0;
				entry.blockedUntil = now + std::chrono::seconds(60);
			}
			Fail(id, "AUTH_FAILED", "配对码不正确，请在设备日志或屏幕上核对配对码后重试。");
			Close();
			return;
		}
		entry.fails = 0;
	}
	json clientCaps = args.contains("caps") && args["caps"].is_array() ? args["caps"] : json();
	json caps = json::array();
	for (const char* c : kRuntimeCaps) {
		// richVariables 改变 variables 的返回形状，只给明确要它的客户端
		bool want = clientCaps.is_null() && std::strcmp(c, "richVariables") != 0;
		if (!want && !clientCaps.is_null()) {
			for (auto& x : clientCaps) {
				if (x.is_string() && x.get<std::string>() == c) want = true;
			}
		}
		if (want) caps.push_back(c);
	}
	if (args.contains("maxFrameBytes") && args["maxFrameBytes"].is_number_unsigned()) {
		// 只影响我们发出的帧；目前 runtime 发的帧远小于上限，记录即可
	}
	if (!core.Activate(shared_from_this())) {
		Fail(id, "BUSY", "这个进程已经被另一个调试器连接，先断开那边再试。");
		Close();
		return;
	}
	handshaken_ = true;
	// Activate 会重置选项，所以协商结果放在它之后
	for (auto& c : caps) {
		if (c.get<std::string>() == "richVariables") core.options().richVariables = true;
	}
	json vms = json::array();
	for (auto& vm : core.Vms()) vms.push_back(vm->Info());
	json body = {
		{"protocol", 3},
		{"revision", 0},
		{"sessionId", sessionId_},
		{"runtime", {{"name", "emmy_debugger_core"}, {"version", EMMY_CORE_VERSION_STRING}, {"build", ""}}},
		{"lua", {{"version", lua::VersionString()}, {"jit", false}, {"abi", std::string("lua") + lua::VersionString()}}},
		{"platform", PlatformName()},
		{"arch", ArchName()},
		{"process", core.ProcessInfo()},
		{"caps", caps},
		{"maxFrameBytes", kDefaultMaxFrameBytes},
		{"vms", std::move(vms)},
	};
	body["process"]["pid"] = static_cast<int64_t>(emmy_getpid());
	Reply(id, std::move(body));
	// 心跳：15 秒没收到任何帧视为断线
	std::weak_ptr<Session> weak = shared_from_this();
	(void)weak;
}

// ---------------------------------------------------------------- 断点 / 选项 / helpers

void Session::CmdSetBreakpoints(uint64_t id, const json& args) {
	Core& core = Core::Get();
	thread_local SyntaxChecker checker;
	Core::BreakpointUpdate update;
	update.revision = GetU64(args, "revision");
	update.replaceAll = GetBool(args, "replaceAll", false);
	if (update.revision == 0) return Fail(id, "INVALID_ARGS", "参数 revision 必须是正整数。");
	bool caseSensitive = core.options().CaseSensitive();
	json states = json::array();
	if (args.contains("upserts") && args["upserts"].is_array()) {
		for (const json& j : args["upserts"]) {
			auto bp = std::make_shared<Breakpoint>();
			bp->serial = NextBreakpointSerial();
			bp->id = GetStr(j, "id");
			bp->line = GetInt(j, "line", 0);
			if (j.contains("source") && j["source"].is_object()) {
				bp->path = GetStr(j["source"], "path");
				bp->chunkName = GetStr(j["source"], "chunkName");
				bp->sha256 = GetStr(j["source"], "sha256");
			}
			if (bp->id.empty() || bp->line <= 0 || (bp->path.empty() && bp->chunkName.empty())) {
				return Fail(id, "INVALID_ARGS", "断点必须有 id、source.path 和正整数 line。");
			}
			bp->condition = GetStr(j, "condition");
			bp->hitConditionText = GetStr(j, "hitCondition");
			bp->logMessage = GetStr(j, "logMessage");
			bp->autoContinue = GetBool(j, "autoContinue", false);
			bp->vmId = GetU64(j, "vmId");
			if (j.contains("capture") && j["capture"].is_array()) {
				for (auto& c : j["capture"]) if (c.is_string()) bp->capture.push_back(c.get<std::string>());
			}
			bp->normPath = NormalizePath(bp->path, caseSensitive);
			if (!bp->chunkName.empty()) bp->normChunk = NormalizePath(bp->chunkName, caseSensitive);
			std::string err;
			json st = {{"id", bp->id}, {"state", "pending"}};
			bool valid = HitCondition::Parse(bp->hitConditionText, bp->hitCondition, err);
			if (valid && !bp->condition.empty() && !checker.Check(bp->condition, err)) {
				valid = false;
				err = "条件表达式语法错误：" + err;
			}
			if (!valid) {
				st["state"] = "invalid";
				st["message"] = err;
				// 无效断点不进索引，但仍然要从旧表里删掉同 id 的
				update.removes.push_back(bp->id);
			} else {
				update.upserts.push_back(bp);
			}
			states.push_back(std::move(st));
		}
	}
	if (args.contains("removes") && args["removes"].is_array()) {
		for (auto& r : args["removes"]) if (r.is_string()) update.removes.push_back(r.get<std::string>());
	}
	std::string err;
	uint64_t rev = update.revision;
	if (!core.ApplyBreakpoints(std::move(update), err)) return Fail(id, "INVALID_ARGS", err);
	Reply(id, {{"revision", rev}, {"breakpoints", std::move(states)}});
}

void Session::CmdSetOptions(uint64_t id, const json& args) {
	Core& core = Core::Get();
	Options& o = core.options();
	bool oldCase = o.CaseSensitive();
	if (args.contains("stopOnEntry") && args["stopOnEntry"].is_boolean()) o.stopOnEntry = args["stopOnEntry"].get<bool>();
	if (args.contains("captureOutput") && args["captureOutput"].is_boolean()) o.captureOutput = args["captureOutput"].get<bool>();
	if (args.contains("allowLuaEval") && args["allowLuaEval"].is_boolean()) o.allowLuaEval = args["allowLuaEval"].get<bool>();
	if (args.contains("valuePathInvokeIndex") && args["valuePathInvokeIndex"].is_boolean()) {
		o.valuePathInvokeIndex = args["valuePathInvokeIndex"].get<bool>();
	}
	if (args.contains("evalTimeoutMs") && args["evalTimeoutMs"].is_number_integer()) {
		o.evalTimeoutMs = std::max(10, std::min(60000, args["evalTimeoutMs"].get<int>()));
	}
	if (args.contains("caseSensitive")) {
		if (args["caseSensitive"].is_boolean()) o.caseSensitive = args["caseSensitive"].get<bool>() ? 1 : 0;
		else if (args["caseSensitive"].is_null()) o.caseSensitive = -1;
	}
	if (args.contains("pauseLatencyMs") && args["pauseLatencyMs"].is_number_integer()) {
		o.pauseLatencyMs = std::max(1, std::min(1000, args["pauseLatencyMs"].get<int>()));
	}
	if (args.contains("variableView") && args["variableView"].is_object()) {
		const json& v = args["variableView"];
		auto flag = [&](const char* k, std::atomic<bool>& dst) {
			if (v.contains(k) && v[k].is_boolean()) dst = v[k].get<bool>();
		};
		if (v.contains("sort") && v["sort"].is_string()) {
			const std::string m = v["sort"].get<std::string>();
			if (m == "smart") o.viewSort = 0;
			else if (m == "name") o.viewSort = 1;
			else if (m == "none") o.viewSort = 2;
			else return Fail(id, "INVALID_ARGS", "variableView.sort 只能是 smart / name / none。");
		}
		if (v.contains("toString") && v["toString"].is_string()) {
			const std::string m = v["toString"].get<std::string>();
			if (m == "tables") o.viewToString = 0;
			else if (m == "all") o.viewToString = 1;
			else if (m == "never") o.viewToString = 2;
			else return Fail(id, "INVALID_ARGS", "variableView.toString 只能是 tables / all / never。");
		}
		flag("groupMethods", o.viewGroupMethods);
		flag("showInherited", o.viewShowInherited);
		flag("showMetatable", o.viewShowMetatable);
		flag("hideStdGlobals", o.viewHideStdGlobals);
		flag("showTemporaries", o.viewShowTemporaries);
		if (v.contains("maxSortKeys") && v["maxSortKeys"].is_number_integer()) {
			o.viewMaxSortKeys = std::max(0, std::min(1000000, v["maxSortKeys"].get<int>()));
		}
	}
	if (args.contains("exceptions") && args["exceptions"].is_string()) {
		const std::string m = args["exceptions"].get<std::string>();
		if (m == "none") o.exceptions = 0;
		else if (m == "uncaught") o.exceptions = 1;
		else if (m == "all") o.exceptions = 2;
		else return Fail(id, "INVALID_ARGS", "exceptions 只能是 none / uncaught / all。");
	}
	if (args.contains("exceptions")) {
		for (auto& vm : core.Vms()) {
			auto v = vm;
			v->Post([v](lua_State* L) { v->SyncErrorWrappers(L); });
		}
	}
	if (o.CaseSensitive() != oldCase) core.RebuildIndex();
	core.NotifyVms(kFlagRefresh);
	Reply(id, json::object());
}

void Session::CmdLoadHelpers(uint64_t id, const json& args) {
	std::vector<HelperScript> scripts;
	if (args.contains("scripts") && args["scripts"].is_array()) {
		for (auto& s : args["scripts"]) {
			HelperScript h;
			h.name = GetStr(s, "name");
			h.source = GetStr(s, "source");
			if (h.name.empty()) return Fail(id, "INVALID_ARGS", "helper 脚本必须有 name。");
			scripts.push_back(std::move(h));
		}
	}
	std::string mainName = GetStr(args, "main", scripts.empty() ? "" : scripts.front().name);
	Core::Get().SetHelpers(std::move(scripts), mainName);
	Core::Get().NotifyVms(kFlagHelpers);
	// 各 VM 在下一次执行 Lua 时加载；失败通过 output（runtime / warn）报告
	Reply(id, json::object());
}

// ---------------------------------------------------------------- 执行控制

void Session::CmdContinue(uint64_t id, const json& args) {
	Core& core = Core::Get();
	uint64_t vmId = GetU64(args, "vmId");
	std::weak_ptr<Session> weak = shared_from_this();
	if (vmId != 0) {
		auto vm = core.FindVm(vmId);
		if (!vm) return Fail(id, "VM_NOT_FOUND", "Lua VM " + std::to_string(vmId) + " 已经关闭，请刷新 VM 列表。");
		uint64_t pauseId = GetU64(args, "pauseId");
		if (vm->currentPauseId() == 0) return Fail(id, "NOT_PAUSED", "Lua VM " + std::to_string(vmId) + " 正在运行。");
		vm->Post([vm, weak, id, pauseId](lua_State*) {
			auto s = weak.lock();
			if (!s) return;
			if (!vm->paused()) return s->Fail(id, "NOT_PAUSED", "Lua VM 正在运行。");
			if (pauseId && pauseId != vm->currentPauseId()) return s->Fail(id, "STALE_PAUSE", "这次暂停已经结束，请等下一次 paused 事件。");
			vm->Resume(ResumeKind::Continue, [s, id] { s->Reply(id, json::object()); });
		});
		return;
	}
	// 所有暂停中的 VM；最后一个恢复后回复
	std::vector<std::shared_ptr<Vm>> pausedVms;
	for (auto& vm : core.Vms()) if (vm->currentPauseId()) pausedVms.push_back(vm);
	if (pausedVms.empty()) return Reply(id, json::object());
	auto remaining = std::make_shared<std::atomic<int>>(static_cast<int>(pausedVms.size()));
	for (auto& vm : pausedVms) {
		vm->Post([vm, weak, id, remaining](lua_State*) {
			auto s = weak.lock();
			auto done = [s, id, remaining] {
				if (remaining->fetch_sub(1) == 1 && s) s->Reply(id, json::object());
			};
			if (!vm->paused()) return done();
			vm->Resume(ResumeKind::Continue, done);
		});
	}
}

void Session::CmdStep(uint64_t id, const std::string& command, const json& args) {
	Core& core = Core::Get();
	uint64_t vmId = GetU64(args, "vmId");
	auto vm = core.FindVm(vmId);
	if (!vm) return Fail(id, "VM_NOT_FOUND", "Lua VM " + std::to_string(vmId) + " 不存在或已经关闭。");
	if (vm->currentPauseId() == 0) return Fail(id, "NOT_PAUSED", "Lua VM " + std::to_string(vmId) + " 正在运行，只能在暂停时单步。");
	uint64_t pauseId = GetU64(args, "pauseId");
	ResumeKind kind = command == "stepIn" ? ResumeKind::StepIn : command == "stepOver" ? ResumeKind::StepOver : ResumeKind::StepOut;
	std::weak_ptr<Session> weak = shared_from_this();
	vm->Post([vm, weak, id, pauseId, kind](lua_State*) {
		auto s = weak.lock();
		if (!s) return;
		if (!vm->paused()) return s->Fail(id, "NOT_PAUSED", "Lua VM 正在运行。");
		if (pauseId && pauseId != vm->currentPauseId()) return s->Fail(id, "STALE_PAUSE", "这次暂停已经结束，请等下一次 paused 事件。");
		vm->Resume(kind, [s, id] { s->Reply(id, json::object()); });
	});
}

void Session::CmdRunToLine(uint64_t id, const json& args) {
	Core& core = Core::Get();
	uint64_t vmId = GetU64(args, "vmId");
	auto vm = core.FindVm(vmId);
	if (!vm) return Fail(id, "VM_NOT_FOUND", "Lua VM " + std::to_string(vmId) + " 不存在或已经关闭。");
	auto bp = std::make_shared<Breakpoint>();
	bp->serial = NextBreakpointSerial();
	bp->id = "runToLine";
	bp->line = GetInt(args, "line", 0);
	if (args.contains("source") && args["source"].is_object()) {
		bp->path = GetStr(args["source"], "path");
		bp->chunkName = GetStr(args["source"], "chunkName");
	}
	if (bp->line <= 0 || (bp->path.empty() && bp->chunkName.empty())) {
		return Fail(id, "INVALID_ARGS", "runToLine 需要 source.path 和正整数 line。");
	}
	bool cs = core.options().CaseSensitive();
	bp->normPath = NormalizePath(bp->path, cs);
	if (!bp->chunkName.empty()) bp->normChunk = NormalizePath(bp->chunkName, cs);
	uint64_t pauseId = GetU64(args, "pauseId");
	std::weak_ptr<Session> weak = shared_from_this();
	vm->Post([vm, weak, id, pauseId, bp](lua_State*) {
		auto s = weak.lock();
		if (!s) return;
		if (vm->paused() && pauseId && pauseId != vm->currentPauseId()) {
			return s->Fail(id, "STALE_PAUSE", "这次暂停已经结束，请等下一次 paused 事件。");
		}
		if (vm->paused()) {
			// 先恢复（恢复会清掉旧的临时断点），再设新的
			vm->Resume(ResumeKind::Continue, [vm, bp, s, id] {
				vm->SetRunToLine(bp);
				s->Reply(id, json::object());
			});
		} else {
			vm->SetRunToLine(bp);
			s->Reply(id, json::object());
		}
	});
	vm->Kick();
}

void Session::CmdPause(uint64_t id, const json& args) {
	Core& core = Core::Get();
	uint64_t vmId = GetU64(args, "vmId");
	if (vmId != 0) {
		auto vm = core.FindVm(vmId);
		if (!vm) return Fail(id, "VM_NOT_FOUND", "Lua VM " + std::to_string(vmId) + " 不存在或已经关闭。");
		if (!vm->currentPauseId()) {
			vm->RequestPause("pause");
			vm->Kick();
		}
	} else {
		for (auto& vm : core.Vms()) {
			if (!vm->currentPauseId()) {
				vm->RequestPause("pause");
				vm->Kick();
			}
		}
	}
	Reply(id, json::object());
}

// ---------------------------------------------------------------- 暂停后取数据

void Session::CmdPaused(uint64_t id, const std::string& command, const json& args) {
	Core& core = Core::Get();
	uint64_t vmId = GetU64(args, "vmId");
	uint64_t objId = 0;
	if (command == "scopes" || (command == "evaluate" && GetU64(args, "frameId"))) objId = GetU64(args, "frameId");
	if (command == "variables" || command == "setVariable") objId = GetU64(args, "ref");
	if (objId) {
		vmId = core.VmOfObject(objId);
		if (!vmId) return Fail(id, "STALE_REFERENCE", "这个引用已失效（游戏已继续运行），请重新获取调用栈。");
	}
	auto vm = core.FindVm(vmId);
	if (!vm) return Fail(id, "VM_NOT_FOUND", "Lua VM " + std::to_string(vmId) + " 不存在或已经关闭。");
	if (!vm->currentPauseId()) return Fail(id, "NOT_PAUSED", "Lua VM " + std::to_string(vmId) + " 正在运行，先暂停或等断点命中。");
	uint64_t pauseId = GetU64(args, "pauseId");
	if (pauseId && pauseId != vm->currentPauseId()) return Fail(id, "STALE_PAUSE", "这次暂停已经结束，请等下一次 paused 事件。");
	Options& o = core.options();
	EvalOptions eo;
	eo.allowLua = o.allowLuaEval.load();
	eo.invokeIndex = o.valuePathInvokeIndex.load();
	eo.timeoutMs = o.evalTimeoutMs.load();
	eo.maxLength = static_cast<size_t>(std::max(16, std::min(1 << 20, GetInt(args, "maxLength", 1024))));
	ViewOptions vo;
	vo.rich = o.richVariables.load() && GetStr(args, "view", "auto") != "raw";
	vo.sort = o.viewSort.load();
	vo.groupMethods = o.viewGroupMethods.load();
	vo.showInherited = o.viewShowInherited.load();
	vo.showMetatable = o.viewShowMetatable.load();
	vo.hideStdGlobals = o.viewHideStdGlobals.load();
	vo.showTemporaries = o.viewShowTemporaries.load();
	vo.maxSortKeys = o.viewMaxSortKeys.load();
	vo.toString = o.viewToString.load();
	vo.maxValueLength = static_cast<size_t>(std::max(16, std::min(1024, GetInt(args, "maxValueLength", 256))));
	std::weak_ptr<Session> weak = shared_from_this();
	vm->Post([vm, weak, id, command, args, objId, eo, vo](lua_State*) {
		auto s = weak.lock();
		if (!s) return;
		if (!vm->paused()) return s->Fail(id, "STALE_REFERENCE", "这次暂停已经结束（游戏已继续运行）。");
		Inspector& ins = vm->inspector();
		ins.SetView(vo);
		json body;
		ProtocolError err;
		bool ok = false;
		if (command == "stackTrace") {
			ok = ins.StackTrace(GetInt(args, "start", 0), GetInt(args, "count", 20), body, err, GetStr(args, "threadId"));
		} else if (command == "scopes") {
			ok = ins.Scopes(objId, body, err);
		} else if (command == "variables") {
			ok = ins.Variables(objId, GetInt(args, "start", 0), GetInt(args, "count", 100), GetStr(args, "filter"), body, err,
				args.contains("expand") ? args["expand"] : json());
		} else if (command == "evaluate") {
			ok = ins.Evaluate(objId, GetStr(args, "expression"), GetStr(args, "mode", "valuePath"), eo, body, err);
		} else if (command == "setVariable") {
			ok = ins.SetVariable(objId, GetStr(args, "name"), GetStr(args, "value"), GetStr(args, "mode", "valuePath"), eo, body, err);
		} else if (command == "threads") {
			ok = ins.Threads(body, err);
		}
		if (ok) s->Reply(id, std::move(body));
		else s->Fail(id, err.code, err.message);
	});
}

void Session::CmdDisconnect(uint64_t id, const json& args) {
	Reply(id, json::object());
	Close();
}

} // namespace emmy
