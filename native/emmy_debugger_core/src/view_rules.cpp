#include "view_rules.h"

namespace emmy {

using json = nlohmann::json;

int TypeViewRule::PinIndex(const std::string& field) const {
	for (size_t i = 0; i < pin.size(); ++i) {
		if (pin[i] == field) return static_cast<int>(i);
	}
	return -1;
}

namespace {

constexpr size_t kMaxTypes = 2000;
constexpr size_t kMaxListItems = 200;
constexpr size_t kMaxTemplateBytes = 1024;
constexpr size_t kMaxTemplatePaths = 16;

void Warn(json& warnings, const std::string& type, const std::string& message) {
	if (warnings.size() >= 100) return; // 太多时只报前 100 条
	json w = {{"message", message}};
	if (!type.empty()) w["type"] = type;
	warnings.push_back(std::move(w));
}

bool StringList(const json& v, const std::string& type, const char* key, std::vector<std::string>& out, json& warnings) {
	if (!v.contains(key)) return true;
	const json& a = v[key];
	if (!a.is_array()) {
		Warn(warnings, type, std::string(key) + " 必须是字符串数组");
		return false;
	}
	for (const json& s : a) {
		if (!s.is_string()) {
			Warn(warnings, type, std::string(key) + " 里有不是字符串的项，已跳过");
			continue;
		}
		if (out.size() >= kMaxListItems) {
			Warn(warnings, type, std::string(key) + " 最多 " + std::to_string(kMaxListItems) + " 项，多出的已忽略");
			break;
		}
		out.push_back(s.get<std::string>());
	}
	return true;
}

// "#{id} ×{count}"：{{ 和 }} 表示字面的花括号
bool ParseTemplate(const std::string& text, std::vector<TypeViewRule::Part>& out, std::string& error) {
	if (text.size() > kMaxTemplateBytes) {
		error = "summary 超过 " + std::to_string(kMaxTemplateBytes) + " 字节";
		return false;
	}
	std::string lit;
	size_t paths = 0;
	for (size_t i = 0; i < text.size(); ++i) {
		const char c = text[i];
		if (c == '{' && i + 1 < text.size() && text[i + 1] == '{') {
			lit += '{';
			++i;
			continue;
		}
		if (c == '}' && i + 1 < text.size() && text[i + 1] == '}') {
			lit += '}';
			++i;
			continue;
		}
		if (c == '}') {
			error = "summary 里有多余的 }（字面的花括号写成 }}）";
			return false;
		}
		if (c != '{') {
			lit += c;
			continue;
		}
		const size_t end = text.find('}', i + 1);
		if (end == std::string::npos) {
			error = "summary 里的 { 没有闭合";
			return false;
		}
		const std::string expr = text.substr(i + 1, end - i - 1);
		TypeViewRule::Part p;
		p.isPath = true;
		p.text = expr;
		std::string code;
		if (!ParseValuePath(expr, p.path, code)) {
			error = "summary 里的 {" + expr + "} 不是合法的 valuePath（" + code + "），只支持 a.b、a[1]、a[\"k\"] 这样的路径";
			return false;
		}
		if (++paths > kMaxTemplatePaths) {
			error = "summary 里最多 " + std::to_string(kMaxTemplatePaths) + " 个 {路径}";
			return false;
		}
		if (!lit.empty()) {
			TypeViewRule::Part l;
			l.text = std::move(lit);
			lit.clear();
			out.push_back(std::move(l));
		}
		out.push_back(std::move(p));
		i = end;
	}
	if (!lit.empty()) {
		TypeViewRule::Part l;
		l.text = std::move(lit);
		out.push_back(std::move(l));
	}
	return true;
}

} // namespace

std::shared_ptr<const ViewRuleSet> ParseViewRules(const json& rules, int& accepted, json& warnings) {
	auto set = std::make_shared<ViewRuleSet>();
	accepted = 0;
	warnings = json::array();
	if (!rules.is_object()) return set;
	if (rules.contains("version") && rules["version"].is_number_integer() && rules["version"].get<int>() > 1) {
		Warn(warnings, "", "规则文件 version=" + std::to_string(rules["version"].get<int>()) + " 比 runtime 支持的新，不认识的字段会被忽略");
	}
	StringList(rules, "", "classNameFields", set->classNameFields, warnings);
	StringList(rules, "", "builtinGlobals", set->builtinGlobals, warnings);
	if (!rules.contains("types")) return set;
	const json& types = rules["types"];
	if (!types.is_object()) {
		Warn(warnings, "", "types 必须是对象（键是类名）");
		return set;
	}
	for (auto it = types.begin(); it != types.end(); ++it) {
		const std::string& type = it.key();
		if (set->types.size() >= kMaxTypes) {
			Warn(warnings, "", "types 最多 " + std::to_string(kMaxTypes) + " 个类型，多出的已忽略");
			break;
		}
		const json& v = it.value();
		if (!v.is_object()) {
			Warn(warnings, type, "类型规则必须是对象");
			continue;
		}
		TypeViewRule rule;
		bool ok = true;
		if (v.contains("summary")) {
			if (!v["summary"].is_string()) {
				Warn(warnings, type, "summary 必须是字符串");
				ok = false;
			} else {
				std::string error;
				if (ParseTemplate(v["summary"].get<std::string>(), rule.summary, error)) {
					rule.hasSummary = true;
				} else {
					Warn(warnings, type, error);
					ok = false;
				}
			}
		}
		std::vector<std::string> hide;
		ok = StringList(v, type, "pin", rule.pin, warnings) && ok;
		ok = StringList(v, type, "hide", hide, warnings) && ok;
		ok = StringList(v, type, "ueProperties", rule.ueProperties, warnings) && ok;
		rule.hide.insert(hide.begin(), hide.end());
		// 一条规则里有错就整条跳过：半条规则比没有更让人困惑
		if (!ok) continue;
		set->types.emplace(type, std::move(rule));
		++accepted;
	}
	return set;
}

} // namespace emmy
