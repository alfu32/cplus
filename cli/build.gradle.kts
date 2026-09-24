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

val allTccTargets = setOf(
    "linux-x86_64", "linux-aarch64",
    "macos-x86_64", "macos-aarch64",
    "windows-x86_64", "windows-aarch64"
)
val tccTargetOption = providers.gradleProperty("target").orElse("none").get().trim().lowercase().ifEmpty { "none" }
val selectedTccTargets = when (tccTargetOption) {
    "none" -> emptySet()
    "all", "crossbuild" -> allTccTargets
    in allTccTargets -> setOf(tccTargetOption)
    else -> throw GradleException(
        "Invalid -Ptarget='$tccTargetOption'; expected one of ${allTccTargets.sorted().joinToString(", ")}, all, crossbuild, or none."
    )
}
val tinyccCliJar = rootProject.file("lib/tinycc-cli.jar").canonicalFile

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds an executable jar containing C-plus and its runtime dependencies."
    archiveBaseName.set("c-plus")
    archiveVersion.set(providers.gradleProperty("release").orElse("0.1.0-SNAPSHOT"))
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("tccTarget", tccTargetOption)

    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output)
    val runtimeArtifacts = configurations.runtimeClasspath.get().filterNot {
        it.canonicalFile == tinyccCliJar
    }
    from(runtimeArtifacts.map { file ->
        if (file.isDirectory) file else zipTree(file)
    })
    from(zipTree(tinyccCliJar)) {
        exclude { details ->
            val path = details.path
            val target = path
                .takeIf { it.startsWith("native/") }
                ?.removePrefix("native/")
                ?.substringBefore('/')
            path.trimEnd('/') == "native" || (
                target != null && target in allTccTargets && target !in selectedTccTargets
            )
        }
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Main-Class"] = "cplus.MainKt"
        attributes["Cplus-Tcc-Host-Payloads"] = selectedTccTargets.sorted().joinToString(",")
        attributes["Cplus-Tcc-Targets"] = selectedTccTargets.sorted().joinToString(",")
    }

    doLast {
        val sourceJar = archiveFile.get().asFile
        val targetJar = sourceJar.parentFile.resolve("${archiveBaseName.get()}.jar")

        sourceJar.copyTo(targetJar, overwrite = true)
        logger.lifecycle("Bundled TinyCC host payloads: ${selectedTccTargets.sorted().joinToString(", ").ifEmpty { "none" }}")
        logger.lifecycle("Bundled TinyCC sysroot payloads: ${selectedTccTargets.sorted().filterNot { it.startsWith("macos-") }.joinToString(", ").ifEmpty { "none" }}")
    }
}
