import org.gradle.api.GradleException
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.SourceSetContainer

val emmyNativeDir = providers.gradleProperty("emmyNativeDir")
    .map { file(it).toPath().toAbsolutePath().normalize() }

// Windows 本机附加（协议 v3）需要的全部文件
val emmyNativeFiles = listOf(
    "EasyHook.dll",
    "emmy_tool.exe",
    "emmy_attach.dll"
) + listOf("51", "52", "53", "54").map { "lua$it/emmy_debugger_core.dll" }

val validateEmmyNativeResources = tasks.register("validateEmmyNativeResources") {
    onlyIf { emmyNativeDir.isPresent }
    doLast {
        val root = emmyNativeDir.orNull
            ?: throw GradleException("-PemmyNativeDir is not set")
        if (!root.toFile().isDirectory) {
            throw GradleException("emmyNativeDir does not exist: $root")
        }
        listOf("x86", "x64").forEach { architecture ->
            val directory = root.resolve(architecture).toFile()
            if (!directory.isDirectory) {
                throw GradleException("missing Native architecture directory: $directory")
            }
            emmyNativeFiles.forEach { name ->
                val file = directory.resolve(name)
                if (!file.isFile || file.length() == 0L) {
                    throw GradleException("missing or empty Native resource: $file")
                }
            }
        }
    }
}

if (emmyNativeDir.isPresent) {
    extensions.getByType<SourceSetContainer>().named("main") {
        resources.exclude("debugger/emmy/windows/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(validateEmmyNativeResources)
    if (emmyNativeDir.isPresent) {
        from(emmyNativeDir) {
            include(listOf("x86", "x64").flatMap { arch -> emmyNativeFiles.map { "$arch/$it" } })
            into("debugger/emmy/windows")
        }
    }
}
