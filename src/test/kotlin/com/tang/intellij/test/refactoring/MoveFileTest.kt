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

package com.tang.intellij.test.refactoring

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.tang.intellij.test.LuaTestBase

class MoveFileTest : LuaTestBase() {
    override fun getTestDataPath() = "src/test/resources"

    fun testMoveFile() {
        myFixture.copyDirectoryToProject("refactoring/moveFile/before", "")
        val rootDir = myFixture.findFileInTempDir("")
        val fileToMove = "A.lua"
        val targetDirName = "to"
        val child = rootDir.findFileByRelativePath(fileToMove)
        assertNotNull("File $fileToMove not found", child)
        val psiManager = PsiManager.getInstance(project)
        val file = psiManager.findFile(child!!)!!

        val target = rootDir.findChild(targetDirName)
        assertNotNull("File $targetDirName not found", target)
        val targetDirectory = psiManager.findDirectory(target!!)!!

        val processor = MoveFilesOrDirectoriesProcessor(
            project,
            arrayOf<PsiElement>(file),
            targetDirectory,
            false,
            false,
            null,
            null
        )
        ProgressManager.getInstance().runProcess({ processor.run() }, EmptyProgressIndicator())

        FileDocumentManager.getInstance().saveAllDocuments()
        assertNull(rootDir.findChild("A.lua"))
        assertNotNull(rootDir.findFileByRelativePath("to/A.lua"))
        assertEquals("require(\"to.A\")", rootDir.findChild("B.lua")!!.contentsToByteArray().toString(Charsets.UTF_8))
    }
}
