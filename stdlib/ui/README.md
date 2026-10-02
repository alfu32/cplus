# C+ UI / React Core

This package is a device-independent UI framework written as C+ objects. The C+ source is the API; generated C symbol names are an implementation detail of the compiler and are never referenced by the framework source.

## Modules

- `vdom.cp` — virtual nodes, values, attributes, styles, events, component/provider/image node definitions.
- `layout.cp` — layout engine and backend-neutral drawable emission.
- `renderer.cp` — renderer interface that consumes layout emissions.
- `printf_renderer.cp` — reference renderer that prints every emitted drawable.
- `driver.cp` — renderer plus event polling, time, waiting, and viewport services.
- `react.cp` — component runtime, identity reconciliation, state/effect/context/future hooks, event dispatch, timers, and event loop.

The dependency direction is:

```
vdom
  ^
layout
  ^
renderer
  ^
driver
  ^
react
```

`layout.cp` imports only the VDOM layer. It does not depend on React. A program may therefore construct a VDOM manually and render it without a React runtime.

## C+ object model

Framework behavior lives in the type that owns it. Public or private methods are declared inside their structs. Static factories and pure type operations are declared `static`. Instance behavior uses the C+ receiver convention.

Example:

```c
ui_vnode_t* root = ui_vnode_t.element("panel");
root->style.layout.set(UI_POSITION_RELATIVE_PARENT, 10, 20, 300, 200);
root->style.graphics.background("#202020");

ui_layout_t layout;
layout.init();
layout.run(root, 800, 600);
layout.destroy();

root->destroy();
```

There are no manually written generated-style names such as `ui__layout_node`, `ui_layout__run`, or `_impl` wrappers in the C+ UI source.

Recursive VDOM objects are owned pointers, so the current C+ compiler uses `->` for their receiver calls. Value objects such as `ui_layout_t`, `ui_driver_t`, `ui_renderer_t`, lists, styles nested by value, etc. use `.` calls.

## VDOM

### `ui_value_t`

Static factories:

```c
ui_value_t.none();
ui_value_t.boolean(1);
ui_value_t.integer(42);
ui_value_t.real(3.14);
ui_value_t.string_value("hello");
ui_value_t.pointer(ptr);
ui_value_t.handler(UI_EVENT_MOUSE_UP, callback, user);
```

Instance operations:

```c
value.equals(&other);
value.destroy();
```

A value owns string storage only when its kind is `UI_VALUE_STRING`. Pointer and handler payloads are borrowed.

### `ui_vnode_t`

Owned node factories:

```c
ui_vnode_t.element("panel");
ui_vnode_t.text("hello");
ui_vnode_t.fragment();
ui_vnode_t.image(bitmap);
ui_vnode_t.component(render, props, sizeof(props));
ui_vnode_t.provider(&context, &value, sizeof(value));
```

Node operations include:

```c
node->key("stable-key");
node->attr("onClick", ui_value_t.handler(...));
node->child(child);                 // transfers child ownership
node->find_attr("name");
node->destroy();                    // recursively destroys the owned tree
```

`children` is a `dynamic_list(ui_vnode_t*)`. The generic list was extended with `add(T value)`, so an already-created child pointer can be inserted directly:

```c
root->children.add(child);
```

### Style

A vnode contains two separate value objects:

```c
node->style.layout
node->style.graphics
```

Layout style defaults to:

```text
position = relative-parent
left     = 0
top      = 0
width    = 0
height   = 0
```

Usage:

```c
node->style.layout.set(
    UI_POSITION_RELATIVE_PARENT,
    10,     // top
    20,     // left
    300,    // width
    100     // height
);
```

Individual setters are also available: `position`, `top`, `left`, `width`, `height`.

Graphics style provides:

```text
background
color
border
font-family
font-size
font-weight
font-decoration
```

Example:

```c
node->style.graphics.background("#202020");
node->style.graphics.color("white");
node->style.graphics.border("1 solid gray");
node->style.graphics.font_family("mono");
node->style.graphics.font_size(14);
node->style.graphics.font_weight(700);
node->style.graphics.font_decoration(UI_FONT_DECORATION_UNDERLINE);
```

## Layout engine

`ui_layout_t` is the layout engine. `ui_layout_result_t` is only the owned result data.

The engine owns its algorithm as inner methods:

```text
ui_layout_t
  static private resolve_rect()
  private        attach_child()
  private        emit_box()
  private        emit_image()
  private        layout_children()
  private        layout_node()
  public         init()
  public         run()
  public         destroy()
```

`resolve_rect()` returns `ui_rect_t` by value. It never returns a pointer to stack storage.

### Positioning

`UI_POSITION_RELATIVE_PARENT` (default):

```text
x = parent.x + left
y = parent.y + top
```

`UI_POSITION_FIXED`:

```text
x = viewport.x + left
y = viewport.y + top
```

`UI_POSITION_RELATIVE_SIBLING`:

```text
x = previous_sibling.x + left
y = previous_sibling.y + top
```

When there is no previous sibling, `relative-sibling` falls back to the parent anchor.

There is deliberately no inline/block/flex/grid/automatic flow model. The preceding sibling's width and height are not added to the next position. Components or parent components are responsible for sizing and arranging children.

Fragments, context providers, and component wrappers are transparent to layout. A component's rendered VDOM is traversed.

### Emissions

The result is a flat `ui_drawable_list_t` containing:

- `UI_DRAWABLE_BOX`
- `UI_DRAWABLE_IMAGE`

A box contains:

- vnode id
- parent drawable index
- resolved rectangle
- borrowed graphics style
- optional text
- direct drawable child indices

An image contains:

- vnode id
- parent drawable index
- resolved rectangle
- borrowed bitmap handle

The result borrows VDOM text/style/bitmap data, so the VDOM must remain alive while the result is being rendered.

## Renderer

`ui_renderer_t` is a backend-neutral drawable consumer. A backend provides callbacks for:

```text
begin frame (optional)
draw box
draw image
end frame (optional)
```

The generic renderer provides:

```c
renderer.draw(&layout.result);
renderer.render(root, 800, 600); // convenience: layout + draw
```

If a draw operation fails after a successful `begin_frame`, `end_frame` is still invoked so the backend can close or release frame state. The first drawing error is preserved.

### C+ 0.5.56 method-pointer limitation

C+ 0.5.56 can call methods normally, but it cannot currently convert a method declaration into a C function-pointer value. Therefore a backend whose methods are exposed through `ui_renderer_t` needs tiny callback bridges. For example, `printf_renderer.cp` has bridge functions that cast the opaque instance and immediately call `renderer->box(...)`, etc. They contain no layout or renderer policy. Generated C method symbol names are not referenced.

Once C+ supports method references compatible with C callbacks, those bridges can disappear without changing the renderer interface.

## Printf renderer

```c
ui_printf_renderer_t printer;
printer.init(stdout);

ui_renderer_t renderer = printer.interface();
renderer.render(root, 800, 600);
```

Example output:

```text
LAYOUT drawables=3
BOX node=1 parent=... x=20.00 y=10.00 w=300.00 h=100.00 ... children=2
BOX node=2 parent=0 x=... text="hello" children=0
IMAGE node=3 parent=0 x=... bitmap=0x...
```

This backend is intended both as a debugging tool and as the reference implementation of the renderer interface.

## Driver

`ui_driver_t` combines the graphics renderer with device-facing services required by the runtime:

- `poll(event)` — dequeue one native/backend event; `>0` means event, `0` empty, `<0` error.
- `now()` — monotonic backend time in milliseconds.
- `wait(timeout_ms)` — wait/pump until work or timeout.
- `size(width,height)` — current viewport size, with configured defaults.
- `render(root)` — obtain viewport size and invoke the renderer.

The core never calls POSIX, Win32, Raylib, terminal APIs, networking APIs, threads, or sockets directly.

A Raylib backend and a terminal backend can both implement this same driver contract later.

## React runtime

The React layer does not perform drawing. Its commit path is:

```text
components/hooks
      |
      v
active VDOM
      |
      v
layout engine
      |
      v
renderer
```

### Components

A component is a render function:

```c
ui_vnode_t* counter(
    ui_render_context_t* context,
    const void* props
) {
    ...
}
```

Create a component vnode with:

```c
ui_vnode_t.component(counter, &props, sizeof(props));
```

Props are copied into the vnode.

### State

```c
int initial = 0;
ui_state_t* state = context->use_state(&initial, sizeof(initial));

int* current = state->value();
int next = *current + 1;
state->set(&next, sizeof(next));
```

`set()` marks the owning component dirty only when the bytes actually change.

### Effects

```c
context->use_effect(effect, user, &dependencies, sizeof(dependencies));
```

Effects run after a successful render commit. A returned cleanup callback is called before a changed effect is rerun and on component destruction.

The dependency payload is an arbitrary byte sequence. Equal size + equal bytes means unchanged.

### Context

```c
ui_context_t theme;
theme.init("theme", &default_theme, sizeof(default_theme));

ui_vnode_t* provider = ui_vnode_t.provider(
    &theme,
    &dark_theme,
    sizeof(dark_theme)
);
```

Components call:

```c
const theme_t* current = context->use_context(&theme);
```

The nearest active provider wins; otherwise the context's default value is returned.

### Futures

`ui_future_t` is a device-independent completion object suitable for an HTTP/network backend to resolve later:

```c
ui_future_t future;
future.init();

// component
context->use_future(&future);

// backend/callback later
future.resolve(&response, sizeof(response));
// or
future.reject(error_code);
// or
future.cancel();
```

Resolution/rejection/cancellation marks subscribed components dirty. The runtime therefore rerenders on the next tick. The core does not perform HTTP itself.

### Reconciliation

VNode identity is preserved when type/key identity matches. Host ids and component hook instances survive updates.

Keyed children are matched by key and compatible vnode identity. Matching is allocation-free. A duplicate key in the new sibling list is treated as already claimed after its first compatible occurrence.

Unkeyed children are matched by position.

This is identity reconciliation, not renderer patch generation. The backend receives the current committed layout and may choose its own retained/immediate drawing strategy.

### Events

Renderer/backend events carry a stable `target_id`. The runtime finds that vnode and dispatches matching handler-valued attributes.

Example:

```c
button->attr(
    "onClick",
    ui_value_t.handler(UI_EVENT_MOUSE_UP, click, user)
);
```

The attribute name is descriptive metadata; dispatch is selected by the handler's event type.

### Timers and event loop

The runtime provides:

```c
runtime.set_timeout(delay_ms, callback, user);
runtime.set_interval(interval_ms, callback, user);
runtime.cancel_timer(id);
runtime.tick();
runtime.run();
runtime.stop();
```

All time and waiting comes from the driver. No platform clock or sleep API exists in React core.

`run()` repeatedly:

1. drains driver events,
2. runs due timers,
3. refreshes dirty components,
4. commits a frame when VDOM changed,
5. flushes effects after commit,
6. asks the driver to wait until the next useful wakeup.

## Ownership summary

- `ui_vnode_t.*` factories return owned nodes.
- `node->child(child)` transfers child ownership into the parent.
- component props and provider values are copied and owned by their vnode.
- component rendered trees and hook instances are owned by the React runtime tree.
- `ui_layout_t` owns `ui_layout_result_t`; result drawables borrow VDOM style/text/bitmap data.
- renderer/driver opaque backend instances are borrowed.
- futures own their resolved byte payload but borrow subscriber dirty flags while mounted.

## Tests

Current suites cover:

- generic dynamic-list `add(T)` semantics,
- VDOM ownership and defaults,
- all three positioning modes,
- no-flow relative-sibling semantics,
- graphics style propagation,
- text/image emission and child indices,
- printf rendering,
- component mount/commit,
- state rerender + host identity preservation,
- effect execution/cleanup/dependencies,
- nearest context provider,
- future wakeups,
- event dispatch,
- timers using driver time,
- keyed reconciliation,
- component prop update with state identity preservation,
- hook-order errors.

The UI tests are also transcoded to C and run under AddressSanitizer + UndefinedBehaviorSanitizer with leak detection.
