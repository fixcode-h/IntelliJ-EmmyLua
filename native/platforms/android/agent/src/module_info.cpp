#include "module_info.h"

#include <cstring>
#include <elf.h>
#include <link.h>

namespace emmy_agent {

namespace {
constexpr uint32_t kNtGnuBuildId = 3;

size_t Align4(size_t v) { return (v + 3) & ~static_cast<size_t>(3); }

struct SearchContext {
    const std::string* moduleName;
    ModuleInfo* result;
};

int OnPhdr(struct dl_phdr_info* info, size_t, void* data) {
    auto* ctx = static_cast<SearchContext*>(data);
    if (!ModuleNameMatches(info->dlpi_name, *ctx->moduleName)) return 0;
    ModuleInfo& r = *ctx->result;
    r.found = true;
    r.path = info->dlpi_name ? info->dlpi_name : "";
    r.loadBias = static_cast<uintptr_t>(info->dlpi_addr);
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const auto& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_NOTE) continue;
        // PT_NOTE 在内存里（被某个 PT_LOAD 覆盖），直接按运行地址读，不用再打开文件
        const auto* notes = reinterpret_cast<const uint8_t*>(info->dlpi_addr + ph.p_vaddr);
        std::string id = ParseGnuBuildId(notes, ph.p_memsz);
        if (!id.empty()) {
            r.buildId = id;
            break;
        }
    }
    return 1; // 找到就停
}
} // namespace

bool ModuleNameMatches(const char* loadedName, const std::string& moduleName) {
    if (loadedName == nullptr || moduleName.empty()) return false;
    size_t n = std::strlen(loadedName);
    size_t m = moduleName.size();
    if (n < m) return false;
    if (std::strcmp(loadedName + n - m, moduleName.c_str()) != 0) return false;
    // 只认完整文件名：libUnreal.so 不能匹配 libFooUnreal.so
    return n == m || loadedName[n - m - 1] == '/';
}

std::string ParseGnuBuildId(const uint8_t* notes, size_t size) {
    static const char kHex[] = "0123456789abcdef";
    size_t pos = 0;
    while (pos + 12 <= size) {
        uint32_t nameSize, descSize, type;
        std::memcpy(&nameSize, notes + pos, 4);
        std::memcpy(&descSize, notes + pos + 4, 4);
        std::memcpy(&type, notes + pos + 8, 4);
        size_t nameStart = pos + 12;
        size_t descStart = nameStart + Align4(nameSize);
        size_t next = descStart + Align4(descSize);
        if (descStart > size || next > size || next <= pos) break;
        if (type == kNtGnuBuildId && nameSize == 4 && std::memcmp(notes + nameStart, "GNU", 4) == 0) {
            std::string out;
            out.reserve(descSize * 2);
            for (uint32_t i = 0; i < descSize; ++i) {
                uint8_t b = notes[descStart + i];
                out.push_back(kHex[b >> 4]);
                out.push_back(kHex[b & 0xf]);
            }
            return out;
        }
        pos = next;
    }
    return std::string();
}

ModuleInfo FindModule(const std::string& moduleName) {
    ModuleInfo result;
    SearchContext ctx{&moduleName, &result};
    dl_iterate_phdr(OnPhdr, &ctx);
    return result;
}

} // namespace emmy_agent
