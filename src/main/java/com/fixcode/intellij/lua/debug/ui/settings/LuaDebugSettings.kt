package com.fixcode.intellij.lua.debug.ui.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 调试的全局设置（Settings > Tools > EmmyLua 调试）。
 *
 * MCP 工具、设备、引擎实现都直接读这里，不各自存一份。MCP 的端口和开关归 IDE 自带的 MCP 服务器
 * （设置 → 工具 → MCP 服务器），这里不再存。
 */
@Service(Service.Level.APP)
@State(name = "EmmyLuaDebugSettings", storages = [Storage("emmylua-debug.xml")])
class LuaDebugSettings : SimplePersistentStateComponent<LuaDebugSettings.State>(State()) {

    class State : BaseState() {
        /**
         * 允许 Agent 用 mode=lua 执行任意表达式。2.5.2 起默认开启（用户 2026-11-17）；仍按控制处理，每个会话第一次要用户确认。
         * BaseState 不存等于默认值的项：以前没动过这项的用户（存的是旧默认 false，没写进文件）升级后自动变成开启；
         * 升级后手动关掉的会存成 false。
         */
        var allowAgentLuaEval by property(true)
        /** 空 = 自动找（ANDROID_HOME / PATH）。 */
        var adbPath by string("")
        /** 未裁剪 so 的搜索目录，分号分隔，支持 $PROJECT_DIR$。 */
        var symbolSearchDirs by string(DEFAULT_SYMBOL_DIRS)
        /** 参考 so：同一工程以前任意一次构建的未裁剪 libUnreal.so，没有同一次构建的符号时按特征码找 Lua 函数。支持 $PROJECT_DIR$。 */
        var referenceSo by string("")
        /**
         * 调试控制台的日志级别。2.5.x 起新增「精简」并设为默认（用户 2026-11-17）；以前的 logLevel 项不再读，
         * 所有人升级后统一回到精简（旧设置当时也没真正生效过），想看更多的再手动调高。
         */
        var consoleLogLevel by enum(LuaDebugLogLevel.COMPACT)
        /** 保存 Lua 文件后经 UELink 自动重载到开着的 UE 编辑器 / 游戏（默认关）。 */
        var reloadToUnrealOnSave by property(false)

        // 变量视图（docs/design/variable-view.md 13.5、15.3）。平铺存储，读写走 [variableView]。
        var viewSort by enum(LuaVariableSort.SMART)
        var viewGroupMethods by property(true)
        var viewShowInherited by property(true)
        var viewShowMetatable by property(true)
        var viewToString by enum(LuaToStringPolicy.TABLES)
        var viewHideStdGlobals by property(true)
        var viewShowTemporaries by property(false)
        var viewPageSize by property(VariableViewSettings.DEFAULT_PAGE_SIZE)
        var viewMaxValueLength by property(VariableViewSettings.DEFAULT_MAX_VALUE_LENGTH)
        var viewHexIntegers by property(false)
        /** 相对项目根；空 = 默认 .emmylua/debug-view.json。 */
        var viewRulesFile by string(VariableViewSettings.DEFAULT_RULES_FILE)
    }

    var allowAgentLuaEval: Boolean
        get() = state.allowAgentLuaEval
        set(value) { state.allowAgentLuaEval = value }

    var adbPath: String
        get() = state.adbPath.orEmpty()
        set(value) { state.adbPath = value.trim() }

    var symbolSearchDirs: List<String>
        get() = state.symbolSearchDirs.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) { state.symbolSearchDirs = value.joinToString(";") }

    var referenceSo: String
        get() = state.referenceSo.orEmpty()
        set(value) { state.referenceSo = value.trim() }

    /** 展开 $PROJECT_DIR$ 后的参考 so；没设置返回 null。 */
    fun expandedReferenceSo(project: Project?): String? {
        val v = referenceSo.ifEmpty { return null }
        val base = project?.basePath
        return if (base != null) v.replace("\$PROJECT_DIR\$", base) else v
    }

    var logLevel: LuaDebugLogLevel
        get() = state.consoleLogLevel
        set(value) { state.consoleLogLevel = value }

    var reloadToUnrealOnSave: Boolean
        get() = state.reloadToUnrealOnSave
        set(value) { state.reloadToUnrealOnSave = value }

    /**
     * 变量视图设置的快照。引擎在握手后和设置变化时用 [VariableViewSettings.toProtocolOptions]
     * 发 `setOptions { variableView }`，渲染层读 IDE 侧的几项（每页条数、预览长度、十六进制）。
     */
    var variableView: VariableViewSettings
        get() = VariableViewSettings(
            sort = state.viewSort,
            groupMethods = state.viewGroupMethods,
            showInherited = state.viewShowInherited,
            showMetatable = state.viewShowMetatable,
            toString = state.viewToString,
            hideStdGlobals = state.viewHideStdGlobals,
            showTemporaries = state.viewShowTemporaries,
            pageSize = state.viewPageSize.coerceIn(VariableViewSettings.PAGE_SIZE_RANGE),
            maxValueLength = state.viewMaxValueLength.coerceIn(VariableViewSettings.MAX_VALUE_LENGTH_RANGE),
            hexIntegers = state.viewHexIntegers,
            rulesFile = state.viewRulesFile.orEmpty().trim().ifEmpty { VariableViewSettings.DEFAULT_RULES_FILE },
        )
        set(value) {
            state.viewSort = value.sort
            state.viewGroupMethods = value.groupMethods
            state.viewShowInherited = value.showInherited
            state.viewShowMetatable = value.showMetatable
            state.viewToString = value.toString
            state.viewHideStdGlobals = value.hideStdGlobals
            state.viewShowTemporaries = value.showTemporaries
            state.viewPageSize = value.pageSize.coerceIn(VariableViewSettings.PAGE_SIZE_RANGE)
            state.viewMaxValueLength = value.maxValueLength.coerceIn(VariableViewSettings.MAX_VALUE_LENGTH_RANGE)
            state.viewHexIntegers = value.hexIntegers
            state.viewRulesFile = value.rulesFile.trim()
        }

    /** 展开 $PROJECT_DIR$ 后的搜索目录。 */
    fun expandedSymbolSearchDirs(project: Project?): List<String> {
        val base = project?.basePath
        return symbolSearchDirs.map { dir ->
            if (base != null) dir.replace("\$PROJECT_DIR\$", base) else dir
        }
    }

    companion object {
        const val DEFAULT_SYMBOL_DIRS = "\$PROJECT_DIR\$/Binaries/Android;\$PROJECT_DIR\$/Intermediate/Android"

        fun getInstance(): LuaDebugSettings = service()
    }
}

enum class LuaDebugLogLevel {
    /** 默认：只显示插件的必要信息（连接、断开、出错、出错暂停）和日志断点，不显示 Lua 的 print 和断点细节。 */
    COMPACT,
    /** 加上 Lua 自己的输出（print、stderr）。 */
    RUNTIME,
    /** 再加上注入过程、断点下发 / 生效、暂停位置、runtime 的诊断提示。 */
    VERBOSE,
    /** 打印每一帧协议，排查问题用。 */
    PROTOCOL,
}

/** 字段段排序（variable-view.md 5.1）。 */
enum class LuaVariableSort(val wire: String) {
    /** 标识符自然序 → 其他字符串 → 数字 → boolean → 其他。 */
    SMART("smart"),
    /** 纯字典序。 */
    NAME("name"),
    /** `next` 原始顺序。 */
    NONE("none"),
}

/** 什么时候调用 `__tostring`（variable-view.md 第 12 节）。 */
enum class LuaToStringPolicy(val wire: String) {
    /** 只对 table；userdata 交给 helper / 宿主（UnLua 失效对象上调用可能崩）。 */
    TABLES("tables"),
    ALL("all"),
    NEVER("never"),
}

/** 变量视图设置的不可变快照，引擎、渲染层、MCP 共用。 */
data class VariableViewSettings(
    val sort: LuaVariableSort = LuaVariableSort.SMART,
    val groupMethods: Boolean = true,
    val showInherited: Boolean = true,
    val showMetatable: Boolean = true,
    val toString: LuaToStringPolicy = LuaToStringPolicy.TABLES,
    val hideStdGlobals: Boolean = true,
    val showTemporaries: Boolean = false,
    /** IDE：`variables.count`。 */
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    /** IDE：`variables.maxValueLength`。 */
    val maxValueLength: Int = DEFAULT_MAX_VALUE_LENGTH,
    /** IDE：整数同时显示十六进制。 */
    val hexIntegers: Boolean = false,
    /** IDE：显示规则文件，相对项目根。 */
    val rulesFile: String = DEFAULT_RULES_FILE,
) {
    /** `setOptions.variableView` 的内容（协议增量 13.5），只含 runtime 关心的字段。 */
    fun toProtocolOptions(): Map<String, Any> = linkedMapOf(
        "sort" to sort.wire,
        "groupMethods" to groupMethods,
        "showInherited" to showInherited,
        "showMetatable" to showMetatable,
        "toString" to toString.wire,
        "hideStdGlobals" to hideStdGlobals,
        "showTemporaries" to showTemporaries,
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val DEFAULT_MAX_VALUE_LENGTH = 256
        const val DEFAULT_RULES_FILE = ".emmylua/debug-view.json"
        /** 协议 9.3：`count` 最大 1000。 */
        val PAGE_SIZE_RANGE = 10..1000
        /** 协议增量 13.3：`maxValueLength` 上限 1024。 */
        val MAX_VALUE_LENGTH_RANGE = 32..1024
    }
}

/** 按项目记住的东西：「IDE 的 MCP 服务器没开」提示是否不再弹、最近附加过的进程。 */
@Service(Service.Level.PROJECT)
@State(name = "EmmyLuaDebugProjectState", storages = [Storage("emmylua-debug.xml")])
class LuaDebugProjectState : SimplePersistentStateComponent<LuaDebugProjectState.State>(State()) {

    class State : BaseState() {
        var mcpPromptDismissed by property(false)
        /** 最近附加过的可执行文件路径（或进程名），新的在前。 */
        var recentAttachTargets by list<String>()
    }

    var mcpPromptDismissed: Boolean
        get() = state.mcpPromptDismissed
        set(value) { state.mcpPromptDismissed = value }

    val recentAttachTargets: List<String> get() = state.recentAttachTargets.toList()

    fun rememberAttach(key: String) {
        val list = state.recentAttachTargets
        list.remove(key)
        list.add(0, key)
        while (list.size > MAX_RECENT) list.removeAt(list.size - 1)
        state.intIncrementModificationCount()
    }

    companion object {
        private const val MAX_RECENT = 5

        fun getInstance(project: Project): LuaDebugProjectState = project.service()
    }
}
