# IntelliJ-EmmyLua · 开发约定

给在这个仓库里工作的 AI 编码助手（Codex、Claude Code 等）和开发者看。`CLAUDE.md` 只引用本文件，规则只改这一份。

## 语言支持（LSP）

设计见 `docs/design/lsp-migration.md`。

- 语义（跳转、补全、引用、诊断、格式化、语义高亮……）全部由 emmylua_ls 提供，经 LSP4IJ 接入。**不要再往 IDE 里加 PSI 级的语义代码**；缺什么先看 emmylua_ls 是否支持、`.emmyrc.json` 怎么配。
- IDE 侧只有 `lexer/LuaLexer.kt`（手写词法器）+ 扁平 PSI，用于高亮、括号、注释、调试器取名字。
- 插件给 emmylua_ls 的配置只通过 `lsp/EmmyLuaWorkspaceConfig`（`workspace/configuration` 的 `emmylua` 段）下发；数组与用户 `.emmyrc.json` 取并集，标量会覆盖，所以用户有配置文件时不下发标量。
- 版本：`gradle.properties` 的 `lsp4ijVersion`、`emmyLuaLsVersion`。出包用 `.github/scripts/fetch-emmylua-ls.sh` 下载各平台二进制到 `build/emmylua-ls/`，Gradle 放进插件的 `server/<平台>/`。
- 包名 `com.fixcode.intellij.lua`。新文件不加文件头；从上游 EmmyLua 派生、仍保留上游代码的文件保留原 Apache 版权头。持久化名字（`@State` 名、存储文件、插件 ID、语言 ID `Lua`、文件类型名 `lua`、运行配置和断点类型 ID）不改。

## MCP 工具规范

插件在 IDE 里内置 MCP Server（`modules/debugger-mcp` + 主插件的宿主），让 Agent 调试 Lua。工具的形状**照 UEToVS 的 MCP**（`fixcode-h/UEToVS` 的 `docs/design/MCP.md`、`docs/design/VS-调试控制.md`、`src/UEToVS.Mcp/Tools/`），同一个 Agent 同时挂着 UEToVS 和 EmmyLua 时用法一致。加工具、改参数或返回前先读完本节。

### 命名

- 工具名 `lua_debug_<领域>`，snake_case。按领域合并成少数几个工具，用 `action` 区分动作（对应 UEToVS 的 `debug_session` / `debug_breakpoints` / `debug_inspect`），不为每个动作单开一个工具：工具多了 Agent 选不准，延迟加载工具说明时也更贵。
- 现有三个：
  - `lua_debug_session`：`status` / `targets` / `attach` / `stop` / `pause` / `continue` / `step` / `wait`
  - `lua_debug_breakpoints`：`list` / `add` / `remove` / `enable` / `clear`
  - `lua_debug_inspect`：`stack` / `vms` / `locals` / `eval` / `output` / `probes` / `profile`
- `lua_` 前缀是为了和 UEToVS 的 `debug_*`、面板的 `ue_*` 不撞名。工具名一旦发布就不改：Agent 和用户的提示词里写死了它。改名或删掉时，旧名字保留一版，调用时返回「改用哪个」的错误。
- 配置文件里的服务器名、`serverInfo.name` 都是 `emmylua-debug`。

### 参数

- 参数名 snake_case（`session_id`、`wait_ms`、`timeout_ms`、`hit_count`、`auto_continue`、`max_chars`）。
- 每个工具的 inputSchema 是 `additionalProperties: false`，`action` 必填并给出 `enum`；每个参数的 description 用英文写清楚哪些 action 用它、默认值是多少（例：`"step: over (default) / into / out / toLine"`）。
- **未知参数直接报错**并列出合法的名字（`JsonArgs(raw, allowed)`）：拼错的过滤条件被悄悄忽略比报错更糟。类型宽松：`"20"` 当整数、`"a, b"` 当字符串数组；超范围报错并写出范围。
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

### 错误

- 出错返回 `isError: true` + `{"error": "<code>", "message": "<中文说明>"}`。`message` 原样给用户和 Agent 看，要写清楚**怎么办**（例：「游戏已继续运行，引用失效：先 lua_debug_session action=wait 或 lua_debug_inspect action=stack 取新的。」）。
- 错误码（snake_case，加新的要在这里登记）：`invalid_argument`、`unknown_tool`、`engine_unavailable`、`no_session`、`no_vm`、`not_paused`、`stale_reference`、`permission_denied`、`eval_forbidden`、`not_supported`、`timeout`、`attach_failed`、`target_not_found`、`cancelled`、`internal_error`。
- 参数错和权限拒绝是工具错误（`isError`），不是 JSON-RPC 协议错误；协议错误只用于报文本身不合法、方法不存在。

### 说明文字

- 每个工具的 description 用 `ToolGuide` 的固定结构（英文，给模型看）：`What` + `USE FIRST WHEN:` + `INSTEAD OF:` + `NOT FOR:` + 可选 `Results:`，总长不超过 1200 字。`UseWhen` 写具体场景和典型调用顺序，`InsteadOf` 写它比通用办法（加 print、读代码猜）好在哪，`NotFor` 写不能做什么、该用什么。
- `title` 是英文显示名，`_meta.titles` 带 `{"zh", "en"}`，客户端按自己的界面语言选。
- `annotations`：只读工具 `readOnlyHint: true`；会结束进程或丢数据的 `destructiveHint: true`。
- `initialize` 的 `instructions` 控制在 2000 字以内（Claude Code 会截断更长的），写调试循环和工具选择；更长的用法放资源 `emmylua-debug://guide`。
- 单测会检查：三段结构齐全、长度上限、参数名 snake_case、instructions 长度（`LuaDebugToolsTest`、`McpProtocolTest`）。

### 权限

- 只读（`status`、`list`、`stack`、`locals`、`output`……）默认允许。
- 控制（断点增删改、继续、单步、暂停、结束会话）：每个调试会话**第一次**控制时在 IDE 里弹通知「允许一次 / 本次会话总是允许 / 拒绝」；拒绝或超时返回 `permission_denied`，说明里让 Agent 去问用户，不要重试。「撤销 Agent 控制」清掉所有授权。
- `attach` 每次都确认。
- `profile`（采样性能分析）按只读处理、不弹确认：它不暂停、不改变量、不改执行路径，和 `output` / `probes` 一样只观察；代价是采样期间 Lua 变慢（约 2 倍），所以限时（`duration_ms` ≤ 60000）并由 runtime 到点自动停，IDE 断开也会停。
- `eval` 默认 `mode=value_path`（只读变量路径）；`mode=lua` 执行任意 Lua，要用户在设置里打开，并且按控制处理。runtime 侧的 `allowLuaEval` 也由引擎按设置下发，作为第二道保险。
- 不要求、也不写任何令牌：只绑 `127.0.0.1`，校验 `Host` / `Origin` 头防 DNS rebinding 和网页跨站调用。

### 传输和安装（和 UEToVS 的差别）

- UEToVS 用 stdio（每个 Agent 会话一个进程，读磁盘索引）。我们用 **IDE 内置的 Streamable HTTP**：`http://127.0.0.1:<端口>/mcp`，默认端口 9330，被占用时顺延。原因：调试状态（会话、断点、暂停）只在 IDE 进程里，stdio 还要再起一个转发进程，并且要在共享配置里写本机路径。
- 安装位置和 UEToVS 一致：项目目录往上找**版本管理根目录**（`.git` 目录或文件、`.hg`、`.svn` 取连续的最外层、`.plastic`、P4CONFIG 文件；都没有时问 `p4 info` 的 client root，包含项目才用），找不到就往上找 UE 项目根（含 `.uproject` 的目录），再没有才用项目目录（`install/VcsRoot.kt`）。IDE 常常只打开 UE 项目里的 `Content/Script`，所以一定要往上找；脚本目录自己是嵌套仓库时以 UE 项目为准。
- 写入规则和 UEToVS 一致：**只合并 `emmylua-debug` 这一项**，别的服务器、未知字段、用户在这一项里加的字段原样保留；文件不是严格 JSON（有注释、尾逗号）时不写，给出手动粘贴的片段；跟随原文件的缩进和换行符；临时文件 + 原子替换。配置里只有回环地址，可以提交进版本库。
- 每个客户端一个 `McpClientConfigWriter`，格式变了只改对应的那个：

  | 客户端 | 文件 | 内容 |
  | --- | --- | --- |
  | Claude Code | `<根>/.mcp.json` | `mcpServers.emmylua-debug = {type: "http", url}` |
  | Cursor | `<根>/.cursor/mcp.json` | `mcpServers.emmylua-debug = {url}` |
  | VS Code / Copilot | `<根>/.vscode/mcp.json` | `servers.emmylua-debug = {type: "http", url}` |
  | Gemini CLI | `<根>/.gemini/settings.json` | `mcpServers.emmylua-debug = {httpUrl}` |
  | Codex | `~/.codex/config.toml`（用户级） | `[mcp_servers.emmylua-debug]` `url = "..."`，只在用户勾选时写 |

### 日志

- 每次工具调用写一条 `mcp.tool.<工具名>`（参数摘要、耗时、客户端）；参数错 / 权限拒绝记 `.rejected`（Warn），内部异常 `.failed`（Error，带堆栈），非等待类超过 1 秒记 `.slow`。同时进调用记录，给调试窗口的「Agent」页显示。

### 测试

- `modules/debugger-mcp` 不依赖 IntelliJ，工具层用 `FakeBackend`（假引擎）单测，HTTP 宿主用真端口测。改工具时同步改 `LuaDebugToolsTest`。
- 云端拉不下 IntelliJ SDK 时，可以只用 kotlinc 编这个模块跑单测；主插件交给 CI。
