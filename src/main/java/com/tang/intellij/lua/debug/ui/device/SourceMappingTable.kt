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

package com.tang.intellij.lua.debug.ui.device

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.AnActionButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import javax.swing.JComponent

/** 表格里的一行；编辑时用可变对象，应用到配置时再转成 BaseState。 */
data class SourceMappingRow(var devicePrefix: String = "", var localDirectory: String = "")

/** 「设备路径前缀 → 本地目录」表，带增删和"选择本地目录"按钮。 */
class SourceMappingTable(private val project: Project) : Disposable {
    private val model = ListTableModel<SourceMappingRow>(
        textColumn(LuaDebugBundle.message("device.mapping.prefix"), { it.devicePrefix }, { r, v -> r.devicePrefix = v }),
        textColumn(LuaDebugBundle.message("device.mapping.local"), { it.localDirectory }, { r, v -> r.localDirectory = v }),
    )
    private val table = TableView(model).apply {
        setShowGrid(false)
        emptyText.text = LuaDebugBundle.message("device.mapping.empty")
        visibleRowCount = 4
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            // 新行默认给个 UE 常见的例子，用户改一下就行
            val row = if (model.rowCount == 0) SourceMappingRow("Content/Script/", "\$PROJECT_DIR\$/Content/Script/")
            else SourceMappingRow()
            model.addRow(row)
            val index = model.rowCount - 1
            table.selectionModel.setSelectionInterval(index, index)
            table.editCellAt(index, 0)
        }
        .setRemoveAction {
            val rows = table.selectedRows.sortedDescending()
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            rows.forEach { model.removeRow(it) }
        }
        .addExtraAction(object : AnActionButton(LuaDebugBundle.message("device.mapping.browse"), AllIcons.Actions.MenuOpen) {
            override fun actionPerformed(e: AnActionEvent) = browseLocal()
            override fun isEnabled(): Boolean = table.selectedRow >= 0
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })
        .setPreferredSize(JBUI.size(500, 110))
        .createPanel()

    fun setRows(rows: List<SourceMappingRow>) {
        model.items = rows.map { it.copy() }.toMutableList()
    }

    fun rows(): List<SourceMappingRow> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        return model.items.map { it.copy() }
    }

    private fun browseLocal() {
        val index = table.selectedRow.takeIf { it >= 0 } ?: return
        val row = model.getItem(table.convertRowIndexToModel(index))
        val dir = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(LuaDebugBundle.message("device.mapping.browse")),
            project, null,
        ) ?: return
        val base = project.basePath
        row.localDirectory = if (base != null && dir.path.startsWith(base)) {
            LuaDeviceConfiguration.PROJECT_DIR + dir.path.removePrefix(base) + "/"
        } else dir.path + "/"
        model.fireTableRowsUpdated(index, index)
    }

    override fun dispose() {}

    private fun textColumn(
        name: String,
        get: (SourceMappingRow) -> String,
        set: (SourceMappingRow, String) -> Unit,
    ) = object : ColumnInfo<SourceMappingRow, String>(name) {
        override fun valueOf(item: SourceMappingRow): String = get(item)
        override fun isCellEditable(item: SourceMappingRow): Boolean = true
        override fun setValue(item: SourceMappingRow, value: String?) = set(item, value.orEmpty())
    }
}
