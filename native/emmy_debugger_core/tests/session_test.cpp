// 端到端测试：进程内起一个「游戏线程」跑 Lua，测试客户端扮演 IDE 走完整的协议 v3。
#include "check.h"
#include "emmy/emmy_core.h"
#include "frame.h"
#include "lua_shim.h"
#include "test_client.h"

#include <atomic>
#include <chrono>
#include <cstring>
#include <thread>

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
  return r
end
function M.counter() return counter end
return M
)";

const char* kPath = "D:/proj/game/main.lua";

struct Game {
	std::atomic<bool> stop{false};
	std::atomic<int64_t> ticks{0};
	std::atomic<bool> hookCleared{false};
	std::atomic<bool> ready{false};
	std::thread thread;

	void Start() {
		thread = std::thread([this] { Run(); });
		while (!ready) std::this_thread::sleep_for(std::chrono::milliseconds(1));
	}

	void Run() {
		lua_State* L = luaL_newstate();
		luaL_openlibs(L);
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
			if (lua_pcall(L, 1, 1, 0) != LUA_OK) {
				std::printf("lua error: %s\n", lua_tostring(L, -1));
			}
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
