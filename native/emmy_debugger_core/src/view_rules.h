// 显示规则（docs/design/variable-view.md 11.1、13.4）：setViewRules 下发的声明式规则，runtime 原生计算，
// 只用 rawget，不执行目标进程的代码。整体替换；Session 线程解析，VM 线程只读（shared_ptr<const>）。
#pragma once

#include "nlohmann/json.hpp"
#include "value_path.h"

#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace emmy {

struct TypeViewRule {
	// summary 模板：字面量和 {valuePath} 交替
	struct Part {
		bool isPath = false;
		std::string text;
		std::vector<PathSegment> path;
	};
	bool hasSummary = false;
	std::vector<Part> summary;
	std::vector<std::string> pin;          // 固定在字段段最前面（按列出顺序）
	std::unordered_set<std::string> hide;  // 不显示（原始视图里仍可见）
	std::vector<std::string> ueProperties; // 来源 B 用，先收下

	bool IsHidden(const std::string& field) const { return !hide.empty() && hide.count(field) != 0; }
	// 在 pin 里的位置，不在时 -1
	int PinIndex(const std::string& field) const;
};

struct ViewRuleSet {
	std::vector<std::string> classNameFields; // 追加在内置的 __cname / __className / className / __type 之后
	std::vector<std::string> builtinGlobals;  // 追加到「内置」分组
	std::unordered_map<std::string, TypeViewRule> types;

	const TypeViewRule* Find(const std::string& type) const {
		if (type.empty() || types.empty()) return nullptr;
		auto it = types.find(type);
		return it == types.end() ? nullptr : &it->second;
	}
};

// 解析整份规则。非法的单条规则跳过并写进 warnings（[{type, message}]），accepted 是收下的类型规则数
std::shared_ptr<const ViewRuleSet> ParseViewRules(const nlohmann::json& rules, int& accepted, nlohmann::json& warnings);

} // namespace emmy
