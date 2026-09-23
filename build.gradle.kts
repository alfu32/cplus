import org.gradle.api.tasks.Exec
import java.time.LocalDate

plugins {
    base
}

group = "cplus"
version = providers.gradleProperty("release").orElse("0.1.0-SNAPSHOT").get()


/*
 * ============================================================================
 * Git / application version
 * ============================================================================
 */

fun git(vararg args: String): String? {
    try {
        val process = ProcessBuilder("git", *args)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        val exitCode = process.waitFor()
        return output.takeIf { exitCode == 0 && it.isNotEmpty() }
    } catch (_: Exception) {
        return null
    }
}

val currentDate = LocalDate.now().toString()
val gitTag = git("describe", "--tags", "--abbrev=0")
val gitCommit = git("rev-parse", "HEAD") ?: "UNKNOWN"
val gitShortCommit = git("rev-parse", "--short=12", "HEAD") ?: "UNKNOWN"
val gitBranch = git("branch", "--show-current") ?: "DETACHED"

/*
 * -Prelease with no value simply falls through to Git.
 *
 * -Prelease=1.2.3 overrides Git.
 */
val requestedRelease = providers.gradleProperty("release").orNull?.trim()?.takeIf(String::isNotEmpty)
val resolvedVersion = requestedRelease ?: gitTag ?: gitShortCommit
version = resolvedVersion

println("Application version : $resolvedVersion")
println("Git tag             : ${gitTag ?: "-"}")
println("Git commit          : $gitShortCommit")
println("Git branch          : $gitBranch")

/*
 * ============================================================================
 * Generated CLI version metadata
 * ============================================================================
 */

val generatedVersionFile = layout.projectDirectory.file("cli/src/main/kotlin/cplus/Version.kt").asFile

fun kotlinString(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\${'$'}")
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

val generateVersion = tasks.register("genVersion") {
    group = "versioning"
    description = "Generate CLI Version.kt from Git/build metadata."

    inputs.property("date", currentDate)
    inputs.property("tag", gitTag.orEmpty())
    inputs.property("commit", gitCommit)
    inputs.property("branch", gitBranch)
    inputs.property("version", resolvedVersion)
    outputs.file(generatedVersionFile)

    doLast {
        generatedVersionFile.parentFile.mkdirs()
        generatedVersionFile.writeText(
            """
package cplus

class Version(
    val version: String = "${kotlinString(resolvedVersion)}",
    val branch: String = "${kotlinString(gitBranch)}",
    val tag: String = "${kotlinString(gitTag.orEmpty())}",
    val commit: String = "${kotlinString(gitCommit)}",
    val date: String = "${kotlinString(currentDate)}"
) {
    override fun toString(): String =
        "c+ ${'$'}version --branch=${'$'}branch --tag=${'$'}tag --date=${'$'}date --commit=${'$'}commit"
}
""".trimIndent() + "\n"
        )
    }
}

gradle.projectsEvaluated {
    project(":cli").tasks.named("compileKotlin").configure {
        dependsOn(generateVersion)
    }
}

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

fun findNpmExecutable(): String? {
    val configured = providers.gradleProperty("npmExecutable").orNull
        ?: providers.environmentVariable("NPM").orNull
    if (!configured.isNullOrBlank()) return configured

    // Gradle daemons can outlive shell PATH changes (for example, activating
    // nvm after the daemon starts). Resolve npm through a login shell so the
    // task sees the same Node installation as the user's terminal.
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        return "npm.cmd"
    }

    return try {
        val process = ProcessBuilder("bash", "-lc", "type -P npm")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        if (process.waitFor() != 0) return null
        output.asReversed()
            .map(String::trim)
            .firstOrNull { candidate ->
                candidate.isNotEmpty() && File(candidate).let { it.isFile && it.canExecute() }
            }
    } catch (_: Exception) {
        null
    }
}

val packageVscode = tasks.register<Exec>("packageVscode") {
    group = "build"
    description = "Packages the VS Code extension."
    workingDir(rootProject.file("vscode-cplus"))
    dependsOn(generateVersion)
    environment("CPLUS_RELEASE_VERSION", resolvedVersion)
    doFirst {
        val npmExecutable = findNpmExecutable()
            ?: throw GradleException(
                "npm was not found. Activate Node in your shell or set NPM / -PnpmExecutable to its path."
            )
        executable(npmExecutable)
        args("run", "package")

        // npm and its node shebang must resolve to the same installation.
        val npmDirectory = File(npmExecutable).absoluteFile.parent
        if (!npmDirectory.isNullOrBlank()) {
            val inheritedPath = System.getenv("PATH").orEmpty()
            environment("PATH", npmDirectory + File.pathSeparator + inheritedPath)
        }
    }
}

val packageVim = tasks.register<Exec>("packageVim") {
    group = "build"
    description = "Packages the Vim runtime."
    workingDir(rootProject.file("vim-cplus"))
    commandLine("make", "package", "VERSION=$version")
}

val packageIntellij = tasks.register<Exec>("packageIntellij") {
    group = "build"
    description = "Packages the IntelliJ plugin."
    workingDir(rootProject.projectDir)
    commandLine("./gradlew", "-p", "intellij-cplus", "buildPlugin", "-Prelease=$version")
}

tasks.register("editorArtifacts") {
    group = "build"
    description = "Builds the VS Code, IntelliJ, and Vim editor artifacts."
    dependsOn(packageVscode, packageVim, packageIntellij)
}
