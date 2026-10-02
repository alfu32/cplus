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

    pub int init(
        borrowed mut *self,
        uint64_t node_id,
        size_t parent_drawable,
        ui_rect_t rect,
        borrowed const ui_graphics_style_t* style,
        borrowed const char* text
    ) {
        if (self == NULL) return 1;
        memset(self, 0, sizeof(*self));
        self->node_id = node_id;
        self->parent_drawable = parent_drawable;
        self->rect = rect;
        self->style = style;
        self->text = text;
        return self->children.init();
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->children.destroy();
        memset(self, 0, sizeof(*self));
    }
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

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        if (self->kind == UI_DRAWABLE_BOX) {
            ui_drawable_box_t* box = &self->as.box;
            box->destroy();
        }
        memset(self, 0, sizeof(*self));
    }
} ui_drawable_t;

comptime typedef dynamic_list(ui_drawable_t) ui_drawable_list_t;

typedef struct ui_layout_result_t {
    ui_drawable_list_t drawables;

    pub int init(borrowed mut *self) {
        if (self == NULL) return 1;
        memset(self, 0, sizeof(*self));
        return self->drawables.init();
    }

    pub void clear(borrowed mut *self) {
        if (self == NULL) return;
        for (size_t i = 0; i < self->drawables.length; i++) {
            self->drawables.items[i].destroy();
        }
        self->drawables.clear();
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->clear();
        self->drawables.destroy();
        memset(self, 0, sizeof(*self));
    }
} ui_layout_result_t;

typedef struct ui_layout_step_t {
    int error;
    int has_anchor;
    ui_rect_t anchor;
    size_t first_drawable;

    static pub ui_layout_step_t empty(void) {
        ui_layout_step_t step;
        memset(&step, 0, sizeof(step));
        step.first_drawable = (size_t)-1;
        return step;
    }

    static pub ui_layout_step_t failed(void) {
        ui_layout_step_t step = ui_layout_step_t.empty();
        step.error = 1;
        return step;
    }
} ui_layout_step_t;

typedef struct ui_layout_t {
    ui_rect_t viewport;
    ui_layout_result_t result;

    priv ui_layout_step_t layout_node(
        borrowed mut *self,
        borrowed const ui_vnode_t* node,
        ui_rect_t parent_rect,
        borrowed const ui_rect_t* sibling_rect,
        size_t parent_drawable
    );

    priv ui_layout_step_t layout_children(
        borrowed mut *self,
        borrowed const ui_vnode_list_t* children,
        ui_rect_t parent_rect,
        borrowed const ui_rect_t* initial_sibling,
        size_t parent_drawable
    );

    static priv ui_rect_t resolve_rect(
        borrowed const ui_vnode_t* node,
        ui_rect_t viewport,
        ui_rect_t parent,
        borrowed const ui_rect_t* sibling
    ) {
        ui_rect_t base = parent;
        if (node->style.layout.position_value == UI_POSITION_FIXED) {
            base = viewport;
        } else if (node->style.layout.position_value == UI_POSITION_RELATIVE_SIBLING && sibling != NULL) {
            base = *sibling;
        }

        ui_rect_t rect;
        rect.x = base.x + node->style.layout.left_value;
        rect.y = base.y + node->style.layout.top_value;
        rect.width = node->style.layout.width_value;
        rect.height = node->style.layout.height_value;
        return rect;
    }

    priv int attach_child(
        borrowed mut *self,
        size_t parent_drawable,
        size_t child_drawable
    ) {
        if (parent_drawable == (size_t)-1) return 0;
        if (parent_drawable >= self->result.drawables.length) return 1;

        ui_drawable_t* parent = &self->result.drawables.items[parent_drawable];
        if (parent->kind != UI_DRAWABLE_BOX) return 0;

        ui_drawable_index_list_t* children = &parent->as.box.children;
        return children->add(child_drawable);
    }

    priv int emit_box(
        borrowed mut *self,
        borrowed const ui_vnode_t* node,
        ui_rect_t rect,
        size_t parent_drawable,
        borrowed mut size_t* out_index
    ) {
        ui_drawable_t drawable;
        memset(&drawable, 0, sizeof(drawable));
        drawable.kind = UI_DRAWABLE_BOX;

        ui_drawable_box_t* box = &drawable.as.box;
        borrowed const char* text = node->kind == UI_VNODE_TEXT ? node->text_value.data : NULL;
        if (box->init(node->id, parent_drawable, rect, &node->style.graphics, text) != 0) return 1;

        size_t index = self->result.drawables.length;
        if (self->result.drawables.add(drawable) != 0) {
            box->destroy();
            return 1;
        }
        if (self->attach_child(parent_drawable, index) != 0) return 1;
        if (out_index != NULL) *out_index = index;
        return 0;
    }

    priv int emit_image(
        borrowed mut *self,
        borrowed const ui_vnode_t* node,
        ui_rect_t rect,
        size_t parent_drawable,
        borrowed mut size_t* out_index
    ) {
        ui_drawable_t drawable;
        memset(&drawable, 0, sizeof(drawable));
        drawable.kind = UI_DRAWABLE_IMAGE;
        drawable.as.image.node_id = node->id;
        drawable.as.image.parent_drawable = parent_drawable;
        drawable.as.image.rect = rect;
        drawable.as.image.bitmap = node->as.image.bitmap;

        size_t index = self->result.drawables.length;
        if (self->result.drawables.add(drawable) != 0) return 1;
        if (self->attach_child(parent_drawable, index) != 0) return 1;
        if (out_index != NULL) *out_index = index;
        return 0;
    }

    priv ui_layout_step_t layout_children(
        borrowed mut *self,
        borrowed const ui_vnode_list_t* children,
        ui_rect_t parent_rect,
        borrowed const ui_rect_t* initial_sibling,
        size_t parent_drawable
    ) {
        ui_layout_step_t result = ui_layout_step_t.empty();
        if (children == NULL) return result;

        ui_rect_t previous;
        int has_previous = initial_sibling != NULL;
        if (initial_sibling != NULL) previous = *initial_sibling;

        for (size_t i = 0; i < children->length; i++) {
            borrowed const ui_rect_t* sibling = has_previous ? &previous : NULL;
            ui_layout_step_t child = self->layout_node(
                children->items[i],
                parent_rect,
                sibling,
                parent_drawable
            );
            if (child.error != 0) return child;

            if (result.first_drawable == (size_t)-1 && child.first_drawable != (size_t)-1) {
                result.first_drawable = child.first_drawable;
            }
            if (child.has_anchor) {
                previous = child.anchor;
                has_previous = 1;
                result.anchor = child.anchor;
                result.has_anchor = 1;
            }
        }
        return result;
    }

    priv ui_layout_step_t layout_node(
        borrowed mut *self,
        borrowed const ui_vnode_t* node,
        ui_rect_t parent_rect,
        borrowed const ui_rect_t* sibling_rect,
        size_t parent_drawable
    ) {
        ui_layout_step_t result = ui_layout_step_t.empty();
        if (node == NULL) return result;

        if (node->kind == UI_VNODE_COMPONENT) {
            if (node->as.component.rendered == NULL) return result;
            return self->layout_node(
                node->as.component.rendered,
                parent_rect,
                sibling_rect,
                parent_drawable
            );
        }

        if (node->kind == UI_VNODE_FRAGMENT || node->kind == UI_VNODE_CONTEXT_PROVIDER) {
            return self->layout_children(
                &node->children,
                parent_rect,
                sibling_rect,
                parent_drawable
            );
        }

        ui_rect_t rect = ui_layout_t.resolve_rect(node, self->viewport, parent_rect, sibling_rect);
        size_t index = (size_t)-1;

        if (node->kind == UI_VNODE_IMAGE) {
            if (self->emit_image(node, rect, parent_drawable, &index) != 0) return ui_layout_step_t.failed();
        } else {
            if (self->emit_box(node, rect, parent_drawable, &index) != 0) return ui_layout_step_t.failed();
        }

        result.has_anchor = 1;
        result.anchor = rect;
        result.first_drawable = index;

        if (node->kind != UI_VNODE_IMAGE && node->children.length > 0) {
            ui_layout_step_t children = self->layout_children(
                &node->children,
                rect,
                NULL,
                index
            );
            if (children.error != 0) return children;
        }

        return result;
    }

    pub int init(borrowed mut *self) {
        if (self == NULL) return 1;
        memset(self, 0, sizeof(*self));
        return self->result.init();
    }

    pub int run(
        borrowed mut *self,
        borrowed const ui_vnode_t* root,
        double viewport_width,
        double viewport_height
    ) {
        if (self == NULL || root == NULL) return 1;

        self->result.clear();
        self->viewport.x = 0.0;
        self->viewport.y = 0.0;
        self->viewport.width = viewport_width;
        self->viewport.height = viewport_height;

        ui_layout_step_t step = self->layout_node(
            root,
            self->viewport,
            NULL,
            (size_t)-1
        );
        return step.error;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->result.destroy();
        memset(self, 0, sizeof(*self));
    }
} ui_layout_t;

#endif
