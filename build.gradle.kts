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

plugins {
    id("org.jetbrains.intellij.platform").version("2.7.0")
    id("org.jetbrains.kotlin.jvm").version("2.1.20")
}

apply(from = "gradle/emmy-native-resources.gradle.kts")

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
        targetCompatibilityLevel = JavaVersion.VERSION_21,
        jvmTarget = "21"
    ),
    BuildData(
        ideaSDKShortVersion = "251",
        ideaSDKVersion = "251.23774.435",
        ideaMainVersion = "2025.1",
        sinceBuild = "251",
        untilBuild = "251.*",
        targetCompatibilityLevel = JavaVersion.VERSION_17,
        jvmTarget = "17"
    )
)

val buildVersion = System.getProperty("IDEA_VER") ?: buildDataList.first().ideaSDKShortVersion

val buildVersionData = buildDataList.find { it.ideaSDKShortVersion == buildVersion }!!

val resDir = "src/main/resources"

val isCI = System.getenv("CI") != null

// 处理版本号：优先使用 pluginVersion 参数，然后是 CI_BUILD_VERSION，最后是默认版本
val pluginVersion = project.findProperty("pluginVersion") as String?
if (pluginVersion != null) {
    version = pluginVersion
} else if (isCI) {
    version = System.getenv("CI_BUILD_VERSION") ?: version
}

// 如果版本号不包含 IDEA 后缀，则添加
if (!version.toString().contains("-IDEA")) {
    version = "${version}-IDEA${buildVersion}"
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
        implementation("com.google.code.gson:gson:2.11.0")
        // IntelliJ's platform classloader provides the JNA classes and its
        // matching native library. Do not let ipcsocket's old JNA 5.5.0
        // transitive dependency create a Java/native version mismatch.
        implementation("org.scala-sbt.ipcsocket:ipcsocket:1.3.0") {
            exclude(group = "net.java.dev.jna", module = "jna")
            exclude(group = "net.java.dev.jna", module = "jna-platform")
        }
        implementation("org.eclipse.mylyn.github:org.eclipse.egit.github.core:2.1.5")
        implementation(project(":modules:debugger-core"))
        implementation(project(":modules:debugger-transport"))
        implementation(project(":modules:debugger-emmy-protocol"))
        implementation(project(":modules:debugger-cli-protocol"))
        implementation(project(":modules:debugger-luapanda-protocol"))
        
        intellijPlatform {
            intellijIdeaCommunity(buildVersionData.ideaMainVersion)
            pluginVerifier()
            testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        }

        testImplementation("junit:junit:4.13.2")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(buildVersionData.jvmTarget))
        }
    }

    intellijPlatform {
        buildSearchableOptions = false
        instrumentCode = true
        
        sandboxContainer = file("${layout.buildDirectory.get().asFile}/${buildVersionData.ideaSDKShortVersion}/idea-sandbox")
        
        pluginConfiguration {
            ideaVersion {
                sinceBuild = buildVersionData.sinceBuild
                if (buildVersionData.untilBuild.isNotBlank()) {
                    untilBuild = buildVersionData.untilBuild
                }
            }
        }

        pluginVerification {
            ides {
                create("IC", buildVersionData.ideaMainVersion)
            }
        }
    }

    tasks {
        buildPlugin {
        }

        // 确保 instrumentCode 在 Java 和 Kotlin 编译之后运行
        // 解决 "Class to bind does not exist" 警告
        named("instrumentCode") {
            dependsOn("compileJava", "compileKotlin")
        }

        // instrumentCode configuration is now handled by instrumentationTools() dependency

        publishPlugin {
            token.set(System.getenv("IDEA_PUBLISH_TOKEN"))
        }

        prepareSandbox {
            doLast {
                copy {
                    from("src/main/resources/std")
                    into("${sandboxDirectory.get()}/plugins/${project.name}/std")
                }
            }
        }
    }
}
