#include "profiler.h"

#include "vm.h"

#include <algorithm>
#include <cstring>

namespace emmy {

namespace {

constexpr uint32_t kNoFunc = 0xffffffffu;

int64_t NowNs() {
	return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

uint64_t Fnv(const char* s, uint64_t h = 1469598103934665603ull) {
	if (!s) return h;
	for (; *s; ++s) {
		h ^= static_cast<unsigned char>(*s);
		h *= 1099511628211ull;
	}
	return h;
}

uint64_t MixInt(uint64_t h, int v) {
	h ^= static_cast<uint64_t>(static_cast<uint32_t>(v)) + 0x9e3779b97f4a7c15ull + (h << 6) + (h >> 2);
	return h;
}

// 折叠栈格式里 ';' 分隔帧、最后一个空格前是帧序列，帧名里出现这些字符会让 flamegraph.pl / speedscope 解析错位
std::string SanitizeFrame(std::string s) {
	for (char& c : s) {
		if (c == ';' || c == '\n' || c == '\r' || c == '\t') c = '_';
	}
	return s;
}

} // namespace

Profiler::~Profiler() {
	Stop();
}

void Profiler::Start(int intervalInstructions, int durationMs) {
	Stop();
	const int interval = std::max(kMinInterval, std::min(kMaxInterval, intervalInstructions > 0 ? intervalInstructions : kDefaultInterval));
	{
		std::lock_guard<std::mutex> lock(mutex_);
		funcs_.clear();
		funcIndex_.clear();
		stacks_.clear();
		samples_ = pausedSkipped_ = truncated_ = other_ = 0;
		overheadNs_ = 0;
		interval_ = interval;
		startedAt_ = std::chrono::steady_clock::now();
		stoppedAt_ = startedAt_;
		everStarted_ = true;
	}
	deadlineNs_.store(durationMs > 0 ? NowNs() + static_cast<int64_t>(durationMs) * 1000000 : 0, std::memory_order_relaxed);
	// Register 内部会 Kick：马上给主线程挂上 COUNT，并在下一次事件时扩散到所有协程
	handle_.store(mux_.Register(&Profiler::SampleEntry, LUA_MASKCOUNT, interval, this), std::memory_order_release);
}

void Profiler::Stop() {
	const uint64_t h = handle_.exchange(0, std::memory_order_acq_rel);
	if (!h) return;
	mux_.Unregister(h);
	// 让其他协程上的钩子在下一次事件时摘掉（Unregister 只改静态掩码，扩散靠 Kick 置的标志）
	mux_.Kick();
	std::lock_guard<std::mutex> lock(mutex_);
	stoppedAt_ = std::chrono::steady_clock::now();
}

void Profiler::SampleEntry(lua_State* L, lua_Debug*, void* ud) {
	auto* self = static_cast<Profiler*>(ud);
	if (!self->running()) return; // 刚停下、客户端表还没同步过来的那一次
	const int64_t t0 = NowNs();
	const int64_t deadline = self->deadlineNs_.load(std::memory_order_relaxed);
	if (deadline != 0 && t0 >= deadline) {
		self->Stop();
		return;
	}
	self->Sample(L);
	const int64_t t1 = NowNs();
	std::lock_guard<std::mutex> lock(self->mutex_);
	self->overheadNs_ += t1 - t0;
}

uint32_t Profiler::Intern(lua_State* L, lua_Debug* ar) {
	const bool isC = ar->what && ar->what[0] == 'C';
	const char* cname = nullptr;
	if (isC) {
		// 所有 C 函数的 source 都是 "=[C]"、linedefined 都是 -1，只能按名字区分（名字来自调用处，取不到时是 "?"）
		lua_getinfo(L, "n", ar);
		cname = ar->name ? ar->name : "?";
	}
	uint64_t h = MixInt(Fnv(ar->source), ar->linedefined);
	if (isC) h = Fnv(cname, h);
	auto& bucket = funcIndex_[h];
	for (uint32_t idx : bucket) {
		Func& f = funcs_[idx];
		if (f.line == ar->linedefined && f.isC == isC && f.source == (ar->source ? ar->source : "") && (!isC || f.name == cname)) {
			if (!f.named && f.nameTries < 8) {
				// 第一次见到时调用者是 C（宿主 lua_pcall 进来的入口函数），换个调用处也许能拿到名字；试有限次，不让匿名函数每次都付 "n" 的代价
				++f.nameTries;
				lua_getinfo(L, "n", ar);
				if (ar->name) {
					f.name = ar->name;
					f.named = true;
				}
			}
			return idx;
		}
	}
	if (funcs_.size() >= kMaxFunctions) {
		// 函数太多（例如大量 load 出来的匿名 chunk）：之后新出现的都并进一个「<其他>」
		static const char* kOther = "<其他>";
		auto& ob = funcIndex_[0];
		for (uint32_t idx : ob) if (funcs_[idx].name == kOther && funcs_[idx].source.empty()) return idx;
		Func f;
		f.name = kOther;
		f.line = 0;
		funcs_.push_back(std::move(f));
		ob.push_back(static_cast<uint32_t>(funcs_.size() - 1));
		return static_cast<uint32_t>(funcs_.size() - 1);
	}
	Func f;
	f.source = ar->source ? ar->source : "";
	f.line = ar->linedefined;
	f.isC = isC;
	if (isC) {
		f.name = cname;
	} else if (ar->what && std::strcmp(ar->what, "main") == 0) {
		f.name = "<主 chunk>";
	} else if (ar->what && std::strcmp(ar->what, "tail") == 0) {
		f.name = "<尾调用>"; // 5.1：被尾调用替换掉的帧
	} else {
		// 名字来自这一次的调用处，只在第一次见到时取（"n" 要分析调用者的字节码，不每次都做）
		lua_getinfo(L, "n", ar);
		f.named = ar->name != nullptr;
		f.name = ar->name ? ar->name : "<匿名>";
	}
	funcs_.push_back(std::move(f));
	bucket.push_back(static_cast<uint32_t>(funcs_.size() - 1));
	return static_cast<uint32_t>(funcs_.size() - 1);
}

void Profiler::Sample(lua_State* L) {
	std::lock_guard<std::mutex> lock(mutex_);
	if (vm_.paused()) {
		// 暂停在断点 / 单步时 VM 线程只跑调试器自己的东西，不算游戏的耗时
		++pausedSkipped_;
		return;
	}
	frameBuf_.clear();
	lua_Debug ar;
	int level = 0;
	bool truncated = false;
	for (; level < kMaxDepth; ++level) {
		if (!lua_getstack(L, level, &ar)) break;
		if (!lua_getinfo(L, "S", &ar)) continue;
		frameBuf_.push_back(Intern(L, &ar));
	}
	if (level == kMaxDepth && lua_getstack(L, level, &ar)) truncated = true;
	if (frameBuf_.empty()) return;
	++samples_;
	const uint64_t stamp = samples_;
	Func& leaf = funcs_[frameBuf_.front()];
	++leaf.self;
	for (uint32_t idx : frameBuf_) {
		Func& f = funcs_[idx];
		if (f.lastSample != stamp) {
			f.lastSample = stamp;
			++f.total;
		}
	}
	if (truncated) ++truncated_;
	// 键：根 → 叶的函数下标原始字节；截断的栈前面加一个 kNoFunc 表示「更深的部分被截掉了」
	keyBuf_.clear();
	if (truncated) keyBuf_.append(reinterpret_cast<const char*>(&kNoFunc), sizeof(uint32_t));
	for (auto it = frameBuf_.rbegin(); it != frameBuf_.rend(); ++it) {
		keyBuf_.append(reinterpret_cast<const char*>(&*it), sizeof(uint32_t));
	}
	auto it = stacks_.find(keyBuf_);
	if (it != stacks_.end()) {
		++it->second;
	} else if (stacks_.size() < kMaxStacks) {
		stacks_.emplace(keyBuf_, 1);
	} else {
		++other_;
	}
}

std::string Profiler::Label(const Func& f) const {
	if (f.isC) return SanitizeFrame(f.name + " [C]");
	if (f.source.empty()) return SanitizeFrame(f.name);
	std::string src = f.source;
	if (!src.empty() && (src[0] == '@' || src[0] == '=')) src.erase(0, 1);
	return SanitizeFrame(f.name + " (" + src + ":" + std::to_string(f.line) + ")");
}

ProfileSnapshot Profiler::Snapshot() {
	ProfileSnapshot s;
	s.running = running();
	std::lock_guard<std::mutex> lock(mutex_);
	s.samples = samples_;
	s.pausedSkipped = pausedSkipped_;
	s.truncatedStacks = truncated_;
	s.otherSamples = other_;
	s.intervalInstructions = interval_;
	s.overheadMs = static_cast<double>(overheadNs_) / 1e6;
	if (everStarted_) {
		auto end = s.running ? std::chrono::steady_clock::now() : stoppedAt_;
		s.durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(end - startedAt_).count();
	}
	s.functions.reserve(funcs_.size());
	std::vector<std::string> labels;
	labels.reserve(funcs_.size());
	for (const Func& f : funcs_) {
		labels.push_back(Label(f));
		if (f.total == 0) continue;
		ProfileFunctionRow r;
		r.key = f.source + "\n" + std::to_string(f.line) + "\n" + (f.isC ? f.name : std::string());
		r.fn = f.name;
		r.source = f.source;
		r.line = f.line;
		r.selfSamples = f.self;
		r.totalSamples = f.total;
		s.functions.push_back(std::move(r));
	}
	s.stacks.reserve(stacks_.size() + 1);
	for (const auto& kv : stacks_) {
		std::string folded;
		const size_t n = kv.first.size() / sizeof(uint32_t);
		for (size_t i = 0; i < n; ++i) {
			uint32_t idx;
			std::memcpy(&idx, kv.first.data() + i * sizeof(uint32_t), sizeof(uint32_t));
			if (!folded.empty()) folded.push_back(';');
			folded += idx == kNoFunc ? std::string("<截断>") : labels[idx];
		}
		s.stacks.emplace_back(std::move(folded), kv.second);
	}
	if (other_) s.stacks.emplace_back("<其他>", other_);
	return s;
}

} // namespace emmy
