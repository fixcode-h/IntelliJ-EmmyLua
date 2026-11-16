#include "path_match.h"

#include <algorithm>
#include <cctype>
#include <cstddef>

namespace emmy {

namespace {

bool EndsWith(const std::string& s, std::string_view suffix) {
	return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

} // namespace

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
	// 没有 '@' 的是 load 进来的代码串本身
	return false;
}

NormalizedPath NormalizePath(std::string_view raw, bool caseSensitive) {
	NormalizedPath out;
	if (!raw.empty() && raw[0] == '@') {
		raw.remove_prefix(1);
	}
	std::string s(raw);
	std::replace(s.begin(), s.end(), '\\', '/');
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
