/* 测试夹具：只有名字和 Lua API 一样的空函数，用来验证符号提取，不是真 Lua。 */
typedef struct lua_State lua_State;
#define F(n) int n(lua_State* L) { return (int)(long)L + __LINE__; }
F(lua_gettop) F(lua_settop) F(lua_type) F(lua_typename) F(lua_tolstring) F(lua_toboolean)
F(lua_tothread) F(lua_pushnil) F(lua_pushnumber) F(lua_pushlstring) F(lua_pushstring)
F(lua_pushcclosure) F(lua_pushboolean) F(lua_pushvalue) F(lua_getfield) F(lua_next)
F(lua_createtable) F(lua_setfield) F(lua_setmetatable) F(lua_getstack) F(lua_getinfo)
F(lua_getlocal) F(lua_getupvalue) F(lua_setupvalue) F(lua_sethook) F(lua_gethook)
F(lua_gethookmask) F(lua_gethookcount) F(luaL_loadstring) F(luaL_checklstring)
F(luaL_checknumber) F(lua_topointer) F(lua_getmetatable) F(lua_rawget) F(lua_rawset)
F(lua_pushlightuserdata) F(lua_touserdata) F(lua_close) F(lua_pushthread)
F(lua_rawseti) F(lua_rawgeti) F(lua_pcallk) F(lua_resume) F(lua_newstate) F(lua_newthread)
F(lua_newuserdatauv) F(lua_callk) F(lua_rotate)
/* 内部函数，用 static 模拟 hidden，只在 .symtab 里 */
__attribute__((visibility("hidden"))) int luaE_warning(lua_State* L) { return 1; }
/* 名字相近但不是我们要的 */
int lua_gettop_wrapper(lua_State* L) { return lua_gettop(L); }
