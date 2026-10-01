#ifndef CPLUS_UI_LAYOUT_CP
#define CPLUS_UI_LAYOUT_CP

#include <stddef.h>
#include <stdint.h>
#include <string.h>

comptime import "stdlib:/ui/vdom.cp";

typedef struct ui_rect_t {
    double x;
    double y;
    double width;
    double height;
} ui_rect_t;

comptime typedef dynamic_list(size_t) ui_drawable_index_list_t;

typedef enum ui_drawable_kind_t {
    UI_DRAWABLE_BOX = 1,
    UI_DRAWABLE_IMAGE
} ui_drawable_kind_t;

typedef struct ui_drawable_box_t {
    uint64_t node_id;
    size_t parent_drawable;
    ui_rect_t rect;
    borrowed const ui_graphics_style_t* style;
    borrowed const char* text;
    ui_drawable_index_list_t children;
} ui_drawable_box_t;

typedef struct ui_drawable_image_t {
    uint64_t node_id;
    size_t parent_drawable;
    ui_rect_t rect;
    borrowed const void* bitmap;
} ui_drawable_image_t;

typedef struct ui_drawable_t {
    ui_drawable_kind_t kind;
    union {
        ui_drawable_box_t box;
        ui_drawable_image_t image;
    } as;
} ui_drawable_t;
comptime typedef dynamic_list(ui_drawable_t) ui_drawable_list_t;

typedef struct ui_layout_result_t ui_layout_result_t;

static int ui__layout_result_init_impl(borrowed mut ui_layout_result_t* result);
static void ui__layout_result_destroy_impl(borrowed mut ui_layout_result_t* result);
static int ui__layout_run_impl(borrowed mut ui_layout_result_t* result, borrowed const ui_vnode_t* root, double viewport_width, double viewport_height);

typedef struct ui_layout_result_t {
    ui_drawable_list_t drawables;

    pub int init(borrowed mut *self) { return ui__layout_result_init_impl(self); }
    pub void destroy(borrowed mut *self) { ui__layout_result_destroy_impl(self); }
    pub int run(borrowed mut *self, borrowed const ui_vnode_t* root, double viewport_width, double viewport_height) {
        return ui__layout_run_impl(self, root, viewport_width, viewport_height);
    }
} ui_layout_result_t;

static ui_rect_t ui__layout_resolve_rect(borrowed const ui_vnode_t* node, ui_rect_t viewport, ui_rect_t parent, borrowed const ui_rect_t* sibling) {
    ui_rect_t base = parent;
    if (node->style.layout.position == UI_POSITION_FIXED) base = viewport;
    else if (node->style.layout.position == UI_POSITION_RELATIVE_SIBLING && sibling != NULL) base = *sibling;
    ui_rect_t result;
    result.x = base.x + node->style.layout.left;
    result.y = base.y + node->style.layout.top;
    result.width = node->style.layout.width;
    result.height = node->style.layout.height;
    return result;
}

static int ui__layout_result_init_impl(borrowed mut ui_layout_result_t* result) {
    if (result == NULL) return 1;
    memset(result, 0, sizeof(*result));
    return result->drawables.init();
}

static void ui__layout_result_destroy_impl(borrowed mut ui_layout_result_t* result) {
    if (result == NULL) return;
    for (size_t i = 0; i < result->drawables.length; i++) {
        ui_drawable_t* drawable = &result->drawables.items[i];
        if (drawable->kind == UI_DRAWABLE_BOX) {
            ui_drawable_index_list_t* children = &drawable->as.box.children;
            children->destroy();
        }
    }
    result->drawables.destroy();
    memset(result, 0, sizeof(*result));
}

static int ui__layout_attach_child(borrowed mut ui_layout_result_t* result, size_t parent_drawable, size_t child_drawable) {
    if (parent_drawable == (size_t)-1) return 0;
    if (parent_drawable >= result->drawables.length) return 1;
    ui_drawable_t* parent = &result->drawables.items[parent_drawable];
    if (parent->kind != UI_DRAWABLE_BOX) return 0;
    ui_drawable_index_list_t* children = &parent->as.box.children;
    return children->add(child_drawable);
}

static int ui__layout_node(
    borrowed const ui_vnode_t* node,
    ui_rect_t viewport,
    ui_rect_t parent_rect,
    borrowed const ui_rect_t* sibling_rect,
    size_t parent_drawable,
    borrowed mut ui_layout_result_t* result,
    borrowed mut ui_rect_t* out_anchor,
    borrowed mut int* out_has_anchor,
    borrowed mut size_t* out_first_drawable
);

static int ui__layout_transparent_children(
    borrowed const ui_vnode_list_t* children,
    ui_rect_t viewport,
    ui_rect_t parent_rect,
    borrowed const ui_rect_t* initial_sibling,
    size_t parent_drawable,
    borrowed mut ui_layout_result_t* result,
    borrowed mut ui_rect_t* out_anchor,
    borrowed mut int* out_has_anchor,
    borrowed mut size_t* out_first_drawable
) {
    ui_rect_t previous;
    int has_previous = initial_sibling != NULL;
    if (initial_sibling != NULL) previous = *initial_sibling;
    *out_has_anchor = 0;
    *out_first_drawable = (size_t)-1;
    for (size_t i = 0; i < children->length; i++) {
        ui_rect_t child_anchor;
        int child_has_anchor = 0;
        size_t child_first = (size_t)-1;
        if (ui__layout_node(children->items[i], viewport, parent_rect, has_previous ? &previous : NULL, parent_drawable, result, &child_anchor, &child_has_anchor, &child_first) != 0) return 1;
        if (*out_first_drawable == (size_t)-1 && child_first != (size_t)-1) *out_first_drawable = child_first;
        if (child_has_anchor) { previous = child_anchor; has_previous = 1; *out_anchor = child_anchor; *out_has_anchor = 1; }
    }
    return 0;
}

static int ui__layout_node(
    borrowed const ui_vnode_t* node,
    ui_rect_t viewport,
    ui_rect_t parent_rect,
    borrowed const ui_rect_t* sibling_rect,
    size_t parent_drawable,
    borrowed mut ui_layout_result_t* result,
    borrowed mut ui_rect_t* out_anchor,
    borrowed mut int* out_has_anchor,
    borrowed mut size_t* out_first_drawable
) {
    *out_has_anchor = 0;
    *out_first_drawable = (size_t)-1;
    if (node == NULL) return 0;

    if (node->kind == UI_VNODE_COMPONENT) {
        if (node->as.component.rendered == NULL) return 0;
        return ui__layout_node(node->as.component.rendered, viewport, parent_rect, sibling_rect, parent_drawable, result, out_anchor, out_has_anchor, out_first_drawable);
    }
    if (node->kind == UI_VNODE_FRAGMENT || node->kind == UI_VNODE_CONTEXT_PROVIDER) {
        return ui__layout_transparent_children(&node->children, viewport, parent_rect, sibling_rect, parent_drawable, result, out_anchor, out_has_anchor, out_first_drawable);
    }

    ui_rect_t rect = ui__layout_resolve_rect(node, viewport, parent_rect, sibling_rect);
    ui_drawable_t drawable;
    memset(&drawable, 0, sizeof(drawable));
    size_t index = result->drawables.length;

    if (node->kind == UI_VNODE_IMAGE) {
        drawable.kind = UI_DRAWABLE_IMAGE;
        drawable.as.image.node_id = node->id;
        drawable.as.image.parent_drawable = parent_drawable;
        drawable.as.image.rect = rect;
        drawable.as.image.bitmap = node->as.image.bitmap;
    } else {
        drawable.kind = UI_DRAWABLE_BOX;
        drawable.as.box.node_id = node->id;
        drawable.as.box.parent_drawable = parent_drawable;
        drawable.as.box.rect = rect;
        drawable.as.box.style = &node->style.graphics;
        drawable.as.box.text = node->kind == UI_VNODE_TEXT ? node->text : NULL;
        ui_drawable_index_list_t* children = &drawable.as.box.children;
        if (children->init() != 0) return 1;
    }

    if (result->drawables.add(drawable) != 0) {
        if (drawable.kind == UI_DRAWABLE_BOX) {
            ui_drawable_index_list_t* children = &drawable.as.box.children;
            children->destroy();
        }
        return 1;
    }
    if (ui__layout_attach_child(result, parent_drawable, index) != 0) return 1;

    *out_anchor = rect;
    *out_has_anchor = 1;
    *out_first_drawable = index;

    if (drawable.kind == UI_DRAWABLE_BOX && node->children.length > 0) {
        ui_rect_t ignored_anchor;
        int ignored_has_anchor = 0;
        size_t ignored_first = (size_t)-1;
        if (ui__layout_transparent_children(&node->children, viewport, rect, NULL, index, result, &ignored_anchor, &ignored_has_anchor, &ignored_first) != 0) return 1;
    }
    return 0;
}

static int ui__layout_run_impl(borrowed mut ui_layout_result_t* result, borrowed const ui_vnode_t* root, double viewport_width, double viewport_height) {
    if (root == NULL || result == NULL) return 1;
    if (result->drawables.items == NULL && result->drawables.capacity == 0 && result->drawables.length == 0) {
        if (result->init() != 0) return 1;
    } else {
        for (size_t i = 0; i < result->drawables.length; i++) {
            if (result->drawables.items[i].kind == UI_DRAWABLE_BOX) {
                ui_drawable_index_list_t* children = &result->drawables.items[i].as.box.children;
                children->destroy();
            }
        }
        result->drawables.length = 0;
    }
    ui_rect_t viewport = {0.0, 0.0, viewport_width, viewport_height};
    ui_rect_t anchor;
    int has_anchor = 0;
    size_t first = (size_t)-1;
    return ui__layout_node(root, viewport, viewport, NULL, (size_t)-1, result, &anchor, &has_anchor, &first);
}

#endif
