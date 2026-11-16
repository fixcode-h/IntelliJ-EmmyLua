// 在当前进程里找静态链接了 Lua 的 so（UE 是 libUnreal.so）：加载基址和内存里的 GNU Build ID。
#pragma once

#include <cstdint>
#include <string>

namespace emmy_agent {

struct ModuleInfo {
    bool found = false;
    std::string path;      // 链接器记录的路径（APK 内映射时形如 base.apk!/lib/arm64-v8a/libUnreal.so）
    uintptr_t loadBias = 0; // dl_iterate_phdr 的 dlpi_addr：运行地址 = loadBias + st_value
    std::string buildId;    // 十六进制小写；没有 note 时为空
};

// moduleName 按文件名匹配（libUnreal.so），也接受 "!/lib/<abi>/libUnreal.so" 形式。
ModuleInfo FindModule(const std::string& moduleName);

// 判断 dlpi_name 是否就是要找的 so，单独拿出来便于测试。
bool ModuleNameMatches(const char* loadedName, const std::string& moduleName);

// 解析一段 note 数据，取 NT_GNU_BUILD_ID。
std::string ParseGnuBuildId(const uint8_t* notes, size_t size);

} // namespace emmy_agent
