plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

group = "cplus"
version = providers.gradleProperty("release").orElse("0.1.0").get()

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
