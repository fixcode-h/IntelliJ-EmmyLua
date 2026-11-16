/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fixcode.intellij.lua.debugger

import com.fixcode.intellij.lua.debug.engine.SourcePaths
import com.fixcode.intellij.lua.debug.xdebugger.IdeaSourceFileIndex
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.util.regex.Pattern

/**
 *
 * Created by tangzx on 2017/6/10.
 */
class LuaTracebackFilter(private val project: Project) : Filter {
    private val index = IdeaSourceFileIndex(project)

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        //lua.exe: Test.lua:3: attempt to call global 'print1' (a nil value)
        //stack traceback:
        //Test.lua:3: in function 'a'
        //Test.lua:7: in function 'b'
        //Test.lua:11: in main chunk

        val pattern = Pattern.compile("\\s*((/+)?[^<>\\\\|:\"*? ]+):(\\d+):")
        val matcher = pattern.matcher(line)
        if (matcher.find()) {
            val fileName = matcher.group(1)
            val lineNumber = Integer.parseInt(matcher.group(3))
            val file = findFile(fileName)
            if (file != null) {
                val hyperlink = OpenFileHyperlinkInfo(project, file, lineNumber - 1)
                val textStartOffset = entireLength - line.length
                val startPos = matcher.start(1)
                val endPos = matcher.end(3) + 1
                return Filter.Result(startPos + textStartOffset, endPos + textStartOffset, hyperlink)
            }
        }
        return null
    }

    /**
     * traceback 里的文件名多半是相对路径或被截断的 chunkname，和调试器映射源码用同一套办法找：
     * 绝对路径直接看磁盘，否则按文件名索引 + 路径段后缀匹配（扩展名 .lua/.txt/.bytes 都认）。
     */
    private fun findFile(fileName: String): VirtualFile? {
        val path = SourcePaths.normalize(fileName.removePrefix("@"))
        if (SourcePaths.isAbsolute(path)) {
            LocalFileSystem.getInstance().findFileByPath(path)?.let { return it }
        }
        var segments = SourcePaths.segments(path, caseSensitive = false)
        // Lua 截断过长的 chunkname 时以 "..." 开头，第一段是残缺的，不能拿来比
        if (path.startsWith("...")) segments = segments.drop(1)
        if (segments.isEmpty()) return null
        val found = try {
            index.findBySuffix(segments, caseSensitive = false)
        } catch (_: IndexNotReadyException) {
            // 索引还在建（dumb mode），这一行先不加链接
            return null
        }
        // 多个候选时取路径最短的：通常是项目里的源码，而不是打包、备份出来的副本
        val best = found.minByOrNull { it.length } ?: return null
        return LocalFileSystem.getInstance().findFileByPath(best)
    }
}
