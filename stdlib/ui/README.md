# Device-independent React-like UI core for C+

## Modules

The UI framework is intentionally split so rendering and layout do not depend on the React runtime:

- `ui/vdom.cp` — virtual nodes, typed attributes, events, styles, child ownership, element/text/image/fragment/component/provider constructors and construction macros.
- `ui/react.cp` — components, hooks, context, futures, reconciliation, event dispatch, timers and the host driver/event-loop contract. Imports only `vdom.cp` from the UI layer.
- `ui/layout.cp` — device-independent box layout and flattening to drawables. Imports only `vdom.cp`; React is not required.
- `ui/renderer.cp` — graphics-renderer interface consuming layout emissions.
- `ui/printf_renderer.cp` — reference renderer that prints emitted boxes/images to a `FILE*`.

All framework/layout logic is ordinary device-independent C after C+ transcoding. There is no Raylib, terminal, POSIX, Win32, Cocoa, socket or HTTP dependency in these modules.

## VDOM

A `ui_vnode_t` owns:

- kind and runtime id
- optional reconciliation key
- optional tag / text
- typed attributes
- generic dynamic-list children (`dynamic_list(ui_vnode_t*)`)
- layout and graphics style
- kind-specific component/provider/image data

Nodes are created before insertion and child ownership transfers to the parent:

```c
UI_ELEMENT(root, "panel");
UI_TEXT(label, "hello");
UI_CHILD(root, label);
```

The generic dynamic list also provides value-semantic `add(T value)`, equivalent to `push(&value)`.

## Vnode style

Every vnode starts with:

```text
position = relative-parent
top      = 0
left     = 0
width    = 0
height   = 0
```

Layout accessors:

```c
ui_vnode_position(node, UI_POSITION_FIXED);
ui_vnode_top(node, 10);
ui_vnode_left(node, 20);
ui_vnode_width(node, 300);
ui_vnode_height(node, 100);

// or atomically:
ui_vnode_layout(node, UI_POSITION_RELATIVE_PARENT, 10, 20, 300, 100);
```

Position modes are deliberately smaller than CSS:

- `UI_POSITION_RELATIVE_PARENT` — default; `(left, top)` is relative to the parent box top-left.
- `UI_POSITION_FIXED` — `(left, top)` is relative to the layout viewport top-left.
- `UI_POSITION_RELATIVE_SIBLING` — `(left, top)` is relative to the previous sibling's resolved top-left; if no prior sibling exists, it falls back to the parent top-left.

The anchor is always top-left. The layout engine never implements block flow, inline flow, flexbox, intrinsic sizing or automatic sibling advancement. Components/parents are responsible for computing and assigning sizes/positions when they want those behaviors.

Graphics style fields are renderer-neutral:

```c
ui_vnode_background(node, "#202020");
ui_vnode_color(node, "white");
ui_vnode_border(node, "1 solid gray");
ui_vnode_font_family(node, "mono");
ui_vnode_font_size(node, 14);
ui_vnode_font_weight(node, 700);
ui_vnode_font_decoration(node, UI_FONT_DECORATION_UNDERLINE);
```

The strings are copied and owned by the vnode.

## Images

Images are explicit VDOM nodes with an opaque borrowed bitmap handle:

```c
UI_IMAGE(image, bitmap);
ui_vnode_layout(image, UI_POSITION_RELATIVE_PARENT, 10, 20, 64, 64);
```

The core does not prescribe pixel format or GPU representation. A concrete renderer interprets the bitmap handle it is given.

## Layout engine

`layout.cp` walks VDOM directly and produces a flat `ui_layout_result_t` containing `ui_drawable_t` entries.

Drawable kinds:

- `UI_DRAWABLE_BOX` — resolved `(x,y,width,height)`, graphics style, optional text, and indices of direct drawable children.
- `UI_DRAWABLE_IMAGE` — resolved `(x,y,width,height)` and opaque bitmap handle.

Fragments, context providers and React component nodes are transparent to graphics layout. A mounted component's `rendered` vnode is traversed instead. Therefore VDOM can be built and laid out without using React at all.

Typical use:

```c
ui_layout_result_t layout = {0};
ui_layout_result_init(&layout);
ui_layout_run(root, 1280, 720, &layout);
// consume layout.drawables
ui_layout_result_destroy(&layout);
```

## Graphics renderer interface

`renderer.cp` defines:

```c
typedef struct ui_renderer_t {
    void* user;
    int (*begin)(void* user, const ui_layout_result_t* layout);
    int (*draw_box)(void* user, const ui_drawable_box_t* box);
    int (*draw_image)(void* user, const ui_drawable_image_t* image);
    int (*end)(void* user, const ui_layout_result_t* layout);
} ui_renderer_t;
```

`ui_renderer_draw(renderer, layout)` visits the flat drawable list in paint order.
`ui_render_vdom(root, viewport_width, viewport_height, renderer)` is the convenience path that performs layout and rendering in one call.

A Raylib renderer can map `draw_box` to rectangle/border/text primitives and `draw_image` to a texture draw. A terminal renderer can map boxes/text into cells and images to whichever terminal graphics protocol it supports. Neither backend owns layout semantics.

## printf renderer

`printf_renderer.cp` is the minimal reference backend:

```c
ui_printf_renderer_state_t state;
ui_renderer_t renderer = ui_printf_renderer(&state, stdout);
ui_render_vdom(root, 800, 600, &renderer);
```

Example output:

```text
LAYOUT drawables=3
BOX node=0 parent=... x=1.00 y=2.00 w=100.00 h=50.00 background="blue" ... children=2
BOX node=0 parent=0 x=4.00 y=6.00 w=40.00 h=12.00 ... text="hello" children=0
IMAGE node=0 parent=0 x=6.00 y=22.00 w=32.00 h=16.00 bitmap=...
```

This backend is useful both for debugging and for deterministic layout integration tests.

## React runtime

`react.cp` retains the previous React-like behavior while importing VDOM from `vdom.cp`:

- keyed and positional reconciliation
- `use_state`
- `use_effect` with cleanup/dependencies
- `use_context`
- `use_future`
- normalized UI event dispatch
- timeout/interval timers
- non-blocking tick and optional blocking event loop

The host `ui_driver_t` remains the event/mutation runtime boundary. It is separate from `ui_renderer_t`: a future Raylib or terminal host can combine an event driver with a graphics renderer without coupling React, layout and graphics code.

## Tests

- `tests/containers.cp` — generic containers and `dynamic_list.add(T)`.
- `tests/react_core.cp` — VDOM ownership through React, reconciliation, hooks, context, futures, events and timers.
- `tests/layout.cp` — default style, all position modes, no implicit sibling flow, style propagation, text/image emissions, child drawable indices and printf renderer output.

Verified with C+ 0.5.56 tree-sitter frontend and GCC.
