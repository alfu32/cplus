# IntelliJ C-plus Module

This module contains a deployable IntelliJ Platform plugin for C-plus: `.cp`/`.c+` file registration, syntax highlighting, annotation and method completion, lightweight symbol indexing, and declaration navigation.

The source is intentionally kept separate from the Kotlin compiler modules. It targets IntelliJ IDEA 2026.2.2 and Java 21-compatible plugin bytecode.

Comptime keywords and the `self` receiver have dedicated highlighting; `@test` and identifier splices (for example, `mapper__@name(T)__to__@name(R)`) are highlighted and offered in completion. Simple object-like `#define generated_name public_name` aliases are suggested as callable names. The plugin does not evaluate comptime expansions; the alias uses ordinary C preprocessor behavior, not a C-plus alias feature. Without `-Prelease`, its version uses generated CLI metadata or the repository's latest Git tag.

Build the deployable plugin with:

```sh
./gradlew -p intellij-cplus buildPlugin
```

The deployable ZIP is written to `intellij-cplus/build/distributions/`. Install it from **Settings → Plugins → gear → Install Plugin from Disk**. The plugin uses a flat PSI parser so editor services work without requiring the C-plus compiler to be installed.
