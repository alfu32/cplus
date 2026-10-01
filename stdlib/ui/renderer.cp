#ifndef CPLUS_UI_RENDERER_CP
#define CPLUS_UI_RENDERER_CP

comptime import "stdlib:/ui/layout.cp";

typedef struct ui_renderer_t {
    borrowed void* user;
    int (*begin)(borrowed void* user, borrowed const ui_layout_result_t* layout);
    int (*draw_box)(borrowed void* user, borrowed const ui_drawable_box_t* box);
    int (*draw_image)(borrowed void* user, borrowed const ui_drawable_image_t* image);
    int (*end)(borrowed void* user, borrowed const ui_layout_result_t* layout);

    pub int draw(borrowed const *self, borrowed const ui_layout_result_t* layout) {
        if (self == NULL || layout == NULL || self->draw_box == NULL || self->draw_image == NULL) return 1;
        if (self->begin != NULL && self->begin(self->user, layout) != 0) return 1;
        for (size_t i = 0; i < layout->drawables.length; i++) {
            const ui_drawable_t* drawable = &layout->drawables.items[i];
            int result = drawable->kind == UI_DRAWABLE_BOX
                ? self->draw_box(self->user, &drawable->as.box)
                : self->draw_image(self->user, &drawable->as.image);
            if (result != 0) return result;
        }
        if (self->end != NULL) return self->end(self->user, layout);
        return 0;
    }

    pub int render(borrowed const *self, borrowed const ui_vnode_t* root, double viewport_width, double viewport_height) {
        if (self == NULL || root == NULL) return 1;
        ui_layout_result_t layout;
        memset(&layout, 0, sizeof(layout));
        if (layout.init() != 0) return 1;
        int result = layout.run(root, viewport_width, viewport_height);
        if (result == 0) result = self->draw(&layout);
        layout.destroy();
        return result;
    }
} ui_renderer_t;

#endif
