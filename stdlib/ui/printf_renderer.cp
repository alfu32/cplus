#ifndef CPLUS_UI_PRINTF_RENDERER_CP
#define CPLUS_UI_PRINTF_RENDERER_CP

#include <stdio.h>

comptime import "stdlib:/ui/renderer.cp";

static int ui_printf_begin_bridge(borrowed void* instance, borrowed const ui_layout_result_t* layout);
static int ui_printf_box_bridge(borrowed void* instance, borrowed const ui_drawable_box_t* box);
static int ui_printf_image_bridge(borrowed void* instance, borrowed const ui_drawable_image_t* image);
static int ui_printf_end_bridge(borrowed void* instance, borrowed const ui_layout_result_t* layout);

typedef struct ui_printf_renderer_t {
    borrowed FILE* stream;

    static priv borrowed const char* text_or_empty(borrowed const string* value) {
        return value == NULL || value->data == NULL ? "" : value->data;
    }

    pub int init(borrowed mut *self, borrowed FILE* stream) {
        if (self == NULL) return 1;
        self->stream = stream == NULL ? stdout : stream;
        return 0;
    }

    pub int begin(borrowed mut *self, borrowed const ui_layout_result_t* layout) {
        if (self == NULL || layout == NULL || self->stream == NULL) return 1;
        return fprintf(self->stream, "LAYOUT drawables=%zu\n", layout->drawables.length) < 0 ? 1 : 0;
    }

    pub int box(borrowed mut *self, borrowed const ui_drawable_box_t* box) {
        if (self == NULL || box == NULL || self->stream == NULL) return 1;
        borrowed const ui_graphics_style_t* style = box->style;
        int written = fprintf(
            self->stream,
            "BOX node=%llu parent=%zu x=%.2f y=%.2f w=%.2f h=%.2f background=\"%s\" color=\"%s\" border=\"%s\" font-family=\"%s\" font-size=%.2f font-weight=%d font-decoration=%u text=\"%s\" children=%zu\n",
            (unsigned long long)box->node_id,
            box->parent_drawable,
            box->rect.x,
            box->rect.y,
            box->rect.width,
            box->rect.height,
            style == NULL ? "" : ui_printf_renderer_t.text_or_empty(&style->background_value),
            style == NULL ? "" : ui_printf_renderer_t.text_or_empty(&style->color_value),
            style == NULL ? "" : ui_printf_renderer_t.text_or_empty(&style->border_value),
            style == NULL ? "" : ui_printf_renderer_t.text_or_empty(&style->font_family_value),
            style == NULL ? 0.0 : style->font_size_value,
            style == NULL ? 0 : style->font_weight_value,
            style == NULL ? 0u : style->font_decoration_value,
            box->text == NULL ? "" : box->text,
            box->children.length
        );
        return written < 0 ? 1 : 0;
    }

    pub int image(borrowed mut *self, borrowed const ui_drawable_image_t* image) {
        if (self == NULL || image == NULL || self->stream == NULL) return 1;
        int written = fprintf(
            self->stream,
            "IMAGE node=%llu parent=%zu x=%.2f y=%.2f w=%.2f h=%.2f bitmap=%p\n",
            (unsigned long long)image->node_id,
            image->parent_drawable,
            image->rect.x,
            image->rect.y,
            image->rect.width,
            image->rect.height,
            image->bitmap
        );
        return written < 0 ? 1 : 0;
    }

    pub int end(borrowed mut *self, borrowed const ui_layout_result_t* layout) {
        (void)layout;
        if (self == NULL || self->stream == NULL) return 1;
        return fflush(self->stream) == 0 ? 0 : 1;
    }

    pub ui_renderer_t interface(borrowed mut *self) {
        ui_renderer_t renderer;
        memset(&renderer, 0, sizeof(renderer));
        if (self == NULL) return renderer;
        renderer.init(
            self,
            ui_printf_begin_bridge,
            ui_printf_box_bridge,
            ui_printf_image_bridge,
            ui_printf_end_bridge
        );
        return renderer;
    }
} ui_printf_renderer_t;

static int ui_printf_begin_bridge(borrowed void* instance, borrowed const ui_layout_result_t* layout) {
    ui_printf_renderer_t* renderer = (ui_printf_renderer_t*)instance;
    return renderer->begin(layout);
}

static int ui_printf_box_bridge(borrowed void* instance, borrowed const ui_drawable_box_t* box) {
    ui_printf_renderer_t* renderer = (ui_printf_renderer_t*)instance;
    return renderer->box(box);
}

static int ui_printf_image_bridge(borrowed void* instance, borrowed const ui_drawable_image_t* image) {
    ui_printf_renderer_t* renderer = (ui_printf_renderer_t*)instance;
    return renderer->image(image);
}

static int ui_printf_end_bridge(borrowed void* instance, borrowed const ui_layout_result_t* layout) {
    ui_printf_renderer_t* renderer = (ui_printf_renderer_t*)instance;
    return renderer->end(layout);
}

#endif
