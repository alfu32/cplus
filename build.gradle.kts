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
