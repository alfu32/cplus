#ifndef CPLUS_UI_RENDERER_CP
#define CPLUS_UI_RENDERER_CP

comptime import "stdlib:/ui/layout.cp";

typedef struct ui_renderer_t {
    borrowed void* user;
    int (*begin)(borrowed void* user, borrowed const ui_layout_result_t* layout);
    int (*draw_box)(borrowed void* user, borrowed const ui_drawable_box_t* box);
    int (*draw_image)(borrowed void* user, borrowed const ui_drawable_image_t* image);
    int (*end)(borrowed void* user, borrowed const ui_layout_result_t* layout);
} ui_renderer_t;

pub int ui_renderer_draw(borrowed const ui_renderer_t* renderer, borrowed const ui_layout_result_t* layout) {
    if (renderer == NULL || layout == NULL || renderer->draw_box == NULL || renderer->draw_image == NULL) return 1;
    if (renderer->begin != NULL && renderer->begin(renderer->user, layout) != 0) return 1;
    for (size_t i = 0; i < layout->drawables.length; i++) {
        const ui_drawable_t* drawable = &layout->drawables.items[i];
        int result = drawable->kind == UI_DRAWABLE_BOX
            ? renderer->draw_box(renderer->user, &drawable->as.box)
            : renderer->draw_image(renderer->user, &drawable->as.image);
        if (result != 0) return result;
    }
    if (renderer->end != NULL) return renderer->end(renderer->user, layout);
    return 0;
}

pub int ui_render_vdom(borrowed const ui_vnode_t* root, double viewport_width, double viewport_height, borrowed const ui_renderer_t* renderer) {
    ui_layout_result_t layout;
    memset(&layout, 0, sizeof(layout));
    if (ui_layout_result_init(&layout) != 0) return 1;
    int result = ui_layout_run(root, viewport_width, viewport_height, &layout);
    if (result == 0) result = ui_renderer_draw(renderer, &layout);
    ui_layout_result_destroy(&layout);
    return result;
}

#endif
