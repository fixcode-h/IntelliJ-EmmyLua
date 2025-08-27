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

package com.tang.intellij.lua.actions;

import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.util.IncorrectOperationException;
import com.tang.intellij.lua.lang.LuaFileType;
import com.tang.intellij.lua.lang.LuaIcons;
import com.tang.intellij.lua.project.LuaSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 *
 * Created by tangzx on 2016/12/24.
 */
public class CreateLuaFileAction extends CreateFileFromTemplateAction implements DumbAware {
    private static final String CREATE_LUA_FILE = "New Lua File";

    public CreateLuaFileAction() {
        super(CREATE_LUA_FILE, "", LuaIcons.FILE);
    }

    @Override
    protected void buildDialog(Project project, PsiDirectory psiDirectory, CreateFileFromTemplateDialog.Builder builder) {
        builder.setTitle(CREATE_LUA_FILE)
        .addKind("Source File", LuaIcons.FILE, "NewLua.lua");
    }

    @Override
    protected String getActionName(PsiDirectory psiDirectory, String s, String s1) {
        return CREATE_LUA_FILE;
    }

    @Nullable
    @Override
    protected PsiFile createFile(String name, String templateName, PsiDirectory dir) {
        LuaSettings settings = LuaSettings.Companion.getInstance();
        
        // 如果启用了自定义文件模板，使用自定义模板
        if (settings.getEnableCustomFileTemplate()) {
            String templateContent = settings.getCustomFileTemplate();
            
            // 处理各种占位符
            templateContent = processTemplateVariables(templateContent, name, dir.getProject());
            
            // 如果启用了文件名替换功能，替换FILE_NAME占位符
            if (settings.getEnableFileNameReplacement()) {
                String placeholder = settings.getFileNamePlaceholder();
                if (placeholder != null && !placeholder.isEmpty()) {
                    String fileNameWithoutExtension = name;
                    if (name.endsWith(".lua")) {
                        fileNameWithoutExtension = name.substring(0, name.length() - 4);
                    }
                    templateContent = templateContent.replace(placeholder, fileNameWithoutExtension);
                }
            }
            
            // 创建文件
            String fileName = name.endsWith(".lua") ? name : name + ".lua";
            PsiFile file = PsiFileFactory.getInstance(dir.getProject())
                    .createFileFromText(fileName, LuaFileType.INSTANCE, templateContent);
            return (PsiFile) dir.add(file);
        }
        
        // 否则使用默认模板
        FileTemplate template = FileTemplateManager.getInstance(dir.getProject()).getInternalTemplate(templateName);
        return createFileFromTemplate(name, template, dir);
    }
    
    private String processTemplateVariables(String content, String fileName, Project project) {
        // 替换用户名
        String userName = System.getProperty("user.name", "Unknown");
        content = content.replace("${USER}", userName);
        
        // 替换日期和时间
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy/MM/dd");
        java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm");
        java.util.Date now = new java.util.Date();
        
        content = content.replace("${DATE}", dateFormat.format(now));
        content = content.replace("${TIME}", timeFormat.format(now));
        
        return content;
    }
}
