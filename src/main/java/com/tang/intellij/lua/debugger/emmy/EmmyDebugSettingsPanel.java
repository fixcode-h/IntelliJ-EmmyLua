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

package com.tang.intellij.lua.debugger.emmy;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfoRt;
import com.tang.intellij.lua.lang.LuaFileType;
import com.tang.intellij.lua.debugger.DebugLogLevel;
import com.tang.intellij.lua.psi.LuaFileUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.util.Objects;

public class EmmyDebugSettingsPanel extends SettingsEditor<EmmyDebugConfiguration> implements DocumentListener {
    private final JComboBox<EmmyDebugTransportType> typeCombox = new JComboBox<>();
    private final JTextField tcpHostInput = new JTextField();
    private final JTextField tcpPortInput = new JTextField();
    private final JLabel tcpHostLabel = new JLabel("Host:");
    private final JLabel tcpPortLabel = new JLabel("Port:");
    private final JTextField pipelineInput = new JTextField();
    private final JLabel pipeNameLabel = new JLabel("Pipe:");
    private final JPanel panel = new JPanel(new GridBagLayout());
    private final JPanel codePanel = new JPanel(new BorderLayout());
    private final JCheckBox waitIDECheckBox = new JCheckBox("Block the program and wait for the IDE.");
    private final JCheckBox breakWhenIDEConnectedCheckBox = new JCheckBox("Force break when connected.");
    private final JCheckBox autoAuthorizeCliCheckBox =
        new JCheckBox("Auto-authorise local CLI/AI clients (they can read values and control execution).");
    private final JComboBox<DebugLogLevel> logLevelComboBox = new JComboBox<>();

    private final JRadioButton x64RadioButton = new JRadioButton("x64");
    private final JRadioButton x86RadioButton = new JRadioButton("x86");
    private final JPanel winArchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JLabel winArchLabel = new JLabel("Architecture:");
    private final ButtonGroup winArchGroup = new ButtonGroup();

    private EditorEx editorEx;

    public EmmyDebugSettingsPanel(Project project) {
        buildPanel();

        // type
        DefaultComboBoxModel<EmmyDebugTransportType> model = new DefaultComboBoxModel<>();
        model.addElement(EmmyDebugTransportType.TCP_CLIENT);
        model.addElement(EmmyDebugTransportType.TCP_SERVER);
        /*for (EmmyDebugTransportType value : EmmyDebugTransportType.values()) {
            model.addElement(value);
        }*/
        typeCombox.setModel(model);
        typeCombox.addActionListener(e -> {
            setType((EmmyDebugTransportType) typeCombox.getSelectedItem());
            onChanged();
        });
        // tcp
        tcpHostInput.setText("localhost");
        tcpHostInput.getDocument().addDocumentListener(this);
        tcpPortInput.setText("9966");
        tcpPortInput.setDocument(new IntegerDocument());
        tcpPortInput.getDocument().addDocumentListener(this);
        // pipe
        pipelineInput.setText("emmylua");
        pipelineInput.getDocument().addDocumentListener(this);

        waitIDECheckBox.addActionListener(e -> onChanged());
        breakWhenIDEConnectedCheckBox.addActionListener(e -> onChanged());
        autoAuthorizeCliCheckBox.addActionListener(e -> onChanged());
        autoAuthorizeCliCheckBox.setToolTipText(
            "勾选后，本会话建立时会自动把本机 CLI/AI 客户端（emmy-debug）加入授权名单。\n" +
                "仅对受信任的项目生效；该能力可读取被调试进程的值并控制执行。\n" +
                "已授予的访问可随时用 Tools → 撤销 EmmyLua CLI 调试权限 撤回。");

        logLevelComboBox.setModel(new DefaultComboBoxModel<>(DebugLogLevel.values()));
        logLevelComboBox.setSelectedItem(DebugLogLevel.RUNTIME);
        logLevelComboBox.setToolTipText("Log0=调试，Log1=运行，Log2=警告，Log3=错误");
        logLevelComboBox.addActionListener(e -> onChanged());

        // arch
        winArchPanel.setVisible(SystemInfoRt.isWindows);
        winArchLabel.setVisible(SystemInfoRt.isWindows);
        winArchGroup.add(x64RadioButton);
        winArchGroup.add(x86RadioButton);
        winArchPanel.add(x64RadioButton);
        winArchPanel.add(x86RadioButton);
        x64RadioButton.setSelected(true);
        x64RadioButton.addChangeListener(e -> onChanged());
        x86RadioButton.addChangeListener(e -> onChanged());

        // editor
        editorEx = createEditorEx(project);
        codePanel.add(editorEx.getComponent(), BorderLayout.CENTER);

        setType(getType());
        breakWhenIDEConnectedCheckBox.setEnabled(false);
        updateCode();
    }

    private void buildPanel() {
        addRow(0, new JLabel("Connection:"), typeCombox);
        addRow(1, tcpHostLabel, tcpHostInput);
        addRow(2, tcpPortLabel, tcpPortInput);
        addRow(3, pipeNameLabel, pipelineInput);
        addRow(4, new JLabel("Log level:"), logLevelComboBox);
        addRow(5, winArchLabel, winArchPanel);
        addRow(6, new JLabel(), waitIDECheckBox);
        addRow(7, new JLabel(), breakWhenIDEConnectedCheckBox);
        addRow(8, new JLabel(), autoAuthorizeCliCheckBox);

        GridBagConstraints hintConstraints = new GridBagConstraints();
        hintConstraints.gridx = 0;
        hintConstraints.gridy = 9;
        hintConstraints.gridwidth = 2;
        hintConstraints.anchor = GridBagConstraints.WEST;
        hintConstraints.insets = new Insets(8, 0, 4, 0);
        panel.add(new JLabel("Copy following code and paste into the Lua code entry."), hintConstraints);

        GridBagConstraints codeConstraints = new GridBagConstraints();
        codeConstraints.gridx = 0;
        codeConstraints.gridy = 10;
        codeConstraints.gridwidth = 2;
        codeConstraints.weightx = 1.0;
        codeConstraints.weighty = 1.0;
        codeConstraints.fill = GridBagConstraints.BOTH;
        panel.add(codePanel, codeConstraints);
    }

    private void addRow(int row, JComponent label, JComponent component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(2, 0, 2, 8);
        panel.add(label, labelConstraints);

        GridBagConstraints componentConstraints = new GridBagConstraints();
        componentConstraints.gridx = 1;
        componentConstraints.gridy = row;
        componentConstraints.weightx = 1.0;
        componentConstraints.fill = GridBagConstraints.HORIZONTAL;
        componentConstraints.insets = new Insets(2, 0, 2, 0);
        panel.add(component, componentConstraints);
    }

    private void onChanged() {
        if (isClient()) {
            breakWhenIDEConnectedCheckBox.setEnabled(waitIDECheckBox.isSelected());
        } else {
            breakWhenIDEConnectedCheckBox.setEnabled(true);
        }
        fireEditorStateChanged();
        updateCode();
    }

    @Override
    protected void resetEditorFrom(@NotNull EmmyDebugConfiguration configuration) {
        typeCombox.setSelectedItem(configuration.getType());
        setType(configuration.getType());

        tcpHostInput.setText(configuration.getHost());
        tcpPortInput.setText(String.valueOf(configuration.getPort()));

        pipelineInput.setText(configuration.getPipeName());
        logLevelComboBox.setSelectedItem(configuration.getLogLevel());
        autoAuthorizeCliCheckBox.setSelected(configuration.getAutoAuthorizeCliClients());

        if (SystemInfoRt.isWindows) {
            if (configuration.getWinArch() == EmmyWinArch.X64) {
                x64RadioButton.setSelected(true);
            } else {
                x86RadioButton.setSelected(true);
            }
        }
    }

    @Override
    protected void applyEditorTo(@NotNull EmmyDebugConfiguration configuration) {
        EmmyDebugTransportType type = (EmmyDebugTransportType) typeCombox.getSelectedItem();
        assert type != null;
        configuration.setType(type);

        configuration.setHost(tcpHostInput.getText());
        configuration.setPort(Integer.parseInt(tcpPortInput.getText()));

        configuration.setPipeName(pipelineInput.getText());
        configuration.setLogLevel((DebugLogLevel) logLevelComboBox.getSelectedItem());
        configuration.setAutoAuthorizeCliClients(autoAuthorizeCliCheckBox.isSelected());
        if (SystemInfoRt.isWindows) {
            configuration.setWinArch(x64RadioButton.isSelected() ? EmmyWinArch.X64 : EmmyWinArch.X86);
        }
    }

    protected void setType(EmmyDebugTransportType type) {
        boolean isTCP = type == EmmyDebugTransportType.TCP_CLIENT || type == EmmyDebugTransportType.TCP_SERVER;
        tcpHostLabel.setVisible(isTCP);
        tcpPortLabel.setVisible(isTCP);
        tcpHostInput.setVisible(isTCP);
        tcpPortInput.setVisible(isTCP);

        pipeNameLabel.setVisible(!isTCP);
        pipelineInput.setVisible(!isTCP);

        waitIDECheckBox.setVisible(isClient());
    }

    private boolean isClient() {
        EmmyDebugTransportType type = getType();
        return type == EmmyDebugTransportType.TCP_CLIENT || type == EmmyDebugTransportType.PIPE_CLIENT;
    }

    private EmmyDebugTransportType getType() {
        return (EmmyDebugTransportType) typeCombox.getSelectedItem();
    }

    private String getHost() {
        return tcpHostInput.getText();
    }

    private int getPort() {
        int port = 0;
        try {
            port = Integer.parseInt(tcpPortInput.getText());
        } catch (Exception ignored) {
        }
        return port;
    }

    private String getPipeName() {
        return pipelineInput.getText();
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        return panel;
    }

    @Override
    protected void disposeEditor() {
        if (editorEx != null) {
            EditorFactory.getInstance().releaseEditor(editorEx);
            editorEx = null;
        }
    }

    private EditorEx createEditorEx(Project project) {
        EditorFactory editorFactory = EditorFactory.getInstance();
        Document editorDocument = editorFactory.createDocument("");
        return (EditorEx)editorFactory.createEditor(editorDocument, project, LuaFileType.INSTANCE, false);
    }

    private void updateCode() {
        ApplicationManager.getApplication().runWriteAction(this::updateCodeImpl);
    }

    private String getDebuggerFolder() {
        if (SystemInfoRt.isWindows)
            return LuaFileUtil.INSTANCE.getPluginVirtualFile("debugger/emmy/windows");
        if (SystemInfoRt.isMac)
            return LuaFileUtil.INSTANCE.getPluginVirtualFile("debugger/emmy/mac");
        return LuaFileUtil.INSTANCE.getPluginVirtualFile("debugger/emmy/linux");
    }

    private void updateCodeImpl() {
        StringBuilder sb = new StringBuilder();
        if (SystemInfoRt.isWindows) {
            EmmyWinArch arch = x64RadioButton.isSelected() ? EmmyWinArch.X64 : EmmyWinArch.X86;
            sb.append("package.cpath = package.cpath .. ';")
                    .append(getDebuggerFolder())
                    .append("/")
                    .append(arch.getDesc())
                    .append("/?.dll'\n");
        } else if (SystemInfoRt.isMac) {
            sb.append("package.cpath = package.cpath .. ';")
                    .append(getDebuggerFolder())
                    .append("/")
                    .append(Objects.equals(System.getProperty("os.arch"), "arm64") ? "arm64": "x64")
                    .append("/?.dylib'\n");
        } else {
            sb.append("package.cpath = package.cpath .. ';")
                    .append(getDebuggerFolder())
                    .append("/?.so'\n");
        }
        sb.append("local dbg = require('emmy_core')\n");
        EmmyDebugTransportType type = getType();
        if (type == EmmyDebugTransportType.PIPE_CLIENT) {
            sb.append("dbg.pipeListen('").append(getPipeName()).append("')\n");
        }
        else if (type == EmmyDebugTransportType.PIPE_SERVER) {
            sb.append("dbg.pipeConnect('").append(getPipeName()).append("')\n");
        }
        else if (type == EmmyDebugTransportType.TCP_CLIENT) {
            sb.append("dbg.tcpListen('").append(getHost()).append("', ").append(getPort()).append(")\n");
        }
        else if (type == EmmyDebugTransportType.TCP_SERVER) {
            sb.append("dbg.tcpConnect('").append(getHost()).append("', ").append(getPort()).append(")\n");
        }

        if (isClient()) {
            if (waitIDECheckBox.isSelected()) {
                sb.append("dbg.waitIDE()\n");
                if (breakWhenIDEConnectedCheckBox.isSelected()) {
                    sb.append("dbg.breakHere()\n");
                }
            }
        } else {
            if (breakWhenIDEConnectedCheckBox.isSelected()) {
                sb.append("dbg.breakHere()\n");
            }
        }
        editorEx.getDocument().setText(sb.toString());
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
        onChanged();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        onChanged();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        onChanged();
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
