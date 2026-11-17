package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeBackend
import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeTools
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpCallLog
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpConfirmer
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpLogger
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpPermissionPolicy
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpToolDispatcher
import com.fixcode.intellij.lua.debugger.mcp.tools.LuaDebugTools

/**
 * 把工具、权限、调用记录组装起来。IDE 侧只需提供引擎（[backend]）、确认弹窗（[confirmer]）和日志（[logger]），
 * 再把 [dispatcher] 的工具注册到 IDE 自带的 MCP 服务器。给了 [code]（每个打开的项目一个，焦点项目在前）时
 * 再加上代码查找工具 `lua_code`。
 */
class EmmyLuaDebugMcp(
    backend: () -> LuaDebugBackend?,
    confirmer: McpConfirmer,
    logger: McpLogger = McpLogger.NONE,
    timeouts: LuaDebugTools.Timeouts = LuaDebugTools.Timeouts(),
    code: (() -> List<LuaCodeBackend>)? = null,
) {
    val policy = McpPermissionPolicy(confirmer)
    val callLog = McpCallLog()
    val dispatcher = McpToolDispatcher(
        LuaDebugTools(backend, policy, timeouts, GUIDE).all() + (code?.let { LuaCodeTools(it).all() } ?: emptyList()),
        callLog,
        logger,
    )

    companion object {
        /**
         * 完整用法，`lua_debug_session action=help` 返回。IDE 的 MCP 服务器没有给单个插件的 instructions
         * 和资源，所以调试循环写进工具说明，更长的放这里。
         */
        val GUIDE = """
            # EmmyLua debug tools for agents

            EmmyLua debugs Lua running in a game or tool (UE/UnLua, Unity, own engines, Android devices) through the IDE's debugger.
            Use these tools when you need runtime values or control flow of Lua code instead of adding print() calls and reloading.

            Loop: `lua_debug_session` action=`status` (no session: action=`targets`, then `attach`) -> `lua_debug_breakpoints` action=`add`
            -> let the user (or a test) trigger the code -> `lua_debug_session` action=`wait` -> `lua_debug_inspect` action=`stack` / `locals` / `eval`
            -> `lua_debug_session` action=`step` or `continue` -> wait again.

            | Need | Tool |
            |---|---|
            | What is being debugged, is anything stopped | `lua_debug_session` action=`status` |
            | Attach to a local process / Android app | `lua_debug_session` action=`targets`, then `attach` (pid / name, or device + package) |
            | Stop at a line | `lua_debug_breakpoints` action=`add` file + line (+ condition, hit_count) |
            | Stop when a function is called (file unknown) | `lua_debug_breakpoints` action=`add` function="BagPanel:OnItemClick" / "M.add" / "GlobalFn" (stops at its first line; optional file narrows it) |
            | Log without stopping | `lua_debug_breakpoints` action=`add` message="hp={self.hp}" |
            | Record values on every hit without stopping | `lua_debug_breakpoints` action=`add` capture=[...] auto_continue=true, then `lua_debug_inspect` action=`probes` |
            | Wait for the next stop | `lua_debug_session` action=`wait` (timeout_ms ≤ 120000) |
            | Where is it, call stack | `lua_debug_inspect` action=`stack` |
            | Variables | `lua_debug_inspect` action=`locals` (scope, path, depth, ref to expand) |
            | A value | `lua_debug_inspect` action=`eval` expr="self.items[3].id" |
            | Step | `lua_debug_session` action=`step` kind=over/into/out/toLine |
            | print output, Lua errors | `lua_debug_inspect` action=`output` (cursor = previous next) |
            | Which Lua functions are slow | `lua_debug_inspect` action=`profile` duration_ms (≤ 60000) sort=self/total limit; the game keeps running |
            | Clean up | `lua_debug_breakpoints` action=`clear` (only the agent's breakpoints) |
            | Hot-reload edited UnLua scripts into the open UE editor / game | `lua_debug_session` action=`reload` files=[...] / modules=[...] / changed=true |

            - The first control action on a session and every attach ask the user in the IDE; on `permission_denied` ask the user, do not retry.
            - The user's own breakpoints are listed without `byAgent`; leave them alone.
            - While a VM is stopped the game thread is frozen: continue when done.
            - `eval` defaults to read-only value paths (self.items[3].id, #list); mode=lua runs Lua (allowed by default, the user confirms once per session; may be turned off in settings).
            - Refs and frames die when the VM continues; on `stale_reference` fetch the stack again.
            - `profile` samples the Lua call stack every 1000 VM instructions: `selfPct` is time in the function itself, `totalPct` includes
              callees. It counts Lua instructions, not time inside C / engine calls, and Lua runs about 2x slower while sampling.
              Trigger the slow scene during the window; 0 samples means no Lua ran (paused, or the scene did not run).
            - `verified: false` means the script is not loaded yet or the line has no code; it binds when the script loads.
            - Lines are 1-based, frame 0 is the top. Results are compact JSON; errors are {"error": code, "message": what to do}.
            - Errors: `invalid_argument`, `unknown_tool`, `engine_unavailable`, `no_session`, `not_paused`, `stale_reference`,
              `permission_denied`, `eval_forbidden`, `not_supported`, `timeout`, `attach_failed`, `target_not_found`, `indexing`, `cancelled`, `internal_error`.
            - `reload` needs UELink (UEKit) in the UE project and UnLua; no UE editor or game running -> `target_not_found`.

            ## Finding Lua code: `lua_code`

            Not a debug tool: it looks up code through the IDE's emmylua_ls (no session needed, read-only).

            | Need | Call |
            |---|---|
            | Where is a function / method / class / table declared | `lua_code` action=`search` query="OnButtonClicked" (fuzzy: "OBC", "M:Fire", "UI.MainPanel.Show"; kinds, container, match=exact) |
            | Which file is a require path / script name | `lua_code` action=`files` query="UI.MainPanel" / "MainPan" |
            | Structure of a big script before reading it | `lua_code` action=`outline` path="UI.MainPanel" (module, absolute or relative path) |
            | Who uses a symbol | `lua_code` action=`references` query="M:Fire", or file + line + col |

            - search covers project scripts by default; scope=all adds libraries (UE declarations, extra libraries, emmylua std).
            - `indexing`: emmylua_ls is starting or indexing, retry in a few seconds. `engine_unavailable`: no project / no server, grep instead.
            - Fields assigned outside a table constructor (`M.x = 1`) are not listed by emmylua_ls; grep for those.
        """.trimIndent()
    }
}
