#include "transport.h"

#include <cstring>
#include <vector>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  define EMMY_INVALID_SOCKET static_cast<socket_t>(INVALID_SOCKET)
#  define emmy_close_socket(s) closesocket(static_cast<SOCKET>(s))
#  define emmy_shutdown(s) shutdown(static_cast<SOCKET>(s), SD_BOTH)
#  define emmy_shutdown_read(s) shutdown(static_cast<SOCKET>(s), SD_RECEIVE)
#else
#  include <arpa/inet.h>
#  include <netdb.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/socket.h>
#  include <sys/un.h>
#  include <unistd.h>
#  define EMMY_INVALID_SOCKET (-1)
#  define emmy_close_socket(s) ::close(s)
#  define emmy_shutdown(s) ::shutdown(s, SHUT_RDWR)
#  define emmy_shutdown_read(s) ::shutdown(s, SHUT_RD)
#endif

namespace emmy {

namespace {

constexpr size_t kMaxQueuedBytes = 64u * 1024u * 1024u;

void SetNoDelay(socket_t s) {
	int one = 1;
	setsockopt(static_cast<decltype(socket(0, 0, 0))>(s), IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&one), sizeof(one));
}

#if !defined(_WIN32)
void IgnoreSigPipe(socket_t s) {
#  if defined(SO_NOSIGPIPE)
	int one = 1;
	setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#  else
	(void)s;
#  endif
}
#endif

int SendFlags() {
#if defined(MSG_NOSIGNAL)
	return MSG_NOSIGNAL;
#else
	return 0;
#endif
}

class Listener : public Transport, public std::enable_shared_from_this<Listener> {
public:
	Listener(socket_t s, ConnectionHandler handler, bool tcp) : sock_(s), handler_(std::move(handler)), tcp_(tcp) {}
	~Listener() override { Stop(); }

	void Start() {
		auto self = shared_from_this();
		thread_ = std::thread([self] { self->AcceptLoop(); });
	}

	void Stop() override {
		if (stopped_.exchange(true)) return;
		emmy_shutdown(sock_);
		emmy_close_socket(sock_);
		if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) thread_.join();
		else if (thread_.joinable()) thread_.detach();
	}

private:
	void AcceptLoop() {
		while (!stopped_) {
			sockaddr_storage addr{};
			socklen_t len = sizeof(addr);
			auto c = accept(static_cast<decltype(socket(0, 0, 0))>(sock_), reinterpret_cast<sockaddr*>(&addr), &len);
			if (stopped_) {
				if (static_cast<socket_t>(c) != EMMY_INVALID_SOCKET) emmy_close_socket(static_cast<socket_t>(c));
				break;
			}
			if (static_cast<socket_t>(c) == EMMY_INVALID_SOCKET) {
				std::this_thread::sleep_for(std::chrono::milliseconds(50));
				continue;
			}
			socket_t cs = static_cast<socket_t>(c);
			std::string peer = "local";
			if (tcp_) {
				SetNoDelay(cs);
				char host[INET6_ADDRSTRLEN] = {0};
				if (addr.ss_family == AF_INET) inet_ntop(AF_INET, &reinterpret_cast<sockaddr_in*>(&addr)->sin_addr, host, sizeof(host));
				else if (addr.ss_family == AF_INET6) inet_ntop(AF_INET6, &reinterpret_cast<sockaddr_in6*>(&addr)->sin6_addr, host, sizeof(host));
				peer = host;
			}
#if !defined(_WIN32)
			IgnoreSigPipe(cs);
#endif
			handler_(std::make_shared<Connection>(cs, peer));
		}
	}

	socket_t sock_;
	ConnectionHandler handler_;
	bool tcp_;
	std::atomic<bool> stopped_{false};
	std::thread thread_;
};

class Connector : public Transport, public std::enable_shared_from_this<Connector> {
public:
	Connector(std::string host, int port, ConnectionHandler handler) : host_(std::move(host)), port_(port), handler_(std::move(handler)) {}
	~Connector() override { Stop(); }

	void Start() {
		auto self = shared_from_this();
		thread_ = std::thread([self] { self->Loop(); });
	}

	void Stop() override {
		if (stopped_.exchange(true)) return;
		{
			std::lock_guard<std::mutex> lock(mutex_);
			if (current_) current_->Close();
		}
		cv_.notify_all();
		if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) thread_.join();
		else if (thread_.joinable()) thread_.detach();
	}

private:
	void Loop() {
		while (!stopped_) {
			addrinfo hints{};
			hints.ai_family = AF_UNSPEC;
			hints.ai_socktype = SOCK_STREAM;
			addrinfo* res = nullptr;
			std::string portStr = std::to_string(port_);
			socket_t s = EMMY_INVALID_SOCKET;
			if (getaddrinfo(host_.c_str(), portStr.c_str(), &hints, &res) == 0) {
				for (addrinfo* p = res; p; p = p->ai_next) {
					auto c = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
					if (static_cast<socket_t>(c) == EMMY_INVALID_SOCKET) continue;
					if (connect(c, p->ai_addr, static_cast<socklen_t>(p->ai_addrlen)) == 0) {
						s = static_cast<socket_t>(c);
						break;
					}
					emmy_close_socket(static_cast<socket_t>(c));
				}
				freeaddrinfo(res);
			}
			if (s != EMMY_INVALID_SOCKET) {
				SetNoDelay(s);
#if !defined(_WIN32)
				IgnoreSigPipe(s);
#endif
				auto conn = std::make_shared<Connection>(s, host_);
				{
					std::lock_guard<std::mutex> lock(mutex_);
					current_ = conn;
				}
				handler_(conn);
				// 等这条连接断开再重连
				std::unique_lock<std::mutex> lock(mutex_);
				while (!stopped_ && !conn->closed()) cv_.wait_for(lock, std::chrono::milliseconds(200));
				current_.reset();
				if (stopped_) break;
			}
			std::unique_lock<std::mutex> lock(mutex_);
			cv_.wait_for(lock, std::chrono::seconds(1), [&] { return stopped_.load(); });
		}
	}

	std::string host_;
	int port_;
	ConnectionHandler handler_;
	std::atomic<bool> stopped_{false};
	std::mutex mutex_;
	std::condition_variable cv_;
	std::shared_ptr<Connection> current_;
	std::thread thread_;
};

} // namespace

bool InitSockets() {
#if defined(_WIN32)
	static bool inited = [] {
		WSADATA data;
		return WSAStartup(MAKEWORD(2, 2), &data) == 0;
	}();
	return inited;
#else
	return true;
#endif
}

Connection::Connection(socket_t s, std::string peer) : sock_(s), peer_(std::move(peer)) {}

Connection::~Connection() {
	Close();
	if (reader_.joinable()) {
		if (reader_.get_id() == std::this_thread::get_id()) reader_.detach();
		else reader_.join();
	}
	if (writer_.joinable()) {
		if (writer_.get_id() == std::this_thread::get_id()) writer_.detach();
		else writer_.join();
	}
	emmy_close_socket(sock_);
}

void Connection::Start(std::function<void(const char*, size_t)> onData, std::function<void()> onClosed) {
	onData_ = std::move(onData);
	onClosed_ = std::move(onClosed);
	auto self = shared_from_this();
	reader_ = std::thread([self] { self->ReadLoop(); });
	writer_ = std::thread([self] { self->WriteLoop(); });
}

void Connection::Send(std::string bytes) {
	{
		std::lock_guard<std::mutex> lock(mutex_);
		if (closed_) return;
		queuedBytes_ += bytes.size();
		if (queuedBytes_ > kMaxQueuedBytes) {
			// 对端长时间不读：宁可断开也不能无限占内存
			closed_ = true;
		} else {
			queue_.push_back(std::move(bytes));
		}
	}
	cv_.notify_all();
	if (closed_) Close();
}

void Connection::Close() {
	// 只先关读端：写线程把已排队的数据（例如 BUSY / AUTH_FAILED 的回复）发完再整体关闭
	closed_ = true;
	emmy_shutdown_read(sock_);
	cv_.notify_all();
	std::call_once(closedOnce_, [this] {
		if (onClosed_) onClosed_();
	});
}

void Connection::Join() {
	if (reader_.joinable() && reader_.get_id() != std::this_thread::get_id()) reader_.join();
	if (writer_.joinable() && writer_.get_id() != std::this_thread::get_id()) writer_.join();
}

void Connection::ReadLoop() {
	std::vector<char> buf(64 * 1024);
	while (!closed_) {
		auto n = recv(static_cast<decltype(socket(0, 0, 0))>(sock_), buf.data(), static_cast<int>(buf.size()), 0);
		if (n <= 0) break;
		if (closed_) break;
		if (onData_) onData_(buf.data(), static_cast<size_t>(n));
	}
	Close();
}

void Connection::WriteLoop() {
	while (true) {
		std::string chunk;
		{
			std::unique_lock<std::mutex> lock(mutex_);
			cv_.wait(lock, [&] { return closed_ || !queue_.empty(); });
			if (queue_.empty()) {
				// closed 且没有待发数据
				emmy_shutdown(sock_);
				break;
			}
			chunk = std::move(queue_.front());
			queue_.pop_front();
			queuedBytes_ -= chunk.size();
		}
		size_t off = 0;
		while (off < chunk.size()) {
			auto n = send(static_cast<decltype(socket(0, 0, 0))>(sock_), chunk.data() + off, static_cast<int>(chunk.size() - off), SendFlags());
			if (n <= 0) {
				Close();
				return;
			}
			off += static_cast<size_t>(n);
		}
	}
}

std::shared_ptr<Transport> ListenTcp(const std::string& host, int port, ConnectionHandler handler, std::string& err) {
	InitSockets();
	addrinfo hints{};
	hints.ai_family = AF_UNSPEC;
	hints.ai_socktype = SOCK_STREAM;
	hints.ai_flags = AI_PASSIVE;
	addrinfo* res = nullptr;
	std::string portStr = std::to_string(port);
	const char* h = host.empty() ? "127.0.0.1" : host.c_str();
	if (getaddrinfo(h, portStr.c_str(), &hints, &res) != 0 || !res) {
		err = "无法解析监听地址 " + std::string(h);
		return nullptr;
	}
	auto s = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
	if (static_cast<socket_t>(s) == EMMY_INVALID_SOCKET) {
		freeaddrinfo(res);
		err = "创建 socket 失败";
		return nullptr;
	}
#if !defined(_WIN32)
	int one = 1;
	setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
#endif
	if (bind(s, res->ai_addr, static_cast<socklen_t>(res->ai_addrlen)) != 0 || listen(s, 4) != 0) {
		freeaddrinfo(res);
		emmy_close_socket(static_cast<socket_t>(s));
		err = "监听 " + std::string(h) + ":" + portStr + " 失败（端口可能被占用）";
		return nullptr;
	}
	freeaddrinfo(res);
	auto l = std::make_shared<Listener>(static_cast<socket_t>(s), std::move(handler), true);
	l->Start();
	return l;
}

std::shared_ptr<Transport> ListenAbstract(const std::string& name, ConnectionHandler handler, std::string& err) {
#if defined(__linux__) || defined(__ANDROID__)
	int s = socket(AF_UNIX, SOCK_STREAM, 0);
	if (s < 0) {
		err = "创建 unix socket 失败";
		return nullptr;
	}
	sockaddr_un addr{};
	addr.sun_family = AF_UNIX;
	if (name.size() + 1 > sizeof(addr.sun_path)) {
		::close(s);
		err = "socket 名字太长";
		return nullptr;
	}
	addr.sun_path[0] = '\0';
	std::memcpy(addr.sun_path + 1, name.data(), name.size());
	socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
	if (bind(s, reinterpret_cast<sockaddr*>(&addr), len) != 0 || listen(s, 4) != 0) {
		::close(s);
		err = "监听 abstract socket " + name + " 失败（名字可能被占用）";
		return nullptr;
	}
	auto l = std::make_shared<Listener>(s, std::move(handler), false);
	l->Start();
	return l;
#else
	(void)name;
	(void)handler;
	err = "abstract unix socket 只在 Linux / Android 上可用";
	return nullptr;
#endif
}

std::shared_ptr<Transport> ConnectTcp(const std::string& host, int port, ConnectionHandler handler, std::string& err) {
	InitSockets();
	auto c = std::make_shared<Connector>(host, port, std::move(handler));
	c->Start();
	(void)err;
	return c;
}

std::shared_ptr<Connection> WrapFd(intptr_t fd) {
	InitSockets();
#if !defined(_WIN32)
	IgnoreSigPipe(static_cast<socket_t>(fd));
#endif
	return std::make_shared<Connection>(static_cast<socket_t>(fd), "fd");
}

} // namespace emmy
