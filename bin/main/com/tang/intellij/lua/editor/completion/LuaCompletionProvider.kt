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

package com.tang.intellij.lua.editor.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.util.ProcessingContext

abstract class LuaCompletionProvider : CompletionProvider<CompletionParameters>() {

    protected var session: CompletionSession? = null

    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, resultSet: CompletionResultSet) {
        try {
            val session = CompletionSession[parameters]
            this.session = session
            addCompletions(session)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            // ⚠️ ProcessCanceledException 必须重新抛出，不能被捕获
            // 这是 IntelliJ 用来取消长时间运行操作的机制
            throw e
        } catch (e: Exception) {
            // 🛡️ 捕获其他异常，防止影响后续Provider
            // 但允许补全继续执行
        }
    }

    abstract fun addCompletions(session: CompletionSession)
}