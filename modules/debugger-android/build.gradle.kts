import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    // 解压 .gnu_debugdata（MiniDebugInfo，xz 格式）；JDK 没有 xz。随主插件一起打进 lib/（主工程 implementation 了本模块）
    implementation("org.tukaani:xz:1.10")
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
