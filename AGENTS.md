# IntelliJ-EmmyLua · 开发约定

给在这个仓库里工作的 AI 编码助手（Codex、Claude Code 等）和开发者看。`CLAUDE.md` 只引用本文件，规则只改这一份。

## 语言支持（LSP）

设计见 `docs/design/lsp-migration.md`。

- 语义（跳转、补全、引用、诊断、格式化、语义高亮……）全部由 emmylua_ls 提供，经 LSP4IJ 接入。**不要再往 IDE 里加 PSI 级的语义代码**；缺什么先看 emmylua_ls 是否支持、`.emmyrc.json` 怎么配。
- IDE 侧只有 `lexer/LuaLexer.kt`（手写词法器）+ 扁平 PSI，用于高亮、括号、注释、调试器取名字，以及「结构」窗口的大纲（`structure/LuaOutline`，只认顶层声明，不做语义；不用 emmylua_ls 的 documentSymbol，原因见设计文档）。
- 插件给 emmylua_ls 的配置只通过 `lsp/EmmyLuaWorkspaceConfig`（`workspace/configuration` 的 `emmylua` 段）下发；数组与用户 `.emmyrc.json` 取并集，标量会覆盖，所以用户有配置文件时不下发标量。
- 格式化：emmylua_ls 内置的 `emmylua_formatter` 只读项目里的 `.luafmt.toml` / `luafmt.toml`（不读 `.editorconfig`、`.emmyrc.json`），缩进由 IDE 随请求发送。不要恢复 IDE 侧的 FormattingModel；插件只给「新建 .luafmt.toml」模板（`project/LuaFormatterConfig`）和 `schemas/luafmt.schema.json`，升级 emmylua_ls 时对着上游 `emmylua_formatter/src/config/mod.rs` 核对字段。
- 版本：`gradle.properties` 的 `lsp4ijVersion`、`emmyLuaLsVersion`。出包用 `.github/scripts/fetch-emmylua-ls.sh` 下载各平台二进制到 `build/emmylua-ls/`，Gradle 放进插件的 `server/<平台>/`。
- 包名 `com.fixcode.intellij.lua`。新文件不加文件头；从上游 EmmyLua 派生、仍保留上游代码的文件保留原 Apache 版权头。持久化名字（`@State` 名、存储文件、插件 ID、语言 ID `Lua`、文件类型名 `lua`、运行配置和断点类型 ID）不改。

## MCP 工具规范

插件把 Lua 调试工具注册到 **IDE 自带的 MCP 服务器**（2025.2 起内置的 MCP Server 插件，`com.intellij.mcpServer`），让 Agent 调试 Lua；工具逻辑在 `modules/debugger-mcp`，主插件只做适配。工具的形状**照 UEToVS 的 MCP**（`fixcode-h/UEToVS` 的 `docs/design/MCP.md`、`docs/design/VS-调试控制.md`、`src/UEToVS.Mcp/Tools/`），同一个 Agent 同时挂着 UEToVS 和 EmmyLua 时用法一致。加工具、改参数或返回前先读完本节。

### 命名

- 工具名 `lua_debug_<领域>`，snake_case。按领域合并成少数几个工具，用 `action` 区分动作（对应 UEToVS 的 `debug_session` / `debug_breakpoints` / `debug_inspect`），不为每个动作单开一个工具：工具多了 Agent 选不准，延迟加载工具说明时也更贵。
- 现有三个：
  - `lua_debug_session`：`status` / `configs`（运行配置）/ `launch`（按运行配置以 Debug 启动；IDE 自带的 `execute_run_configuration` 只能 Run）/ `targets` / `attach` / `stop` / `restart` / `pause` / `continue` / `step` / `wait` / `reload`（经 UELink 热重载 UE 里的 Lua，不需要会话）/ `help`（完整用法）
  - `lua_debug_breakpoints`：`list` / `add`（`file` + `line`，或 `function` 按函数名下断点，二选一）/ `remove` / `enable` / `clear`
  - `lua_debug_inspect`：`stack` / `vms` / `locals` / `eval` / `set`（改变量，按控制处理）/ `output` / `probes` / `profile` / `errors`（按位置聚合的 Lua 错误，含被 pcall 接住的）
- 代码查找类的工具不是调试工具，不叫 `lua_debug_*`，叫 `lua_code`：`search`（按名字找声明）/ `files`（按文件名或 require 路径找 .lua）/ `outline`（文件大纲）/ `references`（查引用）。它替代 UEToVS 删掉的 `search_lua` / `search_lua_files` / `get_lua_outline`，参数和返回照它们的形状（字段名按本规范）。逻辑在 `modules/debugger-mcp` 的 `code/` 包（`LuaCodeTools`，经 `LuaCodeBackend` 取数据），IDE 侧 `lsp/EmmyLuaCodeBackend` 只调 emmylua_ls（documentSymbol、workspace/symbol、references）和 IDE 的文件名索引，**不做语义分析**；只读，不弹确认。emmylua_ls 的 documentSymbol 不列表外赋值（`M.Foo = function`、`M.x = 1`），被 `.emmyrc.json` 排除、太大没加载、没索引完的文件也不给结果，所以 `search` / `outline` 再按文本认行首的 `function a.b:c(`、`a.b = function(`、`a.b = …`、`local x = …` 补上（`LuaSymbols.textDeclarations`，只认这几种写法）；emmylua_ls 用不了时 `search` 整份按文本查并在 `note` 里说明。
- `lua_` 前缀是为了和 UEToVS 的 `debug_*`、面板的 `ue_*` 不撞名。工具名一旦发布就不改：Agent 和用户的提示词里写死了它。改名或删掉时，旧名字保留一版，调用时返回「改用哪个」的错误。
- IDE 的 MCP 服务器里所有插件的工具在同一个列表，所以前缀必须带 `lua_`；设置页里我们的类别是 `LuaDebug`，工具全名 `com.fixcode.intellij.lua.debug.mcp.LuaDebugToolset.<工具名>`（用户在「公开的工具」里的开关按全名记，不改）。

### 参数

- 参数名 snake_case（`session_id`、`wait_ms`、`timeout_ms`、`hit_count`、`auto_continue`、`max_chars`）。
- 每个工具的 inputSchema 写 `additionalProperties: false`（IDE 的 MCP 服务器生成 schema 时只取 properties 和 required，所以未知参数靠下一条拒绝），`action` 必填并给出 `enum`；每个参数的 description 用英文写清楚哪些 action 用它、默认值是多少（例：`"step: over (default) / into / out / toLine"`）。
- **未知参数直接报错**并列出合法的名字（`JsonArgs(raw, allowed)`）：拼错的过滤条件被悄悄忽略比报错更糟。例外：IDE 自带工具通用的 `projectPath`，所有工具经 `McpTool.hiddenArgs` 接受（模块里的 schema 不写它，保持 snake_case）；新版 IDE 的 MCP 服务器在调用工具**之前**就按它（或请求头 `IJ_MCP_SERVER_PROJECT_PATH`、客户端 roots）定项目，定不了直接报「Unable to determine the target project」，工具跑不到，所以 `IdeMcpCompat.withProjectPath` 把它补进给 IDE 的 schema。Agent 没传而 IDE 定了项目时，`LuaMcpToolsProvider` 用 IDE 定的项目目录补上，`lua_code` 按它选项目。类型宽松：`"20"` 当整数、`"a, b"` 当字符串数组；超范围报错并写出范围。
- 行号从 1 开始；帧号 `frame` 从 0（栈顶）开始。
- 列表分页用 `limit` / `offset`（默认 30–50，最大 200）；日志类增量读取用 `cursor`，值是上次返回的 `next`。
- 会阻塞等待的动作给上限：`wait` 的 `timeout_ms` ≤ 120000（默认 30000），`step` 的 `wait_ms` 默认 10000、`pause` 默认 5000、`continue` 默认 0。
- 省略参数时按最可能的意思补：只有一个会话时 `session_id` 可省，多个会话但只有一个停着时用停着的那个；`vm` 省略时用停着的 VM。

### 返回

- 结果是**一个 text 内容块，内容是紧凑 JSON**（不缩进、不另写一份自然语言），字段名 camelCase。
- **默认值字段省略**：`false`、`null`、空数组、空字符串都不写，省 token（例：断点只有被禁用时才有 `disabled: true`，只有 Agent 下的才有 `byAgent: true`）。
- 列表用统一信封：`{count, offset, total?, hasMore, nextOffset?, truncated?, items}`；增量类：`{count, next, items}`。
- 值节点 `{n, t, v, c?, more?, ref?, err?}`：名字、类型、显示值、已展开的子项、没列出的子项数、继续展开用的引用、取值出错。
- 预算：默认约 4000 字，读结构的（栈、变量树）最多 12000 字；超出时截断、标 `truncated` 并给出 `nextOffset` / `next`，至少返回一项保证 Agent 能往下翻。日志类保留最新的。
- 会停下的动作（`pause` / `step` / `wait`）返回同一种事件结构：`event` = `break` / `probe` / `resumed` / `exited` / `timeout` / `idle` / `running`；`break` 时带 `location`、`source`（当前行 ±2）、8 帧 `stack` 和栈顶 `locals`，Agent 一般不用再调一次 `stack`。已经停着且这个客户端没看过的暂停，`wait` 马上返回。

- **等到结果再返回**（面板 ADR 0067 第 5 节）：emmylua_ls 在启动、在建索引时 `lua_code` 自己等（`LuaCodeTools.Timeouts.indexWaitMillis`，默认 60 秒）再返回，不让 Agent 隔几秒重试；一直不给结果的文件（被 `.emmyrc.json` 排除、不在工作区）连着几次没进展就认定不收，记住后跳过，只在没命中时在 `note` 里提一句。

### 错误

- 出错返回 `isError: true` + `{"error": "<code>", "message": "<中文说明>"}`。`message` 原样给用户和 Agent 看，要写清楚**怎么办**（例：「游戏已继续运行，引用失效：先 lua_debug_session action=wait 或 lua_debug_inspect action=stack 取新的。」）。
- 错误码（snake_case，加新的要在这里登记）：`invalid_argument`、`unknown_tool`、`engine_unavailable`、`no_session`、`no_vm`、`not_paused`、`stale_reference`、`permission_denied`、`eval_forbidden`、`not_supported`、`timeout`、`attach_failed`、`target_not_found`、`indexing`（emmylua_ls 正在启动或建索引，稍后重试；`lua_code` 用）、`cancelled`、`internal_error`。
- 参数错和权限拒绝是工具错误（`isError`），不是 JSON-RPC 协议错误；协议层的错误由 IDE 的 MCP 服务器处理。

### 说明文字

- 每个工具的 description 用 `ToolGuide` 的固定结构（英文，给模型看）：`What` + `USE FIRST WHEN:` + `INSTEAD OF:` + `NOT FOR:` + 可选 `Results:`，总长不超过 1200 字。`UseWhen` 写具体场景和典型调用顺序，`InsteadOf` 写它比通用办法（加 print、读代码猜）好在哪，`NotFor` 写不能做什么、该用什么。
- `title` 是英文显示名，`_meta.titles` 带 `{"zh", "en"}`，客户端按自己的界面语言选。
- `annotations`：只读工具 `readOnlyHint: true`；会结束进程或丢数据的 `destructiveHint: true`。
- IDE 的 MCP 服务器没有给单个插件的 `instructions` 和资源：调试循环写进 `lua_debug_session` 的说明（典型调用顺序），完整用法放 `EmmyLuaDebugMcp.GUIDE`（含 `lua_code` 一节），由 `lua_debug_session action=help` 返回。
- 单测会检查：三段结构齐全、长度上限、参数名 snake_case（`LuaDebugToolsTest`、`LuaCodeToolsTest`）、`help` 能返回用法（`McpDispatcherTest`）。

### 权限

- 只读（`status`、`list`、`stack`、`locals`、`output`……，以及整个 `lua_code`）默认允许。
- 控制（断点增删改、继续、单步、暂停、结束会话、UE 热重载 `reload`）：每个调试会话**第一次**控制时在 IDE 里弹通知「允许一次 / 本次会话总是允许 / 拒绝」；拒绝或超时返回 `permission_denied`，说明里让 Agent 去问用户，不要重试。「撤销 Agent 控制」清掉所有授权。
- `attach`、`launch` 每次都确认；`restart` 按控制处理。
- `profile`（采样性能分析）按只读处理、不弹确认：它不暂停、不改变量、不改执行路径，和 `output` / `probes` 一样只观察；代价是采样期间 Lua 变慢（约 2 倍），所以限时（`duration_ms` ≤ 60000）并由 runtime 到点自动停，IDE 断开也会停。
- `eval` 默认 `mode=value_path`（只读变量路径）；`mode=lua` 执行任意 Lua，设置里「允许 Agent 执行任意 Lua 表达式」默认开启（2.5.2 起，用户 2026-11-17 定；用户可关），按控制处理（每个会话第一次弹确认）。runtime 侧的 `allowLuaEval` 也由引擎按设置下发，作为第二道保险。
- 监听地址、来源校验、客户端连接由 IDE 的 MCP 服务器负责，插件不碰。

### 传输和接入（和 UEToVS 的差别）

- UEToVS 用 stdio（每个 Agent 会话一个进程，读磁盘索引）。我们**不自己开 MCP 服务**：工具经扩展点 `com.intellij.mcpServer.mcpToolsProvider` 注册到 IDE 自带的 MCP 服务器（`src/main/resources/META-INF/emmylua-mcp.xml`，plugin.xml 里 `optional depends com.intellij.mcpServer`）。调试状态（会话、断点、暂停）只在 IDE 进程里，挂在 IDE 的服务器上最直接；端口和开关归 IDE，插件不占端口。
- **安装 MCP 到项目**（`LuaMcpInstallDialog` / `LuaMcpInstallPrompt`，安装器在 `modules/debugger-mcp` 的 `install/`）：把 **IDE 自带 MCP 服务器的地址**写进项目里各 Agent 的配置，全组提交一份就能用。
  - 地址经 `ide/IdeMcpServerInfo` 反射读（它不碰 MCP Server 插件的类型，插件被禁用时也能调）：2025.2–2025.3 只有 SSE `http://127.0.0.1:<端口>/sse`；2026.1 起用 Streamable HTTP `/stream`（IDE 自己的自动配置也优先它）。服务在跑时取 IDE 给的地址和端口，没跑时读设置里的端口，再读不到用默认 64342，弹窗里说明是按默认值写的。主干（2026.3 之后）要求请求头 `IJ_MCP_AUTH_TOKEN`：配置里写成从同名环境变量展开（Claude Code / Gemini `${IJ_MCP_AUTH_TOKEN}`，Cursor / VS Code `${env:IJ_MCP_AUTH_TOKEN}`，Codex `env_http_headers`），令牌本身不进文件。
  - 条目名照 IDE「自动配置」写的：2025.x `jetbrains`，2026.1 起按产品名（`McpClient.Companion.productSpecificServerKey()`，如 `idea`、`rider`），这样用户再点 IDE 的自动配置也只是更新同一项，客户端里不会出现两份工具。安装时删掉旧版插件服务（127.0.0.1:9330/mcp）的 `emmylua-debug` 项，以及指向同一端口的 `jetbrains` 旧项。
  - 安装位置：项目目录往上找版本管理根（`.git` 目录或文件、`.hg`、`.svn` 取连续最外层、`.plastic`、P4CONFIG 文件；都没有时问 `p4 info` 的 client root，包含项目才用）；找不到就往上找含 `.uproject` 的 UE 项目根，再没有用项目目录。版本管理根在 UE 项目里面（脚本目录单独成库）时以 UE 项目为准（`VcsRoot.installRoot`）。
  - 项目级配置（Claude Code、Cursor、VS Code、Gemini）在 `headers` 里写 `IJ_MCP_SERVER_PROJECT_PATH` = 本项目目录（和 IDE 自动配置一样），IDE 靠它定项目、Agent 不用传 `projectPath`；状态检查时这个头只要指向本机存在的目录就算装好（全组共用一份配置，别人的检出路径不同也不来回改），没有或目录不存在算过期。Codex 是用户级配置，不写。
  - 写入规则：只合并我们这一项（用户在这一项里加的其他字段保留），其余原样保留；不是严格 JSON（注释、尾逗号）不写，给手动片段；跟随原文件的缩进和换行；临时文件原子替换。每个客户端一个 `McpClientConfigWriter`：Claude Code `.mcp.json`（`type` http/sse + `url`）、Cursor `.cursor/mcp.json`（`url`）、VS Code / Copilot `.vscode/mcp.json`（`servers`，`type` + `url`）、Gemini CLI `.gemini/settings.json`（Streamable HTTP 用 `httpUrl`，SSE 用 `url`）、Codex 用户级 `~/.codex/config.toml`（`[mcp_servers.<名>]` 的 `url`；Codex 不支持 SSE，IDE 只有 SSE 时不写、让用户用 IDE 的自动配置）。
  - 提示：项目打开后（项目里有 .lua 文件）和第一次启动 Lua 调试会话时检查；UE 项目打开时不单独弹，合进「EmmyLua 上手检查」的气泡（`onboarding/LuaOnboardingPrompt`，弹过后本次会话的 MCP 提示也不再弹），一个 IDE 会话每个项目最多弹一次，「不再提示」按项目记住（`LuaDebugProjectState.mcpPromptDismissed`）。IDE 的 MCP 服务器没开时合在同一个气泡里：按钮先打开「设置 → 工具 → MCP 服务器」，回来后开了就接着安装，没开不写；已经装好只差服务器时只提示去打开。MCP Server 插件被禁用时不提示，只是没有这些工具，调试照常。入口还有「工具 → EmmyLua 调试 → 安装 MCP 到项目」和设置页「EmmyLua 调试」（显示本项目安装状态）。
- **仅路由器模式**（2026.2 起）：IDE 默认把插件工具设成 Router-only（只能经 `execute_tool` 调，没有 schema 校验）。我们的类别 `alwaysIncluded = true`（`IdeMcpCompat.category`），2026.2 在路由器模式下仍直接列出，2026.3 默认不勾 Router-only；用户仍可改。文档和安装弹窗提示别给常用工具勾 Router-only。
- **中文名**：IDE 的 tools/list 不带 `_meta`（2026.2 前也没有 `title`），中文名由 `IdeMcpCompat.describe` 附在 description 最后一行 `Title (zh): <中文名>`，面板按这个前缀读；改格式要同步面板。
- **Lua 附加配置也接 Run 执行器**：`LuaDebugRunner` 收到 Run 时换成 Debug 执行器重新启动，所以 IDE 自带的 `execute_run_configuration` 和运行按钮也能开始 Lua 调试。
- IDE 的 MCP 服务器接口 2025.2–2026.3 每版都在变：只有 `McpTool`、`McpToolsProvider`、`McpToolCallResultContent.Text` 稳定，`McpToolDescriptor`、输入 schema（2025.2 `McpToolInputSchema`，之后 `McpToolSchema`）、`McpToolCallResult`、客户端信息（2025.3 起 `McpCallInfo`）**一律经 `ide/IdeMcpCompat` 反射构造或读取**，不要在代码里直接调它们的构造函数。新版 IDE 改了接口时，在 `IdeMcpCompat` 的版本表里加一行，并用 `McpCompatTest` 验证（见「测试」）。
- `McpTool.call` 是挂起函数；工具会阻塞（`wait` 最长 2 分钟、等用户确认），在 `runInterruptible(Dispatchers.IO)` 里跑，客户端取消时中断等待并返回 `cancelled`。
- 旧版的独立 HTTP 服务（端口 9330）已删除；用户项目里旧的 `emmylua-debug` 配置项由「安装 MCP 到项目」删掉并换成 IDE 服务器那一项。

### 日志

- 每次工具调用写一条 `mcp.tool.<工具名>`（参数摘要、耗时、客户端）；参数错 / 权限拒绝记 `.rejected`（Warn），内部异常 `.failed`（Error，带堆栈），非等待类超过 1 秒记 `.slow`。同时进调用记录，给调试窗口的「Agent」页显示。

### 测试

- 安装器（安装位置、各客户端格式、合并规则）用 `McpInstallerTest`。
- `modules/debugger-mcp` 不依赖 IntelliJ，工具层用 `FakeBackend`（假引擎）单测，分发器（参数校验、错误格式、日志、调用记录）用 `McpDispatcherTest`。改工具时同步改 `LuaDebugToolsTest`。
- `lua_code` 用 `FakeCodeBackend`（形状照 emmylua_ls 的真实回包）单测（`LuaCodeToolsTest`）；`LuaCodeRealServerTest` 用真的 emmylua_ls 跑一遍，只在环境变量 `EMMYLUA_LS` 指向可执行文件时运行，改了符号整形或 emmylua_ls 升级时跑一次。
- IDE 适配用主插件的 `McpCompatTest`：编译用的 2025.2 总是测；其他版本把各版本 IDE 的 `plugins/mcpserver*/lib` 里的 MCP 服务器 jar（2026.2 起加 MCP Kotlin SDK 的 jar）放进 `EMMY_MCPSERVER_JARS/<版本>/` 再跑。2026.2 起的 jar 是 Java 25 字节码，在 Java 21 上测时先把类文件主版本号改成 65（只为验证构造参数，不进包）。
- 云端拉不下 IntelliJ SDK 时，可以只用 kotlinc 编这个模块跑单测；主插件交给 CI。
