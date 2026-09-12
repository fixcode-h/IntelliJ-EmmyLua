import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

val cliJvmTarget = if ((System.getProperty("IDEA_VER") ?: "252") == "251") "17" else "21"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(cliJvmTarget))
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(cliJvmTarget)
    targetCompatibility = JavaVersion.toVersion(cliJvmTarget)
}
