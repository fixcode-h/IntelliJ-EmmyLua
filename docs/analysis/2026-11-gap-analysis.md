# 现状分析：功能缺口、扩展方向与优化建议

日期：2026-11-16　依据：`dev` 分支 `59c79a5`、`docs/design/` 下的设计文档、UEKit `dev`（UELink 2.5.1）

这份文档只做分析和排序，不改代码。每条结论尽量注明依据（文件或提交）；标「待确认」的是推测，没有在真机或打出的包上核实。

---

## 0. 结论

重构的主体（新原生核心 + 协议 v3、IDE 引擎、Windows 附加、Android 真机附加、MCP、变量视图、出错即停、行尾变量值、悬停求值）都已经落在 `dev` 上，单测和端到端测试也接进了 CI。现在的主要风险不在「缺功能」，而在三件事：

1. **还没有真机验收过。** Windows 附加和 Android 真机附加都只在 CI 和主机端到端测试里跑过，断点不触发的问题还在等 v2.3.4 实测。在这之前加新功能，出了问题很难分清是新功能还是基础链路。
2. **语言支持迁到 LSP 后，日常编辑的手感有退化。** 回车自动补 `end`、块内自动缩进、`---` 注释续行、后缀模板、Surround With、生成注解的意图动作都没了。这些不需要语义分析，可以基于现有词法器补回来，不违反「不往 IDE 里加 PSI 级语义代码」的约定。
3. **IDE 版本覆盖窄。** 构建只有 2025.1 / 2025.2 两档（`build.gradle.kts` 的 `buildDataList`），`plugin.xml` 写着 `until-build="252.*"`。现在已经是 2026 年 11 月，2025.3、2026.1、2026.2 的用户很可能装不上（待确认打出的包里 `until-build` 的实际值）。

**建议的前五件事**（详见第 6 节）：

| 顺序 | 事项 | 工作量 |
| --- | --- | --- |
| 1 | 真机验收：断点不触发（v2.3.4）、Windows 附加、Android 附加，按清单逐项过一遍，结果写进验证文档 | M（主要是用户实测时间） |
| 2 | 放开 IDE 版本：加 2025.3 / 2026.x 构建和插件校验，确认 `until-build` | S–M |
| 3 | 补回基于词法器的编辑体验：回车补 `end` / `until`、块内缩进、`---` 续行、Surround With | M |
| 4 | 断点性能：决定是否给 UnLua 的 Lua 打可选补丁，或者把目标改成按函数粒度 | 决策 S；补丁 M |
| 5 | 清理过时文档和插件元数据（`plugin.xml` 的版本、更新说明、vendor 占位邮箱、旧架构文档） | S |

---

## 1. 现状速览

| 领域 | 现在有什么 | 依据 |
| --- | --- | --- |
| 语言支持 | emmylua_ls 0.25.1 + LSP4IJ 0.21.0：跳转、补全、引用、重命名、诊断、格式化、语义高亮、折叠、结构视图、调用 / 类型层次、参数提示、内联提示 | `emmylua-core.xml`、`docs/design/lsp-migration.md` §2.3 |
| IDE 侧编辑 | 手写词法器高亮、括号匹配、注释切换、引号配对、9 个实时模板、新建 Lua 文件、复制模块路径 / require 路径 | `src/main/java/.../editor`、`liveTemplates/lua.xml` |
| UE 声明 | 自动探测 UnLua IntelliSense 目录；UELink 2.5 后台增量生成（默认关闭），设置页一键开关，目录变化时重启 emmylua_ls | `project/UnrealIntelliSenseRoots.kt`、`UnrealDeclWatcher.kt`、UEKit `UELinkLuaDecl*.cpp` |
| 调试连接 | Windows 本机附加（x86 / x64，Lua 5.1–5.4 各一份核心）、Android 真机附加（arm64 / armv7，Lua 5.4）、手动地址 | `emmylua-debug.xml`、`auto-release.yml` |
| 调试能力 | 行断点、条件、命中次数、日志点、Probe、出错即停、单步 / 运行到行、协程列表、修改变量、悬停求值、行尾变量值、变量分组和显示规则、UE 对象（UELink 宿主值提供者） | `README.md`、`LuaXValues.kt`、`variable-view.md` |
| Agent | 内置 MCP（`lua_debug_session` / `breakpoints` / `inspect`），一键写入 5 种客户端配置，权限确认，调用记录页 | `AGENTS.md`、`docs/mcp-debug-guide.md` |
| 测试与 CI | 引擎行为用例（假 runtime）+ 真核心端到端、MCP 协议 / 工具单测、Android agent 主机测试、原生核心按 Lua 版本 × 模式矩阵、`bench_hook` | `.github/workflows/*.yml` |

---

## 2. 缺失和退化的功能

对比对象：迁移前的本插件（上游 IntelliJ-EmmyLua 的 PSI 实现 + 旧 EmmyLuaDebugger）、VS Code 版 EmmyLua（同样用 emmylua_ls，调试用 EmmyLuaDebugger）、LuaPanda、lua-language-server（sumneko）以及 UnLua 自带工具。

### 2.1 语言支持

迁移时有意去掉的（`lsp-migration.md` §2.3）和顺带丢掉的，按用户感受排序：

| 功能 | 旧版 | 现在 | 影响 | 能否补回 |
| --- | --- | --- | --- | --- |
| 回车自动补 `end` / `until`、`function ... end` 之间回车 | `LuaEnterAfterUnmatchedBraceHandler`、`LuaEnterBetweenRangeBlockHandler` | **没有** | 高：每天都碰到 | 能。词法器能识别 `function` / `then` / `do` / `repeat` 与 `end` / `until` 的配对，写 `EnterHandlerDelegate` 即可，不需要语法树 |
| 块内自动缩进（`then` / `do` 后回车缩进一级、输入 `end` 回退） | 自写 `FormattingModel` + `LuaTypedHandler` | **待确认**：扁平 PSI 下大概率只保留上一行缩进 | 高 | 能。`LineIndentProvider` 或 `TypedHandlerDelegate` 基于词法器；或确认 LSP4IJ 是否支持 emmylua_ls 的 `onTypeFormatting` |
| `---` 注释回车续行 | `LuaEnterInDocHandler` | 没有 | 中：写注解时常用 | 能，纯文本判断 |
| 后缀模板（`.if` `.nn` `.fori` `.forp` `.local` `.return` `.print` 等 13 个） | `LuaPostfixTemplateProvider` | 删除 | 中 | 部分能。表达式边界只靠词法器不精确，可只支持「标识符 / 成员链 / 调用」这类简单表达式 |
| Surround With（`if` / `do` / `function` / `--region`） | `LuaSurroundDescriptor`、`RegionSurrounder` | 删除 | 中 | 能。按选中行包裹，不需要语义 |
| 生成注解的意图动作（为函数生成 `---@param` / `---@return`、为字段生成 `---@field`、为变量加 `---@type`） | `CreateFunctionDocIntention` 等 7 个 | 删除 | 中：UnLua 项目写注解多 | 优先看 emmylua_ls 的 `codeAction` 是否已有；没有时可向上游提，或在 IDE 侧用 `documentSymbol` + 词法器生成函数注解骨架 |
| 提取方法、内联变量、反转布尔、计算常量等重构 | 意图动作 / 重构 | 删除 | 低 | 依赖 emmylua_ls 的 `codeAction`；不建议 IDE 侧自己做 |
| 高亮出口点（光标在 `return` 上高亮所有出口） | `LuaHighlightExitPointsHandler` | 没有 | 低 | 可用 `documentHighlight` 部分替代 |
| 彩虹变量 | `LuaRainbowVisitor` | 删除 | 低 | 可基于语义 token 做，优先级低 |
| 拼写检查词典 | `lua.dic` | 删除 | 低 | 资源文件补回即可 |
| 「外部库」节点里看到标准库和 UE 声明 | `LuaPredefinedLibraryProvider`、`UnrealIntelliSenseLibraryProvider` | 没有：库目录只交给 emmylua_ls，项目树里看不到 | 中：想浏览 UE 声明只能靠跳转 | 能。实现 `AdditionalLibraryRootsProvider` 只做展示，不建索引 |
| 运行 Lua 脚本的运行配置 | `LuaRunConfiguration` | 删除 | 低（UE 场景用不到） | 需要时再加，纯启动进程 |
| LuaCheck 外部工具 | 有 | 删除 | 低 | emmylua_ls 诊断覆盖大部分；有团队在用 LuaCheck 时可作为外部工具配置说明 |
| `.emmyrc.json` 的补全和校验 | 不适用（旧版不用这个文件） | 没有 JSON Schema 映射 | 中：用户手写配置容易拼错 | 能。emmylua-analyzer-rust 仓库带 `schema.json`，注册 `JsonSchemaProviderFactory` 即可 |

和 VS Code 版 EmmyLua 比：语义能力已经基本对齐（同一个服务器）。VS Code 版多出来的主要是两条命令：「插入调试器代码」（生成 `require("emmy_core").tcpListen(...)` 片段）和「打开 / 生成 `.emmyrc.json`」。后者本插件设置页有；前者依赖内嵌 runtime，见 2.2。

### 2.2 调试

| 功能 | 旧版 / 其他工具 | 现在 | 影响 | 说明 |
| --- | --- | --- | --- | --- |
| **LuaJIT** | 旧 EmmyLuaDebugger 支持；VS Code 版 EmmyLua 支持 | **不支持**：附加到 LuaJIT 进程时直接报「新调试核心暂不支持」 | 对 UnLua 项目无影响；对 Unity（xLua / toLua）、Cocos、自研引擎是硬缺口 | 依据 `native/shared/include/shared/attach_result.h`。新核心依赖 `CallInfo` / `Proto` 内部结构，LuaJIT 要单独一套 |
| **内嵌 runtime**（游戏里 `require("emmy_core")` 后 `tcpListen` / `tcpConnect`） | 旧版、VS Code 版的主要用法 | **没有 Lua 模块**：核心只导出 C ABI（`Emmy_ListenTcp` 等），没有 `luaopen_emmy_core` | 非 Windows 宿主（Linux 服务器、macOS 编辑器、自研引擎）没有接入方式；手动地址的运行配置连不到任何东西 | 设计 §7.5 列为可选。补一个薄的 Lua 绑定模块工作量小，但要同时出各平台 / 各 Lua 版本的包 |
| macOS / Linux 附加 | 旧版也没有 | 没有 | 中：Mac 上做 UE / 服务器 Lua 的人无法调试 | 设计 §11 建议先做 Linux（ptrace）；有了内嵌 runtime 后，非 Windows 可以先走内嵌 |
| iOS | LuaPanda 能做（纯 Lua，需要改包） | 不做 | 已确认不做 | 用户要求包内不加东西，不改包没有可靠方案 |
| Android 无未裁剪 so 时的特征码兜底 | — | 没做 | 中：构建机清了 `Intermediate` 就附加不了 | 设计 §7.2 第 2 点；`native/platforms/android/README.md` 已知限制 |
| 热重载（保存后推给游戏重新加载） | UnLua 自带 `UnLua.HotReload`；LuaPanda 有 | 没有 | 中：UE 里改 Lua 的迭代速度 | 设计 §11 列为中优先级。UE 侧可经 UELink 调 UnLua 的热重载，不一定要走调试连接 |
| 性能分析（采样 / 火焰图） | UnLua 生态有 LuaProfiler 类工具；UELink 有 `lua.profile` 钩子 | 没有 | 中 | 核心已有 `HookMux`，可以和 UELink 共用 |
| 内存快照与对比 | UELink 有 `lua.leaks`（UObject 方向） | 没有 | 低 | 设计 §11 低优先级 |
| 启动即停（stopOnEntry） | LuaPanda | Android「启动应用并附加」有；Windows 附加时游戏已在运行，不适用 | 低 | 内嵌 runtime 做出来后用 `Emmy_WaitIDE` 承接 |
| 监视表达式按项目持久化 | 设计 §2.5 计划新增 | 用平台默认行为 | 低 | — |
| Agent 修改变量、热重载 | — | MCP 不提供 | 低 | 按需加，按控制类权限处理 |

### 2.3 平台与分发

| 项 | 现状 | 影响 |
| --- | --- | --- |
| IDE 版本 | 只构建 2025.1（251）和 2025.2（252）；`plugin.xml` 是 `since-build="252" until-build="252.*"`，252 那档的 `untilBuild` 在 Gradle 里留空 | **待确认**打出的包能否装进 2025.3 / 2026.x。如果不能，新版 IDE 用户装不上，这是最直接的可用性问题 |
| 2025.3 起 IDEA 合并版 | 2025.3 起社区版和旗舰版合并，平台自带 LSP API 对免费用户也可用（`lsp-migration.md` §2.1 已记下要再评估） | 换成平台 LSP API 可以去掉 LSP4IJ 这个必需依赖，安装少一步；但 LSP4IJ 功能更全，需要逐项对比后再决定 |
| 插件元数据 | `plugin.xml` 的 `<version>1.0.0`、更新说明只有「1.0.0 新调试链路」、vendor 邮箱是 `fixcode@example.com`、描述中英混杂 | 插件市场 / 安装对话框里显示不对；vendor 和插件名待定 |
| 插件市场发布 | `build.gradle.kts` 配了 `publishPlugin`，但 `auto-release.yml` 只发 GitHub Release | 用户只能手动装 zip，也收不到 IDE 内的更新提示 |

---

## 3. 已知未决事项（汇总）

| 事项 | 状态 | 建议 |
| --- | --- | --- |
| 断点不触发 | v2.3.4 加了 chunk 名映射和「5 秒未命中报出执行过的 chunk」诊断，等用户实测 | 实测前不要再动断点匹配逻辑；实测结果直接决定下一步 |
| 断点性能两项未达标 | `bench_hook`：断点在别的文件时 fib 3.64x（目标 ≤ 1.3x）；断点在正在跑的函数里循环 12.0x（目标 ≤ 2x）。根因是 Lua 的 CALL / LINE 钩子本身的开销 | 见 5.1 |
| Windows / Android 真机验收 | 未做 | 第一优先级，见第 6 节 |
| iOS | 已确认不做 | — |
| vendor / 插件名 | 待定 | 发插件市场前要定 |
| UE 声明的 IDE 开关和刷新 | 正在做（另一线程） | — |
| UE C++（UELink 2.5.x） | 未在本地编译验证 | 和真机验收一起做 |
| Agent 面板跟上 `luadecl.*` | 未做（`agent-plugins` 的 `ue/`） | 低优先级，IDE 设置页已能开关 |
| 变量视图设计 | `variable-view.md` 仍标「草案，待评审」，但大部分已实现 | 评审后把状态改为「已实施」，第 13 节并入 `protocol-v3.md` |

---

## 4. 可扩展方向

按「对 UE + UnLua 用户的价值 ÷ 工作量」排序。

| 方向 | 价值 | 工作量 | 前置 | 说明 |
| --- | --- | --- | --- | --- |
| **UE 里一键热重载** | 高 | M | UELink | 保存 Lua 后经 UELink `/rpc` 让编辑器 / PIE 重载对应模块（UnLua 已有热重载实现）。不走调试连接，没附加也能用；MCP 可以给 Agent 一个 `reload` 动作 |
| **调试器和 UE 编辑器联动** | 高 | M | UELink | 附加列表直接列出带 UELink 名片的编辑器 / PIE 实例（不用再从进程列表找）；暂停时在 UE 里高亮当前 Actor；从变量树里的 UObject 跳到 UE 资产 |
| **采样性能分析** | 高 | L | 核心 `HookMux` | runtime 用 COUNT 钩子采样调用栈，IDE 显示火焰图 / 热点函数；MCP 给 Agent 一个「采样 N 秒，返回最热的 20 个函数」。和 UELink `lua.profile` 共用钩子，不打架 |
| **内嵌 runtime 的 Lua 模块** | 中（扩大适用范围） | M | — | `luaopen_emmy_core` 提供 `tcpListen` / `tcpConnect` / `waitIDE` / `breakHere`；补「插入调试器代码」动作。补上 Linux / macOS / 自研引擎的接入 |
| **DAP 适配** | 中 | M | 引擎已与 IntelliJ 解耦 | 给 VS Code / Neovim 用同一套核心和真机附加能力；也能让 VS Code 版 EmmyLua 用户迁过来试 |
| **条件断点表达式补全和校验** | 中 | S | emmylua_ls | 条件 / 日志点输入框接上语言服务器的补全；下断点时让 runtime 预编译一次，语法错误当场报出来，而不是命中时才失败 |
| **Lua 错误聚合视图** | 中 | S–M | 出错即停已有 | 把运行中所有 Lua 错误（含被 pcall 吞掉的）按位置去重计数，不停也能看；MCP 的 `output` 已有原始数据 |
| **内存快照对比** | 低 | M | — | 遍历 registry 统计 table 数量和大小，两次对比找增长最多的类型 |
| **Linux 附加（ptrace）** | 低 | L | — | 主要给服务器端 Lua；有内嵌模块后需求会降低 |
| **LuaJIT 支持** | 看用户群 | L | — | 只在要覆盖 Unity / Cocos 时值得做 |

---

## 5. 可优化项

### 5.1 性能

| 项 | 现状 | 建议 | 工作量 |
| --- | --- | --- | --- |
| 断点所在函数里的紧循环（12x） | 每条指令进 C 的行钩子；`2c41152` 已省掉一次查表（约快 15%） | 三条路，按代价排序：① 接受现实，把目标改为「按函数粒度」，在 README 写清楚「断点所在函数会变慢，别的函数不受影响」；② 可选补丁：给 UnLua 自带的 Lua 源码加一个按 `Proto` 的断点行位图，在 `luaG_traceexec` 里先查位图、没有断点的行不回调 C（预计能接近 2x），以「可选、不改项目代码就不生效」的方式提供，因为 UEKit 规定不要求改项目代码；③ 字节码打桩（把断点行的指令换成陷阱指令），侵入性和风险最大，不建议 | ① S；② M；③ L |
| 断点在别的文件时调用密集代码（fib 3.64x） | CALL 钩子本身的开销，参照「只挂空 CALL 钩子」是 2.08x | 同上的补丁可以把 CALL 钩子也去掉（直接在 `luaD_precall` 里查 `Proto` 标记）；不打补丁时，可试「只在含断点文件的函数首次调用时打标记，之后靠 `Proto` 指针缓存」进一步减少 C 回调里的工作，但回调次数降不下来 | 同上 |
| 性能回退检测 | `bench_hook` 在 CI 只出报告不卡门槛（共享机器抖动大），`--check` 只对照目标、不对照上一版 | 设计里的「比基线退步超过 10% 失败」还没落地：把基线倍数写进仓库，对已达标的三项（没连 IDE、没断点、条件断点）卡相对倍数，倍数本身是同一次运行里和「不挂调试器」比，受机器抖动影响小 | S |
| IDE 侧延迟目标 | 设计 §5.3 有表，没有自动化测量 | 在假 runtime 行为用例里加计时断言（命中到栈帧可用、展开 1 万项 table 首页） | S |
| emmylua_ls 冷启动 | 目标 5 万行声明 < 10 秒，未见实测记录 | 用 UELink 生成的完整声明测一次，记进 `lsp-migration.md`；UE 声明量大时考虑在 `.emmyrc.json` 里给库目录关诊断（已关）和限制补全条数 | S |

### 5.2 稳定性

| 项 | 建议 | 工作量 |
| --- | --- | --- |
| 真机验收清单 | 把 `native/platforms/android/README.md` 的 7 条手动步骤和 Windows 附加的对应步骤合成一份验收清单（含 UnLua 定制分支、PIE 重启、热重载后、断线重连），每次出包前过一遍，结果写进 `docs/` | S（写清单） |
| 崩溃兜底 | 附加是在用户的游戏进程里跑，任何崩溃都是丢数据。建议：核心所有入口加「出过一次致命错误就自我禁用」的开关；Windows 已带 PDB，补一条「崩溃时怎么收集 dump 发给我们」的说明 | M |
| UnLua 定制分支的 ABI 差异 | 已经因为 `lua_Debug.i_ci` 布局踩过一次（改为只用 `lua_getstack` / `getinfo`）。建议附加时做一次「ABI 自检」：用已知小函数验证 `getinfo` 结果，不对就拒绝附加并说明，而不是边跑边崩 | M |
| 断点匹配诊断 | v2.3.4 已在控制台打印 chunk 名。建议把「断点没命中」做成断点图标上的提示（灰色 + 悬停说明「没有加载过匹配的 chunk」），而不是只在控制台 | S–M |
| emmylua_ls 进程异常 | 依赖 LSP4IJ 的重启；建议在服务器连续崩溃时给一条带「打开 LSP 控制台」按钮的通知 | S |

### 5.3 易用性

| 项 | 建议 | 工作量 |
| --- | --- | --- |
| 首次使用引导 | 打开 UE 项目时一次性提示：装好 LSP4IJ、检测到的 UE 声明来源、是否开启 UELink 后台生成、MCP 是否安装。现在这些散在三处设置页和两条通知里 | S–M |
| 附加列表 | 进程列表优先显示带 UELink 名片的编辑器 / PIE / 游戏实例，标明 Lua 版本和是否已加载核心 | S |
| 断点不触发的自助排查 | 一个「诊断」动作：列出本次会话加载过的 chunk、断点文件和它们的映射关系、建议的路径映射 | S |
| 设置页整合 | 语言（「语言和框架 → EmmyLua」）和调试（「工具 → EmmyLua 调试」）分在两处；至少互相加链接 | S |
| 中英文 | 文案包已有 `LuaDebugBundle*.properties`，语言侧和 `plugin.xml` 描述还是中英混写；面向插件市场时需要英文描述 | S |

### 5.4 测试与 CI

| 项 | 现状 | 建议 | 工作量 |
| --- | --- | --- | --- |
| IDE 版本矩阵 | `verify.yml` 只跑 251 / 252 | 加 253 / 261 / 262 的 `verifyPlugin`（插件校验器能提前发现 API 不兼容） | S |
| LSP 集成 | `UnrealIntelliSenseRootsTest` 测了目录探测；`EmmyLuaWorkspaceConfig` 下发内容、`UnrealDeclWatcher` 重启逻辑没有测试 | 补单测；再加一个用真 emmylua_ls 跑「打开 UE 布局示例项目 → 跳转 / 补全可用」的冒烟测试（CI 已经下载二进制） | M |
| Windows 附加端到端 | 只编译不运行 | Windows runner 上起一个链接 Lua 的小宿主，跑 `emmy_tool` 附加 + 断点命中，覆盖注入链路 | M |
| 编辑器小功能 | 补回第 2.1 节的回车 / 缩进处理后，用 `BasePlatformTestCase` 写打字测试 | S |
| 文档检查 | 无 | CI 里检查 `docs/` 的相对链接有效，防止文档随重构失效 | S |

### 5.5 文档和仓库卫生

| 项 | 依据 | 建议 |
| --- | --- | --- |
| 过时文档 | `docs/emmy-native-resource-build.md` 还在讲 `src/main/resources/debugger/emmy` 和 `emmy_core.dll` / `emmy_hook.dll`；`docs/native-tcp-firewall-diagnosis-20260910.md` 是旧架构的诊断记录 | 前者按新出包流程重写或删除；后者移到 `docs/design/archive/` |
| 过时脚本 | `tools/collect-emmy-native-resources.ps1`、`verify-emmy-native-resources.ps1`、`test-native-tcp-with-firewall.ps1` 针对旧资源布局 | 确认无人使用后删除 |
| 旧素材 | 根目录 `design/emmy.ai`、`snapshot/*.gif`（旧 PSI 功能演示图） | 设计 §2.3 第 14 条已建议清理；README 若不引用就删 |
| README | 「支持的 IDE 版本」列了 Rider、CLion 等，但调试只支持 Windows；没有写已知限制（LuaJIT、iOS、断点所在函数变慢） | 加一节「限制」，和第 2 节对齐 |
| 设计文档状态 | `variable-view.md` 标「草案」、`lsp-migration.md` 标「实施中」，实际已基本完成 | 更新状态行，链接到实现提交 |
| 用户文档 | 只有 `mcp-debug-guide.md` 一篇使用说明 | 补「附加调试」「Android 真机附加」「UE 声明」三篇面向用户的说明（现在的内容散在 README 和设计文档里） |

---

## 6. 建议路线

工作量：S ≤ 1 天，M 2–5 天，L 1–2 周以上。

### 第一批：先让现有功能可靠（建议立刻做）

| # | 事项 | 工作量 | 依赖 |
| --- | --- | --- | --- |
| 1 | 用户实测 v2.3.4 断点；Windows / Android 真机按验收清单过一遍，结果写进 `docs/` | M | 用户时间 |
| 2 | 确认并放开 IDE 版本上限；CI 加新版本的插件校验 | S–M | — |
| 3 | 修插件元数据（版本、更新说明、vendor、描述），决定插件名 | S | 用户定名 |
| 4 | 清理过时文档 / 脚本 / 素材，README 加「限制」 | S | — |
| 5 | 断点性能：决定走「改目标」还是「可选补丁」 | S（决策） | 用户决定 |

### 第二批：补回日常编辑体验

| # | 事项 | 工作量 |
| --- | --- | --- |
| 6 | 回车补 `end` / `until`、块内缩进、`---` 续行 | M |
| 7 | `.emmyrc.json` 的 JSON Schema | S |
| 8 | Surround With、简单后缀模板 | M |
| 9 | 「外部库」节点显示 UE 声明和标准库 | S |
| 10 | 确认 emmylua_ls 的 `codeAction` 能否生成函数注解；不能就 IDE 侧补骨架生成 | S–M |

### 第三批：UE 场景的新能力

| # | 事项 | 工作量 |
| --- | --- | --- |
| 11 | UE 一键热重载（经 UELink），MCP 加 `reload` | M |
| 12 | 附加列表接 UELink 名片；断点诊断动作 | S–M |
| 13 | 采样性能分析（IDE 火焰图 + MCP） | L |
| 14 | 断点性能可选补丁（如果第 5 项选了它） | M |
| 15 | Android 特征码兜底 | M–L |

### 之后再看

内嵌 runtime 的 Lua 模块、DAP 适配、Linux 附加、LuaJIT、内存快照、2025.3+ 换平台 LSP API。这些都扩大适用范围，但对现在的 UE + UnLua 用户价值不如上面几批，等有具体需求再排。

---

## 7. 需要你决定的事

1. **断点性能**：接受「断点所在函数会变慢」并改目标，还是做一个给 UnLua 自带 Lua 打的可选补丁（需要项目重编 UnLua，和 UEKit「不要求改项目代码」的规矩冲突，只能做成可选）。
2. **插件名和 vendor**：发插件市场前要定；发不发插件市场也一起定。
3. **IDE 版本策略**：是否去掉 2025.1（设计 §11 也提过），以及上限放到哪。
4. **非 UE 用户要不要管**：如果只服务 UE + UnLua，LuaJIT、内嵌模块、Linux 附加都可以不做；如果还要给 Unity / 自研引擎用，内嵌模块要提前。
