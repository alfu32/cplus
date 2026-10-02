#ifndef CPLUS_UI_VDOM_CP
#define CPLUS_UI_VDOM_CP

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

comptime import "stdlib:/containers/dynamic_list.cp";
comptime import "stdlib:/strings/string.cp";

typedef struct ui_runtime_t ui_runtime_t;
typedef struct ui_render_context_t ui_render_context_t;
typedef struct ui_component_instance_t ui_component_instance_t;
typedef struct ui_context_t ui_context_t;
typedef struct ui_vnode_t ui_vnode_t;

typedef ui_vnode_t* ui_vnode_ref_t;
comptime typedef dynamic_list(ui_vnode_ref_t) ui_vnode_list_t;

typedef enum ui_event_type_t {
    UI_EVENT_NONE = 0,
    UI_EVENT_KEY_DOWN,
    UI_EVENT_KEY_UP,
    UI_EVENT_TEXT,
    UI_EVENT_MOUSE_MOVE,
    UI_EVENT_MOUSE_DOWN,
    UI_EVENT_MOUSE_UP,
    UI_EVENT_MOUSE_WHEEL,
    UI_EVENT_FOCUS,
    UI_EVENT_BLUR,
    UI_EVENT_CUSTOM,
    UI_EVENT_QUIT
} ui_event_type_t;

typedef struct ui_event_t {
    ui_event_type_t type;
    uint64_t target_id;
    int key;
    unsigned int codepoint;
    int button;
    double x;
    double y;
    double dx;
    double dy;
    double wheel_x;
    double wheel_y;
    borrowed const char* custom_type;
    borrowed void* payload;
} ui_event_t;

typedef int (*ui_event_handler_fn)(borrowed const ui_event_t* event, borrowed void* user);

typedef struct ui_event_handler_t {
    ui_event_type_t type;
    ui_event_handler_fn callback;
    borrowed void* user;
} ui_event_handler_t;

typedef enum ui_value_kind_t {
    UI_VALUE_NONE = 0,
    UI_VALUE_BOOL,
    UI_VALUE_INT,
    UI_VALUE_DOUBLE,
    UI_VALUE_STRING,
    UI_VALUE_POINTER,
    UI_VALUE_HANDLER
} ui_value_kind_t;

typedef struct ui_value_t {
    ui_value_kind_t kind;
    union {
        int boolean;
        int64_t integer;
        double real;
        string text;
        borrowed void* pointer;
        ui_event_handler_t handler;
    } as;

    static pub ui_value_t none(void) {
        ui_value_t value;
        memset(&value, 0, sizeof(value));
        value.kind = UI_VALUE_NONE;
        return value;
    }

    static pub ui_value_t boolean(int value) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_BOOL;
        result.as.boolean = value != 0;
        return result;
    }

    static pub ui_value_t integer(int64_t value) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_INT;
        result.as.integer = value;
        return result;
    }

    static pub ui_value_t real(double value) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_DOUBLE;
        result.as.real = value;
        return result;
    }

    static pub ui_value_t pointer(borrowed void* value) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_POINTER;
        result.as.pointer = value;
        return result;
    }

    static pub ui_value_t handler(ui_event_type_t type, ui_event_handler_fn callback, borrowed void* user) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_HANDLER;
        result.as.handler.type = type;
        result.as.handler.callback = callback;
        result.as.handler.user = user;
        return result;
    }

    static pub ui_value_t string_value(borrowed const char* value) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_STRING;
        string* text = &result.as.text;
        text->init();
        if (text->assign(value == NULL ? "" : value) != 0) {
            text->destroy();
            result.kind = UI_VALUE_NONE;
        }
        return result;
    }

    pub int equals(borrowed const *self, borrowed const ui_value_t* other) {
        if (self == NULL || other == NULL || self->kind != other->kind) return 0;
        switch (self->kind) {
            case UI_VALUE_NONE: return 1;
            case UI_VALUE_BOOL: return self->as.boolean == other->as.boolean;
            case UI_VALUE_INT: return self->as.integer == other->as.integer;
            case UI_VALUE_DOUBLE: return self->as.real == other->as.real;
            case UI_VALUE_STRING:
                return strcmp(self->as.text.data == NULL ? "" : self->as.text.data,
                              other->as.text.data == NULL ? "" : other->as.text.data) == 0;
            case UI_VALUE_POINTER: return self->as.pointer == other->as.pointer;
            case UI_VALUE_HANDLER:
                return self->as.handler.type == other->as.handler.type &&
                       self->as.handler.callback == other->as.handler.callback &&
                       self->as.handler.user == other->as.handler.user;
            default: return 0;
        }
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        if (self->kind == UI_VALUE_STRING) {
            string* text = &self->as.text;
            text->destroy();
        }
        *self = ui_value_t.none();
    }
} ui_value_t;

typedef struct ui_attr_t {
    string name;
    ui_value_t value;

    pub int init(borrowed mut *self, borrowed const char* name, ui_value_t value) {
        if (self == NULL || name == NULL) {
            value.destroy();
            return 1;
        }
        memset(self, 0, sizeof(*self));
        self->name.init();
        if (self->name.assign(name) != 0) {
            self->name.destroy();
            value.destroy();
            return 1;
        }
        self->value = value;
        return 0;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->name.destroy();
        self->value.destroy();
        memset(self, 0, sizeof(*self));
    }
} ui_attr_t;

comptime typedef dynamic_list(ui_attr_t) ui_attr_list_t;

typedef enum ui_position_t {
    UI_POSITION_RELATIVE_PARENT = 0,
    UI_POSITION_FIXED,
    UI_POSITION_RELATIVE_SIBLING
} ui_position_t;

typedef enum ui_font_decoration_t {
    UI_FONT_DECORATION_NONE = 0,
    UI_FONT_DECORATION_UNDERLINE = 1,
    UI_FONT_DECORATION_LINE_THROUGH = 2,
    UI_FONT_DECORATION_OVERLINE = 4
} ui_font_decoration_t;

typedef struct ui_layout_style_t {
    ui_position_t position_value;
    double top_value;
    double left_value;
    double width_value;
    double height_value;

    pub void init(borrowed mut *self) {
        if (self == NULL) return;
        memset(self, 0, sizeof(*self));
        self->position_value = UI_POSITION_RELATIVE_PARENT;
    }

    pub borrowed mut ui_layout_style_t* position(borrowed mut *self, ui_position_t value) {
        if (self != NULL) self->position_value = value;
        return self;
    }

    pub borrowed mut ui_layout_style_t* top(borrowed mut *self, double value) {
        if (self != NULL) self->top_value = value;
        return self;
    }

    pub borrowed mut ui_layout_style_t* left(borrowed mut *self, double value) {
        if (self != NULL) self->left_value = value;
        return self;
    }

    pub borrowed mut ui_layout_style_t* width(borrowed mut *self, double value) {
        if (self != NULL) self->width_value = value;
        return self;
    }

    pub borrowed mut ui_layout_style_t* height(borrowed mut *self, double value) {
        if (self != NULL) self->height_value = value;
        return self;
    }

    pub borrowed mut ui_layout_style_t* set(
        borrowed mut *self,
        ui_position_t position,
        double top,
        double left,
        double width,
        double height
    ) {
        if (self == NULL) return NULL;
        self->position_value = position;
        self->top_value = top;
        self->left_value = left;
        self->width_value = width;
        self->height_value = height;
        return self;
    }
} ui_layout_style_t;

typedef struct ui_graphics_style_t {
    string background_value;
    string color_value;
    string border_value;
    string font_family_value;
    double font_size_value;
    int font_weight_value;
    unsigned int font_decoration_value;

    pub int init(borrowed mut *self) {
        if (self == NULL) return 1;
        memset(self, 0, sizeof(*self));
        self->background_value.init();
        self->color_value.init();
        self->border_value.init();
        self->font_family_value.init();
        return 0;
    }

    pub int background(borrowed mut *self, borrowed const char* value) {
        return self == NULL ? 1 : self->background_value.assign(value == NULL ? "" : value);
    }

    pub int color(borrowed mut *self, borrowed const char* value) {
        return self == NULL ? 1 : self->color_value.assign(value == NULL ? "" : value);
    }

    pub int border(borrowed mut *self, borrowed const char* value) {
        return self == NULL ? 1 : self->border_value.assign(value == NULL ? "" : value);
    }

    pub int font_family(borrowed mut *self, borrowed const char* value) {
        return self == NULL ? 1 : self->font_family_value.assign(value == NULL ? "" : value);
    }

    pub borrowed mut ui_graphics_style_t* font_size(borrowed mut *self, double value) {
        if (self != NULL) self->font_size_value = value;
        return self;
    }

    pub borrowed mut ui_graphics_style_t* font_weight(borrowed mut *self, int value) {
        if (self != NULL) self->font_weight_value = value;
        return self;
    }

    pub borrowed mut ui_graphics_style_t* font_decoration(borrowed mut *self, unsigned int value) {
        if (self != NULL) self->font_decoration_value = value;
        return self;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->background_value.destroy();
        self->color_value.destroy();
        self->border_value.destroy();
        self->font_family_value.destroy();
        memset(self, 0, sizeof(*self));
    }
} ui_graphics_style_t;

typedef struct ui_style_t {
    ui_layout_style_t layout;
    ui_graphics_style_t graphics;

    pub int init(borrowed mut *self) {
        if (self == NULL) return 1;
        memset(self, 0, sizeof(*self));
        self->layout.init();
        return self->graphics.init();
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->graphics.destroy();
        memset(self, 0, sizeof(*self));
    }
} ui_style_t;

typedef enum ui_vnode_kind_t {
    UI_VNODE_ELEMENT = 1,
    UI_VNODE_TEXT,
    UI_VNODE_FRAGMENT,
    UI_VNODE_COMPONENT,
    UI_VNODE_CONTEXT_PROVIDER,
    UI_VNODE_IMAGE
} ui_vnode_kind_t;

typedef ui_vnode_t* (*ui_component_render_fn)(borrowed mut ui_render_context_t* context, borrowed const void* props);

typedef struct ui_vnode_t {
    ui_vnode_kind_t kind;
    uint64_t id;
    string key_value;
    string tag_value;
    string text_value;
    ui_style_t style;
    ui_attr_list_t attrs;
    ui_vnode_list_t children;
    union {
        struct {
            ui_component_render_fn render;
            owned void* props;
            size_t props_size;
            owned ui_component_instance_t* instance;
            owned ui_vnode_t* rendered;
        } component;
        struct {
            borrowed ui_context_t* context;
            owned void* value;
            size_t value_size;
        } provider;
        struct {
            borrowed const void* bitmap;
        } image;
    } as;

    pub void destroy(owned *self);

    static priv owned void* copy_bytes(borrowed const void* source, size_t size) {
        if (source == NULL || size == 0) return NULL;
        void* copy = malloc(size);
        if (copy != NULL) memcpy(copy, source, size);
        return copy;
    }

    static priv owned ui_vnode_t* create(ui_vnode_kind_t kind) {
        ui_vnode_t* node = calloc(1, sizeof(ui_vnode_t));
        if (node == NULL) return NULL;

        node->kind = kind;
        node->key_value.init();
        node->tag_value.init();
        node->text_value.init();

        if (node->style.init() != 0 || node->attrs.init() != 0 || node->children.init() != 0) {
            node->destroy();
            return NULL;
        }
        return node;
    }

    static pub owned ui_vnode_t* element(borrowed const char* tag) {
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_ELEMENT);
        if (node == NULL) return NULL;
        if (node->tag_value.assign(tag == NULL ? "" : tag) != 0) {
            node->destroy();
            return NULL;
        }
        return node;
    }

    static pub owned ui_vnode_t* text(borrowed const char* text) {
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_TEXT);
        if (node == NULL) return NULL;
        if (node->text_value.assign(text == NULL ? "" : text) != 0) {
            node->destroy();
            return NULL;
        }
        return node;
    }

    static pub owned ui_vnode_t* fragment(void) {
        return ui_vnode_t.create(UI_VNODE_FRAGMENT);
    }

    static pub owned ui_vnode_t* image(borrowed const void* bitmap) {
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_IMAGE);
        if (node != NULL) node->as.image.bitmap = bitmap;
        return node;
    }

    static pub owned ui_vnode_t* component(
        ui_component_render_fn render,
        borrowed const void* props,
        size_t props_size
    ) {
        if (render == NULL) return NULL;
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_COMPONENT);
        if (node == NULL) return NULL;
        node->as.component.render = render;
        node->as.component.props_size = props_size;
        if (props_size > 0) {
            node->as.component.props = ui_vnode_t.copy_bytes(props, props_size);
            if (node->as.component.props == NULL) {
                node->destroy();
                return NULL;
            }
        }
        return node;
    }

    static pub owned ui_vnode_t* provider(
        borrowed ui_context_t* context,
        borrowed const void* value,
        size_t value_size
    ) {
        if (context == NULL || value == NULL || value_size == 0) return NULL;
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_CONTEXT_PROVIDER);
        if (node == NULL) return NULL;
        node->as.provider.context = context;
        node->as.provider.value_size = value_size;
        node->as.provider.value = ui_vnode_t.copy_bytes(value, value_size);
        if (node->as.provider.value == NULL) {
            node->destroy();
            return NULL;
        }
        return node;
    }

    pub int key(borrowed mut *self, borrowed const char* value) {
        if (self == NULL) return 1;
        return self->key_value.assign(value == NULL ? "" : value);
    }

    pub borrowed const char* key_text(borrowed const *self) {
        if (self == NULL || self->key_value.data == NULL) return "";
        return self->key_value.data;
    }

    pub borrowed const char* tag(borrowed const *self) {
        if (self == NULL || self->tag_value.data == NULL) return "";
        return self->tag_value.data;
    }

    pub borrowed const char* text_content(borrowed const *self) {
        if (self == NULL || self->text_value.data == NULL) return "";
        return self->text_value.data;
    }

    pub int attr(borrowed mut *self, borrowed const char* name, ui_value_t value) {
        if (self == NULL || name == NULL) {
            value.destroy();
            return 1;
        }

        for (size_t i = 0; i < self->attrs.length; i++) {
            ui_attr_t* existing = &self->attrs.items[i];
            if (strcmp(existing->name.data == NULL ? "" : existing->name.data, name) == 0) {
                existing->value.destroy();
                existing->value = value;
                return 0;
            }
        }

        ui_attr_t attribute;
        if (attribute.init(name, value) != 0) return 1;
        if (self->attrs.add(attribute) != 0) {
            attribute.destroy();
            return 1;
        }
        return 0;
    }

    pub borrowed const ui_attr_t* find_attr(borrowed const *self, borrowed const char* name) {
        if (self == NULL || name == NULL) return NULL;
        for (size_t i = 0; i < self->attrs.length; i++) {
            borrowed const ui_attr_t* attribute = &self->attrs.items[i];
            if (strcmp(attribute->name.data == NULL ? "" : attribute->name.data, name) == 0) return attribute;
        }
        return NULL;
    }

    pub int child(borrowed mut *self, owned ui_vnode_t* child) {
        if (self == NULL || child == NULL || child == self) return 1;
        return self->children.add(child);
    }

    pub void destroy(owned *self) {
        if (self == NULL) return;

        for (size_t i = 0; i < self->attrs.length; i++) self->attrs.items[i].destroy();
        for (size_t i = 0; i < self->children.length; i++) {
            ui_vnode_t* child = self->children.items[i];
            if (child != NULL) child->destroy();
        }

        self->attrs.destroy();
        self->children.destroy();
        self->key_value.destroy();
        self->tag_value.destroy();
        self->text_value.destroy();
        self->style.destroy();

        if (self->kind == UI_VNODE_COMPONENT) {
            if (self->as.component.rendered != NULL) {
                ui_vnode_t* rendered = self->as.component.rendered;
                rendered->destroy();
            }
            free(self->as.component.props);
        } else if (self->kind == UI_VNODE_CONTEXT_PROVIDER) {
            free(self->as.provider.value);
        }

        free(self);
    }
} ui_vnode_t;

#endif
