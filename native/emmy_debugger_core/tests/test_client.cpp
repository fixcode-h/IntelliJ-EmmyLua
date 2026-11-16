#include "test_client.h"

#include "frame.h"

#include <chrono>
#include <cstddef>
#include <cstring>

#if defined(_WIN32)
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  define tc_close closesocket
#else
#  include <arpa/inet.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/socket.h>
#  include <sys/un.h>
#  include <unistd.h>
#  define tc_close ::close
#endif

namespace emmy::test {

TestClient::~TestClient() {
	Close();
}

bool TestClient::Connect(const std::string& host, int port, int timeoutMs) {
	auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
	while (std::chrono::steady_clock::now() < deadline) {
		auto s = socket(AF_INET, SOCK_STREAM, 0);
		sockaddr_in addr{};
		addr.sin_family = AF_INET;
		addr.sin_port = htons(static_cast<uint16_t>(port));
		inet_pton(AF_INET, host.c_str(), &addr.sin_addr);
		if (connect(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0) {
			int one = 1;
			setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&one), sizeof(one));
			sock_ = static_cast<intptr_t>(s);
			reader_ = std::thread([this] { ReadLoop(); });
			return true;
		}
		tc_close(s);
		std::this_thread::sleep_for(std::chrono::milliseconds(20));
	}
	return false;
}

bool TestClient::ConnectAbstract(const std::string& name, int timeoutMs) {
#if defined(__linux__)
	auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
	while (std::chrono::steady_clock::now() < deadline) {
		int s = socket(AF_UNIX, SOCK_STREAM, 0);
		sockaddr_un addr{};
		addr.sun_family = AF_UNIX;
		if (name.size() + 1 > sizeof(addr.sun_path)) {
			tc_close(s);
			return false;
		}
		addr.sun_path[0] = '\0';
		std::memcpy(addr.sun_path + 1, name.data(), name.size());
		auto len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
		if (connect(s, reinterpret_cast<sockaddr*>(&addr), len) == 0) {
			sock_ = static_cast<intptr_t>(s);
			reader_ = std::thread([this] { ReadLoop(); });
			return true;
		}
		tc_close(s);
		std::this_thread::sleep_for(std::chrono::milliseconds(20));
	}
	return false;
#else
	(void)name;
	(void)timeoutMs;
	return false;
#endif
}

void TestClient::SendRaw(const std::string& bytes) {
	size_t off = 0;
	while (off < bytes.size()) {
		auto n = send(static_cast<decltype(socket(0, 0, 0))>(sock_), bytes.data() + off, static_cast<int>(bytes.size() - off), 0);
		if (n <= 0) return;
		off += static_cast<size_t>(n);
	}
}

json TestClient::Request(const std::string& command, json args, int timeoutMs) {
	uint64_t id;
	{
		std::lock_guard<std::mutex> lock(mutex_);
		id = nextId_++;
	}
	json req = {{"id", id}, {"command", command}, {"args", std::move(args)}};
	SendRaw(EncodeFrame(MessageKind::Request, false, req.dump()));
	std::unique_lock<std::mutex> lock(mutex_);
	if (!cv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] { return responses_.count(id) > 0 || closed_; })) {
		return nullptr;
	}
	auto it = responses_.find(id);
	if (it == responses_.end()) return nullptr;
	json r = std::move(it->second);
	responses_.erase(it);
	return r;
}

json TestClient::WaitEvent(const std::string& name, int timeoutMs, std::function<bool(const json&)> pred) {
	auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
	std::unique_lock<std::mutex> lock(mutex_);
	while (true) {
		for (auto it = events_.begin(); it != events_.end(); ++it) {
			if ((*it)["event"] == name && (!pred || pred((*it)["body"]))) {
				json e = std::move(*it);
				events_.erase(it);
				return e;
			}
		}
		if (closed_) return nullptr;
		if (cv_.wait_until(lock, deadline) == std::cv_status::timeout) {
			// 最后再扫一遍
			for (auto it = events_.begin(); it != events_.end(); ++it) {
				if ((*it)["event"] == name && (!pred || pred((*it)["body"]))) {
					json e = std::move(*it);
					events_.erase(it);
					return e;
				}
			}
			return nullptr;
		}
	}
}

std::vector<json> TestClient::DrainEvents(const std::string& name) {
	std::lock_guard<std::mutex> lock(mutex_);
	std::vector<json> out;
	for (auto it = events_.begin(); it != events_.end();) {
		if ((*it)["event"] == name) {
			out.push_back(std::move(*it));
			it = events_.erase(it);
		} else {
			++it;
		}
	}
	return out;
}

std::string TestClient::rawPrefix() const {
	return raw_;
}

void TestClient::ReadLoop() {
	FrameDecoder decoder;
	char buf[65536];
	while (true) {
		auto n = recv(static_cast<decltype(socket(0, 0, 0))>(sock_), buf, sizeof(buf), 0);
		if (n <= 0) break;
		if (raw_.size() < 64) raw_.append(buf, static_cast<size_t>(std::min<long>(n, 64)));
		auto st = decoder.Feed(buf, static_cast<size_t>(n), [&](Frame&& f) {
			json msg = json::parse(f.payload);
			std::lock_guard<std::mutex> lock(mutex_);
			if (f.kind == MessageKind::Response) {
				if (!msg["id"].is_number()) {
					std::printf("  bad response: %s\n", f.payload.c_str());
					return;
				}
				uint64_t id = msg["id"].get<uint64_t>();
				responses_[id] = std::move(msg);
			}
			else events_.push_back(std::move(msg));
			cv_.notify_all();
		});
		if (st != FrameDecoder::Status::Ok) break;
	}
	std::lock_guard<std::mutex> lock(mutex_);
	closed_ = true;
	cv_.notify_all();
}

void TestClient::Close() {
	if (sock_ != -1) {
#if defined(_WIN32)
		shutdown(static_cast<SOCKET>(sock_), SD_BOTH);
#else
		shutdown(static_cast<int>(sock_), SHUT_RDWR);
#endif
		if (reader_.joinable()) reader_.join();
		tc_close(static_cast<decltype(socket(0, 0, 0))>(sock_));
		sock_ = -1;
	}
}

} // namespace emmy::test
