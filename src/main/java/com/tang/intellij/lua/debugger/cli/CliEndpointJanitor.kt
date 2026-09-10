package com.tang.intellij.lua.debugger.cli

import java.nio.file.Files
import java.nio.file.Path

class CliEndpointJanitor(private val descriptorPath: Path) {
    fun write(descriptor: CliInstanceDescriptor) {
        CliInstanceDirectory.writeAtomic(descriptorPath, descriptor)
    }

    fun read(): CliInstanceDescriptor? = CliInstanceDirectory.read(descriptorPath)

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

    companion object {
        /** Remove descriptors left by crashed IDEA processes, retaining live instances. */
        fun removeStaleDescriptors(root: Path, isAlive: (Long) -> Boolean = { pid ->
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }) {
            if (!Files.isDirectory(root)) return
            Files.list(root).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") }.forEach { path ->
                    val descriptor = CliInstanceDirectory.read(path)
                    if (descriptor == null || !isAlive(descriptor.pid)) {
                        runCatching { Files.deleteIfExists(path) }
                        descriptor?.tokenFile?.let { token ->
                            val tokenPath = runCatching { Path.of(token).toAbsolutePath().normalize() }.getOrNull()
                            if (tokenPath != null && tokenPath.parent == path.parent?.toAbsolutePath()?.normalize()) {
                                runCatching { Files.deleteIfExists(tokenPath) }
                            }
                        }
                    }
                }
            }
        }

        /** Best-effort owner-only permissions on POSIX systems; Windows uses the user profile ACL. */
        fun restrictToOwner(path: Path) {
            runCatching {
                val permissions = setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                )
                Files.setPosixFilePermissions(path, permissions)
            }
        }
    }
}
