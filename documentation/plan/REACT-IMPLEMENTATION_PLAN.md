# C+ idiomatic UI refactor

## Goal
Refactor the device-independent UI framework so public operations are declared on their owning structs and consumed through C+ receiver/static-method syntax rather than C-style free functions.

## Resulting module API

- `ui/vdom.cp`
  - `ui_value_t`: static factories (`none`, `boolean`, `integer`, `real`, `string`, `pointer`, `handler`), instance `equals`, `destroy`.
  - `ui_attr_t`: instance `destroy`.
  - `ui_graphics_style_t` / `ui_style_t`: instance lifecycle.
  - `ui_vnode_t`: static factories (`element`, `text_node`, `fragment`, `image`, `component`, `provider`); instance key/attribute/child/style/layout/graphics setters and `destroy`.
- `ui/react.cp`
  - `ui_context_t`: `init`, `destroy`.
  - `ui_future_t`: `init`, `resolve`, `reject`, `cancel`, `destroy`.
  - `ui_state_t`: `set_raw` (typed `state_set` remains a convenience macro).
  - `ui_render_context_t`: raw hook methods used by typed hook macros.
  - `ui_runtime_t`: `init`, `mount`, `update_root`, `dispatch`, timers, `tick`, `run`, `stop`, `destroy`.
- `ui/layout.cp`
  - `ui_layout_result_t`: `init`, `run`, `destroy`.
- `ui/renderer.cp`
  - `ui_renderer_t`: `draw`, `render`.
- `ui/printf_renderer.cp`
  - `ui_printf_renderer_state_t.renderer(stream)` creates a renderer bound to the state.

## Calling convention examples

```c
ui_vnode_t* root = ui_vnode_t.element("panel");
root->layout(UI_POSITION_RELATIVE_PARENT, 0, 0, 320, 200);
root->background("#202020");

ui_runtime_t runtime;
runtime.init(driver);
runtime.mount(root);
runtime.tick();
runtime.destroy();
```

## Macro note
`UI_ELEMENT`, `UI_TEXT`, `UI_CHILD`, etc. are ordinary C preprocessor compatibility helpers. C preprocessor expansion occurs after C+ receiver resolution, so a variable declared only inside such a macro is not visible to the C+ type resolver for a later `object.method(...)` call. Static constructors are therefore the preferred idiomatic form whenever receiver calls follow.

## Validation
- `containers.cp`: 11 fixtures / 108 assertions
- `react_core.cp`: 11 fixtures / 82 assertions
- `layout.cp`: 6 fixtures / 76 assertions
- aggregate: 28 fixtures / 266 assertions / 0 failures
- React and layout test outputs also compiled and ran under AddressSanitizer + UndefinedBehaviorSanitizer with leak detection; sanitizer stderr was empty.
