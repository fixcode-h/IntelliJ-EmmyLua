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

package com.tang.intellij.lua;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;
import java.util.Locale;
import java.util.ResourceBundle;

public class LuaBundle extends DynamicBundle {
    
    // 强制使用中文的消息方法
    public static String messageZh(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, @NotNull Object... params) {
        try {
            ResourceBundle zhBundle = ResourceBundle.getBundle(BUNDLE, Locale.SIMPLIFIED_CHINESE);
            String message = zhBundle.getString(key);
            if (params.length > 0) {
                return java.text.MessageFormat.format(message, params);
            }
            return message;
        } catch (Exception e) {
            // 如果中文资源包失败，回退到默认方法
            return Instance.getMessage(key, params);
        }
    }
    
    public static String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, @NotNull Object... params) {
        // 检查当前语言环境，如果是中文相关，强制使用中文资源包
        Locale currentLocale = Locale.getDefault();
        if ("zh".equals(currentLocale.getLanguage()) || 
            "CN".equals(currentLocale.getCountry()) ||
            currentLocale.equals(Locale.SIMPLIFIED_CHINESE) ||
            currentLocale.equals(Locale.TRADITIONAL_CHINESE)) {
            return messageZh(key, params);
        }
        
        return Instance.getMessage(key, params);
    }



    private static final LuaBundle Instance = new LuaBundle();
    @NonNls
    private static final String BUNDLE = "LuaBundle";

    private LuaBundle() {
        super(BUNDLE);
    }
}
