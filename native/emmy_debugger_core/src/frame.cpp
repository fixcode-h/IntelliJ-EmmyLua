#include "frame.h"

#include <cstring>

namespace emmy {

std::string EncodeFrame(MessageKind kind, bool cbor, const std::string& payload) {
	std::string out;
	out.resize(kFrameHeaderSize + payload.size());
	auto len = static_cast<uint32_t>(payload.size());
	out[0] = static_cast<char>(kFrameMagic0);
	out[1] = static_cast<char>(kFrameMagic1);
	out[2] = static_cast<char>(static_cast<uint8_t>(kind) | (cbor ? kFrameCborFlag : 0));
	out[3] = static_cast<char>((len >> 24) & 0xFF);
	out[4] = static_cast<char>((len >> 16) & 0xFF);
	out[5] = static_cast<char>((len >> 8) & 0xFF);
	out[6] = static_cast<char>(len & 0xFF);
	if (!payload.empty()) {
		std::memcpy(&out[kFrameHeaderSize], payload.data(), payload.size());
	}
	return out;
}

FrameDecoder::Status FrameDecoder::Feed(const char* data, size_t len, const std::function<void(Frame&&)>& onFrame) {
	if (status_ != Status::Ok) {
		return status_;
	}
	if (len == 0) {
		return status_;
	}
	if (!sawFirstByte_) {
		sawFirstByte_ = true;
		if (static_cast<uint8_t>(data[0]) != kFrameMagic0) {
			return status_ = Status::NotV3;
		}
	}
	buffer_.append(data, len);
	size_t offset = 0;
	while (buffer_.size() - offset >= kFrameHeaderSize) {
		auto* p = reinterpret_cast<const uint8_t*>(buffer_.data() + offset);
		if (p[0] != kFrameMagic0 || p[1] != kFrameMagic1) {
			return status_ = Status::BadMagic;
		}
		uint8_t type = p[2];
		uint8_t kind = type & 0x0F;
		if ((type & ~(0x0F | kFrameCborFlag)) != 0 || kind < 1 || kind > 3) {
			return status_ = Status::BadType;
		}
		uint32_t length = (static_cast<uint32_t>(p[3]) << 24) | (static_cast<uint32_t>(p[4]) << 16) |
			(static_cast<uint32_t>(p[5]) << 8) | static_cast<uint32_t>(p[6]);
		if (length == 0) {
			return status_ = Status::Empty;
		}
		if (length > maxFrameBytes_) {
			// 超限不读 payload，直接让调用方断开
			return status_ = Status::TooLarge;
		}
		if (buffer_.size() - offset - kFrameHeaderSize < length) {
			break;
		}
		Frame frame;
		frame.kind = static_cast<MessageKind>(kind);
		frame.cbor = (type & kFrameCborFlag) != 0;
		frame.payload.assign(buffer_.data() + offset + kFrameHeaderSize, length);
		offset += kFrameHeaderSize + length;
		onFrame(std::move(frame));
	}
	if (offset > 0) {
		buffer_.erase(0, offset);
	}
	return status_;
}

const char* FrameDecoder::error() const {
	switch (status_) {
	case Status::Ok: return "";
	case Status::NotV3: return "对端不是调试协议 v3（旧版本调试器）";
	case Status::BadMagic: return "帧头 magic 不正确";
	case Status::BadType: return "帧类型字节不合法";
	case Status::TooLarge: return "帧超过最大长度";
	case Status::Empty: return "帧 payload 为空";
	}
	return "未知错误";
}

} // namespace emmy
