import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
    application
}

group = rootProject.group
version = rootProject.version

dependencies {
    implementation(project(":compiler"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("cplus.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(rootProject.fileTree("stdlib") {
        include("**/*.cp", "**/*.c")
    })
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "cplus.MainKt"
    }
}

val tccArchOption = providers.gradleProperty("arch").orElse("none").get().trim().lowercase()
val tccOsOption = providers.gradleProperty("os").orElse("none").get().trim().lowercase()

val tccArchitectures = when (tccArchOption) {
    "x86_64" -> setOf("x86_64")
    "arm64" -> setOf("aarch64")
    "all" -> setOf("x86_64", "aarch64")
    "none" -> emptySet()
    else -> throw GradleException("Invalid -Parch='$tccArchOption'; expected x86_64, arm64, all, or none.")
}

val tccOperatingSystems = when (tccOsOption) {
    "win" -> setOf("windows")
    "mac" -> setOf("macos")
    "linux" -> setOf("linux")
    "all" -> setOf("windows", "macos", "linux")
    "none" -> emptySet()
    else -> throw GradleException("Invalid -Pos='$tccOsOption'; expected win, mac, linux, all, or none.")
}
if ((tccArchOption == "none") != (tccOsOption == "none")) {
    throw GradleException("-Pos=none and -Parch=none must be selected together.")
}

val allTccTargets = setOf(
    "linux-x86_64", "linux-aarch64",
    "macos-x86_64", "macos-aarch64",
    "windows-x86_64", "windows-aarch64"
)
val selectedTccTargets = tccOperatingSystems
    .flatMap { os -> tccArchitectures.map { arch -> "$os-$arch" } }
    .toSet()
val tinyccEmbedJar = rootProject.file("lib/tinycc-embed.jar").canonicalFile

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds an executable jar containing C-plus and its runtime dependencies."
    archiveBaseName.set("c-plus")
    archiveVersion.set(providers.gradleProperty("release").orElse("0.1.0-SNAPSHOT"))
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("tccArch", tccArchOption)
    inputs.property("tccOs", tccOsOption)

    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output)
    val runtimeArtifacts = configurations.runtimeClasspath.get().filterNot {
        it.canonicalFile == tinyccEmbedJar
    }
    from(runtimeArtifacts.map { file ->
        if (file.isDirectory) file else zipTree(file)
    })
    from(zipTree(tinyccEmbedJar)) {
        exclude { details ->
            val path = details.path
            val target = path
                .takeIf { it.startsWith("native/") }
                ?.removePrefix("native/")
                ?.substringBefore('/')
            val normalizedPath = path.trimEnd('/')
            val isTargetSysrootPayload = target != null && (
                normalizedPath == "native/$target/files.list" ||
                    normalizedPath == "native/$target/tinycc/sysroot" ||
                    normalizedPath.startsWith("native/$target/tinycc/sysroot/")
                )
            path.trimEnd('/') == "native" || (
                target != null && target in allTccTargets && target !in selectedTccTargets &&
                    !(selectedTccTargets.isNotEmpty() && isTargetSysrootPayload)
                )
        }
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Main-Class"] = "cplus.MainKt"
        attributes["Cplus-Tcc-Host-Payloads"] = selectedTccTargets.sorted().joinToString(",")
        attributes["Cplus-Tcc-Targets"] = if (selectedTccTargets.isEmpty()) "" else allTccTargets.sorted().joinToString(",")
    }

    doLast {
        val sourceJar = archiveFile.get().asFile
        val targetJar = sourceJar.parentFile.resolve("${archiveBaseName.get()}.jar")

        sourceJar.copyTo(targetJar, overwrite = true)
        logger.lifecycle("Bundled TinyCC host payloads: ${selectedTccTargets.sorted().joinToString(", ").ifEmpty { "none" }}")
        logger.lifecycle("Bundled TinyCC target drivers: ${if (selectedTccTargets.isEmpty()) "none" else allTccTargets.sorted().joinToString(", ")}")
    }
}
