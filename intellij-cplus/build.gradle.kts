plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

group = "cplus"
val generatedCliVersionFile = rootDir.resolve("../cli/src/main/kotlin/cplus/Version.kt")
val generatedCliVersion = generatedCliVersionFile.takeIf { it.isFile }
    ?.readText()
    ?.let { Regex("val version: String = \\\"([^\\\"]+)\\\"").find(it)?.groupValues?.get(1) }
val requestedRelease = providers.gradleProperty("release").orNull?.trim()?.takeIf(String::isNotEmpty)
fun gitVersion(vararg args: String): String? = try {
    val process = ProcessBuilder("git", *args)
        .directory(rootDir.resolve(".."))
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    output.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
} catch (_: Exception) {
    null
}
val resolvedVersion = requestedRelease
    ?: generatedCliVersion
    ?: gitVersion("describe", "--tags", "--abbrev=0")
    ?: gitVersion("rev-parse", "--short=12", "HEAD")
    ?: "0.1.0"
version = resolvedVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.2")
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    instrumentCode = false
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
        }
        vendor {
            name = "C-plus contributors"
        }
    }
}

tasks {
    named("buildSearchableOptions") {
        enabled = false
    }
}
