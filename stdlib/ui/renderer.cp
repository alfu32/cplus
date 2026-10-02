#ifndef CPLUS_UI_RENDERER_CP
#define CPLUS_UI_RENDERER_CP

comptime import "stdlib:/ui/layout.cp";

typedef int (*ui_renderer_begin_fn)(borrowed void* instance, borrowed const ui_layout_result_t* layout);
typedef int (*ui_renderer_box_fn)(borrowed void* instance, borrowed const ui_drawable_box_t* box);
typedef int (*ui_renderer_image_fn)(borrowed void* instance, borrowed const ui_drawable_image_t* image);
typedef int (*ui_renderer_end_fn)(borrowed void* instance, borrowed const ui_layout_result_t* layout);

typedef struct ui_renderer_t {
    borrowed void* instance;
    ui_renderer_begin_fn begin_frame;
    ui_renderer_box_fn draw_box;
    ui_renderer_image_fn draw_image;
    ui_renderer_end_fn end_frame;

    pub int init(
        borrowed mut *self,
        borrowed void* instance,
        ui_renderer_begin_fn begin_frame,
        ui_renderer_box_fn draw_box,
        ui_renderer_image_fn draw_image,
        ui_renderer_end_fn end_frame
    ) {
        if (self == NULL || draw_box == NULL || draw_image == NULL) return 1;
        self->instance = instance;
        self->begin_frame = begin_frame;
        self->draw_box = draw_box;
        self->draw_image = draw_image;
        self->end_frame = end_frame;
        return 0;
    }

    pub int draw(borrowed *self, borrowed const ui_layout_result_t* layout) {
        if (self == NULL || layout == NULL || self->draw_box == NULL || self->draw_image == NULL) return 1;
        if (self->begin_frame != NULL && self->begin_frame(self->instance, layout) != 0) return 1;

        int error = 0;
        for (size_t i = 0; i < layout->drawables.length && error == 0; i++) {
            borrowed const ui_drawable_t* drawable = &layout->drawables.items[i];
            error = drawable->kind == UI_DRAWABLE_BOX
                ? self->draw_box(self->instance, &drawable->as.box)
                : self->draw_image(self->instance, &drawable->as.image);
        }

        if (self->end_frame != NULL) {
            int end_error = self->end_frame(self->instance, layout);
            if (error == 0) error = end_error;
        }
        return error;
    }

    pub int render(
        borrowed *self,
        borrowed const ui_vnode_t* root,
        double viewport_width,
        double viewport_height
    ) {
        if (self == NULL || root == NULL) return 1;

        ui_layout_t layout;
        if (layout.init() != 0) return 1;

        int error = layout.run(root, viewport_width, viewport_height);
        if (error == 0) error = self->draw(&layout.result);

        layout.destroy();
        return error;
    }
} ui_renderer_t;

#endif
