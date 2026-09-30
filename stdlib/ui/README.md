# Device-independent React-like UI core for C+

## 1. Scope

This module implements a renderer-independent retained virtual UI runtime in C/C+.
It contains no Raylib, terminal, POSIX, Win32, Cocoa, Android, browser, socket, or HTTP implementation.
Platform/device behavior enters only through `ui_driver_t` callbacks.

Import:

```c
comptime import "stdlib:/ui/react.cp";
```

The source is C+ for integration with the standard library generics; the transcoded implementation is ordinary C.

## 2. Architecture

The runtime is split into five logical layers:

1. **Virtual DOM** — `ui_vnode_t`, attributes, text, fragments, components, context providers, keys and children.
2. **Component runtime** — component instances, hook slots, dirty scheduling and component-local reconciliation.
3. **Reconciler** — compares retained trees and emits backend-neutral patches.
4. **Event/async runtime** — driver event polling, event handler dispatch, timers and generic futures.
5. **Driver boundary** — host integration for drawing/presentation, input polling, time and optional blocking wait.

Raylib and terminal backends should implement `ui_driver_t`; they must not be imported by this module.

## 3. Virtual DOM

A node has:

- `kind`: element, text, fragment, component or context provider
- `id`: runtime-assigned stable identity
- `key`: optional reconciliation key
- `tag`: renderer-defined element type such as `box`, `text`, `button`, `row`, etc.
- `text`: text-node payload
- `attrs`: `dynamic_list(ui_attr_t)`
- `children`: `dynamic_list(ui_vnode_t*)`

Children are pointers because recursive by-value storage is not representable in C without an indirection. The child list itself is still the generic dynamic list. Children are created before insertion and ownership transfers to the parent.

Typical construction:

```c
UI_ELEMENT(root, "row");
UI_TEXT(label, "hello");
UI_ATTR(root, "role", ui_value_string("toolbar"));
UI_CHILD(root, label);
```

The generic dynamic list has also been extended with:

```c
pub int add(borrowed mut *self, const T value);
```

`add(value)` is value-semantic and equivalent to `push(&value)`.

## 4. Attribute values

`ui_value_t` supports:

- none
- bool
- signed integer
- double
- owned string
- borrowed pointer
- event handler

A backend receives the same typed value in `UI_PATCH_SET_ATTRIBUTE` and decides which attributes it understands.
Unknown attributes may be ignored by a backend.

## 5. Components

A component is:

```c
typedef ui_vnode_t* (*ui_component_fn)(ui_render_context_t* context, const void* props);
```

Props are copied into the component vnode when it is created.
The vnode returned by the component becomes owned by the component instance.

Component identity is `(component function, key, position)` for unkeyed children and `(component function, key)` for keyed children.

## 6. Hooks

Hooks are stored in a stable ordered slot list per component instance. Hook order and hook type must remain identical between renders. A mismatch is a runtime error.

### State

```c
ui_state_t* count = use_state(context, int, 0);
int current = state_value(int, count);
state_set(int, count, current + 1);
```

A state update marks only the owning component dirty, and the next runtime tick reconciles that component subtree.

### Effect

```c
ui_cleanup_fn effect(void* user) { ... }
use_effect(context, effect, user, &deps, sizeof(deps));
```

Effects run after the patch frame is committed. On dependency change, the previous cleanup runs before the next effect. Cleanup also runs on unmount.

`deps_size == 0` means a stable empty dependency set (mount/unmount behavior).

### Context

```c
ui_context_t theme;
ui_context_init(&theme, "theme", &default_theme, sizeof(default_theme));
const theme_t* value = use_context(context, theme_t, &theme);
```

A provider is a transparent virtual node:

```c
UI_PROVIDER(provider, &theme, &dark_theme, sizeof(dark_theme));
UI_CHILD(provider, child_component);
```

Providers are lexically scoped during traversal and do not require global mutable state.

### Future

```c
ui_future_t future;
ui_future_init(&future);
ui_future_t* current = use_future(context, &future);
```

A future can be resolved, rejected or cancelled. Completion wakes subscribed components.
The core deliberately does not perform HTTP or thread creation. An HTTP library, worker pool or backend can resolve the future when work completes.
A future must outlive components currently subscribed to it.

## 7. Reconciliation and patch protocol

The reconciler emits:

- `UI_PATCH_MOUNT`
- `UI_PATCH_UNMOUNT`
- `UI_PATCH_MOVE`
- `UI_PATCH_SET_ATTRIBUTE`
- `UI_PATCH_REMOVE_ATTRIBUTE`
- `UI_PATCH_SET_TEXT`

Every mounted virtual node, including fragments/components/providers, receives an ID. Non-rendering node kinds act as logical groups. This gives every backend the same stable tree model and avoids renderer-specific flattening rules in core.

Unkeyed children reconcile positionally. Keyed children are matched by key and identity and emit `UI_PATCH_MOVE` when reordered.

## 8. Driver interface

```c
typedef struct ui_driver_t {
    void* user;
    int (*begin_frame)(void* user);
    int (*apply_patch)(void* user, const ui_patch_t* patch);
    int (*end_frame)(void* user);
    int (*poll_event)(void* user, ui_event_t* out_event);
    uint64_t (*now_ms)(void* user);
    int (*wait)(void* user, uint64_t timeout_ms); // optional
} ui_driver_t;
```

Responsibilities:

- `begin_frame`: begin one atomic UI mutation batch
- `apply_patch`: mutate backend retained state
- `end_frame`: finish/commit/draw as appropriate
- `poll_event`: dequeue at most one native UI event; return 1 for event, 0 for empty, <0 for error
- `now_ms`: monotonic backend clock in milliseconds
- `wait`: optionally block until input/wakeup/timeout

A Raylib driver can store a retained logical tree and draw it each frame. A terminal driver can map the same tree into cells and ANSI output. Neither requires changes to React core.

## 9. Events

The core normalizes keyboard, text, mouse, focus, custom and quit events into `ui_event_t`.
The driver supplies `target_id` after hit testing/focus resolution.
Handlers are typed attribute values created with:

```c
ui_value_handler(UI_EVENT_MOUSE_UP, callback, user)
```

The runtime locates the target vnode and dispatches matching handlers.

## 10. Timers and event loop

The runtime provides:

```c
ui_set_timeout(...)
ui_set_interval(...)
ui_cancel_timer(...)
ui_runtime_tick(...)
ui_runtime_run(...)
ui_runtime_stop(...)
```

Timers are based exclusively on `driver.now_ms`; no OS clock API is used by core.
`ui_runtime_tick` is non-blocking. `ui_runtime_run` uses optional `driver.wait` to avoid spinning.

## 11. Ownership rules

- `ui_runtime_mount` takes ownership of the root vnode.
- `UI_CHILD` transfers child ownership to its parent.
- component props are copied.
- string attributes are copied and owned by the vnode.
- context provider values are copied.
- state values and effect dependency bytes are copied.
- event-handler `user` pointers and pointer attributes are borrowed.
- future payloads are copied on resolution.
- `ui_runtime_destroy` recursively releases the retained tree and component state.

Do not attach the same vnode to multiple parents.

## 12. Backend implementation plan

The next two drivers can be implemented independently:

**Raylib 2D**
- retain node/property hierarchy keyed by `node_id`
- layout pass
- hit-test pass
- translate Raylib keyboard/mouse input to `ui_event_t`
- draw during `end_frame`

**Terminal/ANSI**
- retain the same hierarchy
- layout to terminal rows/columns
- render into a cell backbuffer
- diff previous/current cell buffers and emit ANSI cursor/style sequences
- decode terminal keyboard/mouse escape sequences into `ui_event_t`

The core API does not need backend-specific extensions for either initial implementation.

## 13. Test specification

`stdlib/tests/react_core.cp` verifies:

1. child storage through generic `dynamic_list.add(T)`
2. mount patch ordering and typed attributes
3. state update -> dirty component -> text reconciliation
4. effect commit timing, dependency behavior and cleanup
5. provider scoping / `use_context`
6. future subscription and wakeup
7. driver event dequeue and target dispatch
8. timeout/interval behavior against a fake driver clock
9. root reconciliation without unnecessary remount
10. keyed identity preservation and move patches
11. hook-order invariant enforcement

`stdlib/tests/containers.cp` also verifies the new `dynamic_list.add(T)` API.

Validated with C+ 0.5.56 tree-sitter frontend and GCC:

- container + React core: 22 fixtures, 190 assertions, 0 failures
- React core standalone: 11 fixtures, 82 assertions, 0 failures
- generated C compiled with `-Wall -Wextra -fsanitize=address`
- sanitizer execution completed without AddressSanitizer or leak failures

Known compiler note: the legacy frontend in C+ 0.5.56 does not correctly lower a generic struct method that accepts `T` by value. The tree-sitter frontend does. Therefore `dynamic_list.add(T)` currently requires `--frontend tree-sitter` until that legacy lowering bug is fixed.
