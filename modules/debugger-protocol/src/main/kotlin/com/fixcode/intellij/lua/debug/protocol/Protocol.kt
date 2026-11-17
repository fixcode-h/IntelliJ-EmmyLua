package com.fixcode.intellij.lua.debug.protocol

/**
 * 协议 v3 的命令、事件、能力位和错误码。
 * 以 docs/design/protocol-v3.md 为准；两端改名要在同一个 PR 里一起改。
 */
object Protocol {
    const val VERSION: Int = 3

    /** 本实现对应的协议修订号（protocol-v3.md 第 19 节）。 */
    const val REVISION: Int = 0

    const val CLIENT_NAME: String = "intellij-emmylua"
}

object Commands {
    const val HELLO = "hello"
    const val LOAD_HELPERS = "loadHelpers"
    const val SET_OPTIONS = "setOptions"
    const val SET_BREAKPOINTS = "setBreakpoints"
    const val CONFIGURATION_DONE = "configurationDone"
    const val VMS = "vms"
    const val THREADS = "threads"
    const val CONTINUE = "continue"
    const val PAUSE = "pause"
    const val STEP_IN = "stepIn"
    const val STEP_OVER = "stepOver"
    const val STEP_OUT = "stepOut"
    const val RUN_TO_LINE = "runToLine"
    const val STACK_TRACE = "stackTrace"
    const val SCOPES = "scopes"
    const val VARIABLES = "variables"
    const val EVALUATE = "evaluate"
    const val SET_VARIABLE = "setVariable"
    const val SET_VIEW_RULES = "setViewRules"
    /** 采样性能分析（第 20 节，能力 profiler）。 */
    const val PROFILE = "profile"
    /** 错误聚合（第 21 节，能力 errorReport）。 */
    const val ERRORS = "errors"
    const val CANCEL = "cancel"
    const val PING = "ping"
    const val DISCONNECT = "disconnect"
}

object Events {
    const val VM_ADDED = "vmAdded"
    const val VM_STATE_CHANGED = "vmStateChanged"
    const val VM_CLOSED = "vmClosed"
    const val BREAKPOINT_STATUS = "breakpointStatus"
    const val BREAKPOINT_HIT = "breakpointHit"
    const val PAUSED = "paused"
    const val RESUMED = "resumed"
    const val OUTPUT = "output"
    const val TERMINATED = "terminated"
    /** 错误聚合（第 21 节）：新错误马上推，同一条每秒最多推一次计数。 */
    const val LUA_ERROR = "luaError"
}

object Capabilities {
    const val CBOR = "cbor"
    const val VALUE_PATH = "valuePath"
    const val LUA_EVAL = "luaEval"
    const val SET_VARIABLE = "setVariable"
    const val CONDITION = "condition"
    const val HIT_CONDITION = "hitCondition"
    const val LOGPOINT = "logpoint"
    const val CAPTURE = "capture"
    const val RUN_TO_LINE = "runToLine"
    const val EXCEPTIONS = "exceptions"
    const val COROUTINES = "coroutines"
    const val OUTPUT = "output"
    const val HELPERS = "helpers"
    const val SOURCE_HASH = "sourceHash"
    const val HOOK_MUX = "hookMux"
    const val TERMINATE = "terminate"
    /** 变量视图增量（variable-view.md 13.2–13.3、13.5）：kind / groups / expand / variableView 选项。 */
    const val RICH_VARIABLES = "richVariables"
    /** setViewRules（variable-view.md 13.4）。 */
    const val VIEW_RULES = "viewRules"
    /** 采样性能分析 profile（第 20 节）。没协商到时 IDE 不发 profile、不显示采样按钮。 */
    const val PROFILER = "profiler"
    /** 错误聚合：setOptions.collectErrors、errors、luaError（第 21 节）。 */
    const val ERROR_REPORT = "errorReport"

    /** IDE 在 hello 里声明想用的能力；最终生效的是 welcome 里的交集。 */
    val CLIENT_WANTED: List<String> = listOf(
        VALUE_PATH, LUA_EVAL, SET_VARIABLE, CONDITION, HIT_CONDITION, LOGPOINT, CAPTURE, RUN_TO_LINE,
        EXCEPTIONS, COROUTINES, OUTPUT, HELPERS, SOURCE_HASH, TERMINATE, RICH_VARIABLES, VIEW_RULES, PROFILER, ERROR_REPORT
    )
}

object ErrorCodes {
    const val NOT_READY = "NOT_READY"
    const val PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val BUSY = "BUSY"
    const val UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
    const val INVALID_ARGS = "INVALID_ARGS"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val VM_NOT_FOUND = "VM_NOT_FOUND"
    const val NOT_PAUSED = "NOT_PAUSED"
    /** 控制命令的 pauseId 不是 runtime 当前暂停（已继续 / 被新暂停取代）。 */
    const val STALE_PAUSE = "STALE_PAUSE"
    /** frameId / ref 属于已经结束的暂停。 */
    const val STALE_REFERENCE = "STALE_REFERENCE"
    const val EVAL_ERROR = "EVAL_ERROR"
    const val EVAL_FORBIDDEN = "EVAL_FORBIDDEN"
    const val TIMEOUT = "TIMEOUT"
    const val CANCELLED = "CANCELLED"
    const val INTERNAL = "INTERNAL"

    /** 以下是 IDE 本地产生的，不会出现在线上。 */
    const val DISCONNECTED = "DISCONNECTED"
}

object PauseReasons {
    const val BREAKPOINT = "breakpoint"
    const val STEP = "step"
    const val PAUSE = "pause"
    const val RUN_TO_LINE = "runToLine"
    const val ENTRY = "entry"
    const val EXCEPTION = "exception"
    const val HOST_BREAK = "hostBreak"
}

object ResumedBy {
    const val CONTINUE = "continue"
    const val STEP = "step"
    const val RUN_TO_LINE = "runToLine"
    const val DISCONNECT = "disconnect"
    const val HOST = "host"
}

object VmStates {
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val CLOSING = "closing"
}

object BreakpointStateNames {
    const val PENDING = "pending"
    const val VERIFIED = "verified"
    const val MISMATCH = "mismatch"
    const val AMBIGUOUS = "ambiguous"
    const val INVALID = "invalid"
}

object ThreadStatuses {
    const val RUNNING = "running"
    const val SUSPENDED = "suspended"
    const val NORMAL = "normal"
    const val DEAD = "dead"
}

object ScopeNames {
    const val LOCALS = "locals"
    const val UPVALUES = "upvalues"
    const val VARARGS = "varargs"
    const val GLOBALS = "globals"
}

/** variables.view（variable-view.md 13.3）。 */
object VariableViews {
    const val AUTO = "auto"
    /** lua_next 原始顺序、不分组、不调 helper / 宿主 / __tostring。 */
    const val RAW = "raw"
}

object EvalModes {
    /** 受限求值：只允许 `a.b[1].c` 这样的取值路径，不执行任意代码。Agent 默认用它。 */
    const val VALUE_PATH = "valuePath"
    /** 任意 Lua，需要能力 luaEval 且 runtime 的 allowLuaEval 为 true。 */
    const val LUA = "lua"
}

object OutputCategories {
    const val STDOUT = "stdout"
    const val STDERR = "stderr"
    const val LOGPOINT = "logpoint"
    const val RUNTIME = "runtime"
    const val HOST = "host"
}

object ExceptionModes {
    const val NONE = "none"
    const val UNCAUGHT = "uncaught"
    const val ALL = "all"
}

/** profile.action（第 20 节）。 */
object ProfileActions {
    const val START = "start"
    const val STOP = "stop"
    const val RESULT = "result"
}

/** profile.sort：函数表按自身样本还是总样本排序。 */
object ProfileSorts {
    const val SELF = "self"
    const val TOTAL = "total"
}
