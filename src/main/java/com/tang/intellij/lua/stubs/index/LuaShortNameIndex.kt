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

import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.search.SearchContext

/**
 *
 * Created by TangZX on 2017/1/19.
 */
class LuaShortNameIndex : StringStubIndexExtension<NavigatablePsiElement>() {

    override fun getVersion(): Int {
        return LuaLanguage.INDEX_VERSION
    }

    override fun getKey() = StubKeys.SHORT_NAME

    companion object {
        val instance = LuaShortNameIndex()

        fun find(key: String, searchContext: SearchContext): Collection<NavigatablePsiElement> {
            if (searchContext.isDumb) return emptyList()
            return try {
                StubIndex.getElements(StubKeys.SHORT_NAME, key, searchContext.project, searchContext.scope, NavigatablePsiElement::class.java)
            } catch (e: Throwable) {
                // 索引不同步时静默处理
                emptyList()
            }
        }
    }
}
