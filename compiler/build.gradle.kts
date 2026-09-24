import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
}

group = rootProject.group
version = rootProject.version

dependencies {
    implementation(files(rootProject.file("lib/tinycc-cli.jar")))
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
