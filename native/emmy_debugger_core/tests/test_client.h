// 测试和基准用的协议 v3 客户端（扮演 IDE）。
#pragma once

#include "nlohmann/json.hpp"

#include <condition_variable>
#include <deque>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <thread>

namespace emmy::test {

using json = nlohmann::json;

class TestClient {
public:
	~TestClient();
	bool Connect(const std::string& host, int port, int timeoutMs = 3000);
	// 发请求并等回复；超时返回 null
	json Request(const std::string& command, json args = json::object(), int timeoutMs = 5000);
	// 等某个事件（跳过不满足条件的）；超时返回 null
	json WaitEvent(const std::string& name, int timeoutMs = 5000, std::function<bool(const json&)> pred = nullptr);
	// 不等待：取出已经到达的某类事件
	std::vector<json> DrainEvents(const std::string& name);
	void SendRaw(const std::string& bytes);
	void Close();
	bool closed() const { return closed_; }
	std::string rawPrefix() const;

private:
	void ReadLoop();

	intptr_t sock_ = -1;
	std::thread reader_;
	std::mutex mutex_;
	std::condition_variable cv_;
	std::map<uint64_t, json> responses_;
	std::deque<json> events_;
	uint64_t nextId_ = 1;
	bool closed_ = false;
	std::string raw_;
};

} // namespace emmy::test
