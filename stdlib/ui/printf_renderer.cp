#ifndef CPLUS_UI_PRINTF_RENDERER_CP
#define CPLUS_UI_PRINTF_RENDERER_CP

#include <stdio.h>
comptime import "stdlib:/ui/renderer.cp";

typedef struct ui_printf_renderer_state_t {
    borrowed FILE* stream;

    static priv borrowed const char* safe_text(borrowed const char* text) { return text == NULL ? "" : text; }

    static priv int begin_cb(borrowed void* user, borrowed const ui_layout_result_t* layout) {
        ui_printf_renderer_state_t* state = user;
        return fprintf(state->stream, "LAYOUT drawables=%zu\n", layout->drawables.length) < 0 ? 1 : 0;
    }

    static priv int box_cb(borrowed void* user, borrowed const ui_drawable_box_t* box) {
        ui_printf_renderer_state_t* state = user;
        const ui_graphics_style_t* style = box->style;
        int written = fprintf(state->stream,
            "BOX node=%llu parent=%zu x=%.2f y=%.2f w=%.2f h=%.2f background=\"%s\" color=\"%s\" border=\"%s\" font-family=\"%s\" font-size=%.2f font-weight=%d font-decoration=%u text=\"%s\" children=%zu\n",
            (unsigned long long)box->node_id, box->parent_drawable,
            box->rect.x, box->rect.y, box->rect.width, box->rect.height,
            style == NULL ? "" : ui_printf_renderer_state_t.safe_text(style->background),
            style == NULL ? "" : ui_printf_renderer_state_t.safe_text(style->color),
            style == NULL ? "" : ui_printf_renderer_state_t.safe_text(style->border),
            style == NULL ? "" : ui_printf_renderer_state_t.safe_text(style->font_family),
            style == NULL ? 0.0 : style->font_size,
            style == NULL ? 0 : style->font_weight,
            style == NULL ? 0u : style->font_decoration,
            ui_printf_renderer_state_t.safe_text(box->text), box->children.length);
        return written < 0 ? 1 : 0;
    }

    static priv int image_cb(borrowed void* user, borrowed const ui_drawable_image_t* image) {
        ui_printf_renderer_state_t* state = user;
        int written = fprintf(state->stream,
            "IMAGE node=%llu parent=%zu x=%.2f y=%.2f w=%.2f h=%.2f bitmap=%p\n",
            (unsigned long long)image->node_id, image->parent_drawable,
            image->rect.x, image->rect.y, image->rect.width, image->rect.height, image->bitmap);
        return written < 0 ? 1 : 0;
    }

    static priv int end_cb(borrowed void* user, borrowed const ui_layout_result_t* layout) {
        (void)layout;
        ui_printf_renderer_state_t* state = user;
        return fflush(state->stream) == 0 ? 0 : 1;
    }

    pub ui_renderer_t renderer(borrowed mut *self, borrowed FILE* stream) {
        ui_renderer_t renderer;
        memset(&renderer, 0, sizeof(renderer));
        if (self == NULL) return renderer;
        self->stream = stream == NULL ? stdout : stream;
        renderer.user = self;
        renderer.begin = ui_printf_renderer_state__begin_cb;
        renderer.draw_box = ui_printf_renderer_state__box_cb;
        renderer.draw_image = ui_printf_renderer_state__image_cb;
        renderer.end = ui_printf_renderer_state__end_cb;
        return renderer;
    }
} ui_printf_renderer_state_t;

#endif
