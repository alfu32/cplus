import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    kotlin("multiplatform")
    id("io.github.tree-sitter.ktreesitter-plugin")
}

val grammarDirectory = layout.projectDirectory.dir("upstream")
val generatedDirectory = layout.buildDirectory.dir("tree-sitter-generated")
val isWindows = System.getProperty("os.name")
    .lowercase()
    .contains("windows")

val treeSitterExecutable =
    if (isWindows) "tree-sitter.cmd"
    else "tree-sitter"
grammar {
    baseDir = grammarDirectory.asFile
    grammarName = "c"
    className = "TreeSitterCPlus"
    packageName = "cplus.parser.treesitter"
}

kotlin {
    jvm()
    jvmToolchain(21)

    sourceSets {
        val generatedSrc = tasks.generateGrammarFiles.get().generatedSrc
        commonMain {
            kotlin.srcDir(generatedSrc.dir("commonMain/kotlin"))
            dependencies {
                implementation(project(":compiler"))
            }
        }
        jvmMain {
            kotlin.srcDir(generatedSrc.dir("jvmMain/kotlin"))
            resources.srcDir(generatedSrc.dir("jvmMain/resources"))
            dependencies {
                implementation("io.github.tree-sitter:ktreesitter:0.25.1")
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
        }
    }
}

val generatedGrammarSrc = tasks.generateGrammarFiles.get().generatedSrc
val nativeOutputDirectory = layout.buildDirectory.dir("native-parser")
val nativeHostOs = when {
    System.getProperty("os.name").lowercase().contains("windows") -> "windows"
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    else -> error("Unsupported JNI parser host: ${System.getProperty("os.name")}")
}
val nativeHostArch = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "aarch64"
    else -> error("Unsupported JNI parser architecture: ${System.getProperty("os.arch")}")
}
tasks.generateGrammarFiles.configure {
    doLast {
        val cmakeFile = cmakeListsFile.get().asFile

        if (cmakeFile.isFile) {
            val original = cmakeFile.readText()
            val normalized = original.replace('\\', '/')

            if (normalized != original) {
                cmakeFile.writeText(normalized)
            }
        }
    }
}

val configureNativeParser = tasks.register<Exec>("configureNativeParser") {
    group = "build"
    description = "Configures the local JNI language shim build."
    dependsOn(tasks.generateGrammarFiles)
    commandLine(buildList {
        addAll(listOf(
            "cmake", "-S", generatedGrammarSrc.get().asFile.parentFile.absolutePath,
            "-B", layout.buildDirectory.dir("native-parser-cmake").get().asFile.absolutePath,
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${nativeOutputDirectory.get().asFile.absolutePath}"
        ))
        if (nativeHostOs == "windows") {
            add("-DCMAKE_LIBRARY_OUTPUT_DIRECTORY_RELEASE=${nativeOutputDirectory.get().asFile.absolutePath}")
        }
    })
    inputs.file(tasks.generateGrammarFiles.get().cmakeListsFile)
}

val buildNativeParser = tasks.register<Exec>("buildNativeParser") {
    group = "build"
    description = "Builds the JNI grammar shim for the current host."
    dependsOn(configureNativeParser, verifyTreeSitterParser)
    commandLine("cmake", "--build", layout.buildDirectory.dir("native-parser-cmake").get().asFile.absolutePath, "--config", "Release")
    inputs.files(generatedGrammarSrc.file("jni/binding.c"), grammarDirectory.file("src/parser.c"))
    outputs.dir(nativeOutputDirectory)
}

val installHostParserLibrary = tasks.register<Copy>("installHostParserLibrary") {
    dependsOn(buildNativeParser)
    from(nativeOutputDirectory)
    into(generatedGrammarSrc.dir("jvmMain/resources/lib/$nativeHostOs/$nativeHostArch"))
    include("libktreesitter-c.so", "libktreesitter-c.dylib", "ktreesitter-c.dll")
}

val parserNativePayloadDirectory = layout.buildDirectory.dir("parser-native-payload")

val kTreeSitterVersion = "0.25.1"
val kTreeSitterSource = layout.buildDirectory.dir("third-party/ktreesitter-$kTreeSitterVersion")
val treeSitterSource = layout.buildDirectory.dir("third-party/tree-sitter-$kTreeSitterVersion")
val kTreeSitterBuild = layout.buildDirectory.dir("native-ktreesitter-cmake")
val kTreeSitterOutput = layout.buildDirectory.dir("native-ktreesitter")
val forceHostKTreeSitterBuild = providers.gradleProperty("buildKTreeSitterBase")
    .map { it.toBoolean() }
    .orElse(false)
val nativeKTreeSitterLibrary = when (nativeHostOs) {
    "windows" -> "ktreesitter.dll"
    "macos" -> "libktreesitter.dylib"
    else -> "libktreesitter.so"
}

fun extractPinnedZip(url: String, destination: File) {
    if (destination.resolve(".extracted").isFile) return

    destination.deleteRecursively()
    destination.mkdirs()
    URI(url).toURL().openStream().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destination.resolve(entry.name).canonicalFile
                require(target.path.startsWith(destination.canonicalPath + File.separator)) {
                    "Refusing to extract archive entry outside $destination: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
            }
        }
    }
    destination.resolve(".extracted").writeText(url)
}

val buildHostKTreeSitter = tasks.register("buildHostKTreeSitter") {
    group = "build"
    description = "Builds a host KTreeSitter base JNI library for the parser runtime."
    onlyIf { forceHostKTreeSitterBuild.get() || nativeHostOs == "windows" || nativeHostOs == "macos" }
    outputs.file(kTreeSitterOutput.map { it.file(nativeKTreeSitterLibrary) })
    doLast {
        val ktreesitterArchiveRoot = kTreeSitterSource.get().asFile
        val treeSitterArchiveRoot = treeSitterSource.get().asFile
        extractPinnedZip(
            "https://github.com/tree-sitter/kotlin-tree-sitter/archive/refs/tags/v$kTreeSitterVersion.zip",
            ktreesitterArchiveRoot
        )
        extractPinnedZip(
            "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v$kTreeSitterVersion.zip",
            treeSitterArchiveRoot
        )

        val ktreesitterRoot = ktreesitterArchiveRoot.resolve("kotlin-tree-sitter-$kTreeSitterVersion/ktreesitter")
        val treeSitterRoot = treeSitterArchiveRoot.resolve("tree-sitter-$kTreeSitterVersion")
        val cmakeRoot = kTreeSitterBuild.get().asFile
        cmakeRoot.mkdirs()
        val outputRoot = kTreeSitterOutput.get().asFile
        outputRoot.mkdirs()
        val normalized = { file: File -> file.absolutePath.replace('\\', '/') }
        cmakeRoot.resolve("CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.12)
            project(ktreesitter LANGUAGES C)
            find_package(JNI REQUIRED)
            set(CMAKE_C_STANDARD 11)
            if(MSVC)
                add_compile_options(/W3 /wd4244)
            else()
                set(CMAKE_C_VISIBILITY_PRESET hidden)
                add_compile_options(-Wall -Wextra
                    -Wno-unused-parameter
                    -Wno-cast-function-type
                    -Werror=incompatible-pointer-types
                    -Werror=implicit-function-declaration)
            endif()
            include_directories(
                ${'$'}{JNI_INCLUDE_DIRS}
                "${normalized(treeSitterRoot.resolve("lib/src"))}"
                "${normalized(treeSitterRoot.resolve("lib/include"))}"
            )
            add_compile_definitions(TREE_SITTER_HIDE_SYMBOLS _DEFAULT_SOURCE _POSIX_C_SOURCE=200112L)
            file(GLOB JNI_SOURCES "${normalized(ktreesitterRoot.resolve("src/jni"))}/*.c")
            add_library(ktreesitter SHARED ${'$'}{JNI_SOURCES} "${normalized(treeSitterRoot.resolve("lib/src/lib.c"))}")
            set_target_properties(ktreesitter PROPERTIES
                RUNTIME_OUTPUT_DIRECTORY "${normalized(outputRoot)}"
                LIBRARY_OUTPUT_DIRECTORY "${normalized(outputRoot)}"
                ARCHIVE_OUTPUT_DIRECTORY "${normalized(outputRoot)}"
                RUNTIME_OUTPUT_DIRECTORY_RELEASE "${normalized(outputRoot)}"
                LIBRARY_OUTPUT_DIRECTORY_RELEASE "${normalized(outputRoot)}"
                ARCHIVE_OUTPUT_DIRECTORY_RELEASE "${normalized(outputRoot)}"
                DEFINE_SYMBOL ""
            )
            """.trimIndent()
        )

        fun run(command: List<String>) {
            val process = ProcessBuilder(command).inheritIO().start()
            check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}" }
        }
        run(
            listOf(
                "cmake", "-S", cmakeRoot.absolutePath, "-B", kTreeSitterBuild.get().asFile.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release"
            )
        )
        run(
            listOf(
                "cmake", "--build", kTreeSitterBuild.get().asFile.absolutePath,
                "--config", "Release"
            )
        )
        val output = outputRoot.resolve(nativeKTreeSitterLibrary)
        val produced = outputRoot.walkTopDown().firstOrNull { it.isFile && it.name == nativeKTreeSitterLibrary }
        if (!output.isFile && produced != null) {
            produced.copyTo(output, overwrite = true)
        }
        require(output.isFile) { "KTreeSitter host JNI build did not produce $output" }
    }
}

val installHostKTreeSitterLibrary = tasks.register<Copy>("installHostKTreeSitterLibrary") {
    dependsOn(buildHostKTreeSitter)
    onlyIf { forceHostKTreeSitterBuild.get() || nativeHostOs == "windows" || nativeHostOs == "macos" }
    from(kTreeSitterOutput)
    into(generatedGrammarSrc.dir("jvmMain/resources/lib/$nativeHostOs/$nativeHostArch"))
    include(nativeKTreeSitterLibrary)
}

tasks.named<Copy>("jvmProcessResources") {
    dependsOn(installHostParserLibrary, installHostKTreeSitterLibrary)
    // CI stages the six independently built host libraries here before assembling the
    // platform-neutral CLI distribution. Local builds simply contribute their host library.
    from(parserNativePayloadDirectory)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

val jvmTestTask = tasks.named<Test>("jvmTest") {
    useJUnitPlatform {
        excludeTags("frontend-benchmark")
    }
}

tasks.register<Test>("benchmarkFrontend") {
    group = "verification"
    description = "Measures fresh Tree-sitter parse, prototype transcode, and full reparse-after-edit costs."
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    useJUnitPlatform {
        includeTags("frontend-benchmark")
    }
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

val generateTreeSitterParser = tasks.register<Exec>("generateTreeSitterParser") {
    group = "build"
    description = "Regenerates the pinned C-plus Tree-sitter parser into build output."
    workingDir(grammarDirectory)

    commandLine(
        treeSitterExecutable,
        "generate",
        "--abi",
        "15",
        "-o",
        generatedDirectory.get().asFile.absolutePath
    )

    inputs.files(
        grammarDirectory.file("grammar.js"),
        grammarDirectory.file("tree-sitter.json")
    )
    outputs.dir(generatedDirectory)
}

val verifyTreeSitterParser = tasks.register("verifyTreeSitterParser") {
    group = "verification"
    description = "Checks the committed Tree-sitter parser matches its pinned grammar."
    dependsOn(generateTreeSitterParser)
    inputs.dir(generatedDirectory)
    inputs.files(grammarDirectory.file("src/parser.c"), grammarDirectory.file("src/node-types.json"))
    doLast {
        listOf("grammar.json", "node-types.json", "parser.c").forEach { filename ->
            val committed = grammarDirectory.file("src/$filename").asFile
            val generated = generatedDirectory.get().file(filename).asFile
            if (!generated.isFile || committed.readBytes().contentEquals(generated.readBytes()).not()) {
                throw GradleException("Tree-sitter generated file is stale: upstream/src/$filename")
            }
        }
    }
}

val testTreeSitterGrammar = tasks.register<Exec>("testTreeSitterGrammar") {
    group = "verification"
    description = "Runs the complete C and C-plus Tree-sitter corpus."
    workingDir(grammarDirectory)

    commandLine(treeSitterExecutable, "test")

    dependsOn(verifyTreeSitterParser)
}

tasks.named("check") {
    dependsOn(testTreeSitterGrammar, tasks.named("jvmTest"))
}
