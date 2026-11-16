// 传输层：阻塞 socket + 每个连接一个读线程、一个写线程。只负责收发字节，不认识协议。
// 写线程有队列：VM 线程发事件只是入队，IDE 卡住不会卡住游戏；队列超过上限直接断开。
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace emmy {

#if defined(_WIN32)
using socket_t = uintptr_t;
#else
using socket_t = int;
#endif

class Connection : public std::enable_shared_from_this<Connection> {
public:
	explicit Connection(socket_t s, std::string peer);
	~Connection();

	void Start(std::function<void(const char*, size_t)> onData, std::function<void()> onClosed);
	void Send(std::string bytes);
	void Close();
	bool closed() const { return closed_.load(); }
	const std::string& peer() const { return peer_; }
	// 等读写线程退出（不能在这两个线程里调用）
	void Join();

private:
	void ReadLoop();
	void WriteLoop();

	socket_t sock_;
	std::string peer_;
	std::atomic<bool> closed_{false};
	std::mutex mutex_;
	std::condition_variable cv_;
	std::deque<std::string> queue_;
	size_t queuedBytes_ = 0;
	std::function<void(const char*, size_t)> onData_;
	std::function<void()> onClosed_;
	std::once_flag closedOnce_;
	std::thread reader_;
	std::thread writer_;
};

class Transport {
public:
	virtual ~Transport() = default;
	virtual void Stop() = 0;
};

using ConnectionHandler = std::function<void(std::shared_ptr<Connection>)>;

// 监听：TCP（host:port）或 Linux/Android abstract unix socket
std::shared_ptr<Transport> ListenTcp(const std::string& host, int port, ConnectionHandler handler, std::string& err);
std::shared_ptr<Transport> ListenAbstract(const std::string& name, ConnectionHandler handler, std::string& err);
// 主动连接（runtime 连 IDE），断开后每秒重连直到 Stop
std::shared_ptr<Transport> ConnectTcp(const std::string& host, int port, ConnectionHandler handler, std::string& err);
// 已建立的 fd
std::shared_ptr<Connection> WrapFd(intptr_t fd);

bool InitSockets();

} // namespace emmy
