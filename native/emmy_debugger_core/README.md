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
| `dynamic`（Android 默认） | 附加到别人的进程（Android agent、Windows 的 `emmy_attach.dll`） | 不带 Lua 实现；所有 `lua_*` 经函数指针调用宿主那份 Lua，指针由 `Emmy_SetLuaApiResolver` 按名字给出（不设则 `dlsym(RTLD_DEFAULT)`）。`luaL_*` 用自带的 lauxlib，改名成 `emmy_luaL_*`，宿主裁没裁 lauxlib 都不影响 |

dynamic 模式的限制：

- 头文件和内部结构（`CallInfo` / `Proto`，取函数信息和遍历协程用）按编译时选的 Lua 版本，宿主必须是同一大版本、默认 `luaconf.h`。一份 so 只支持一个 Lua 大版本，不支持 LuaJIT。
- 函数分必需和可选两档（`src/lua_dyn.cpp`）：缺必需的 `Emmy_RegisterLuaVm` 返回 0，原因用 `Emmy_LastError()` 取；
  缺可选的（`lua_setlocal`、`lua_concat`、`lua_pushvfstring` 等常被 `--gc-sections` 裁掉的）换成替代实现，只降级对应功能，IDE 连上时用 output warn 说明。
- `-DEMMY_CORE_BUILD_SHARED=ON` 另编 `emmy_debugger_core.dll/.so`（导出 C ABI），Windows 附加按 Lua 版本各编一份放在 `lua5X/` 下。

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

引擎对象的显示由宿主值提供者 v2 提供（[include/emmy/emmy_host_value.h](include/emmy/emmy_host_value.h)，`Emmy_RegisterHostValueProviderV2`，约定见 variable-view.md 13.6）：
被认领的 userdata 的摘要和子项全部来自宿主，被认领的表在 Lua 字段之后加一个「UE 对象」分组；`valid=false` 显示 `<已失效>`，不再调 `__tostring`。

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

## 变量视图（richVariables）

hello 的 `caps` 里**明确写了** `richVariables` 才开（不写 caps 的老客户端拿到的还是 v3.0 的形状）。`variables` 收 `view`（`auto` / `raw`）和 `maxValueLength`（16–1024，默认 256），`setOptions.variableView` 收 13.5 的各项。实现在 `src/rich_view.cpp`：

- 每个值给 `kind`、`objectId`、字符串的 `length`、函数和协程的 `location`；table 按 4.1 分 `array` / `record` / `object` / `class` / `module`，类名按 6.3 推断（`__name` → `__cname` 等字段 → `package.loaded` 反查，每次暂停建一次反查表、按元表缓存）。
- 第一次展开 table 时建键快照（数组段 + 按 5.1 智能排序的字段段），之后每页只 `rawget` 这一页的值；键超过 `maxSortKeys` 不排序并回 `sorted: false`。`filter=indexed` 按下标区间分页。
- 首页回 `groups`：方法、`__index` 链每层的「继承自 X」（被更近一层覆盖的标 `shadowed`）、「`__index` 是函数」、「元表 X」（元方法在前、标 `metamethod`）、全局作用域的「内置」、局部变量的「临时值」（`showTemporaries`）、宿主认领的表的「UE 对象」。
- 局部变量里被遮蔽的同名外层变量也列出（`attrs: ["shadowed"]`），upvalue 不列 `_ENV`。
- 一页估算超过 256 KiB 就少给几项并回 `truncated: true`。
- `expand`（10.4）：最多 32 条名字路径、深度 8，命中的节点（含分组）把首页子项内联到 `children`，合计超过 256 KiB 停止并标 `truncated`。
- 协程值可以展开：`status` 和「调用栈」分组，每帧再展开是那一帧的局部变量（不给 `evaluateName`）。`stackTrace` 也认 `threadId`。
- 除了 `__tostring`（`toString`：默认只对 table，`all` 连 userdata 一起，`never` 不调），全程只用 rawget / lua_next / rawlen / lua_getinfo / lua_getlocal / lua_getupvalue，不调元方法。`__tostring` 在新协程里跑，5 ms 指令预算，期间不命中断点；出错的标 `helperError`，同一个元表连续出错 3 次后本次暂停不再调。

## 出错即停（exceptions）

`setOptions.exceptions` = `none` / `uncaught` / `all`，暂停时 `reason = exception`，`exception = { message, traceback, uncaught }`，栈从第一个 Lua 帧算起（错误处理函数、`error` / `assert` 不显示），局部变量还能看。

- 「没被接住」只有宿主知道：宿主在 `lua_pcall` 的错误处理函数里调 `Emmy_NotifyLuaError`，或者直接用 `Emmy_LuaErrorHandler` 当错误处理函数；挂 `lua_pcall` 的注入方式用 `Emmy_PrepareHostPcall` / `Emmy_FinishHostPcall`（Windows 附加已接，Lua 自己的 `pcall` 和带续延的调用不插）。
- `all` 时核心把全局 `pcall` / `xpcall` 换成用 `xpcall` 写的包装（5.2+ 仍可 yield），被接住的错误也会停；改回 `none` / `uncaught` 时还原。脚本里提前 `local pcall = pcall` 存下的引用不受影响。

## 还没做

- helper（`emmyHelper.queryVariable`）参与变量展示：脚本已能加载执行，还没接到 `variables`。
- 变量视图（[docs/design/variable-view.md](../../docs/design/variable-view.md)）还差：`variables.expand`（10.4 一次往返恢复展开）、`setViewRules` / 能力 `viewRules`（11.1）、helper v2（11.2）。这两项由变量展示线程做。
