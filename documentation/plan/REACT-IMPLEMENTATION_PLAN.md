# React / VDOM / layout / renderer implementation

Status: implemented and tested.

1. Keep the generic `dynamic_list.add(T)` value-semantic API.
2. Split VDOM into `ui/vdom.cp` so nodes/styles can be used without React.
3. Keep React component/hooks/reconciliation/event-loop logic in `ui/react.cp` importing VDOM.
4. Add renderer-neutral vnode layout and graphics style accessors.
5. Add explicit image vnodes carrying opaque borrowed bitmap handles.
6. Implement `ui/layout.cp` as a separate VDOM-only layout engine.
7. Support only fixed, relative-parent and relative-sibling positioning; no automatic flow or intrinsic sizing.
8. Emit flattened box/image drawables with resolved geometry and direct child drawable indices.
9. Define `ui_renderer_t` in `ui/renderer.cp` with begin/draw_box/draw_image/end callbacks.
10. Add `ui/printf_renderer.cp` as a reference/debug renderer.
11. Preserve all previous React-core regression tests.
12. Add layout/renderer tests and run the combined container + React + layout suite.
