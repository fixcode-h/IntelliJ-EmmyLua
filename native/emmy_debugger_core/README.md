# emmy_debugger_core

协议 v3 的原生调试核心（C++17）。协议见 [docs/design/protocol-v3.md](../../docs/design/protocol-v3.md)，整体设计见 [docs/design/2026-11-debugger-redesign.md](../../docs/design/2026-11-debugger-redesign.md) 第 5 节。

## 构建

```bash
cmake -S native/emmy_debugger_core -B build/core -DCMAKE_BUILD_TYPE=Release \
      -DEMMY_CORE_LUA_VERSION=54          # 51 / 52 / 53 / 54
      -DEMMY_CORE_LUA_MODE=source         # source / dynamic，见下
cmake --build build/core
ctest --test-dir build/core               # 单元 + 端到端
./build/core/bench_hook [--check] [--json out.json] [--repeat N]
```

作为子目录引入（`add_subdirectory`）时导出静态库目标 `emmy_debugger_core`，公开头文件 `<emmy/emmy_core.h>`；测试和基准默认不编。

### Lua 接法 `EMMY_CORE_LUA_MODE`

| 模式 | 用途 | 说明 |
| --- | --- | --- |
| `source`（桌面默认） | 内嵌到自己编 Lua 的宿主、测试、基准 | 链接 `native/third-party/lua-x` 编出的 `emmy_core_lua` |
| `dynamic`（Android 默认） | 附加到别人的进程（Android agent） | 不带 Lua 实现；所有 `lua_*` 经函数指针调用宿主那份 Lua，指针由 `Emmy_SetLuaApiResolver` 按名字给出（不设则 `dlsym(RTLD_DEFAULT)`）。`luaL_*` 用自带的 lauxlib，改名成 `emmy_luaL_*`，宿主裁没裁 lauxlib 都不影响 |

dynamic 模式的限制：

- 头文件和内部结构（`CallInfo` / `Proto`，取函数信息和遍历协程用）按编译时选的 Lua 版本，宿主必须是同一大版本、默认 `luaconf.h`。一份 so 只支持一个 Lua 大版本，不支持 LuaJIT。
- 只支持 GCC / Clang（自带 lauxlib.c 用了 `__typeof__`）。
- 缺核心要用的 `lua_*` 时 `Emmy_RegisterLuaVm` 返回 0，原因用 `Emmy_LastError()` 取。

## C ABI

见 [include/emmy/emmy_core.h](include/emmy/emmy_core.h)。典型用法：

```c
Emmy_SetLuaApiResolver(resolve, ud);          // dynamic 模式；source 模式可省
Emmy_SetProcessInfo(package, buildId);
Emmy_ListenAbstract("emmylua-<pid>", token, err, sizeof err);  // 或 Emmy_ListenTcp / Emmy_ConnectTcp
// 在 Lua 线程：
uint64_t vm = Emmy_RegisterLuaVm(L, "android-attach");
Emmy_WaitIDE(5000);                           // 可选：等 IDE 发 configurationDone（启动即停、首个断点不丢）
...
Emmy_UnregisterLuaVm(L);                      // lua_close 之前
```

其他工具要挂钩子时用 `Emmy_HookMux_Register` 共享，不要直接 `lua_sethook`（会把调试器的钩子顶掉）。

## 性能

`bench_hook` 量调试器对 Lua 执行速度的影响（Linux x64、Lua 5.4、取中位数，倍数相对不挂调试器）：

| 场景 | 循环 5M | 递归 fib(27) | 建表 300k | 目标 |
| --- | --- | --- | --- | --- |
| 注册了 VM、没连 IDE | 1.02x | 1.01x | 0.96x | 1.00x |
| 连上、没有断点 | 1.01x | 1.00x | 0.97x | ≤ 1.05x |
| 断点在别的文件 | 1.12x | 3.64x | 1.06x | ≤ 1.3x |
| 断点在正在跑的函数里（执行不到的行） | 12.0x | 3.28x | 1.09x | ≤ 2x |
| 参照：只挂一个空 CALL 钩子 | 1.06x | 2.08x | 0.97x | — |
| 参照：旧架构常开 LINE\|CALL\|RET 空钩子 | 5.28x | 4.40x | 1.32x | — |

- 连上但没有断点时完全不挂钩子。
- 有断点时只挂 CALL 钩子，按「函数的行范围是否含断点行」决定要不要对这个函数开行钩子。函数调用非常密集的代码（fib）有 CALL 钩子本身的开销（参照行就是下限的一半）。
- 断点所在函数自己的紧循环逃不掉 Lua 行钩子（每条指令都要判断），比旧架构的空钩子仍好，但达不到 2x；这一项的目标需要按函数粒度重新定。
- 条件断点单次判断约 0.07 µs（目标 ≤ 2 µs）。

## 还没做

- `exceptions` 能力（错误时暂停）；`setOptions.exceptions` 只接受 `none`。
- helper（`emmyHelper.queryVariable`）参与变量展示：脚本已能加载执行，还没接到 `variables`。
- 变量视图扩展字段（[docs/design/variable-view.md](../../docs/design/variable-view.md)）。
- Windows 附加注入用的 dynamic 模式（MSVC）。
