package com.tang.intellij.lua.debug.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMapperTest {
    private class MemoryIndex(vararg files: String) : SourceFileIndex {
        val files = files.map(SourcePaths::normalize)
        var searches = 0
        override fun exists(path: String) = SourcePaths.normalize(path) in files
        override fun findBySuffix(segments: List<String>, caseSensitive: Boolean): List<String> {
            searches++
            return files.filter { SourcePaths.segments(it, caseSensitive).takeLast(segments.size) == segments }
        }
        override fun sha256(path: String): String? = null
    }

    @Test
    fun `路径规范化沿用旧 SourceIdentity 的规则`() {
        assertEquals("/project/file.lua", SourcePaths.normalize("/../project/dir/../../project/file.lua"))
        assertEquals("c:/project/file.lua", SourcePaths.normalize("C:/../project/dir/../file.lua"))
        assertEquals("../file.lua", SourcePaths.normalize("dir/../../file.lua"))
        // 盘符统一小写；其余大小写保留，比较时按平台决定是否忽略（协议 7.2）
        assertEquals("c:/Project/SCRIPT.lua", SourcePaths.normalize("C:\\Project\\sub\\..\\SCRIPT.lua"))
        assertEquals("a/b.lua", SourcePaths.normalize("./a//b.lua"))
    }

    @Test
    fun `非文件 chunk 不映射`() {
        val mapper = SourceMapper(index = MemoryIndex())
        assertEquals(SourceResolution.NotAFile, mapper.resolve("=[C]"))
        assertEquals(SourceResolution.NotAFile, mapper.resolve("[string \"return 1\"]"))
    }

    @Test
    fun `规则表按最长前缀匹配`() {
        val index = MemoryIndex("D:/Game/Content/Script/UI/Bag.lua", "D:/Override/UI/Bag.lua")
        val mapper = SourceMapper(
            listOf(MappingRule("Content/Script", "D:/Game/Content/Script"), MappingRule("Content/Script/UI", "D:/Override/UI")),
            index, autoLearn = false
        )
        assertEquals(SourceResolution.Mapped("d:/Override/UI/Bag.lua"), mapper.resolve("@Content/Script/UI/Bag.lua"))
    }

    @Test
    fun `自动学习唯一匹配并记成规则`() {
        val index = MemoryIndex("D:/Game/Content/Script/UI/Bag.lua", "D:/Game/Content/Script/UI/Shop.lua")
        val mapper = SourceMapper(index = index)
        val bag = mapper.resolve("@Content/Script/UI/Bag.lua")
        assertEquals(SourceResolution.Mapped("d:/Game/Content/Script/UI/Bag.lua", learned = true), bag)
        assertTrue(mapper.rules().any { it.localDirectory == "d:/Game" })

        val searches = index.searches
        assertEquals(SourceResolution.Mapped("d:/Game/Content/Script/UI/Shop.lua"), mapper.resolve("@Content/Script/UI/Shop.lua"))
        assertEquals(searches, index.searches) // 走学到的规则，不再搜索
    }

    @Test
    fun `多个候选时交给用户选 选过后记住`() {
        val index = MemoryIndex("D:/a/ui/main.lua", "D:/b/ui/main.lua")
        val mapper = SourceMapper(index = index)
        val resolution = mapper.resolve("@ui/main.lua")
        assertEquals(SourceResolution.Ambiguous(listOf("d:/a/ui/main.lua", "d:/b/ui/main.lua")), resolution)

        mapper.override("@ui/main.lua", "D:/b/ui/main.lua")
        assertEquals("d:/b/ui/main.lua", mapper.localPathOf("@ui/main.lua"))
        assertEquals("@ui/main.lua", mapper.knownChunkFor("d:/b/ui/main.lua"))
        assertNull(mapper.knownChunkFor("d:/a/ui/main.lua"))
    }

    @Test
    fun `大小写不敏感时按忽略大小写匹配`() {
        val index = MemoryIndex("D:/Game/Script/Bag.lua")
        val mapper = SourceMapper(index = index, caseSensitive = false)
        assertEquals("d:/Game/Script/Bag.lua", mapper.localPathOf("@script/bag.lua"))
    }
}
