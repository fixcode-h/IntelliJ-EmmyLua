# emmy_debugger_core：协议 v3 的新原生调试核心（C++17）。
# 可以单独构建（cmake -S native/emmy_debugger_core），也可以被 native/CMakeLists.txt add_subdirectory。
# 设计见 docs/design/2026-11-debugger-redesign.md 第 5 节，协议见 docs/design/protocol-v3.md。
cmake_minimum_required(VERSION 3.14)
project(emmy_debugger_core CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Lua 版本：51 / 52 / 53 / 54。
# Lua 接法（EMMY_CORE_LUA_MODE）：
#   source   核心链接自带的 Lua 静态库（emmy_core_lua），内嵌宿主、测试、基准用
#   dynamic  不带 Lua 实现，运行时按名字解析宿主的 lua_*（Emmy_SetLuaApiResolver），附加注入（Android agent）用
# Android 默认 dynamic，其余默认 source。
set(EMMY_CORE_LUA_VERSION "54" CACHE STRING "Lua version: 51/52/53/54")
if(ANDROID)
    set(_emmy_default_mode dynamic)
else()
    set(_emmy_default_mode source)
endif()
set(EMMY_CORE_LUA_MODE "${_emmy_default_mode}" CACHE STRING "source / dynamic")
# 被别的工程 add_subdirectory 时默认不编测试和基准
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    set(_emmy_top ON)
else()
    set(_emmy_top OFF)
endif()
option(EMMY_CORE_BUILD_TESTS "Build emmy_debugger_core tests" ${_emmy_top})
option(EMMY_CORE_BUILD_BENCH "Build bench_hook" ${_emmy_top})
set(EMMY_CORE_VERSION "3.0.0-dev" CACHE STRING "runtime version reported in welcome")

get_filename_component(EMMY_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
set(EMMY_THIRD_PARTY "${EMMY_NATIVE_DIR}/third-party")

if(EMMY_CORE_LUA_VERSION STREQUAL "54")
    set(EMMY_CORE_LUA_DIR "${EMMY_THIRD_PARTY}/lua-5.4.6/src")
elseif(EMMY_CORE_LUA_VERSION STREQUAL "53")
    set(EMMY_CORE_LUA_DIR "${EMMY_THIRD_PARTY}/lua-5.3.5/src")
elseif(EMMY_CORE_LUA_VERSION STREQUAL "52")
    set(EMMY_CORE_LUA_DIR "${EMMY_THIRD_PARTY}/lua-5.2.4/src")
elseif(EMMY_CORE_LUA_VERSION STREQUAL "51")
    set(EMMY_CORE_LUA_DIR "${EMMY_THIRD_PARTY}/lua-5.1.5/src")
else()
    message(FATAL_ERROR "EMMY_CORE_LUA_VERSION 只支持 51/52/53/54，当前为 ${EMMY_CORE_LUA_VERSION}")
endif()

# ---- 内置 Lua（静态库，source 模式的核心、测试、基准使用） ----
file(GLOB EMMY_CORE_LUA_SOURCES "${EMMY_CORE_LUA_DIR}/*.c")
list(FILTER EMMY_CORE_LUA_SOURCES EXCLUDE REGEX ".*/(lua|luac|onelua|print)\\.c$")
add_library(emmy_core_lua STATIC EXCLUDE_FROM_ALL ${EMMY_CORE_LUA_SOURCES})
target_include_directories(emmy_core_lua PUBLIC "${EMMY_CORE_LUA_DIR}")
if(UNIX AND NOT APPLE AND NOT ANDROID)
    target_compile_definitions(emmy_core_lua PRIVATE LUA_USE_LINUX)
elseif(APPLE)
    target_compile_definitions(emmy_core_lua PRIVATE LUA_USE_MACOSX)
endif()
if(EMMY_CORE_LUA_VERSION STREQUAL "52")
    target_compile_definitions(emmy_core_lua PUBLIC LUA_COMPAT_ALL)
elseif(EMMY_CORE_LUA_VERSION STREQUAL "53")
    target_compile_definitions(emmy_core_lua PUBLIC LUA_COMPAT_5_2)
endif()
if(UNIX)
    target_link_libraries(emmy_core_lua PUBLIC m ${CMAKE_DL_LIBS})
endif()

# 测试和基准总是要一份 Lua；source 模式的核心也链它。只编 dynamic 核心（agent）时没人用，不会被构建。
if(NOT EMMY_CORE_LUA_MODE STREQUAL "source" AND NOT EMMY_CORE_LUA_MODE STREQUAL "dynamic")
    message(FATAL_ERROR "EMMY_CORE_LUA_MODE 只支持 source / dynamic，当前为 ${EMMY_CORE_LUA_MODE}")
endif()

# ---- 核心 ----
set(EMMY_CORE_DYN_SOURCES "")
if(EMMY_CORE_LUA_MODE STREQUAL "dynamic")
    if(MSVC)
        message(FATAL_ERROR "dynamic 模式暂只支持 GCC / Clang（自带 lauxlib.c 用到 __typeof__）")
    endif()
    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/gen_lua_dyn.cmake)
    set(EMMY_CORE_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
    file(MAKE_DIRECTORY "${EMMY_CORE_GEN_DIR}")
    emmy_gen_lua_dyn("${EMMY_CORE_LUA_DIR}" "${EMMY_CORE_GEN_DIR}")
    set(EMMY_CORE_DYN_SOURCES src/lua_dyn.c "${EMMY_CORE_LUA_DIR}/lauxlib.c")
endif()

add_library(emmy_debugger_core STATIC
    ${EMMY_CORE_DYN_SOURCES}
    src/frame.cpp
    src/path_match.cpp
    src/breakpoints.cpp
    src/value_path.cpp
    src/lua_internal.cpp
    src/hook_mux.cpp
    src/inspector.cpp
    src/vm.cpp
    src/session.cpp
    src/transport.cpp
    src/core.cpp
)
target_include_directories(emmy_debugger_core
    PUBLIC  "${CMAKE_CURRENT_SOURCE_DIR}/include"
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src" "${EMMY_THIRD_PARTY}/nlohmann/include")
target_compile_definitions(emmy_debugger_core PRIVATE
    EMMY_CORE_LUA_${EMMY_CORE_LUA_VERSION}
    EMMY_CORE_VERSION_STRING="${EMMY_CORE_VERSION}")
if(EMMY_CORE_LUA_MODE STREQUAL "source")
    target_link_libraries(emmy_debugger_core PUBLIC emmy_core_lua)
else()
    # 只要头文件；所有源文件（含 lauxlib.c）最先包含 lua_dyn.h，把 lua_* 调用换成函数指针
    target_include_directories(emmy_debugger_core PRIVATE "${EMMY_CORE_LUA_DIR}" "${EMMY_CORE_GEN_DIR}")
    target_compile_definitions(emmy_debugger_core PRIVATE EMMY_CORE_LUA_DYNAMIC)
    target_compile_options(emmy_debugger_core PRIVATE
        "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/src/lua_dyn.h" -fvisibility=hidden)
    # 强制包含的 lua_dyn.h 先于 lauxlib.c 自己的 #define LUA_LIB 读到 luaconf.h，所以这里提前定义
    set_source_files_properties("${EMMY_CORE_LUA_DIR}/lauxlib.c" PROPERTIES COMPILE_OPTIONS "-w" COMPILE_DEFINITIONS LUA_LIB)
    target_link_libraries(emmy_debugger_core PUBLIC ${CMAKE_DL_LIBS})
endif()
find_package(Threads REQUIRED)
target_link_libraries(emmy_debugger_core PUBLIC Threads::Threads)
if(WIN32)
    target_link_libraries(emmy_debugger_core PUBLIC ws2_32)
endif()
if(MSVC)
    target_compile_options(emmy_debugger_core PRIVATE /W4 /utf-8 /wd4100)
else()
    target_compile_options(emmy_debugger_core PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()

if(EMMY_CORE_BUILD_TESTS)
    enable_testing()
    add_executable(emmy_core_unit_test tests/unit_test.cpp)
    target_include_directories(emmy_core_unit_test PRIVATE src "${EMMY_THIRD_PARTY}/nlohmann/include")
    target_link_libraries(emmy_core_unit_test PRIVATE emmy_debugger_core emmy_core_lua)
    add_test(NAME emmy_core_unit_test COMMAND emmy_core_unit_test)

    add_executable(emmy_core_session_test tests/session_test.cpp tests/test_client.cpp)
    target_include_directories(emmy_core_session_test PRIVATE src tests "${EMMY_THIRD_PARTY}/nlohmann/include")
    target_link_libraries(emmy_core_session_test PRIVATE emmy_debugger_core emmy_core_lua)
    if(EMMY_CORE_LUA_MODE STREQUAL "dynamic")
        # dynamic 模式在主机上测：核心用 dlsym 找测试程序自己链接的 Lua，所以要导出符号
        set_target_properties(emmy_core_session_test PROPERTIES ENABLE_EXPORTS ON)
    endif()
    add_test(NAME emmy_core_session_test COMMAND emmy_core_session_test)

    # IDE 端到端测试的宿主（IntelliJ 侧 debugger-engine 的 NativeRuntimeE2eTest 启动它）
    add_executable(emmy_core_ide_host tests/ide_host.cpp)
    target_include_directories(emmy_core_ide_host PRIVATE src)
    target_link_libraries(emmy_core_ide_host PRIVATE emmy_debugger_core emmy_core_lua)
    if(EMMY_CORE_LUA_MODE STREQUAL "dynamic")
        set_target_properties(emmy_core_ide_host PROPERTIES ENABLE_EXPORTS ON)
    endif()
endif()

if(EMMY_CORE_BUILD_BENCH)
    add_executable(bench_hook bench/bench_hook.cpp tests/test_client.cpp)
    target_include_directories(bench_hook PRIVATE src tests "${EMMY_THIRD_PARTY}/nlohmann/include")
    target_link_libraries(bench_hook PRIVATE emmy_debugger_core emmy_core_lua)
    if(EMMY_CORE_LUA_MODE STREQUAL "dynamic")
        set_target_properties(bench_hook PROPERTIES ENABLE_EXPORTS ON)
    endif()
endif()
