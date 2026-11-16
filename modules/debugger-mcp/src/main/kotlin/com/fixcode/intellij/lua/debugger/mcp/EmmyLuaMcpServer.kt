package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.http.McpHttpHost
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpCallLog
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpConfirmer
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpLogger
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpPermissionPolicy
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpServer
import com.fixcode.intellij.lua.debugger.mcp.tools.LuaDebugTools

/**
 * 把协议、工具、权限、调用记录、HTTP 宿主组装起来。IDE 侧只需提供引擎（[backend]）、确认弹窗（[confirmer]）
 * 和日志（[logger]），然后 start/stop。
 */
class EmmyLuaMcpServer(
    backend: () -> LuaDebugBackend?,
    confirmer: McpConfirmer,
    version: String,
    logger: McpLogger = McpLogger.NONE,
    timeouts: LuaDebugTools.Timeouts = LuaDebugTools.Timeouts(),
) {
    val policy = McpPermissionPolicy(confirmer)
    val callLog = McpCallLog()
    val tools = LuaDebugTools(backend, policy, timeouts).all()
    val protocol = McpServer(SERVER_NAME, version, tools, callLog, INSTRUCTIONS, GUIDE, GUIDE_URI, logger)
    private val host = McpHttpHost(protocol)

    val port: Int get() = host.port
    val isRunning: Boolean get() = host.isRunning

    /** 从 [preferredPort] 起找可用端口启动，返回实际端口；失败抛 IOException（中文消息）。 */
    fun start(preferredPort: Int): Int = host.start(preferredPort)

    fun stop() = host.stop()

    companion object {
        /** 配置文件里的服务器名，也是 serverInfo.name。改名要同时改安装器的迁移。 */
        const val SERVER_NAME = "emmylua-debug"
        const val GUIDE_URI = "emmylua-debug://guide"

        // 控制在 2000 字以内：有的客户端（Claude Code）会截断更长的 instructions
        val INSTRUCTIONS = """
            EmmyLua debugs Lua running in a game or tool (UE/UnLua, Unity, own engines, Android devices) through the IDE's debugger.
            Use these tools when you need runtime values or control flow of Lua code instead of adding print() calls and reloading.
            Loop: lua_debug_session action=status (no session: action=targets, then action=attach) -> lua_debug_breakpoints action=add
            -> let the user (or a test) trigger the code -> lua_debug_session action=wait -> lua_debug_inspect action=stack / locals / eval
            -> lua_debug_session action=step or continue -> wait again.
            To watch values without freezing the game: lua_debug_breakpoints action=add with capture=[paths] and auto_continue=true,
            then lua_debug_inspect action=probes. Print output and Lua errors: lua_debug_inspect action=output (pass next as cursor).
            Slow scene: lua_debug_inspect action=profile duration_ms=3000 while it runs -> hottest Lua functions (self/total %).
            The first control action on a session and every attach ask the user in the IDE; on permission_denied ask the user, do not retry.
            eval defaults to read-only value paths (self.items[3].id, #list); mode=lua is off unless the user enabled it.
            Refs and frames die when the VM continues; on stale_reference fetch the stack again.
            UE/UnLua: after editing scripts, lua_debug_session action=reload files=[...] (or changed=true) hot-reloads them
            into the open UE editor/game through UELink, no restart and no debug session needed.
            Lines are 1-based, frame 0 is the top. Results are compact JSON; errors are {"error": code, "message": what to do}.
        """.trimIndent()

        val GUIDE = """
            # EmmyLua debug tools for agents

            | Need | Tool |
            |---|---|
            | What is being debugged, is anything stopped | `lua_debug_session` action=`status` |
            | Attach to a local process / Android app | `lua_debug_session` action=`targets`, then `attach` (pid / name, or device + package) |
            | Stop at a line | `lua_debug_breakpoints` action=`add` file + line (+ condition, hit_count) |
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

            - The user's own breakpoints are listed without `byAgent`; leave them alone.
            - While a VM is stopped the game thread is frozen: continue when done.
            - `profile` samples the Lua call stack every 1000 VM instructions: `selfPct` is time in the function itself, `totalPct` includes
              callees. It counts Lua instructions, not time inside C / engine calls, and Lua runs about 2x slower while sampling.
              Trigger the slow scene during the window; 0 samples means no Lua ran (paused, or the scene did not run).
            - `verified: false` means the script is not loaded yet or the line has no code; it binds when the script loads.
            - Errors: `invalid_argument`, `unknown_tool`, `engine_unavailable`, `no_session`, `not_paused`, `stale_reference`,
              `permission_denied`, `eval_forbidden`, `not_supported`, `timeout`, `attach_failed`, `target_not_found`, `internal_error`.
            - `reload` needs UELink (UEKit) in the UE project and UnLua; no UE editor or game running -> `target_not_found`.
        """.trimIndent()
    }
}
