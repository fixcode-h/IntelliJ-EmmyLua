// valuePath：只取值、不执行代码的受限表达式（协议 10.2，规则沿用原 restricted_eval）。
#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace emmy {

struct PathSegment {
	enum class Kind { Identifier, String, Integer };
	Kind kind = Kind::Identifier;
	std::string text;
	int64_t integer = 0;
};

constexpr size_t kMaxValuePathBytes = 4096;
constexpr size_t kMaxValuePathSegments = 32;

// 成功返回 true；失败时 errorCode 是稳定的错误码（EMPTY / TOO_LONG / TOO_MANY_SEGMENTS / BAD_ROOT / BAD_TOKEN / UNTERMINATED_STRING / BAD_INDEX）
bool ParseValuePath(std::string_view expr, std::vector<PathSegment>& out, std::string& errorCode);

// 是否是 valuePath 下允许的字面量（setVariable 用）：数字、带引号字符串、true/false/nil
bool IsLiteral(std::string_view expr);

} // namespace emmy
