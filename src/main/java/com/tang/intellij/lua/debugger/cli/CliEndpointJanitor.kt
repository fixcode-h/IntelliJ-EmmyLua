package com.tang.intellij.lua.debugger.cli

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class CliEndpointJanitor(private val descriptorPath: Path) {
    private val gson = Gson()

    fun write(descriptor: CliInstanceDescriptor) {
        Files.createDirectories(descriptorPath.parent)
        val temporary = descriptorPath.resolveSibling(".${descriptorPath.fileName}.tmp")
        Files.write(temporary, gson.toJson(descriptor).toByteArray(StandardCharsets.UTF_8))
        Files.move(
            temporary,
            descriptorPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    fun read(): CliInstanceDescriptor? {
        if (!Files.isRegularFile(descriptorPath)) return null
        return runCatching {
            gson.fromJson(Files.readString(descriptorPath), CliInstanceDescriptor::class.java)
        }.getOrNull()
    }

    fun deleteOwnDescriptor() {
        Files.deleteIfExists(descriptorPath)
    }

    fun removeIfStale(isAlive: (Long) -> Boolean = { pid ->
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }): Boolean {
        val descriptor = read() ?: return false
        if (isAlive(descriptor.pid)) return false
        deleteOwnDescriptor()
        return true
    }
}
