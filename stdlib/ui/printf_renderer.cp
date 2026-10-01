#ifndef CPLUS_UI_PRINTF_RENDERER_CP
#define CPLUS_UI_PRINTF_RENDERER_CP

#include <stdio.h>
comptime import "stdlib:/ui/renderer.cp";

typedef struct ui_printf_renderer_state_t {
    borrowed FILE* stream;
} ui_printf_renderer_state_t;

static borrowed const char* ui__position_name(ui_position_t position) {
    switch (position) {
        case UI_POSITION_FIXED: return "fixed";
        case UI_POSITION_RELATIVE_SIBLING: return "relative-sibling";
        default: return "relative-parent";
    }
}

static borrowed const char* ui__safe_text(borrowed const char* text) { return text == NULL ? "" : text; }

static int ui__printf_begin(borrowed void* user, borrowed const ui_layout_result_t* layout) {
    ui_printf_renderer_state_t* state = user;
    return fprintf(state->stream, "LAYOUT drawables=%zu\n", layout->drawables.length) < 0 ? 1 : 0;
}

static int ui__printf_box(borrowed void* user, borrowed const ui_drawable_box_t* box) {
    ui_printf_renderer_state_t* state = user;
    const ui_graphics_style_t* s = box->style;
    int written = fprintf(state->stream,
        "BOX node=%llu parent=%zu x=%.2f y=%.2f w=%.2f h=%.2f background=\"%s\" color=\"%s\" border=\"%s\" font-family=\"%s\" font-size=%.2f font-weight=%d font-decoration=%u text=\"%s\" children=%zu\n",
        (unsigned long long)box->node_id,
        box->parent_drawable,
        box->rect.x, box->rect.y, box->rect.width, box->rect.height,
        s == NULL ? "" : ui__safe_text(s->background),
        s == NULL ? "" : ui__safe_text(s->color),
        s == NULL ? "" : ui__safe_text(s->border),
        s == NULL ? "" : ui__safe_text(s->font_family),
        s == NULL ? 0.0 : s->font_size,
        s == NULL ? 0 : s->font_weight,
        s == NULL ? 0u : s->font_decoration,
        ui__safe_text(box->text),
        box->children.length);
    return written < 0 ? 1 : 0;
}

static int ui__printf_image(borrowed void* user, borrowed const ui_drawable_image_t* image) {
    ui_printf_renderer_state_t* state = user;
    int written = fprintf(state->stream,
        "IMAGE node=%llu parent=%zu x=%.2f y=%.2f w=%.2f h=%.2f bitmap=%p\n",
        (unsigned long long)image->node_id,
        image->parent_drawable,
        image->rect.x, image->rect.y, image->rect.width, image->rect.height,
        image->bitmap);
    return written < 0 ? 1 : 0;
}

static int ui__printf_end(borrowed void* user, borrowed const ui_layout_result_t* layout) {
    (void)layout;
    ui_printf_renderer_state_t* state = user;
    return fflush(state->stream) == 0 ? 0 : 1;
}

pub ui_renderer_t ui_printf_renderer(borrowed mut ui_printf_renderer_state_t* state, borrowed FILE* stream) {
    ui_renderer_t renderer;
    memset(&renderer, 0, sizeof(renderer));
    if (state == NULL) return renderer;
    state->stream = stream == NULL ? stdout : stream;
    renderer.user = state;
    renderer.begin = ui__printf_begin;
    renderer.draw_box = ui__printf_box;
    renderer.draw_image = ui__printf_image;
    renderer.end = ui__printf_end;
    return renderer;
}

#endif
