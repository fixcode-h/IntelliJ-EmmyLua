#include "path_match.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <cstddef>

namespace emmy {

namespace {

bool EndsWith(const std::string& s, std::string_view suffix) {
	return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

} // namespace

namespace {

bool EndsWithFileExt(std::string_view s) {
	static const char* kExts[] = {".lua", ".luac", ".txt", ".bytes"};
	for (const char* ext : kExts) {
		const size_t n = std::strlen(ext);
		if (s.size() <= n) continue;
		bool same = true;
		for (size_t i = 0; i < n && same; ++i) {
			same = std::tolower(static_cast<unsigned char>(s[s.size() - n + i])) == ext[i];
		}
		if (same) return true;
	}
	return false;
}

bool IsLuaKeyword(std::string_view s) {
	static const char* kWords[] = {"and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if",
		"in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"};
	for (const char* w : kWords) {
		if (s == w) return true;
	}
	return false;
}

} // namespace

bool IsModuleChunk(std::string_view s) {
	// UnLua 等按模块名加载时 chunkname 可能就是 "Tutorials.BP_CharacterBase"：只有标识符字符和点。
	// 这样的串当代码执行只可能是语法错误（单独一个名字不是合法语句），除了 return / break 这类关键字。
	if (s.empty() || s.size() > 256 || s.front() == '.' || s.back() == '.') return false;
	if (std::isdigit(static_cast<unsigned char>(s.front()))) return false;
	for (char c : s) {
		if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '.')) return false;
	}
	if (s.find("..") != std::string_view::npos) return false;
	if (EndsWithFileExt(s)) return false; // foo.lua 是文件名，不是模块名
	return !IsLuaKeyword(s);
}

bool IsFileChunk(std::string_view source) {
	if (source.empty()) {
		return false;
	}
	if (source[0] == '=') {
		return false;
	}
	if (source[0] == '@') {
		return true;
	}
	// 没有 '@' 的按约定是 load 进来的代码串本身。但 UnLua（尤其改过的分支）、一些引擎直接拿路径或模块名当
	// chunkname 不加 '@'，所以长得像路径的也当文件：单行、不太长，并且以脚本扩展名结尾，或者含路径分隔符且
	// 没有代码里才会出现的符号，或者是「A.B.C」这样的模块名。
	if (source.size() > 1024) return false;
	if (source.find_first_of("\r\n") != std::string_view::npos) return false;
	const bool hasSep = source.find_first_of("/\\") != std::string_view::npos;
	if (EndsWithFileExt(source)) {
		// 「return M.lua」这类代码串：没有路径分隔符时不许有空白
		if (!hasSep && source.find_first_of(" \t") != std::string_view::npos) return false;
		return source.find_first_of("(){}=;\"'") == std::string_view::npos;
	}
	if (hasSep) {
		return source.find_first_of("(){}=;\"'\t,+*<>[]#~") == std::string_view::npos;
	}
	return IsModuleChunk(source);
}

NormalizedPath NormalizePath(std::string_view raw, bool caseSensitive) {
	NormalizedPath out;
	if (!raw.empty() && raw[0] == '@') {
		raw.remove_prefix(1);
	}
	std::string s(raw);
	std::replace(s.begin(), s.end(), '\\', '/');
	// 模块名（Tutorials.BP_CharacterBase）按 require 的规则换成路径，才能和 IDE 的文件路径按段比后缀
	if (s.find('.') != std::string::npos && IsModuleChunk(s)) std::replace(s.begin(), s.end(), '.', '/');
	if (!caseSensitive) {
		std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
	}
	bool absolute = !s.empty() && s[0] == '/';
	std::vector<std::string> segs;
	size_t i = 0;
	while (i <= s.size()) {
		size_t j = s.find('/', i);
		if (j == std::string::npos) {
			j = s.size();
		}
		std::string seg = s.substr(i, j - i);
		i = j + 1;
		if (seg.empty() || seg == ".") {
			continue;
		}
		if (seg == ".." && !segs.empty() && segs.back() != "..") {
			segs.pop_back();
			continue;
		}
		segs.push_back(std::move(seg));
	}
	// 开头的 '..' 无法折叠，匹配时没有意义，丢掉
	size_t lead = 0;
	while (lead < segs.size() && segs[lead] == "..") {
		++lead;
	}
	segs.erase(segs.begin(), segs.begin() + static_cast<std::ptrdiff_t>(lead));
	if (!segs.empty()) {
		std::string& last = segs.back();
		static const char* kExts[] = {".lua", ".luac", ".txt", ".bytes"};
		for (const char* ext : kExts) {
			std::string e = ext;
			if (EndsWith(last, e) && last.size() > e.size()) {
				last.resize(last.size() - e.size());
				break;
			}
			// 大小写敏感时也认大写扩展名
			std::string upper = e;
			std::transform(upper.begin(), upper.end(), upper.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
			if (EndsWith(last, upper) && last.size() > upper.size()) {
				last.resize(last.size() - upper.size());
				break;
			}
		}
	}
	std::string text = absolute ? "/" : "";
	for (size_t k = 0; k < segs.size(); ++k) {
		if (k) {
			text += '/';
		}
		text += segs[k];
	}
	out.text = std::move(text);
	out.segments = std::move(segs);
	return out;
}

size_t SuffixMatchSegments(const NormalizedPath& a, const NormalizedPath& b) {
	const auto& x = a.segments;
	const auto& y = b.segments;
	if (x.empty() || y.empty()) {
		return 0;
	}
	size_t n = std::min(x.size(), y.size());
	for (size_t k = 1; k <= n; ++k) {
		if (x[x.size() - k] != y[y.size() - k]) {
			return 0;
		}
	}
	// 较短的一方必须整段被匹配（是另一方的后缀）
	return n;
}

} // namespace emmy
