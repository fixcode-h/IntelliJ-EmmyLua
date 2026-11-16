// chunkName ↔ 断点路径匹配（协议 7.2）。纯字符串运算，只在 chunk 第一次出现或断点修订号变化时调用，不在热路径上。
#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace emmy {

struct NormalizedPath {
	std::string text;                  // 规范化后的整条路径（已按需转小写、去扩展名）
	std::vector<std::string> segments; // 按 '/' 切开的段
};

// 规范化：去 '@'，'\' → '/'，去 './'，折叠 'a/../'，合并 '//'，去掉 .lua/.luac/.txt/.bytes 扩展名，可选转小写。
NormalizedPath NormalizePath(std::string_view raw, bool caseSensitive);

// chunk 源是不是文件（'=[C]'、'=?'、'[string ...]' 都不是）
bool IsFileChunk(std::string_view source);

// 两条路径的段是否构成后缀关系；返回匹配的段数，不匹配返回 0。
size_t SuffixMatchSegments(const NormalizedPath& a, const NormalizedPath& b);

} // namespace emmy
