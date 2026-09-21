pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.0"
        id("org.jetbrains.intellij.platform") version "2.19.0"
    }
}

rootProject.name = "cplus-intellij"
