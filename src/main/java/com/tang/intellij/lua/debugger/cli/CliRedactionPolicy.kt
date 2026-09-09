package com.tang.intellij.lua.debugger.cli

object CliRedactionPolicy {
    fun redactDescriptor(descriptor: CliInstanceDescriptor): CliInstanceDescriptor {
        return descriptor.copy(endpoint = "<redacted>", tokenFile = "<redacted>")
    }

    fun redactProjectName(projectName: String, trusted: Boolean): String =
        if (trusted) projectName else "<redacted>"
}
