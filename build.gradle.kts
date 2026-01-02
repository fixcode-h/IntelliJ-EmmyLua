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

import de.undercouch.gradle.tasks.download.*
import org.apache.tools.ant.taskdefs.condition.Os
import java.io.ByteArrayOutputStream

plugins {
    id("org.jetbrains.intellij.platform").version("2.7.0")
    id("org.jetbrains.kotlin.jvm").version("2.1.20")
    id("de.undercouch.download").version("5.3.0")
}

data class BuildData(
    val ideaSDKShortVersion: String,
    // https://www.jetbrains.com/intellij-repository/releases
    val ideaSDKVersion: String,
    val ideaMainVersion: String, // 主版本号，用于intellijIdeaCommunity()
    val sinceBuild: String,
    val untilBuild: String,
    val archiveName: String = "IntelliJ-EmmyLua",
    val jvmTarget: String = "1.8",
    val targetCompatibilityLevel: JavaVersion = JavaVersion.VERSION_11,
    val explicitJavaDependency: Boolean = true,
    val bunch: String = ideaSDKShortVersion,
    // https://github.com/JetBrains/gradle-intellij-plugin/issues/403#issuecomment-542890849
    val instrumentCodeCompilerVersion: String = ideaSDKVersion
)

val buildDataList = listOf(
    BuildData(
        ideaSDKShortVersion = "252",
        ideaSDKVersion = "252.23892.409",
        ideaMainVersion = "2025.2",
        sinceBuild = "252",
        untilBuild = "",
        bunch = "212",
        targetCompatibilityLevel = JavaVersion.VERSION_21,
        jvmTarget = "21"
    ),
    BuildData(
        ideaSDKShortVersion = "251",
        ideaSDKVersion = "251.23774.435",
        ideaMainVersion = "2025.1",
        sinceBuild = "251",
        untilBuild = "251.*",
        bunch = "212",
        targetCompatibilityLevel = JavaVersion.VERSION_17,
        jvmTarget = "17"
    )
)

val buildVersion = System.getProperty("IDEA_VER") ?: buildDataList.first().ideaSDKShortVersion

val buildVersionData = buildDataList.find { it.ideaSDKShortVersion == buildVersion }!!

val emmyDebuggerVersion = "1.3.0"

val resDir = "src/main/resources"

val isWin = Os.isFamily(Os.FAMILY_WINDOWS)

val isCI = System.getenv("CI") != null

// 处理版本号：优先使用 pluginVersion 参数，然后是 CI_BUILD_VERSION，最后是默认版本
val pluginVersion = project.findProperty("pluginVersion") as String?
if (pluginVersion != null) {
    version = pluginVersion
} else if (isCI) {
    version = System.getenv("CI_BUILD_VERSION") ?: version
    exec {
        executable = "git"
        args("config", "--global", "user.email", "love.tangzx@qq.com")
    }
    exec {
        executable = "git"
        args("config", "--global", "user.name", "tangzx")
    }
}

// 如果版本号不包含 IDEA 后缀，则添加
if (!version.toString().contains("-IDEA")) {
    version = "${version}-IDEA${buildVersion}"
}

fun getRev(): String {
    val os = ByteArrayOutputStream()
    exec {
        executable = "git"
        args("rev-parse", "HEAD")
        standardOutput = os
    }
    return os.toString().substring(0, 7)
}

project(":") {

    repositories {
        mavenCentral()
        
        intellijPlatform {
            defaultRepositories()
        }
    }

    dependencies {
        implementation(fileTree(baseDir = "libs") { include("*.jar") })
        implementation("com.google.code.gson:gson:2.8.6")
        implementation("org.scala-sbt.ipcsocket:ipcsocket:1.3.0")
        implementation("org.luaj:luaj-jse:3.0.1")
        implementation("org.eclipse.mylyn.github:org.eclipse.egit.github.core:2.1.5")
        implementation("com.jgoodies:forms:1.2.1")
        
        // 添加 SLF4J 实现以解决警告
        implementation("org.slf4j:slf4j-simple:2.0.9")
        
        intellijPlatform {
            intellijIdeaCommunity(buildVersionData.ideaMainVersion)
            pluginVerifier()
        }
    }

    sourceSets {
        main {
            java.srcDirs("gen", "src/main/compat")
            // 移除 debugger 文件夹的排除，确保调试器文件被包含在插件包中
            resources.exclude("std/**")
        }
    }

    java {
        sourceCompatibility = buildVersionData.targetCompatibilityLevel
        targetCompatibility = buildVersionData.targetCompatibilityLevel
    }

    intellijPlatform {
        buildSearchableOptions = false
        instrumentCode = true
        
        sandboxContainer = file("${layout.buildDirectory.get().asFile}/${buildVersionData.ideaSDKShortVersion}/idea-sandbox")
        
        pluginConfiguration {
            ideaVersion {
                sinceBuild = buildVersionData.sinceBuild
                untilBuild = buildVersionData.untilBuild
            }
        }
    }

    task("bunch") {
        doLast {
            val rev = getRev()
            // 注释掉危险的git操作，避免代码丢失
            // reset
            // exec {
            //     executable = "git"
            //     args("reset", "HEAD", "--hard")
            // }
            // clean untracked files
            // exec {
            //     executable = "git"
            //     args("clean", "-d", "-f")
            // }
            // switch
            exec {
                executable = if (isWin) "bunch/bin/bunch.bat" else "bunch/bin/bunch"
                args("switch", ".", buildVersionData.bunch)
            }
            // reset to HEAD
            exec {
                executable = "git"
                args("reset", rev)
            }
        }
    }

    tasks {
        buildPlugin {
            dependsOn("bunch")
            // 确保依赖 processResources 任务，获取最新的资源文件
            dependsOn(processResources)
            // 移除 archiveBaseName 配置，使用默认的插件名称
            // 这样可以确保插件包结构正确，避免多余的目录层级
            
            doFirst {
                println("Building plugin - verifying debugger directory")
                val debuggerDir = file("src/main/resources/debugger")
                if (debuggerDir.exists()) {
                    println("✅ Source debugger directory: ${debuggerDir.absolutePath}")
                    
                    val allFiles = debuggerDir.walkTopDown().filter { it.isFile() }
                    val fileCount = allFiles.count()
                    val totalSize = allFiles.sumOf { it.length() }
                    
                    println("📁 Files to be packaged: $fileCount")
                    println("📝 Total size: $totalSize bytes")
                    
                    // 验证关键文件
                    val keyFiles = listOf(
                        "debugger/emmy/code/emmyHelper.lua",
                        "debugger/emmy/code/tool/emmyLog.lua",
                        "debugger/Emmy.lua"
                    )
                    
                    keyFiles.forEach { relativePath ->
                        val keyFile = file("src/main/resources/$relativePath")
                        if (keyFile.exists()) {
                            println("✅ Key file found: $relativePath (${keyFile.length()} bytes)")
                        } else {
                            println("⚠️ Key file missing: $relativePath")
                        }
                    }
                } else {
                    println("❌ Source debugger directory not found!")
                }
            }
        }
        
        // 清理调试器目录缓存的任务
        register("cleanDebuggerCache") {
            group = "build"
            description = "Clean debugger directory cache to ensure fresh builds with latest files"
            
            doLast {
                println("Cleaning debugger directory cache...")
                
                // 清理构建缓存中与调试器相关的文件
                val buildCacheDir = file("${project.buildDir}/.gradle")
                if (buildCacheDir.exists()) {
                    buildCacheDir.deleteRecursively()
                    println("✅ Build cache cleared")
                }
                
                // 清理临时文件
                val tempDir = file("${project.buildDir}/tmp")
                if (tempDir.exists()) {
                    tempDir.deleteRecursively()
                    println("✅ Temp files cleared")
                }
                
                // 清理 processResources 的输出缓存
                val resourcesOutputDir = file("${project.buildDir}/resources")
                if (resourcesOutputDir.exists()) {
                    resourcesOutputDir.deleteRecursively()
                    println("✅ Resources output cache cleared")
                }
                
                println("🧹 Debugger directory cache cleanup completed")
                println("💡 Next build will use fresh files from src/main/resources/debugger")
            }
        }

        compileKotlin {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(buildVersionData.jvmTarget))
            }
        }
        
        // 确保 instrumentCode 在 Java 和 Kotlin 编译之后运行
        // 解决 "Class to bind does not exist" 警告
        named("instrumentCode") {
            dependsOn("compileJava", "compileKotlin")
        }

        patchPluginXml {// 明确声明输入，确保 Gradle 理解任务依赖关系
            inputs.dir("src/main/resources/debugger")
        }
        
        // 确保每次构建都使用最新的 debugger 目录下所有文件，避免缓存干扰
        processResources {
            // 明确声明对整个 debugger 目录的依赖
            inputs.dir("src/main/resources/debugger")
            // 禁用此任务的缓存，确保每次都重新处理
            outputs.cacheIf { false }
            
            doFirst {
                println("Processing debugger directory - ensuring all files are fresh")
                val debuggerDir = file("src/main/resources/debugger")
                if (debuggerDir.exists()) {
                    println("✅ Debugger directory found: ${debuggerDir.absolutePath}")
                    
                    // 统计目录下的文件
                    val allFiles = debuggerDir.walkTopDown().filter { it.isFile() }
                    val fileCount = allFiles.count()
                    val totalSize = allFiles.sumOf { it.length() }
                    
                    println("📁 Total files in debugger directory: $fileCount")
                    println("📝 Total size: $totalSize bytes")
                    
                    // 显示关键文件信息
                    val emmyHelperFile = file("src/main/resources/debugger/emmy/code/emmyHelper.lua")
                    if (emmyHelperFile.exists()) {
                        println("📄 emmyHelper.lua: ${emmyHelperFile.length()} bytes, modified: ${emmyHelperFile.lastModified()}")
                    }
                    
                    val emmyFile = file("src/main/resources/debugger/Emmy.lua")
                    if (emmyFile.exists()) {
                        println("📄 Emmy.lua: ${emmyFile.length()} bytes, modified: ${emmyFile.lastModified()}")
                    }
                    
                    // 显示所有 .lua 文件
                    allFiles.filter { it.extension == "lua" }.forEach { luaFile ->
                        val relativePath = debuggerDir.toPath().relativize(luaFile.toPath())
                        println("🔧 Lua file: $relativePath (${luaFile.length()} bytes)")
                    }
                    
                } else {
                    println("❌ Debugger directory not found at expected location")
                }
            }
        }

        // instrumentCode configuration is now handled by instrumentationTools() dependency

        publishPlugin {
            token.set(System.getenv("IDEA_PUBLISH_TOKEN"))
        }

        prepareSandbox {
            // 确保依赖 processResources 任务，获取最新的资源文件
            dependsOn(processResources)
            
            doLast {
                copy {
                    from("src/main/resources/std")
                    into("${sandboxDirectory.get()}/plugins/${project.name}/std")
                }
                copy {
                    from("src/main/resources/debugger")
                    into("${sandboxDirectory.get()}/plugins/${project.name}/debugger")
                    // 确保每次都重新复制，避免缓存问题
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                }
                
                // 验证 debugger 目录是否成功复制到沙盒
                val sandboxDebuggerDir = file("${sandboxDirectory.get()}/plugins/${project.name}/debugger")
                if (sandboxDebuggerDir.exists()) {
                    println("✅ Debugger directory successfully copied to sandbox")
                    
                    val allFiles = sandboxDebuggerDir.walkTopDown().filter { it.isFile() }
                    val fileCount = allFiles.count()
                    val totalSize = allFiles.sumOf { it.length() }
                    
                    println("📁 Files in sandbox debugger directory: $fileCount")
                    println("📝 Total size in sandbox: $totalSize bytes")
                    
                    // 验证关键文件是否存在于沙盒中
                    val keyFiles = listOf(
                        "emmy/code/emmyHelper.lua",
                        "emmy/code/tool/emmyLog.lua",
                        "Emmy.lua"
                    )
                    
                    keyFiles.forEach { relativePath ->
                        val sandboxFile = file("${sandboxDebuggerDir}/$relativePath")
                        if (sandboxFile.exists()) {
                            println("✅ Key file in sandbox: $relativePath (${sandboxFile.length()} bytes)")
                        } else {
                            println("❌ Key file missing in sandbox: $relativePath")
                        }
                    }
                } else {
                    println("❌ Debugger directory not found in sandbox!")
                }
            }
        }
    }
}
