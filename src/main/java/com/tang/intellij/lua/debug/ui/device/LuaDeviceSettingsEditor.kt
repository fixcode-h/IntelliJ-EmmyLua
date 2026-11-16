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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.not
import com.intellij.ui.layout.selected
import com.intellij.util.Alarm
import com.tang.intellij.lua.debug.api.LuaCheckStatus
import com.tang.intellij.lua.debug.api.LuaDebugTarget
import com.tang.intellij.lua.debug.api.LuaDevice
import com.tang.intellij.lua.debug.api.LuaDeviceProcess
import com.tang.intellij.lua.debug.api.LuaDeviceService
import com.tang.intellij.lua.debug.api.LuaDeviceState
import com.tang.intellij.lua.debug.api.LuaEnvCheckItem
import com.tang.intellij.lua.debug.api.LuaSymbolReport
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.BoxLayout
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 「Lua 真机附加」配置页（设计文档 10.3）：
 * 设备 / 进程下拉、启动即停、符号文件和校验结果、源码映射、手动地址、检查环境。
 *
 * 设备和进程查询都可能卡几秒（adb），全部在后台线程做，结果回 EDT 更新；
 * 真机组件（LuaDeviceService）没装时页面照常显示，只是下拉为空并提示原因。
 */
class LuaDeviceSettingsEditor(private val project: Project) : SettingsEditor<LuaDeviceConfiguration>() {
    private val service: LuaDeviceService? get() = LuaDeviceService.getInstance()

    // 设备
    private val deviceModel = DefaultComboBoxModel<DeviceItem>()
    private val deviceCombo = ComboBox(deviceModel).apply {
        renderer = SimpleListCellRenderer.create("") { it.text }
    }
    private val refreshDevices = JButton(LuaDebugBundle.message("device.refresh"), AllIcons.Actions.Refresh)
    private val deviceStatus = JBLabel().apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL }

    // 进程
    private val processCombo = ComboBox<String>().apply { isEditable = true }
    private val processStatus = JBLabel().apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL }
    private val launchAndAttach = JBCheckBox(LuaDebugBundle.message("device.launchAndAttach"))
    private var processes: List<LuaDeviceProcess> = emptyList()

    // 符号
    private val symbolFile = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("so")
                .withTitle(LuaDebugBundle.message("device.symbol.choose")),
        )
        (textField as? JBTextField)?.emptyText?.text = LuaDebugBundle.message("device.symbol.auto")
    }
    private val symbolStatus = JBLabel()

    // 源码映射
    private val mappingTable = SourceMappingTable(project)
    private val autoLearn = JBCheckBox(LuaDebugBundle.message("device.mapping.autoLearn"))

    // 手动地址
    private val useManual = JBCheckBox(LuaDebugBundle.message("device.manual.use"))
    private val manualHost = JBTextField()
    private val manualPort = JSpinner(SpinnerNumberModel(9966, 1, 65535, 1)).apply {
        editor = JSpinner.NumberEditor(this, "#")
    }
    private val pairingCode = JBTextField()

    // 检查环境
    private val checkButton = JButton(LuaDebugBundle.message("device.check"))
    private val checkResults = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    /** 符号文件输入停下 500ms 后再读 ELF，避免每敲一个字读一次大文件。 */
    private val symbolAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var pendingSerial: String = ""

    private val root: JComponent = panel {
        if (LuaDeviceService.getInstance() == null) {
            row {
                icon(AllIcons.General.Warning)
                label(LuaDebugBundle.message("device.error.noService"))
            }
        }
        row(LuaDebugBundle.message("device.device")) {
            cell(deviceCombo).align(AlignX.FILL).resizableColumn()
            cell(refreshDevices)
        }.enabledIf(useManual.selected.not())
        row("") { cell(deviceStatus) }
        row(LuaDebugBundle.message("device.process")) {
            cell(processCombo).align(AlignX.FILL).resizableColumn()
                .comment(LuaDebugBundle.message("device.process.comment"))
        }.enabledIf(useManual.selected.not())
        row("") { cell(processStatus) }
        row("") { cell(launchAndAttach) }.enabledIf(useManual.selected.not())
        row(LuaDebugBundle.message("device.symbol")) {
            cell(symbolFile).align(AlignX.FILL).resizableColumn()
        }.enabledIf(useManual.selected.not())
        row("") { cell(symbolStatus) }

        collapsibleGroup(LuaDebugBundle.message("device.mapping")) {
            row {
                cell(mappingTable.component).align(AlignX.FILL).resizableColumn()
            }
            row { cell(autoLearn) }
            row { comment(LuaDebugBundle.message("device.mapping.comment")) }
        }.expanded = true

        collapsibleGroup(LuaDebugBundle.message("device.manual")) {
            row { cell(useManual).comment(LuaDebugBundle.message("device.manual.comment")) }
            row(LuaDebugBundle.message("device.manual.host")) {
                cell(manualHost).columns(20)
                label(LuaDebugBundle.message("device.manual.port"))
                cell(manualPort)
            }.enabledIf(useManual.selected)
            row(LuaDebugBundle.message("device.manual.pairing")) {
                cell(pairingCode).columns(10).comment(LuaDebugBundle.message("device.manual.pairing.comment"))
            }.enabledIf(useManual.selected)
        }

        separator()
        row {
            cell(checkButton)
        }.enabledIf(useManual.selected.not())
        row { cell(checkResults).align(AlignX.FILL) }
    }

    init {
        refreshDevices.addActionListener { loadDevices(selectedSerial()) }
        deviceCombo.addActionListener {
            val serial = selectedSerial()
            if (serial != pendingSerial) {
                pendingSerial = serial
                showDeviceStatus()
                loadProcesses(serial, currentPackage())
                scheduleSymbolInspection()
            }
        }
        processCombo.addActionListener { showProcessStatus() }
        symbolFile.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleSymbolInspection()
            override fun removeUpdate(e: DocumentEvent) = scheduleSymbolInspection()
            override fun changedUpdate(e: DocumentEvent) = scheduleSymbolInspection()
        })
        checkButton.addActionListener { runEnvironmentCheck() }
        Disposer.register(this, mappingTable)
    }

    override fun createEditor(): JComponent = root

    override fun resetEditorFrom(configuration: LuaDeviceConfiguration) {
        val o = configuration.options
        pendingSerial = o.deviceSerial.orEmpty()
        setDeviceItems(emptyList(), pendingSerial)
        processCombo.model = DefaultComboBoxModel(listOfNotNull(o.packageName?.takeIf { it.isNotBlank() }).toTypedArray())
        processCombo.selectedItem = o.packageName.orEmpty()
        launchAndAttach.isSelected = o.launchAndAttach
        symbolFile.text = o.symbolFile.orEmpty()
        mappingTable.setRows(o.mappings.map { SourceMappingRow(it.devicePrefix.orEmpty(), it.localDirectory.orEmpty()) })
        autoLearn.isSelected = o.autoLearnMapping
        useManual.isSelected = o.useManualAddress
        manualHost.text = o.manualHost.orEmpty()
        manualPort.value = o.manualPort
        pairingCode.text = o.pairingCode.orEmpty()
        checkResults.removeAll()
        loadDevices(pendingSerial)
    }

    override fun applyEditorTo(configuration: LuaDeviceConfiguration) {
        val o = configuration.options
        o.deviceSerial = selectedSerial()
        o.packageName = currentPackage()
        o.launchAndAttach = launchAndAttach.isSelected
        o.symbolFile = symbolFile.text.trim()
        o.mappings = mappingTable.rows()
            .filter { it.devicePrefix.isNotBlank() || it.localDirectory.isNotBlank() }
            .map { row ->
                SourceMappingRowState().apply {
                    devicePrefix = row.devicePrefix.trim()
                    localDirectory = row.localDirectory.trim()
                }
            }.toMutableList()
        o.autoLearnMapping = autoLearn.isSelected
        o.useManualAddress = useManual.isSelected
        o.manualHost = manualHost.text.trim()
        o.manualPort = manualPort.value as Int
        o.pairingCode = pairingCode.text.trim()
    }

    // ---------- 设备 ----------

    /** 下拉第一项固定是"自动"：运行时只有一台在线设备就用它。 */
    private data class DeviceItem(val serial: String, val text: String, val device: LuaDevice?)

    private fun autoItem() = DeviceItem("", LuaDebugBundle.message("device.auto"), null)

    private fun selectedSerial(): String = (deviceCombo.selectedItem as? DeviceItem)?.serial.orEmpty()

    private fun selectedDevice(): LuaDevice? = (deviceCombo.selectedItem as? DeviceItem)?.device

    private fun setDeviceItems(devices: List<LuaDevice>, select: String) {
        val items = mutableListOf(autoItem())
        devices.mapTo(items) { DeviceItem(it.serial, describe(it), it) }
        // 配置里记的设备现在没连上，也留在列表里，不能悄悄改成"自动"
        if (select.isNotEmpty() && items.none { it.serial == select }) {
            items += DeviceItem(select, LuaDebugBundle.message("device.disconnected", select), null)
        }
        deviceModel.removeAllElements()
        items.forEach { deviceModel.addElement(it) }
        deviceCombo.selectedItem = items.first { it.serial == select }
    }

    private fun describe(d: LuaDevice): String {
        val transport = LuaDebugBundle.message("device.transport.${d.transport.name.lowercase()}")
        val state = when (d.state) {
            LuaDeviceState.ONLINE -> d.androidVersion?.let { "Android $it" }
            LuaDeviceState.UNAUTHORIZED -> LuaDebugBundle.message("device.state.unauthorized")
            LuaDeviceState.OFFLINE -> LuaDebugBundle.message("device.state.offline")
        }
        return listOfNotNull(d.model, "($transport · adb${state?.let { " · $it" } ?: ""})").joinToString(" ")
    }

    private fun showDeviceStatus() {
        val d = selectedDevice()
        deviceStatus.icon = null
        deviceStatus.text = when {
            d == null -> ""
            d.state == LuaDeviceState.UNAUTHORIZED -> LuaDebugBundle.message("device.status.unauthorized")
            d.state == LuaDeviceState.OFFLINE -> LuaDebugBundle.message("device.status.offline")
            !d.supportsAttachAgent -> LuaDebugBundle.message("device.status.oldAndroid", d.androidVersion ?: "?")
            else -> listOfNotNull(d.abi, d.apiLevel?.let { "API $it" }).joinToString(" · ")
        }
        if (d != null && d.state != LuaDeviceState.ONLINE) deviceStatus.icon = AllIcons.General.Warning
    }

    private fun loadDevices(select: String) {
        val service = service ?: return
        refreshDevices.isEnabled = false
        deviceStatus.icon = AllIcons.Process.Step_1
        deviceStatus.text = LuaDebugBundle.message("device.progress.listing")
        background({ service.listDevices() }) { result ->
            refreshDevices.isEnabled = true
            result.onSuccess { devices ->
                pendingSerial = select
                setDeviceItems(devices, select)
                showDeviceStatus()
                if (devices.isEmpty()) {
                    deviceStatus.icon = AllIcons.General.Warning
                    deviceStatus.text = LuaDebugBundle.message("device.error.noDevice")
                }
                loadProcesses(effectiveSerial(devices, select), currentPackage())
                scheduleSymbolInspection()
            }.onFailure { e ->
                deviceStatus.icon = AllIcons.General.Error
                deviceStatus.text = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** "自动"时，如果只有一台在线设备，就拿它来列进程。 */
    private fun effectiveSerial(devices: List<LuaDevice>, select: String): String =
        select.ifEmpty { devices.singleOrNull { it.state == LuaDeviceState.ONLINE }?.serial.orEmpty() }

    private fun currentSerialForQueries(): String {
        val selected = selectedSerial()
        if (selected.isNotEmpty()) return selected
        val online = (0 until deviceModel.size).mapNotNull { deviceModel.getElementAt(it).device }
            .filter { it.state == LuaDeviceState.ONLINE }
        return online.singleOrNull()?.serial.orEmpty()
    }

    // ---------- 进程 ----------

    private fun currentPackage(): String =
        (processCombo.editor.item as? String ?: processCombo.selectedItem as? String).orEmpty().trim()
            .substringBefore(' ')

    private fun loadProcesses(serial: String, keep: String) {
        val service = service ?: return
        if (serial.isEmpty()) {
            processes = emptyList()
            showProcessStatus()
            return
        }
        processStatus.text = LuaDebugBundle.message("device.progress.processes")
        background({
            val running = service.listProcesses(serial, onlyDebuggable = true)
            val installed = runCatching { service.listDebuggablePackages(serial) }.getOrDefault(emptyList())
            running to installed
        }) { result ->
            result.onSuccess { (running, installed) ->
                processes = running
                // 正在运行的排前面；没运行但已安装的可调试包也列出来（"启动并附加"用）
                val names = (running.map { it.packageName } + installed).distinct()
                val model = DefaultComboBoxModel(names.toTypedArray())
                processCombo.model = model
                processCombo.selectedItem = keep.ifEmpty { names.firstOrNull().orEmpty() }
                showProcessStatus()
            }.onFailure { e ->
                processStatus.icon = AllIcons.General.Error
                processStatus.text = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun showProcessStatus() {
        val pkg = currentPackage()
        val running = processes.firstOrNull { it.packageName == pkg }
        processStatus.icon = null
        processStatus.text = when {
            pkg.isEmpty() -> ""
            running == null -> LuaDebugBundle.message("device.process.notRunning")
            !running.debuggable -> {
                processStatus.icon = AllIcons.General.Warning
                LuaDebugBundle.message("device.process.notDebuggable", running.pid)
            }
            else -> (listOf(LuaDebugBundle.message("device.process.running", running.pid)) + running.tags).joinToString(" · ")
        }
        scheduleSymbolInspection()
    }

    // ---------- 符号 ----------

    private fun scheduleSymbolInspection() {
        val service = service ?: return
        val configured = symbolFile.text.trim()
        val serial = currentSerialForQueries().takeIf { it.isNotEmpty() }
        val pkg = currentPackage().takeIf { it.isNotEmpty() }
        val expanded = project.basePath?.let { configured.replace(LuaDeviceConfiguration.PROJECT_DIR, it) } ?: configured
        symbolAlarm.cancelAllRequests()
        symbolAlarm.addRequest({
            val result = runCatching {
                val file = service.resolveSymbolFile(project, expanded.ifEmpty { null }, serial, pkg)
                file to file?.let { service.inspectSymbols(it, serial, pkg) }
            }
            ApplicationManager.getApplication().invokeLater({ showSymbolStatus(configured.isEmpty(), result) }, modality())
        }, 500)
    }

    private fun showSymbolStatus(auto: Boolean, result: Result<Pair<String?, LuaSymbolReport?>>) {
        result.onFailure { e ->
            symbolStatus.icon = AllIcons.General.Error
            symbolStatus.text = e.message ?: e.javaClass.simpleName
            return
        }
        val (file, report) = result.getOrThrow()
        if (file == null || report == null) {
            symbolStatus.icon = AllIcons.General.Warning
            symbolStatus.text = LuaDebugBundle.message("device.symbol.notFound")
            return
        }
        val prefix = if (auto) LuaDebugBundle.message("device.symbol.autoFound", file) + " · " else ""
        val (icon, text) = symbolSummary(report)
        symbolStatus.icon = icon
        symbolStatus.text = "<html>$prefix$text</html>"
    }

    private fun symbolSummary(r: LuaSymbolReport): Pair<javax.swing.Icon, String> {
        if (r.error != null) return AllIcons.General.Error to r.error
        val parts = mutableListOf<String>()
        val icon = when (r.buildIdMatches) {
            true -> AllIcons.General.InspectionsOK.also { parts += LuaDebugBundle.message("device.symbol.match") }
            false -> AllIcons.General.Error.also { parts += LuaDebugBundle.message("device.symbol.mismatch") }
            null -> AllIcons.General.Information.also { parts += LuaDebugBundle.message("device.symbol.notCompared") }
        }
        parts += LuaDebugBundle.message("device.symbol.count", r.luaFunctionCount)
        r.luaVersion?.let { parts += "Lua $it" }
        if (r.missingFunctions.isNotEmpty()) {
            parts += LuaDebugBundle.message("device.symbol.missing", r.missingFunctions.take(5).joinToString(", "), r.missingFunctions.size)
        }
        parts += r.warnings
        val finalIcon = if (icon == AllIcons.General.InspectionsOK && (r.missingFunctions.isNotEmpty() || r.warnings.isNotEmpty()))
            AllIcons.General.Warning else icon
        return finalIcon to parts.joinToString(" · ")
    }

    // ---------- 检查环境 ----------

    private fun runEnvironmentCheck() {
        val service = service
        checkResults.removeAll()
        if (service == null) {
            addCheckRow(LuaEnvCheckItem(LuaDebugBundle.message("device.check.component"), LuaCheckStatus.FAILED,
                LuaDebugBundle.message("device.error.noService")))
            return
        }
        checkButton.isEnabled = false
        checkResults.add(JBLabel(LuaDebugBundle.message("device.check.running"), AllIcons.Process.Step_1, JBLabel.LEFT))
        checkResults.revalidate()
        val target = LuaDebugTarget.AndroidDevice(
            deviceSerial = currentSerialForQueries(),
            packageName = currentPackage(),
            launchAndAttach = launchAndAttach.isSelected,
            symbolFile = symbolFile.text.trim().ifEmpty { null }
                ?.let { p -> project.basePath?.let { p.replace(LuaDeviceConfiguration.PROJECT_DIR, it) } ?: p },
        )
        background({ service.checkEnvironment(project, target) }) { result ->
            checkButton.isEnabled = true
            checkResults.removeAll()
            result.onSuccess { items -> items.forEach { addCheckRow(it) } }
                .onFailure { e ->
                    addCheckRow(LuaEnvCheckItem(LuaDebugBundle.message("device.check.title"), LuaCheckStatus.FAILED,
                        e.message ?: e.javaClass.simpleName))
                }
            checkResults.revalidate()
            checkResults.repaint()
        }
    }

    private fun addCheckRow(item: LuaEnvCheckItem) {
        val icon = when (item.status) {
            LuaCheckStatus.OK -> AllIcons.General.InspectionsOK
            LuaCheckStatus.WARNING -> AllIcons.General.Warning
            LuaCheckStatus.FAILED -> AllIcons.General.Error
        }
        val fix = item.fix?.let { "<br><small>$it</small>" } ?: ""
        checkResults.add(JBLabel("<html><b>${item.title}</b>：${item.detail}$fix</html>", icon, JBLabel.LEFT))
    }

    // ---------- 工具 ----------

    private fun modality(): ModalityState = ModalityState.stateForComponent(root)

    private fun <T> background(task: () -> T, onEdt: (Result<T>) -> Unit) {
        val modality = modality()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(task)
            ApplicationManager.getApplication().invokeLater({ if (!Disposer.isDisposed(this)) onEdt(result) }, modality)
        }
    }
}
