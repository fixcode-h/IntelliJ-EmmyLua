// 暂停时的取数据：栈、作用域、变量（懒加载 + 分页）、求值、修改变量。只在 VM 线程使用。
#pragma once

#include "lua_shim.h"
#include "nlohmann/json.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace emmy {

using json = nlohmann::json;
class Vm;

struct ProtocolError {
	std::string code;
	std::string message;
};

struct EvalOptions {
	bool allowLua = true;
	bool invokeIndex = false;
	int timeoutMs = 1000;
	size_t maxLength = 1024;
};

// 一个可展开对象的子项
struct VariableItem {
	std::string name;
	std::string nameType;
	std::string value;
	std::string type;
	std::string typeName;
	uint64_t ref = 0;
	int64_t namedCount = -1;
	int64_t indexedCount = -1;
	std::string evaluateName;
	bool indexed = false;
	// variable-view 13.2 的附加字段（宿主值提供者会填）；空的不输出
	std::string kind;
	std::string hint;
	std::vector<std::string> attrs;
	int64_t length = -1;

	json ToJson() const;
};

class Inspector {
public:
	explicit Inspector(Vm& vm);

	// ---- 暂停生命周期 ----
	void BeginPause(lua_State* L);
	void EndPause(lua_State* L);
	bool active() const { return pausedL_ != nullptr; }

	// ---- 协议命令（暂停中） ----
	bool StackTrace(int start, int count, json& body, ProtocolError& err);
	json TopFrame();
	bool Scopes(uint64_t frameId, json& body, ProtocolError& err);
	bool Variables(uint64_t ref, int start, int count, const std::string& filter, json& body, ProtocolError& err);
	bool Evaluate(uint64_t frameId, const std::string& expr, const std::string& mode, const EvalOptions& opt, json& body, ProtocolError& err);
	bool SetVariable(uint64_t ref, const std::string& name, const std::string& value, const std::string& mode, const EvalOptions& opt, json& body, ProtocolError& err);
	bool Threads(json& body, ProtocolError& err);

	// ---- 运行中（钩子里、未暂停）也能用：条件断点、日志断点、采集 ----
	// 在 L 的第 level 层帧上求值 Lua 表达式，结果留在 L 栈顶（成功时）
	bool EvalLuaAt(lua_State* L, int level, const std::string& expr, int timeoutMs, std::string& err, int compiledCacheRef = 0);
	bool EvalValuePathAt(lua_State* L, int level, const std::string& expr, bool invokeIndex, std::string& err);
	// 取栈顶值的一行摘要
	std::string Summarize(lua_State* L, int idx, size_t maxLen, std::string* typeName = nullptr);

	// 条件断点：预编译后的函数缓存（key 为断点 serial）
	bool EvalCondition(lua_State* L, uint64_t serial, const std::string& expr, int timeoutMs, bool& result, std::string& err);
	void ResetConditionCache(lua_State* L);

	// 求值超时守卫：钩子入口发现正在求值时调用；返回 true 表示应抛出超时错误
	bool evaluating() const { return evaluating_; }
	bool EvalTimedOut();

private:
	struct FrameRef {
		lua_State* thread = nullptr;
		int level = 0;
	};
	// HostList：子项来自宿主值提供者（slot 处的值，或 hostRef 句柄）
	enum class RefKind { Locals, Upvalues, Varargs, Globals, Value, HostList };
	struct RefEntry {
		RefKind kind = RefKind::Value;
		uint64_t frameId = 0;
		int slot = 0; // 值在锚表里的位置（Value / Globals）
		std::string evaluateName;
		bool materialized = false;
		std::vector<VariableItem> children;
		uint64_t providerId = 0; // 被宿主值提供者认领的值
		std::string hostRef;
		std::string hostSummary;
	};

	uint64_t NewFrameId(lua_State* thread, int level);
	uint64_t NewRef(RefKind kind, uint64_t frameId, int slot, const std::string& evaluateName);
	int Anchor(lua_State* L, int idx);          // 把 idx 处的值放进锚表，返回槽位
	void PushAnchored(lua_State* L, int slot);
	bool GetFrame(uint64_t frameId, FrameRef& out, lua_Debug& ar, ProtocolError& err);
	void Materialize(RefEntry& entry);
	bool HostDescribe(lua_State* L, int idx, VariableItem& item, const std::string& evaluateName);
	// 宿主列子项，分页原样交给宿主；返回宿主报的总数
	int64_t HostChildren(lua_State* L, int idx, const RefEntry& entry, int start, int count, std::vector<VariableItem>& out);
	bool IsHostListed(const RefEntry& entry);
	void MakeItem(lua_State* L, int idx, VariableItem& item, const std::string& evaluateName);
	void PushFrameEnv(lua_State* L, lua_State* thread, lua_Debug& ar); // 压入帧的 _ENV / 函数环境
	bool BuildEvalEnv(lua_State* L, lua_State* thread, int level);    // 压入求值环境表
	bool RunOnEvalThread(lua_State* L, int nargs, int timeoutMs, std::string& err);
	bool LookupRoot(lua_State* L, lua_State* thread, int level, const std::string& name);
	bool IndexValue(lua_State* L, const struct PathSegment& seg, bool invokeIndex, std::string& err);
	std::string FormatNumber(lua_State* L, int idx);
	std::string TableSummary(lua_State* L, int idx, size_t maxLen);

	Vm& vm_;
	lua_State* pausedL_ = nullptr;
	int anchorRef_ = LUA_NOREF;
	int nextSlot_ = 1;
	std::unordered_map<uint64_t, FrameRef> frames_;
	std::unordered_map<uint64_t, RefEntry> refs_;
	std::vector<uint64_t> ids_;
	std::vector<uint64_t> frameOrder_;
	int condCacheRef_ = LUA_NOREF;
	bool evaluating_ = false;
	int64_t evalDeadlineMs_ = 0;
	std::vector<uint64_t> usedProviders_; // 本次暂停里调用过的宿主值提供者，暂停结束时通知释放
	uint64_t pauseIdForHost_ = 0;
};

} // namespace emmy
