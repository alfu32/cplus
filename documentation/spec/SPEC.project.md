# C-plus Project and Module Specification

Status: initial project manifest and module search implementation.

This document describes project discovery, standard-library resolution, module imports, and project scaffolding. Dependency resolution and lockfiles are not implemented yet.

## Project root and manifest

The CLI searches the input file's directory and its parents for `cplus.toml`. If the file is outside a project, it also searches upward from the current working directory. The current manifest format is a deliberately small TOML subset:

```toml
name = "example"
version = "0.1.0"
source = "src"
stdlib = ""
module-paths = ["src", "modules"]
dependencies = []
```

`name`, `source`, `stdlib`, and `module-paths` are read by the CLI. Paths are relative to the manifest unless absolute. `dependencies` is reserved for future package support and currently has no effect. Project module roots are searched in listed order.

## Imports

Comptime C-plus imports support explicit roots and extensionless names:

```c
comptime import "stdlib:/memory/xmem";
comptime import "module:/shared/point.cp";
```

`stdlib:/` searches standard-library roots; `module:/` searches roots from `module-paths` (`project:/` is an accepted alias). For C-plus imports, an omitted extension tries `.cp` then `.c+`. Ordinary relative imports stay relative to the importing file. Paths resolved through a named root cannot escape that root. Imported modules are loaded once per compilation graph and cycles are diagnosed.

For unchanged C files, use `#include "path/to/file.c"` or `@import("path/to/file.c")`; C files are passed to the C preprocessor/compiler, not the comptime evaluator.

The standard-library root lookup order is explicit `--stdlib directory`, the project's `stdlib` entry, `CPLUS_STDLIB`, `CPLUS_HOME/stdlib`, the CLI jar/bundle and repository ancestors, then conventional user and system install locations. Compilation also passes project, stdlib, and source roots to TinyCC's include search path.

## Scaffolding

```sh
cpc new example
cd example
cpc run src/main.cp
cpc new . # initialize the current directory if scaffold-owned files do not already exist
```

Scaffolding creates `cplus.toml`, `src/main.cp`, `modules/`, `tests/`, and `README.md`. It refuses to overwrite the manifest, main source, or README if any already exists.

## Current limits

The manifest parser supports simple top-level string values and arrays of strings, not arbitrary TOML tables, complex escape rules, package registries, dependency versions, lockfiles, or transitive dependency configuration. `source` is recorded for future commands; direct CLI commands still take explicit source paths. No implicit module names are injected: use `module:/` or a relative import.
