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

package com.tang.intellij.lua.psi;

import com.intellij.lang.LighterASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.WhitespacesAndCommentsBinder;
import com.intellij.lang.WhitespacesBinders;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.tang.intellij.lua.psi.parser.LuaExpressionParser;
import com.tang.intellij.lua.psi.parser.LuaStatementParser;

import static com.tang.intellij.lua.psi.LuaTypes.*;

@SuppressWarnings("unused")
public class LuaParserUtil extends GeneratedParserUtilBase {

    /**
     * right:
     * --- comment
     * local obj
     *
     * wrong:
     * --- comment
     *
     * local obj
     */
    public static WhitespacesAndCommentsBinder MY_LEFT_COMMENT_BINDER = (list, b, tokenTextGetter) -> {
        int lines = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            IElementType type = list.get(i);
            if (type == DOC_COMMENT) {
                return i;
            } else {
                CharSequence sequence = tokenTextGetter.get(i);
                lines += StringUtil.getLineBreakCount(sequence);
                if (lines > 1) {
                    break;
                }
            }
        }
        return list.size();
    };

    public static WhitespacesAndCommentsBinder MY_RIGHT_COMMENT_BINDER = (list, b, tokenTextGetter) -> {
        for (int i = 0; i < list.size(); i++) {
            IElementType type = list.get(i);
            if (type == DOC_COMMENT) {
                return i + 1;
            } else {
                CharSequence sequence = tokenTextGetter.get(i);
                if (StringUtil.contains(sequence, "\n")) {
                    break;
                }
            }
        }
        return 0;
    };

    public static boolean repeat(PsiBuilder builder_, int level_, Parser parser, int times) {
        PsiBuilder.Marker marker = builder_.mark();
        boolean r = true;
        for (int i = 0; r && i < times; i++) {
            r = parser.parse(builder_, level_);
        }
        marker.rollbackTo();
        return r;
    }

    public static boolean checkType(PsiBuilder builder_, int level_, IElementType type) {
        LighterASTNode marker = builder_.getLatestDoneMarker();
        return marker != null && marker.getTokenType() == type;
    }

    public static boolean lazyBlock(PsiBuilder builder_, int level_) {
        int i = 0;
        IElementType begin=  builder_.rawLookup(--i);
        while (begin == TokenType.WHITE_SPACE)
            begin = builder_.rawLookup(--i);

        if (begin != null) {
            PsiBuilder.Marker marker = builder_.mark();
            marker.setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, null);
            if (begin == RPAREN)
                begin = FUNCTION;

            matchStart(true, builder_, 0, begin);
            marker.collapse(BLOCK);
            marker.setCustomEdgeTokenBinders(null, WhitespacesBinders.GREEDY_RIGHT_BINDER);
        }
        return true;
    }

    private static TokenSet END_SET = TokenSet.create(END);
    private static TokenSet IF_SKIPS = TokenSet.create(THEN, ELSE, ELSEIF);
    private static TokenSet REPEAT_TYPES = TokenSet.create(UNTIL);
    private static TokenSet THEN_TYPES1 = TokenSet.create(ELSE, ELSEIF, END);
    private static TokenSet THEN_SKIPS2 = TokenSet.create(ELSE, ELSEIF);
    private static TokenSet BRACE_L_SET = TokenSet.create(LCURLY, LBRACK, LPAREN);
    private static TokenSet BRACE_R_SET = TokenSet.create(RCURLY, RBRACK, RPAREN);
    
    /**
     * 恢复集合：一旦遇到这些词，立即停止当前 Block 的贪婪匹配
     * 主要用于防止因缺少 end 导致后续代码被错误折叠
     * 包含 FUNCTION 关键字：当遇到新函数定义时，即使上一个函数缺少 end，
     * 也要强制结束当前 Block 的解析，保护后续代码不被吞噬
     */
    private static TokenSet RECOVERY_SET = TokenSet.create(FUNCTION);

    private static boolean matchStart(boolean advanced, PsiBuilder builder, int level, IElementType begin) {
        if (begin == DO) {
            return matchEnd(advanced, builder, level, TokenSet.EMPTY, END_SET);
        }
        else if (begin == REPEAT) {
            return matchEnd(advanced, builder, level, TokenSet.EMPTY, REPEAT_TYPES);
        }
        else if (begin == IF) {
            return matchEnd(advanced, builder ,level, IF_SKIPS, END_SET);
        }
        else if (begin == THEN) {
            if (level == 0)
                return matchEnd(advanced, builder, level, TokenSet.EMPTY, THEN_TYPES1);
            else
                return matchEnd(advanced, builder, level, THEN_SKIPS2, END_SET);
        }
        else if (begin == ELSE) {
            return matchEnd(advanced, builder, level, TokenSet.EMPTY, END_SET);
        }
        else if (begin == FUNCTION) {
            return matchEnd(advanced, builder, level, TokenSet.EMPTY, END_SET);
        }
//        else if (BRACE_L_SET.contains(begin)) {
//            return matchBrace(advanced, builder, level, getRBrace(begin));
//        }
        return false;
    }

    private static IElementType getRBrace(IElementType type) {
        if (type == LCURLY) return RCURLY;
        if (type == LPAREN) return RPAREN;
        if (type == LBRACK) return RBRACK;
        return null;
    }

    private static boolean matchBrace(boolean advanced, PsiBuilder builder, int level, IElementType end) {
        if (!advanced)
            builder.advanceLexer();
        IElementType type = builder.getTokenType();
        while (true) {
            if (type == null || builder.eof()) {
                return false;
            }

            while (true) {
                if (type == end) {
                    if (level != 0)
                        builder.advanceLexer();
                    return true;
                }
                boolean matchBrace = false;
                if (BRACE_L_SET.contains(type)) {
                    matchBrace = matchBrace(false, builder, level + 1, getRBrace(type));
                }
                if (!matchBrace) break;
                type = builder.getTokenType();
            }

            builder.advanceLexer();
            type = builder.getTokenType();
        }
    }

    private static boolean matchEnd(boolean advanced, PsiBuilder builder, int level, TokenSet skips, TokenSet types) {
        if (!advanced)
            builder.advanceLexer();
        IElementType type = builder.getTokenType();

        while (true) {
            // 1. 基础结束条件：文件末尾
            if (type == null || builder.eof()) {
                return false;
            }

            // 2. 【核心优化】止损策略：防止吞噬后续代码
            // 如果遇到了 FUNCTION 关键字，且它不是当前我们正在等待的结束符（types），
            // 那么大概率是用户开始写下一个函数了，但上一个函数忘了写 end。
            // 此时直接返回 false，强制结束当前 Block 的解析，防止吞噬后续代码。
            //
            // 注：这会牺牲掉 "function A() function B() end end" 这种嵌套定义的折叠支持，
            // 但换来的是编辑过程中极其稳定的体验。对于 LazyBlock 来说，这是绝对值得的 trade-off。
            if (RECOVERY_SET.contains(type) && !types.contains(type)) {
                return false;
            }

            // 3. 循环匹配逻辑
            while (!skips.contains(type)) {
                // 3.1 找到了预期的结束符 (如 end, until, elseif...) -> 成功
                if (types.contains(type)) {
                    if (level != 0)
                        builder.advanceLexer();
                    return true;
                }

                // 3.2 尝试解析嵌套结构 (如内部的 if, do, repeat)
                // bug fix: https://github.com/EmmyLua/IntelliJ-EmmyLua/issues/276
                if (type == UNTIL) {
                    return true;
                }

                // 注意：由于我们在步骤 2 已经拦截了 FUNCTION，
                // 如果 matchStart 里包含 FUNCTION 的处理，这里实际上不会再进入嵌套函数的解析。
                // 这正是我们想要的效果：视嵌套函数为平级结构，从而保护后续代码。
                boolean isMatched = matchStart(false, builder, level + 1, type);
                
                if (isMatched) {
                    // 如果嵌套结构成功解析（例如内部的 if ... end），重新获取 type 继续循环
                    type = builder.getTokenType();
                    
                    // 【二次检查】防止 matchStart 消耗完后，紧接着就是一个 FUNCTION
                    // 例如： if cond then ... end function B() ...
                    if (RECOVERY_SET.contains(type) && !types.contains(type)) {
                        return false;
                    }
                } else {
                    // 3.3 如果不是嵌套结构，准备消耗当前 Token
                    
                    // 【三次检查】在暴力 advance 之前，最后确认一次
                    // 虽然步骤 2 已经检查过，但为了逻辑严密防止漏网之鱼（如 skips 逻辑变动）
                    if (RECOVERY_SET.contains(type)) {
                        return false;
                    }
                    
                    builder.advanceLexer();
                    type = builder.getTokenType();
                }
                
                // 循环内检查 EOF
                if (type == null || builder.eof()) {
                    return false;
                }
            }

            builder.advanceLexer();
            type = builder.getTokenType();
        }
    }

    public static boolean parseExpr(PsiBuilder b, int l) {
        return LuaExpressionParser.INSTANCE.parseExpr(b, l) != null;
    }

    public static boolean parseStatement(PsiBuilder b, int l) {
        return LuaStatementParser.INSTANCE.parseStatement(b, l) != null;
    }
}
