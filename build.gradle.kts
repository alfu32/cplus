import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
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

val legacyTargetProperties = listOf("os", "arch").filter { providers.gradleProperty(it).isPresent }
if (legacyTargetProperties.isNotEmpty()) {
    throw GradleException(
        "Use -Ptarget instead of the removed ${legacyTargetProperties.joinToString(" and ") { "-P$it" }} build properties."
    )
}

val allTccTargets = setOf(
    "linux-x86_64", "linux-aarch64",
    "macos-x86_64", "macos-aarch64",
    "windows-x86_64", "windows-aarch64"
)
val bundleTargetOption = providers.gradleProperty("target").orElse("none").get().trim().lowercase().ifEmpty { "none" }
val bundledTccTargets = when (bundleTargetOption) {
    "none" -> emptySet()
    "all", "crossbuild" -> allTccTargets
    in allTccTargets -> setOf(bundleTargetOption)
    else -> throw GradleException(
        "Invalid -Ptarget='$bundleTargetOption'; expected one of ${allTccTargets.sorted().joinToString(", ")}, all, crossbuild, or none."
    )
}
val artifactTargetNames = mapOf(
    "linux-x86_64" to "linux-x86_64",
    "linux-aarch64" to "linux-arm64",
    "macos-x86_64" to "mac-x86_64",
    "macos-aarch64" to "mac-arm64",
    "windows-x86_64" to "win-x86_64",
    "windows-aarch64" to "win-arm64"
)
val bundleNameSuffix = when {
    bundledTccTargets.isEmpty() -> "none-none"
    bundledTccTargets.size == allTccTargets.size -> "all-all"
    else -> artifactTargetNames.getValue(bundledTccTargets.single())
}
val bundleName = "cplus-$resolvedVersion-$bundleNameSuffix"
val bundleStageDirectory = layout.buildDirectory.dir("distributions/$bundleName")
val distributionDirectory = rootProject.file("distribution")
val distDirectory = rootProject.file("dist")
val bundleTargetList = bundledTccTargets.sorted()
val bundleOs = when (bundledTccTargets.singleOrNull()?.substringBefore('-')) {
    "linux" -> "linux"
    "macos" -> "mac"
    "windows" -> "win"
    else -> if (bundledTccTargets.isEmpty()) "none" else "all"
}

val stageBundleDist = tasks.register<Sync>("stageBundleDist") {
    group = "distribution"
    description = "Stages the C-plus CLI, standard library, documentation, launchers, and installers."
    dependsOn(":cli:fatJar")
    inputs.property("bundleTarget", bundleTargetOption)
    into(bundleStageDirectory)

    from(rootProject.file("cli/build/libs/c-plus.jar")) {
        rename { "c-plus.jar" }
    }
    from(rootProject.file("stdlib")) {
        into("stdlib")
        exclude("**/*.swp", "**/build/**", "**/.DS_Store")
    }
    from(rootProject.file("documentation")) {
        into("documentation")
        exclude("**/.DS_Store")
    }
    from(rootProject.file("README.md"))
    from(distributionDirectory.resolve("README.md")) {
        rename { "DISTRIBUTION.md" }
    }
    from(rootProject.file("examples")) {
        into("examples")
        include("**/*.cp", "**/*.c")
    }

    if (bundleOs == "all" || bundleOs == "none") {
        from(distributionDirectory.resolve("launchers"))
        from(distributionDirectory.resolve("linux"))
        from(distributionDirectory.resolve("macos"))
        from(distributionDirectory.resolve("windows"))
        from(distributionDirectory.resolve("linux")) { into("install/linux") }
        from(distributionDirectory.resolve("macos")) { into("install/macos") }
        from(distributionDirectory.resolve("windows")) { into("install/windows") }
    } else {
        when (bundleOs) {
            "linux" -> {
                from(distributionDirectory.resolve("launchers/cpc.sh"))
                from(distributionDirectory.resolve("linux"))
            }
            "mac" -> {
                from(distributionDirectory.resolve("launchers/cpc.zsh"))
                from(distributionDirectory.resolve("macos"))
            }
            "win" -> {
                from(distributionDirectory.resolve("launchers/cpc.cmd"))
                from(distributionDirectory.resolve("windows"))
            }
        }
    }

    doLast {
        val stage = bundleStageDirectory.get().asFile
        stage.resolve("VERSION").writeText("$resolvedVersion\n")
        val targets = bundleTargetList.joinToString("\n")
        stage.resolve("TARGETS.txt").writeText(if (targets.isEmpty()) "" else "$targets\n")
        listOf("cpc.sh", "cpc.zsh", "install.sh", "uninstall.sh", "install.zsh", "uninstall.zsh")
            .map(stage::resolve)
            .filter { it.isFile }
            .forEach { it.setExecutable(true, false) }
        listOf("install/linux/install.sh", "install/linux/uninstall.sh", "install/macos/install.zsh", "install/macos/uninstall.zsh")
            .map(stage::resolve)
            .filter { it.isFile }
            .forEach { it.setExecutable(true, false) }
    }
}

val bundleJar = tasks.register<Copy>("bundleJar") {
    group = "distribution"
    description = "Copies the standalone C-plus JAR matching the selected -Ptarget bundle."
    dependsOn(":cli:fatJar")
    from(rootProject.file("cli/build/libs/c-plus.jar")) {
        rename { "$bundleName.jar" }
    }
    into(distDirectory)
}

val bundleJars = tasks.register<Copy>("bundleJars") {
    group = "distribution"
    description = "Extracts standalone C-plus JARs from existing ZIP bundles for the selected release."
    val versionBundleZips = rootProject.fileTree(distDirectory) {
        include("cplus-$resolvedVersion-*.zip")
    }.files.sorted()
    inputs.files(versionBundleZips)
    versionBundleZips.forEach { bundleZip ->
        from(zipTree(bundleZip)) {
            include("c-plus.jar")
            rename { bundleZip.name.removeSuffix(".zip") + ".jar" }
        }
    }
    into(distDirectory)
    doFirst {
        if (versionBundleZips.isEmpty()) {
            throw GradleException("No dist/cplus-$resolvedVersion-*.zip bundles were found to extract.")
        }
        val missingCliJars = versionBundleZips.filter { bundleZip ->
            zipTree(bundleZip).matching { include("c-plus.jar") }.isEmpty
        }
        if (missingCliJars.isNotEmpty()) {
            throw GradleException(
                "Bundle ZIP(s) do not contain c-plus.jar: ${missingCliJars.joinToString { it.name }}"
            )
        }
    }
}

tasks.register<Zip>("bundleDist") {
    group = "distribution"
    description = "Builds a versioned C-plus distribution bundle for -Ptarget."
    dependsOn(stageBundleDist, bundleJar)
    from(bundleStageDirectory)
    eachFile {
        if (name in setOf("cpc.sh", "cpc.zsh", "install.sh", "install.zsh", "uninstall.sh", "uninstall.zsh")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
    archiveFileName.set("$bundleName.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
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
