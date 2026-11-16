cmake_minimum_required(VERSION 3.14)

project(emmy_tool)

add_executable(emmy_tool)

add_dependencies(
        emmy_tool
        shared
)

target_include_directories(emmy_tool PRIVATE
        src
)

target_sources(emmy_tool PRIVATE
        src/windows/utility.cpp
        src/windows/emmy_tool.windows.cpp
        src/command_line.cpp
        src/main.cpp
        src/emmy_tool.cpp
)

target_link_libraries(
        emmy_tool
        imagehlp
        ws2_32
        shared
)


install(
        TARGETS emmy_tool
        LIBRARY DESTINATION lib
        RUNTIME DESTINATION bin
)
if(MSVC)
    # 源文件和 attach_result.h 都是 UTF-8，输出给 IDE 的 JSON 也按 UTF-8
    target_compile_options(emmy_tool PRIVATE /utf-8)
endif()
