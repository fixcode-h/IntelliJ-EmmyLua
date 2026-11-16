// 协议 v3 帧编解码（docs/design/protocol-v3.md 第 2 节）。与 IO 无关，可单测。
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>

namespace emmy {

enum class MessageKind : uint8_t {
	Request = 1,
	Response = 2,
	Event = 3,
};

constexpr uint8_t kFrameMagic0 = 0xE3;
constexpr uint8_t kFrameMagic1 = 0x01;
constexpr uint8_t kFrameCborFlag = 0x10;
constexpr size_t kFrameHeaderSize = 7;
constexpr uint32_t kDefaultMaxFrameBytes = 16u * 1024u * 1024u;

struct Frame {
	MessageKind kind = MessageKind::Request;
	bool cbor = false;
	std::string payload;
};

std::string EncodeFrame(MessageKind kind, bool cbor, const std::string& payload);

// 增量解码：Feed 任意切分的字节，凑够一帧就回调。出错后不再接受数据，error() 给出原因。
class FrameDecoder {
public:
	enum class Status {
		Ok,
		// 首字节不是 0xE3：对端是旧版本（v1 文本协议）或者根本不是调试协议
		NotV3,
		BadMagic,
		BadType,
		TooLarge,
		Empty,
	};

	explicit FrameDecoder(uint32_t maxFrameBytes = kDefaultMaxFrameBytes) : maxFrameBytes_(maxFrameBytes) {}

	Status Feed(const char* data, size_t len, const std::function<void(Frame&&)>& onFrame);
	Status status() const { return status_; }
	const char* error() const;
	void SetMaxFrameBytes(uint32_t v) { maxFrameBytes_ = v; }

private:
	uint32_t maxFrameBytes_;
	Status status_ = Status::Ok;
	bool sawFirstByte_ = false;
	std::string buffer_;
};

} // namespace emmy
