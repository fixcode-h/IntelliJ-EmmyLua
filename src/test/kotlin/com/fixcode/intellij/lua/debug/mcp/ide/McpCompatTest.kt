package com.fixcode.intellij.lua.debug.mcp.ide

import com.fixcode.intellij.lua.debugger.mcp.EmmyLuaDebugMcp
import com.fixcode.intellij.lua.debugger.mcp.protocol.ControlDecision
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpConfirmer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

/**
 * [IdeMcpCompat] 能不能在每个 IDE 版本的 MCP 服务器接口上构造出工具描述和结果。
 *
 * 编译用的 2025.2 总是测；其他版本要把各版本 IDE 的 `plugins/mcpserver*` 里的 jar 放进
 * `EMMY_MCPSERVER_JARS/<版本>/`（2026.2 起还要 MCP Kotlin SDK 的 jar），没设置时跳过。
 */
class McpCompatTest {
    private val tools = EmmyLuaDebugMcp({ null }, object : McpConfirmer {
        override fun confirmControl(subject: String, clientName: String?) = ControlDecision.DENY
        override fun confirmLaunch(description: String, clientName: String?) = false
        override fun luaEvalAllowed() = false
    }).dispatcher.tools

    @Test
    fun `编译用的版本：描述和结果都能构造`() {
        for (tool in tools) {
            val d = IdeMcpCompat.descriptor(tool)
            assertEquals(tool.name, d.name)
            assertEquals(tool.description, d.description)
        }
        val r = IdeMcpCompat.result(arrayOf(com.intellij.mcpserver.McpToolCallResultContent.Text("{}")), true)
        assertTrue(r.isError)
    }

    @Test
    fun `各版本 IDE 的 MCP 服务器接口`() {
        val root = System.getenv("EMMY_MCPSERVER_JARS")?.let(::File)
        assumeTrue("没有设置 EMMY_MCPSERVER_JARS，跳过多版本验证", root != null && root.isDirectory)
        val versions = root!!.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue(versions.isNotEmpty())
        for (dir in versions) {
            val jars = dir.listFiles { f -> f.name.endsWith(".jar") }!!.map { it.toURI().toURL() }
            ChildFirstLoader(jars.toTypedArray(), javaClass.classLoader).use { loader ->
                for (tool in tools) {
                    val d = IdeMcpCompat.descriptor(tool, loader)
                    assertEquals("${dir.name} ${tool.name}", tool.name, get(d, "getName"))
                    assertEquals(dir.name, tool.description, get(d, "getDescription"))
                    runCatching { d.javaClass.getMethod("getTitle") }.getOrNull()?.let {
                        assertEquals(dir.name, tool.title, it.invoke(d))
                    }
                    runCatching { d.javaClass.getMethod("getFullyQualifiedName") }.getOrNull()?.let {
                        assertEquals(dir.name, "${IdeMcpCompat.CATEGORY_FQN}.${tool.name}", it.invoke(d))
                    }
                    val schema = get(d, "getInputSchema")!!
                    val text = schema.javaClass.getMethod("prettyPrint").invoke(schema) as String
                    assertTrue("${dir.name}: $text", text.contains("\"action\"") && text.contains("\"enum\""))
                    runCatching { d.javaClass.getMethod("getAnnotations") }.getOrNull()?.let {
                        val a = it.invoke(d)!!
                        assertEquals(dir.name, tool.name == "lua_debug_inspect", a.javaClass.getMethod("getReadOnlyHint").invoke(a))
                    }
                }
                val textCls = loader.loadClass("com.intellij.mcpserver.McpToolCallResultContent\$Text")
                val text = textCls.getConstructor(String::class.java).newInstance("{\"x\":1}")
                val r = IdeMcpCompat.result(arrayOf(text), true, loader)
                assertEquals(dir.name, true, get(r, "isError"))
                assertEquals(dir.name, 1, (get(r, "getContent") as Array<*>).size)
                println("MCP 接口兼容：${dir.name} 通过")
            }
        }
    }

    private fun get(o: Any, method: String): Any? = o.javaClass.getMethod(method).invoke(o)

    /** MCP 服务器和 MCP SDK 的类优先从版本 jar 取（测试类路径上有 2025.2 的同名类），其余交给父加载器。 */
    private class ChildFirstLoader(urls: Array<java.net.URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!name.startsWith("com.intellij.mcpserver.") && !name.startsWith("io.modelcontextprotocol.")) {
                return super.loadClass(name, resolve)
            }
            findLoadedClass(name) ?: try {
                findClass(name)
            } catch (e: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }
    }
}
