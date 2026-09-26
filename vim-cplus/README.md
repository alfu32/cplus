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
:CPlusParse
:CPlusSymbols
:CPlusImportGraph
:CPlusTranscode
:CPlusCompile
:CPlusRun
```

Compiler and parser diagnostics are loaded into the quickfix list. `:CPlusParse` and `:CPlusSymbols` send the current buffer text to the CLI through `cplus parse --stdin --source <path>`, so they include unsaved edits. Parse diagnostics convert UTF-16 columns to Vim byte columns; symbols build a navigable location-list outline from normalized struct, field, method, and function nodes. `:CPlusImportGraph` consumes `cplus.imports.v1` and displays resolved imported files in the location list; selecting an entry opens the imported file, while its label retains the requesting file and line. Import-graph resolution reads files from disk, so save the current file and imports first. These location-list commands leave the diagnostics quickfix list intact. Parser commands require a C-plus CLI distribution containing the parser JNI library for the current host. Set `let g:cplus_check_on_write = 1` to compile after every save or `let g:cplus_parse_on_write = 1` to run the parser after every save.

From the repository root, run `vim -Nu NONE -i NONE -n -es -S vim-cplus/test/outline.vim` to verify parser-backed symbols, import-graph navigation, and Unicode-aware quickfix positions; it uses `cli/build/libs/c-plus.jar`.
