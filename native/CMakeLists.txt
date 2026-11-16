# Windows 本机附加用的工具：emmy_tool.exe（注入）、emmy_attach.dll（目标进程里的引导）、EasyHook.dll。
# 调试核心本身在 emmy_debugger_core/，单独构建（每个 Lua 版本一份 dynamic + shared 的 emmy_debugger_core.dll）；
# Android 真机的 agent 在 platforms/android/。约定见 docs/design/protocol-v3.md 第 16 节。
cmake_minimum_required(VERSION 3.14)

project(emmy)

set(CMAKE_CXX_STANDARD 14)

if(NOT WIN32)
    message(STATUS "native/ 顶层只有 Windows 附加工具；调试核心请直接构建 native/emmy_debugger_core")
    return()
endif()

add_subdirectory(shared)
add_subdirectory(third-party/EasyHook)
add_subdirectory(emmy_tool)
add_subdirectory(emmy_attach)
