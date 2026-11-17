# 评估：必须做的、要修的、可优化的

日期：2026-11-17　依据：`dev` `80c8260`（= v2.4.0 出包内容）、`main` `d3e499d`、GitHub Actions 最近一次运行、UEKit `dev` `edbfb3d`（UELink 2.5.1）

上一份分析（[2026-11-gap-analysis.md](2026-11-gap-analysis.md)）的三批已全部进了 v2.4.0。这份只看「现在还差什么」，不改代码。标「待确认」的是读代码推断，没在真机上复现过。

---

## 0. 结论

功能面已经基本齐了，现在的短板是**可靠性和验证**：

1. **还没有一次完整的真机验收。** v2.3.4 的断点不触发没确认是否解决，Windows / Android 清单一项都没记录。
2. **CI 是红的，出包却照发。** `Native Core` 在 Windows 上 `session.profile_sampling` 失败，v2.4.0 就是带着这个红灯发出去的：出包流程不看测试结果。
3. **UELink 的快照点 / 采样钩子和调试器的钩子会互相抢（待确认）。** 面板里用了 `lua.snap` 或 `lua.profile` 时，UELink 每帧会把钩子重新挂成自己的，IDE 的断点和单步可能整段失效。这可能是「断点不触发」的另一个来源。
4. **安装说明会把人引到错的插件。** README 推荐「插件市场搜 EmmyLua」，但本插件没上架，搜到的是上游 EmmyLua；两者用同一个语言 ID `Lua`，同时装会冲突，而 `plugin.xml` 没声明不兼容。

| 优先级 | 事项 | 类型 | 工作量 |
| --- | --- | --- | --- |
| P0 | 真机验收（含 v2.3.4 断点、UELink 2.5.1 本地编译） | 必须做 | 用户实测 |
| P0 | 修 Windows 上 `profile_sampling` 失败，CI 回绿 | 要修 | S |
| P0 | 出包前必须跑测试，测试红不发版 | 要修 | S |
| P0 | UELink 钩子与调试器钩子互抢 | 要修（待确认） | S–M |
| P1 | README 安装方式 + `plugin.xml` 声明与其他 Lua 插件不兼容 | 要修 | S |
| P1 | 真 emmylua_ls 冒烟测试（UE 声明能跳转） | 必须做 | M |
| P1 | 发版后把 `main` 合回 `dev` | 要修 | S |
| P2 | 其余见第 3 节 | 可优化 | — |

正在别的线程处理的四件事列在第 4 节，不重复评估。

---

## 1. 必须要做的

### 1.1 真机验收（P0）

- **依据**：[acceptance-checklist.md](../acceptance-checklist.md) 文末「记录」表是空的；调试引擎线程还停在「等用户重新出包实测的控制台日志」。
- **要做**：
  1. 先用 v2.4.0 验 A1–A3：在 `ReceiveBeginPlay` 第一行下断点，附加后再开始 PIE。不停就把控制台里 5 秒后那条「执行过的 chunk」提示贴回来；连这条都没有，说明钩子没挂上 UnLua 的 Lua（同时排查 2.1 的 UELink 钩子问题：验收时**先不要在面板里开快照点 / 采样**）。
  2. 断点通了以后按 A → B → C → D 过完整清单，每次结果写进「记录」表。
  3. UELink 2.5.1 的 C++（UE 声明生成、EmmyLua 属性提供者重新注册）还没在本地编译过，一起编一次。
- **为什么是 P0**：后面所有功能都建立在「附加 + 断点」这条链路上，没验过之前再加功能，出了问题分不清是谁的。

### 1.2 用真 emmylua_ls 做冒烟测试（P1）

- **依据**：`src/test` 下只有 `UnrealIntelliSenseRootsTest`（目录探测）和 `LuaLibraryRootsTest`；`lsp/EmmyLuaWorkspaceConfig`（下发给服务器的配置）和 `UnrealDeclWatcher`（目录变化重启服务器）没有测试。CI 已经在 `verify.yml` 里下载了 emmylua_ls 二进制，但只用来打包。
- **要做**：用一个最小的 UE 目录布局（`Content/Script` + `Plugins/UnLua/Content/IntelliSense` + `Intermediate/UELink/LuaDecl`），起真 emmylua_ls，断言「`UE.AActor` 能跳到声明」「`require` 的模块能补全」。
- **为什么必须**：用户刚报的「外部库里看得见、emmylua_ls 不识别」正是这类问题，有这条测试就能在 CI 里拦下，后续升 emmylua_ls 版本也有保障。

### 1.3 出包流程补测试门槛（P0，见 2.2）

归到「要修」里写。

---

## 2. 需要修的问题

### 2.1 UELink 钩子会抢走调试器的钩子（P0，待确认）

- **位置**：UEKit `plugins/UELink/Source/UELinkCore/Private/AgentPanelLuaDebugOps.cpp`
  - `UpdateHook()`（约 140 行）：已经挂过钩子（`bHooked`）时直接 `lua_sethook(L, &HookFn, Need | S.PrevMask, S.PrevCount)`，不看当前钩子是不是自己的。
  - 每帧的定时器（约 625 行）：`NeedMask() != (lua_gethookmask(L) & ~S.PrevMask)` 就调 `UpdateHook(L)`。调试器换了钩子后掩码一定对不上，于是每帧都把钩子抢回去。
  - 同文件里 `HookTaken()` 和 `UpdateHook()` 的 else 分支注释都写着「别人（IDE 调试器）换了钩子，不去动它」，只有这条路径没遵守。
- **另一边**：调试器 `native/emmy_debugger_core/src/hook_mux.cpp` 的 `Apply()` 在每次事件里发现掩码不同也会 `lua_sethook` 抢回；`AdoptExistingHook()` 只在附加时收养一次已有钩子。
- **后果**：面板里开着快照点或采样时附加 IDE，两边每帧互相覆盖；UELink 抢到时 `HookFn` 只按它保存的旧 `PrevMask` 转发，调试器要的 LINE 事件可能收不到，**断点、单步时灵时不灵**。协程上的钩子 UELink 不管，所以协程里的断点反而可能正常，现象会很迷惑。
- **建议**：
  1. 马上能做（UELink 修订版）：定时器里先 `HookTaken(L)`，被别人换了就不再 `UpdateHook`，只把快照点 / 采样标成「被调试器接管」，`lua.snap` / `lua.profile` 返回里说明原因。
  2. 根治：原生核心导出一个注册钩子客户端的 C 接口（`HookMux::Register` 已经有，只差 `Emmy_` 导出），UELink 发现进程里有 emmy runtime 时（`UELinkEmmyValues.cpp` 已经能找到模块）改为通过它挂，两边共用一个钩子。设计文档里本来就写了「和 UELink `lua.profile` 共用钩子」，这一步没做完。
- **顺带**：`lua.exec` / `lua.reload` 执行期间会临时换成 `TimeoutHook`（`AgentPanelLua.cpp` 约 1203 行、`AgentPanelLuaDevOps.cpp` 约 577 / 825 行），执行完恢复原钩子。期间 IDE 改断点触发的 `Kick()` 会被恢复动作覆盖，新断点要等下一次改断点才生效。影响小，修 1 时一起处理：恢复前比较一下当前钩子还是不是 `TimeoutHook`。

### 2.2 CI 红着也出包（P0）

- **现象**：`Native Core` 在 `dev` `80c8260` 和 `main` `2ae6ad8` 上都失败，失败的是 `test (windows-latest, Lua 54, source)`：`session_test.cpp:1067 CHECK(hasAdd)`、`:1075 CHECK(foldedOk)`，即 Windows 上采样 500 毫秒没采到 `add` 函数。Linux 各版本都过。采样功能（`caa8af9`）进来之后才出现。
- **判断**：Windows 上测试宿主每个 tick 都 `print` 到控制台，控制台输出很慢，Lua 时间几乎都花在 `print` 里，`add` 太短采不到。大概率是**用例写法**的问题而不是采样本身坏了，但要在 Windows 上确认一次（例如去掉 `print` 或把采样间隔调小再跑）。
- **出包流程**：`auto-release.yml` 只编原生库和插件，不跑 `ctest`、不跑 Gradle `test`，也不等 `verify.yml` / `native-core.yml` 的结果，所以 v2.4.0 带着红灯发了。
- **建议**：
  1. 修用例（或修采样），让 `Native Core` 回绿。
  2. 出包工作流加一步：同一个提交的 `Verify Plugin`、`Native Core` 都成功才发 Release（用 `workflow_run` 触发，或在 release job 里直接跑一遍 `ctest` + `./gradlew test`）。

### 2.3 安装说明和插件冲突（P1）

- **README**：「方式一：通过 JetBrains 插件市场（推荐）… 搜索 "EmmyLua"」。本插件没上架（用户已定暂不上架），按这一步装上的是上游 EmmyLua。
- **冲突**：本插件保留了语言 ID `Lua`、文件类型名 `lua`（AGENTS.md 规定不改），上游 EmmyLua、EmmyLua2、SumnekoLua 等插件注册的是同一个语言 ID，同时启用会报重复注册或互相覆盖高亮 / 解析。`plugin.xml` 没有 `<incompatible-with>`。
- **建议**：README 删掉方式一（或改成「还没上架」）；`plugin.xml` 加 `<incompatible-with>` 列出常见的 Lua 插件 ID（上游 EmmyLua 是 `com.tang`，其余的 ID 要核对后再写），IDE 安装时会提示禁用另一个。

### 2.4 `dev` 落后 `main` 一个提交（P1）

- **现象**：出包流程在 `main` 上提交 `chore(release): 自动更新版本至 2.4.0`（`d3e499d`）改 `version.properties`，没合回 `dev`；`dev` 上还是 `LAST_VERSION=2.3.2`。
- **影响**：现在不大（出包读的是 `main`），但每次发版都会多一个分叉，`dev` 上看到的版本号是错的，以后谁在 `dev` 上改这个文件就会冲突。
- **建议**：每次合 `main` 出包后 `git merge origin/main` 回 `dev`；或者出包流程自己把这个提交也推到 `dev`。

---

## 3. 可优化的

按「对 UE + UnLua 日常使用的价值 ÷ 工作量」排序。

### 3.1 稳定性

| # | 事项 | 现状 / 依据 | 建议 | 工作量 |
| --- | --- | --- | --- | --- |
| 1 | 原生核心的崩溃兜底 | 核心只在遍历内部结构时用 SEH 兜底（`lua_internal.cpp`、`attach.cpp`）；没有「出过一次致命错误就自我禁用」 | 钩子入口出错后摘钩子、停会话，并在 IDE 提示；Windows 补一条「崩溃时怎么抓 dump」的说明（PDB 已随 Release 发） | M |
| 2 | UnLua 分支 ABI 自检 | 已经因 `lua_Debug.i_ci` 布局踩过一次 | 附加时用一个已知小函数验证 `lua_getinfo` / `lua_getlocal` 结果，不对就拒绝附加并说明 | M |
| 3 | emmylua_ls 进程异常 | 只靠 LSP4IJ 自动重启 | 连续崩溃时弹一条带「打开 LSP 控制台」的通知 | S |
| 4 | Windows 附加端到端 | `native-core.yml` 的 `windows-attach` 只编译不运行 | Windows runner 上起一个链接 Lua 的小宿主，跑 `emmy_tool` 注入 + 断点命中 | M |

### 3.2 性能

| # | 事项 | 现状 | 建议 | 工作量 |
| --- | --- | --- | --- | --- |
| 5 | 性能回退检测 | `bench` 只出报告不卡门槛 | 对已达标三项（没连 IDE、没断点、条件断点）卡「同一次运行里相对不挂调试器的倍数」，比基线退 10% 失败 | S |
| 6 | IDE 侧延迟 | 设计 §5.3 有目标表，没有自动测 | 在假 runtime 行为用例里加计时断言：命中到栈帧可用、1 万项 table 首页 | S |
| 7 | emmylua_ls 冷启动 | 目标「5 万行声明 < 10 秒」（`lsp-migration.md`），没有实测记录 | 用 UELink 生成的全量声明测一次记进文档；配合 1.2 的冒烟测试 | S |

### 3.3 功能和体验

| # | 事项 | 建议 | 工作量 |
| --- | --- | --- | --- |
| 8 | 两套采样 | UELink 有 `lua.profile`（面板用），调试器有自己的采样（IDE / MCP 用），钩子、数据、界面各一套 | 2.1 根治后让面板也走调试器的采样，或者明确分工写进两边文档 | M |
| 9 | 首次使用引导 | 打开 UE 项目时一次性提示：LSP4IJ、检测到的 UE 声明来源、是否开 UELink 后台生成、MCP 是否安装（等 MCP 迁移完成后再做） | S–M |
| 10 | 设置页 | 语言（语言和框架 → EmmyLua）和调试（工具 → EmmyLua 调试）分在两处，至少互相加链接 | S |
| 11 | 用户文档 | 只有 `mcp-debug-guide.md`（MCP 迁移后也要重写）；补「附加调试」「Android 真机附加」「UE 声明」三篇 | S–M |
| 12 | 依赖版本 | `lsp4ijVersion=0.21.0`、`emmyLuaLsVersion=0.25.1` 是固定的 | 每月看一次上游；升 emmylua_ls 时同时换 `resources/schemas/emmyrc.schema.json`，跑 1.2 的冒烟测试 | S |
| 13 | Agent 面板跟上 `luadecl.*` | `agent-plugins` 的 `ue/` 还没有对应工具 | IDE 设置页已能开关，低优先级 | S |

---

## 4. 进行中（别的线程在做，这里只登记）

| 事项 | 状态 |
| --- | --- |
| UE 声明和 UnLua 自带脚本（`Plugins/UnLua/Content/IntelliSense`、`Content/Script`）在外部库可见但 emmylua_ls 不识别、跳转失败 | 进行中。修完建议补 1.2 的冒烟测试防回退 |
| 调试工具迁到 IDEA 自带 MCP、去掉独立 MCP | 进行中。完成后 `AGENTS.md` 的「MCP 工具规范」整节（传输、安装、权限、端口）、`mcp-debug-guide.md`、`emmylua-debug-mcp.xml`、`modules/debugger-mcp` 的去留要一起改，旧工具名按规范保留一版报「改用哪个」 |
| 变量面板降噪（UnLua 类表内部字段、Object / UE 对象重复、元表收进内部节点） | 进行中 |
| v2.3.4 的断点不触发 | 待用户实测（见 1.1），注意 2.1 可能是另一个原因 |
| 真机验收 | 未开始（见 1.1） |

---

## 5. 建议顺序

1. 用户：v2.4.0 上先验断点（1.1 第 1 步），验的时候面板里别开快照点 / 采样。
2. 同时修 2.2（CI 回绿 + 出包门槛）、2.1 第 1 步（UELink 修订版）、2.3、2.4。这几项都小，可以一起进下一版。
3. 断点确认通了，再按清单完整验收；进行中的三件做完后一起出一版。
4. 再做 1.2 冒烟测试和第 3 节的稳定性几项。
