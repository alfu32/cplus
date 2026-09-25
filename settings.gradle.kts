pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        kotlin("jvm") version "2.2.20"
        kotlin("multiplatform") version "2.2.20"
        id("io.github.tree-sitter.ktreesitter-plugin") version "0.25.1"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "c-plus"
include(":compiler", ":cli", ":parser-tree-sitter")
