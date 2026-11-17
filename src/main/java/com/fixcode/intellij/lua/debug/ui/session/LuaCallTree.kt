package com.fixcode.intellij.lua.debug.ui.session

/**
 * 「Lua 采样」页的调用树和火焰图共用的模型：由折叠栈（每行「帧;帧;帧 样本数」，根在前）合并成一棵树。
 * 帧标签是 runtime 的 `名字 (文件:定义行)`、C 函数 `名字 [C]`，另有 `<截断>`、`<其他>` 这类占位。
 */
internal class LuaCallNode(val label: String, val parent: LuaCallNode?) {
    var total: Long = 0
    var self: Long = 0
    private val byLabel = LinkedHashMap<String, LuaCallNode>()

    /** 子节点按总样本数从大到小。 */
    val children: List<LuaCallNode> by lazy { byLabel.values.sortedByDescending { it.total } }

    /** 以这个节点为根的子树有几层（含自己）。 */
    val height: Int by lazy { 1 + (children.maxOfOrNull { it.height } ?: 0) }

    val depth: Int get() = parent?.let { it.depth + 1 } ?: 0

    /** 从根往下的标签路径（不含根），刷新后按它找回同一个节点。 */
    val path: List<String> get() = generateSequence(this) { it.parent }.toList().dropLast(1).map { it.label }.reversed()

    /** 函数名部分（去掉末尾的 ` (文件:行)`）。 */
    val name: String get() = splitLabel(label).first

    /** 位置部分（`文件:行`、`[C]`），没有时为空串。 */
    val location: String get() = splitLabel(label).second

    fun find(path: List<String>): LuaCallNode? = path.fold(this as LuaCallNode?) { node, label -> node?.byLabel?.get(label) }

    internal fun child(label: String) = byLabel.getOrPut(label) { LuaCallNode(label, this) }

    companion object {
        fun splitLabel(label: String): Pair<String, String> {
            if (label.endsWith(" [C]")) return label.removeSuffix(" [C]") to "[C]"
            if (!label.endsWith(")")) return label to ""
            val cut = label.lastIndexOf(" (")
            if (cut <= 0) return label to ""
            return label.substring(0, cut) to label.substring(cut + 2, label.length - 1)
        }
    }
}

internal object LuaCallTree {
    const val ROOT = "<全部>"

    fun parse(folded: String): LuaCallNode {
        val root = LuaCallNode(ROOT, null)
        for (line in folded.lineSequence()) {
            val cut = line.lastIndexOf(' ')
            if (cut <= 0) continue
            val samples = line.substring(cut + 1).trim().toLongOrNull() ?: continue
            if (samples <= 0) continue
            var node = root
            root.total += samples
            for (frame in line.substring(0, cut).split(';')) {
                if (frame.isEmpty()) continue
                node = node.child(frame)
                node.total += samples
            }
            node.self += samples
        }
        return root
    }
}
