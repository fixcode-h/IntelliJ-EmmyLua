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

package com.tang.intellij.lua.performance

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.indexing.FileBasedIndex
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * 性能监控工具窗口
 * 
 * 功能：
 * 1. 实时显示缓存统计
 * 2. 识别性能瓶颈
 * 3. 一键复制数据
 */
class PerformanceToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PerformanceToolWindowPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class PerformanceToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val textArea = JTextArea()
    private val refreshButton = JButton("刷新数据")
    private val copyButton = JButton("复制到剪贴板")
    private val clearButton = JButton("清理运行时缓存")
    private val rebuildIndexButton = JButton("重建索引")
    private val autoRefreshCheckBox = JCheckBox("停止自动刷新")
    
    private var timer: Timer? = null
    
    init {
        initUI()
        refreshData()
        startAutoRefresh() // 默认启动自动刷新
    }
    
    private fun initUI() {
        // 文本区域设置
        textArea.isEditable = false
        textArea.font = Font("Monospaced", Font.PLAIN, 12)
        textArea.margin = java.awt.Insets(10, 10, 10, 10)
        
        val scrollPane = JScrollPane(textArea)
        scrollPane.preferredSize = Dimension(600, 400)
        
        // 按钮面板
        val buttonPanel = JPanel()
        refreshButton.addActionListener { refreshData() }
        copyButton.addActionListener { copyToClipboard() }
        clearButton.addActionListener { clearCaches() }
        rebuildIndexButton.addActionListener { rebuildIndex() }
        
        autoRefreshCheckBox.addActionListener {
            if (autoRefreshCheckBox.isSelected) {
                stopAutoRefresh()
            } else {
                startAutoRefresh()
            }
        }
        
        buttonPanel.add(refreshButton)
        buttonPanel.add(copyButton)
        buttonPanel.add(clearButton)
        buttonPanel.add(rebuildIndexButton)
        buttonPanel.add(JSeparator(SwingConstants.VERTICAL))
        buttonPanel.add(autoRefreshCheckBox)
        
        // 布局
        add(scrollPane, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
    }
    
    private fun refreshData() {
        val report = ApplicationManager.getApplication().runReadAction<String> {
            buildPerformanceReport()
        }
        textArea.text = report
        textArea.caretPosition = 0
    }
    
    private fun buildPerformanceReport(): String {
        val sb = StringBuilder()
        
        // 标题
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("         EmmyLua 性能监控面板")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("刷新时间: ${java.time.LocalDateTime.now()}")
        sb.appendLine()
        
        // 1. 类型推断缓存统计
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("📊 类型推断缓存 (LuaTypeCache)")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        try {
            val typeCache = com.tang.intellij.lua.ty.LuaTypeCache.getInstance(project)
            val stats = typeCache.getStats()
            
            sb.appendLine("  L1 (ThreadLocal):")
            sb.appendLine("    大小: ${stats.l1Size} 条 ${if (stats.l1Size == 0) "(跨线程查询，正常)" else ""}")
            sb.appendLine("    命中: ${stats.l1Hits} 次")
            sb.appendLine()
            
            sb.appendLine("  L2 (Project级):")
            sb.appendLine("    总大小: ${stats.l2Size} 条")
            sb.appendLine("    有效: ${stats.l2ValidEntries} 条")
            sb.appendLine("    命中: ${stats.l2Hits} 次")
            sb.appendLine()
            
            sb.appendLine("  L3 (已禁用): hits=${stats.l3Hits}")
            sb.appendLine()
            
            sb.appendLine("  总体统计:")
            sb.appendLine("    总命中: ${stats.l1Hits + stats.l2Hits + stats.l3Hits} 次")
            sb.appendLine("    未命中: ${stats.misses} 次")
            sb.appendLine("    命中率: ${"%.2f".format(stats.hitRate * 100)}%")
            
            // 性能评估
            sb.appendLine()
            val totalOps = stats.l1Hits + stats.l2Hits + stats.l3Hits + stats.misses
            when {
                stats.hitRate >= 0.8 -> sb.appendLine("  ✅ 状态: 优秀 (命中率 >= 80%)")
                stats.hitRate >= 0.6 -> sb.appendLine("  ⚠️  状态: 良好 (命中率 >= 60%)")
                stats.hitRate >= 0.4 && totalOps < 30000 -> sb.appendLine("  ℹ️  状态: 预热中 (命中率 ${String.format("%.1f", stats.hitRate * 100)}%, 操作数: $totalOps)")
                else -> sb.appendLine("  ❌ 状态: 需要优化 (命中率 < 60%)")
            }
        } catch (e: Exception) {
            sb.appendLine("  ❌ 获取失败: ${e.message}")
        }
        sb.appendLine()
        
        // 2. 类继承层次缓存
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("📊 类继承层次缓存 (LuaClassHierarchyCache)")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        try {
            val hierarchyCache = com.tang.intellij.lua.ty.LuaClassHierarchyCache.getInstance(project)
            val hierarchyStats = hierarchyCache.getStats()
            sb.appendLine(hierarchyStats)
        } catch (e: Exception) {
            sb.appendLine("  ❌ 获取失败: ${e.message}")
        }
        sb.appendLine()
        
        // 3. PSI解析缓存
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("📊 PSI解析缓存 (ResolveResultCache)")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        try {
            val resolveStats = com.tang.intellij.lua.psi.ResolveResultCache.getStats()
            sb.appendLine(resolveStats)
        } catch (e: Exception) {
            sb.appendLine("  ❌ 获取失败: ${e.message}")
        }
        sb.appendLine()
        
        // 4. 性能监控数据
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("📊 操作耗时统计 (PerformanceMonitor)")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        try {
            val perfReport = PerformanceMonitor.getReport()
            sb.append(perfReport)
        } catch (e: Exception) {
            sb.appendLine("  ❌ 获取失败: ${e.message}")
        }
        sb.appendLine()
        
        // 5. 性能瓶颈分析
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("🔍 性能瓶颈分析")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.append(analyzeBottlenecks())
        sb.appendLine()
        
        // 6. 优化建议
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("💡 优化建议")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.append(generateSuggestions())
        sb.appendLine()
        
        sb.appendLine("═══════════════════════════════════════════════════════════")
        
        return sb.toString()
    }
    
    private fun analyzeBottlenecks(): String {
        val sb = StringBuilder()
        val issues = mutableListOf<String>()
        
        try {
            // 检查类型缓存命中率
            val typeCache = com.tang.intellij.lua.ty.LuaTypeCache.getInstance(project)
            val stats = typeCache.getStats()
            
            val totalOps = stats.l1Hits + stats.l2Hits + stats.l3Hits + stats.misses
            
            // 只有在操作数足够多且命中率低时才报警
            if (totalOps > 30000 && stats.hitRate < 0.6) {
                issues.add("⚠️ 类型缓存命中率过低 (${String.format("%.1f", stats.hitRate * 100)}%)")
            } else if (totalOps < 30000 && stats.hitRate < 0.4) {
                issues.add("ℹ️ 缓存正在预热中，请稍后再查看 (当前: ${String.format("%.1f", stats.hitRate * 100)}%)")
            }
            
            if (stats.l2Size > 15000) {
                issues.add("⚠️ L2缓存条目过多 (${stats.l2Size}), 可能影响性能")
            }
            
            val invalidRate = if (stats.l2Size > 0) {
                (stats.l2Size - stats.l2ValidEntries).toDouble() / stats.l2Size
            } else 0.0
            
            if (invalidRate > 0.3) {
                issues.add("⚠️ L2缓存失效率过高 (${String.format("%.1f", invalidRate * 100)}%)")
            }
            
            // 检查类继承缓存
            val hierarchyCache = com.tang.intellij.lua.ty.LuaClassHierarchyCache.getInstance(project)
            val hierarchyStatsText: String = hierarchyCache.getStats().toString()
            
            val hasLowHitRate = hierarchyStatsText.contains("HitRate=0.")
            val hasZeroHits = hierarchyStatsText.contains("Hits=0")
            if (hasLowHitRate || hasZeroHits) {
                issues.add("ℹ️ 类继承缓存未启用或未命中")
            }
            
        } catch (e: Exception) {
            issues.add("❌ 分析失败: ${e.message}")
        }
        
        if (issues.isEmpty()) {
            sb.appendLine("  ✅ 未发现明显性能瓶颈")
        } else {
            issues.forEach { issue ->
                sb.appendLine("  $issue")
            }
        }
        
        return sb.toString()
    }
    
    private fun generateSuggestions(): String {
        val sb = StringBuilder()
        val suggestions = mutableListOf<String>()
        
        try {
            val typeCache = com.tang.intellij.lua.ty.LuaTypeCache.getInstance(project)
            val stats = typeCache.getStats()
            
            val totalOps = stats.l1Hits + stats.l2Hits + stats.l3Hits + stats.misses
            
            if (totalOps < 10000) {
                suggestions.add("ℹ️ 数据量较少，建议：")
                suggestions.add("   - 触发更多代码补全操作")
                suggestions.add("   - 打开和编辑更多Lua文件")
                suggestions.add("   - 等待5-10分钟后再查看")
            } else if (totalOps > 30000 && stats.hitRate < 0.7) {
                suggestions.add("1. 考虑增加缓存大小以提升命中率")
            }
            
            if (totalOps > 30000 && stats.misses > 10000) {
                suggestions.add("2. 缓存未命中较多，可能是代码变动频繁")
            }
            
            if (stats.l2Size > 15000) {
                suggestions.add("3. 定期清理过期缓存以释放内存")
            }
            
        } catch (e: Exception) {
            suggestions.add("❌ 无法生成建议: ${e.message}")
        }
        
        if (suggestions.isEmpty()) {
            sb.appendLine("  ✅ 当前性能表现良好，暂无优化建议")
        } else {
            suggestions.forEach { suggestion ->
                sb.appendLine("  $suggestion")
            }
        }
        
        return sb.toString()
    }
    
    private fun copyToClipboard() {
        try {
            val data = textArea.text
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(data), null)
            
            JOptionPane.showMessageDialog(
                this,
                "性能数据已复制到剪贴板！",
                "复制成功",
                JOptionPane.INFORMATION_MESSAGE
            )
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this,
                "复制失败: ${e.message}",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
    
    private fun clearCaches() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "确定要清理运行时缓存吗？\n" +
            "这将清理：\n" +
            "• 类型推断缓存\n" +
            "• 类继承层次缓存\n" +
            "• PSI解析缓存\n" +
            "• 性能统计数据\n\n" +
            "注意：不会清理索引缓存。\n" +
            "如果代码跳转/补全有问题，请使用【重建索引】按钮。",
            "确认清理运行时缓存",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            try {
                ApplicationManager.getApplication().runWriteAction {
                    // 清理类型缓存
                    val typeCache = com.tang.intellij.lua.ty.LuaTypeCache.getInstance(project)
                    typeCache.clear()
                    
                    // 清理类继承缓存
                    val hierarchyCache = com.tang.intellij.lua.ty.LuaClassHierarchyCache.getInstance(project)
                    hierarchyCache.clear()
                    
                    // 清理性能监控
                    PerformanceMonitor.reset()
                    
                    // 清理SearchContext缓存
                    com.tang.intellij.lua.search.SearchContext.invalidateCache(project)
                }
                
                JOptionPane.showMessageDialog(
                    this,
                    "缓存已清理完成！",
                    "清理成功",
                    JOptionPane.INFORMATION_MESSAGE
                )
                
                refreshData()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "清理失败: ${e.message}",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
    
    private fun rebuildIndex() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "确定要重建项目索引吗？\n\n" +
            "这将触发完整的索引重建，包括：\n" +
            "• Lua 文件的 Stub 索引\n" +
            "• 类、函数、变量的定义索引\n" +
            "• 类型信息和引用关系\n\n" +
            "用途：\n" +
            "• 修改文件大小限制后\n" +
            "• 代码跳转/补全失败\n" +
            "• 索引数据损坏\n\n" +
            "⚠️ 索引过程可能需要5-15分钟，期间代码提示功能会受限。\n" +
            "建议在不需要频繁编辑代码时执行。",
            "确认重建索引",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            try {
                // 停止自动刷新，避免在重建期间频繁查询
                val wasAutoRefreshing = timer != null
                if (wasAutoRefreshing) {
                    stopAutoRefresh()
                    autoRefreshCheckBox.isSelected = true
                }
                
                JOptionPane.showMessageDialog(
                    this,
                    "索引重建已开始！\n\n" +
                    "请观察IDE右下角的进度条：\n" +
                    "[████░░░] Indexing... XX/YY files\n\n" +
                    "完成后代码跳转和补全功能将恢复正常。\n" +
                    "期间可以正常编辑代码，但代码提示会受限。",
                    "索引重建中",
                    JOptionPane.INFORMATION_MESSAGE
                )
                
                // 触发索引重建
                ApplicationManager.getApplication().invokeLater {
                    // 使用 FileBasedIndex.requestReindex() 触发重建
                    ApplicationManager.getApplication().runWriteAction {
                        val fileBasedIndex = FileBasedIndex.getInstance()
                        
                        // 标记所有 Lua 文件需要重新索引
                        project.basePath?.let { basePath ->
                            val vfsManager = VirtualFileManager.getInstance()
                            val baseDir = vfsManager.findFileByUrl("file://$basePath")
                            
                            baseDir?.let { dir ->
                                VfsUtilCore.iterateChildrenRecursively(
                                    dir,
                                    { file -> file.isDirectory || file.extension == "lua" },
                                    { file ->
                                        if (!file.isDirectory && file.extension == "lua") {
                                            fileBasedIndex.requestReindex(file)
                                        }
                                        true
                                    }
                                )
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "索引重建失败: ${e.message}\n\n" +
                    "请尝试：\n" +
                    "1. File → Invalidate Caches... → Invalidate and Restart\n" +
                    "2. 或手动删除索引目录后重启",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
    
    private fun startAutoRefresh() {
        timer = Timer(5000) { refreshData() }
        timer?.start()
    }
    
    private fun stopAutoRefresh() {
        timer?.stop()
        timer = null
    }
}

