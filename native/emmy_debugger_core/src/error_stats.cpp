#include "error_stats.h"

#include <algorithm>

namespace emmy {

using json = nlohmann::json;

json ErrorStats::ToJson(const Entry& e, bool withTraceback) {
	json j = {{"id", e.id}, {"vmId", e.vmId}, {"message", e.message}, {"count", e.count}, {"firstMs", e.firstMs}, {"lastMs", e.lastMs}};
	if (!e.chunkName.empty()) j["chunkName"] = e.chunkName;
	if (e.line > 0) j["line"] = e.line;
	if (e.uncaught) j["uncaught"] = true;
	if (withTraceback && !e.traceback.empty()) j["traceback"] = e.traceback;
	return j;
}

json ErrorStats::Record(uint64_t vmId, const std::string& chunkName, int line, const std::string& message, bool uncaught,
	int64_t nowMs, const std::function<std::string()>& traceback) {
	const std::string msg = message.size() > kMaxMessage ? message.substr(0, kMaxMessage) : message;
	// 同一位置、同一消息算一条；接住与否不同也分开（处理方式不一样）
	std::string key = std::to_string(vmId) + '\x1f' + chunkName + '\x1f' + std::to_string(line) + '\x1f' + (uncaught ? '1' : '0') + '\x1f' + msg;
	std::lock_guard<std::mutex> lock(mutex_);
	auto it = entries_.find(key);
	if (it == entries_.end()) {
		if (entries_.size() >= kMaxEntries) {
			++dropped_;
			return nullptr;
		}
		Entry e;
		e.id = nextId_++;
		e.vmId = vmId;
		e.chunkName = chunkName;
		e.line = line;
		e.message = msg;
		e.uncaught = uncaught;
		e.count = 1;
		e.firstMs = e.lastMs = e.lastEmitMs = nowMs;
		if (traceback) e.traceback = traceback();
		auto& stored = entries_.emplace(std::move(key), std::move(e)).first->second;
		return ToJson(stored, true);
	}
	Entry& e = it->second;
	++e.count;
	e.lastMs = nowMs;
	if (nowMs - e.lastEmitMs < kEmitIntervalMs) return nullptr;
	e.lastEmitMs = nowMs;
	return ToJson(e, false);
}

json ErrorStats::Snapshot() const {
	std::vector<const Entry*> list;
	uint64_t dropped;
	json items = json::array();
	std::lock_guard<std::mutex> lock(mutex_);
	list.reserve(entries_.size());
	for (auto& kv : entries_) list.push_back(&kv.second);
	std::sort(list.begin(), list.end(), [](const Entry* a, const Entry* b) { return a->lastMs != b->lastMs ? a->lastMs > b->lastMs : a->id > b->id; });
	for (const Entry* e : list) items.push_back(ToJson(*e, true));
	dropped = dropped_;
	json out = {{"items", std::move(items)}};
	if (dropped) out["dropped"] = dropped;
	return out;
}

void ErrorStats::Clear() {
	std::lock_guard<std::mutex> lock(mutex_);
	entries_.clear();
	dropped_ = 0;
}

} // namespace emmy
