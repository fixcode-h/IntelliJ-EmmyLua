#include "host_value.h"

#include "lua_shim.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>

namespace emmy::host {

namespace {

struct Entry {
	uint64_t id = 0;
	EmmyHostValueProviderV2 p{};
	std::string name;
};

std::mutex g_mutex;
std::vector<Entry> g_providers;
std::atomic<size_t> g_count{0};
uint64_t g_nextId = 1;

std::vector<Entry> Snapshot() {
	std::lock_guard<std::mutex> lk(g_mutex);
	return g_providers;
}

bool Find(uint64_t id, Entry& out) {
	std::lock_guard<std::mutex> lk(g_mutex);
	for (const Entry& e : g_providers) {
		if (e.id == id) {
			out = e;
			return true;
		}
	}
	return false;
}

void EMMY_HOST_API_CALL Write(void* sink, const char* utf8, size_t length) {
	if (sink && utf8 && length) static_cast<std::string*>(sink)->append(utf8, length);
}

uint64_t NowUnixMs() {
	return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::system_clock::now().time_since_epoch()).count());
}

EmmyHostValueQuery MakeQuery(lua_State* L, int idx, const char* hostRef, uint64_t vmId, uint64_t pauseId,
	uint32_t maxValueLength, std::string* sink) {
	EmmyHostValueQuery q{};
	q.size = sizeof(q);
	q.version = EMMY_HOST_VALUE_ABI_VERSION;
	q.vmId = vmId;
	q.pauseId = pauseId;
	q.L = L;
	q.index = idx;
	q.hostRef = hostRef;
	q.start = 0;
	q.count = 5000;
	q.maxValueLength = maxValueLength;
	q.deadlineUnixMillis = NowUnixMs() + 200; // 单次回调的预算，宿主尽量遵守
	q.sink = sink;
	q.write = &Write;
	return q;
}

std::string Str(const json& j, const char* key) {
	auto it = j.find(key);
	return it != j.end() && it->is_string() ? it->get<std::string>() : std::string();
}

} // namespace

bool HasProviders() {
	return g_count.load(std::memory_order_acquire) != 0;
}

bool Describe(lua_State* L, int idx, uint64_t vmId, uint64_t pauseId, uint32_t maxValueLength, Described& out) {
	idx = lua::AbsIndex(L, idx);
	for (const Entry& e : Snapshot()) {
		if (!e.p.describe) continue;
		std::string buf;
		EmmyHostValueQuery q = MakeQuery(L, idx, nullptr, vmId, pauseId, maxValueLength, &buf);
		const int top = lua_gettop(L);
		int status = e.p.describe(e.p.userData, &q);
		lua_settop(L, top); // 宿主没把栈恢复也不影响后面
		if (status != EMMY_HOST_VALUE_OK && status != EMMY_HOST_VALUE_OBJECT_INVALID) continue;
		json j = json::parse(buf, nullptr, false);
		if (j.is_discarded() || !j.is_object()) {
			if (status == EMMY_HOST_VALUE_OBJECT_INVALID) j = json::object();
			else continue; // 解析失败当作未认领
		}
		out.providerId = e.id;
		out.valid = status != EMMY_HOST_VALUE_OBJECT_INVALID && j.value("valid", true);
		out.expandable = out.valid && j.value("expandable", false);
		out.typeName = Str(j, "typeName");
		out.summary = Str(j, "summary");
		out.hint = Str(j, "hint");
		out.kind = Str(j, "kind");
		return true;
	}
	return false;
}

bool ListChildren(uint64_t providerId, lua_State* L, int idx, const std::string& hostRef, uint64_t vmId,
	uint64_t pauseId, uint32_t start, uint32_t count, uint32_t maxValueLength, json& out, std::string& err) {
	Entry e;
	if (!Find(providerId, e) || !e.p.listChildren) {
		err = "提供者已注销";
		return false;
	}
	std::string buf;
	if (!hostRef.empty()) idx = 0;
	else idx = lua::AbsIndex(L, idx);
	EmmyHostValueQuery q = MakeQuery(L, idx, hostRef.empty() ? nullptr : hostRef.c_str(),
		vmId, pauseId, maxValueLength, &buf);
	q.start = start;
	q.count = count;
	const int top = lua_gettop(L);
	int status = e.p.listChildren(e.p.userData, &q);
	lua_settop(L, top);
	if (status == EMMY_HOST_VALUE_OBJECT_INVALID) {
		out = {{"valid", false}, {"children", json::array()}, {"total", 0}};
		return true;
	}
	if (status != EMMY_HOST_VALUE_OK) {
		err = e.name + " 没有返回子项（状态 " + std::to_string(status) + "）";
		return false;
	}
	out = json::parse(buf, nullptr, false);
	if (out.is_discarded() || !out.is_object()) {
		err = e.name + " 返回的子项不是合法 JSON";
		return false;
	}
	return true;
}

void ReleasePause(uint64_t vmId, uint64_t pauseId, const std::vector<uint64_t>& usedProviders) {
	for (uint64_t id : usedProviders) {
		Entry e;
		if (Find(id, e) && e.p.releasePause) e.p.releasePause(e.p.userData, vmId, pauseId);
	}
}

} // namespace emmy::host

using namespace emmy::host;

extern "C" uint64_t EMMY_HOST_API_CALL Emmy_RegisterHostValueProviderV2(const EmmyHostValueProviderV2* provider) {
	if (!provider || provider->version != EMMY_HOST_VALUE_ABI_VERSION) return 0;
	if (provider->size < sizeof(EmmyHostValueProviderV2) || !provider->describe) return 0;
	std::lock_guard<std::mutex> lk(g_mutex);
	Entry e;
	e.id = g_nextId++;
	e.p = *provider;
	e.name = provider->name ? provider->name : "host";
	e.p.name = nullptr; // 用自己复制的 name
	g_providers.push_back(e);
	g_count.store(g_providers.size(), std::memory_order_release);
	return e.id;
}

extern "C" int EMMY_HOST_API_CALL Emmy_UnregisterHostValueProviderV2(uint64_t providerId) {
	std::lock_guard<std::mutex> lk(g_mutex);
	auto it = std::find_if(g_providers.begin(), g_providers.end(), [&](const Entry& e) { return e.id == providerId; });
	if (it == g_providers.end()) return -1;
	g_providers.erase(it);
	g_count.store(g_providers.size(), std::memory_order_release);
	return 0;
}
