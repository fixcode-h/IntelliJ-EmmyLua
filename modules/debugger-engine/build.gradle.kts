import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 调试引擎：会话、VM、断点、执行控制、变量、源码映射。纯 Kotlin，不依赖 IntelliJ，
// XDebugger 适配层和 MCP 都是它的客户端。testFixtures 里是 v3 协议的假 runtime，主模块的测试也用它。
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-test-fixtures`
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    api(project(":modules:debugger-protocol"))
    implementation("com.google.code.gson:gson:2.8.6")

    testFixturesImplementation(kotlin("stdlib"))
    testFixturesImplementation("com.google.code.gson:gson:2.8.6")
    testFixturesImplementation("junit:junit:4.13.2")

    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

val debuggerJvmTarget = if ((System.getProperty("IDEA_VER") ?: "252") == "251") "17" else "21"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(debuggerJvmTarget))
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(debuggerJvmTarget)
    targetCompatibility = JavaVersion.toVersion(debuggerJvmTarget)
}

tasks.test {
    // 行为用例走本机回环 TCP，单个用例有超时保护；并行跑互不影响
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}
