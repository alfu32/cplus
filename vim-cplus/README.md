# Vim C-plus Module

This Vim runtime module detects `.cp` and `.c+` files, highlights C-plus syntax, provides an `omnifunc` for annotations, types, comptime forms, fields, and methods, and exposes compiler commands.

Install by adding this directory to Vim's `runtimepath`, for example:

```vim
set runtimepath^=/path/to/c-plus/vim-cplus
```

Use `i_CTRL-X_CTRL-O` for completion. Set `g:cplus_command` to the C-plus CLI (defaults to `cplus`) and use:

```vim
:CPlusCheck
:CPlusTranscode
:CPlusCompile
:CPlusRun
```

Compiler diagnostics are loaded into the quickfix list. Set `let g:cplus_check_on_write = 1` to compile after every save.
