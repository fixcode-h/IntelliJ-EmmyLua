package com.fixcode.intellij.lua.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiFile
import javax.swing.Icon

/** 「结构」窗口：大纲由 [LuaOutline] 从 token 算出，不等语言服务器。 */
class LuaStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile) = object : TreeBasedStructureViewBuilder() {
        override fun createStructureViewModel(editor: Editor?): StructureViewModel = LuaStructureViewModel(psiFile, editor)
        override fun isRootNodeShown() = false
    }
}

class LuaStructureViewModel(private val file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, LuaFileElement(file)), StructureViewModel.ElementInfoProvider {

    init {
        withSorters(Sorter.ALPHA_SORTER)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement) = element is LuaOutlineElement && element.node.children.isEmpty()

    /** 光标跟随：扁平 PSI 没有父子关系，按偏移找最深的、包含光标的声明。 */
    override fun getCurrentEditorElement(): Any? {
        val offset = editor?.caretModel?.offset ?: return null
        var best: LuaOutlineElement? = null
        var level: List<LuaOutlineElement> = root.children.filterIsInstance<LuaOutlineElement>()
        while (true) {
            val hit = level.firstOrNull { offset >= it.node.start && offset <= it.node.end } ?: break
            best = hit
            level = hit.children.filterIsInstance<LuaOutlineElement>()
        }
        return best?.value
    }
}

private class LuaFileElement(private val file: PsiFile) : StructureViewTreeElement {
    override fun getValue(): Any = file
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = file.name
        override fun getIcon(unused: Boolean): Icon? = file.getIcon(0)
    }
    override fun getChildren(): Array<StructureViewTreeElement> {
        val text = file.viewProvider.document?.immutableCharSequence ?: file.text
        return LuaOutline.build(text).map { LuaOutlineElement(file, it, it.name) }.toTypedArray()
    }
    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)
    override fun canNavigate() = file.canNavigate()
    override fun canNavigateToSource() = file.canNavigateToSource()
}

class LuaOutlineElement(private val file: PsiFile, val node: LuaOutline.Node, private val path: String) :
    StructureViewTreeElement, SortableTreeElement {

    /** 树刷新时按「路径 + 种类」认出同一个节点，展开状态不丢。 */
    private val key = Key(path, node.kind)

    data class Key(val path: String, val kind: LuaOutline.Kind)

    override fun getValue(): Any = key
    override fun getAlphaSortKey() = node.name

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = node.name + (node.params ?: "")
        override fun getIcon(unused: Boolean): Icon = when (node.kind) {
            LuaOutline.Kind.CLASS -> AllIcons.Nodes.Class
            LuaOutline.Kind.VARIABLE -> if (node.children.isNotEmpty()) AllIcons.Nodes.Class else AllIcons.Nodes.Variable
            LuaOutline.Kind.FIELD -> if (node.children.isNotEmpty()) AllIcons.Nodes.Class else AllIcons.Nodes.Field
            LuaOutline.Kind.FUNCTION -> AllIcons.Nodes.Function
            LuaOutline.Kind.METHOD -> AllIcons.Nodes.Method
            LuaOutline.Kind.STATIC_METHOD -> AllIcons.Nodes.Method
        }
    }

    override fun getChildren(): Array<StructureViewTreeElement> =
        node.children.map { LuaOutlineElement(file, it, "$path.${it.name}") }.toTypedArray()

    override fun navigate(requestFocus: Boolean) {
        val vf = file.virtualFile ?: return
        OpenFileDescriptor(file.project, vf, node.offset).navigate(requestFocus)
    }

    override fun canNavigate() = file.virtualFile != null
    override fun canNavigateToSource() = canNavigate()
}
