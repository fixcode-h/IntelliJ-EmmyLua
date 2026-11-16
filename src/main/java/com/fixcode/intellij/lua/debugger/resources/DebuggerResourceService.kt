package com.fixcode.intellij.lua.debugger.resources

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DebuggerResourceService {
    private const val PLUGIN_ID = "com.fixcode.emmylua.enhanced"
    // emmy_tool attach -protocol 3 注入 emmy_attach.dll，它动态链接 EasyHook.dll；旧的 emmy_core.dll / emmy_hook.dll 已删除
    private val nativeFiles = listOf("EasyHook.dll", "emmy_tool.exe")
        .flatMap { file -> listOf("x86/$file", "x64/$file") }
    /** 新附加链路（协议 v3）的文件：出包流水线从 native/ 现编后放进来，本地开发构建可能没有，所以缺了不报错。 */
    private val optionalNativeFiles = (listOf("emmy_attach.dll") + listOf("51", "52", "53", "54").map { "lua$it/emmy_debugger_core.dll" })
        .flatMap { file -> listOf("x86/$file", "x64/$file") }
    private val extractionLocks = ConcurrentHashMap<Path, Any>()

    fun emmyNativeToolsDirectory(): Path = extract(
        resourceRoot = "debugger/emmy/windows",
        relativeFiles = nativeFiles,
        optionalFiles = optionalNativeFiles,
        platform = "windows",
        arch = "multi-arch"
    )

    private fun extract(
        resourceRoot: String,
        relativeFiles: List<String>,
        platform: String,
        arch: String,
        optionalFiles: List<String> = emptyList()
    ): Path {
        val loader = DebuggerResourceService::class.java.classLoader
        val resources = relativeFiles.associateWith { relativePath ->
            val resourcePath = "$resourceRoot/$relativePath"
            loader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                ?: error("Missing debugger resource: $resourcePath")
        } + optionalFiles.mapNotNull { relativePath ->
            loader.getResourceAsStream("$resourceRoot/$relativePath")?.use { relativePath to it.readBytes() }
        }
        val version = pluginVersion().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val parent = Path.of(PathManager.getSystemPath(), "emmylua", "debugger-resources", version, platform, arch)
        return extractResources(resources, parent)
    }

    internal fun extractResources(resources: Map<String, ByteArray>, parent: Path): Path {
        require(resources.isNotEmpty()) { "Debugger resources must not be empty" }
        val hash = contentHash(resources)
        val target = parent.resolve(hash)
        if (isComplete(target, resources)) return target

        Files.createDirectories(parent)
        val normalizedParent = parent.toAbsolutePath().normalize()
        val localLock = extractionLocks.computeIfAbsent(normalizedParent) { Any() }
        synchronized(localLock) {
            val lockPath = parent.resolve(".extract.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    if (isComplete(target, resources)) return target
                    if (Files.exists(target)) {
                        repairIncompleteDirectory(resources, target)
                    } else {
                        publishDirectory(resources, parent, target, hash)
                    }
                }
            }
        }
        check(isComplete(target, resources)) { "Debugger resource extraction is incomplete: $target" }
        return target
    }

    private fun publishDirectory(resources: Map<String, ByteArray>, parent: Path, target: Path, hash: String) {
        val temporary = parent.resolve(".$hash.tmp-${UUID.randomUUID()}")
        try {
            for ((relativePath, bytes) in resources) {
                writeResource(temporary.resolve(relativePath), relativePath, bytes)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                deleteRecursively(temporary)
                repairIncompleteDirectory(resources, target)
            }
        } catch (error: Throwable) {
            deleteRecursively(temporary)
            throw error
        }
    }

    private fun repairIncompleteDirectory(resources: Map<String, ByteArray>, target: Path) {
        Files.createDirectories(target)
        for ((relativePath, bytes) in resources) {
            val output = target.resolve(relativePath)
            if (hasExpectedContent(output, bytes)) continue

            Files.createDirectories(output.parent)
            val staged = output.resolveSibling(".${output.fileName}.tmp-${UUID.randomUUID()}")
            try {
                writeResource(staged, relativePath, bytes)
                try {
                    Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(staged)
            }
        }
    }

    private fun writeResource(output: Path, relativePath: String, bytes: ByteArray) {
        Files.createDirectories(output.parent)
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW)
        if (relativePath.endsWith(".exe")) output.toFile().setExecutable(true)
    }

    internal fun contentHash(resources: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((path, bytes) in resources.toSortedMap()) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "dev"

    private fun isComplete(directory: Path, resources: Map<String, ByteArray>): Boolean =
        Files.isDirectory(directory) && resources.all { (relativePath, bytes) ->
            hasExpectedContent(directory.resolve(relativePath), bytes)
        }

    private fun hasExpectedContent(path: Path, expected: ByteArray): Boolean =
        Files.isRegularFile(path) &&
            Files.size(path) == expected.size.toLong() &&
            Files.readAllBytes(path).contentEquals(expected)

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
