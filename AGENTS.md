# Repository Guidelines

## Project Structure & Module Organization

Keep production code in the focused module that owns it. The Kotlin compiler is under `compiler/src/main`, the CLI and its tests are under `cli/src`, editor integrations live in `vscode-cplus/`, `intellij-cplus/`, and `vim-cplus/`, and living specifications are under `documentation/spec/`. Add a README when introducing a new executable, library, or major subsystem.

## Build, Test, and Development Commands

Use the Gradle wrapper and keep commands reproducible from a clean checkout:

- `./gradlew build` compiles and packages all Kotlin modules.
- `./gradlew test` runs the CLI/compiler test suite.
- `./gradlew run --args='help'` runs the CLI through the aggregate project.
- `./gradlew -Prelease=0.2.0 fatJar` builds the self-contained CLI jar.
- `npm install && npm run compile` builds the VS Code extension.

Do not commit generated build output, caches, `node_modules`, or local environment files unless explicitly required.

## Coding Style & Naming Conventions

Follow the formatter and linter selected by the project; formatting should be automated rather than debated in review. Use four spaces for indentation unless the chosen language ecosystem requires another standard. Name files and directories consistently, use `PascalCase` for types, `camelCase` for functions and variables, and `UPPER_SNAKE_CASE` for constants. Keep public interfaces documented and avoid unrelated refactors in feature changes.

## Testing Guidelines

Kotlin tests use JUnit 5 under `cli/src/test`. Name tests for the behavior and expected result, cover normal and failure cases, and run `./gradlew test` before opening a pull request. Editor modules should add focused fixture tests as their tooling is introduced.

## Commit & Pull Request Guidelines

There is no existing Git history from which to infer a repository-specific convention. Use short, imperative commit subjects (for example, `Add parser validation`) and keep each commit focused. Pull requests should explain the change, rationale, validation commands and results, and any follow-up work. Link an issue when one exists; include screenshots or logs when changing user-visible behavior.

## response guidelines

- always respond in the sum up in the commitizen format

All commits must follow the Commitizen / Conventional Commits standard using the structural layout below:

### Commitizen / Conventional Commits standard
```text
<type>(<scope>): <subject>

<body>
```

#### Field Definitions

* **`<type>`**: Must be one of the following lowercase tokens:
    * `feat`: A new feature or capability.
    * `fix`: A bug fix.
    * `docs`: Documentation changes only.
    * `style`: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc).
    * `refactor`: A code change that neither fixes a bug nor adds a feature.
    * `perf`: A code change that improves performance.
    * `test`: Adding missing tests or correcting existing tests.
    * `chore`: Changes to the build process, auxiliary tools, or libraries/dependencies.
* **`<scope>`**: Optional. A noun naming the specific codebase component or module affected, wrapped in parentheses (e.g., `(parser)`, `(auth)`, `(runtime)`).
* **`<subject>`**: A brief, imperative-mood summary of the change. Do not capitalize the first letter. Do not end with a period.
* **`<body>`**: Optional. Separate from the subject with exactly one blank line. Provides the motivation for the change and contrasts it with previous behavior.

additionally the body should be structured as follows:

(REQUEST:)
- summary of what was asked/requested

(IMPLEMENTATION:)
- summary of the solution or answer
implementation details:
- bulleted list of technical/functional modifications or planning steps ( what you print out by default in the summary )

(NOT IMPLEMENTED:)
 - summary of not implemented features/parts of the request
 - features/requests remaining to be implemented/researched
 - eventual steps/tests to be taken by the user before proceeding

#### Examples

```text
fix(editor): persist and reveal mapped compiler diagnostics

REQUEST:
the user has to be able to see error points given by diagnostics by expandable markers in the gutter

IMPLEMENTATION:
  - Diagnostics are persisted on each node and restored with the project.
  - New validation/compilation clears previous diagnostics.
  - Gutter markers now reveal the mapped editor, section, and source line automatically.
  - Nodes with diagnostics show a red warning badge in the diagram.
  - Runtime/override errors without source-map entries are retained and shown as unmapped instead of being discarded.
  - The status bar now shows:
    generated-file:line:column -> node section source-line:column

NOT IMPLEMENTED:
  - colorisation and retrieval of code artifacts
  - research solution through local / embedded small LM.
    - we need CUDA working on this machine otherwise we'll not be able to test
```

```text
fix(compiler): resolve memory leaks on dynamic execution evaluation loops
```


## Security & Configuration Tips

Never commit credentials, tokens, private keys, or machine-specific configuration. Provide safe example configuration with placeholder values and document required environment variables. Review dependency and generated-file changes carefully before committing.
