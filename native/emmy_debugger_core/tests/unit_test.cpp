// 纯逻辑单元测试：帧编解码、路径匹配、断点索引、命中条件、valuePath。
#include "breakpoints.h"
#include "check.h"
#include "frame.h"
#include "path_match.h"
#include "value_path.h"

#include <memory>

using namespace emmy;

TEST_CASE("frame.roundtrip_split_bytes") {
	std::string a = EncodeFrame(MessageKind::Request, false, "{\"id\":1}");
	std::string b = EncodeFrame(MessageKind::Event, true, std::string("\xa1\x61\x61\x01", 4));
	std::string all = a + b;
	FrameDecoder d;
	std::vector<Frame> frames;
	for (char c : all) {
		CHECK(d.Feed(&c, 1, [&](Frame&& f) { frames.push_back(std::move(f)); }) == FrameDecoder::Status::Ok);
	}
	CHECK_EQ(frames.size(), static_cast<size_t>(2));
	CHECK(frames[0].kind == MessageKind::Request);
	CHECK(!frames[0].cbor);
	CHECK_EQ(frames[0].payload, std::string("{\"id\":1}"));
	CHECK(frames[1].kind == MessageKind::Event);
	CHECK(frames[1].cbor);
}

TEST_CASE("frame.header_is_big_endian") {
	std::string f = EncodeFrame(MessageKind::Response, false, std::string(258, 'x'));
	CHECK_EQ(static_cast<int>(static_cast<uint8_t>(f[0])), 0xE3);
	CHECK_EQ(static_cast<int>(static_cast<uint8_t>(f[1])), 0x01);
	CHECK_EQ(static_cast<int>(f[2]), 2);
	CHECK_EQ(static_cast<int>(static_cast<uint8_t>(f[5])), 1);
	CHECK_EQ(static_cast<int>(static_cast<uint8_t>(f[6])), 2);
}

TEST_CASE("frame.rejects_old_protocol_and_bad_frames") {
	FrameDecoder d;
	std::string v1 = "1\n{\"cmd\":1}\n";
	CHECK(d.Feed(v1.data(), v1.size(), [](Frame&&) {}) == FrameDecoder::Status::NotV3);

	FrameDecoder big(16);
	std::string f = EncodeFrame(MessageKind::Request, false, std::string(17, 'x'));
	CHECK(big.Feed(f.data(), f.size(), [](Frame&&) {}) == FrameDecoder::Status::TooLarge);

	FrameDecoder badType;
	std::string g = EncodeFrame(MessageKind::Request, false, "{}");
	g[2] = 0x24;
	CHECK(badType.Feed(g.data(), g.size(), [](Frame&&) {}) == FrameDecoder::Status::BadType);

	FrameDecoder empty;
	std::string h = EncodeFrame(MessageKind::Request, false, "");
	CHECK(empty.Feed(h.data(), h.size(), [](Frame&&) {}) == FrameDecoder::Status::Empty);
}

TEST_CASE("path.normalize") {
	auto p = NormalizePath("@.\\Content\\Script\\UI\\..\\UI\\BagPanel.lua", false);
	CHECK_EQ(p.text, std::string("content/script/ui/bagpanel"));
	auto q = NormalizePath("D:/Game//Content/Script/UI/BagPanel.lua", true);
	CHECK_EQ(q.text, std::string("D:/Game/Content/Script/UI/BagPanel"));
	auto r = NormalizePath("../../lua/main.luac", true);
	CHECK_EQ(r.text, std::string("lua/main"));
}

TEST_CASE("path.suffix_match") {
	auto local = NormalizePath("D:/Game/Content/Script/UI/BagPanel.lua", false);
	auto chunk = NormalizePath("@Content/Script/UI/BagPanel.lua", false);
	CHECK_EQ(SuffixMatchSegments(local, chunk), static_cast<size_t>(4));
	auto other = NormalizePath("@Content/Script/UI/Panel.lua", false);
	CHECK_EQ(SuffixMatchSegments(local, other), static_cast<size_t>(0));
	auto deviceAbs = NormalizePath("@/data/app/assets/Content/Script/UI/BagPanel.lua", false);
	auto rel = NormalizePath("Script/UI/BagPanel.lua", false);
	CHECK_EQ(SuffixMatchSegments(rel, deviceAbs), static_cast<size_t>(3));
}

static std::shared_ptr<Breakpoint> MakeBp(const char* id, const char* path, int line, const char* chunk = "", uint64_t vmId = 0) {
	auto bp = std::make_shared<Breakpoint>();
	bp->serial = NextBreakpointSerial();
	bp->id = id;
	bp->path = path;
	bp->chunkName = chunk;
	bp->line = line;
	bp->vmId = vmId;
	bp->normPath = NormalizePath(path, false);
	if (*chunk) bp->normChunk = NormalizePath(chunk, false);
	return bp;
}

TEST_CASE("index.match_by_suffix_and_vm_filter") {
	std::vector<BreakpointPtr> bps = {
		MakeBp("a", "D:/Game/Content/Script/UI/BagPanel.lua", 10),
		MakeBp("b", "D:/Game/Content/Script/UI/BagPanel.lua", 20, "", 2),
		MakeBp("c", "D:/Game/Content/Script/Other.lua", 5),
	};
	BreakpointIndex idx(1, bps, false);
	auto m1 = idx.Match("@Content/Script/UI/BagPanel.lua", 1);
	CHECK_EQ(m1.bps.size(), static_cast<size_t>(1));
	CHECK(m1.HasLine(10));
	CHECK(!m1.HasLine(20));
	auto m2 = idx.Match("@Content/Script/UI/BagPanel.lua", 2);
	CHECK_EQ(m2.bps.size(), static_cast<size_t>(2));
	CHECK(idx.Match("=[C]", 1).bps.empty());
	CHECK(idx.Match("@Script/Nothing.lua", 1).bps.empty());
}

TEST_CASE("index.longest_match_wins_and_ambiguous") {
	std::vector<BreakpointPtr> bps = {
		MakeBp("a", "D:/Game/A/UI/Main.lua", 1),
		MakeBp("b", "D:/Game/B/UI/Main.lua", 2),
	};
	BreakpointIndex idx(1, bps, false);
	auto exact = idx.Match("@A/UI/Main.lua", 1);
	CHECK_EQ(exact.bps.size(), static_cast<size_t>(1));
	CHECK_EQ(exact.bps[0]->id, std::string("a"));
	auto amb = idx.Match("@UI/Main.lua", 1);
	CHECK(amb.bps.empty());
	CHECK_EQ(amb.ambiguous.size(), static_cast<size_t>(2));
}

TEST_CASE("path.file_chunk_without_at") {
	// UnLua 等不加 '@' 的路径 / 模块名
	CHECK(IsFileChunk("@main.lua"));
	CHECK(IsFileChunk("D:/Game/Content/Script/BP_CharacterBase.lua"));
	CHECK(IsFileChunk("Content\\Script\\BP_CharacterBase.lua"));
	CHECK(IsFileChunk("BP_CharacterBase.lua"));
	CHECK(IsFileChunk("D:/My Game/Script/A.lua"));
	CHECK(IsFileChunk("Tutorials.BP_CharacterBase"));
	CHECK(IsFileChunk("BP_CharacterBase"));
	// 代码串仍然不是文件
	CHECK(!IsFileChunk("=[C]"));
	CHECK(!IsFileChunk("return 1"));
	CHECK(!IsFileChunk("return"));
	CHECK(!IsFileChunk("return M.lua"));
	CHECK(!IsFileChunk("x = a / b"));
	CHECK(!IsFileChunk("print('a/b.lua')"));
	CHECK(!IsFileChunk("local t = {}\nreturn t"));
	CHECK(!IsFileChunk("[string \"boot\"]"));
	CHECK_EQ(NormalizePath("Tutorials.BP_CharacterBase", false).text, std::string("tutorials/bp_characterbase"));
	CHECK_EQ(NormalizePath("BP_CharacterBase.lua", true).text, std::string("BP_CharacterBase"));
}

TEST_CASE("index.match_unlua_chunks") {
	std::vector<BreakpointPtr> bps = {MakeBp("a", "D:/Game/Content/Script/Tutorials/BP_CharacterBase.lua", 12)};
	BreakpointIndex idx(1, bps, false);
	CHECK_EQ(idx.Match("D:\\Game\\Content\\Script\\Tutorials\\BP_CharacterBase.lua", 1).bps.size(), static_cast<size_t>(1));
	CHECK_EQ(idx.Match("Tutorials/BP_CharacterBase.lua", 1).bps.size(), static_cast<size_t>(1));
	CHECK_EQ(idx.Match("Tutorials.BP_CharacterBase", 1).bps.size(), static_cast<size_t>(1));
	CHECK_EQ(idx.Match("BP_CharacterBase", 1).bps.size(), static_cast<size_t>(1));
	CHECK(idx.Match("return BP_CharacterBase", 1).bps.empty());
}

TEST_CASE("index.chunkname_exact") {
	std::vector<BreakpointPtr> bps = {MakeBp("a", "D:/x/main.lua", 3, "[string \"boot\"]")};
	BreakpointIndex idx(1, bps, false);
	CHECK_EQ(idx.Match("[string \"boot\"]", 1).bps.size(), static_cast<size_t>(1));
	CHECK(idx.Match("@main.lua", 1).bps.empty());
}

TEST_CASE("hit_condition.parse_and_check") {
	HitCondition h;
	std::string err;
	CHECK(HitCondition::Parse("5", h, err));
	CHECK(!h.Check(4) && h.Check(5) && !h.Check(6));
	CHECK(HitCondition::Parse(">= 3", h, err));
	CHECK(!h.Check(2) && h.Check(3) && h.Check(9));
	CHECK(HitCondition::Parse("> 3", h, err));
	CHECK(!h.Check(3) && h.Check(4));
	CHECK(HitCondition::Parse("%3", h, err));
	CHECK(!h.Check(2) && h.Check(3) && h.Check(6));
	CHECK(HitCondition::Parse("", h, err));
	CHECK(h.Check(1));
	CHECK(!HitCondition::Parse("abc", h, err));
	CHECK(!HitCondition::Parse("%0", h, err));
}

TEST_CASE("value_path.parse") {
	std::vector<PathSegment> segs;
	std::string err;
	CHECK(ParseValuePath("self.items[3][\"a b\"].id", segs, err));
	CHECK_EQ(segs.size(), static_cast<size_t>(5));
	CHECK_EQ(segs[0].text, std::string("self"));
	CHECK(segs[2].kind == PathSegment::Kind::Integer && segs[2].integer == 3);
	CHECK_EQ(segs[3].text, std::string("a b"));
	CHECK(!ParseValuePath("a + b", segs, err));
	CHECK(!ParseValuePath("f()", segs, err));
	CHECK(!ParseValuePath("1abc", segs, err));
	CHECK_EQ(err, std::string("BAD_ROOT"));
	CHECK(!ParseValuePath("a[\"x]", segs, err));
	CHECK(IsLiteral("12.5e3"));
	CHECK(IsLiteral("'hi'"));
	CHECK(IsLiteral(" nil "));
	CHECK(!IsLiteral("os.exit()"));
}

int main() {
	return emmy::test::RunAll();
}
