// 端到端测试：进程内起一个「游戏线程」跑 Lua，测试客户端扮演 IDE 走完整的协议 v3。
#include "check.h"
#include "core.h"
#include "vm.h"
#include "emmy/emmy_core.h"
#include "emmy/emmy_host_value.h"
#include "frame.h"
#include "lua_shim.h"
#include "test_client.h"

#include <atomic>
#include <chrono>
#include <cstring>
#include <map>
#include <thread>
#include <vector>

using namespace emmy;
using emmy::test::TestClient;
using json = nlohmann::json;

namespace {

const char* kScript = R"(local M = {}
local counter = 0
function M.add(a, b)
  local sum = a + b
  return sum
end
function M.tick(i)
  local t = { name = "x", list = {1, 2, 3}, [10] = true }
  local r = M.add(i, 1)
  counter = counter + r
  print("tick", i)
  if FAIL then FAIL = nil; local x = nil; return x.y end; if FAIL2 then FAIL2 = nil; pcall(function() error("caught boom") end) end; if REPEAT_ERR and REPEAT_ERR > 0 then REPEAT_ERR = REPEAT_ERR - 1; pcall(function() error("repeat boom") end) end
  return r
end
function M.counter() return counter end
CO = coroutine.create(function(a)
  local inner = a * 2
  coroutine.yield(inner)
end)
coroutine.resume(CO, 21)
GameMod = M
return M
)";

const char* kPath = "D:/proj/game/main.lua";

struct Game {
	std::atomic<bool> stop{false};
	std::atomic<int64_t> ticks{0};
	std::atomic<bool> hookCleared{false};
	std::atomic<bool> ready{false};
	bool lastErrorExpected = false;
	std::thread thread;

	void Start() {
		thread = std::thread([this] { Run(); });
		while (!ready) std::this_thread::sleep_for(std::chrono::milliseconds(1));
	}

	void Run() {
		lua_State* L = luaL_newstate();
		luaL_openlibs(L);
		// 测试里造 UnLua 类表要用 lightuserdata（ClassDesc），Lua 自己造不出来
		lua_pushcfunction(L, [](lua_State* S) { lua_pushlightuserdata(S, reinterpret_cast<void*>(0x1234)); return 1; });
		lua_setglobal(L, "mklud");
		Emmy_RegisterLuaVm(L, "test");
		Emmy_SetVmName(L, "TestVM");
		lua::LoadBuffer(L, kScript, std::strlen(kScript), "@game/main.lua");
		lua_call(L, 0, 1);
		int mod = lua_gettop(L);
		ready = true;
		int64_t i = 0;
		while (!stop) {
			lua_getfield(L, mod, "tick");
			lua_pushinteger(L, static_cast<lua_Integer>(++i));
			// 像注入方式一样：宿主的 lua_pcall 没有错误处理函数时由核心插一个（出错即停）
			const int h = Emmy_PrepareHostPcall(L, 1, 0);
			const int rc = lua_pcall(L, 1, 1, h);
			Emmy_FinishHostPcall(L, h);
			if (rc != LUA_OK && !lastErrorExpected) {
				std::printf("lua error: %s\n", lua_tostring(L, -1));
			}
			lastErrorExpected = false;
			lua_pop(L, 1);
			ticks = i;
			hookCleared = lua_gethook(L) == nullptr;
			std::this_thread::sleep_for(std::chrono::microseconds(500));
		}
		Emmy_UnregisterLuaVm(L);
		lua_close(L);
	}

	void Stop() {
		stop = true;
		if (thread.joinable()) thread.join();
	}
};

int g_port = 0;

json Hello(TestClient& c, json caps = nullptr) {
	json args = {{"protocol", 3}, {"client", {{"name", "test"}, {"version", "0"}}}};
	if (!caps.is_null()) args["caps"] = caps;
	return c.Request("hello", args);
}

json Bp(const char* id, int line, json extra = json::object()) {
	json bp = {{"id", id}, {"source", {{"path", kPath}}}, {"line", line}};
	for (auto& kv : extra.items()) bp[kv.key()] = kv.value();
	return bp;
}

bool Ok(const json& r) {
	return r.is_object() && r.value("ok", false);
}

uint64_t g_rev = 0;

json SetBps(TestClient& c, json upserts, json removes = json::array(), bool replaceAll = true) {
	return c.Request("setBreakpoints", {{"revision", ++g_rev}, {"replaceAll", replaceAll}, {"upserts", upserts}, {"removes", removes}});
}

json Continue(TestClient& c, const json& paused) {
	return c.Request("continue", {{"vmId", paused["body"]["vmId"]}, {"pauseId", paused["body"]["pauseId"]}});
}

Game* g_game = nullptr;

} // namespace

TEST_CASE("session.handshake_and_errors") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	json early = c.Request("vms");
	CHECK(!Ok(early));
	CHECK_EQ(early["error"]["code"].get<std::string>(), std::string("NOT_READY"));
	json r = Hello(c);
	CHECK(Ok(r));
	CHECK_EQ(r["body"]["protocol"].get<int>(), 3);
	CHECK_EQ(r["body"]["vms"].size(), static_cast<size_t>(1));
	CHECK_EQ(r["body"]["vms"][0]["name"].get<std::string>(), std::string("TestVM"));
	bool hasLuaEval = false;
	for (auto& cap : r["body"]["caps"]) if (cap == "luaEval") hasLuaEval = true;
	CHECK(hasLuaEval);

	// 第二个 IDE：BUSY
	TestClient c2;
	CHECK(c2.Connect("127.0.0.1", g_port));
	json busy = Hello(c2);
	CHECK(!Ok(busy));
	CHECK_EQ(busy["error"]["code"].get<std::string>(), std::string("BUSY"));

	// 旧协议（v1 文本行）：回一行说明并断开
	TestClient old;
	CHECK(old.Connect("127.0.0.1", g_port));
	old.SendRaw("1\n{\"cmd\":0}\n");
	for (int i = 0; i < 100 && !old.closed(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(10));
	CHECK(old.rawPrefix().rfind("EMMY_PROTOCOL_V3_REQUIRED", 0) == 0);

	json unknown = c.Request("noSuchCommand");
	CHECK_EQ(unknown["error"]["code"].get<std::string>(), std::string("UNKNOWN_COMMAND"));
	json notPaused = c.Request("stackTrace", {{"vmId", 1}});
	CHECK_EQ(notPaused["error"]["code"].get<std::string>(), std::string("NOT_PAUSED"));
	c.Request("disconnect");
}

TEST_CASE("session.breakpoint_stack_variables_eval") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c)));
	g_rev = 0;
	json r = SetBps(c, json::array({Bp("bp-9", 9)}));
	CHECK(Ok(r));
	CHECK_EQ(r["body"]["breakpoints"][0]["state"].get<std::string>(), std::string("pending"));
	CHECK(Ok(c.Request("configurationDone")));
	json st = c.WaitEvent("breakpointStatus");
	CHECK(st.is_object());
	if (st.is_object()) {
		CHECK_EQ(st["body"]["state"].get<std::string>(), std::string("verified"));
		CHECK_EQ(st["body"]["chunkName"].get<std::string>(), std::string("@game/main.lua"));
	}
	json p = c.WaitEvent("paused");
	CHECK(p.is_object());
	if (!p.is_object()) return;
	CHECK_EQ(p["body"]["reason"].get<std::string>(), std::string("breakpoint"));
	CHECK_EQ(p["body"]["top"]["line"].get<int>(), 9);
	CHECK_EQ(p["body"]["breakpointIds"][0].get<std::string>(), std::string("bp-9"));
	uint64_t vmId = p["body"]["vmId"];
	uint64_t pauseId = p["body"]["pauseId"];

	json stack = c.Request("stackTrace", {{"vmId", vmId}, {"pauseId", pauseId}});
	CHECK(Ok(stack));
	CHECK(stack["body"]["total"].get<int>() >= 1);
	json top = stack["body"]["frames"][0];
	CHECK_EQ(top["line"].get<int>(), 9);
	CHECK_EQ(top["chunkName"].get<std::string>(), std::string("@game/main.lua"));
	uint64_t frameId = top["frameId"];

	json stale = c.Request("stackTrace", {{"vmId", vmId}, {"pauseId", pauseId + 100}});
	CHECK_EQ(stale["error"]["code"].get<std::string>(), std::string("STALE_PAUSE"));

	json scopes = c.Request("scopes", {{"frameId", frameId}});
	CHECK(Ok(scopes));
	uint64_t localsRef = 0;
	for (auto& s : scopes["body"]["scopes"]) if (s["name"] == "locals") localsRef = s["ref"];
	CHECK(localsRef != 0);
	json locals = c.Request("variables", {{"ref", localsRef}});
	CHECK(Ok(locals));
	uint64_t tRef = 0;
	bool hasI = false;
	for (auto& v : locals["body"]["variables"]) {
		if (v["name"] == "i") hasI = true;
		if (v["name"] == "t") tRef = v["ref"];
	}
	CHECK(hasI);
	CHECK(tRef != 0);
	json tVars = c.Request("variables", {{"ref", tRef}});
	CHECK(Ok(tVars));
	bool hasName = false, hasList = false, has10 = false;
	for (auto& v : tVars["body"]["variables"]) {
		if (v["name"] == "name") { hasName = true; CHECK_EQ(v["value"].get<std::string>(), std::string("\"x\"")); }
		if (v["name"] == "list") { hasList = true; CHECK_EQ(v["indexedCount"].get<int>(), 3); CHECK_EQ(v["evaluateName"].get<std::string>(), std::string("t.list")); }
		if (v["name"] == "[10]") has10 = true;
	}
	CHECK(hasName && hasList && has10);
	json paged = c.Request("variables", {{"ref", tRef}, {"start", 1}, {"count", 1}});
	CHECK_EQ(paged["body"]["variables"].size(), static_cast<size_t>(1));
	CHECK_EQ(paged["body"]["total"].get<int>(), 3);

	json e1 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "t.list[2]"}});
	CHECK(Ok(e1));
	CHECK_EQ(e1["body"]["value"].get<std::string>(), std::string("2"));
	json e2 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "i * 2 + #t.list"}, {"mode", "lua"}});
	CHECK(Ok(e2));
	CHECK_EQ(e2["body"]["type"].get<std::string>(), std::string("number"));
	json e3 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "i + "}, {"mode", "lua"}});
	CHECK_EQ(e3["error"]["code"].get<std::string>(), std::string("EVAL_ERROR"));
	json e4 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "while true do end"}, {"mode", "lua"}});
	CHECK_EQ(e4["error"]["code"].get<std::string>(), std::string("TIMEOUT"));
	json e5 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "os.exit()"}});
	CHECK_EQ(e5["error"]["code"].get<std::string>(), std::string("INVALID_ARGS"));
	CHECK(Ok(c.Request("setOptions", {{"allowLuaEval", false}})));
	json e6 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "1"}, {"mode", "lua"}});
	CHECK_EQ(e6["error"]["code"].get<std::string>(), std::string("EVAL_FORBIDDEN"));
	CHECK(Ok(c.Request("setOptions", {{"allowLuaEval", true}})));

	// 改局部变量：i = 1000，tick 返回 1001
	json sv = c.Request("setVariable", {{"ref", localsRef}, {"name", "i"}, {"value", "1000"}});
	CHECK(Ok(sv));
	CHECK_EQ(sv["body"]["value"].get<std::string>(), std::string("1000"));
	json e7 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "i"}});
	CHECK_EQ(e7["body"]["value"].get<std::string>(), std::string("1000"));

	// 单步进入 add
	json s1 = c.Request("stepIn", {{"vmId", vmId}, {"pauseId", pauseId}});
	CHECK(Ok(s1));
	json resumed = c.WaitEvent("resumed");
	CHECK(resumed.is_object());
	json p2 = c.WaitEvent("paused");
	CHECK(p2.is_object());
	if (!p2.is_object()) return;
	CHECK_EQ(p2["body"]["reason"].get<std::string>(), std::string("step"));
	CHECK_EQ(p2["body"]["top"]["line"].get<int>(), 4);
	// 旧的 frameId 失效
	json staleRef = c.Request("scopes", {{"frameId", frameId}});
	CHECK_EQ(staleRef["error"]["code"].get<std::string>(), std::string("STALE_REFERENCE"));
	// 单步跳出：回到 tick
	CHECK(Ok(c.Request("stepOut", {{"vmId", vmId}, {"pauseId", p2["body"]["pauseId"]}})));
	json p3 = c.WaitEvent("paused");
	CHECK(p3.is_object());
	if (!p3.is_object()) return;
	int outLine = p3["body"]["top"]["line"].get<int>();
	CHECK(outLine == 9 || outLine == 10);
	// 单步跳过
	CHECK(Ok(c.Request("stepOver", {{"vmId", vmId}, {"pauseId", p3["body"]["pauseId"]}})));
	json p4 = c.WaitEvent("paused");
	CHECK(p4.is_object());
	if (!p4.is_object()) return;
	CHECK_EQ(p4["body"]["top"]["line"].get<int>(), outLine + 1);
	json r2 = c.Request("evaluate", {{"frameId", p4["body"]["top"]["frameId"]}, {"expression", "r"}});
	CHECK_EQ(r2["body"]["value"].get<std::string>(), std::string("1001"));

	CHECK(Ok(SetBps(c, json::array())));
	CHECK(Ok(Continue(c, p4)));
	c.Request("disconnect");
}

TEST_CASE("session.condition_hit_logpoint_probe") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c)));
	g_rev = 0;
	json r = SetBps(c, json::array({
		Bp("cond", 10, {{"condition", "i % 7 == 0"}}),
		Bp("log", 4, {{"logMessage", "add {a} {b}"}}),
		Bp("probe", 5, {{"capture", json::array({"sum", "nope.x"})}, {"autoContinue", true}}),
		Bp("bad", 12, {{"condition", "i +"}}),
		Bp("badHit", 12, {{"hitCondition", "x"}}),
	}));
	CHECK(Ok(r));
	int invalid = 0;
	for (auto& b : r["body"]["breakpoints"]) if (b["state"] == "invalid") ++invalid;
	CHECK_EQ(invalid, 2);
	CHECK(Ok(c.Request("configurationDone")));
	json p = c.WaitEvent("paused");
	CHECK(p.is_object());
	if (!p.is_object()) return;
	CHECK_EQ(p["body"]["breakpointIds"][0].get<std::string>(), std::string("cond"));
	json e = c.Request("evaluate", {{"frameId", p["body"]["top"]["frameId"]}, {"expression", "i"}});
	CHECK(Ok(e));
	CHECK_EQ(std::stoll(e["body"]["value"].get<std::string>()) % 7, 0LL);
	json log = c.WaitEvent("output", 2000, [](const json& b) { return b["category"] == "logpoint"; });
	CHECK(log.is_object());
	if (log.is_object()) CHECK(log["body"]["text"].get<std::string>().rfind("add ", 0) == 0);
	json hit = c.WaitEvent("breakpointHit", 2000, [](const json& b) { return b["id"] == "probe"; });
	CHECK(hit.is_object());
	if (hit.is_object()) {
		CHECK_EQ(hit["body"]["captures"][0]["type"].get<std::string>(), std::string("number"));
		CHECK(hit["body"]["captures"][1].contains("error") || hit["body"]["captures"][1]["value"] == "nil");
	}
	// 命中次数：第 3 次才停
	CHECK(Ok(SetBps(c, json::array({Bp("hit3", 11, {{"hitCondition", "== 3"}})}))));
	CHECK(Ok(Continue(c, p)));
	json p2 = c.WaitEvent("paused", 5000);
	CHECK(p2.is_object());
	if (!p2.is_object()) return;
	CHECK_EQ(p2["body"]["breakpointIds"][0].get<std::string>(), std::string("hit3"));
	CHECK(Ok(SetBps(c, json::array())));
	CHECK(Ok(Continue(c, p2)));
	c.Request("disconnect");
}

TEST_CASE("session.function_breakpoints") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	json hello = Hello(c);
	CHECK(Ok(hello));
	bool hasCap = false;
	for (auto& cap : hello["body"]["caps"]) if (cap == "functionBreakpoints") hasCap = true;
	CHECK(hasCap);
	g_rev = 0;
	auto Fn = [](const char* id, const char* fn, json extra = json::object()) {
		json bp = {{"id", id}, {"function", fn}};
		for (auto& kv : extra.items()) bp[kv.key()] = kv.value();
		return bp;
	};
	// 按全局表找到函数值：停在函数第一行（line 4），状态带函数定义行
	json r = SetBps(c, json::array({Fn("fn-add", "GameMod.add"), Fn("fn-bad", "a:b:c"), Fn("fn-none", "noSuchFunction")}));
	CHECK(Ok(r));
	CHECK_EQ(r["body"]["breakpoints"][0]["state"].get<std::string>(), std::string("pending"));
	CHECK_EQ(r["body"]["breakpoints"][1]["state"].get<std::string>(), std::string("invalid"));
	CHECK(Ok(c.Request("configurationDone")));
	json st = c.WaitEvent("breakpointStatus", 5000, [](const json& b) { return b["id"] == "fn-add"; });
	CHECK(st.is_object());
	if (st.is_object()) {
		CHECK_EQ(st["body"]["state"].get<std::string>(), std::string("verified"));
		CHECK_EQ(st["body"]["chunkName"].get<std::string>(), std::string("@game/main.lua"));
		CHECK_EQ(st["body"]["line"].get<int>(), 3);
	}
	json p = c.WaitEvent("paused");
	CHECK(p.is_object());
	if (!p.is_object()) return;
	CHECK_EQ(p["body"]["reason"].get<std::string>(), std::string("breakpoint"));
	CHECK_EQ(p["body"]["breakpointIds"][0].get<std::string>(), std::string("fn-add"));
	CHECK_EQ(p["body"]["top"]["line"].get<int>(), 4);
	json b = c.Request("evaluate", {{"frameId", p["body"]["top"]["frameId"]}, {"expression", "b"}});
	CHECK_EQ(b["body"]["value"].get<std::string>(), std::string("1"));

	// 找不到函数值（local 模块表 M）：按调用处的名字 + 文件名兜底，条件在函数帧里求值
	CHECK(Ok(SetBps(c, json::array({Fn("fn-main", "main.add", {{"condition", "a % 5 == 0"}})}))));
	CHECK(Ok(Continue(c, p)));
	json p2 = c.WaitEvent("paused", 5000);
	CHECK(p2.is_object());
	if (!p2.is_object()) return;
	CHECK_EQ(p2["body"]["breakpointIds"][0].get<std::string>(), std::string("fn-main"));
	CHECK_EQ(p2["body"]["top"]["line"].get<int>(), 4);
	json a = c.Request("evaluate", {{"frameId", p2["body"]["top"]["frameId"]}, {"expression", "a"}});
	CHECK_EQ(std::stoll(a["body"]["value"].get<std::string>()) % 5, 0LL);

	// 宿主从 C 调进来的（调用处没有名字）按函数值也能命中；日志断点不停，命中次数照算
	CHECK(Ok(SetBps(c, json::array({Fn("fn-tick", "GameMod:tick", {{"logMessage", "tick {i}"}, {"hitCondition", ">= 2"}}),
		Fn("fn-src", "GameMod.add", {{"source", {{"path", "D:/proj/other/elsewhere.lua"}}}})}))));
	CHECK(Ok(Continue(c, p2)));
	json log = c.WaitEvent("output", 3000, [](const json& b) { return b["category"] == "logpoint"; });
	CHECK(log.is_object());
	if (log.is_object()) {
		CHECK(log["body"]["text"].get<std::string>().rfind("tick ", 0) == 0);
		CHECK_EQ(log["body"]["line"].get<int>(), 8);
	}
	// source 对不上的函数断点不停
	json none = c.WaitEvent("paused", 500);
	CHECK(none.is_null());
	CHECK(Ok(SetBps(c, json::array())));
	if (none.is_object()) Continue(c, none);
	c.Request("disconnect");
}

TEST_CASE("session.breakpoint_diagnostics") {
	// 断点 5 秒没匹配上任何执行过的脚本：报出执行过的 chunk 名
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c)));
	g_rev = 0;
	CHECK(Ok(c.Request("configurationDone")));
	json bp = {{"id", "nowhere"}, {"source", {{"path", "D:/proj/other/nothing.lua"}}}, {"line", 3}};
	CHECK(Ok(SetBps(c, json::array({bp}))));
	json diag = c.WaitEvent("output", 8000, [](const json& b) { return b["category"] == "runtime"; });
	CHECK(diag.is_object());
	if (diag.is_object()) {
		const std::string text = diag["body"]["text"].get<std::string>();
		CHECK(text.find("@game/main.lua") != std::string::npos);
		std::printf("  %s", text.c_str());
	}
	c.Request("disconnect");
}

TEST_CASE("session.pause_runToLine_output_disconnect") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c)));
	g_rev = 0;
	CHECK(Ok(c.Request("setOptions", {{"captureOutput", true}})));
	CHECK(Ok(c.Request("configurationDone")));
	json out = c.WaitEvent("output", 3000, [](const json& b) { return b["category"] == "stdout"; });
	CHECK(out.is_object());
	if (out.is_object()) CHECK(out["body"]["text"].get<std::string>().rfind("tick\t", 0) == 0);

	auto t0 = std::chrono::steady_clock::now();
	CHECK(Ok(c.Request("pause", {{"vmId", 0}})));
	json p = c.WaitEvent("paused");
	auto latency = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
	std::printf("  pause latency: %lld ms\n", static_cast<long long>(latency));
	CHECK(p.is_object());
	if (!p.is_object()) return;
	CHECK_EQ(p["body"]["reason"].get<std::string>(), std::string("pause"));

	json rtl = c.Request("runToLine", {{"vmId", p["body"]["vmId"]}, {"pauseId", p["body"]["pauseId"]},
		{"source", {{"path", kPath}}}, {"line", 11}});
	CHECK(Ok(rtl));
	json p2 = c.WaitEvent("paused");
	CHECK(p2.is_object());
	if (!p2.is_object()) return;
	CHECK_EQ(p2["body"]["reason"].get<std::string>(), std::string("runToLine"));
	CHECK_EQ(p2["body"]["top"]["line"].get<int>(), 11);

	// 暂停中断开：游戏必须继续跑，钩子要摘掉
	int64_t ticks = g_game->ticks;
	c.Close();
	bool progressed = false;
	for (int i = 0; i < 200; ++i) {
		if (g_game->ticks > ticks + 5 && g_game->hookCleared) { progressed = true; break; }
		std::this_thread::sleep_for(std::chrono::milliseconds(10));
	}
	CHECK(progressed);

	// 重连正常
	TestClient c2;
	CHECK(c2.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c2)));
	c2.Request("disconnect");
}



// ---- 宿主值提供者 v2：认领 {1, 2, 3} 这张表（t.list），像 UnLua 实例表一样加「UE 对象」分组 ----
namespace {
std::atomic<int> g_hostReleases{0};
std::atomic<int> g_hostDescribes{0};

void HostWrite(const EmmyHostValueQuery* q, const char* s) {
	// 分两次写，验证 runtime 会拼接
	size_t n = std::strlen(s);
	q->write(q->sink, s, n / 2);
	q->write(q->sink, s + n / 2, n - n / 2);
}

bool IsList(const EmmyHostValueQuery* q) {
	lua_State* L = q->L;
	if (!L || lua_type(L, q->index) != LUA_TTABLE || lua::RawLen(L, q->index) != 3) return false;
	lua_rawgeti(L, q->index, 1);
	bool one = lua_tointeger(L, -1) == 1;
	lua_pop(L, 1);
	return one;
}

int EMMY_HOST_API_CALL HostDescribe(void*, const EmmyHostValueQuery* q) {
	if (!IsList(q)) return EMMY_HOST_VALUE_UNAVAILABLE;
	++g_hostDescribes;
	HostWrite(q, R"({"valid":true,"typeName":"BP_Bag_C","summary":"BP_Bag_C /Game/Bag","kind":"ueObject","expandable":true})");
	return EMMY_HOST_VALUE_OK;
}

int EMMY_HOST_API_CALL HostList(void*, const EmmyHostValueQuery* q) {
	if (q->hostRef && std::strcmp(q->hostRef, "c1") == 0) {
		HostWrite(q, R"({"total":1,"children":[{"name":"X","type":"number","value":"1.5"}]})");
		return EMMY_HOST_VALUE_OK;
	}
	if (q->hostRef) return EMMY_HOST_VALUE_UNAVAILABLE;
	// 按 start / count 分页（验证 runtime 原样传下来）
	const char* items[] = {R"J({"name":"Capacity","type":"number","value":"20","typeName":"int32"})J",
		R"J({"name":"Location","type":"userdata","value":"(X=1.5)","kind":"ueStruct","expandable":true,"childRef":"c1"})J"};
	std::string out = R"({"total":2,"children":[)";
	for (uint32_t i = q->start, n = 0; i < 2 && n < q->count; ++i, ++n) {
		if (n) out += ",";
		out += items[i];
	}
	out += "]}";
	HostWrite(q, out.c_str());
	return EMMY_HOST_VALUE_OK;
}

void EMMY_HOST_API_CALL HostRelease(void*, uint64_t, uint64_t) {
	++g_hostReleases;
}
} // namespace

TEST_CASE("session.host_value_provider") {
	EmmyHostValueProviderV2 p{};
	p.size = sizeof(p);
	p.version = EMMY_HOST_VALUE_ABI_VERSION;
	p.name = "test-host";
	p.describe = &HostDescribe;
	p.listChildren = &HostList;
	p.releasePause = &HostRelease;
	uint64_t pid = Emmy_RegisterHostValueProviderV2(&p);
	// 宿主定期重注册（UELink 每 2 秒）不会产生重复项
	CHECK_EQ(Emmy_RegisterHostValueProviderV2(&p), pid);
	CHECK_EQ(Emmy_HasHostValueProviderV2(pid), 1);
	CHECK_EQ(Emmy_HasHostValueProviderV2(pid + 1000), 0);
	CHECK(pid != 0);

	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c)));
	g_rev = 0;
	CHECK(Ok(SetBps(c, json::array({Bp("hv", 9)}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	if (!pz.is_object()) { Emmy_UnregisterHostValueProviderV2(pid); return; }
	uint64_t vmId = pz["body"]["vmId"], pauseId = pz["body"]["pauseId"];
	json stack = c.Request("stackTrace", {{"vmId", vmId}, {"pauseId", pauseId}});
	uint64_t frameId = stack["body"]["frames"][0]["frameId"];
	json scopes = c.Request("scopes", {{"frameId", frameId}});
	uint64_t localsRef = 0;
	for (auto& s : scopes["body"]["scopes"]) if (s["name"] == "locals") localsRef = s["ref"];
	uint64_t tRef = 0;
	json lv = c.Request("variables", {{"ref", localsRef}});
	for (auto& v : lv["body"]["variables"]) if (v["name"] == "t") tRef = v["ref"];
	uint64_t listRef = 0;
	json tv = c.Request("variables", {{"ref", tRef}});
	for (auto& v : tv["body"]["variables"]) {
		if (v["name"] != "list") continue;
		CHECK_EQ(v["value"].get<std::string>(), std::string("BP_Bag_C /Game/Bag"));
		CHECK_EQ(v["typeName"].get<std::string>(), std::string("BP_Bag_C"));
		CHECK_EQ(v["kind"].get<std::string>(), std::string("ueObject"));
		listRef = v["ref"];
	}
	CHECK(listRef != 0);
	json listVars = c.Request("variables", {{"ref", listRef}});
	uint64_t groupRef = 0;
	int luaItems = 0;
	for (auto& v : listVars["body"]["variables"]) {
		if (v.value("kind", "") == "group") groupRef = v["ref"];
		else if (v.value("indexedCount", -1) == -1 && v["name"].get<std::string>()[0] == '[') ++luaItems;
	}
	CHECK_EQ(luaItems, 3);
	CHECK(groupRef != 0);
	json ue = c.Request("variables", {{"ref", groupRef}});
	CHECK(Ok(ue));
	CHECK_EQ(ue["body"]["total"].get<int>(), 2);
	uint64_t locRef = 0;
	for (auto& v : ue["body"]["variables"]) if (v["name"] == "Location") locRef = v["ref"];
	CHECK(locRef != 0);
	json page2 = c.Request("variables", {{"ref", groupRef}, {"start", 1}, {"count", 1}});
	CHECK_EQ(page2["body"]["variables"].size(), static_cast<size_t>(1));
	CHECK_EQ(page2["body"]["variables"][0]["name"].get<std::string>(), std::string("Location"));
	CHECK_EQ(page2["body"]["total"].get<int>(), 2);
	json loc = c.Request("variables", {{"ref", locRef}});
	CHECK(Ok(loc));
	CHECK_EQ(loc["body"]["variables"][0]["name"].get<std::string>(), std::string("X"));

	CHECK(Ok(SetBps(c, json::array())));
	CHECK(Ok(Continue(c, pz)));
	CHECK(g_hostReleases.load() >= 1);
	CHECK_EQ(Emmy_UnregisterHostValueProviderV2(pid), 0);
	c.Request("disconnect");
}

TEST_CASE("session.rich_variables") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	json hello = Hello(c, json::array({"valuePath", "luaEval", "richVariables"}));
	CHECK(Ok(hello));
	bool rich = false;
	for (auto& cap : hello["body"]["caps"]) if (cap == "richVariables") rich = true;
	CHECK(rich);
	g_rev = 0;
	CHECK(Ok(SetBps(c, json::array({Bp("rv", 9)}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	if (!pz.is_object()) return;
	uint64_t vmId = pz["body"]["vmId"], pauseId = pz["body"]["pauseId"];
	json stack = c.Request("stackTrace", {{"vmId", vmId}, {"pauseId", pauseId}});
	uint64_t frameId = stack["body"]["frames"][0]["frameId"];

	// 一个两层继承的对象：Base 在 package.loaded 里，Panel 用 __cname 声明类名
	const char* build = R"L((function()
  local Base = {} Base.__index = Base
  function Base:Show(a, b) end
  Base.speed = 5
  Base.name = "base"
  package.loaded["UI.BaseView"] = Base
  local Panel = setmetatable({}, {__index = Base})
  Panel.__index = Panel
  Panel.__cname = "BagPanel"
  function Panel:OnItemClick(index) end
  Panel.name = "panel"
  local o = setmetatable({item10 = 1, item2 = 2, Name = "N", name = "x", [7] = 0, ["display name"] = 1, [true] = 1,
    10, 20, 30, string.rep("ab", 40)}, Panel)
  o.onClick = function() end
  return o
end)())L";
	json ev = c.Request("evaluate", {{"frameId", frameId}, {"expression", build}, {"mode", "lua"}});
	CHECK(Ok(ev));
	if (!Ok(ev)) { std::printf("%s\n", ev.dump().c_str()); CHECK(Ok(Continue(c, pz))); return; }
	CHECK_EQ(ev["body"]["kind"].get<std::string>(), std::string("object"));
	CHECK_EQ(ev["body"]["typeName"].get<std::string>(), std::string("BagPanel"));
	CHECK(ev["body"]["value"].get<std::string>().rfind("BagPanel{", 0) == 0);
	CHECK(ev["body"].contains("objectId"));
	uint64_t oRef = ev["body"]["ref"];

	json ov = c.Request("variables", {{"ref", oRef}, {"maxValueLength", 16}});
	CHECK(Ok(ov));
	std::vector<std::string> names;
	for (auto& v : ov["body"]["variables"]) names.push_back(v["name"].get<std::string>());
	const std::vector<std::string> want = {"[1]", "[2]", "[3]", "[4]", "item2", "item10", "Name", "name",
		"[\"display name\"]", "[7]", "[true]"};
	CHECK(names == want);
	if (names != want) {
		for (auto& n : names) std::printf("  name: %s\n", n.c_str());
	}
	CHECK_EQ(ov["body"]["total"].get<int>(), 11);
	CHECK(ov["body"]["sorted"].get<bool>());
	for (auto& v : ov["body"]["variables"]) {
		if (v["name"] == "[4]") {
			CHECK_EQ(v["length"].get<int>(), 80);
			CHECK(v["value"].get<std::string>().find("（共 80 字节）") != std::string::npos);
		}
		if (v["name"] == "item2") {
			CHECK(!v.contains("evaluateName")); // lua 模式求值的结果没有路径
#if EMMY_LUA_VERSION_NUM >= 503
			CHECK_EQ(v["kind"].get<std::string>(), std::string("integer"));
#endif
		}
	}
	std::map<std::string, json> groups;
	for (auto& g : ov["body"]["groups"]) groups[g["name"].get<std::string>()] = g;
	// 方法、元表收在默认折叠的「内部」里，而且排在最后
	CHECK(groups.count("内部"));
	CHECK_EQ(ov["body"]["groups"].back()["name"].get<std::string>(), std::string("内部"));
	CHECK(!groups.count("方法"));
	if (groups.count("内部")) {
		json inner = c.Request("variables", {{"ref", groups["内部"]["ref"]}});
		for (auto& g : inner["body"]["variables"]) groups[g["name"].get<std::string>()] = g;
	}
	CHECK(groups.count("方法"));
	CHECK(groups.count("继承自 BagPanel"));
	CHECK(groups.count("继承自 BaseView"));
	CHECK(groups.count("元表 BagPanel"));
	if (groups.count("方法")) {
		CHECK_EQ(groups["方法"]["namedCount"].get<int>(), 1);
		json m = c.Request("variables", {{"ref", groups["方法"]["ref"]}});
		CHECK_EQ(m["body"]["variables"][0]["name"].get<std::string>(), std::string("onClick"));
		CHECK_EQ(m["body"]["variables"][0]["kind"].get<std::string>(), std::string("function"));
		CHECK(m["body"]["variables"][0].contains("location"));
	}
	if (groups.count("继承自 BaseView")) {
		CHECK_EQ(groups["继承自 BaseView"]["value"].get<std::string>(), std::string("module UI.BaseView"));
		json b = c.Request("variables", {{"ref", groups["继承自 BaseView"]["ref"]}});
		CHECK(Ok(b));
		std::vector<std::string> bn;
		for (auto& v : b["body"]["variables"]) bn.push_back(v["name"].get<std::string>());
		CHECK((bn == std::vector<std::string>{"name", "speed", "Show"}));
		for (auto& v : b["body"]["variables"]) {
			bool shadowed = false, inherited = false;
			for (auto& a : v.value("attrs", json::array())) {
				if (a == "shadowed") shadowed = true;
				if (a == "inherited") inherited = true;
			}
			CHECK(inherited);
			CHECK_EQ(shadowed, v["name"] == "name");
#if EMMY_LUA_VERSION_NUM >= 502
			if (v["name"] == "Show") CHECK_EQ(v["value"].get<std::string>(), std::string("function BaseView:Show(a, b)"));
#endif
		}
	}
	if (groups.count("元表 BagPanel")) {
		json m = c.Request("variables", {{"ref", groups["元表 BagPanel"]["ref"]}});
		CHECK(Ok(m));
		// 元方法排最前
		auto& vars = m["body"]["variables"];
		CHECK_EQ(vars[0]["name"].get<std::string>(), std::string("__index"));
		CHECK_EQ(vars[0]["attrs"][0].get<std::string>(), std::string("metamethod"));
		CHECK_EQ(vars[1]["name"].get<std::string>(), std::string("__cname"));
		CHECK(!vars[1].contains("attrs"));
	}
	// 数组段按下标区间分页；分组只在首页
	json idx = c.Request("variables", {{"ref", oRef}, {"filter", "indexed"}, {"start", 1}, {"count", 2}});
	CHECK(Ok(idx));
	CHECK_EQ(idx["body"]["variables"].size(), static_cast<size_t>(2));
	CHECK_EQ(idx["body"]["variables"][0]["name"].get<std::string>(), std::string("[2]"));
	CHECK_EQ(idx["body"]["total"].get<int>(), 4);
	CHECK(!idx["body"].contains("groups"));
	json named = c.Request("variables", {{"ref", oRef}, {"filter", "named"}, {"start", 2}, {"count", 2}});
	CHECK_EQ(named["body"]["variables"][0]["name"].get<std::string>(), std::string("Name"));
	CHECK_EQ(named["body"]["total"].get<int>(), 7);
	// 原始视图：v3.0 的形状，元表是伪项
	json raw = c.Request("variables", {{"ref", oRef}, {"view", "raw"}});
	bool hasMetaItem = false;
	for (auto& v : raw["body"]["variables"]) if (v["name"] == "(metatable)") hasMetaItem = true;
	CHECK(hasMetaItem);
	CHECK(!raw["body"].contains("groups"));

	// 类表
	json cls = c.Request("evaluate", {{"frameId", frameId}, {"expression", "package.loaded['UI.BaseView']"}, {"mode", "lua"}});
	CHECK_EQ(cls["body"]["kind"].get<std::string>(), std::string("class"));
	CHECK_EQ(cls["body"]["value"].get<std::string>(), std::string("class BaseView (1 个方法)"));

	// 作用域：局部变量按声明顺序；全局里标准库收进「内置」；upvalue 不列 _ENV
	json scopes = c.Request("scopes", {{"frameId", frameId}});
	uint64_t localsRef = 0, upRef = 0, globalsRef = 0;
	for (auto& s : scopes["body"]["scopes"]) {
		if (s["name"] == "locals") localsRef = s["ref"];
		if (s["name"] == "upvalues") upRef = s["ref"];
		if (s["name"] == "globals") globalsRef = s["ref"];
	}
	json lv = c.Request("variables", {{"ref", localsRef}});
	CHECK_EQ(lv["body"]["variables"][0]["name"].get<std::string>(), std::string("i"));
	for (auto& v : lv["body"]["variables"]) {
		if (v["name"] == "t") CHECK_EQ(v["kind"].get<std::string>(), std::string("record"));
	}
	if (upRef) {
		json uv = c.Request("variables", {{"ref", upRef}});
		for (auto& v : uv["body"]["variables"]) CHECK(v["name"] != "_ENV");
	}
	json gv = c.Request("variables", {{"ref", globalsRef}, {"count", 1000}});
	CHECK(Ok(gv));
	bool hasPrint = false, hasBuiltins = false;
	for (auto& v : gv["body"]["variables"]) if (v["name"] == "print") hasPrint = true;
	for (auto& g : gv["body"]["groups"]) if (g["name"] == "内置") hasBuiltins = true;
	CHECK(!hasPrint);
	CHECK(hasBuiltins);

	// 关掉方法分组和元表后重取
	CHECK(Ok(c.Request("setOptions", {{"variableView", {{"groupMethods", false}, {"showMetatable", false}}}})));
	json ev2 = c.Request("evaluate", {{"frameId", frameId}, {"expression", "package.loaded['UI.BaseView']"}, {"mode", "lua"}});
	json bv = c.Request("variables", {{"ref", ev2["body"]["ref"]}});
	for (auto& g : bv["body"].value("groups", json::array())) CHECK(g["name"].get<std::string>().rfind("元表", 0) != 0);
	CHECK_EQ(c.Request("setOptions", {{"variableView", {{"sort", "bogus"}}}})["error"]["code"].get<std::string>(), std::string("INVALID_ARGS"));

	CHECK(Ok(SetBps(c, json::array())));
	CHECK(Ok(Continue(c, pz)));
	c.Request("disconnect");
}

TEST_CASE("session.expand_tostring_coroutine") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c, json::array({"valuePath", "luaEval", "richVariables"}))));
	g_rev = 0;
	CHECK(Ok(SetBps(c, json::array({Bp("ex", 9)}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	if (!pz.is_object()) return;
	json stack = c.Request("stackTrace", {{"vmId", pz["body"]["vmId"]}, {"pauseId", pz["body"]["pauseId"]}});
	uint64_t frameId = stack["body"]["frames"][0]["frameId"];
	json scopes = c.Request("scopes", {{"frameId", frameId}});
	uint64_t localsRef = 0, globalsRef = 0;
	for (auto& s : scopes["body"]["scopes"]) {
		if (s["name"] == "locals") localsRef = s["ref"];
		if (s["name"] == "globals") globalsRef = s["ref"];
	}
	// expand：一次往返把 t 和 t.list 的首页都带回来
	json lv = c.Request("variables", {{"ref", localsRef}, {"expand", json::array({json::array({"t"}), json::array({"t", "list"})})}});
	CHECK(Ok(lv));
	bool expanded = false;
	for (auto& v : lv["body"]["variables"]) {
		if (v["name"] != "t") continue;
		CHECK(v.contains("children"));
		if (!v.contains("children")) break;
		for (auto& cv : v["children"]["variables"]) {
			if (cv["name"] != "list") continue;
			CHECK(cv.contains("children"));
			if (cv.contains("children")) {
				CHECK_EQ(cv["children"]["total"].get<int>(), 3);
				CHECK_EQ(cv["children"]["variables"][2]["value"].get<std::string>(), std::string("3"));
				expanded = true;
			}
		}
	}
	CHECK(expanded);

	// __tostring：table 默认调用；出错的标 helperError；死循环被预算打断
	json ts = c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"},
		{"expression", "setmetatable({a = 1}, {__tostring = function(t) return 'Pretty#' .. t.a end})"}});
	CHECK_EQ(ts["body"]["value"].get<std::string>(), std::string("Pretty#1"));
	json bad = c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"},
		{"expression", "setmetatable({}, {__tostring = function() error('nope') end})"}});
	CHECK_EQ(bad["body"]["attrs"][0].get<std::string>(), std::string("helperError"));
	json loop = c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"},
		{"expression", "setmetatable({}, {__tostring = function() while true do end end})"}});
	CHECK(Ok(loop));
	CHECK(loop["body"].value("hint", "").find("__tostring") != std::string::npos);
	CHECK(Ok(c.Request("setOptions", {{"variableView", {{"toString", "never"}}}})));
	json never = c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"},
		{"expression", "setmetatable({a = 1}, {__tostring = function(t) return 'Pretty' end})"}});
	CHECK(never["body"]["value"].get<std::string>() != "Pretty");
	CHECK(Ok(c.Request("setOptions", {{"variableView", {{"toString", "tables"}}}})));

	// 挂起的协程：展开得到 status 和「调用栈」分组，帧里能看到局部变量
	json gv = c.Request("variables", {{"ref", globalsRef}, {"count", 1000}});
	uint64_t coRef = 0;
	for (auto& v : gv["body"]["variables"]) {
		if (v["name"] != "CO") continue;
		CHECK_EQ(v["kind"].get<std::string>(), std::string("coroutine"));
		CHECK(v["value"].get<std::string>().rfind("suspended", 0) == 0);
		coRef = v["ref"];
	}
	CHECK(coRef != 0);
	json cov = c.Request("variables", {{"ref", coRef}});
	CHECK_EQ(cov["body"]["variables"][0]["value"].get<std::string>(), std::string("\"suspended\""));
	uint64_t stackRef = 0;
	for (auto& g : cov["body"]["groups"]) if (g["name"] == "调用栈") stackRef = g["ref"];
	CHECK(stackRef != 0);
	json frames = c.Request("variables", {{"ref", stackRef}});
	uint64_t frameLocals = 0;
	for (auto& f : frames["body"]["variables"]) {
		CHECK_EQ(f["kind"].get<std::string>(), std::string("frame"));
		if (!frameLocals && f["ref"].get<uint64_t>()) frameLocals = f["ref"];
	}
	CHECK(frameLocals != 0);
	json fl = c.Request("variables", {{"ref", frameLocals}});
	bool hasInner = false;
	for (auto& v : fl["body"]["variables"]) {
		if (v["name"] == "inner") {
			hasInner = true;
			CHECK_EQ(v["value"].get<std::string>(), std::string("42"));
			CHECK(!v.contains("evaluateName"));
		}
	}
	CHECK(hasInner);
	// threads + stackTrace{threadId}：直接取挂起协程的栈
	json th = c.Request("threads", {{"vmId", pz["body"]["vmId"]}});
	std::string coId;
	for (auto& t : th["body"]["threads"]) if (t["status"] == "suspended") coId = t["threadId"];
	CHECK(!coId.empty());
	json cst = c.Request("stackTrace", {{"vmId", pz["body"]["vmId"]}, {"pauseId", pz["body"]["pauseId"]}, {"threadId", coId}});
	CHECK(Ok(cst));
	bool yieldLine = false;
	uint64_t coFrame = 0;
	for (auto& f : cst["body"]["frames"]) {
		if (f["line"] == 18) { // coroutine.yield 那一行
			yieldLine = true;
			coFrame = f["frameId"];
		}
	}
	CHECK(yieldLine);
	// 别的协程的帧上求值 / 改值：要在那个协程的栈上找局部变量，不能拿暂停线程同一层的帧（以前会得到 nil）
	for (const char* mode : {"valuePath", "lua"}) {
		json ce = c.Request("evaluate", {{"frameId", coFrame}, {"expression", "inner"}, {"mode", mode}});
		CHECK(Ok(ce));
		if (Ok(ce)) CHECK_EQ(ce["body"]["value"].get<std::string>(), std::string("42"));
	}
	json cs = c.Request("scopes", {{"frameId", coFrame}});
	uint64_t coLocals = 0;
	for (auto& s : cs["body"]["scopes"]) if (s["name"] == "locals") coLocals = s["ref"];
	CHECK(coLocals != 0);
	CHECK(Ok(c.Request("variables", {{"ref", coLocals}})));
	json csv = c.Request("setVariable", {{"ref", coLocals}, {"name", "inner"}, {"value", "inner + 1"}, {"mode", "lua"}});
	CHECK(Ok(csv));
	if (Ok(csv)) CHECK_EQ(csv["body"]["value"].get<std::string>(), std::string("43"));
	json ce2 = c.Request("evaluate", {{"frameId", coFrame}, {"expression", "inner"}});
	if (Ok(ce2)) CHECK_EQ(ce2["body"]["value"].get<std::string>(), std::string("43"));
	c.Request("setVariable", {{"ref", coLocals}, {"name", "inner"}, {"value", "42"}});
	CHECK_EQ(c.Request("stackTrace", {{"vmId", pz["body"]["vmId"]}, {"threadId", "t1234"}})["error"]["code"].get<std::string>(), std::string("INVALID_ARGS"));

	CHECK(Ok(SetBps(c, json::array())));
	CHECK(Ok(Continue(c, pz)));
	c.Request("disconnect");
}

TEST_CASE("session.exceptions") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	json hello = Hello(c, json::array({"valuePath", "luaEval", "exceptions"}));
	bool cap = false;
	for (auto& x : hello["body"]["caps"]) if (x == "exceptions") cap = true;
	CHECK(cap);
	g_rev = 0;
	CHECK_EQ(c.Request("setOptions", {{"exceptions", "bogus"}})["error"]["code"].get<std::string>(), std::string("INVALID_ARGS"));
	CHECK(Ok(c.Request("setOptions", {{"exceptions", "uncaught"}})));
	CHECK(Ok(SetBps(c, json::array({Bp("arm", 9)}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	if (!pz.is_object()) return;
	json stack = c.Request("stackTrace", {{"vmId", pz["body"]["vmId"]}, {"pauseId", pz["body"]["pauseId"]}});
	uint64_t frameId = stack["body"]["frames"][0]["frameId"];
	// 被 pcall 接住的错误在 uncaught 模式下不停；没接住的停在出错那一行
	CHECK(Ok(c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"}, {"expression", "rawset(_G, 'FAIL2', true)"}})));
	CHECK(Ok(c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"}, {"expression", "rawset(_G, 'FAIL', true)"}})));
	CHECK(Ok(SetBps(c, json::array())));
	g_game->lastErrorExpected = true;
	CHECK(Ok(Continue(c, pz)));
	json ex = c.WaitEvent("paused");
	CHECK(ex.is_object());
	if (!ex.is_object()) return;
	CHECK_EQ(ex["body"]["reason"].get<std::string>(), std::string("exception"));
	CHECK_EQ(ex["body"]["top"]["line"].get<int>(), 12);
	CHECK(ex["body"]["exception"]["uncaught"].get<bool>());
	CHECK(ex["body"]["exception"]["message"].get<std::string>().find("index") != std::string::npos);
	CHECK(ex["body"]["exception"]["traceback"].get<std::string>().find("main.lua:12") != std::string::npos);
	// 栈顶是出错的 Lua 函数，错误处理函数不显示；局部变量还在
	json st2 = c.Request("stackTrace", {{"vmId", ex["body"]["vmId"]}, {"pauseId", ex["body"]["pauseId"]}});
	CHECK_EQ(st2["body"]["frames"][0]["line"].get<int>(), 12);
	json e = c.Request("evaluate", {{"frameId", st2["body"]["frames"][0]["frameId"]}, {"expression", "r"}});
	CHECK(Ok(e));

	// all：pcall 接住的也停
	CHECK(Ok(c.Request("setOptions", {{"exceptions", "all"}})));
	CHECK(Ok(c.Request("evaluate", {{"frameId", st2["body"]["frames"][0]["frameId"]}, {"mode", "lua"}, {"expression", "rawset(_G, 'FAIL2', true)"}})));
	CHECK(Ok(Continue(c, ex)));
	json caught = c.WaitEvent("paused");
	CHECK(caught.is_object());
	if (!caught.is_object()) return;
	CHECK_EQ(caught["body"]["reason"].get<std::string>(), std::string("exception"));
	CHECK(!caught["body"]["exception"]["uncaught"].get<bool>());
	CHECK(caught["body"]["exception"]["message"].get<std::string>().find("caught boom") != std::string::npos);
	CHECK_EQ(caught["body"]["top"]["line"].get<int>(), 12);
	CHECK(Ok(c.Request("setOptions", {{"exceptions", "none"}})));
	CHECK(Ok(Continue(c, caught)));
	c.Request("disconnect");
}


// 注入方式「先附加再开始 PIE」：新 VM 在跑第一段 Lua 前等 IDE 下完断点（Emmy_WaitIDEForVm），
// 模块顶层只执行一次的代码也要停住（之前钩子要过 1000 条指令才刷新，顶层几行早跑完了）
TEST_CASE("session.new_vm_top_level_breakpoint") {
	std::atomic<bool> done{false};
	std::thread late([&] {
		lua_State* L = luaL_newstate();
		luaL_openlibs(L);
		Emmy_RegisterLuaVm(L, "late");
		Emmy_WaitIDEForVm(L, 5000);
		const char* src = "local M = {}\nlocal inited = 0\ninited = inited + 1\nreturn M\n";
		if (lua::LoadBuffer(L, src, std::strlen(src), "@game/late.lua") == LUA_OK) lua_pcall(L, 0, 1, 0);
		Emmy_UnregisterLuaVm(L);
		lua_close(L);
		done = true;
	});
	std::this_thread::sleep_for(std::chrono::milliseconds(100));
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c)));
	g_rev = 0;
	json bp = {{"id", "late"}, {"source", {{"path", "D:/proj/game/late.lua"}}}, {"line", 3}};
	CHECK(Ok(SetBps(c, json::array({bp}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused", 5000);
	CHECK(pz.is_object());
	if (pz.is_object()) {
		CHECK_EQ(pz["body"]["top"]["line"].get<int>(), 3);
		CHECK(Ok(SetBps(c, json::array())));
		CHECK(Ok(Continue(c, pz)));
	}
	c.Request("disconnect");
	late.join();
	CHECK(done.load());
}

TEST_CASE("session.errorReport") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	json hello = Hello(c, json::array({"valuePath", "luaEval", "errorReport"}));
	bool cap = false;
	for (auto& x : hello["body"]["caps"]) if (x == "errorReport") cap = true;
	CHECK(cap);
	g_rev = 0;
	CHECK_EQ(c.Request("setOptions", {{"collectErrors", "bogus"}})["error"]["code"].get<std::string>(), std::string("INVALID_ARGS"));
	CHECK(Ok(c.Request("setOptions", {{"collectErrors", "all"}})));
	CHECK(Ok(SetBps(c, json::array({Bp("arm", 9)}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	if (!pz.is_object()) return;
	json stack = c.Request("stackTrace", {{"vmId", pz["body"]["vmId"]}, {"pauseId", pz["body"]["pauseId"]}});
	uint64_t frameId = stack["body"]["frames"][0]["frameId"];
	// 被 pcall 接住的错误连续 5 次 + 一次没接住的：只计数，不停
	CHECK(Ok(c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"}, {"expression", "rawset(_G, 'REPEAT_ERR', 5)"}})));
	CHECK(Ok(c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"}, {"expression", "rawset(_G, 'FAIL', true)"}})));
	CHECK(Ok(SetBps(c, json::array())));
	g_game->lastErrorExpected = true;
	CHECK(Ok(Continue(c, pz)));
	json first = c.WaitEvent("luaError");
	CHECK(first.is_object());
	if (!first.is_object()) return;
	CHECK(first["body"].contains("traceback"));
	CHECK_EQ(first["body"]["line"].get<int>(), 12);
	std::this_thread::sleep_for(std::chrono::milliseconds(300));
	json list = c.Request("errors", json::object());
	CHECK(Ok(list));
	bool sawRepeat = false, sawUncaught = false;
	for (auto& it : list["body"]["items"]) {
		const std::string m = it["message"].get<std::string>();
		if (m.find("repeat boom") != std::string::npos) {
			sawRepeat = true;
			CHECK_EQ(it["count"].get<int>(), 5);
			CHECK(!it.contains("uncaught"));
			CHECK_EQ(it["chunkName"].get<std::string>(), std::string("@game/main.lua"));
		}
		if (m.find("index") != std::string::npos) {
			sawUncaught = true;
			CHECK(it.value("uncaught", false));
			CHECK(it["traceback"].get<std::string>().find("main.lua:12") != std::string::npos);
		}
	}
	CHECK(sawRepeat);
	CHECK(sawUncaught);
	// 没有暂停过
	CHECK(!c.WaitEvent("paused", 200).is_object());
	CHECK(Ok(c.Request("errors", {{"action", "clear"}})));
	CHECK(c.Request("errors", json::object())["body"]["items"].empty());
	CHECK(Ok(c.Request("setOptions", {{"collectErrors", "none"}})));
	c.Request("disconnect");
}

namespace {

std::string ReadHelper(const char* name) {
#ifdef EMMY_HELPERS_V3_DIR
	std::string path = std::string(EMMY_HELPERS_V3_DIR) + "/" + name + ".lua";
	FILE* f = std::fopen(path.c_str(), "rb");
	if (!f) return "";
	std::string out;
	char buf[4096];
	size_t n;
	while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
	std::fclose(f);
	return out;
#else
	(void)name;
	return "";
#endif
}

std::vector<std::string> Names(const json& page) {
	std::vector<std::string> out;
	for (auto& v : page["body"]["variables"]) out.push_back(v["name"].get<std::string>());
	return out;
}

bool HasAttr(const json& v, const char* attr) {
	for (auto& a : v.value("attrs", json::array())) if (a == attr) return true;
	return false;
}

} // namespace

// variable-view 11.1 / 11.2 / 13.4：显示规则（摘要模板、固定、隐藏、类名字段）和 helper v2 formatter
TEST_CASE("session.view_rules_and_formatters") {
	const std::string emmyLua = ReadHelper("emmy"), ueLua = ReadHelper("emmy_ue");
	if (emmyLua.empty() || ueLua.empty()) {
		std::printf("  跳过：找不到 helper v2 脚本\n");
		return;
	}
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	json hello = Hello(c, json::array({"valuePath", "luaEval", "richVariables", "viewRules", "helpers"}));
	CHECK(Ok(hello));
	bool viewRules = false;
	for (auto& cap : hello["body"]["caps"]) if (cap == "viewRules") viewRules = true;
	CHECK(viewRules);

	// 测试用的入口：真 emmy.lua / emmy_ue.lua，再注册几个 formatter
	const char* testMain = R"L(
local emmy = require("emmy")
require("emmy_ue")
emmy.registerFormatter {
  name = "Vec", match = { typeName = "Vec" },
  summary = function(v) return "(" .. v.x .. ", " .. v.y .. ")" end,
  count = function(v) return #v.items end,
  children = function(v, start, count)
    local out = {}
    for i = start + 1, math.min(#v.items, start + count) do out[#out + 1] = { name = "[" .. i .. "]", value = v.items[i] } end
    if start == 0 then out[1] = { name = "first", summary = "固定显示" } end
    return out
  end,
}
emmy.registerFormatter { name = "Bad", match = { typeName = "Bad" }, summary = function() error("boom") end }
emmy.registerFormatter { name = "Slow", match = function(obj, tn) return tn == "Slow" end, summary = function() while true do end end }
local Old = emmy.getProcessorBase():extend()
function Old:processSpecific(variable, obj)
  variable.value = "old:" .. obj.v
  local child = emmy.createNode()
  child.name = "v"
  child:query(obj.v, 1, true)
  variable:addChild(child)
end
emmy.registerProcessor("Old", Old)
return emmy
)L";
	json lh = c.Request("loadHelpers", {{"main", "test_main"}, {"scripts", json::array({
		{{"name", "emmy"}, {"source", emmyLua}},
		{{"name", "emmy_ue"}, {"source", ueLua}},
		{{"name", "test_main"}, {"source", testMain}},
	})}});
	CHECK(Ok(lh));
	json rules = {
		{"version", 1},
		{"classNameFields", {"__myclass"}},
		{"builtinGlobals", {"MY_GLOBAL"}},
		{"types", {
			{"BagPanel", {{"summary", "#{item2} {Name} {missing.x}"}, {"pin", {"name", "item10"}}, {"hide", {"item10", "speed"}}}},
			{"Pinned", {{"pin", {"b"}}}},
			{"Broken", {{"summary", "{a b}"}}},
		}},
	};
	json sr = c.Request("setViewRules", {{"rules", rules}});
	CHECK(Ok(sr));
	CHECK_EQ(sr["body"]["accepted"].get<int>(), 2);
	CHECK_EQ(sr["body"]["warnings"].size(), static_cast<size_t>(1));
	CHECK_EQ(sr["body"]["warnings"][0]["type"].get<std::string>(), std::string("Broken"));
	CHECK(Ok(c.Request("setOptions", {{"variableView", {{"helperBudgetMs", 50}}}})));
	g_rev = 0;
	CHECK(Ok(SetBps(c, json::array({Bp("vr", 9)}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	if (!pz.is_object()) return;
	uint64_t vmId = pz["body"]["vmId"], pauseId = pz["body"]["pauseId"];
	json stack = c.Request("stackTrace", {{"vmId", vmId}, {"pauseId", pauseId}});
	uint64_t frameId = stack["body"]["frames"][0]["frameId"];
	auto eval = [&](const char* expr) {
		return c.Request("evaluate", {{"frameId", frameId}, {"expression", expr}, {"mode", "lua"}});
	};

	// ---- 显示规则 ----
	json ev = eval(R"L((function()
  local Base = { speed = 5, Show = function(self) end } Base.__index = Base
  local Panel = setmetatable({}, {__index = Base}) Panel.__index = Panel
  Panel.__myclass = "BagPanel"
  return setmetatable({item10 = 1, item2 = 2, Name = "N", name = "x", zeta = true}, Panel)
end)())L");
	CHECK(Ok(ev));
	CHECK_EQ(ev["body"]["typeName"].get<std::string>(), std::string("BagPanel")); // classNameFields 生效
	CHECK_EQ(ev["body"]["value"].get<std::string>(), std::string("#2 N ?"));
	json ov = c.Request("variables", {{"ref", ev["body"]["ref"]}});
	CHECK((Names(ov) == std::vector<std::string>{"name", "item2", "Name", "zeta"}));
	CHECK(HasAttr(ov["body"]["variables"][0], "pinned"));
	CHECK(!HasAttr(ov["body"]["variables"][1], "pinned"));
	for (auto& g : ov["body"]["groups"]) {
		if (g["name"].get<std::string>().rfind("继承自", 0) != 0) continue;
		json b = c.Request("variables", {{"ref", g["ref"]}});
		for (auto& v : b["body"]["variables"]) CHECK(v["name"] != "speed"); // 继承来的也按实例类型的规则隐藏
	}
	// 原始视图不受规则影响
	json raw = c.Request("variables", {{"ref", ev["body"]["ref"]}, {"view", "raw"}});
	bool rawHasHidden = false;
	for (auto& v : raw["body"]["variables"]) if (v["name"] == "item10") rawHasHidden = true;
	CHECK(rawHasHidden);
	// 只有 pin 时摘要优先列固定字段
	json pv = eval("setmetatable({a = 1, b = 2}, {__name = 'Pinned'})");
	CHECK_EQ(pv["body"]["value"].get<std::string>(), std::string("{b=2, …}"));

	// ---- helper v2 ----
	json vec = eval("setmetatable({x = 1, y = 2, items = (function() local t = {} for i = 1, 250 do t[i] = i * 10 end return t end)()}, {__name = 'Vec'})");
	CHECK(Ok(vec));
	CHECK_EQ(vec["body"]["value"].get<std::string>(), std::string("(1, 2)"));
	CHECK_EQ(vec["body"]["typeName"].get<std::string>(), std::string("Vec"));
	json p1 = c.Request("variables", {{"ref", vec["body"]["ref"]}, {"start", 0}, {"count", 100}});
	CHECK(Ok(p1));
	CHECK_EQ(p1["body"]["total"].get<int>(), 250);
	CHECK_EQ(p1["body"]["variables"].size(), static_cast<size_t>(100));
	CHECK_EQ(p1["body"]["variables"][0]["name"].get<std::string>(), std::string("first"));
	CHECK_EQ(p1["body"]["variables"][0]["value"].get<std::string>(), std::string("固定显示"));
	json p3 = c.Request("variables", {{"ref", vec["body"]["ref"]}, {"start", 200}, {"count", 100}});
	CHECK_EQ(p3["body"]["variables"].size(), static_cast<size_t>(50));
	CHECK_EQ(p3["body"]["variables"][0]["name"].get<std::string>(), std::string("[201]"));
	CHECK_EQ(p3["body"]["variables"][0]["value"].get<std::string>(), std::string("2010"));

	json bad = eval("setmetatable({k = 1}, {__name = 'Bad'})");
	CHECK(Ok(bad));
	CHECK(HasAttr(bad["body"], "helperError"));
	CHECK(bad["body"]["hint"].get<std::string>().find("boom") != std::string::npos);
	CHECK(bad["body"]["value"].get<std::string>().find("k=1") != std::string::npos); // 退回默认显示

	auto t0 = std::chrono::steady_clock::now();
	json slow = eval("setmetatable({}, {__name = 'Slow'})");
	auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
	CHECK(Ok(slow));
	CHECK(HasAttr(slow["body"], "helperError")); // 死循环被预算打断
	CHECK(ms < 2000);

	json old = eval("setmetatable({v = 7}, {__name = 'Old'})");
	CHECK_EQ(old["body"]["value"].get<std::string>(), std::string("old:7"));
	json oc = c.Request("variables", {{"ref", old["body"]["ref"]}});
	CHECK_EQ(oc["body"]["variables"][0]["name"].get<std::string>(), std::string("v"));
	CHECK_EQ(oc["body"]["variables"][0]["value"].get<std::string>(), std::string("7"));

	// 规则清空后恢复默认
	CHECK(Ok(c.Request("setViewRules", {{"rules", json::object()}})));
	json ev3 = c.Request("variables", {{"ref", ev["body"]["ref"]}});
	CHECK(ev3.is_object());

	CHECK(Ok(SetBps(c, json::array())));
	CHECK(Ok(Continue(c, pz)));
	c.Request("disconnect");
}

TEST_CASE("session.profile_sampling") {
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	json hello = Hello(c, json::array({"profiler"}));
	CHECK(Ok(hello));
	bool hasCap = false;
	for (auto& cap : hello["body"]["caps"]) if (cap == "profiler") hasCap = true;
	CHECK(hasCap);
	CHECK(Ok(c.Request("configurationDone")));
	g_rev = 0;
	CHECK(Ok(SetBps(c, json::array())));

	CHECK_EQ(c.Request("profile", {{"action", "bogus"}})["error"]["code"].get<std::string>(), std::string("INVALID_ARGS"));
	CHECK_EQ(c.Request("profile", {{"action", "start"}, {"intervalInstructions", 5}})["error"]["code"].get<std::string>(),
		std::string("INVALID_ARGS"));
	CHECK_EQ(c.Request("profile", {{"action", "result"}, {"vmId", 999}})["error"]["code"].get<std::string>(), std::string("VM_NOT_FOUND"));

	json st = c.Request("profile", {{"action", "start"}, {"intervalInstructions", 100}});
	CHECK(Ok(st));
	CHECK_EQ(st["body"]["vms"].size(), static_cast<size_t>(1));
	// 采到 add 要看运气：Windows 上 sleep(500us) 实际睡 1–15 毫秒，tick 次数比 Linux 少两个数量级，
	// 固定等 500 毫秒常常一次 add 都采不到。改成轮询到条件满足（最多 10 秒）。
	json r;
	bool hasTick = false, hasAdd = false, foldedOk = false;
	double tickTotal = 0;
	for (int attempt = 0; attempt < 100 && !(hasTick && hasAdd && foldedOk); ++attempt) {
		std::this_thread::sleep_for(std::chrono::milliseconds(100));
		r = c.Request("profile", {{"action", "result"}, {"sort", "total"}, {"limit", 10}});
		if (!Ok(r)) break;
		hasTick = hasAdd = foldedOk = false;
		for (auto& f : r["body"]["functions"]) {
			if (f["source"] == "@game/main.lua" && f["line"] == 7) { // tick 总是从宿主 lua_pcall 进来，没有名字
				hasTick = true;
				tickTotal = f["totalPercent"].get<double>();
			}
			if (f["fn"] == "add" && f["line"] == 3) hasAdd = true;
		}
		for (auto& sk : r["body"]["stacks"]) {
			const std::string text = sk["stack"].get<std::string>();
			if (text.find("(game/main.lua:7);add (game/main.lua:3)") != std::string::npos) foldedOk = true;
		}
	}
	CHECK(Ok(r));
	CHECK(r["body"]["running"].get<bool>());
	CHECK(r["body"]["samples"].get<uint64_t>() > 0);
	CHECK(hasTick);
	CHECK(hasAdd);
	CHECK(tickTotal > 50.0); // 游戏线程每次进 Lua 都从 tick 开始
	CHECK(r["body"]["functions"].size() <= static_cast<size_t>(10));
	CHECK(foldedOk);

	// 暂停在断点时不采样
	CHECK(Ok(SetBps(c, json::array({Bp("bp-9", 9)}))));
	json p = c.WaitEvent("paused");
	CHECK(p.is_object());
	if (p.is_object()) {
		uint64_t before = c.Request("profile", {{"action", "result"}})["body"]["samples"].get<uint64_t>();
		std::this_thread::sleep_for(std::chrono::milliseconds(200));
		uint64_t after = c.Request("profile", {{"action", "result"}})["body"]["samples"].get<uint64_t>();
		CHECK_EQ(before, after);
		CHECK(Ok(SetBps(c, json::array())));
		CHECK(Ok(Continue(c, p)));
	}

	json stop = c.Request("profile", {{"action", "stop"}, {"stackLimit", 0}});
	CHECK(Ok(stop));
	CHECK(!stop["body"]["running"].get<bool>());
	CHECK(stop["body"]["stacks"].empty());
	CHECK(stop["body"]["stacksTruncated"].get<bool>());
	// 停了以后钩子摘掉（没有断点时完全不挂钩子）
	bool cleared = false;
	for (int i = 0; i < 200 && !cleared; ++i) {
		std::this_thread::sleep_for(std::chrono::milliseconds(5));
		cleared = g_game->hookCleared.load();
	}
	CHECK(cleared);

	// 限时采样：到点自动停
	CHECK(Ok(c.Request("profile", {{"action", "start"}, {"durationMs", 100}})));
	bool autoStopped = false;
	for (int i = 0; i < 100 && !autoStopped; ++i) {
		std::this_thread::sleep_for(std::chrono::milliseconds(10));
		autoStopped = !c.Request("profile", {{"action", "result"}})["body"]["running"].get<bool>();
	}
	CHECK(autoStopped);

	// 断开时停掉采样
	CHECK(Ok(c.Request("profile", {{"action", "start"}})));
	c.Request("disconnect");
	c.Close();
	cleared = false;
	for (int i = 0; i < 200 && !cleared; ++i) {
		std::this_thread::sleep_for(std::chrono::milliseconds(5));
		cleared = g_game->hookCleared.load();
	}
	CHECK(cleared);
}

// ---- UnLua 对象的显示：实例表摘要带字段、Object 不重复列、类表只显示类名、内部字段收起 ----
namespace {
int EMMY_HOST_API_CALL UeDescribe(void*, const EmmyHostValueQuery* q) {
	lua_State* L = q->L;
	if (!L || lua_type(L, q->index) != LUA_TTABLE) return EMMY_HOST_VALUE_UNAVAILABLE;
	lua_pushstring(L, "__ue");
	lua_rawget(L, q->index);
	const bool ue = lua_toboolean(L, -1) != 0;
	lua_pop(L, 1);
	if (!ue) return EMMY_HOST_VALUE_UNAVAILABLE;
	const char* s = R"({"valid":true,"typeName":"BP_X_C","summary":"BP_X_C_0","kind":"ueObject","expandable":true})";
	q->write(q->sink, s, std::strlen(s));
	return EMMY_HOST_VALUE_OK;
}
int EMMY_HOST_API_CALL UeList(void*, const EmmyHostValueQuery* q) {
	const char* s = R"({"total":0,"children":[]})";
	q->write(q->sink, s, std::strlen(s));
	return EMMY_HOST_VALUE_OK;
}
} // namespace

TEST_CASE("session.unlua_object_view") {
	EmmyHostValueProviderV2 p{};
	p.size = sizeof(p);
	p.version = EMMY_HOST_VALUE_ABI_VERSION;
	p.name = "ue-test";
	p.describe = &UeDescribe;
	p.listChildren = &UeList;
	uint64_t pid = Emmy_RegisterHostValueProviderV2(&p);
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c, json::array({"valuePath", "luaEval", "richVariables"}))));
	g_rev = 0;
	CHECK(Ok(SetBps(c, json::array({Bp("uo", 9)}))));
	CHECK(Ok(c.Request("configurationDone")));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	if (!pz.is_object()) { Emmy_UnregisterHostValueProviderV2(pid); return; }
	json stack = c.Request("stackTrace", {{"vmId", pz["body"]["vmId"]}, {"pauseId", pz["body"]["pauseId"]}});
	uint64_t frameId = stack["body"]["frames"][0]["frameId"];
	json ev = c.Request("evaluate", {{"frameId", frameId}, {"mode", "lua"}, {"expression",
		"{__ue = true, Health = 100, Damage = 128.0, Object = io.stdout,"
		" DamageType = {__name = '/Script/Engine.UDamageType', ClassDesc = mklud(), TypeHash = 140685849361140.0}}"}});
	CHECK(Ok(ev));
#if EMMY_LUA_VERSION_NUM >= 503
	CHECK_EQ(ev["body"]["value"].get<std::string>(), std::string("BP_X_C_0 {Damage=128.0, DamageType=class UDamageType, Health=100}"));
#endif
	json ov = c.Request("variables", {{"ref", ev["body"]["ref"]}});
	uint64_t dtRef = 0;
	for (auto& v : ov["body"]["variables"]) {
		CHECK(v["name"] != "Object"); // 和「UE 对象」分组是同一个对象
		if (v["name"] == "DamageType") {
			CHECK_EQ(v["value"].get<std::string>(), std::string("class UDamageType"));
			CHECK_EQ(v["kind"].get<std::string>(), std::string("class"));
			dtRef = v["ref"];
		}
	}
	bool ueGroup = false;
	for (auto& g : ov["body"]["groups"]) if (g["name"] == "UE 对象") ueGroup = true;
	CHECK(ueGroup);
	CHECK(dtRef != 0);
	json dv = c.Request("variables", {{"ref", dtRef}});
	CHECK_EQ(dv["body"]["total"].get<int>(), 0); // ClassDesc / TypeHash / __name 都不在主列表
	uint64_t innerRef = 0;
	for (auto& g : dv["body"]["groups"]) if (g["name"] == "内部") innerRef = g["ref"];
	CHECK(innerRef != 0);
	json inner = c.Request("variables", {{"ref", innerRef}});
	CHECK_EQ(inner["body"]["variables"][0]["name"].get<std::string>(), std::string("UnLua 字段"));
	json fields = c.Request("variables", {{"ref", inner["body"]["variables"][0]["ref"]}});
	for (auto& v : fields["body"]["variables"]) {
#if EMMY_LUA_VERSION_NUM >= 503
		if (v["name"] == "TypeHash") CHECK_EQ(v["value"].get<std::string>(), std::string("140685849361140.0"));
#endif
	}
	CHECK_EQ(fields["body"]["total"].get<int>(), 3);
	CHECK(Ok(SetBps(c, json::array())));
	CHECK(Ok(Continue(c, pz)));
	c.Request("disconnect");
	Emmy_UnregisterHostValueProviderV2(pid);
}

TEST_CASE("session.internal_fault_disables_vm_only") {
	// 调试器内部出错（这里注入一个 C++ 异常）时：只停掉出错的那个 VM 的钩子并报告，宿主照常跑，别的 VM 不受影响
	TestClient c;
	CHECK(c.Connect("127.0.0.1", g_port));
	CHECK(Ok(Hello(c)));
	CHECK(Ok(c.Request("configurationDone")));
	g_rev = 0;
	CHECK(Ok(SetBps(c, json::array({Bp("never", 100)})))); // 有断点才挂钩子
	lua_State* L = luaL_newstate();
	luaL_openlibs(L);
	Emmy_RegisterLuaVm(L, "fault");
	const char* src = "function f(n) local s = 0 for i = 1, n do s = s + i end return s end";
	lua::LoadBuffer(L, src, std::strlen(src), "@game/fault.lua");
	lua_call(L, 0, 0);
	auto run = [L] {
		lua_getglobal(L, "f");
		lua_pushinteger(L, 5000);
		const int rc = lua_pcall(L, 1, 1, 0);
		lua_pop(L, 1);
		return rc;
	};
	CHECK_EQ(run(), LUA_OK);
	std::shared_ptr<Vm> vm = Core::Get().FindVmByState(L);
	CHECK(vm != nullptr);
	if (vm) {
		vm->Kick();
		CHECK_EQ(run(), LUA_OK);
		CHECK(lua_gethook(L) != nullptr);
		vm->mux().InjectFaultForTest();
		CHECK_EQ(run(), LUA_OK);
		CHECK(vm->mux().faulted());
		CHECK(lua_gethook(L) == nullptr);
		json out = c.WaitEvent("output", 3000, [](const json& b) {
			return b.value("text", std::string()).find("调试器内部错误") != std::string::npos;
		});
		CHECK(out.is_object());
		vm->Kick(); // 出过错的 VM 不再挂钩子
		CHECK_EQ(run(), LUA_OK);
		CHECK(lua_gethook(L) == nullptr);
	}
	// 游戏线程上的 VM 照常可以断下
	CHECK(Ok(SetBps(c, json::array({Bp("ok", 9)}))));
	json pz = c.WaitEvent("paused");
	CHECK(pz.is_object());
	CHECK(Ok(SetBps(c, json::array())));
	if (pz.is_object()) CHECK(Ok(Continue(c, pz)));
	c.Request("disconnect");
	Emmy_UnregisterLuaVm(L);
	lua_close(L);
}

int main() {
	g_port = 39000 + static_cast<int>(std::chrono::steady_clock::now().time_since_epoch().count() % 2000);
	char err[256] = {0};
	if (Emmy_ListenTcp("127.0.0.1", g_port, err, sizeof(err)) != 0) {
		std::printf("listen failed: %s\n", err);
		return 2;
	}
	Game game;
	g_game = &game;
	game.Start();
	int rc = emmy::test::RunAll();
	game.Stop();
	Emmy_Stop();
	return rc;
}
