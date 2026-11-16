package com.fixcode.intellij.lua.project

import com.fixcode.intellij.lua.lsp.EmmyLuaWorkspaceConfig
import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import javax.swing.Icon

/**
 * 项目树「外部库」里显示 emmylua_ls 用的库目录（UE 声明、设置里的额外库），只为了能浏览和按文件名查找；
 * 索引、跳转仍由 emmylua_ls 做。根目录来自 [EmmyLuaWorkspaceConfig.libraries]，和下发给服务器的一致。
 */
class LuaLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        LuaLibraryRoots.getInstance(project).libraries()

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        LuaLibraryRoots.getInstance(project).libraries().flatMap { it.sourceRoots }
}

/** 外部库里的一个节点。名字进 [getComparisonId]，同名同根视为同一个库。 */
class LuaSyntheticLibrary(private val name: String, private val roots: List<VirtualFile>) :
    SyntheticLibrary(name, null), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = roots
    override fun getPresentableText(): String = name
    override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.PpLibFolder
    override fun equals(other: Any?): Boolean = other is LuaSyntheticLibrary && other.name == name && other.roots == roots
    override fun hashCode(): Int = name.hashCode() * 31 + roots.hashCode()
}

/** 缓存算好的库目录：`getAdditionalProjectLibraries` 调得频繁，不每次都去磁盘往上找 UE 项目。 */
@Service(Service.Level.PROJECT)
class LuaLibraryRoots(private val project: Project) {
    data class Roots(val unreal: List<Path>, val extra: List<Path>)

    @Volatile private var cached: Roots? = null

    fun libraries(): List<LuaSyntheticLibrary> {
        val roots = cached ?: compute().also { cached = it }
        return toLibraries(roots) { LocalFileSystem.getInstance().findFileByNioFile(it) }
    }

    /**
     * 重新算一遍；有变化时通知平台刷新「外部库」。库目录变了的地方（UE 声明目录出现 / 换了、设置改了）
     * 都会重启 emmylua_ls，所以由 [com.fixcode.intellij.lua.lsp.EmmyLuaServer.restart] 顺带调用。
     */
    fun refresh() {
        if (project.isDisposed) return
        val now = runCatching { compute() }.getOrNull() ?: return
        val old = cached
        if (now == old) return
        cached = now
        // 新出现的目录 VFS 可能还没见过：在读写锁外刷新一下再找
        val fs = LocalFileSystem.getInstance()
        (now.unreal + now.extra).forEach { fs.refreshAndFindFileByNioFile(it) }
        val oldLibs = old?.let { r -> toLibraries(r) { fs.findFileByNioFile(it) } }.orEmpty()
        val newLibs = toLibraries(now) { fs.findFileByNioFile(it) }
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            WriteAction.run<RuntimeException> {
                for (name in listOf(UNREAL_NAME, EXTRA_NAME)) {
                    val before = oldLibs.firstOrNull { it.presentableText == name }?.sourceRoots.orEmpty()
                    val after = newLibs.firstOrNull { it.presentableText == name }?.sourceRoots.orEmpty()
                    if (before != after) AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, name, before, after, name)
                }
            }
        }, ModalityState.nonModal())
    }

    private fun compute(): Roots {
        val all = EmmyLuaWorkspaceConfig.libraries(project)
        val unreal = if (LuaProjectSettings.getInstance(project).detectUnrealProject)
            EmmyLuaWorkspaceConfig.unrealLibraries(project).map { it.normalize() }.toSet() else emptySet()
        return Roots(all.filter { it.normalize() in unreal }, all.filterNot { it.normalize() in unreal })
    }

    private fun toLibraries(roots: Roots, find: (Path) -> VirtualFile?): List<LuaSyntheticLibrary> = buildList {
        roots.unreal.mapNotNull(find).takeIf { it.isNotEmpty() }?.let { add(LuaSyntheticLibrary(UNREAL_NAME, it)) }
        roots.extra.mapNotNull(find).takeIf { it.isNotEmpty() }?.let { add(LuaSyntheticLibrary(EXTRA_NAME, it)) }
    }

    companion object {
        const val UNREAL_NAME = "UE Lua 声明"
        const val EXTRA_NAME = "EmmyLua 额外库"

        fun getInstance(project: Project): LuaLibraryRoots = project.getService(LuaLibraryRoots::class.java)
    }
}
