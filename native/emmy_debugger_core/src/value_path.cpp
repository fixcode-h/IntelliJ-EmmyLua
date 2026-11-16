#include "value_path.h"

#include <cctype>

namespace emmy {

namespace {

bool IsIdentStart(char c) { return std::isalpha(static_cast<unsigned char>(c)) || c == '_'; }
bool IsIdentChar(char c) { return std::isalnum(static_cast<unsigned char>(c)) || c == '_'; }

void SkipSpace(std::string_view s, size_t& i) {
	while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) ++i;
}

bool ParseQuoted(std::string_view s, size_t& i, std::string& out) {
	char q = s[i++];
	out.clear();
	while (i < s.size()) {
		char c = s[i++];
		if (c == q) return true;
		if (c == '\\' && i < s.size()) {
			char e = s[i++];
			switch (e) {
			case 'n': out += '\n'; break;
			case 't': out += '\t'; break;
			case 'r': out += '\r'; break;
			case '\\': out += '\\'; break;
			case '"': out += '"'; break;
			case '\'': out += '\''; break;
			default: out += e; break;
			}
			continue;
		}
		out += c;
	}
	return false;
}

} // namespace

bool ParseValuePath(std::string_view s, std::vector<PathSegment>& out, std::string& err) {
	out.clear();
	if (s.size() > kMaxValuePathBytes) { err = "TOO_LONG"; return false; }
	size_t i = 0;
	SkipSpace(s, i);
	if (i >= s.size()) { err = "EMPTY"; return false; }
	if (!IsIdentStart(s[i])) { err = "BAD_ROOT"; return false; }
	auto readIdent = [&](PathSegment& seg) {
		size_t b = i;
		while (i < s.size() && IsIdentChar(s[i])) ++i;
		seg.kind = PathSegment::Kind::Identifier;
		seg.text.assign(s.substr(b, i - b));
	};
	PathSegment root;
	readIdent(root);
	out.push_back(std::move(root));
	while (true) {
		SkipSpace(s, i);
		if (i >= s.size()) break;
		PathSegment seg;
		if (s[i] == '.') {
			++i;
			SkipSpace(s, i);
			if (i >= s.size() || !IsIdentStart(s[i])) { err = "BAD_TOKEN"; return false; }
			readIdent(seg);
			seg.kind = PathSegment::Kind::String;
		} else if (s[i] == '[') {
			++i;
			SkipSpace(s, i);
			if (i >= s.size()) { err = "BAD_INDEX"; return false; }
			if (s[i] == '"' || s[i] == '\'') {
				if (!ParseQuoted(s, i, seg.text)) { err = "UNTERMINATED_STRING"; return false; }
				seg.kind = PathSegment::Kind::String;
			} else {
				bool neg = false;
				if (s[i] == '-') { neg = true; ++i; }
				size_t b = i;
				int64_t v = 0;
				while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i]))) {
					if (i - b > 17) { err = "BAD_INDEX"; return false; }
					v = v * 10 + (s[i] - '0');
					++i;
				}
				if (i == b) { err = "BAD_INDEX"; return false; }
				seg.kind = PathSegment::Kind::Integer;
				seg.integer = neg ? -v : v;
			}
			SkipSpace(s, i);
			if (i >= s.size() || s[i] != ']') { err = "BAD_INDEX"; return false; }
			++i;
		} else {
			err = "BAD_TOKEN";
			return false;
		}
		out.push_back(std::move(seg));
		if (out.size() > kMaxValuePathSegments) { err = "TOO_MANY_SEGMENTS"; return false; }
	}
	return true;
}

bool IsLiteral(std::string_view s) {
	size_t i = 0;
	SkipSpace(s, i);
	size_t e = s.size();
	while (e > i && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
	std::string_view t = s.substr(i, e - i);
	if (t == "true" || t == "false" || t == "nil") return true;
	if (t.empty()) return false;
	if (t[0] == '"' || t[0] == '\'') {
		size_t k = 0;
		std::string tmp;
		return ParseQuoted(t, k, tmp) && k == t.size();
	}
	size_t k = 0;
	if (t[k] == '-' || t[k] == '+') ++k;
	bool digit = false, dot = false;
	for (; k < t.size(); ++k) {
		char c = t[k];
		if (std::isdigit(static_cast<unsigned char>(c))) digit = true;
		else if (c == '.' && !dot) dot = true;
		else if ((c == 'e' || c == 'E') && digit) {
			++k;
			if (k < t.size() && (t[k] == '-' || t[k] == '+')) ++k;
			bool expDigit = false;
			for (; k < t.size(); ++k) {
				if (!std::isdigit(static_cast<unsigned char>(t[k]))) return false;
				expDigit = true;
			}
			return expDigit;
		} else return false;
	}
	return digit;
}

} // namespace emmy
