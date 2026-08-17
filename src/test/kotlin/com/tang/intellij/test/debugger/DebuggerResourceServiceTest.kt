package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.resources.DebuggerResourceService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DebuggerResourceServiceTest {
    @Test
    fun `content hash is deterministic and content sensitive`() {
        val first = linkedMapOf("b.lua" to bytes("b"), "a.lua" to bytes("a"))
        val reordered = linkedMapOf("a.lua" to bytes("a"), "b.lua" to bytes("b"))
        val changed = linkedMapOf("a.lua" to bytes("changed"), "b.lua" to bytes("b"))

        assertEquals(DebuggerResourceService.contentHash(first), DebuggerResourceService.contentHash(reordered))
        assertNotEquals(DebuggerResourceService.contentHash(first), DebuggerResourceService.contentHash(changed))
    }

    @Test
    fun `concurrent extraction publishes one complete immutable directory`() {
        val parent = Files.createTempDirectory("emmylua-resource-test")
        val resources = linkedMapOf(
            "emmyHelper.lua" to bytes("return {}"),
            "tool/emmyLog.lua" to bytes("return true")
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..16).map {
                executor.submit<Path> { DebuggerResourceService.extractResources(resources, parent) }
            }
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.distinct().size)
            val target = results.first()
            assertArrayEquals(resources.getValue("emmyHelper.lua"), Files.readAllBytes(target.resolve("emmyHelper.lua")))
            assertArrayEquals(resources.getValue("tool/emmyLog.lua"), Files.readAllBytes(target.resolve("tool/emmyLog.lua")))
            Files.list(parent).use { paths ->
                assertTrue(paths.noneMatch { it.fileName.toString().contains(".tmp-") })
            }
        } finally {
            executor.shutdownNow()
            deleteRecursively(parent)
        }
    }

    @Test
    fun `extraction repairs an existing incomplete content directory`() {
        val parent = Files.createTempDirectory("emmylua-resource-repair-test")
        val resources = linkedMapOf(
            "x86/emmy_tool.exe" to bytes("x86-tool"),
            "x86/emmy_hook.dll" to bytes("x86-hook"),
            "x64/emmy_tool.exe" to bytes("x64-tool"),
            "x64/emmy_hook.dll" to bytes("x64-hook")
        )
        val target = parent.resolve(DebuggerResourceService.contentHash(resources))
        try {
            Files.createDirectories(target.resolve("x64"))
            Files.write(target.resolve("x64/emmy_hook.dll"), resources.getValue("x64/emmy_hook.dll"))
            Files.write(target.resolve("x64/emmy_tool.exe"), bytes("bad-tool"))

            val extracted = DebuggerResourceService.extractResources(resources, parent)

            assertEquals(target, extracted)
            for ((relativePath, expected) in resources) {
                assertArrayEquals(expected, Files.readAllBytes(extracted.resolve(relativePath)))
            }
            Files.walk(target).use { paths ->
                assertTrue(paths.noneMatch { it.fileName.toString().contains(".tmp-") })
            }
        } finally {
            deleteRecursively(parent)
        }
    }

    private fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
