# C-plus

C-plus is a small Kotlin command-line processor that lowers C-plus source (`.cp` or `.c+`) to ordinary C. C-plus keeps C's expressions and statements, adding struct-scoped methods, method-call syntax, and ownership/visibility annotations.

## Build and run

This is a Java 21-compatible Gradle project. The wrapper is the canonical build entry point:

```sh
./gradlew build
./gradlew test
./gradlew run --args='help'
```

To create a self-contained release jar, pass the release version explicitly:

```sh
./gradlew -Prelease=0.2.0 fatJar
java -jar build/libs/c-plus-0.2.0.jar help
```

The bundled TinyCC JNI library is used for compilation, so `compile` and `run` do not require a system `tcc` executable:

```sh
./gradlew run --args='transcode examples/basic.cp -o build/basic.c'
./gradlew run --args='compile examples/basic.cp -o build/basic -DDEBUG=1'
./gradlew run --args='run examples/basic.cp -o build/basic-run -Iinclude'
```

The installed application can also be launched from `build/install/c-plus/bin/c-plus` after `./gradlew installDist`.

## Current lowering rules

- `typedef struct name_t { ... } name_t;` method definitions and declarations are moved outside the struct.
- An instance method whose first parameter is `*self` receives an implicit `name_t *self` parameter.
- Methods become `name__method(...)`; for example, `(&value).reset()` becomes `name__reset(&value)`.
- `name_t.method(...)` becomes the corresponding static method call.
- `pub`, `priv`, `mut`, `borrowed`, `owned`, and `stat` are retained as empty C macros. A `static` method remains a C `static` function.

The receiver type is inferred from declarations such as `name_t value;` or `name_t *value;`. Unknown receivers are left unchanged so ordinary C remains valid.

Comptime and generic declarations beginning with `@` are reserved by the syntax, but are not expanded yet; the processor reports a focused error instead of emitting invalid C. They are intentionally deferred to the next implementation pass.
