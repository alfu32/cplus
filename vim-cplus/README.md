# Vim C-plus Module

This Vim runtime module detects `.cp` and `.c+` files, highlights C-plus syntax, provides an `omnifunc` for annotations, types, comptime forms, fields, and methods after either `value.` or `pointer->`, and exposes compiler commands.

The compiler-lowered `defer` statement and `@throws`/`@try`/`@catch` error-handling forms have dedicated C-plus highlighting and completion.

It gives comptime directives and built-ins, `comptime flags`, the target value `os`, reflection properties, assertion/test macros, and the `self` receiver distinct highlighting. Completion includes C keywords, all C-plus annotations, comptime forms and built-ins, test helper macros, identifier splices such as `mapper__@name(T)__to__@name(R)`, and simple `#define generated_name public_name` aliases. Vim does not evaluate comptime itself; use `:CPlusTranscode` to inspect the generated C.

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
