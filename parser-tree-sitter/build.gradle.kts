plugins {
    kotlin("multiplatform")
    id("io.github.tree-sitter.ktreesitter-plugin")
}

val grammarDirectory = layout.projectDirectory.dir("upstream")
val generatedDirectory = layout.buildDirectory.dir("tree-sitter-generated")

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

tasks.named<Copy>("jvmProcessResources") {
    dependsOn(installHostParserLibrary)
    // CI stages the six independently built host libraries here before assembling the
    // platform-neutral CLI distribution. Local builds simply contribute their host library.
    from(parserNativePayloadDirectory)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val generateTreeSitterParser = tasks.register<Exec>("generateTreeSitterParser") {
    group = "build"
    description = "Regenerates the pinned C-plus Tree-sitter parser into build output."
    workingDir(grammarDirectory)
    commandLine("tree-sitter", "generate", "--abi", "15", "-o", generatedDirectory.get().asFile.absolutePath)
    inputs.files(grammarDirectory.file("grammar.js"), grammarDirectory.file("tree-sitter.json"))
    outputs.dir(generatedDirectory)
}

val verifyTreeSitterParser = tasks.register("verifyTreeSitterParser") {
    group = "verification"
    description = "Checks the committed Tree-sitter parser matches its pinned grammar."
    dependsOn(generateTreeSitterParser)
    inputs.dir(generatedDirectory)
    inputs.files(grammarDirectory.file("src/parser.c"), grammarDirectory.file("src/node-types.json"))
    doLast {
        listOf("parser.c", "node-types.json").forEach { filename ->
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
    commandLine("tree-sitter", "test")
    dependsOn(verifyTreeSitterParser)
}

tasks.named("check") {
    dependsOn(testTreeSitterGrammar, tasks.named("jvmTest"))
}
