# React/UI Core Rewrite — Implementation Plan and Status

## Objective

Rebuild the UI framework from scratch as idiomatic C+ OOP, while keeping the generated program portable C and keeping all OS/device-specific behavior behind backend interfaces.

## Design rules

1. C+ source is authoritative. Never call or declare compiler-generated C symbol names manually.
2. Methods live inside the struct that owns the behavior.
3. Use static methods for factories/pure type operations and receiver methods for instance behavior.
4. Keep VDOM, layout, rendering, device driver, and React runtime separate.
5. Layout is explicit geometry only; no implicit CSS-like flow.
6. The core never depends on Raylib, terminal APIs, POSIX, Win32, sockets, or an HTTP implementation.
7. Prefer value returns for small values (`ui_rect_t`, layout traversal state) rather than pointers/out-parameters where ownership is unnecessary.
8. Keep recursive VDOM ownership explicit.

## Work completed

### Generic containers
- Added `dynamic_list<T>.add(T value)` as a value-taking convenience over `push(&value)`.
- Added regression coverage.

### VDOM
- Rebuilt values, attributes, events, styles, node factories, fragments, components, context providers, images, children, keys, ownership, and destruction as C+ objects.

### Layout
- Added actual `ui_layout_t` engine object.
- Kept `ui_layout_result_t` as result storage only.
- Moved `resolve_rect`, `attach_child`, emission, child traversal, and recursive node traversal into `ui_layout_t`.
- `resolve_rect` returns a rectangle by value.
- Implemented fixed / relative-parent / relative-sibling positions and zero geometry defaults.
- Implemented flat box/image drawable output.

### Renderer
- Added backend-neutral renderer interface.
- Added layout + render convenience path.
- Added printf backend.
- Ensured end-frame is called after a successful begin-frame even if a drawable fails.

### Driver
- Added backend event queue, monotonic time, wait/pump, viewport, and renderer aggregation.

### React
- Rebuilt component instances and hook storage.
- Implemented state, effects + cleanup, context, futures, stable identity reconciliation, event dispatch, timers, tick loop, and long-running event loop.
- Reconciliation preserves host ids/component state across compatible updates.
- Key matching is allocation-free.
- Render commit is VDOM -> layout -> renderer; React has no renderer-specific patch model.

## Compiler limitation isolated at renderer ABI

C+ 0.5.56 does not expose a method-reference expression that can be assigned to a C function pointer. Concrete renderers therefore require trivial callback bridge functions to enter instance methods through the generic renderer callback ABI. No generated symbol name is referenced and no framework logic lives in those bridges.

## Validation

- Containers: 11 fixtures / 108 assertions.
- Layout + renderer: 6 fixtures / 64 assertions.
- React: 10 fixtures / 73 assertions.
- Combined: 27 fixtures / 245 assertions / 0 failures.
- Layout/renderer generated C: ASan + UBSan + leak detection clean.
- React generated C: ASan + UBSan + leak detection clean.

## Deferred backend work

Raylib 2D and terminal/ANSI driver implementations are intentionally not part of this rewrite. They plug into the finalized renderer/driver interfaces and can be built next without changing VDOM, layout, or React core.
