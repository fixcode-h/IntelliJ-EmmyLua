import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":modules:debugger-cli-protocol"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.scala-sbt.ipcsocket:ipcsocket:1.3.0")
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

application {
    mainClass.set("com.tang.intellij.emmydebug.MainKt")
}
