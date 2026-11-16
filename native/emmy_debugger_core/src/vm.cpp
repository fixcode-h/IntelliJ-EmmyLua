#include "vm.h"

#include "core.h"
#include "lua_internal.h"
#include "session.h"

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

} // namespace

Vm::Vm(uint64_t id, lua_State* L, std::string discovery)
	: id_(id), mainL_(lua::MainThreadOf(L)), g_(lua::GlobalStateOf(L)), discovery_(std::move(discovery)), mux_(mainL_), inspector_(*this) {
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
	inspector_.ResetConditionCache(mainL_);
	ClearStep(mainL_);
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
	SourceEntry* e = Lookup(f.source, 0);
	if (!e || !e->hot) return false;
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
	if (pending_.load(std::memory_order_acquire) != 0) {
		ProcessPending(L);
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
		if (lua::HookFuncInfo(L, ar, false, f) && IsHotFunc(f)) return BaseMask() | region | LUA_MASKLINE;
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
		SourceEntry* e = hasInfo && !f.isC ? Lookup(f.source, 0) : nullptr;
		bool hot = hasInfo && IsHotFunc(f);
		const uint64_t pausesBefore = pauseCounter_;
		if (pauseAtLine_) {
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
		if (pauseCounter_ != pausesBefore) hot = hasInfo && IsHotFunc(f);
		if (hot) return BaseMask() | region | LUA_MASKLINE;
		return BaseMask() | (curMask & LUA_MASKRET ? region : 0);
	}
	// COUNT：保持当前的区域状态
	AdaptCount();
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

void Vm::OnBreakLine(lua_State* L, lua_Debug* ar, SourceEntry* entry) {
	Core& core = Core::Get();
	Options& opt = core.options();
	const int line = ar->currentline;
	std::vector<std::string> pauseIds;
	bool rtlHit = entry->rtl && entry->rtlLine == line;
	for (const Breakpoint* bp : entry->match.bps) {
		if (bp->line != line) continue;
		if (!bp->condition.empty()) {
			bool ok = false;
			std::string err;
			if (!inspector_.EvalCondition(L, bp->serial, bp->condition, opt.evalTimeoutMs.load(), ok, err)) {
				auto& reported = reported_[bp->serial];
				if (reported != "invalid") {
					reported = "invalid";
					core.Emit("breakpointStatus", {{"id", bp->id}, {"vmId", id_}, {"state", "invalid"}, {"message", "条件求值出错：" + err}});
				}
				continue;
			}
			if (!ok) continue;
		}
		int64_t& count = hitCounts_[bp->serial];
		++count;
		if (!bp->hitCondition.Check(count)) continue;
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
			core.Emit("output", {{"category", "logpoint"}, {"text", out}, {"vmId", id_}, {"chunkName", entry->chunk}, {"line", line}});
		}
		if (!bp->Pauses() || !bp->capture.empty()) {
			if (!bp->Pauses()) {
				// 每个断点每秒最多 100 条
				int64_t now = WallMs();
				int64_t& winStart = hitWindowStart_[bp->serial];
				int& winCount = hitWindowCount_[bp->serial];
				if (now - winStart >= 1000) { winStart = now; winCount = 0; }
				if (++winCount > 100) { ++hitDropped_[bp->serial]; continue; }
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
		if (bp->Pauses()) pauseIds.push_back(bp->id);
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

void Vm::EnterPause(lua_State* L, const char* reason, std::vector<std::string> bpIds, const char* description) {
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
	inspector_.BeginPause(L);

	json body = {{"vmId", id_}, {"pauseId", pauseId}, {"threadId", ThreadIdOf(L)}, {"reason", reason}, {"top", inspector_.TopFrame()}};
	if (!bpIds.empty()) {
		body["breakpointIds"] = bpIds;
		body["description"] = "命中断点 " + bpIds.front();
	} else if (description) {
		body["description"] = description;
	}
	core.Emit("paused", std::move(body));

	resumeKind_ = ResumeKind::None;
	while (resumeKind_ == ResumeKind::None) {
		std::deque<Command> cmds;
		{
			std::unique_lock<std::mutex> lock(cmdMutex_);
			cmdCv_.wait(lock, [&] {
				return !commands_.empty() || (pending_.load() & (kFlagDetach | kFlagSession)) != 0;
			});
			cmds.swap(commands_);
		}
		for (Command& c : cmds) c(L);
		uint32_t p = pending_.load();
		if ((p & (kFlagDetach | kFlagSession)) && resumeKind_ == ResumeKind::None) {
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
	if (pending_.load() != 0) ProcessPending(L);
	if (!active_.load()) return;
	EnterPause(L, "hostBreak", {}, "宿主调用了 breakHere");
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
