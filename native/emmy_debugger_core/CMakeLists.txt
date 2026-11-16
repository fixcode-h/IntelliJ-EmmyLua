# emmy_debugger_core：协议 v3 的新原生调试核心（C++17）。
# 可以单独构建（cmake -S native/emmy_debugger_core），也可以被 native/CMakeLists.txt add_subdirectory。
# 设计见 docs/design/2026-11-debugger-redesign.md 第 5 节，协议见 docs/design/protocol-v3.md。
cmake_minimum_required(VERSION 3.14)
project(emmy_debugger_core CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Lua 版本：51 / 52 / 53 / 54。当前以「带 Lua 源码编译」方式构建（内嵌、测试、基准都用这种方式）；
# 附加注入用的动态 Lua API（运行时解析符号）在后续提交接入，见 README。
set(EMMY_CORE_LUA_VERSION "54" CACHE STRING "Lua version: 51/52/53/54")
option(EMMY_CORE_BUILD_TESTS "Build emmy_debugger_core tests" ON)
option(EMMY_CORE_BUILD_BENCH "Build bench_hook" ON)
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

# ---- 内置 Lua（静态库，测试/基准/内嵌宿主使用） ----
file(GLOB EMMY_CORE_LUA_SOURCES "${EMMY_CORE_LUA_DIR}/*.c")
list(FILTER EMMY_CORE_LUA_SOURCES EXCLUDE REGEX ".*/(lua|luac|onelua|print)\\.c$")
add_library(emmy_core_lua STATIC ${EMMY_CORE_LUA_SOURCES})
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

# ---- 核心 ----
add_library(emmy_debugger_core STATIC
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
target_link_libraries(emmy_debugger_core PUBLIC emmy_core_lua)
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
    target_link_libraries(emmy_core_unit_test PRIVATE emmy_debugger_core)
    add_test(NAME emmy_core_unit_test COMMAND emmy_core_unit_test)

    add_executable(emmy_core_session_test tests/session_test.cpp tests/test_client.cpp)
    target_include_directories(emmy_core_session_test PRIVATE src tests "${EMMY_THIRD_PARTY}/nlohmann/include")
    target_link_libraries(emmy_core_session_test PRIVATE emmy_debugger_core)
    add_test(NAME emmy_core_session_test COMMAND emmy_core_session_test)
endif()

if(EMMY_CORE_BUILD_BENCH)
    add_executable(bench_hook bench/bench_hook.cpp tests/test_client.cpp)
    target_include_directories(bench_hook PRIVATE src tests "${EMMY_THIRD_PARTY}/nlohmann/include")
    target_link_libraries(bench_hook PRIVATE emmy_debugger_core)
endif()
