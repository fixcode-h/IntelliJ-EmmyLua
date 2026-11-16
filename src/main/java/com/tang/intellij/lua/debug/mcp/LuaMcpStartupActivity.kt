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

package com.tang.intellij.lua.debug.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开时确保 MCP 服务在跑（应用级，只会真正启动一次）。
 * 「没安装时提示」由 UI 的 LuaMcpInstallPromptActivity 负责，它通过 LuaMcpService 查安装状态。
 */
class LuaMcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LuaMcpServer.getInstance().applySettings()
    }
}
