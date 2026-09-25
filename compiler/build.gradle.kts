import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
}

group = rootProject.group
version = rootProject.version

dependencies {
    implementation(files(rootProject.file("lib/tinycc-cli.jar")))
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

tasks.test {
    useJUnitPlatform()
}
