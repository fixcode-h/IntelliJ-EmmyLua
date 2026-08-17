package com.tang.intellij.lua.editor.formatter;

import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;

/**
 * Bridges the CodeStyleSettingsProvider API difference between IDEA 2025.1 and 2025.2.
 */
public abstract class LuaCodeStyleSettingsProviderCompat extends CodeStyleSettingsProvider {
    public String getConfigurableId() {
        return "preferences.sourceCode.Lua";
    }
}
