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

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds an executable jar containing C-plus and its runtime dependencies."
    archiveBaseName.set("c-plus")
    archiveVersion.set(providers.gradleProperty("release").orElse("0.1.0-SNAPSHOT"))
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { file ->
        if (file.isDirectory) file else zipTree(file)
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Main-Class"] = "cplus.MainKt"
    }
}
