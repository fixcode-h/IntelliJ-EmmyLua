# Android 真机附加 agent（libemmylua_agent.so），见 docs/design/2026-11-debugger-redesign.md 7.2 / 8.4。
#
# Android（NDK）：
#   cmake -S native/platforms/android/agent -B build/agent-arm64 \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static
#   或直接用 native/platforms/android/build_agent.sh。
# 主机单测（Linux）：
#   cmake -S native/platforms/android/agent -B build/agent-host -DEMMY_AGENT_BUILD_TESTS=ON && ctest
cmake_minimum_required(VERSION 3.14)
project(emmylua_agent C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(EMMY_AGENT_BUILD_TESTS "编主机单测（Linux）" OFF)
set(EMMY_SHADOWHOOK_DIR "" CACHE PATH "ShadowHook 源码目录（android-inline-hook 仓库根）；留空则按 EMMY_SHADOWHOOK_TAG 下载")
set(EMMY_SHADOWHOOK_TAG "v2.0.1" CACHE STRING "ShadowHook 版本")
set(EMMY_AGENT_VERSION "3.0.0-dev" CACHE STRING "agent 版本号，写进状态文件")

set(AGENT_SRC ${CMAKE_CURRENT_SOURCE_DIR}/src)

# 平台无关的部分：配置解析、模块查找、状态文件、抓 lua_State 的逻辑。主机单测也用它。
add_library(emmylua_agent_common STATIC
        ${AGENT_SRC}/agent_config.cpp
        ${AGENT_SRC}/module_info.cpp
        ${AGENT_SRC}/status_file.cpp
        ${AGENT_SRC}/lua_capture.cpp
        ${AGENT_SRC}/agent_runner.cpp)
target_include_directories(emmylua_agent_common PUBLIC ${AGENT_SRC})
target_compile_definitions(emmylua_agent_common PUBLIC EMMY_AGENT_VERSION="${EMMY_AGENT_VERSION}")
if(NOT MSVC)
    target_compile_options(emmylua_agent_common PRIVATE -Wall -Wextra -fvisibility=hidden)
endif()

if(ANDROID)
    if(NOT ANDROID_STL STREQUAL "c++_static")
        # agent 被注入到别人的进程里，不能依赖应用自带的 libc++_shared.so
        message(FATAL_ERROR "agent 必须用 -DANDROID_STL=c++_static 构建")
    endif()

    # ---- ShadowHook：静态编进 agent，符号全部隐藏，和游戏自带的 ShadowHook（如果有）互不影响 ----
    if(EMMY_SHADOWHOOK_DIR)
        set(SHADOWHOOK_ROOT ${EMMY_SHADOWHOOK_DIR})
    else()
        include(FetchContent)
        FetchContent_Declare(shadowhook_src
                GIT_REPOSITORY https://github.com/bytedance/android-inline-hook.git
                GIT_TAG ${EMMY_SHADOWHOOK_TAG}
                GIT_SHALLOW TRUE)
        FetchContent_GetProperties(shadowhook_src)
        if(NOT shadowhook_src_POPULATED)
            FetchContent_Populate(shadowhook_src)
        endif()
        set(SHADOWHOOK_ROOT ${shadowhook_src_SOURCE_DIR})
    endif()
    set(SH_DIR ${SHADOWHOOK_ROOT}/shadowhook/src/main/cpp)
    if(NOT EXISTS ${SH_DIR}/shadowhook.c)
        message(FATAL_ERROR "找不到 ShadowHook 源码：${SH_DIR}")
    endif()
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(SH_ARCH arm64)
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(SH_ARCH arm)
    else()
        message(FATAL_ERROR "agent 只支持 arm64-v8a / armeabi-v7a（ShadowHook 的限制），当前 ${ANDROID_ABI}")
    endif()
    enable_language(ASM)
    file(GLOB SH_SRC ${SH_DIR}/*.c ${SH_DIR}/arch/${SH_ARCH}/*.c ${SH_DIR}/arch/${SH_ARCH}/*.S
            ${SH_DIR}/common/*.c ${SH_DIR}/third_party/*/*.c)
    # sh_jni.c 定义了 JNI_OnLoad，和 agent 自己的入口冲突，agent 不走 Java 层
    list(FILTER SH_SRC EXCLUDE REGEX ".*/sh_jni\\.c$")
    add_library(emmy_shadowhook STATIC ${SH_SRC})
    target_compile_features(emmy_shadowhook PRIVATE c_std_11)
    target_compile_options(emmy_shadowhook PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections -w)
    target_include_directories(emmy_shadowhook
            PUBLIC ${SH_DIR}/include
            PRIVATE ${SH_DIR} ${SH_DIR}/arch/${SH_ARCH} ${SH_DIR}/common
            ${SH_DIR}/third_party/xdl ${SH_DIR}/third_party/bsd ${SH_DIR}/third_party/lss)

    add_library(emmylua_agent SHARED
            ${AGENT_SRC}/agent_main.cpp
            ${AGENT_SRC}/hook_shadowhook.cpp)
    target_link_libraries(emmylua_agent PRIVATE emmylua_agent_common emmy_shadowhook log)

    # 调试核心：单独构建 agent 时，native/emmy_debugger_core 存在就把它加进来
    set(EMMY_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../emmy_debugger_core)
    if(NOT TARGET emmy_debugger_core AND EXISTS ${EMMY_CORE_DIR}/CMakeLists.txt)
        add_subdirectory(${EMMY_CORE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/emmy_debugger_core)
    endif()
    # 同一 CMake 构建里有 emmy_debugger_core 就链接它，否则用占位实现（附加时状态文件会报错说明）
    if(TARGET emmy_debugger_core)
        target_link_libraries(emmylua_agent PRIVATE emmy_debugger_core)
        target_compile_definitions(emmylua_agent PRIVATE EMMY_AGENT_HAS_CORE_HEADER)
        target_compile_definitions(emmylua_agent_common PRIVATE EMMY_AGENT_HAS_CORE_HEADER)
        target_link_libraries(emmylua_agent_common PRIVATE emmy_debugger_core)
    else()
        message(WARNING "没有 emmy_debugger_core 目标，agent 使用占位核心（只能验证注入和 hook，不能调试）")
        target_sources(emmylua_agent PRIVATE ${AGENT_SRC}/core_stub.cpp)
    endif()

    target_compile_options(emmylua_agent PRIVATE -fvisibility=hidden -Wall -Wextra)
    target_link_options(emmylua_agent PRIVATE
            -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map
            -Wl,--exclude-libs,ALL
            -Wl,--gc-sections
            -Wl,--build-id=sha1
            -Wl,-z,max-page-size=16384) # Android 15 起的 16 KB 页设备
    set_target_properties(emmylua_agent PROPERTIES OUTPUT_NAME emmylua_agent)
elseif(EMMY_AGENT_BUILD_TESTS)
    enable_testing()
    # 夹具：带 Build ID 的小 so，测 dl_iterate_phdr 找模块和内存里读 Build ID
    add_library(agent_test_fixture SHARED tests/fixture_module.c)
    target_link_options(agent_test_fixture PRIVATE -Wl,--build-id=sha1)
    set_target_properties(agent_test_fixture PROPERTIES OUTPUT_NAME emmyagentfixture)

    add_executable(agent_host_test tests/agent_host_test.cpp ${AGENT_SRC}/hook_unavailable.cpp)
    target_link_libraries(agent_host_test PRIVATE emmylua_agent_common ${CMAKE_DL_LIBS} pthread)
    target_compile_definitions(agent_host_test PRIVATE
            FIXTURE_PATH="$<TARGET_FILE:agent_test_fixture>")
    add_dependencies(agent_host_test agent_test_fixture)
    add_test(NAME agent_host_test COMMAND agent_host_test)

    # ---- 端到端：真 Lua 5.4 + agent 主流程 + 真调试核心（dynamic 模式）+ 测试客户端走协议 v3 ----
    set(EMMY_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../emmy_debugger_core)
    if(EXISTS ${EMMY_CORE_DIR}/CMakeLists.txt)
        set(EMMY_CORE_LUA_MODE dynamic CACHE STRING "" FORCE)
        set(EMMY_CORE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
        set(EMMY_CORE_BUILD_BENCH OFF CACHE BOOL "" FORCE)
        add_subdirectory(${EMMY_CORE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/emmy_debugger_core)
        get_filename_component(EMMY_NATIVE_DIR ${EMMY_CORE_DIR}/.. ABSOLUTE)
        set(E2E_LUA_DIR ${EMMY_NATIVE_DIR}/third-party/lua-5.4.6/src)

        # 「游戏」：Lua 静态编进一个 so 且符号全部隐藏，和裁剪过导出表的 libUnreal.so 一样 dlsym 找不到
        file(GLOB E2E_LUA_SRC ${E2E_LUA_DIR}/*.c)
        list(FILTER E2E_LUA_SRC EXCLUDE REGEX ".*/(lua|luac|onelua)\\.c$")
        add_library(agent_e2e_fakeunreal SHARED ${E2E_LUA_SRC})
        target_compile_definitions(agent_e2e_fakeunreal PRIVATE LUA_USE_LINUX)
        target_compile_options(agent_e2e_fakeunreal PRIVATE -fvisibility=hidden -w)
        target_link_options(agent_e2e_fakeunreal PRIVATE -Wl,--build-id=sha1)
        target_link_libraries(agent_e2e_fakeunreal PRIVATE m ${CMAKE_DL_LIBS})
        set_target_properties(agent_e2e_fakeunreal PROPERTIES OUTPUT_NAME fakeunreal)

        # agent 公共部分要用核心的正式头文件重新编一份
        add_executable(agent_core_e2e_test tests/agent_core_e2e_test.cpp
                ${AGENT_SRC}/agent_config.cpp ${AGENT_SRC}/module_info.cpp ${AGENT_SRC}/status_file.cpp
                ${AGENT_SRC}/lua_capture.cpp ${AGENT_SRC}/agent_runner.cpp ${AGENT_SRC}/hook_unavailable.cpp
                ${EMMY_CORE_DIR}/tests/test_client.cpp)
        target_include_directories(agent_core_e2e_test PRIVATE ${AGENT_SRC} ${EMMY_CORE_DIR}/src ${EMMY_CORE_DIR}/tests
                ${EMMY_NATIVE_DIR}/third-party/nlohmann/include)
        target_compile_definitions(agent_core_e2e_test PRIVATE EMMY_AGENT_HAS_CORE_HEADER
                EMMY_AGENT_VERSION="${EMMY_AGENT_VERSION}"
                FAKE_UNREAL_PATH="$<TARGET_FILE:agent_e2e_fakeunreal>")
        target_link_libraries(agent_core_e2e_test PRIVATE emmy_debugger_core ${CMAKE_DL_LIBS} pthread)
        add_dependencies(agent_core_e2e_test agent_e2e_fakeunreal)
        add_test(NAME agent_core_e2e_test COMMAND agent_core_e2e_test)
    endif()
endif()
