package com.fixcode.intellij.lua.actions

import com.fixcode.intellij.lua.lang.LuaFileType
import com.fixcode.intellij.lua.lang.LuaIcons
import com.fixcode.intellij.lua.project.LuaSettings
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplateDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptorFactory
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import java.text.SimpleDateFormat
import java.util.Date

class CreateLuaFileAction : CreateFileFromTemplateAction(TITLE, "", LuaIcons.FILE), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle(TITLE).addKind("Source File", LuaIcons.FILE, TEMPLATE)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?) = TITLE

    override fun createFile(name: String, templateName: String, dir: PsiDirectory): PsiFile? {
        val settings = LuaSettings.instance
        if (!settings.enableCustomFileTemplate) {
            return createFileFromTemplate(name, FileTemplateManager.getInstance(dir.project).getInternalTemplate(templateName), dir)
        }
        val baseName = name.removeSuffix(".lua")
        val now = Date()
        var text = settings.customFileTemplate
            .replace("\${USER}", System.getProperty("user.name", "Unknown"))
            .replace("\${DATE}", SimpleDateFormat("yyyy/MM/dd").format(now))
            .replace("\${TIME}", SimpleDateFormat("HH:mm").format(now))
            .replace("\${PROJECT_NAME}", dir.project.name)
        if (settings.enableFileNameReplacement && settings.fileNamePlaceholder.isNotEmpty()) {
            text = text.replace(settings.fileNamePlaceholder, baseName)
        }
        val file = PsiFileFactory.getInstance(dir.project).createFileFromText("$baseName.lua", LuaFileType, text)
        return dir.add(file) as PsiFile
    }

    private companion object {
        const val TITLE = "New Lua File"
        const val TEMPLATE = "NewLua.lua"
    }
}

class LuaFileTemplateProvider : FileTemplateGroupDescriptorFactory {
    override fun getFileTemplatesDescriptor() = FileTemplateGroupDescriptor("Lua", LuaIcons.FILE).apply {
        addTemplate(FileTemplateDescriptor("NewLua.lua", LuaIcons.FILE))
    }
}
