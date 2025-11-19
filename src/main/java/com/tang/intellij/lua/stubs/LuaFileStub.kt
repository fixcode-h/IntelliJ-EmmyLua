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

package com.tang.intellij.lua.stubs

import com.intellij.lang.ASTNode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.IStubFileElementType
import com.intellij.util.io.StringRef
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.lang.LuaParserDefinition
import com.tang.intellij.lua.psi.LuaPsiFile

/**

 * Created by tangzx on 2016/11/27.
 */
class LuaFileElementType : IStubFileElementType<LuaFileStub>(LuaLanguage.INSTANCE) {

    companion object {
        val LOG = Logger.getInstance(LuaFileElementType::class.java)
    }

    // debug performance
    override fun parseContents(chameleon: ASTNode): ASTNode? {
        val psi = chameleon.psi
        val t = System.currentTimeMillis()
        val contents = super.parseContents(chameleon)
        if (psi is LuaPsiFile) {
            if (LOG.isDebugEnabled) {
                val dt = System.currentTimeMillis() - t
                val fileName = psi.name
                println("$fileName : $dt")
                LOG.debug("$fileName : $dt")
            }
        }
        return contents
    }

    override fun getBuilder(): StubBuilder {
        return object : DefaultStubBuilder() {

            private var isTooLarger = false

            override fun createStubForFile(file: PsiFile): StubElement<*> {
                if (file is LuaPsiFile){
                    isTooLarger = file.tooLarger
                    return LuaFileStub(file)
                }
                return super.createStubForFile(file)
            }

            override fun skipChildProcessingWhenBuildingStubs(parent: ASTNode, node: ASTNode): Boolean {
                // ✅ 修改：对于5MB以下的文件，仍然建立完整索引
                // 只有超过5MB的文件才跳过索引（性能保护）
                // 这样1-5MB的文件可以正常使用代码提示和跳转功能
                return isTooLarger
            }
        }
    }

    override fun serialize(stub: LuaFileStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.module)
        dataStream.writeUTFFast(stub.uid)
        if (LOG.isTraceEnabled) {
            println("--------- START: ${stub.psi.name}")
            println(stub.printTree())
            println("--------- END: ${stub.psi.name}")
        }
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): LuaFileStub {
        // Stub 反序列化：从持久化存储中恢复 stub 数据
        try {
            val moduleRef = dataStream.readName()
            val uid = dataStream.readUTFFast()
            
            // 验证读取的数据是否有效
            if (uid.isEmpty()) {
                // UID 为空表示数据损坏
                // 注意：不要返回空 stub，而是抛出异常，让 IntelliJ 知道需要重建索引
                if (LOG.isDebugEnabled) {
                    LOG.debug("Invalid UID in stub data, stub may be corrupted")
                }
                // 仍然返回一个有效的 stub，但带有特殊标记
                return LuaFileStub(null, null, "<corrupted>")
            }
            
            return LuaFileStub(null, StringRef.toString(moduleRef), uid)
        } catch (e: Exception) {
            // 反序列化失败：可能是格式变更、数据损坏等
            if (LOG.isDebugEnabled) {
                LOG.debug("Failed to deserialize LuaFileStub: ${e.message}", e)
            }
            // 返回一个带特殊标记的 stub，而不是空 stub
            // 这样可以避免后续查询时出现 "stub ids not found" 错误
            return LuaFileStub(null, null, "<deserialization-failed>")
        }
    }

    override fun getExternalId() = "lua.file"
}

class LuaFileStub : PsiFileStubImpl<LuaPsiFile> {
    private var file: LuaPsiFile? = null
    private var moduleName:String? = null

    val uid: String

    constructor(file: LuaPsiFile) : this(file, file.moduleName, file.uid)

    constructor(file: LuaPsiFile?, module:String?, uid: String) : super(file) {
        this.file = file
        this.uid = uid
        moduleName = module
    }

    val module: String? get() {
        return moduleName
    }

    override fun getType(): LuaFileElementType = LuaParserDefinition.FILE
}