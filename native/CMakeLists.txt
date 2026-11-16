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

# EasyHook 的 CMakeLists 用这个宏给 VS 工程按目录分组
macro(source_group_by_dir proj_dir source_files)
    if(MSVC OR APPLE)
        get_filename_component(sgbd_cur_dir ${proj_dir} ABSOLUTE)
        foreach(sgbd_file ${${source_files}})
            get_filename_component(sgbd_abs_file ${sgbd_file} ABSOLUTE)
            file(RELATIVE_PATH sgbd_fpath ${sgbd_cur_dir} ${sgbd_abs_file})
            string(REGEX REPLACE "\(.*\)/.*" \\1 sgbd_group_name ${sgbd_fpath})
            string(COMPARE EQUAL ${sgbd_fpath} ${sgbd_group_name} sgbd_nogroup)
            string(REPLACE "/" "\\" sgbd_group_name ${sgbd_group_name})
            if(sgbd_nogroup)
                set(sgbd_group_name "\\")
            endif(sgbd_nogroup)
            source_group(${sgbd_group_name} FILES ${sgbd_file})
        endforeach(sgbd_file)
    endif(MSVC OR APPLE)
endmacro(source_group_by_dir)

add_subdirectory(shared)
add_subdirectory(third-party/EasyHook)
add_subdirectory(emmy_tool)
add_subdirectory(emmy_attach)
