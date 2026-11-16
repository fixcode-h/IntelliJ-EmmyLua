import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 协议 v3：帧编解码、消息和 DTO。不依赖 IntelliJ，引擎、MCP、假 runtime 共用。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.8.6")
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
