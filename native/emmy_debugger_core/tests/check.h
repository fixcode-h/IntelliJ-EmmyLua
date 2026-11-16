// 极简测试宏：不引第三方测试框架，CI 里直接跑可执行文件看退出码。
#pragma once

#include <cstdio>
#include <exception>
#include <type_traits>
#include <functional>
#include <string>
#include <vector>

namespace emmy::test {

struct Case {
	const char* name;
	std::function<void()> fn;
};

inline std::vector<Case>& Cases() {
	static std::vector<Case> cases;
	return cases;
}

inline int& Failures() {
	static int n = 0;
	return n;
}

struct Registrar {
	Registrar(const char* name, std::function<void()> fn) { Cases().push_back({name, std::move(fn)}); }
};

inline int RunAll() {
	for (auto& c : Cases()) {
		int before = Failures();
		try {
			c.fn();
		} catch (const std::exception& e) {
			std::printf("  exception: %s\n", e.what());
			++Failures();
		}
		std::printf("[%s] %s\n", Failures() == before ? "PASS" : "FAIL", c.name);
	}
	std::printf("%zu cases, %d failures\n", Cases().size(), Failures());
	return Failures() == 0 ? 0 : 1;
}

} // namespace emmy::test

#define EMMY_CAT2(a, b) a##b
#define EMMY_CAT(a, b) EMMY_CAT2(a, b)
#define TEST_CASE(name) \
	static void EMMY_CAT(test_fn_, __LINE__)(); \
	static ::emmy::test::Registrar EMMY_CAT(test_reg_, __LINE__)(name, &EMMY_CAT(test_fn_, __LINE__)); \
	static void EMMY_CAT(test_fn_, __LINE__)()

#define CHECK(cond) \
	do { \
		if (!(cond)) { \
			std::printf("  %s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #cond); \
			++::emmy::test::Failures(); \
		} \
	} while (0)

#define CHECK_EQ(a, b) \
	do { \
		auto _va = (a); \
		auto _vb = (b); \
		if (!(_va == _vb)) { \
			std::printf("  %s:%d: CHECK_EQ(%s, %s) failed: [%s] vs [%s]\n", __FILE__, __LINE__, #a, #b, \
				::emmy::test::ToStr(_va).c_str(), ::emmy::test::ToStr(_vb).c_str()); \
			++::emmy::test::Failures(); \
		} \
	} while (0)

namespace emmy::test {
inline std::string ToStr(const std::string& s) { return s; }
inline std::string ToStr(const char* s) { return s ? s : "(null)"; }
template <class T>
std::string ToStr(const T& v) {
	if constexpr (std::is_arithmetic_v<T>) return std::to_string(v);
	else return "?";
}
} // namespace emmy::test
