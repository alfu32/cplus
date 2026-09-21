# Repository Guidelines

## Project Structure & Module Organization

This repository is currently a skeleton with no source, test, asset, or build directories. As the project grows, keep production code under `src/`, tests under `tests/`, and non-code resources under `assets/`. Group related functionality into focused modules rather than large catch-all files. Add a README when introducing a new executable, library, or major subsystem.

## Build, Test, and Development Commands

No build system or development scripts are present yet. When adding one, document the canonical commands in `README.md` and keep them reproducible from a clean checkout. Prefer a small, stable interface such as:

- `make build` (or the project’s equivalent) to compile/package the project.
- `make test` to run the complete automated suite.
- `make format` and `make lint` for automatic formatting and static checks.

Do not commit generated build output, caches, or local environment files unless explicitly required.

## Coding Style & Naming Conventions

Follow the formatter and linter selected by the project; formatting should be automated rather than debated in review. Use four spaces for indentation unless the chosen language ecosystem requires another standard. Name files and directories consistently, use `PascalCase` for types, `camelCase` for functions and variables, and `UPPER_SNAKE_CASE` for constants. Keep public interfaces documented and avoid unrelated refactors in feature changes.

## Testing Guidelines

No testing framework or coverage threshold is configured yet. Add tests alongside each new behavior, placing them under `tests/` and using names that describe the scenario and expected result (for example, `parser_rejects_missing_input`). Cover normal, boundary, and failure cases, and run the full suite before opening a pull request.

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

