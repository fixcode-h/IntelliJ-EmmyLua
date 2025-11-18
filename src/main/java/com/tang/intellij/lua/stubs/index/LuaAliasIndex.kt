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

package com.tang.intellij.lua.stubs.index

import com.intellij.openapi.project.Project
import com.intellij.psi.search.ProjectAndLibrariesScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.Processor
import com.tang.intellij.lua.comment.psi.LuaDocTagAlias
import com.tang.intellij.lua.search.SearchContext

class LuaAliasIndex : StringStubIndexExtension<LuaDocTagAlias>() {
    companion object {
        val instance = LuaAliasIndex()

        fun find(name: String, context: SearchContext): LuaDocTagAlias? {
            if (context.isDumb)
                return null
            return try {
                StubIndex.getElements(StubKeys.ALIAS, name, context.project, context.scope, LuaDocTagAlias::class.java).firstOrNull()
            } catch (e: Throwable) {
                // 索引不同步时静默处理
                null
            }
        }
        
        fun processAllKeys(project: Project, processor: Processor<String>): Boolean {
            val scope = ProjectAndLibrariesScope(project)
            val allKeys = instance.getAllKeys(project)
            for (key in allKeys) {
                if (!processor.process(key))
                    return false
            }
            return true
        }
    }

    override fun getKey(): StubIndexKey<String, LuaDocTagAlias> {
        return StubKeys.ALIAS
    }
}