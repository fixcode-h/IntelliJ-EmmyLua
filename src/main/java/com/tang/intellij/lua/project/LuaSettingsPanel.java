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

package com.tang.intellij.lua.project;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.FileContentUtil;
import com.tang.intellij.lua.lang.LuaLanguageLevel;
import com.tang.intellij.lua.LuaBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Created by tangzx on 2017/6/12.
 */
public class LuaSettingsPanel implements SearchableConfigurable, Configurable.NoScroll {
    private final LuaSettings settings;
    private JScrollPane myPanel;
    private JTabbedPane contentPanel;
    private JTextField constructorNames;
    private JCheckBox strictDoc;
    private JCheckBox smartCloseEnd;
    private JCheckBox showWordsInFile;
    private JCheckBox enforceTypeSafety;
    private JCheckBox nilStrict;
    private JCheckBox recognizeGlobalNameAsCheckBox;
    private LuaAdditionalSourcesRootPanel additionalRoots;
    private JCheckBox enableGenericCheckBox;
    private JCheckBox captureOutputDebugString;
    private JCheckBox captureStd;
    private JComboBox<String> charsetComboBox;
    private JComboBox<LuaLanguageLevel> languageLevel;
    private JTextField requireFunctionNames;
    private JTextField tooLargerFileThreshold;
    private JTextField ueProjectPathField;
    private JButton browseUEProjectButton;
    private JButton autoDetectUEProjectButton;
    private JCheckBox enableUEIntelliSenseCheckBox;
    private JTextField ueProcessNamesField;
    private JTextField debugProcessBlacklistField;
    private JTextField customEmmyHelperPathField;
    private JButton browseCustomEmmyHelperButton;
    private JCheckBox enableCustomFileTemplateCheckBox;
    private JTextArea customFileTemplateTextArea;
    private JCheckBox enableFileNameReplacementCheckBox;
    private JTextField fileNamePlaceholderField;

    public LuaSettingsPanel() {
        this.settings = LuaSettings.Companion.getInstance();
        constructorNames.setText(settings.getConstructorNamesString());
        strictDoc.setSelected(settings.isStrictDoc());
        smartCloseEnd.setSelected(settings.isSmartCloseEnd());
        showWordsInFile.setSelected(settings.isShowWordsInFile());
        enforceTypeSafety.setSelected(settings.isEnforceTypeSafety());
        nilStrict.setSelected(settings.isNilStrict());
        recognizeGlobalNameAsCheckBox.setSelected(settings.isRecognizeGlobalNameAsType());
        additionalRoots.setRoots(settings.getAdditionalSourcesRoot());
        enableGenericCheckBox.setSelected(settings.getEnableGeneric());
        requireFunctionNames.setText(settings.getRequireLikeFunctionNamesString());
        tooLargerFileThreshold.setDocument(new IntegerDocument());
        tooLargerFileThreshold.setText(String.valueOf(settings.getTooLargerFileThreshold()));

        captureStd.setSelected(settings.getAttachDebugCaptureStd());
        captureOutputDebugString.setSelected(settings.getAttachDebugCaptureOutput());

        SortedMap<String, Charset> charsetSortedMap = Charset.availableCharsets();
        ComboBoxModel<String> outputCharsetModel = new DefaultComboBoxModel<>(ArrayUtil.toStringArray(charsetSortedMap.keySet()));
        charsetComboBox.setModel(outputCharsetModel);
        charsetComboBox.setSelectedItem(settings.getAttachDebugDefaultCharsetName());

        //language level
        ComboBoxModel<LuaLanguageLevel> lanLevelModel = new DefaultComboBoxModel<>(LuaLanguageLevel.values());
        languageLevel.setModel(lanLevelModel);
        lanLevelModel.setSelectedItem(settings.getLanguageLevel());

        //UE project settings
        ueProjectPathField.setText(settings.getUeProjectPath());
        enableUEIntelliSenseCheckBox.setSelected(settings.getEnableUEIntelliSense());
        // 将进程名称数组转换为逗号分隔的字符串
        String[] processNames = settings.getUeProcessNames();
        if (processNames != null && processNames.length > 0) {
            ueProcessNamesField.setText(String.join(", ", processNames));
        } else {
            ueProcessNamesField.setText("");
        }
        
        // 将调试器进程黑名单数组转换为逗号分隔的字符串
        String[] blacklistProcesses = settings.getDebugProcessBlacklist();
        if (blacklistProcesses != null && blacklistProcesses.length > 0) {
            debugProcessBlacklistField.setText(String.join(", ", blacklistProcesses));
        } else {
            debugProcessBlacklistField.setText("");
        }
        
        // 自定义EmmyHelper.lua路径设置
        customEmmyHelperPathField.setText(settings.getCustomEmmyHelperPath());
        
        // 文件模板设置
        enableCustomFileTemplateCheckBox.setSelected(settings.getEnableCustomFileTemplate());
        customFileTemplateTextArea.setText(settings.getCustomFileTemplate());
        enableFileNameReplacementCheckBox.setSelected(settings.getEnableFileNameReplacement());
        fileNamePlaceholderField.setText(settings.getFileNamePlaceholder());

        //browse custom emmy helper button action
        browseCustomEmmyHelperButton.addActionListener(e -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                    .withFileFilter(file -> file.getName().endsWith(".lua"));
            descriptor.setTitle("Select Custom EmmyHelper.lua File");
            descriptor.setDescription("Choose a custom Lua file to use as EmmyHelper");
            
            VirtualFile selectedFile = FileChooser.chooseFile(descriptor, null, null);
            if (selectedFile != null) {
                customEmmyHelperPathField.setText(selectedFile.getPath());
            }
        });

        //browse button action
        browseUEProjectButton.addActionListener(e -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                    .withFileFilter(file -> file.getName().endsWith(".uproject"));
            descriptor.setTitle("Select UE Project File");
            descriptor.setDescription("Choose the .uproject file for your Unreal Engine project");
            
            VirtualFile selectedFile = FileChooser.chooseFile(descriptor, null, null);
            if (selectedFile != null) {
                ueProjectPathField.setText(selectedFile.getPath());
            }
        });

        //auto detect button action
        autoDetectUEProjectButton.addActionListener(e -> {
            // 在后台线程中执行自动检测，避免阻塞UI
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String detectedPath = settings.autoDetectUProjectPath();
                
                // 在UI线程中更新界面
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (detectedPath != null) {
                        ueProjectPathField.setText(detectedPath);
                        // 显示成功消息
                        Messages.showInfoMessage(
                            "已自动检测到 .uproject 文件：\n" + detectedPath,
                            "自动检测成功"
                        );
                    } else {
                        // 显示未找到消息
                        Messages.showWarningDialog(
                            "未能在当前项目及其父目录中找到 .uproject 文件。\n" +
                            "请确保：\n" +
                            "1. 当前项目类型已设置为 'Unreal Engine'\n" +
                            "2. 项目目录或其父目录包含 .uproject 文件",
                            "自动检测失败"
                        );
                    }
                });
            });
        });
    }

    @NotNull
    @Override
    public String getId() {
        return "Lua";
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "Lua";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return myPanel;
    }

    @Override
    public boolean isModified() {
        return !StringUtil.equals(settings.getConstructorNamesString(), constructorNames.getText()) ||
                !StringUtil.equals(settings.getRequireLikeFunctionNamesString(), requireFunctionNames.getText()) ||
                settings.getTooLargerFileThreshold() != getTooLargerFileThreshold() ||
                settings.isStrictDoc() != strictDoc.isSelected() ||
                settings.isSmartCloseEnd() != smartCloseEnd.isSelected() ||
                settings.isShowWordsInFile() != showWordsInFile.isSelected() ||
                settings.isEnforceTypeSafety() != enforceTypeSafety.isSelected() ||
                settings.isNilStrict() != nilStrict.isSelected() ||
                settings.isRecognizeGlobalNameAsType() != recognizeGlobalNameAsCheckBox.isSelected() ||
                settings.getEnableGeneric() != enableGenericCheckBox.isSelected() ||
                settings.getAttachDebugCaptureOutput() != captureOutputDebugString.isSelected() ||
                settings.getAttachDebugCaptureStd() != captureStd.isSelected() ||
                settings.getAttachDebugDefaultCharsetName() != charsetComboBox.getSelectedItem() ||
                settings.getLanguageLevel() != languageLevel.getSelectedItem() ||
                !StringUtil.equals(settings.getUeProjectPath(), ueProjectPathField.getText()) ||
                settings.getEnableUEIntelliSense() != enableUEIntelliSenseCheckBox.isSelected() ||
                !Arrays.equals(settings.getUeProcessNames(), getProcessNamesFromTextField()) ||
                !Arrays.equals(settings.getDebugProcessBlacklist(), getDebugProcessBlacklistFromTextField()) ||
                !StringUtil.equals(settings.getCustomEmmyHelperPath(), customEmmyHelperPathField.getText()) ||
                settings.getEnableCustomFileTemplate() != enableCustomFileTemplateCheckBox.isSelected() ||
                !StringUtil.equals(settings.getCustomFileTemplate(), customFileTemplateTextArea.getText()) ||
                settings.getEnableFileNameReplacement() != enableFileNameReplacementCheckBox.isSelected() ||
                !StringUtil.equals(settings.getFileNamePlaceholder(), fileNamePlaceholderField.getText()) ||
                !Arrays.equals(settings.getAdditionalSourcesRoot(), additionalRoots.getRoots(), String::compareTo);
    }

    @Override
    public void apply() {
        settings.setConstructorNamesString(constructorNames.getText());
        constructorNames.setText(settings.getConstructorNamesString());
        settings.setRequireLikeFunctionNamesString(requireFunctionNames.getText());
        requireFunctionNames.setText(settings.getRequireLikeFunctionNamesString());
        settings.setTooLargerFileThreshold(getTooLargerFileThreshold());
        settings.setStrictDoc(strictDoc.isSelected());
        settings.setSmartCloseEnd(smartCloseEnd.isSelected());
        settings.setShowWordsInFile(showWordsInFile.isSelected());
        settings.setEnforceTypeSafety(enforceTypeSafety.isSelected());
        settings.setNilStrict(nilStrict.isSelected());
        settings.setRecognizeGlobalNameAsType(recognizeGlobalNameAsCheckBox.isSelected());
        settings.setAdditionalSourcesRoot(additionalRoots.getRoots());
        settings.setEnableGeneric(enableGenericCheckBox.isSelected());
        settings.setAttachDebugCaptureOutput(captureOutputDebugString.isSelected());
        settings.setAttachDebugCaptureStd(captureStd.isSelected());
        settings.setAttachDebugDefaultCharsetName((String) Objects.requireNonNull(charsetComboBox.getSelectedItem()));
        
        //UE project settings
        settings.setUeProjectPath(ueProjectPathField.getText());
        settings.setEnableUEIntelliSense(enableUEIntelliSenseCheckBox.isSelected());
        
        //Custom EmmyHelper path
        settings.setCustomEmmyHelperPath(customEmmyHelperPathField.getText());
        
        // 文件模板设置
        settings.setEnableCustomFileTemplate(enableCustomFileTemplateCheckBox.isSelected());
        settings.setCustomFileTemplate(customFileTemplateTextArea.getText());
        settings.setEnableFileNameReplacement(enableFileNameReplacementCheckBox.isSelected());
        settings.setFileNamePlaceholder(fileNamePlaceholderField.getText());
        // 将逗号分隔的字符串转换为进程名称数组
        String processNamesText = ueProcessNamesField.getText().trim();
        if (processNamesText.isEmpty()) {
            settings.setUeProcessNames(new String[0]);
        } else {
            String[] processNames = processNamesText.split(",");
            for (int i = 0; i < processNames.length; i++) {
                processNames[i] = processNames[i].trim();
            }
            settings.setUeProcessNames(processNames);
        }
        
        // 将逗号分隔的字符串转换为调试器进程黑名单数组
        String blacklistText = debugProcessBlacklistField.getText().trim();
        if (blacklistText.isEmpty()) {
            settings.setDebugProcessBlacklist(new String[0]);
        } else {
            String[] blacklistProcesses = blacklistText.split(",");
            for (int i = 0; i < blacklistProcesses.length; i++) {
                blacklistProcesses[i] = blacklistProcesses[i].trim();
            }
            settings.setDebugProcessBlacklist(blacklistProcesses);
        }
        
        LuaLanguageLevel selectedLevel = (LuaLanguageLevel) Objects.requireNonNull(languageLevel.getSelectedItem());
        if (selectedLevel != settings.getLanguageLevel()) {
            settings.setLanguageLevel(selectedLevel);
            StdLibraryProvider.Companion.reload();

            FileContentUtil.reparseOpenedFiles();
        } else {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                DaemonCodeAnalyzer.getInstance(project).restart();
            }
        }
    }

    private String[] getProcessNamesFromTextField() {
        String processNamesText = ueProcessNamesField.getText().trim();
        if (processNamesText.isEmpty()) {
            return new String[0];
        } else {
            String[] processNames = processNamesText.split(",");
            for (int i = 0; i < processNames.length; i++) {
                processNames[i] = processNames[i].trim();
            }
            return processNames;
        }
    }
    
    private String[] getDebugProcessBlacklistFromTextField() {
        String blacklistText = debugProcessBlacklistField.getText().trim();
        if (blacklistText.isEmpty()) {
            return new String[0];
        } else {
            String[] blacklistProcesses = blacklistText.split(",");
            for (int i = 0; i < blacklistProcesses.length; i++) {
                blacklistProcesses[i] = blacklistProcesses[i].trim();
            }
            return blacklistProcesses;
        }
    }

    @Override
    public void reset() {
        constructorNames.setText(settings.getConstructorNamesString());
        strictDoc.setSelected(settings.isStrictDoc());
        smartCloseEnd.setSelected(settings.isSmartCloseEnd());
        showWordsInFile.setSelected(settings.isShowWordsInFile());
        enforceTypeSafety.setSelected(settings.isEnforceTypeSafety());
        nilStrict.setSelected(settings.isNilStrict());
        recognizeGlobalNameAsCheckBox.setSelected(settings.isRecognizeGlobalNameAsType());
        additionalRoots.setRoots(settings.getAdditionalSourcesRoot());
        enableGenericCheckBox.setSelected(settings.getEnableGeneric());
        requireFunctionNames.setText(settings.getRequireLikeFunctionNamesString());
        tooLargerFileThreshold.setText(String.valueOf(settings.getTooLargerFileThreshold()));
        captureStd.setSelected(settings.getAttachDebugCaptureStd());
        captureOutputDebugString.setSelected(settings.getAttachDebugCaptureOutput());
        charsetComboBox.setSelectedItem(settings.getAttachDebugDefaultCharsetName());
        languageLevel.setSelectedItem(settings.getLanguageLevel());
        
        // Reset UE project fields
        ueProjectPathField.setText(settings.getUeProjectPath());
        enableUEIntelliSenseCheckBox.setSelected(settings.getEnableUEIntelliSense());
        // 将进程名称数组转换为逗号分隔的字符串
        String[] processNames = settings.getUeProcessNames();
        if (processNames != null && processNames.length > 0) {
            ueProcessNamesField.setText(String.join(", ", processNames));
        } else {
            ueProcessNamesField.setText("");
        }
        
        // Reset custom EmmyHelper path
        customEmmyHelperPathField.setText(settings.getCustomEmmyHelperPath());
        
        // Reset 文件模板设置
        enableCustomFileTemplateCheckBox.setSelected(settings.getEnableCustomFileTemplate());
        customFileTemplateTextArea.setText(settings.getCustomFileTemplate());
        enableFileNameReplacementCheckBox.setSelected(settings.getEnableFileNameReplacement());
        fileNamePlaceholderField.setText(settings.getFileNamePlaceholder());
    }

    private int getTooLargerFileThreshold() {
        int value;
        try {
            value = Integer.parseInt(tooLargerFileThreshold.getText());
        } catch (NumberFormatException e) {
            value = settings.getTooLargerFileThreshold();
        }
        return value;
    }

    static class IntegerDocument extends PlainDocument {
        public void insertString(int offset, String s, AttributeSet attributeSet) throws BadLocationException {
            try {
                Integer.parseInt(s);
            } catch (Exception ex) {
                return;
            }
            super.insertString(offset, s, attributeSet);
        }
    }
}