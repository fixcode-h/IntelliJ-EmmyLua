// This is a generated file. Not intended for manual editing.
package com.tang.intellij.lua.comment.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.StubBasedPsiElement;
import com.tang.intellij.lua.stubs.LuaDocTagEnumStub;
import com.tang.intellij.lua.ty.ITy;

public interface LuaDocTagEnum extends LuaDocTag, PsiNameIdentifierOwner, StubBasedPsiElement<LuaDocTagEnumStub> {

  @Nullable
  LuaDocCommentString getCommentString();

  @NotNull
  PsiElement getId();

  @NotNull
  ITy getType();

  @NotNull
  PsiElement getNameIdentifier();

  @NotNull
  PsiElement setName(@NotNull String newName);

  @NotNull
  String getName();

  int getTextOffset();

}