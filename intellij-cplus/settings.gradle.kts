pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.2.20"
        id("org.jetbrains.intellij.platform") version "2.19.0"
    }
}

rootProject.name = "cplus-intellij"
