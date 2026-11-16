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

package com.tang.intellij.lua.debug.ui.attach

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.tang.intellij.lua.debug.api.LuaLocalProcess
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import com.tang.intellij.lua.debug.ui.LuaDebugIcons
import com.tang.intellij.lua.debug.ui.process.LuaLocalProcessScanner
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/** 运行「Lua 附加」时选进程的对话框：名字 · pid · 架构 · Lua 版本 · UE 标签，可直接打字过滤，双击附加。 */
class LuaProcessChooser private constructor(project: Project, processes: List<LuaLocalProcess>) : DialogWrapper(project, true) {
    private val list = JBList(processes).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        cellRenderer = object : ColoredListCellRenderer<LuaLocalProcess>() {
            override fun customizeCellRenderer(list: JList<out LuaLocalProcess>, p: LuaLocalProcess, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = if (LuaLocalProcessScanner.isLuaCandidate(p)) LuaDebugIcons.LUA else null
                append(p.name)
                append("  ${p.pid}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val extra = listOfNotNull(p.arch, p.luaVersion?.let { if (it.startsWith("Lua")) it else "Lua $it" }) +
                    LuaLocalProcessScanner.tags(p)
                if (extra.isNotEmpty()) append("  " + extra.joinToString("  "), SimpleTextAttributes.GRAY_ATTRIBUTES)
                p.executablePath?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && selectedValue != null) doOKAction()
            }
        })
    }

    init {
        title = LuaDebugBundle.message("attach.chooser.title")
        setOKButtonText(LuaDebugBundle.message("attach.chooser.ok"))
        ListSpeedSearch.installOn(list) { "${it.name} ${it.pid} ${LuaLocalProcessScanner.tags(it).joinToString(" ")}" }
        init()
    }

    override fun createCenterPanel(): JComponent = ScrollPaneFactory.createScrollPane(list).apply {
        preferredSize = JBUI.size(640, 360)
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    companion object {
        /** 模态对话框，在 EDT 上调；取消返回 null。 */
        fun choose(project: Project, processes: List<LuaLocalProcess>): LuaLocalProcess? {
            val dialog = LuaProcessChooser(project, processes)
            return if (dialog.showAndGet()) dialog.list.selectedValue else null
        }
    }
}
