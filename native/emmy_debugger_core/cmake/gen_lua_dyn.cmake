# 动态 Lua API：从所选版本的 lua.h / lauxlib.h 里取出全部 API 名字，在 out_dir 下生成
# emmy_lua_dyn_list.h（前两项）和 emmy_lua_dyn_calls.h（第三项）。
#   EMMY_LUA_API_LIST(X)   每个 lua_* 函数一项，lua_dyn.c 用它定义函数指针、按名字解析
#   EMMY_LUA_DYN_CALLS     #define lua_xxx(...) (emmy_dyn_lua_xxx)(__VA_ARGS__)：核心和 lauxlib 的调用都走指针
#   luaL_* 改名            #define luaL_xxx emmy_luaL_xxx：自带的 lauxlib 改名，不和宿主（或测试里链接的 Lua）冲突
# 在 configure 时生成，不用 Python，也不把四个版本的列表提交进仓库。
function(emmy_gen_lua_dyn lua_dir out_dir)
    file(READ "${lua_dir}/lua.h" _luah)
    string(REGEX MATCHALL "LUA_API[^;]*\\((lua_[A-Za-z0-9_]+)\\)[ \t]*\\(" _m "${_luah}")
    set(_api "")
    foreach(_d IN LISTS _m)
        string(REGEX REPLACE ".*\\((lua_[A-Za-z0-9_]+)\\)[ \t]*\\($" "\\1" _n "${_d}")
        list(APPEND _api ${_n})
    endforeach()
    # 5.1 的调试 API 名字不带括号：LUA_API int lua_getstack (lua_State *L, ...);
    string(REGEX MATCHALL "LUA_API[^;(]*[ *](lua_[a-z][a-z0-9_]*)[ \t]*\\(" _m "${_luah}")
    foreach(_d IN LISTS _m)
        string(REGEX REPLACE ".*[ *](lua_[a-z][a-z0-9_]*)[ \t]*\\($" "\\1" _n "${_d}")
        list(APPEND _api ${_n})
    endforeach()
    list(REMOVE_DUPLICATES _api)

    # lauxlib.h 里出现的所有 luaL_* / luaI_* 名字（函数、类型）都改名，但跳过它自己定义成宏的
    # （例如 5.1 的 #define luaI_openlib luaL_openlib：改名 luaL_openlib 即可，宏会接着展开到新名字）
    file(READ "${lua_dir}/lauxlib.h" _auxh)
    string(REGEX MATCHALL "lua[LI]_[A-Za-z0-9_]+" _ids "${_auxh}")
    list(REMOVE_DUPLICATES _ids)
    string(REGEX MATCHALL "#[ \t]*define[ \t]+lua[LI]_[A-Za-z0-9_]+" _defs "${_auxh}")
    set(_macros "")
    foreach(_d IN LISTS _defs)
        string(REGEX REPLACE ".*define[ \t]+" "" _n "${_d}")
        list(APPEND _macros ${_n})
    endforeach()
    set(_aux "")
    foreach(_n IN LISTS _ids)
        list(FIND _macros ${_n} _i)
        if(_i EQUAL -1)
            list(APPEND _aux ${_n})
        endif()
    endforeach()

    list(LENGTH _api _cnt)
    if(_cnt LESS 50)
        message(FATAL_ERROR "从 ${lua_dir}/lua.h 只解析到 ${_cnt} 个 API，格式不认识")
    endif()

    set(_s "/* 自动生成（cmake/gen_lua_dyn.cmake），不要手改 */\n#pragma once\n\n#define EMMY_LUA_API_LIST(X)")
    foreach(_n IN LISTS _api)
        string(APPEND _s " \\\n\tX(${_n})")
    endforeach()
    string(APPEND _s "\n\n")
    foreach(_n IN LISTS _aux)
        string(APPEND _s "#define ${_n} emmy_${_n}\n")
    endforeach()
    _emmy_write_if_changed("${out_dir}/emmy_lua_dyn_list.h" "${_s}")

    set(_s "/* 自动生成（cmake/gen_lua_dyn.cmake），不要手改 */\n#pragma once\n\n")
    foreach(_n IN LISTS _api)
        string(APPEND _s "#define ${_n}(...) (emmy_dyn_${_n})(__VA_ARGS__)\n")
    endforeach()
    _emmy_write_if_changed("${out_dir}/emmy_lua_dyn_calls.h" "${_s}")
endfunction()

# 内容没变就不碰文件，避免每次 configure 都全量重编
function(_emmy_write_if_changed path content)
    file(WRITE "${path}.tmp" "${content}")
    configure_file("${path}.tmp" "${path}" COPYONLY)
endfunction()
