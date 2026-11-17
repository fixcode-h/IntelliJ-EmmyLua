# Windows 本机附加的端到端测试（CI：native-core.yml 的 windows-attach）：
#   attach_host.exe   像游戏一样链接 lua54.dll（导出 Lua API），不知道调试器的存在，每 2 毫秒调一次 tick(i)
#   attach_host_late.exe  延迟加载 lua54.dll（像 UnLua 的 Lua.dll），收到 GO 才加载 Lua、建 VM、跑一次模块顶层代码
#   attach_driver.exe 扮演 IDE：起宿主 → emmy_tool attach 注入 → 连 runtime → 下断点等暂停 → 继续 → 断开后再附加一次；
#                     再起晚加载的宿主，先附加（等 Lua）再让它加载 Lua，断在只执行一次的顶层代码上
# 用法：attach_driver <附加工具目录（emmy_tool.exe、emmy_attach.dll、lua54/emmy_debugger_core.dll）> <attach_host.exe> [attach_host_late.exe]
cmake_minimum_required(VERSION 3.14)
project(emmy_attach_e2e CXX C)

if(NOT WIN32)
    message(FATAL_ERROR "attach_e2e 只在 Windows 上构建")
endif()

set(CMAKE_CXX_STANDARD 17)
get_filename_component(EMMY_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)

set(LUA_BUILD_AS_DLL ON)
add_subdirectory("${EMMY_NATIVE_DIR}/third-party/lua-5.4.6" lua54)
target_include_directories(lua54 PUBLIC "${EMMY_NATIVE_DIR}/third-party/lua-5.4.6/src")

add_executable(attach_host attach_host.cpp)
target_link_libraries(attach_host PRIVATE lua54)

add_executable(attach_host_late attach_host_late.cpp)
target_link_libraries(attach_host_late PRIVATE lua54 delayimp)
target_link_options(attach_host_late PRIVATE /DELAYLOAD:lua54.dll)

add_executable(attach_driver attach_driver.cpp
    "${EMMY_NATIVE_DIR}/emmy_debugger_core/tests/test_client.cpp"
    "${EMMY_NATIVE_DIR}/emmy_debugger_core/src/frame.cpp")
target_include_directories(attach_driver PRIVATE
    "${EMMY_NATIVE_DIR}/emmy_debugger_core/tests"
    "${EMMY_NATIVE_DIR}/emmy_debugger_core/src"
    "${EMMY_NATIVE_DIR}/third-party/nlohmann/include")
target_compile_options(attach_driver PRIVATE /utf-8)
target_compile_options(attach_host PRIVATE /utf-8)
target_compile_options(attach_host_late PRIVATE /utf-8)
target_link_libraries(attach_driver PRIVATE ws2_32)
target_compile_definitions(attach_driver PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
