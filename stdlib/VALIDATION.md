# Validation

Compiler: attached `cplus-0.5.56-bare.jar`, tree-sitter frontend, external GCC.

## Combined unit regression

```sh
CC=gcc java -jar cplus-0.5.56-bare.jar test run \
  --frontend tree-sitter \
  --stdlib stdlib \
  stdlib/tests/containers.cp \
  stdlib/tests/ui_layout.cp \
  stdlib/tests/react.cp \
  -v2
```

Result:

```text
containers.cp   11 fixtures   108 assertions   PASS
ui_layout.cp     6 fixtures    64 assertions   PASS
react.cp        10 fixtures    73 assertions   PASS
---------------------------------------------------
TOTAL           27 fixtures   245 assertions   PASS
failed files: 0
```

## Generated-C sanitizers

Both `ui_layout.cp` and `react.cp` test harnesses were transcoded with the tree-sitter frontend and compiled with:

```sh
gcc -std=c11 -g -O1 \
  -fsanitize=address,undefined \
  -fno-omit-frame-pointer \
  generated_test.c -o generated_test
```

They were executed with:

```sh
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1 \
./generated_test
```

Result: both passed with empty sanitizer stderr; no ASan, leak, or UBSan diagnostics.

## Reference printf renderer example

`examples/ui_printf.cp` was compiled and run through the attached C+ compiler. It emitted:

```text
LAYOUT drawables=2
BOX node=0 parent=18446744073709551615 x=20.00 y=10.00 w=320.00 h=120.00 background="#202020" color="white" border="" font-family="" font-size=0.00 font-weight=0 font-decoration=0 text="" children=1
BOX node=0 parent=0 x=32.00 y=18.00 w=200.00 h=24.00 background="" color="" border="" font-family="mono" font-size=16.00 font-weight=0 font-decoration=0 text="Hello from C+" children=0
```

Node IDs are zero in this direct VDOM example because stable interactive IDs are assigned by `ui_runtime_t`; direct layout/rendering does not require them.
