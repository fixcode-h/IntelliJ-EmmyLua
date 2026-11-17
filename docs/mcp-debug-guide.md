# 让 Agent 调试 Lua（MCP）

插件把 Lua 调试工具注册到 **IDE 自带的 MCP 服务器**（IntelliJ 2025.2 起内置的 MCP Server 插件）。Claude Code、Cursor、VS Code（Copilot）、Codex 等 Agent 连上 IDE 的 MCP 服务器后，除了 IDE 自己的工具，还能直接调试 Lua：下断点、等暂停、看栈和变量、单步、采样性能。插件不再自己起 MCP 服务，也不再往项目里写配置文件。

## 开启

1. 打开 **设置 → 工具 → MCP 服务器**，勾选「启用 MCP 服务器」。
2. 在同一页用「自动配置」把 IDE 加到你用的 Agent（Claude Code、Cursor、VS Code、Codex……），或者照页面上给的地址手动配置。地址和端口由 IDE 管理。
3. 在「MCP 服务器 → 公开的工具」里能看到 `lua_debug_session`、`lua_debug_breakpoints`、`lua_debug_inspect` 三个调试工具和代码查找工具 `lua_code`（类别都是 LuaDebug），可以按需关掉。开了「仅路由器模式」的 IDE 版本里，Lua 调试工具和 IDE 自己的工具一样处理。

第一次启动 Lua 调试会话时，如果 IDE 的 MCP 服务器还没开，右下角会提示一次，点「打开 MCP 服务器设置」即可。**设置 → 工具 → EmmyLua 调试** 和 **Tools → EmmyLua 调试 → MCP 状态** 里能看到服务器是否在运行。

MCP Server 插件被禁用时，Lua 调试本身照常可用，只是 Agent 用不了这些工具。

从旧版本升级：旧版写进项目的 `.mcp.json`、`.cursor/mcp.json` 等文件里的 `emmylua-debug` 项（`http://127.0.0.1:9330/mcp`）已经连不上，删掉这一项，改用 IDE 的 MCP 服务器。

## 安全

- 监听地址、来源校验由 IDE 的 MCP 服务器负责（只听本机）。
- Agent 只读（看会话、栈、变量、输出）不需要确认。
- Agent 第一次控制某个调试会话（下断点、继续、单步）时，右下角会弹出确认：「本次会话总是允许 / 允许一次 / 拒绝」，60 秒不处理按拒绝。
- Agent 每次附加进程都要确认。
- Agent 热重载 UE 里的 Lua（`reload`）按控制处理：第一次弹确认，「本次会话总是允许」后不再问。
- 执行任意 Lua 表达式默认关闭，需要在 **设置 → 工具 → EmmyLua 调试** 里打开「允许 Agent 执行任意 Lua 表达式」；默认只能读变量路径（如 `self.items[3].id`）。
- 调试窗口的「Agent」页能看到 Agent 的每一次调用，可以一键「撤销 Agent 控制」「清除 Agent 断点」。

## Agent 能用的工具

三个工具，用 `action` 区分动作（和 UEToVS 的 VS 调试工具同一种形状）：

| 工具 | 动作 |
| --- | --- |
| `lua_debug_session` | `help` 完整用法 · `status` 会话状态 · `targets` 可附加的进程/设备 · `attach` 附加 · `stop` 结束 · `pause` / `continue` / `step` · `wait` 等下一次暂停 · `reload` 把改过的 Lua 热重载到开着的 UE 编辑器 / 游戏（`files` / `modules` / `changed=true`，经 UELink，不需要调试会话） |
| `lua_debug_breakpoints` | `list` · `add`（条件、命中次数、日志点、capture + auto_continue 的 Probe）· `remove` · `enable` · `clear`（默认只删 Agent 下的） |
| `lua_debug_inspect` | `stack` · `vms` · `locals` · `eval` · `output`（print 和 Lua 错误）· `probes` · `profile`（采样 `duration_ms`，默认 3 秒、最多 60 秒，返回最耗时的 Lua 函数，`sort` = self / total，`limit` 默认 30） |

`profile` 不需要确认：采样只观察，不暂停游戏、不改变量；采样期间 Lua 大约慢一倍，结束后恢复。结果按 Lua 指令数统计，花在 C / 引擎接口里的时间不计入。让 Agent 采样时，在这几秒里操作到慢的那个场景。

`reload` 要求 UE 项目里装了 UELink（UEKit，2.4.0 以上）和 UnLua；没开 UE 编辑器或游戏时返回 `target_not_found`，UE 那边没有 `lua.reload` 时返回 `not_supported`。

## 查找 Lua 代码：`lua_code`

`lua_code` 不是调试工具，不需要调试会话，也不弹确认（只读）：它经 emmylua_ls（和编辑器里的跳转、查找引用同一个语言服务器）查 Lua 代码，替代 UEToVS 以前的 `search_lua`、`search_lua_files`、`get_lua_outline`，参数和结果的形状尽量和它们一致。

| 动作 | 用法 | 结果 |
| --- | --- | --- |
| `search` | `query` 名字或片段：前缀、子串、CamelHumps（`OBC` → `OnButtonClicked`）；`M:Fire`、`UI.MainPanel.Show` 限定容器（容器名或文件的模块名）。可选 `kinds`（function / method / class / table / field / variable）、`container`、`match=exact`、`include_locals=false`、`scope=all` | `name`、`kind`、`container`、`file`、`line` / `col`（1 起，指向名字）、`sig`（参数表）、`module`、`local`、`lib` |
| `files` | `query` 文件名、片段或 require 路径（`UI.MainPanel`、`UI/MainPanel`、`MainPan`） | `name`、`path`、`module`、`lib` |
| `outline` | `path` 绝对路径、相对项目或 `Content/Script` 的路径、模块名；可选 `kinds` | `file`、`module`，`items` 按源码顺序 |
| `references` | `query` 名字（按名字找到声明再查），或 `file` + `line` + `col`（或 `file` + `line` + `query`）；`include_declaration` 默认 true | `target`，`items` 每处的 `file`、`line`、`col` 和那一行的内容 `text` |

- 结果是分页信封 `{count, offset, total, hasMore, nextOffset?, items}`，`limit` 最大 200，用 `offset=nextOffset` 往下翻。
- `search` 默认只查项目里的脚本；`scope=all` 再加上库目录（UE 声明、设置里的额外库、emmylua 自带的标准库）里的类型和全局。库目录不逐个文件扫（UE 声明动辄上万个文件）。
- 第一次 `search` 要向 emmylua_ls 取所有项目脚本的符号（几千个文件约 1–2 秒），之后按文件版本缓存，改过的文件才重新取。
- 声明来自 emmylua_ls 的 documentSymbol，再加上它不列出的 `---@class` / `---@enum` / `---@field` 注解。`M.x = 1` 这种在表构造之外赋值的字段 emmylua_ls 不列出，找这类用 grep。
- emmylua_ls 还在启动或建索引时返回 `indexing`（几秒后重试）；没有项目、找不到 emmylua_ls 时返回 `engine_unavailable`，说明里写了怎么办。emmylua_ls 还没启动过（没打开过 Lua 文件）时，第一次调用会顺手启动它。
- 打开了多个项目时默认查焦点项目，结果带 `project`；可以用 `project` 参数指定。

开发者看规范：仓库根目录 [AGENTS.md](../AGENTS.md)「MCP 工具规范」。
