// bench_hook：量调试器对 Lua 执行速度的影响（设计文档 5.3「运行时开销」）。
//
// 每个场景跑同样的 Lua 工作负载，和「不挂调试器」比耗时倍数：
//   baseline          没注册 VM
//   registered        注册了 VM，但没有 IDE 连接（目标 1.00x）
//   connected         IDE 已连接，没有任何断点（目标 ≤ 1.05x）
//   bp_other_file     断点在别的文件（目标 ≤ 1.3x）
//   bp_same_file      断点在正在执行的文件、但不在执行到的行（目标 ≤ 2x）
//   ref_call_hook     参照：只挂 CALL、回调什么都不做（任何「按函数判断」方案在 Lua 层面的下限）
//   legacy_line_hook  参照：常开 LINE|CALL|RET、回调什么都不做（旧架构的下限，旧实现每行还要加锁和复制断点表）
// 另外单独量条件断点的单次判断耗时（目标 ≤ 2 µs）。
//
// 用法：bench_hook [--check] [--json out.json] [--repeat N]
//   --check  超过目标（含 15% 余量，CI 机器有抖动）时返回非 0
#include "emmy/emmy_core.h"
#include "lua_shim.h"
#include "test_client.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <string>
#include <thread>
#include <vector>

using emmy::test::TestClient;
using json = nlohmann::json;

namespace {

const char* kWorkload = R"(local W = {}
function W.loop(n)
  local s = 0
  for i = 1, n do
    s = s + i % 7
    if s > 1e12 then
      s = 0
    end
  end
  return s
end
local function fib(n)
  if n < 2 then return n end
  return fib(n - 1) + fib(n - 2)
end
W.fib = fib
function W.tables(n)
  local t = {}
  for i = 1, n do t[i] = { id = i, name = "n" .. (i % 100) } end
  local c = 0
  for _, v in ipairs(t) do
    if v.id % 3 == 0 then c = c + #v.name end
  end
  for k, v in pairs(t[1]) do c = c + 1 end
  return c
end
function W.cond(n)
  local hits = 0
  for i = 1, n do
    hits = hits + 1
  end
  return hits
end
return W
)";
// 断点在 W.loop 里 if 分支（第 7 行 `s = 0` 永远执行不到）
constexpr int kSameFileLine = 7;
// W.cond 的循环体行
constexpr int kCondLine = 33;

struct Workload {
	const char* name;
	const char* fn;
	int arg;
};

const Workload kWorkloads[] = {
	{"loop_5m", "loop", 5000000},
	{"fib_27", "fib", 27},
	{"tables_300k", "tables", 300000},
};

double RunOnce(lua_State* L, int mod, const Workload& w) {
	lua_getfield(L, mod, w.fn);
	lua_pushinteger(L, w.arg);
	auto t0 = std::chrono::steady_clock::now();
	if (lua_pcall(L, 1, 1, 0) != LUA_OK) {
		std::fprintf(stderr, "lua error: %s\n", lua_tostring(L, -1));
	}
	auto t1 = std::chrono::steady_clock::now();
	lua_pop(L, 1);
	return std::chrono::duration<double, std::milli>(t1 - t0).count();
}

// 取多次运行的中位数，减少抖动
double Measure(lua_State* L, int mod, const Workload& w, int repeat) {
	std::vector<double> v;
	RunOnce(L, mod, w); // 预热
	for (int i = 0; i < repeat; ++i) v.push_back(RunOnce(L, mod, w));
	std::sort(v.begin(), v.end());
	return v[v.size() / 2];
}

void EmptyHook(lua_State*, lua_Debug*) {}

lua_State* NewState(int& mod) {
	lua_State* L = luaL_newstate();
	luaL_openlibs(L);
	emmy::lua::LoadBuffer(L, kWorkload, std::strlen(kWorkload), "@bench/workload.lua");
	lua_call(L, 0, 1);
	mod = lua_gettop(L);
	return L;
}

// 让 VM 跑一小段 Lua，处理 IDE 发来的标志（连接、断点刷新都在钩子里生效）
void Settle(lua_State* L, int mod) {
	for (int i = 0; i < 20; ++i) {
		lua_getfield(L, mod, "loop");
		lua_pushinteger(L, 20000);
		lua_pcall(L, 1, 1, 0);
		lua_pop(L, 1);
		std::this_thread::sleep_for(std::chrono::milliseconds(2));
	}
}

} // namespace

int main(int argc, char** argv) {
	bool check = false;
	int repeat = 7;
	std::string jsonOut;
	for (int i = 1; i < argc; ++i) {
		if (!std::strcmp(argv[i], "--check")) check = true;
		else if (!std::strcmp(argv[i], "--repeat") && i + 1 < argc) repeat = std::max(1, std::atoi(argv[++i]));
		else if (!std::strcmp(argv[i], "--json") && i + 1 < argc) jsonOut = argv[++i];
	}

	int port = 41000 + static_cast<int>(std::chrono::steady_clock::now().time_since_epoch().count() % 2000);
	char err[256] = {0};
	if (Emmy_ListenTcp("127.0.0.1", port, err, sizeof(err)) != 0) {
		std::fprintf(stderr, "listen failed: %s\n", err);
		return 2;
	}

	struct Scenario {
		const char* name;
		double target; // 0 表示只是参照，不检查
	};
	const Scenario scenarios[] = {
		{"baseline", 0},
		{"registered", 1.00},
		{"connected", 1.05},
		{"bp_other_file", 1.3},
		{"bp_same_file", 2.0},
		{"ref_call_hook", 0},
		{"legacy_line_hook", 0},
	};

	json report = json::object();
	report["lua"] = emmy::lua::VersionString();
	report["repeat"] = repeat;
	std::map<std::string, std::map<std::string, double>> ms;
	int mod = 0;

	// baseline / legacy：独立 VM，不注册
	{
		lua_State* L = NewState(mod);
		for (const Workload& w : kWorkloads) ms["baseline"][w.name] = Measure(L, mod, w, repeat);
		lua_sethook(L, &EmptyHook, LUA_MASKCALL, 0);
		for (const Workload& w : kWorkloads) ms["ref_call_hook"][w.name] = Measure(L, mod, w, repeat);
		lua_sethook(L, &EmptyHook, LUA_MASKLINE | LUA_MASKCALL | LUA_MASKRET, 0);
		for (const Workload& w : kWorkloads) ms["legacy_line_hook"][w.name] = Measure(L, mod, w, repeat);
		lua_close(L);
	}

	lua_State* L = NewState(mod);
	Emmy_RegisterLuaVm(L, "bench");
	for (const Workload& w : kWorkloads) ms["registered"][w.name] = Measure(L, mod, w, repeat);

	TestClient ide;
	if (!ide.Connect("127.0.0.1", port)) {
		std::fprintf(stderr, "connect failed\n");
		return 2;
	}
	ide.Request("hello", {{"protocol", 3}, {"client", {{"name", "bench"}, {"version", "0"}}}});
	ide.Request("configurationDone");
	Settle(L, mod);
	for (const Workload& w : kWorkloads) ms["connected"][w.name] = Measure(L, mod, w, repeat);

	uint64_t rev = 0;
	auto setBp = [&](const char* path, int line, json extra = json::object()) {
		json bp = {{"id", "b1"}, {"source", {{"path", path}}}, {"line", line}};
		for (auto& kv : extra.items()) bp[kv.key()] = kv.value();
		ide.Request("setBreakpoints", {{"revision", ++rev}, {"replaceAll", true}, {"upserts", json::array({bp})}});
		Settle(L, mod);
	};
	setBp("D:/proj/bench/other.lua", 3);
	for (const Workload& w : kWorkloads) ms["bp_other_file"][w.name] = Measure(L, mod, w, repeat);
	setBp("D:/proj/bench/workload.lua", kSameFileLine);
	for (const Workload& w : kWorkloads) ms["bp_same_file"][w.name] = Measure(L, mod, w, repeat);

	// 条件断点：每次循环都判断一次、永远为假
	const int condN = 100000;
	setBp("D:/proj/bench/workload.lua", kCondLine, {{"condition", "i < 0"}});
	Workload condW{"cond", "cond", condN};
	double condMs = Measure(L, mod, condW, std::max(3, repeat / 2));
	setBp("D:/proj/bench/other.lua", 3);
	double condBaseMs = Measure(L, mod, condW, std::max(3, repeat / 2));
	double condUs = (condMs - condBaseMs) * 1000.0 / condN;

	ide.Request("disconnect");
	ide.Close();
	Emmy_UnregisterLuaVm(L);
	lua_close(L);
	Emmy_Stop();

	bool failed = false;
	std::printf("Lua %s，取 %d 次中位数（ms），括号内为相对 baseline 的倍数\n\n", emmy::lua::VersionString(), repeat);
	std::printf("%-18s", "scenario");
	for (const Workload& w : kWorkloads) std::printf("%22s", w.name);
	std::printf("%10s\n", "target");
	for (const Scenario& s : scenarios) {
		std::printf("%-18s", s.name);
		json row = json::object();
		for (const Workload& w : kWorkloads) {
			double v = ms[s.name][w.name];
			double ratio = v / ms["baseline"][w.name];
			char cell[64];
			std::snprintf(cell, sizeof(cell), "%.2f (%.2fx)", v, ratio);
			std::printf("%22s", cell);
			row[w.name] = {{"ms", v}, {"ratio", ratio}};
			if (check && s.target > 0 && ratio > s.target * 1.15) failed = true;
		}
		if (s.target > 0) std::printf("%9.2fx", s.target);
		std::printf("\n");
		report["scenarios"][s.name] = row;
		if (s.target > 0) report["scenarios"][s.name]["target"] = s.target;
	}
	std::printf("\n条件断点单次判断：%.2f µs（目标 ≤ 2 µs）\n", condUs);
	report["conditionUs"] = condUs;
	if (check && condUs > 2.0 * 1.15) failed = true;

	if (!jsonOut.empty()) {
		std::ofstream(jsonOut) << report.dump(2) << "\n";
	}
	if (failed) std::printf("\n有场景超过目标\n");
	return failed ? 1 : 0;
}
