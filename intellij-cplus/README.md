# IntelliJ C-plus Module

This module contains a deployable IntelliJ Platform plugin for C-plus: `CPlusLanguage` is registered through the `.cp`/`.c+` language file type with the repository's SVG file icon, syntax highlighting, annotation and receiver-aware member completion, lightweight symbol indexing, and declaration navigation. Completion resolves both `value.` and `pointer->`, distinguishing value, pointer, and static type receivers.

The source is intentionally kept separate from the Kotlin compiler modules. It targets IntelliJ IDEA 2026.2.2 and Java 21-compatible plugin bytecode.

The highlighter distinguishes C and C-plus types, control keywords, function calls, C-plus annotations, comptime directives and built-ins, `comptime flags`, the target value `os`, reflection properties, `@assert`/`@assertEquals`, `@throws`/`@try`/`@catch`, test helper macros, and the `self` receiver. Completion offers the C keyword set, C-plus annotations, comptime result kinds/forms, built-in C-plus forms, and test macros.

`@test` and identifier splices (for example, `mapper__@name(T)__to__@name(R)`) are highlighted and offered in completion. Simple object-like `#define generated_name public_name` aliases are suggested as callable names. The plugin does not evaluate comptime expansions; use the compiler for exact materialized output. Without `-Prelease`, its version uses generated CLI metadata or the repository's latest Git tag.

Configure **Settings → Tools → C-plus** with the compiler command, runner command, and test program. Each `@test` fixture has a gutter run icon; it launches the runner with the configured test program and fixture name as arguments. The default runner is `cplus test`, and the test program defaults to the open source file. The compiler command is stored for compiler-backed features.

Build the deployable plugin with:

```sh
./gradlew -p intellij-cplus buildPlugin
```

The deployable ZIP is written to `intellij-cplus/build/distributions/`. Install it from **Settings → Plugins → gear → Install Plugin from Disk**. The plugin uses a flat PSI parser so editor services work without requiring the C-plus compiler to be installed.

CI targets IntelliJ IDEA 2026.2.2 with the IntelliJ Platform Gradle Plugin and caches that platform under `.intellijPlatform/ides`. The first uncached build resolves the IDE; subsequent GitHub Actions runs restore the cached distribution rather than downloading it again.
