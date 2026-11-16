// 宿主值提供者 v2 的登记和调用（docs/design/variable-view.md 13.6）。调用只在 VM 线程、暂停循环里发生。
#pragma once

#include "emmy/emmy_host_value.h"
#include "nlohmann/json.hpp"

#include <cstdint>
#include <string>
#include <vector>

namespace emmy::host {

using json = nlohmann::json;

bool HasProviders();

struct Described {
	uint64_t providerId = 0;
	bool valid = true;
	bool expandable = false;
	std::string typeName, summary, hint, kind;
};

// 栈上 idx 处的 userdata / table 有没有提供者认领。认领了返回 true。
bool Describe(lua_State* L, int idx, uint64_t vmId, uint64_t pauseId, uint32_t maxValueLength, Described& out);

// 列子项。hostRef 为空时查 L 栈上 idx 处的值。成功时 out = 宿主写的 JSON（含 children / total）。
bool ListChildren(uint64_t providerId, lua_State* L, int idx, const std::string& hostRef, uint64_t vmId,
	uint64_t pauseId, uint32_t maxValueLength, json& out, std::string& err);

// 暂停结束：通知这次暂停里用过的提供者
void ReleasePause(uint64_t vmId, uint64_t pauseId, const std::vector<uint64_t>& usedProviders);

} // namespace emmy::host
