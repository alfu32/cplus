# C-plus package management

Status: **initial implementation complete; registry and reproducibility work remains**

## Goal

Provide a small, deterministic package workflow for C-plus source modules:

1. Create a package manifest without scaffolding an executable project.
2. Declare dependencies in the current module.
3. Resolve, validate, and install the complete dependency tree into `modules/`.
4. Keep installation safe: invalid or incomplete trees must not be published as
   usable modules, and existing module directories must not be overwritten.

This feature is deliberately separate from `cpc init`. `cpc init` creates a
runnable project skeleton; `cpc pkg init` creates only package metadata.

## Technical approach

The package manager is implemented in the CLI as a filesystem-oriented service.
It uses the existing `cplus.toml` format and the existing project/module
resolution rules rather than introducing a second manifest format.

### Package identity

Each package is identified by the `name` in its root `cplus.toml`. A dependency
key must match that name. The `version` field is recorded but is not yet used
for selection or compatibility solving.

```toml
name = "geometry"
version = "0.1.0"
source = "src"
stdlib = "auto"
module-paths = ["src", "modules"]

[dependencies]
math = { path = "../math", version = "0.1.0" }
```

### Dependency references

The initial implementation accepts:

- relative or absolute local directory paths;
- `file:` URIs;
- HTTP(S) URLs ending in `.zip`.

`pkg add` accepts `name=reference`. A bare path or URL is also accepted and
uses its final path component as the package name. `pkg install` with no
arguments installs the manifest dependencies; references supplied after
`install` are resolved directly for that invocation.

### Staged installation algorithm

For a project root `P`, installation follows this sequence:

1. Read `P/cplus.toml` and collect its dependencies.
2. Create a temporary staging directory below `P`.
3. Obtain each dependency into staging, copying local directories or
   downloading and extracting ZIP archives.
4. Validate that each obtained package contains exactly one usable root
   `cplus.toml` and that its manifest name matches the dependency name.
5. Resolve each package's dependencies relative to that package's original
   source location for local packages, or its extracted root for downloads.
6. Reject cycles, missing manifests, duplicate target names, unsafe archive
   paths, and existing `modules/<name>` destinations.
7. After the complete tree passes validation, move every staged package to
   `P/modules/<name>`.
8. Delete the staging directory in a `finally` cleanup path.

The result is a flat module directory. Transitive packages are not nested
under their parent package. The compiler can therefore reuse its existing
module-root search behavior.

## CLI contract

```text
cpc pkg init [folder]
cpc pkg add name=path-or-url
cpc pkg install [name=path-or-url ...]
```

`folder` defaults to the current directory. `pkg add` and `pkg install` operate
on the current directory's module manifest and currently require execution
from that module's directory. The command returns an error rather than overwriting
an existing `cplus.toml` or installed module directory.

## Safety and determinism invariants

- Every installed package has a validated manifest before it is moved.
- A package name cannot be silently rebound to a different manifest.
- Archive extraction cannot write outside its staging root.
- A destination collision is checked before any staged package is moved.
- Temporary staging is removed on success and failure.
- Dependency resolution is explicit; package names do not yet create implicit
  import aliases.

## Deliberately deferred behavior

The current implementation is not a package registry or a full dependency
solver. It does not yet provide:

- registry discovery or publish/login commands;
- semver range selection or version conflict solving;
- a lockfile, checksums, or signature verification;
- upgrade/remove/update commands;
- TAR or other non-ZIP remote archives;
- automatic replacement of an existing module directory.

Those capabilities are tracked separately in
`documentation/plan/MODULES-FOLLOWUP.md`.
