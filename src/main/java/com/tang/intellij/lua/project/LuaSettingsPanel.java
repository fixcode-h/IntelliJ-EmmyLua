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
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.FileContentUtil;
import com.tang.intellij.lua.debugger.SourceMappingService;
import com.tang.intellij.lua.lang.LuaLanguageLevel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Created by tangzx on 2017/6/12.
 */
public class LuaSettingsPanel implements SearchableConfigurable, Configurable.NoScroll {
    private final Project project;
    private final LuaSettings settings;
    private final LuaProjectSettings projectSettings;
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
    private JComboBox<LuaLanguageLevel> languageLevel;
    private JTextField requireFunctionNames;
    private JTextField tooLargerFileThreshold;
    private JTextField ueProcessNamesField;
    private JTextField debugProcessBlacklistField;
    private JTextField customHelperPathField;
    private JButton browseCustomHelperPathButton;
    private JTextField customHelperExtNameField;
    private JButton browseCustomHelperExtNameButton;
    private JCheckBox enableCustomFileTemplateCheckBox;
    private JTextArea customFileTemplateTextArea;
    private JCheckBox enableFileNameReplacementCheckBox;
    private JTextField fileNamePlaceholderField;
    private JCheckBox enableDevModeCheckBox;

    public LuaSettingsPanel(Project project) {
        this.project = project;
        this.settings = LuaSettings.Companion.getInstance();
        this.projectSettings = LuaProjectSettings.getInstance(project);
        buildUi();
        constructorNames.setText(settings.getConstructorNamesString());
        strictDoc.setSelected(settings.isStrictDoc());
        smartCloseEnd.setSelected(settings.isSmartCloseEnd());
        showWordsInFile.setSelected(settings.isShowWordsInFile());
        enforceTypeSafety.setSelected(settings.isEnforceTypeSafety());
        nilStrict.setSelected(settings.isNilStrict());
        recognizeGlobalNameAsCheckBox.setSelected(settings.isRecognizeGlobalNameAsType());
        additionalRoots.setRoots(projectSettings.getAdditionalSourcesRoot());
        enableGenericCheckBox.setSelected(settings.getEnableGeneric());
        requireFunctionNames.setText(settings.getRequireLikeFunctionNamesString());
        tooLargerFileThreshold.setDocument(new IntegerDocument());
        tooLargerFileThreshold.setText(String.valueOf(settings.getTooLargerFileThreshold()));

        //language level
        ComboBoxModel<LuaLanguageLevel> lanLevelModel = new DefaultComboBoxModel<>(LuaLanguageLevel.values());
        languageLevel.setModel(lanLevelModel);
        lanLevelModel.setSelectedItem(settings.getLanguageLevel());

        // 将进程名称数组转换为逗号分隔的字符串
        String[] processNames = projectSettings.getUeProcessNames();
        if (processNames != null && processNames.length > 0) {
            ueProcessNamesField.setText(String.join(", ", processNames));
        } else {
            ueProcessNamesField.setText("");
        }
        
        // 将调试器进程黑名单数组转换为逗号分隔的字符串
        String[] blacklistProcesses = projectSettings.getDebugProcessBlacklist();
        if (blacklistProcesses != null && blacklistProcesses.length > 0) {
            debugProcessBlacklistField.setText(String.join(", ", blacklistProcesses));
        } else {
            debugProcessBlacklistField.setText("");
        }
        
        // 自定义 Helper 目录路径设置
        customHelperPathField.setText(projectSettings.getCustomHelperPath());
        
        // 自定义 Helper 扩展脚本名称设置
        customHelperExtNameField.setText(projectSettings.getCustomHelperExtName());
        
        // 文件模板设置
        enableCustomFileTemplateCheckBox.setSelected(settings.getEnableCustomFileTemplate());
        customFileTemplateTextArea.setText(settings.getCustomFileTemplate());
        enableFileNameReplacementCheckBox.setSelected(settings.getEnableFileNameReplacement());
        fileNamePlaceholderField.setText(settings.getFileNamePlaceholder());
        
        // 开发模式设置
        enableDevModeCheckBox.setSelected(projectSettings.getEnableDevMode());

        //browse custom helper path button action (选择目录)
        browseCustomHelperPathButton.addActionListener(e -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
            descriptor.setTitle("Select Custom Helper Directory");
            descriptor.setDescription("Choose a directory containing custom helper Lua scripts");
            
            VirtualFile selectedFile = FileChooser.chooseFile(descriptor, null, null);
            if (selectedFile != null) {
                customHelperPathField.setText(selectedFile.getPath());
            }
        });
        
        //browse custom helper ext name button action (从 customHelperPath 目录选择文件)
        browseCustomHelperExtNameButton.addActionListener(e -> {
            String helperPath = customHelperPathField.getText().trim();
            VirtualFile rootDir = null;
            if (!helperPath.isEmpty()) {
                rootDir = LocalFileSystem.getInstance().findFileByPath(helperPath);
            }
            
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                    .withFileFilter(file -> file.getName().endsWith(".lua"));
            descriptor.setTitle("Select Custom Helper Extension Script");
            descriptor.setDescription("Choose a Lua file from the custom helper directory");
            if (rootDir != null && rootDir.isDirectory()) {
                descriptor.setRoots(rootDir);
            }
            
            VirtualFile selectedFile = FileChooser.chooseFile(descriptor, null, rootDir);
            if (selectedFile != null) {
                // 只保存文件名（不含扩展名）
                String fileName = selectedFile.getNameWithoutExtension();
                customHelperExtNameField.setText(fileName);
            }
        });
    }

    private void buildUi() {
        constructorNames = new JTextField();
        strictDoc = new JCheckBox("Strict documentation checks");
        smartCloseEnd = new JCheckBox("Automatically close statements with end");
        showWordsInFile = new JCheckBox("Show words from current file");
        enforceTypeSafety = new JCheckBox("Enforce type safety");
        nilStrict = new JCheckBox("Strict nil checks");
        recognizeGlobalNameAsCheckBox = new JCheckBox("Recognize global names as types");
        additionalRoots = new LuaAdditionalSourcesRootPanel();
        additionalRoots.setPreferredSize(new Dimension(480, 130));
        enableGenericCheckBox = new JCheckBox("Enable generics");
        languageLevel = new JComboBox<>();
        requireFunctionNames = new JTextField();
        tooLargerFileThreshold = new JTextField();

        ueProcessNamesField = new JTextField();
        debugProcessBlacklistField = new JTextField();
        customHelperPathField = new JTextField();
        browseCustomHelperPathButton = new JButton("Browse...");
        customHelperExtNameField = new JTextField();
        browseCustomHelperExtNameButton = new JButton("Browse...");
        enableDevModeCheckBox = new JCheckBox("Load debugger scripts from project sources");

        enableCustomFileTemplateCheckBox = new JCheckBox("Enable custom Lua file template");
        customFileTemplateTextArea = new JTextArea(14, 60);
        customFileTemplateTextArea.setLineWrap(false);
        enableFileNameReplacementCheckBox = new JCheckBox("Replace file-name placeholder");
        fileNamePlaceholderField = new JTextField();

        contentPanel = new JTabbedPane();
        contentPanel.addTab("Language", createLanguagePanel());
        contentPanel.addTab("Debugger", createDebuggerPanel());
        contentPanel.addTab("File Template", createTemplatePanel());

        myPanel = new JScrollPane(contentPanel);
        myPanel.setBorder(BorderFactory.createEmptyBorder());
        myPanel.getVerticalScrollBar().setUnitIncrement(16);
    }

    private JPanel createLanguagePanel() {
        JPanel panel = createGridPanel();
        int row = 0;
        addRow(panel, row++, "Constructor functions", constructorNames);
        addRow(panel, row++, "Require-like functions", requireFunctionNames);
        addRow(panel, row++, "Large file threshold (KB)", tooLargerFileThreshold);
        addRow(panel, row++, "Lua language level", languageLevel);
        addFullRow(panel, row++, strictDoc);
        addFullRow(panel, row++, smartCloseEnd);
        addFullRow(panel, row++, showWordsInFile);
        addFullRow(panel, row++, enforceTypeSafety);
        addFullRow(panel, row++, nilStrict);
        addFullRow(panel, row++, recognizeGlobalNameAsCheckBox);
        addFullRow(panel, row++, enableGenericCheckBox);
        addFullRow(panel, row++, additionalRoots);
        addVerticalGlue(panel, row);
        return panel;
    }

    private JPanel createDebuggerPanel() {
        JPanel panel = createGridPanel();
        int row = 0;
        addRow(panel, row++, "Unreal Engine process names", ueProcessNamesField);
        addRow(panel, row++, "Process blacklist", debugProcessBlacklistField);
        addRow(panel, row++, "Custom helper directory", withBrowseButton(customHelperPathField, browseCustomHelperPathButton));
        addRow(panel, row++, "Custom helper script", withBrowseButton(customHelperExtNameField, browseCustomHelperExtNameButton));
        addFullRow(panel, row++, enableDevModeCheckBox);
        addVerticalGlue(panel, row);
        return panel;
    }

    private JPanel createTemplatePanel() {
        JPanel panel = createGridPanel();
        int row = 0;
        addFullRow(panel, row++, enableCustomFileTemplateCheckBox);
        addFullRow(panel, row++, new JScrollPane(customFileTemplateTextArea));
        addFullRow(panel, row++, enableFileNameReplacementCheckBox);
        addRow(panel, row++, "File-name placeholder", fileNamePlaceholderField);
        addVerticalGlue(panel, row);
        return panel;
    }

    private static JPanel createGridPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return panel;
    }

    private static JPanel withBrowseButton(JTextField field, JButton button) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    private static void addRow(JPanel panel, int row, String label, JComponent component) {
        GridBagConstraints labelConstraints = constraints(0, row, 0.0);
        labelConstraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints componentConstraints = constraints(1, row, 1.0);
        componentConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, componentConstraints);
    }

    private static void addFullRow(JPanel panel, int row, JComponent component) {
        GridBagConstraints constraints = constraints(0, row, 1.0);
        constraints.gridwidth = 2;
        constraints.fill = component instanceof JScrollPane || component instanceof LuaAdditionalSourcesRootPanel
                ? GridBagConstraints.BOTH
                : GridBagConstraints.HORIZONTAL;
        constraints.weighty = component instanceof JScrollPane || component instanceof LuaAdditionalSourcesRootPanel ? 1.0 : 0.0;
        panel.add(component, constraints);
    }

    private static void addVerticalGlue(JPanel panel, int row) {
        GridBagConstraints constraints = constraints(0, row, 1.0);
        constraints.gridwidth = 2;
        constraints.weighty = 1.0;
        constraints.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), constraints);
    }

    private static GridBagConstraints constraints(int column, int row, double weightX) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.weightx = weightX;
        constraints.insets = new Insets(4, 4, 4, 8);
        return constraints;
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
                settings.getLanguageLevel() != languageLevel.getSelectedItem() ||
                !Arrays.equals(projectSettings.getUeProcessNames(), getProcessNamesFromTextField()) ||
                !Arrays.equals(projectSettings.getDebugProcessBlacklist(), getDebugProcessBlacklistFromTextField()) ||
                !StringUtil.equals(projectSettings.getCustomHelperPath(), customHelperPathField.getText()) ||
                !StringUtil.equals(projectSettings.getCustomHelperExtName(), customHelperExtNameField.getText()) ||
                settings.getEnableCustomFileTemplate() != enableCustomFileTemplateCheckBox.isSelected() ||
                !StringUtil.equals(settings.getCustomFileTemplate(), customFileTemplateTextArea.getText()) ||
                settings.getEnableFileNameReplacement() != enableFileNameReplacementCheckBox.isSelected() ||
                !StringUtil.equals(settings.getFileNamePlaceholder(), fileNamePlaceholderField.getText()) ||
                projectSettings.getEnableDevMode() != enableDevModeCheckBox.isSelected() ||
                !Arrays.equals(projectSettings.getAdditionalSourcesRoot(), additionalRoots.getRoots(), String::compareTo);
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
        projectSettings.setAdditionalSourcesRoot(additionalRoots.getRoots());
        SourceMappingService.Companion.getInstance(project).invalidate();
        settings.setEnableGeneric(enableGenericCheckBox.isSelected());
        
        //Custom helper path
        projectSettings.setCustomHelperPath(customHelperPathField.getText());
        
        //Custom helper ext name
        projectSettings.setCustomHelperExtName(customHelperExtNameField.getText());
        
        // 文件模板设置
        settings.setEnableCustomFileTemplate(enableCustomFileTemplateCheckBox.isSelected());
        settings.setCustomFileTemplate(customFileTemplateTextArea.getText());
        settings.setEnableFileNameReplacement(enableFileNameReplacementCheckBox.isSelected());
        settings.setFileNamePlaceholder(fileNamePlaceholderField.getText());
        
        // 开发模式设置
        projectSettings.setEnableDevMode(enableDevModeCheckBox.isSelected());
        
        // 将逗号分隔的字符串转换为进程名称数组
        String processNamesText = ueProcessNamesField.getText().trim();
        if (processNamesText.isEmpty()) {
            projectSettings.setUeProcessNames(new String[0]);
        } else {
            String[] processNames = processNamesText.split(",");
            for (int i = 0; i < processNames.length; i++) {
                processNames[i] = processNames[i].trim();
            }
            projectSettings.setUeProcessNames(processNames);
        }
        
        // 将逗号分隔的字符串转换为调试器进程黑名单数组
        String blacklistText = debugProcessBlacklistField.getText().trim();
        if (blacklistText.isEmpty()) {
            projectSettings.setDebugProcessBlacklist(new String[0]);
        } else {
            String[] blacklistProcesses = blacklistText.split(",");
            for (int i = 0; i < blacklistProcesses.length; i++) {
                blacklistProcesses[i] = blacklistProcesses[i].trim();
            }
            projectSettings.setDebugProcessBlacklist(blacklistProcesses);
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
        additionalRoots.setRoots(projectSettings.getAdditionalSourcesRoot());
        enableGenericCheckBox.setSelected(settings.getEnableGeneric());
        requireFunctionNames.setText(settings.getRequireLikeFunctionNamesString());
        tooLargerFileThreshold.setText(String.valueOf(settings.getTooLargerFileThreshold()));
        languageLevel.setSelectedItem(settings.getLanguageLevel());
        
        // 将进程名称数组转换为逗号分隔的字符串
        String[] processNames = projectSettings.getUeProcessNames();
        if (processNames != null && processNames.length > 0) {
            ueProcessNamesField.setText(String.join(", ", processNames));
        } else {
            ueProcessNamesField.setText("");
        }
        
        // Reset custom helper path
        customHelperPathField.setText(projectSettings.getCustomHelperPath());
        
        // Reset custom helper ext name
        customHelperExtNameField.setText(projectSettings.getCustomHelperExtName());
        
        // Reset 文件模板设置
        enableCustomFileTemplateCheckBox.setSelected(settings.getEnableCustomFileTemplate());
        customFileTemplateTextArea.setText(settings.getCustomFileTemplate());
        enableFileNameReplacementCheckBox.setSelected(settings.getEnableFileNameReplacement());
        fileNamePlaceholderField.setText(settings.getFileNamePlaceholder());
        
        // Reset 开发模式设置
        enableDevModeCheckBox.setSelected(projectSettings.getEnableDevMode());
        debugProcessBlacklistField.setText(String.join(", ", projectSettings.getDebugProcessBlacklist()));
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
