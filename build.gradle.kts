import org.gradle.api.tasks.Exec

plugins {
    base
}

group = "cplus"
version = providers.gradleProperty("release").orElse("0.1.0-SNAPSHOT").get()

tasks.named("assemble") {
    dependsOn(":cli:assemble")
}

tasks.named("check") {
    dependsOn(":cli:check")
}

tasks.named("clean") {
    dependsOn(":cli:clean", ":compiler:clean")
}

tasks.register("test") {
    group = "verification"
    description = "Runs the CLI module tests."
    dependsOn(":cli:test")
}

tasks.register("fatJar") {
    group = "build"
    description = "Builds the executable CLI jar."
    dependsOn(":cli:fatJar")
}

val packageVscode = tasks.register<Exec>("packageVscode") {
    group = "build"
    description = "Packages the VS Code extension."
    workingDir(rootProject.file("vscode-cplus"))
    commandLine("npm", "run", "package")
}

val packageVim = tasks.register<Exec>("packageVim") {
    group = "build"
    description = "Packages the Vim runtime."
    workingDir(rootProject.file("vim-cplus"))
    commandLine("make", "package")
}

val packageIntellij = tasks.register<Exec>("packageIntellij") {
    group = "build"
    description = "Packages the IntelliJ plugin."
    workingDir(rootProject.projectDir)
    commandLine("./gradlew", "-p", "intellij-cplus", "buildPlugin")
}

tasks.register("editorArtifacts") {
    group = "build"
    description = "Builds the VS Code, IntelliJ, and Vim editor artifacts."
    dependsOn(packageVscode, packageVim, packageIntellij)
}
