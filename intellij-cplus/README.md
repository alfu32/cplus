# IntelliJ C-plus Module

This module contains the IntelliJ Platform integration for C-plus: `.cp`/`.c+` file registration, syntax highlighting, annotation completion, and lightweight method completion.

The source is intentionally kept separate from the Kotlin compiler modules. Wire it to the IntelliJ Platform Gradle plugin when the supported IDE baseline is selected. Full PSI-backed navigation, compiler diagnostics, and semantic IntelliSense should build on the same language-server protocol used by the other editors.
