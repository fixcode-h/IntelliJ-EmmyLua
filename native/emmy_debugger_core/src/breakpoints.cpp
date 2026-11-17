#include "breakpoints.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>

namespace emmy {

namespace {

std::atomic<uint64_t> g_serial{0};

std::string_view Trim(std::string_view s) {
	while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.remove_prefix(1);
	while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.remove_suffix(1);
	return s;
}

bool ParseInt(std::string_view s, int64_t& out) {
	s = Trim(s);
	if (s.empty() || s.size() > 18) return false;
	int64_t v = 0;
	for (char c : s) {
		if (c < '0' || c > '9') return false;
		v = v * 10 + (c - '0');
	}
	out = v;
	return true;
}

} // namespace

uint64_t NextBreakpointSerial() {
	return ++g_serial;
}

bool ParseFunctionSpec(std::string_view text, Breakpoint& bp, std::string& err) {
	text = Trim(text);
	bp.funcQualifier.clear();
	bp.funcName.clear();
	bp.funcMethod = false;
	auto bad = [&](const std::string& why) {
		err = "函数断点写法不对（" + why + "），支持：Module.func、Class:Method、全局函数名 func";
		return false;
	};
	if (text.empty()) return bad("函数名为空");
	if (text.size() > 256) return bad("太长");
	auto isIdent = [](std::string_view seg) {
		if (seg.empty() || std::isdigit(static_cast<unsigned char>(seg[0]))) return false;
		for (char c : seg) {
			if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '_')) return false;
		}
		return true;
	};
	size_t colon = text.find(':');
	std::string_view qual;
	std::string_view name;
	if (colon != std::string_view::npos) {
		if (text.find(':', colon + 1) != std::string_view::npos) return bad("只能有一个 ':'");
		qual = text.substr(0, colon);
		name = text.substr(colon + 1);
		if (qual.empty()) return bad("':' 前面要写类名");
		bp.funcMethod = true;
	} else {
		size_t dot = text.rfind('.');
		if (dot != std::string_view::npos) {
			qual = text.substr(0, dot);
			name = text.substr(dot + 1);
		} else {
			name = text;
		}
	}
	if (!isIdent(name)) return bad("函数名「" + std::string(name) + "」不是标识符");
	size_t start = 0;
	while (!qual.empty() && start <= qual.size()) {
		size_t end = qual.find('.', start);
		std::string_view seg = qual.substr(start, end == std::string_view::npos ? std::string_view::npos : end - start);
		if (!isIdent(seg)) return bad("「" + std::string(seg) + "」不是标识符");
		if (end == std::string_view::npos) break;
		start = end + 1;
	}
	bp.funcQualifier = std::string(qual);
	bp.funcName = std::string(name);
	return true;
}

bool HitCondition::Check(int64_t count) const {
	switch (op) {
	case Op::None: return true;
	case Op::Eq: return count == n;
	case Op::Ge: return count >= n;
	case Op::Gt: return count > n;
	case Op::Mod: return n > 0 && count % n == 0;
	}
	return true;
}

bool HitCondition::Parse(std::string_view text, HitCondition& out, std::string& err) {
	text = Trim(text);
	out = HitCondition();
	if (text.empty()) return true;
	Op op = Op::Eq;
	if (text.substr(0, 2) == "==") { op = Op::Eq; text.remove_prefix(2); }
	else if (text.substr(0, 2) == ">=") { op = Op::Ge; text.remove_prefix(2); }
	else if (text.substr(0, 1) == ">") { op = Op::Gt; text.remove_prefix(1); }
	else if (text.substr(0, 1) == "%") { op = Op::Mod; text.remove_prefix(1); }
	int64_t n = 0;
	if (!ParseInt(text, n) || (op == Op::Mod && n == 0) || (op == Op::Eq && n == 0)) {
		err = "命中次数条件写法不对，支持：5、== 5、>= 5、> 5、% 3";
		return false;
	}
	out.op = op;
	out.n = n;
	return true;
}

bool ChunkMatch::HasLine(int line) const {
	for (const Breakpoint* bp : bps) {
		if (bp->line == line) return true;
	}
	return false;
}

BreakpointIndex::BreakpointIndex(uint64_t revision, std::vector<BreakpointPtr> bps, bool caseSensitive)
	: revision_(revision), caseSensitive_(caseSensitive), bps_(std::move(bps)) {
	// 相同 key（规范化后的 chunkName 或路径）的断点放进同一组，匹配时按组算
	for (const BreakpointPtr& bp : bps_) {
		if (bp->IsFunction()) {
			funcs_.push_back(bp);
			continue;
		}
		bool byChunk = bp->HasChunkName();
		const NormalizedPath* key = byChunk ? &bp->normChunk : &bp->normPath;
		Group* found = nullptr;
		for (Group& g : groups_) {
			if (g.byChunk == byChunk && g.key->text == key->text) {
				found = &g;
				break;
			}
		}
		if (!found) {
			groups_.push_back(Group{byChunk, key, {}});
			found = &groups_.back();
		}
		found->bps.push_back(bp.get());
	}
}

bool BreakpointIndex::AnyFor(uint64_t vmId) const {
	for (const BreakpointPtr& bp : bps_) {
		if (bp->vmId == 0 || bp->vmId == vmId) return true;
	}
	return false;
}

bool BreakpointIndex::AnyLineFor(uint64_t vmId) const {
	for (const BreakpointPtr& bp : bps_) {
		if (!bp->IsFunction() && (bp->vmId == 0 || bp->vmId == vmId)) return true;
	}
	return false;
}

ChunkMatch BreakpointIndex::Match(std::string_view chunkSource, uint64_t vmId) const {
	ChunkMatch result;
	if (bps_.empty() || chunkSource.empty() || chunkSource[0] == '=') {
		return result;
	}
	NormalizedPath chunk = NormalizePath(chunkSource, caseSensitive_);
	bool isFile = IsFileChunk(chunkSource);
	std::vector<const Group*> best;
	size_t bestScore = 0;
	for (const Group& g : groups_) {
		if (g.byChunk) {
			if (g.key->text == chunk.text) {
				for (const Breakpoint* bp : g.bps) result.bps.push_back(bp);
			}
			continue;
		}
		if (!isFile) continue;
		size_t score = SuffixMatchSegments(*g.key, chunk);
		if (score == 0) continue;
		if (score > bestScore) {
			bestScore = score;
			best.clear();
			best.push_back(&g);
		} else if (score == bestScore) {
			best.push_back(&g);
		}
	}
	if (best.size() == 1) {
		for (const Breakpoint* bp : best[0]->bps) result.bps.push_back(bp);
	} else if (best.size() > 1) {
		for (const Group* g : best)
			for (const Breakpoint* bp : g->bps) result.ambiguous.push_back(bp);
	}
	auto vmFilter = [vmId](const Breakpoint* bp) { return bp->vmId != 0 && bp->vmId != vmId; };
	result.bps.erase(std::remove_if(result.bps.begin(), result.bps.end(), vmFilter), result.bps.end());
	result.ambiguous.erase(std::remove_if(result.ambiguous.begin(), result.ambiguous.end(), vmFilter), result.ambiguous.end());
	std::stable_sort(result.bps.begin(), result.bps.end(), [](const Breakpoint* a, const Breakpoint* b) { return a->line < b->line; });
	return result;
}

bool MatchSingle(const Breakpoint& bp, std::string_view chunkSource, bool caseSensitive) {
	if (chunkSource.empty() || chunkSource[0] == '=') return false;
	NormalizedPath chunk = NormalizePath(chunkSource, caseSensitive);
	if (bp.HasChunkName()) {
		return NormalizePath(bp.chunkName, caseSensitive).text == chunk.text;
	}
	if (!IsFileChunk(chunkSource)) return false;
	return SuffixMatchSegments(NormalizePath(bp.path, caseSensitive), chunk) > 0;
}

} // namespace emmy
