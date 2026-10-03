# IDE project integration

`cpc new` creates a project that both IDE integrations can recognize:

- `cplus.toml` is the project manifest.
- `src/` is the source root.
- `modules/` is the local module root.
- `stdlib = "auto"` asks the CLI and plugins to discover the installed C-plus
  standard library.

The IntelliJ plugin registers the resolved stdlib as the `C-plus standard
library` project library and uses its source files for completion. VS Code uses
the same discovery rules for local completion; the CLI LSP remains the richer
cross-file provider when enabled.

The plugin command contract is consistent across hosts: command settings may
contain an executable and fixed arguments, while the integration appends its
owned subcommand. In particular, the LSP setting may be `cpc` or the legacy
`cpc lsp`, but it launches exactly `cpc lsp`.

