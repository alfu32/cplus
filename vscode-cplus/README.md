# VS Code C-plus Module

This extension provides C-plus file detection for `.cp` and `.c+`, TextMate syntax highlighting, annotation/method completion, symbol navigation, and lightweight hover information.

## Development

```sh
npm install
npm run compile
```

Open this directory in VS Code and press `F5` to launch an Extension Development Host. The provider is intentionally local and lightweight; compiler-backed diagnostics and full semantic IntelliSense will be added when the compiler exposes a language-server protocol.
