#include "vm.h"

#include "core.h"
#include "lua_internal.h"
#include "session.h"

#include <chrono>
#include <algorithm>
#include <cstring>

namespace emmy {

namespace {

constexpr int kMinCount = 1000;
constexpr int kMaxCount = 10000000;
constexpr size_t kMaxCacheEntries = 65536;

int64_t WallMs() {
	return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

std::string ThreadIdOf(lua_State* L) {
	char buf[32];
	std::snprintf(buf, sizeof(buf), "t%llx", static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(L)));
	return buf;
}

// ---- ABI 自检 ----
// 注入方式下核心按标准 Lua 头文件编译，宿主的 Lua 可能是改过的分支（UnLua 二次开发、改了 LUA_IDSIZE、
// 改了 lua_Debug 布局……）。布局不一致时 lua_getinfo 会写坏栈、读出乱码，表现为随机崩溃或断点永远不停。
// 第一次钩子事件时跑一段已知的小代码，核对 getstack / getinfo / getlocal 的结果，不对就不调试这个 VM。

constexpr const char kAbiChunk[] =
	"local probe = ...\n"            // 1
	"local function f(p1)\n"         // 2
	"  local l1 = 7\n"               // 3
	"  probe()\n"                    // 4
	"  return l1\n"                  // 5
	"end\n"                          // 6
	"return f(42)\n";                // 7
constexpr const char kAbiChunkName[] = "=emmy_abi_check";

struct AbiProbe {
	bool ran = false;
	std::string why;
};
thread_local AbiProbe* t_abiProbe = nullptr;

int AbiProbeFn(lua_State* L) {
	AbiProbe* probe = t_abiProbe;
	if (!probe) return 0;
	probe->ran = true;
	// lua_Debug 后面放一段哨兵：宿主的 lua_Debug 比我们的大时 getinfo 会写到这里
	struct {
		lua_Debug ar;
		unsigned char guard[512];
	} buf;
	std::memset(&buf, 0, sizeof(buf));
	std::memset(buf.guard, 0xA5, sizeof(buf.guard));
	auto fail = [&](const std::string& what) {
		if (probe->why.empty()) probe->why = what;
		return 0;
	};
	if (!lua_getstack(L, 1, &buf.ar)) return fail("lua_getstack 取不到调用者");
	if (!lua_getinfo(L, "Sl", &buf.ar)) return fail("lua_getinfo 失败");
	for (unsigned char b : buf.guard) {
		if (b != 0xA5) return fail("lua_getinfo 写出了 lua_Debug 的范围（宿主的 lua_Debug 结构更大）");
	}
	if (!buf.ar.source || std::strcmp(buf.ar.source, kAbiChunkName) != 0) return fail("lua_Debug.source 不对");
	if (!std::memchr(buf.ar.short_src, 0, sizeof(buf.ar.short_src))) return fail("lua_Debug.short_src 没有结束符");
	if (buf.ar.currentline != 4 || buf.ar.linedefined != 2 || buf.ar.lastlinedefined != 6) {
		return fail("lua_Debug 的行号字段不对（currentline=" + std::to_string(buf.ar.currentline) + "，linedefined=" +
			std::to_string(buf.ar.linedefined) + "，lastlinedefined=" + std::to_string(buf.ar.lastlinedefined) + "）");
	}
	const char* n1 = lua_getlocal(L, &buf.ar, 1);
	if (!n1 || std::strcmp(n1, "p1") != 0 || lua_tonumber(L, -1) != 42) {
		if (n1) lua_pop(L, 1);
		return fail("lua_getlocal 取到的第 1 个局部变量不对");
	}
	lua_pop(L, 1);
	const char* n2 = lua_getlocal(L, &buf.ar, 2);
	if (!n2 || std::strcmp(n2, "l1") != 0 || lua_tonumber(L, -1) != 7) {
		if (n2) lua_pop(L, 1);
		return fail("lua_getlocal 取到的第 2 个局部变量不对");
	}
	lua_pop(L, 1);
	return 0;
}

// 返回空串表示通过
std::string RunAbiSelfCheck(lua_State* L) {
	AbiProbe probe;
	t_abiProbe = &probe;
	const int top = lua_gettop(L);
	std::string why;
	if (lua::LoadBuffer(L, kAbiChunk, sizeof(kAbiChunk) - 1, kAbiChunkName) != LUA_OK) {
		why = std::string("自检代码加载失败：") + (lua_tostring(L, -1) ? lua_tostring(L, -1) : "?");
	} else {
		lua_pushcfunction(L, &AbiProbeFn);
		if (lua_pcall(L, 1, 1, 0) != LUA_OK) {
			why = std::string("自检代码执行失败：") + (lua_tostring(L, -1) ? lua_tostring(L, -1) : "?");
		} else if (!probe.ran) {
			why = "自检函数没有被调用";
		} else if (!probe.why.empty()) {
			why = probe.why;
		} else if (lua_tonumber(L, -1) != 7) {
			why = "自检代码的返回值不对";
		}
	}
	lua_settop(L, top);
	t_abiProbe = nullptr;
	return why;
}

} // namespace

Vm::Vm(uint64_t id, lua_State* L, std::string discovery)
	: id_(id), mainL_(lua::MainThreadOf(L)), g_(lua::GlobalStateOf(L)), discovery_(std::move(discovery)), mux_(mainL_), inspector_(*this), profiler_(*this, mux_) {
	name_ = "Lua VM #" + std::to_string(id);
	mux_.SetDebugger(this);
}

Vm::~Vm() = default;

void Vm::Install() {
	RegisterMux(g_, &mux_);
	mux_.AdoptExistingHook();
	sessionGen_ = 0;
	// 已经有 IDE 连着：马上进入调试状态
	if (Core::Get().connected()) {
		SetFlags(kFlagSession | kFlagRefresh | kFlagHelpers);
		mux_.Kick();
	}
}

void Vm::Uninstall() {
	if (outputCaptured_) InstallOutputCapture(mainL_, false);
	if (errorsWrapped_) InstallErrorWrappers(mainL_, false);
	inspector_.ResetConditionCache(mainL_);
	ClearStep(mainL_);
	ReleaseFunctionTargets(mainL_);
	profiler_.Stop();
	mux_.Uninstall();
	UnregisterMux(g_);
	// 唤醒可能在等命令的线程（理论上 Unregister 在 VM 线程调用，此时不会在暂停循环里）
	SetFlags(kFlagDetach);
}

// ---------------------------------------------------------------- 任意线程

void Vm::SetName(const std::string& name) {
	std::lock_guard<std::mutex> lock(infoMutex_);
	name_ = name;
}

std::string Vm::name() {
	std::lock_guard<std::mutex> lock(infoMutex_);
	return name_;
}

void Vm::ResetContext(const std::string& reason) {
	sourceEpoch_.fetch_add(1);
	SetFlags(kFlagRefresh);
	if (Core::Get().connected()) {
		Core::Get().Emit("vmStateChanged", {{"vmId", id_}, {"state", currentPauseId() ? "paused" : "running"},
			{"sourceEpoch", sourceEpoch_.load()}, {"reason", reason.empty() ? "contextReset" : reason}});
	}
}

json Vm::Info() {
	json j = {
		{"vmId", id_},
		{"generation", 1},
		{"name", name()},
		{"state", currentPauseId() ? "paused" : "running"},
		{"luaVersion", lua::VersionString()},
		{"sourceEpoch", sourceEpoch_.load()},
		{"mainThreadId", ThreadIdOf(mainL_)},
	};
	if (!discovery_.empty()) j["discovery"] = discovery_;
	return j;
}

void Vm::Post(Command cmd) {
	{
		std::lock_guard<std::mutex> lock(cmdMutex_);
		commands_.push_back(std::move(cmd));
	}
	pending_.fetch_or(kFlagCommands, std::memory_order_release);
	cmdCv_.notify_all();
	if (!currentPauseId()) mux_.Kick();
}

void Vm::SetFlags(uint32_t flags) {
	pending_.fetch_or(flags, std::memory_order_release);
	if (!currentPauseId()) mux_.Kick();
	if (flags & kFlagDetach) {
		std::lock_guard<std::mutex> lock(cmdMutex_);
		cmdCv_.notify_all();
	} else {
		cmdCv_.notify_all();
	}
}

void Vm::RequestPause(const char* reason) {
	pauseReason_.store(std::strcmp(reason, "entry") == 0 ? 1 : 0);
	SetFlags(kFlagPause);
}

// ---------------------------------------------------------------- 掩码

int Vm::BaseMask() const {
	// 线程无关的基础掩码。没有断点、没有单步、没有待处理请求时为 0：
	// Lua 5.4 只要挂了任何钩子（哪怕只有 COUNT）解释器就会逐条指令检查，开销接近 2 倍，所以平时一个都不挂。
	// 需要 VM 线程处理事情（暂停请求、命令、断点变化）时由 Kick() 临时挂上 COUNT。
	if (!active_.load(std::memory_order_relaxed)) return 0;
	int m = 0;
	if (anyBreakpoints_.load(std::memory_order_relaxed) || runToLine_) m |= LUA_MASKCALL;
	if (step_.kind != ResumeKind::None) m |= LUA_MASKCALL | LUA_MASKRET | LUA_MASKLINE;
	if (pauseAtLine_) m |= LUA_MASKLINE | LUA_MASKCOUNT;
	if (pending_.load(std::memory_order_relaxed) != 0) m |= LUA_MASKCOUNT;
	return m;
}

bool Vm::HasPendingWork() const {
	return pending_.load(std::memory_order_acquire) != 0;
}

bool Vm::WantsHook() const {
	return Core::Get().connected();
}

bool Vm::InEval(lua_State* L, bool& timedOut) {
	if (!inspector_.evaluating()) return false;
	timedOut = inspector_.EvalTimedOut();
	return true;
}

void Vm::AdaptCount() {
	// 让「无断点时响应 Break All」的延迟接近 pauseLatencyMs：COUNT 间隔按实际执行速度自适应
	auto now = std::chrono::steady_clock::now();
	if (lastCount_.time_since_epoch().count() != 0) {
		auto dt = std::chrono::duration_cast<std::chrono::microseconds>(now - lastCount_).count();
		int64_t target = static_cast<int64_t>(Core::Get().options().pauseLatencyMs.load()) * 1000 / 2;
		if (target < 1000) target = 1000;
		int c = countInterval_.load(std::memory_order_relaxed);
		if (dt < target / 4 && c < kMaxCount) countInterval_.store(std::min(c * 2, kMaxCount), std::memory_order_relaxed);
		else if (dt > target && c > kMinCount) countInterval_.store(std::max(c / 4, kMinCount), std::memory_order_relaxed);
	}
	lastCount_ = now;
}

// ---------------------------------------------------------------- 热路径

bool Vm::IsHotFunc(const lua::FuncInfo& f) {
	if (f.isC) return false;
	return IsHotIn(Lookup(f.source, 0), f);
}

bool Vm::IsHotIn(const SourceEntry* e, const lua::FuncInfo& f) {
	if (!e || !e->hot || f.isC) return false;
	if (f.lineDefined == 0) return true; // 主 chunk 覆盖整个文件
	// 只有函数定义范围内有断点行才开行钩子（同一文件里的其他函数不受影响）
	auto it = std::lower_bound(e->lines.begin(), e->lines.end(), f.lineDefined);
	return it != e->lines.end() && *it <= f.lastLineDefined;
}

bool Vm::AnyHotFrame(lua_State* L, lua_Debug* ar) {
	// 从调用者往上找有没有处在「热」函数里的帧；最多看 200 层
	lua_Debug cur;
	lua::FuncInfo f;
	for (int level = 1; level < 200; ++level) {
		if (!lua_getstack(L, level, &cur)) return false;
		if (lua::HookFuncInfo(L, &cur, false, f) && IsHotFunc(f)) return true;
	}
	return true;
}

int Vm::OnHook(lua_State* L, lua_Debug* ar) {
	// 单写者计数：不用原子加，避免热路径上的锁总线指令
	hookEvents_.store(hookEvents_.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
	if (!abiChecked_) {
		abiChecked_ = true;
		std::string why = RunAbiSelfCheck(L);
		if (!why.empty()) {
			mux_.Disable(L, "这个 Lua VM 和调试器的 Lua 接口不一致，已停止调试它（游戏继续运行）：" + why +
				"。通常是项目用了改过的 Lua（UnLua 二次开发、改了 LUA_IDSIZE 或 lua_Debug）。请把这条信息和 Lua 版本反馈给我们。");
			return 0;
		}
	}
	// 暂停循环里执行的 Lua 走到别的协程上的钩子：标志留给恢复之后处理（见 EnterPause）
	if (inPauseLoop_) return BaseMask();
	bool refreshed = false;
	if (pending_.load(std::memory_order_acquire) != 0) {
		ProcessPending(L);
		refreshed = true;
	}
	if (!active_.load(std::memory_order_relaxed)) {
		return 0;
	}
	// 线程掩码约定：
	//   冷：只有 BaseMask（有断点时 CALL）
	//   热函数里：CALL | RET | LINE
	//   热函数调用的冷函数里：CALL | RET（等它返回到热函数时再开 LINE）
	const int ev = ar->event;
	const int curMask = lua_gethookmask(L);
	const int region = LUA_MASKCALL | LUA_MASKRET;
	lua::FuncInfo f;
	if (lua::IsCallEvent(ev)) {
		const bool hasInfo = lua::HookFuncInfo(L, ar, false, f);
		// 函数断点：命中时给这个函数开 LINE，在它的第一行停（那时参数已经就位，条件和局部变量才取得到）
		if (hasInfo && anyFuncBreakpoints_.load(std::memory_order_relaxed) && OnFunctionCall(L, ar, f)) {
			return BaseMask() | region | LUA_MASKLINE;
		}
		if (hasInfo && IsHotFunc(f)) return BaseMask() | region | LUA_MASKLINE;
		return BaseMask() | (curMask & LUA_MASKRET ? region : 0);
	}
	if (ev == LUA_HOOKRET) {
		if (lua::HookFuncInfo(L, ar, true, f)) {
			if (IsHotFunc(f)) return BaseMask() | region | LUA_MASKLINE;
			// 回到冷的调用者：再往上还有热帧就继续跟 RET，否则回到冷状态
			lua_Debug self;
			if (lua_getstack(L, 0, &self) && AnyHotFrame(L, &self)) return BaseMask() | region;
		}
		return BaseMask();
	}
	if (ev == LUA_HOOKLINE) {
		bool hasInfo = lua::HookFuncInfo(L, ar, false, f);
		SourceEntry* e = nullptr;
		bool hot = false;
		if (hasInfo && !f.isC) {
			// 热函数里每行都进来：同一个函数连续的行事件直接用上次的判断（断点表变了 cacheStamp_ 会变）
			if (f.source == lineSource_ && f.lineDefined == lineDefined_ && f.lastLineDefined == lineLastDefined_ && lineStamp_ == cacheStamp_) {
				e = lineEntry_;
				hot = lineHot_;
			} else {
				e = Lookup(f.source, 0);
				hot = IsHotIn(e, f);
				lineSource_ = f.source;
				lineDefined_ = f.lineDefined;
				lineLastDefined_ = f.lastLineDefined;
				lineStamp_ = cacheStamp_;
				lineEntry_ = e;
				lineHot_ = hot;
			}
		}
		const uint64_t pausesBefore = pauseCounter_;
		// 函数断点在这个函数的第一行停下时，这一行不再按行断点 / 单步再停一次
		const bool funcPaused = funcPending_.L == L && OnFunctionEntry(L, ar, hasInfo ? f : lua::FuncInfo{});
		if (funcPaused) {
		} else if (pauseAtLine_) {
			pauseAtLine_ = false;
			EnterPause(L, pauseAtLineReason_, {}, nullptr);
		} else if (step_.kind != ResumeKind::None) {
			if (StepShouldStop(L)) {
				ClearStep(L);
				uint64_t before = pauseCounter_;
				// 单步刚好落在断点行：先按断点处理（可能带条件、日志），没停下来再按单步停
				if (hot && e->HasLine(ar->currentline)) OnBreakLine(L, ar, e);
				if (pauseCounter_ == before) EnterPause(L, "step", {}, nullptr);
			} else if (hot && e->HasLine(ar->currentline)) {
				OnBreakLine(L, ar, e);
			}
		} else if (hot && e->HasLine(ar->currentline)) {
			OnBreakLine(L, ar, e);
		}
		// 暂停之后断点/单步状态可能变了，重新判断
		if (pauseCounter_ != pausesBefore) {
			e = hasInfo && !f.isC ? Lookup(f.source, 0) : nullptr;
			hot = IsHotIn(e, f);
		}
		if (hot) return BaseMask() | region | LUA_MASKLINE;
		return BaseMask() | (curMask & LUA_MASKRET ? region : 0);
	}
	// COUNT：保持当前的区域状态
	AdaptCount();
	if (refreshed && ev == LUA_HOOKCOUNT) {
		// 断点刚变（或 VM 刚接入会话）：正在跑的函数没有 CALL 事件可等，当场判断它和它的调用者热不热。
		// 否则模块顶层代码、主循环这类一直不返回的函数里新下的断点要等下次进入才生效
		if (lua::HookFuncInfo(L, ar, false, f) && IsHotFunc(f)) return BaseMask() | region | LUA_MASKLINE;
		lua_Debug self;
		if (lua_getstack(L, 0, &self) && AnyHotFrame(L, &self)) return BaseMask() | region;
	}
	return BaseMask() | (curMask & (region | LUA_MASKLINE));
}

Vm::SourceEntry* Vm::Lookup(const char* source, size_t) {
	if (!source) return nullptr;
	if (source == lastSource_ && lastEntry_ && lastEntry_->stamp == cacheStamp_) {
		return lastEntry_;
	}
	auto it = cache_.find(source);
	if (it != cache_.end() && it->second.stamp == cacheStamp_ && it->second.chunk == source) {
		lastSource_ = source;
		lastEntry_ = &it->second;
		return lastEntry_;
	}
	if (it == cache_.end()) {
		if (cache_.size() >= kMaxCacheEntries) {
			cache_.clear();
			++cacheStamp_; // 指向旧条目的缓存（lineEntry_ 等）一起作废
			lastSource_ = nullptr;
			lastEntry_ = nullptr;
		}
		it = cache_.emplace(source, SourceEntry{}).first;
	}
	SourceEntry& e = it->second;
	e.chunk = source;
	e.stamp = cacheStamp_;
	e.match = index_ ? index_->Match(e.chunk, id_) : ChunkMatch{};
	e.rtl = false;
	if (runToLine_ && MatchSingle(*runToLine_, e.chunk, Core::Get().options().CaseSensitive())) {
		e.rtl = true;
		e.rtlLine = runToLine_->line;
	}
	e.hot = !e.match.bps.empty() || e.rtl;
	DiagRecordSource(e);
	e.lines.clear();
	for (const Breakpoint* bp : e.match.bps) e.lines.push_back(bp->line);
	if (e.rtl) e.lines.push_back(e.rtlLine);
	std::sort(e.lines.begin(), e.lines.end());
	if (!e.match.bps.empty() || !e.match.ambiguous.empty()) ReportMatch(e);
	lastSource_ = source;
	lastEntry_ = &e;
	return &e;
}

void Vm::ReportMatch(const SourceEntry& entry) {
	Core& core = Core::Get();
	std::string hash;
	bool hashLoaded = false;
	for (const Breakpoint* bp : entry.match.bps) {
		std::string state = "verified";
		if (!bp->sha256.empty()) {
			if (!hashLoaded) {
				hash = core.SourceHashOf(entry.chunk);
				hashLoaded = true;
			}
			if (!hash.empty() && hash != bp->sha256) state = "mismatch";
		}
		auto& reported = reported_[bp->serial];
		if (reported == state) continue;
		reported = state;
		json body = {{"id", bp->id}, {"vmId", id_}, {"state", state}, {"chunkName", entry.chunk}, {"line", bp->line}};
		if (state == "mismatch") body["message"] = "设备上的代码和本地文件内容不一致，断点行号可能不准。";
		core.Emit("breakpointStatus", std::move(body));
	}
	for (const Breakpoint* bp : entry.match.ambiguous) {
		auto& reported = reported_[bp->serial];
		if (reported == "ambiguous") continue;
		reported = "ambiguous";
		core.Emit("breakpointStatus", {{"id", bp->id}, {"vmId", id_}, {"state", "ambiguous"}, {"chunkName", entry.chunk},
			{"message", "多个本地文件都能对应 " + entry.chunk + "，请在源码映射里指定 chunkName。"}});
	}
}

bool Vm::HandleBreakpointHit(lua_State* L, const Breakpoint* bp, int line, const std::string& chunk) {
	Core& core = Core::Get();
	Options& opt = core.options();
	if (!bp->condition.empty()) {
		bool ok = false;
		std::string err;
		if (!inspector_.EvalCondition(L, bp->serial, bp->condition, opt.evalTimeoutMs.load(), ok, err)) {
			auto& reported = reported_[bp->serial];
			if (reported != "invalid") {
				reported = "invalid";
				core.Emit("breakpointStatus", {{"id", bp->id}, {"vmId", id_}, {"state", "invalid"}, {"message", "条件求值出错：" + err}});
			}
			return false;
		}
		if (!ok) return false;
	}
	int64_t& count = hitCounts_[bp->serial];
	++count;
	if (!bp->hitCondition.Check(count)) return false;
	if (!bp->logMessage.empty()) {
		// {expr} 插值；{{ / }} 转义
		std::string out;
		const std::string& m = bp->logMessage;
		for (size_t i = 0; i < m.size(); ++i) {
			if (m[i] == '{' && i + 1 < m.size() && m[i + 1] == '{') { out += '{'; ++i; continue; }
			if (m[i] == '}' && i + 1 < m.size() && m[i + 1] == '}') { out += '}'; ++i; continue; }
			if (m[i] == '{') {
				size_t end = m.find('}', i + 1);
				if (end == std::string::npos) { out += m.substr(i); break; }
				std::string expr = m.substr(i + 1, end - i - 1);
				std::string err;
				int top = lua_gettop(L);
				bool ok = opt.allowLuaEval.load() ? inspector_.EvalLuaAt(L, 0, expr, opt.evalTimeoutMs.load(), err)
												  : inspector_.EvalValuePathAt(L, 0, expr, false, err);
				if (ok) {
					if (lua_type(L, -1) == LUA_TSTRING) out += lua_tostring(L, -1);
					else out += inspector_.Summarize(L, -1, 256);
				} else {
					out += "<" + err + ">";
				}
				lua_settop(L, top);
				i = end;
				continue;
			}
			out += m[i];
		}
		out += '\n';
		core.Emit("output", {{"category", "logpoint"}, {"text", out}, {"vmId", id_}, {"chunkName", chunk}, {"line", line}});
	}
	if (!bp->Pauses() || !bp->capture.empty()) {
		if (!bp->Pauses()) {
			// 每个断点每秒最多 100 条
			int64_t now = WallMs();
			int64_t& winStart = hitWindowStart_[bp->serial];
			int& winCount = hitWindowCount_[bp->serial];
			if (now - winStart >= 1000) { winStart = now; winCount = 0; }
			if (++winCount > 100) { ++hitDropped_[bp->serial]; return false; }
		}
		json captures = json::array();
		for (const std::string& expr : bp->capture) {
			std::string err;
			int top = lua_gettop(L);
			json c = {{"expression", expr}};
			if (inspector_.EvalValuePathAt(L, 0, expr, opt.valuePathInvokeIndex.load(), err)) {
				std::string typeName;
				c["value"] = inspector_.Summarize(L, -1, 1024, &typeName);
				c["type"] = lua_typename(L, lua_type(L, -1));
				if (!typeName.empty()) c["typeName"] = typeName;
			} else {
				c["error"] = err;
			}
			lua_settop(L, top);
			captures.push_back(std::move(c));
		}
		if (!bp->Pauses()) {
			json body = {{"id", bp->id}, {"vmId", id_}, {"threadId", ThreadIdOf(L)}, {"hitCount", count}, {"line", line},
				{"timeMs", WallMs()}, {"captures", std::move(captures)}};
			int& dropped = hitDropped_[bp->serial];
			if (dropped) { body["dropped"] = dropped; dropped = 0; }
			core.Emit("breakpointHit", std::move(body));
		}
	}
	return bp->Pauses();
}

void Vm::OnBreakLine(lua_State* L, lua_Debug* ar, SourceEntry* entry) {
	const int line = ar->currentline;
	std::vector<std::string> pauseIds;
	bool rtlHit = entry->rtl && entry->rtlLine == line;
	for (const Breakpoint* bp : entry->match.bps) {
		if (bp->line != line) continue;
		if (HandleBreakpointHit(L, bp, line, entry->chunk)) pauseIds.push_back(bp->id);
	}
	if (!pauseIds.empty()) {
		EnterPause(L, "breakpoint", std::move(pauseIds), nullptr);
	} else if (rtlHit) {
		EnterPause(L, "runToLine", {}, nullptr);
	}
}

int Vm::StackDepthAtLeast(lua_State* L, int level) {
	lua_Debug ar;
	return lua_getstack(L, level, &ar);
}

bool Vm::StepShouldStop(lua_State* L) {
	switch (step_.kind) {
	case ResumeKind::StepIn:
		return true;
	case ResumeKind::StepOver:
	case ResumeKind::StepOut: {
		// 暂停时的帧数为 depth；stepOver 停在帧数 <= depth，stepOut 停在帧数 <= depth - 1。
		// 帧数 <= n  ⇔  第 n 层（从 0 计）不存在
		int limit = step_.kind == ResumeKind::StepOver ? step_.depth : step_.depth - 1;
		if (L == step_.thread) {
			return limit >= 1 && !StackDepthAtLeast(L, limit);
		}
		// 别的线程在跑：被跟踪的协程已经 yield 或结束（离开了它），就停在这里
		lua_State* t = step_.thread;
		if (lua_status(t) == LUA_YIELD) return true;
		return !StackDepthAtLeast(t, 0);
	}
	default:
		return false;
	}
}

void Vm::ClearStep(lua_State* L) {
	if (step_.anchorRef != LUA_NOREF) {
		luaL_unref(L, LUA_REGISTRYINDEX, step_.anchorRef);
	}
	step_ = StepState{};
}

// ---------------------------------------------------------------- 待处理标志

void Vm::ProcessPending(lua_State* L) {
	uint32_t flags = pending_.exchange(0, std::memory_order_acq_rel);
	Core& core = Core::Get();
	if (flags & (kFlagSession | kFlagDetach)) {
		uint64_t gen = core.sessionGeneration();
		if (gen != sessionGen_) {
			sessionGen_ = gen;
			ResetSessionState(L);
		}
		bool wasActive = active_.load();
		active_.store(core.connected(), std::memory_order_relaxed);
		if (!active_.load() && wasActive) {
			ResetSessionState(L);
		}
		flags |= kFlagRefresh;
	}
	if (flags & kFlagRefresh) {
		RefreshIndex();
		bool wantCapture = active_.load() && core.options().captureOutput.load();
		if (wantCapture != outputCaptured_) InstallOutputCapture(L, wantCapture);
		bool wantWrap = active_.load() && core.options().WantCaughtErrors();
		if (wantWrap != errorsWrapped_) InstallErrorWrappers(L, wantWrap);
	}
	if (!active_.load()) {
		// 没连接：丢弃命令（会话已经没了），清掉暂停请求
		std::deque<Command> drop;
		{
			std::lock_guard<std::mutex> lock(cmdMutex_);
			drop.swap(commands_);
		}
		return;
	}
	if (flags & kFlagHelpers) {
		ApplyHelpers(L);
	}
	if (flags & kFlagCommands) {
		RunCommands(L);
	}
	if (flags & (kFlagPause | kFlagEntry)) {
		pauseAtLine_ = true;
		pauseAtLineReason_ = pauseReason_.load() == 1 ? "entry" : "pause";
	}
}

void Vm::RunCommands(lua_State* L) {
	std::deque<Command> cmds;
	{
		std::lock_guard<std::mutex> lock(cmdMutex_);
		cmds.swap(commands_);
	}
	for (Command& c : cmds) c(L);
}

void Vm::ResetSessionState(lua_State* L) {
	hitCounts_.clear();
	reported_.clear();
	hitWindowStart_.clear();
	hitWindowCount_.clear();
	hitDropped_.clear();
	runToLine_.reset();
	ClearStep(L);
	pauseAtLine_ = false;
	inspector_.ResetConditionCache(L);
	ReleaseFunctionTargets(L);
	++cacheStamp_;
}

void Vm::RefreshIndex() {
	Core& core = Core::Get();
	auto idx = core.CurrentIndex();
	uint64_t epoch = sourceEpoch_.load();
	if (idx != index_ || epoch != seenEpoch_) {
		if (epoch != seenEpoch_) reported_.clear();
		index_ = idx;
		seenEpoch_ = epoch;
		++cacheStamp_;
		lastEntry_ = nullptr;
		lastSource_ = nullptr;
	}
	anyBreakpoints_.store(index_ && index_->AnyFor(id_), std::memory_order_relaxed);
	anyLineBreakpoints_.store(index_ && index_->AnyLineFor(id_), std::memory_order_relaxed);
	// 函数断点：每次刷新都重新解析（断点、上下文、热重载都可能换掉函数）
	funcBps_.clear();
	if (index_) {
		for (const BreakpointPtr& bp : index_->functions()) {
			if (bp->vmId == 0 || bp->vmId == id_) funcBps_.push_back(bp);
		}
	}
	funcDirty_ = true;
	if (funcBps_.empty()) funcPending_ = FuncPending{};
	anyFuncBreakpoints_.store(!funcBps_.empty(), std::memory_order_relaxed);
	{
		std::lock_guard<std::mutex> lock(diagMutex_);
		if (diagIndex_ != index_.get()) {
			diagIndex_ = index_.get();
			diagSources_.clear();
			diagSourceTotal_ = 0;
			diagMatched_ = false;
			diagReported_ = false;
			diagEventsBase_ = hookEvents_.load(std::memory_order_relaxed);
		}
	}
}

void Vm::DiagRecordSource(const SourceEntry& e) {
	std::lock_guard<std::mutex> lock(diagMutex_);
	if (!e.match.bps.empty()) diagMatched_ = true;
	if (e.chunk.empty() || e.chunk[0] == '=') return; // =[C] 之类没有参考价值
	for (const std::string& s : diagSources_) {
		if (s == e.chunk) return;
	}
	++diagSourceTotal_;
	if (diagSources_.size() < kDiagMaxSources) diagSources_.push_back(e.chunk);
}

std::string Vm::BreakpointDiagnostics(const void* currentIndex) {
	// 只管行断点：函数断点没有文件可对，解析不到时按名字兜底，不算「路径对不上」
	if (!anyLineBreakpoints_.load(std::memory_order_relaxed)) return {};
	std::lock_guard<std::mutex> lock(diagMutex_);
	if (diagReported_ || diagMatched_) return {};
	diagReported_ = true;
	const uint64_t events = hookEvents_.load(std::memory_order_relaxed) - diagEventsBase_;
	std::string text = "VM " + std::to_string(id_) + "：断点下发后还没有匹配上任何执行过的脚本。";
	if (diagIndex_ != currentIndex) {
		text += "这批断点还没在这个 VM 上生效：从下发起它没有执行过 Lua，或者调试钩子没挂到实际执行脚本的 lua_State 上。";
		return text;
	}
	if (diagSources_.empty()) {
		text += "这段时间钩子收到 " + std::to_string(events) +
			" 个事件，但没有执行过任何脚本文件里的函数（断点所在的代码可能还没跑到）。";
		return text;
	}
	text += "这段时间执行过的脚本 chunk 名（去重，共 " + std::to_string(diagSourceTotal_) + " 个";
	if (diagSourceTotal_ > diagSources_.size()) text += "，只列前 " + std::to_string(diagSources_.size()) + " 个";
	text += "）：";
	for (const std::string& s : diagSources_) {
		std::string line = s.size() > 200 ? s.substr(0, 200) + "…" : s;
		for (char& c : line) {
			if (c == '\n' || c == '\r') c = ' ';
		}
		text += "\n  " + line;
	}
	text += "\n如果断点所在的文件不在里面，是那段代码还没执行；如果在里面但没断下，是 chunk 名和 IDE 里的文件路径没对上，请把这段日志附到问题报告里。";
	return text;
}

void Vm::SetRunToLine(std::shared_ptr<Breakpoint> bp) {
	runToLine_ = std::move(bp);
	++cacheStamp_;
	lastEntry_ = nullptr;
}

// ---------------------------------------------------------------- 暂停

void Vm::Resume(ResumeKind kind, std::function<void()> afterResumed) {
	resumeKind_ = kind;
	if (afterResumed) afterResumed_.push_back(std::move(afterResumed));
}

void Vm::EnterPause(lua_State* L, const char* reason, std::vector<std::string> bpIds, const char* description,
	json exception, int skipLevels) {
	Core& core = Core::Get();
	if (inPauseLoop_ || !core.connected()) return;
	inPauseLoop_ = true;
	paused_ = true;
	pausedL_ = L;
	pauseAtLine_ = false;
	if (runToLine_) SetRunToLine(nullptr);
	ClearStep(L);
	uint64_t pauseId = ++pauseCounter_;
	currentPauseId_.store(pauseId, std::memory_order_release);
	inspector_.BeginPause(L, skipLevels);

	json body = {{"vmId", id_}, {"pauseId", pauseId}, {"threadId", ThreadIdOf(L)}, {"reason", reason}, {"top", inspector_.TopFrame()}};
	if (!bpIds.empty()) {
		body["breakpointIds"] = bpIds;
		body["description"] = "命中断点 " + bpIds.front();
	} else if (description) {
		body["description"] = description;
	}
	if (!exception.is_null()) body["exception"] = std::move(exception);
	core.Emit("paused", std::move(body));

	resumeKind_ = ResumeKind::None;
	// 断开（或换了会话）以会话代数和连接状态为准，不只看 pending_ 标志：暂停循环里跑的命令会执行 Lua
	// （求值、类型显示脚本、pcall 出错进错误收集），途中的钩子 / 错误回调可能先把标志取走，只看标志就会一直等下去，
	// 用户点了停止调试游戏还卡在断点上。
	const uint64_t pauseGen = core.sessionGeneration();
	auto sessionGone = [&] {
		return (pending_.load() & (kFlagDetach | kFlagSession)) != 0 || !core.connected() || core.sessionGeneration() != pauseGen;
	};
	while (resumeKind_ == ResumeKind::None) {
		std::deque<Command> cmds;
		{
			std::unique_lock<std::mutex> lock(cmdMutex_);
			// 带超时兜底：万一唤醒丢了，最迟一会儿也会重新检查会话是否还在
			cmdCv_.wait_for(lock, std::chrono::milliseconds(200), [&] { return !commands_.empty() || sessionGone(); });
			cmds.swap(commands_);
		}
		for (Command& c : cmds) {
			// 已经断开：剩下的请求（变量、求值）没人收结果了，不再跑，马上放游戏继续
			if (sessionGone()) break;
			c(L);
			if (resumeKind_ != ResumeKind::None) break;
		}
		if (resumeKind_ == ResumeKind::None && sessionGone()) {
			// IDE 断开（或换了会话）：不能让游戏卡在这里
			resumeKind_ = ResumeKind::Detach;
		}
	}
	ResumeKind kind = resumeKind_;
	inspector_.EndPause(L);
	currentPauseId_.store(0, std::memory_order_release);
	paused_ = false;
	pausedL_ = nullptr;

	if (kind == ResumeKind::StepIn || kind == ResumeKind::StepOver || kind == ResumeKind::StepOut) {
		step_.kind = kind;
		step_.thread = L;
		int depth = 0;
		lua_Debug ar;
		while (lua_getstack(L, depth, &ar)) ++depth;
		step_.depth = depth;
		lua_pushthread(L);
		step_.anchorRef = luaL_ref(L, LUA_REGISTRYINDEX);
	}
	const char* by = "continue";
	switch (kind) {
	case ResumeKind::StepIn:
	case ResumeKind::StepOver:
	case ResumeKind::StepOut: by = "step"; break;
	case ResumeKind::Detach: by = "disconnect"; break;
	default: break;
	}
	if (kind != ResumeKind::Detach) {
		core.Emit("resumed", {{"vmId", id_}, {"pauseId", pauseId}, {"by", by}});
	}
	auto after = std::move(afterResumed_);
	afterResumed_.clear();
	for (auto& fn : after) fn();
	inPauseLoop_ = false;
}

void Vm::BreakHere(lua_State* L) {
	if (inPauseLoop_) return;
	if (pending_.load() != 0) ProcessPending(L);
	if (!active_.load()) return;
	EnterPause(L, "hostBreak", {}, "宿主调用了 breakHere");
}

// ---------------------------------------------------------------- 出错即停（exceptions）

namespace {

// 包装后的 pcall / xpcall 用的错误处理函数：错误被接住，所以 uncaught = false
int CaughtErrorNotify(lua_State* L) {
	auto vm = Core::Get().FindVmByState(L);
	if (vm) vm->OnLuaError(L, 1, false);
	lua_settop(L, 1);
	return 1;
}

// 包装用 xpcall 写，保留 5.2+ 的 yield 能力；处理函数先通知（栈还在），再交给用户自己的处理函数
#if EMMY_LUA_VERSION_NUM >= 502
const char kWrapChunk[] = R"(local xpcall, notify = ...
local function pcall2(f, ...) return xpcall(f, notify, ...) end
local function xpcall2(f, h, ...)
  return xpcall(f, function(e) notify(e) return h(e) end, ...)
end
return pcall2, xpcall2
)";
#else
const char kWrapChunk[] = R"(local xpcall, notify = ...
local select, unpack = select, unpack
local function pcall2(f, ...)
  local n, a = select('#', ...), {...}
  return xpcall(function() return f(unpack(a, 1, n)) end, notify)
end
local function xpcall2(f, h)
  return xpcall(f, function(e) notify(e) return h(e) end)
end
return pcall2, xpcall2
)";
#endif

const char kWrapChunkName[] = "=(emmy)";

} // namespace

void Vm::InstallErrorWrappers(lua_State* L, bool enable) {
	const int top = lua_gettop(L);
	lua::PushGlobalTable(L);
	const int g = lua_gettop(L);
	lua_getfield(L, LUA_REGISTRYINDEX, "emmy.pcall.original");
	const bool wrapped = lua_type(L, -1) == LUA_TFUNCTION;
	lua_pop(L, 1);
	if (enable && !wrapped) {
		lua_getfield(L, g, "pcall");
		lua_getfield(L, g, "xpcall");
		if (lua_type(L, -1) != LUA_TFUNCTION || lua_type(L, -2) != LUA_TFUNCTION) {
			lua_settop(L, top);
			return;
		}
		if (lua::LoadBuffer(L, kWrapChunk, sizeof(kWrapChunk) - 1, kWrapChunkName) != LUA_OK) {
			lua_settop(L, top);
			return;
		}
		lua_pushvalue(L, -2); // 原 xpcall
		lua_pushcfunction(L, &CaughtErrorNotify);
		if (lua_pcall(L, 2, 2, 0) != LUA_OK) {
			lua_settop(L, top);
			return;
		}
		// 栈：g, pcall, xpcall, pcall2, xpcall2
		lua_setfield(L, g, "xpcall");
		lua_setfield(L, g, "pcall");
		lua_setfield(L, LUA_REGISTRYINDEX, "emmy.xpcall.original");
		lua_setfield(L, LUA_REGISTRYINDEX, "emmy.pcall.original");
	} else if (!enable && wrapped) {
		lua_getfield(L, LUA_REGISTRYINDEX, "emmy.pcall.original");
		lua_setfield(L, g, "pcall");
		lua_getfield(L, LUA_REGISTRYINDEX, "emmy.xpcall.original");
		lua_setfield(L, g, "xpcall");
		lua_pushnil(L);
		lua_setfield(L, LUA_REGISTRYINDEX, "emmy.pcall.original");
		lua_pushnil(L);
		lua_setfield(L, LUA_REGISTRYINDEX, "emmy.xpcall.original");
	}
	lua_settop(L, top);
	errorsWrapped_ = enable;
}

void Vm::SyncErrorWrappers(lua_State* L) {
	const bool want = active_.load() && Core::Get().options().WantCaughtErrors();
	if (want != errorsWrapped_) InstallErrorWrappers(L, want);
}

bool Vm::IsInsideLuaPcall(lua_State* L) {
	lua_Debug ar;
	if (!lua_getstack(L, 0, &ar)) return false;
	lua_getinfo(L, "f", &ar);
	bool inside = false;
	for (const char* key : {"emmy.pcall.original", "emmy.xpcall.original"}) {
		lua_getfield(L, LUA_REGISTRYINDEX, key);
		if (lua_type(L, -1) != LUA_TFUNCTION) {
			lua_pop(L, 1);
			continue;
		}
		inside = inside || lua_rawequal(L, -1, -2);
		lua_pop(L, 1);
	}
	if (!inside) {
		// 没包装时比较全局的 pcall / xpcall
		lua::PushGlobalTable(L);
		for (const char* name : {"pcall", "xpcall"}) {
			lua_getfield(L, -1, name);
			inside = inside || lua_rawequal(L, -1, -3);
			lua_pop(L, 1);
		}
		lua_pop(L, 1);
	}
	lua_pop(L, 1);
	return inside;
}

void Vm::OnLuaError(lua_State* L, int msgIdx, bool uncaught) {
	Core& core = Core::Get();
	const int mode = core.options().exceptions.load();
	const int collect = core.options().collectErrors.load();
	const bool wantPause = mode == 2 || (mode == 1 && uncaught);
	const bool wantCollect = collect == 2 || (collect == 1 && uncaught);
	if (!wantPause && !wantCollect) return;
	// 暂停循环里（求值、显示脚本里的 pcall 出错）不能处理待处理标志：会把「断开」标志取走，暂停循环就醒不过来
	if (inPauseLoop_ || inspector_.evaluating()) return;
	if (pending_.load() != 0) ProcessPending(L);
	if (!active_.load()) return;
	msgIdx = lua::AbsIndex(L, msgIdx);
	std::string message;
	const int mt = lua_type(L, msgIdx);
	if (mt == LUA_TSTRING || mt == LUA_TNUMBER) {
		lua_pushvalue(L, msgIdx);
		size_t len;
		const char* s = lua_tolstring(L, -1, &len);
		message.assign(s, std::min<size_t>(len, 4096));
		lua_pop(L, 1);
	} else {
		message = "(错误对象) " + inspector_.Summarize(L, msgIdx, 256);
	}
	// 栈顶的 C 函数（错误处理函数、error、assert）和调试器自己的包装不显示，从第一个 Lua 帧开始
	int skip = 0;
	lua_Debug ar;
	for (int level = 0; lua_getstack(L, level, &ar); ++level) {
		lua_getinfo(L, "S", &ar);
		const bool own = ar.source && std::strcmp(ar.source, kWrapChunkName) == 0;
		const bool c = ar.what && std::strcmp(ar.what, "C") == 0;
		if (!own && !c) {
			skip = level;
			break;
		}
	}
	auto buildTraceback = [&]() {
		std::string traceback;
		int lines = 0;
		lua_Debug fr;
		for (int level = skip; lua_getstack(L, level, &fr) && lines < 32; ++level, ++lines) {
			lua_getinfo(L, "nSl", &fr);
			if (fr.source && std::strcmp(fr.source, kWrapChunkName) == 0) continue;
			traceback += "\t";
			traceback += fr.short_src;
			if (fr.currentline > 0) traceback += ":" + std::to_string(fr.currentline);
			traceback += ": in ";
			if (fr.name) traceback += std::string("function '") + fr.name + "'";
			else if (fr.what && std::strcmp(fr.what, "main") == 0) traceback += "main chunk";
			else traceback += "?";
			traceback += "\n";
		}
		return traceback;
	};
	if (wantCollect) {
		// 错误聚合：位置取第一个 Lua 帧；同一条之后每秒最多推一次计数，取栈只在第一次
		std::string chunkName;
		int line = 0;
		lua_Debug top;
		if (lua_getstack(L, skip, &top)) {
			lua_getinfo(L, "Sl", &top);
			if (top.source) chunkName = top.source;
			line = top.currentline;
		}
		json ev = core.errors().Record(id_, chunkName, line, message, uncaught, WallMs(), buildTraceback);
		if (!ev.is_null()) core.Emit("luaError", std::move(ev));
	}
	if (!wantPause) return;
	json exc = {{"message", message}, {"traceback", buildTraceback()}, {"uncaught", uncaught}};
	const std::string desc = (uncaught ? "未处理的错误：" : "出错（已被 pcall 接住）：") + message.substr(0, 200);
	EnterPause(L, "exception", {}, desc.c_str(), std::move(exc), skip);
}

// ---------------------------------------------------------------- 输出捕获

namespace {

int CapturedPrint(lua_State* L) {
	int n = lua_gettop(L);
	std::string text;
	lua_getglobal(L, "tostring");
	for (int i = 1; i <= n; ++i) {
		lua_pushvalue(L, -1);
		lua_pushvalue(L, i);
		lua_call(L, 1, 1);
		size_t len = 0;
		const char* s = lua_tolstring(L, -1, &len);
		if (i > 1) text += '\t';
		if (s) text.append(s, len);
		lua_pop(L, 1);
	}
	lua_pop(L, 1);
	text += '\n';
	if (text.size() > 64 * 1024) text.resize(64 * 1024);
	auto vm = Core::Get().FindVmByState(L);
	if (vm) vm->EmitOutput(L, text);
	// 仍然调用原来的 print
	lua_pushvalue(L, lua_upvalueindex(1));
	if (lua_type(L, -1) == LUA_TFUNCTION) {
		lua_insert(L, 1);
		lua_call(L, n, 0);
	}
	return 0;
}

} // namespace

void Vm::EmitOutput(lua_State* L, const std::string& text) {
	json body = {{"category", "stdout"}, {"text", text}, {"vmId", id_}};
	lua_Debug ar;
	if (lua_getstack(L, 1, &ar)) {
		lua_getinfo(L, "Sl", &ar);
		if (ar.source) body["chunkName"] = ar.source;
		body["line"] = ar.currentline;
	}
	Core::Get().Emit("output", std::move(body));
}

void Vm::InstallOutputCapture(lua_State* L, bool enable) {
	lua::PushGlobalTable(L);
	int g = lua_gettop(L);
	lua_getfield(L, LUA_REGISTRYINDEX, "emmy.print.original");
	bool hasOriginal = lua_type(L, -1) == LUA_TFUNCTION;
	if (enable && !hasOriginal) {
		lua_pop(L, 1);
		lua_getfield(L, g, "print");
		lua_pushvalue(L, -1);
		lua_setfield(L, LUA_REGISTRYINDEX, "emmy.print.original");
		lua_pushcclosure(L, &CapturedPrint, 1);
		lua_setfield(L, g, "print");
	} else if (!enable && hasOriginal) {
		lua_setfield(L, g, "print");
		lua_pushnil(L);
		lua_setfield(L, LUA_REGISTRYINDEX, "emmy.print.original");
	} else {
		lua_pop(L, 1);
	}
	lua_pop(L, 1);
	outputCaptured_ = enable;
}

// ---------------------------------------------------------------- helpers

void Vm::ApplyHelpers(lua_State* L) {
	json result;
	LoadHelpers(L, result);
}

void Vm::LoadHelpers(lua_State* L, json& result) {
	auto helpers = Core::Get().Helpers();
	result = {{"vmId", id_}, {"ok", true}};
	// 重新加载前先清掉上一份 formatter 调度函数
	lua_pushstring(L, "emmy.formatter.dispatch");
	lua_pushnil(L);
	lua_rawset(L, LUA_REGISTRYINDEX);
	if (!helpers || helpers->first.empty()) return;
	int top = lua_gettop(L);
	// 私有模块表：{ name = source 函数 }，helper 里的 require 先查这里
	lua_newtable(L);
	int mods = lua_gettop(L);
	std::string firstError;
	for (const HelperScript& s : helpers->first) {
		std::string chunk = "@emmy/" + s.name + ".lua";
		if (lua::LoadBuffer(L, s.source.data(), s.source.size(), chunk.c_str()) != LUA_OK) {
			if (firstError.empty()) firstError = lua_tostring(L, -1) ? lua_tostring(L, -1) : "语法错误";
			lua_pop(L, 1);
			continue;
		}
		lua_setfield(L, mods, s.name.c_str());
	}
	static const char* kLoader =
		"local mods, mainName = ...\n"
		"local loaded = {}\n"
		"local globalRequire = require\n"
		"local function emmyRequire(name)\n"
		"  local v = loaded[name]\n"
		"  if v ~= nil then return v end\n"
		"  local f = mods[name]\n"
		"  if not f then return globalRequire(name) end\n"
		"  loaded[name] = true\n"
		"  local r = f(name)\n"
		"  if r == nil then r = true end\n"
		"  loaded[name] = r\n"
		"  return r\n"
		"end\n"
		"local env = setmetatable({ require = emmyRequire }, { __index = _G, __newindex = _G })\n"
		"for _, f in pairs(mods) do\n"
		"  if setfenv then setfenv(f, env) else debug.setupvalue(f, 1, env) end\n"
		"end\n"
		"return emmyRequire(mainName)\n";
	if (lua::LoadBuffer(L, kLoader, std::strlen(kLoader), "=(emmy helper loader)") != LUA_OK) {
		firstError = lua_tostring(L, -1) ? lua_tostring(L, -1) : "loader 编译失败";
	} else {
		lua_pushvalue(L, mods);
		lua_pushstring(L, helpers->second.c_str());
		std::string err;
		if (lua_pcall(L, 2, 1, 0) != LUA_OK) {
			if (firstError.empty()) firstError = lua_tostring(L, -1) ? lua_tostring(L, -1) : "执行失败";
		} else if (lua_type(L, -1) == LUA_TTABLE) {
			// helper v2（variable-view 11.2）：主模块返回 emmy 表，调度函数放进 registry 给 Inspector 用；
			// 不放全局，不依赖 debug 库
			const int mod = lua_gettop(L);
			lua_pushstring(L, "__dispatch");
			lua_rawget(L, mod);
			if (lua_type(L, -1) == LUA_TFUNCTION) {
				lua_pushstring(L, "emmy.formatter.dispatch");
				lua_insert(L, -2);
				lua_rawset(L, LUA_REGISTRYINDEX);
			} else {
				lua_pop(L, 1);
			}
			// 某个类型脚本加载失败（emmy.loadErrors）：其余照常，失败的报到输出
			lua_pushstring(L, "loadErrors");
			lua_rawget(L, mod);
			if (lua_type(L, -1) == LUA_TTABLE) {
				const size_t n = lua::RawLen(L, -1);
				for (size_t i = 1; i <= n && firstError.empty(); ++i) {
					lua_rawgeti(L, -1, static_cast<lua_Integer>(i));
					if (lua_type(L, -1) == LUA_TSTRING) firstError = lua_tostring(L, -1);
					lua_pop(L, 1);
				}
			}
			lua_pop(L, 1);
		}
	}
	lua_settop(L, top);
	if (!firstError.empty()) {
		result["ok"] = false;
		result["message"] = firstError;
		Core::Get().Log("runtime", "warn", "VM " + std::to_string(id_) + " 加载类型显示脚本失败：" + firstError);
	}
}

} // namespace emmy
