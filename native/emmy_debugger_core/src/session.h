// 一条 IDE 连接上的协议会话：解帧、握手、命令分发（网络线程），需要 Lua 的命令投递给 VM 线程。
#pragma once

#include "frame.h"
#include "nlohmann/json.hpp"
#include "transport.h"

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

namespace emmy {

using json = nlohmann::json;

class Session : public std::enable_shared_from_this<Session> {
public:
	explicit Session(std::shared_ptr<Connection> conn);

	void Start();
	void Close();

	void Reply(uint64_t id, json body);
	void Fail(uint64_t id, const std::string& code, const std::string& message, json data = nullptr);
	void Emit(const char* event, json body);

	bool handshaken() const { return handshaken_.load(); }
	bool isClosed() const { return closed_.load(); }
	const std::string& sessionId() const { return sessionId_; }

private:
	void OnBytes(const char* data, size_t len);
	void OnClosed();
	void OnFrame(Frame&& frame);
	void Dispatch(uint64_t id, const std::string& command, const json& args);
	void SendRaw(MessageKind kind, const json& payload);

	void CmdHello(uint64_t id, const json& args);
	void CmdSetBreakpoints(uint64_t id, const json& args);
	void CmdSetOptions(uint64_t id, const json& args);
	void CmdLoadHelpers(uint64_t id, const json& args);
	void CmdContinue(uint64_t id, const json& args);
	void CmdStep(uint64_t id, const std::string& command, const json& args);
	void CmdRunToLine(uint64_t id, const json& args);
	void CmdPause(uint64_t id, const json& args);
	void CmdPaused(uint64_t id, const std::string& command, const json& args);
	void CmdDisconnect(uint64_t id, const json& args);

	std::shared_ptr<Connection> conn_;
	FrameDecoder decoder_;
	std::mutex sendMutex_;
	uint64_t nextSeq_ = 1;
	std::atomic<bool> handshaken_{false};
	std::atomic<bool> closed_{false};
	std::string sessionId_;
};

} // namespace emmy
