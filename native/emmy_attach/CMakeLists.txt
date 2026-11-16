# emmy_attach.dll：Windows 本机附加（协议 v3）。由 emmy_tool attach -protocol 3 注入目标进程。
# 它自己不带调试核心：找到进程里的 Lua 模块、判断 Lua 版本后，加载同目录下 lua5X/emmy_debugger_core.dll
# （emmy_debugger_core 以 dynamic + EMMY_CORE_BUILD_SHARED 方式按版本各编一份），用目标进程自己的 Lua。
# 约定见 docs/design/protocol-v3.md 第 16 节。
cmake_minimum_required(VERSION 3.11)
project(emmy_attach)

add_library(emmy_attach SHARED src/dllmain.cpp src/attach.cpp)
set_target_properties(emmy_attach PROPERTIES PREFIX "")
target_include_directories(emmy_attach PRIVATE
        src
        ${emmy_SOURCE_DIR}/include
        ${emmy_SOURCE_DIR}/emmy_debugger_core/include
        ${emmy_SOURCE_DIR}/third-party/EasyHook/EasyHookDll)
target_link_libraries(emmy_attach PRIVATE EasyHook shared psapi)
if(MSVC)
    target_compile_options(emmy_attach PRIVATE /utf-8)
endif()

install(TARGETS emmy_attach LIBRARY DESTINATION bin RUNTIME DESTINATION bin)
