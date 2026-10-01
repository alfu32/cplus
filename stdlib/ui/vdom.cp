#ifndef CPLUS_UI_VDOM_CP
#define CPLUS_UI_VDOM_CP

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

comptime import "stdlib:/containers/dynamic_list.cp";

typedef struct ui_runtime_t ui_runtime_t;
typedef struct ui_render_context_t ui_render_context_t;
typedef struct ui_vnode_t ui_vnode_t;
typedef struct ui_component_instance_t ui_component_instance_t;
typedef struct ui_context_t ui_context_t;

typedef ui_vnode_t* ui_vnode_ref_t;
comptime typedef dynamic_list(ui_vnode_ref_t) ui_vnode_list_t;

typedef enum ui_event_type_t {
    UI_EVENT_NONE = 0, UI_EVENT_KEY_DOWN, UI_EVENT_KEY_UP, UI_EVENT_TEXT,
    UI_EVENT_MOUSE_MOVE, UI_EVENT_MOUSE_DOWN, UI_EVENT_MOUSE_UP, UI_EVENT_MOUSE_WHEEL,
    UI_EVENT_FOCUS, UI_EVENT_BLUR, UI_EVENT_CUSTOM, UI_EVENT_QUIT
} ui_event_type_t;

typedef struct ui_event_t {
    ui_event_type_t type;
    uint64_t target_id;
    int key;
    unsigned int codepoint;
    int button;
    double x, y, dx, dy, wheel_x, wheel_y;
    const char* custom_type;
    borrowed void* payload;
} ui_event_t;

typedef int (*ui_event_fn)(borrowed const ui_event_t* event, borrowed void* user);
typedef struct ui_event_handler_t {
    ui_event_type_t type;
    ui_event_fn callback;
    borrowed void* user;
} ui_event_handler_t;

typedef enum ui_value_kind_t {
    UI_VALUE_NONE = 0, UI_VALUE_BOOL, UI_VALUE_INT, UI_VALUE_DOUBLE,
    UI_VALUE_STRING, UI_VALUE_POINTER, UI_VALUE_HANDLER
} ui_value_kind_t;

typedef struct ui_value_t {
    ui_value_kind_t kind;
    union {
        int boolean;
        int64_t integer;
        double real;
        char* string;
        borrowed void* pointer;
        ui_event_handler_t handler;
    } as;

    static pub ui_value_t none(void) {
        ui_value_t value;
        memset(&value, 0, sizeof(value));
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

    static pub ui_value_t handler(ui_event_type_t type, ui_event_fn callback, borrowed void* user) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_HANDLER;
        result.as.handler.type = type;
        result.as.handler.callback = callback;
        result.as.handler.user = user;
        return result;
    }

    static pub ui_value_t string(borrowed const char* value) {
        ui_value_t result = ui_value_t.none();
        result.kind = UI_VALUE_STRING;
        const char* source = value == NULL ? "" : value;
        size_t n = strlen(source) + 1;
        result.as.string = malloc(n);
        if (result.as.string != NULL) memcpy(result.as.string, source, n);
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
                if (self->as.string == NULL || other->as.string == NULL) return self->as.string == other->as.string;
                return strcmp(self->as.string, other->as.string) == 0;
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
        if (self->kind == UI_VALUE_STRING) free(self->as.string);
        *self = ui_value_t.none();
    }
} ui_value_t;

typedef struct ui_attr_t {
    char* name;
    ui_value_t value;

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        free(self->name);
        self->name = NULL;
        self->value.destroy();
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
    ui_position_t position;
    double top, left, width, height;
} ui_layout_style_t;

typedef struct ui_graphics_style_t {
    char* background;
    char* color;
    char* border;
    char* font_family;
    double font_size;
    int font_weight;
    unsigned int font_decoration;

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        free(self->background); free(self->color); free(self->border); free(self->font_family);
        memset(self, 0, sizeof(*self));
    }
} ui_graphics_style_t;

typedef struct ui_style_t {
    ui_layout_style_t layout;
    ui_graphics_style_t graphics;

    pub void init(borrowed mut *self) {
        if (self == NULL) return;
        memset(self, 0, sizeof(*self));
        self->layout.position = UI_POSITION_RELATIVE_PARENT;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->graphics.destroy();
        self->init();
    }
} ui_style_t;

typedef enum ui_vnode_kind_t {
    UI_VNODE_ELEMENT = 1, UI_VNODE_TEXT, UI_VNODE_FRAGMENT,
    UI_VNODE_COMPONENT, UI_VNODE_CONTEXT_PROVIDER, UI_VNODE_IMAGE
} ui_vnode_kind_t;

typedef ui_vnode_t* (*ui_component_fn)(borrowed mut ui_render_context_t* context, borrowed const void* props);
typedef void (*ui_component_instance_destroy_fn)(borrowed mut ui_component_instance_t* instance);

typedef struct ui_vnode_t {
    ui_vnode_kind_t kind;
    uint64_t id;
    char* key;
    char* tag;
    char* text;
    ui_style_t style;
    ui_attr_list_t attrs;
    ui_vnode_list_t children;
    union {
        struct {
            ui_component_fn render;
            owned void* props;
            size_t props_size;
            owned ui_component_instance_t* instance;
            ui_component_instance_destroy_fn instance_destroy;
            owned ui_vnode_t* rendered;
        } component;
        struct {
            borrowed ui_context_t* context;
            owned void* value;
            size_t value_size;
        } provider;
        struct { borrowed const void* bitmap; } image;
    } as;

    static priv char* copy_string(borrowed const char* text) {
        if (text == NULL) return NULL;
        size_t n = strlen(text) + 1;
        char* copy = malloc(n);
        if (copy != NULL) memcpy(copy, text, n);
        return copy;
    }

    static priv void* copy_memory(borrowed const void* value, size_t size) {
        if (size == 0 || value == NULL) return NULL;
        void* copy = malloc(size);
        if (copy != NULL) memcpy(copy, value, size);
        return copy;
    }

    static priv int replace_string(borrowed mut char** destination, borrowed const char* value) {
        char* copy = value == NULL ? NULL : ui_vnode_t.copy_string(value);
        if (value != NULL && copy == NULL) return 1;
        free(*destination);
        *destination = copy;
        return 0;
    }

    static priv void discard(owned ui_vnode_t* node) {
        if (node == NULL) return;
        node->attrs.destroy();
        node->children.destroy();
        free(node->key); free(node->tag); free(node->text);
        node->style.destroy();
        if (node->kind == UI_VNODE_COMPONENT) free(node->as.component.props);
        else if (node->kind == UI_VNODE_CONTEXT_PROVIDER) free(node->as.provider.value);
        free(node);
    }

    static priv owned ui_vnode_t* create(ui_vnode_kind_t kind) {
        ui_vnode_t* node = calloc(1, sizeof(ui_vnode_t));
        if (node == NULL) return NULL;
        node->kind = kind;
        node->style.init();
        if (node->attrs.init() != 0 || node->children.init() != 0) {
            node->attrs.destroy(); node->children.destroy(); free(node); return NULL;
        }
        return node;
    }

    static pub owned ui_vnode_t* element(borrowed const char* tag) {
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_ELEMENT);
        if (node == NULL) return NULL;
        node->tag = ui_vnode_t.copy_string(tag == NULL ? "" : tag);
        if (node->tag == NULL) { ui_vnode_t.discard(node); return NULL; }
        return node;
    }

    static pub owned ui_vnode_t* text_node(borrowed const char* text) {
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_TEXT);
        if (node == NULL) return NULL;
        node->text = ui_vnode_t.copy_string(text == NULL ? "" : text);
        if (node->text == NULL) { ui_vnode_t.discard(node); return NULL; }
        return node;
    }

    static pub owned ui_vnode_t* fragment(void) { return ui_vnode_t.create(UI_VNODE_FRAGMENT); }

    static pub owned ui_vnode_t* image(borrowed const void* bitmap) {
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_IMAGE);
        if (node != NULL) node->as.image.bitmap = bitmap;
        return node;
    }

    static pub owned ui_vnode_t* component(ui_component_fn component, borrowed const void* props, size_t props_size) {
        if (component == NULL) return NULL;
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_COMPONENT);
        if (node == NULL) return NULL;
        node->as.component.render = component;
        node->as.component.props_size = props_size;
        if (props_size > 0) {
            node->as.component.props = ui_vnode_t.copy_memory(props, props_size);
            if (node->as.component.props == NULL) { ui_vnode_t.discard(node); return NULL; }
        }
        return node;
    }

    static pub owned ui_vnode_t* provider(borrowed ui_context_t* context, borrowed const void* value, size_t value_size) {
        if (context == NULL || value == NULL || value_size == 0) return NULL;
        ui_vnode_t* node = ui_vnode_t.create(UI_VNODE_CONTEXT_PROVIDER);
        if (node == NULL) return NULL;
        node->as.provider.context = context;
        node->as.provider.value_size = value_size;
        node->as.provider.value = ui_vnode_t.copy_memory(value, value_size);
        if (node->as.provider.value == NULL) { ui_vnode_t.discard(node); return NULL; }
        return node;
    }

    pub int key_set(borrowed mut *self, borrowed const char* key) {
        if (self == NULL) return 1;
        return ui_vnode_t.replace_string(&self->key, key);
    }

    pub int attr(borrowed mut *self, borrowed const char* name, ui_value_t value) {
        if (self == NULL || name == NULL) { value.destroy(); return 1; }
        for (size_t i = 0; i < self->attrs.length; i++) {
            ui_attr_t* attr = &self->attrs.items[i];
            if (strcmp(attr->name, name) == 0) {
                attr->value.destroy();
                attr->value = value;
                return 0;
            }
        }
        ui_attr_t attr;
        attr.name = ui_vnode_t.copy_string(name);
        attr.value = value;
        if (attr.name == NULL) { attr.value.destroy(); return 1; }
        if (self->attrs.add(attr) != 0) { attr.destroy(); return 1; }
        return 0;
    }

    pub int child(borrowed mut *self, owned ui_vnode_t* child) {
        if (self == NULL || child == NULL) return 1;
        return self->children.add(child);
    }

    pub borrowed mut ui_style_t* style_mut(borrowed mut *self) { return self == NULL ? NULL : &self->style; }
    pub borrowed const ui_style_t* style_get(borrowed const *self) { return self == NULL ? NULL : &self->style; }

    pub int position(borrowed mut *self, ui_position_t value) { if (self == NULL) return 1; self->style.layout.position = value; return 0; }
    pub int top(borrowed mut *self, double value) { if (self == NULL) return 1; self->style.layout.top = value; return 0; }
    pub int left(borrowed mut *self, double value) { if (self == NULL) return 1; self->style.layout.left = value; return 0; }
    pub int width(borrowed mut *self, double value) { if (self == NULL) return 1; self->style.layout.width = value; return 0; }
    pub int height(borrowed mut *self, double value) { if (self == NULL) return 1; self->style.layout.height = value; return 0; }

    pub int layout(borrowed mut *self, ui_position_t position, double top, double left, double width, double height) {
        if (self == NULL) return 1;
        self->style.layout.position = position;
        self->style.layout.top = top;
        self->style.layout.left = left;
        self->style.layout.width = width;
        self->style.layout.height = height;
        return 0;
    }

    pub int background(borrowed mut *self, borrowed const char* value) { return self == NULL ? 1 : ui_vnode_t.replace_string(&self->style.graphics.background, value); }
    pub int color(borrowed mut *self, borrowed const char* value) { return self == NULL ? 1 : ui_vnode_t.replace_string(&self->style.graphics.color, value); }
    pub int border(borrowed mut *self, borrowed const char* value) { return self == NULL ? 1 : ui_vnode_t.replace_string(&self->style.graphics.border, value); }
    pub int font_family(borrowed mut *self, borrowed const char* value) { return self == NULL ? 1 : ui_vnode_t.replace_string(&self->style.graphics.font_family, value); }
    pub int font_size(borrowed mut *self, double value) { if (self == NULL) return 1; self->style.graphics.font_size = value; return 0; }
    pub int font_weight(borrowed mut *self, int value) { if (self == NULL) return 1; self->style.graphics.font_weight = value; return 0; }
    pub int font_decoration(borrowed mut *self, unsigned int value) { if (self == NULL) return 1; self->style.graphics.font_decoration = value; return 0; }

    pub void destroy(owned *self) {
        if (self == NULL) return;
        for (size_t i = 0; i < self->attrs.length; i++) self->attrs.items[i].destroy();
        for (size_t i = 0; i < self->children.length; i++) {
            ui_vnode_t* child_node = self->children.items[i];
            child_node->destroy();
        }
        self->attrs.destroy(); self->children.destroy();
        free(self->key); free(self->tag); free(self->text);
        self->style.destroy();
        if (self->kind == UI_VNODE_COMPONENT) {
            if (self->as.component.rendered != NULL) {
                ui_vnode_t* rendered = self->as.component.rendered;
                rendered->destroy();
            }
            if (self->as.component.instance != NULL && self->as.component.instance_destroy != NULL)
                self->as.component.instance_destroy(self->as.component.instance);
            free(self->as.component.props);
        } else if (self->kind == UI_VNODE_CONTEXT_PROVIDER) {
            free(self->as.provider.value);
        }
        free(self);
    }
} ui_vnode_t;

#define UI_ELEMENT(name, tag_literal) ui_vnode_t* name = ui_vnode__element((tag_literal))
#define UI_TEXT(name, text_value) ui_vnode_t* name = ui_vnode__text_node((text_value))
#define UI_FRAGMENT(name) ui_vnode_t* name = ui_vnode__fragment()
#define UI_IMAGE(name, bitmap_value) ui_vnode_t* name = ui_vnode__image((bitmap_value))
#define UI_COMPONENT(name, component_fn, props_ptr, props_size) ui_vnode_t* name = ui_vnode__component((component_fn), (props_ptr), (props_size))
#define UI_PROVIDER(name, context_ptr, value_ptr, value_size) ui_vnode_t* name = ui_vnode__provider((context_ptr), (value_ptr), (value_size))
#define UI_CHILD(parent, child_node) ui_vnode__child((parent), (child_node))
#define UI_ATTR(parent, attr_name, attr_value) ui_vnode__attr((parent), (attr_name), (attr_value))

#endif
