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

## 采样性能分析（profiler）

协议 v3 第 20 节的 `profile`（能力 `profiler`），实现在 `src/profiler.cpp`，每个 VM 一个 `Profiler`：

- `start` 经 HookMux 注册一个 COUNT 客户端（默认每 1000 条指令，`intervalInstructions` 100–10000000），`stop` 摘掉；不采样时不挂任何钩子。IDE 断开时自动停。
- 每次采样用 `lua_getstack` / `lua_getinfo("S")` 取当前协程的栈（最多 64 层，更深的根部截掉、以 `<截断>` 开头），C 函数和第一次见到的函数另取 `"n"` 当名字。不读 `lua_Debug` 里 Lua 私有的字段，dynamic 模式附加 UnLua 等改过的 Lua 也安全。
- 聚合成「函数（chunkName + 定义行，C 函数按名字）→ 自身 / 总样本」和「折叠栈 → 样本数」；每个 VM 不同栈最多 1 万条，之后新出现的合并成 `<其他>`，函数最多 2 万个。
- 暂停在断点 / 单步时到点的采样不计入（`pausedSkipped`）。多 VM 各自统计，`vmId = 0` 时 runtime 合并后返回。
- 样本按 Lua 指令数分布，不按墙钟时间：花在 C 函数（引擎接口）里的时间不计入任何样本。

开销（`bench_hook`，Linux x64，倍数相对不挂调试器；共享机器有抖动，看量级）：

| 场景 | 循环 5M | 递归 fib(27) | 建表 300k |
| --- | --- | --- | --- |
| Lua 5.4，采样（每 1000 条指令） | 2.63x | 2.55x | 1.18x |
| Lua 5.4，采样（每 10000 条指令） | 2.56x | 2.73x | 1.17x |
| Lua 5.3，采样（每 1000 条指令） | 2.40x | 2.81x | 1.01x |
| Lua 5.3，采样（每 10000 条指令） | 2.15x | 2.02x | 1.25x |

- 采样回调本身每个样本约 0.3–0.5 µs（5.4）/ 0.5–0.7 µs（5.3），按 1000 条指令一次算，只占执行时间的几个百分点。
- 大头是解释器：Lua 5.3 / 5.4 只要挂了 COUNT 钩子，每条指令都要进一次 `luaG_traceexec` 数指令，和间隔无关（参照行 `ref_count_hook`：只挂一个空 COUNT 钩子，和采样行基本一样）。所以加大间隔几乎不降开销，只减少样本；指令密集的纯 Lua 循环约慢 2–2.5 倍，调用 C / 建表多的代码影响小得多。各函数的相对占比不受这部分影响（每条指令加的开销一样）。
- 只在采样期间有这份开销，停止后钩子摘掉、回到「连上、没有断点」的 1.0x。

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

## 显示规则和 helper v2

- `setViewRules`（能力 `viewRules`，variable-view 11.1 / 13.4）：摘要模板（只 rawget）、`pin` 排最前并标 `pinned`、`hide`（含继承成员）、`classNameFields`、`builtinGlobals`。实现在 `view_rules.cpp`（解析）和 `rich_rules.cpp`（接入）。
- helper v2（11.2）：`loadHelpers` 的主模块返回 `emmy` 表时，`emmy.__dispatch` 放进 registry；宿主不认领的 table / userdata 交给 formatter，在新协程里带 `helperBudgetMs`（默认 20 ms）预算执行，出错退回默认显示并标 `helperError`。IDE 下发的脚本在插件资源 `debugger/emmy/v3/`。

## 出错即停（exceptions）

`setOptions.exceptions` = `none` / `uncaught` / `all`，暂停时 `reason = exception`，`exception = { message, traceback, uncaught }`，栈从第一个 Lua 帧算起（错误处理函数、`error` / `assert` 不显示），局部变量还能看。

- 「没被接住」只有宿主知道：宿主在 `lua_pcall` 的错误处理函数里调 `Emmy_NotifyLuaError`，或者直接用 `Emmy_LuaErrorHandler` 当错误处理函数；挂 `lua_pcall` 的注入方式用 `Emmy_PrepareHostPcall` / `Emmy_FinishHostPcall`（Windows 附加已接，Lua 自己的 `pcall` 和带续延的调用不插）。

协程的钩子：`lua_sethook` 按线程设置，`lua_newthread` 继承的是创建者当时的钩子。核心在需要时遍历 VM 里的协程补钩子；挂了 `lua_resume` 的注入方式另外在每次恢复协程前调 `Emmy_SyncThreadHook(co)`（Windows 附加已接），协程没有任何钩子而调试器需要时补上，遍历漏掉（例如布局校验没通过）也不会丢断点。
- `all` 时核心把全局 `pcall` / `xpcall` 换成用 `xpcall` 写的包装（5.2+ 仍可 yield），被接住的错误也会停；改回 `none` / `uncaught` 时还原。脚本里提前 `local pcall = pcall` 存下的引用不受影响。
