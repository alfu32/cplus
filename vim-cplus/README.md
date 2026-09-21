# Vim C-plus Module

This Vim runtime module detects `.cp` and `.c+` files, highlights C-plus syntax, and provides an `omnifunc` for annotations and method names found in the current buffer.

Install by adding this directory to Vim's `runtimepath`, for example:

```vim
set runtimepath^=/path/to/c-plus/vim-cplus
```

Use `i_CTRL-X_CTRL-O` for completion. Compiler-backed diagnostics and richer semantic completion should be connected through the shared language-server protocol when it is introduced.
