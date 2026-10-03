# C-plus projects, modules, and dependencies

## Decision

C-plus uses an npm-like source-module layout, but keeps compilation explicit and
deterministic. A project owns its sources and a `modules/` directory; the CLI
resolves imports from the project's declared module roots. Dependencies are
resolved before transcoding and never injected implicitly into unrelated source
files.

The first supported dependency source is a local path. The package commands
also accept `file:` references and direct HTTP(S) ZIP URLs. Network registries,
version solving, and generated lockfiles are deliberately deferred until the
local model is stable. This gives projects reproducible behavior on all hosts
without making the compiler depend on JavaScript tooling or a registry service.

## Manifest

The existing flat keys remain valid:

```toml
name = "demo"
version = "0.1.0"
source = "src"
stdlib = "auto"
module-paths = ["src", "modules"]
dependencies = []
```

Dependencies may also be declared as a table:

```toml
[dependencies]
geometry = { path = "../geometry", version = "0.1.0" }
ui = { path = "modules/ui" }
```

Each dependency directory must contain its own `cplus.toml`. Its `source`,
`module-paths`, and transitive local dependencies are resolved relative to that
manifest. A dependency contributes its declared module roots to the project's
`module:/...` search path. The initial implementation deliberately keeps the
existing path-based import spelling (`module:/vector`); package-name aliases
such as `module:/geometry/vector` are reserved for the registry/lockfile phase.

## Package commands

`pkg` manages package manifests and installs dependencies. It is intentionally
separate from `cpc init`, which creates an executable project skeleton.

```text
cpc pkg init [folder]
cpc pkg add name=path-or-url
cpc pkg install [name=path-or-url ...]
```

`pkg init` creates only `cplus.toml`; it does not create `src`, `modules`,
tests, or a README. `pkg add` appends a dependency to the current manifest.
With no arguments, `pkg install` stages and validates all manifest
dependencies, recursively resolves them, detects cycles, and only then moves
the flattened packages into `modules/<name>`. Existing module directories are
not overwritten. Direct HTTP(S) downloads currently support ZIP archives;
registry discovery, semver selection, lockfiles, and other archive formats
remain future work.

`stdlib = "auto"` means use the installed/project standard-library discovery
rules. An empty value remains supported for old manifests and has the same
fallback behavior. A non-empty path is resolved relative to the manifest.

## Resolution rules

1. Locate the nearest `cplus.toml` from the input source.
2. Resolve the project's standard library and module roots.
3. Resolve local dependency paths, validating that each dependency has a
   manifest and that its declared name matches the dependency key.
4. Add dependency module roots in manifest order and recursively resolve their
   dependencies.
5. Reject duplicate dependency names, missing paths, manifest cycles, and
   imports that escape a configured root.
6. Produce one canonical dependency-first import graph for transcode, compile,
   run, test, LSP, IntelliJ, and VS Code.

This is intentionally closer to npm's directory model than Java's raw
classpath model, while preserving C-plus's explicit comptime import syntax.
