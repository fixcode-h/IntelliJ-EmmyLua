package com.tang.intellij.lua.debugger

import com.tang.intellij.lua.debugger.emmy.Stack
import com.tang.intellij.lua.debugger.emmy.VariableValue
import com.tang.intellij.lua.debugger.luapanda.LuaPandaStack
import com.tang.intellij.lua.debugger.luapanda.LuaPandaVariable

internal fun Stack.toInlineSnapshot(filePath: String, line: Int): DebugFrameSnapshot {
    val variables = linkedMapOf<String, DebugValueSnapshot>()
    upvalueVariables.forEach { variables[it.nameValue] = it.toInlineSnapshot() }
    localVariables.forEach { variables[it.nameValue] = it.toInlineSnapshot() }
    return DebugFrameSnapshot(filePath, line, variables.toMap())
}

private fun VariableValue.toInlineSnapshot(): DebugValueSnapshot {
    val childSnapshots = linkedMapOf<String, DebugValueSnapshot>()
    children.orEmpty().forEach { child ->
        childSnapshots[child.nameValue] = child.toInlineSnapshot()
    }
    return DebugValueSnapshot(
        name = nameValue,
        value = value,
        type = valueTypeName.ifBlank { valueTypeValue.name },
        children = childSnapshots.toMap()
    )
}

internal fun LuaPandaStack.toInlineSnapshot(filePath: String, line: Int): DebugFrameSnapshot {
    val variables = linkedMapOf<String, DebugValueSnapshot>()
    upvalues.orEmpty().forEach { variables[it.name] = it.toInlineSnapshot() }
    locals.orEmpty().forEach { variables[it.name] = it.toInlineSnapshot() }
    return DebugFrameSnapshot(filePath, line, variables.toMap())
}

private fun LuaPandaVariable.toInlineSnapshot(): DebugValueSnapshot {
    val childSnapshots = linkedMapOf<String, DebugValueSnapshot>()
    children.orEmpty().forEach { child ->
        childSnapshots[child.name] = child.toInlineSnapshot()
    }
    return DebugValueSnapshot(name, value, type, childSnapshots.toMap())
}
