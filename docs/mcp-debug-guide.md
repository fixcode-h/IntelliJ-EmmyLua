# 让 Agent 调试 Lua（MCP）

插件在 IDE 里内置了一个 MCP 服务，Claude Code、Cursor、VS Code（Copilot）、Gemini CLI、Codex 等 Agent 连上后可以直接调试 Lua：下断点、等暂停、看栈和变量、单步。原来的命令行工具 `emmy-debug` 已删除，由它代替。

## 安装到项目

打开含 Lua 的项目时，如果还没装，右下角会提示「EmmyLua 调试 MCP 未安装到本项目」，点「安装」即可。也可以在 **Tools > EmmyLua 调试 > 安装 MCP 到项目** 里随时安装、更新或卸载。

- 配置写在项目的**版本管理根目录**（`.git` / `.svn` / `.hg` / `.plastic` / Perforce 工作区根），找不到时写在项目目录。
- 按检测到的客户端写入（默认勾选已经有对应目录的）：

  | 客户端 | 文件 |
  | --- | --- |
  | Claude Code | `.mcp.json` |
  | Cursor | `.cursor/mcp.json` |
  | VS Code / Copilot | `.vscode/mcp.json` |
  | Gemini CLI | `.gemini/settings.json` |
  | Codex | `~/.codex/config.toml`（用户级，只在勾选时写） |

- 只增改 `emmylua-debug` 这一项，别的配置原样保留；文件里有注释等非严格 JSON 时不会改动，会给出要手动粘贴的片段。
- 配置里只有本机地址 `http://127.0.0.1:9330/mcp`，没有密钥，可以提交进版本库给全组用。写完不会自动加入版本管理，由你决定。

端口在 **设置 > 工具 > EmmyLua 调试** 里改（默认 9330，被占用时自动顺延）；端口变了以后，安装状态会提示一键更新配置。

## 安全

- 服务只监听 `127.0.0.1`，并校验请求来源，别的机器和网页连不上。
- Agent 只读（看会话、栈、变量、输出）不需要确认。
- Agent 第一次控制某个调试会话（下断点、继续、单步）时，右下角会弹出确认：「本次会话总是允许 / 允许一次 / 拒绝」，60 秒不处理按拒绝。
- Agent 每次附加进程都要确认。
- 执行任意 Lua 表达式默认关闭，需要在设置里打开「允许 Agent 执行任意 Lua」；默认只能读变量路径（如 `self.items[3].id`）。
- 调试窗口的「Agent」页能看到 Agent 的每一次调用，可以一键「撤销 Agent 控制」「清除 Agent 断点」。

## Agent 能用的工具

三个工具，用 `action` 区分动作（和 UEToVS 的 VS 调试工具同一种形状）：

| 工具 | 动作 |
| --- | --- |
| `lua_debug_session` | `status` 会话状态 · `targets` 可附加的进程/设备 · `attach` 附加 · `stop` 结束 · `pause` / `continue` / `step` · `wait` 等下一次暂停 |
| `lua_debug_breakpoints` | `list` · `add`（条件、命中次数、日志点、capture + auto_continue 的 Probe）· `remove` · `enable` · `clear`（默认只删 Agent 下的） |
| `lua_debug_inspect` | `stack` · `vms` · `locals` · `eval` · `output`（print 和 Lua 错误）· `probes` |

开发者看规范：仓库根目录 [AGENTS.md](../AGENTS.md)「MCP 工具规范」。
