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

package com.tang.intellij.lua.debug.ui

import com.intellij.icons.AllIcons
import com.tang.intellij.lua.lang.LuaIcons
import javax.swing.Icon

object LuaDebugIcons {
    val LUA: Icon get() = LuaIcons.FILE
    val ATTACH: Icon get() = AllIcons.Debugger.AttachToProcess
    val DEVICE: Icon get() = AllIcons.Nodes.Deploy
    val MCP_RUNNING: Icon get() = AllIcons.General.InspectionsOK
    val MCP_STOPPED: Icon get() = AllIcons.General.InspectionsPause
    val MCP_ERROR: Icon get() = AllIcons.General.Error
    val MCP_NOT_INSTALLED: Icon get() = AllIcons.General.Warning
}
