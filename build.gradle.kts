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

// 从命令行属性 "pluginVersion" 获取版本号，这是由 GitHub Actions 传入的
// 如果该属性不存在（例如本地构建），则设置一个清晰的默认值
version = if (project.hasProperty("pluginVersion")) {
    project.property("pluginVersion") as String
} else {
    "1.0.0-LOCAL" // 为本地构建设置一个默认版本号
}

// CI 环境下的 git 配置可以保留，因为它不影响版本号
if (isCI) {
    exec {
        executable = "git"
        args("config", "--global", "user.email", "love.tangzx@qq.com")
    }
    exec {
        executable = "git"
        args("config", "--global", "user.name", "tangzx")
    }
}

version = "${version}-IDEA${buildVersion}"

fun getRev(): String {
    val os = ByteArrayOutputStream()
    exec {
        executable = "git"
        args("rev-parse", "HEAD")
        standardOutput = os
    }
    return os.toString().substring(0, 7)
}

task("downloadEmmyDebugger", type = Download::class) {
    src(arrayOf(
        "https://github.com/EmmyLua/EmmyLuaDebugger/releases/download/${emmyDebuggerVersion}/darwin-arm64.zip",
        "https://github.com/EmmyLua/EmmyLuaDebugger/releases/download/${emmyDebuggerVersion}/darwin-x64.zip",
        "https://github.com/EmmyLua/EmmyLuaDebugger/releases/download/${emmyDebuggerVersion}/linux-x64.zip",
        "https://github.com/EmmyLua/EmmyLuaDebugger/releases/download/${emmyDebuggerVersion}/win32-x64.zip",
        "https://github.com/EmmyLua/EmmyLuaDebugger/releases/download/${emmyDebuggerVersion}/win32-x86.zip"
    ))

    dest("temp")
}

task("unzipEmmyDebugger", type = Copy::class) {
    dependsOn("downloadEmmyDebugger")
    from(zipTree("temp/win32-x86.zip")) {
        into("windows/x86")
    }
    from(zipTree("temp/win32-x64.zip")) {
        into("windows/x64")
    }
    from(zipTree("temp/darwin-x64.zip")) {
        into("mac/x64")
    }
    from(zipTree("temp/darwin-arm64.zip")) {
        into("mac/arm64")
    }
    from(zipTree("temp/linux-x64.zip")) {
        into("linux")
    }
    destinationDir = file("temp")
}

task("installEmmyDebugger", type = Copy::class) {
    dependsOn("unzipEmmyDebugger")
    from("temp/windows/x64/") {
        include("emmy_core.dll")
        into("debugger/emmy/windows/x64")
    }
    from("temp/windows/x86/") {
        include("emmy_core.dll")
        into("debugger/emmy/windows/x86")
    }
    from("temp/linux/") {
        include("emmy_core.so")
        into("debugger/emmy/linux")
    }
    from("temp/mac/x64") {
        include("emmy_core.dylib")
        into("debugger/emmy/mac/x64")
    }
    from("temp/mac/arm64") {
        include("emmy_core.dylib")
        into("debugger/emmy/mac/arm64")
    }
    destinationDir = file("src/main/resources")
    
    // 明确指定输出目录
    outputs.dir("src/main/resources/debugger")
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
        
        intellijPlatform {
            intellijIdeaCommunity(buildVersionData.ideaMainVersion)
            pluginVerifier()
        }
    }

    sourceSets {
        main {
            java.srcDirs("gen", "src/main/compat")
            resources.exclude("debugger/**")
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
            dependsOn("bunch", "installEmmyDebugger")
            // 移除 archiveBaseName 配置，使用默认的插件名称
            // 这样可以确保插件包结构正确，避免多余的目录层级
        }

        compileKotlin {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(buildVersionData.jvmTarget))
            }
        }

        // 确保 patchPluginXml 任务依赖于 installEmmyDebugger
        patchPluginXml {
            dependsOn("installEmmyDebugger")
            // 明确声明输入，确保 Gradle 理解任务依赖关系
            inputs.dir("src/main/resources/debugger")
        }

        // instrumentCode configuration is now handled by instrumentationTools() dependency

        publishPlugin {
            token.set(System.getenv("IDEA_PUBLISH_TOKEN"))
        }

        prepareSandbox {
            dependsOn("installEmmyDebugger")
            doLast {
                copy {
                    from("src/main/resources/std")
                    into("${sandboxDirectory.get()}/plugins/${project.name}/std")
                }
                copy {
                    from("src/main/resources/debugger")
                    into("${sandboxDirectory.get()}/plugins/${project.name}/debugger")
                }
            }
        }
    }
}
