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
        char* string;
        borrowed void* pointer;
        ui_event_handler_t handler;
    } as;
} ui_value_t;

typedef struct ui_attr_t {
    char* name;
    ui_value_t value;
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
    double top;
    double left;
    double width;
    double height;
} ui_layout_style_t;

typedef struct ui_graphics_style_t {
    char* background;
    char* color;
    char* border;
    char* font_family;
    double font_size;
    int font_weight;
    unsigned int font_decoration;
} ui_graphics_style_t;

typedef struct ui_style_t {
    ui_layout_style_t layout;
    ui_graphics_style_t graphics;
} ui_style_t;

typedef enum ui_vnode_kind_t {
    UI_VNODE_ELEMENT = 1,
    UI_VNODE_TEXT,
    UI_VNODE_FRAGMENT,
    UI_VNODE_COMPONENT,
    UI_VNODE_CONTEXT_PROVIDER,
    UI_VNODE_IMAGE
} ui_vnode_kind_t;

typedef ui_vnode_t* (*ui_component_fn)(borrowed mut ui_render_context_t* context, borrowed const void* props);
typedef void (*ui_component_instance_destroy_fn)(borrowed mut ui_component_instance_t* instance);

struct ui_vnode_t {
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
        struct {
            borrowed const void* bitmap;
        } image;
    } as;
};

static char* ui__strdup(borrowed const char* text) {
    if (text == NULL) return NULL;
    size_t n = strlen(text) + 1;
    char* copy = malloc(n);
    if (copy != NULL) memcpy(copy, text, n);
    return copy;
}

static void* ui__memdup(borrowed const void* value, size_t size) {
    if (size == 0 || value == NULL) return NULL;
    void* copy = malloc(size);
    if (copy != NULL) memcpy(copy, value, size);
    return copy;
}

static int ui__replace_string(borrowed mut char** destination, borrowed const char* value) {
    char* copy = value == NULL ? NULL : ui__strdup(value);
    if (value != NULL && copy == NULL) return 1;
    free(*destination);
    *destination = copy;
    return 0;
}

pub ui_value_t ui_value_none(void) { ui_value_t v; memset(&v, 0, sizeof(v)); return v; }
pub ui_value_t ui_value_bool(int value) { ui_value_t v = ui_value_none(); v.kind = UI_VALUE_BOOL; v.as.boolean = value != 0; return v; }
pub ui_value_t ui_value_int(int64_t value) { ui_value_t v = ui_value_none(); v.kind = UI_VALUE_INT; v.as.integer = value; return v; }
pub ui_value_t ui_value_double(double value) { ui_value_t v = ui_value_none(); v.kind = UI_VALUE_DOUBLE; v.as.real = value; return v; }
pub ui_value_t ui_value_pointer(borrowed void* value) { ui_value_t v = ui_value_none(); v.kind = UI_VALUE_POINTER; v.as.pointer = value; return v; }
pub ui_value_t ui_value_handler(ui_event_type_t type, ui_event_fn callback, borrowed void* user) { ui_value_t v = ui_value_none(); v.kind = UI_VALUE_HANDLER; v.as.handler.type = type; v.as.handler.callback = callback; v.as.handler.user = user; return v; }
pub ui_value_t ui_value_string(borrowed const char* value) { ui_value_t v = ui_value_none(); v.kind = UI_VALUE_STRING; v.as.string = ui__strdup(value == NULL ? "" : value); return v; }

static void ui__value_destroy(borrowed mut ui_value_t* value) {
    if (value != NULL && value->kind == UI_VALUE_STRING) free(value->as.string);
    if (value != NULL) *value = ui_value_none();
}

static int ui__value_equal(borrowed const ui_value_t* a, borrowed const ui_value_t* b) {
    if (a->kind != b->kind) return 0;
    switch (a->kind) {
        case UI_VALUE_NONE: return 1;
        case UI_VALUE_BOOL: return a->as.boolean == b->as.boolean;
        case UI_VALUE_INT: return a->as.integer == b->as.integer;
        case UI_VALUE_DOUBLE: return a->as.real == b->as.real;
        case UI_VALUE_STRING:
            if (a->as.string == NULL || b->as.string == NULL) return a->as.string == b->as.string;
            return strcmp(a->as.string, b->as.string) == 0;
        case UI_VALUE_POINTER: return a->as.pointer == b->as.pointer;
        case UI_VALUE_HANDLER: return a->as.handler.type == b->as.handler.type && a->as.handler.callback == b->as.handler.callback && a->as.handler.user == b->as.handler.user;
        default: return 0;
    }
}

static void ui__style_init(borrowed mut ui_style_t* style) {
    memset(style, 0, sizeof(*style));
    style->layout.position = UI_POSITION_RELATIVE_PARENT;
}

static void ui__style_destroy(borrowed mut ui_style_t* style) {
    if (style == NULL) return;
    free(style->graphics.background);
    free(style->graphics.color);
    free(style->graphics.border);
    free(style->graphics.font_family);
    ui__style_init(style);
}

static ui_vnode_t* ui__vnode_new(ui_vnode_kind_t kind) {
    ui_vnode_t* node = calloc(1, sizeof(ui_vnode_t));
    if (node == NULL) return NULL;
    node->kind = kind;
    ui__style_init(&node->style);
    if (node->attrs.init() != 0 || node->children.init() != 0) {
        node->attrs.destroy(); node->children.destroy(); free(node); return NULL;
    }
    return node;
}

pub owned ui_vnode_t* ui_vnode_element(borrowed const char* tag) {
    ui_vnode_t* node = ui__vnode_new(UI_VNODE_ELEMENT);
    if (node == NULL) return NULL;
    node->tag = ui__strdup(tag == NULL ? "" : tag);
    if (node->tag == NULL) { node->attrs.destroy(); node->children.destroy(); free(node); return NULL; }
    return node;
}

pub owned ui_vnode_t* ui_vnode_text(borrowed const char* text) {
    ui_vnode_t* node = ui__vnode_new(UI_VNODE_TEXT);
    if (node == NULL) return NULL;
    node->text = ui__strdup(text == NULL ? "" : text);
    if (node->text == NULL) { node->attrs.destroy(); node->children.destroy(); free(node); return NULL; }
    return node;
}

pub owned ui_vnode_t* ui_vnode_fragment(void) { return ui__vnode_new(UI_VNODE_FRAGMENT); }

pub owned ui_vnode_t* ui_vnode_image(borrowed const void* bitmap) {
    ui_vnode_t* node = ui__vnode_new(UI_VNODE_IMAGE);
    if (node == NULL) return NULL;
    node->as.image.bitmap = bitmap;
    return node;
}

pub owned ui_vnode_t* ui_vnode_component(ui_component_fn component, borrowed const void* props, size_t props_size) {
    if (component == NULL) return NULL;
    ui_vnode_t* node = ui__vnode_new(UI_VNODE_COMPONENT);
    if (node == NULL) return NULL;
    node->as.component.render = component;
    node->as.component.props_size = props_size;
    if (props_size > 0) {
        node->as.component.props = ui__memdup(props, props_size);
        if (node->as.component.props == NULL) { node->attrs.destroy(); node->children.destroy(); free(node); return NULL; }
    }
    return node;
}

pub owned ui_vnode_t* ui_vnode_provider(borrowed ui_context_t* context, borrowed const void* value, size_t value_size) {
    if (context == NULL || value == NULL || value_size == 0) return NULL;
    ui_vnode_t* node = ui__vnode_new(UI_VNODE_CONTEXT_PROVIDER);
    if (node == NULL) return NULL;
    node->as.provider.context = context;
    node->as.provider.value_size = value_size;
    node->as.provider.value = ui__memdup(value, value_size);
    if (node->as.provider.value == NULL) { node->attrs.destroy(); node->children.destroy(); free(node); return NULL; }
    return node;
}

pub int ui_vnode_key(borrowed mut ui_vnode_t* node, borrowed const char* key) {
    if (node == NULL) return 1;
    return ui__replace_string(&node->key, key);
}

pub int ui_vnode_attr(borrowed mut ui_vnode_t* node, borrowed const char* name, ui_value_t value) {
    if (node == NULL || name == NULL) { ui__value_destroy(&value); return 1; }
    for (size_t i = 0; i < node->attrs.length; i++) {
        ui_attr_t* attr = &node->attrs.items[i];
        if (strcmp(attr->name, name) == 0) {
            ui__value_destroy(&attr->value);
            attr->value = value;
            return 0;
        }
    }
    ui_attr_t attr;
    attr.name = ui__strdup(name);
    attr.value = value;
    if (attr.name == NULL) { ui__value_destroy(&attr.value); return 1; }
    if (node->attrs.add(attr) != 0) { free(attr.name); ui__value_destroy(&attr.value); return 1; }
    return 0;
}

pub int ui_vnode_child(borrowed mut ui_vnode_t* parent, owned ui_vnode_t* child) {
    if (parent == NULL || child == NULL) return 1;
    return parent->children.add(child);
}

pub borrowed mut ui_style_t* ui_vnode_style(borrowed mut ui_vnode_t* node) { return node == NULL ? NULL : &node->style; }
pub borrowed const ui_style_t* ui_vnode_style_const(borrowed const ui_vnode_t* node) { return node == NULL ? NULL : &node->style; }
pub int ui_vnode_position(borrowed mut ui_vnode_t* node, ui_position_t position) { if (node == NULL) return 1; node->style.layout.position = position; return 0; }
pub int ui_vnode_top(borrowed mut ui_vnode_t* node, double value) { if (node == NULL) return 1; node->style.layout.top = value; return 0; }
pub int ui_vnode_left(borrowed mut ui_vnode_t* node, double value) { if (node == NULL) return 1; node->style.layout.left = value; return 0; }
pub int ui_vnode_width(borrowed mut ui_vnode_t* node, double value) { if (node == NULL) return 1; node->style.layout.width = value; return 0; }
pub int ui_vnode_height(borrowed mut ui_vnode_t* node, double value) { if (node == NULL) return 1; node->style.layout.height = value; return 0; }
pub int ui_vnode_layout(borrowed mut ui_vnode_t* node, ui_position_t position, double top, double left, double width, double height) {
    if (node == NULL) return 1;
    node->style.layout.position = position; node->style.layout.top = top; node->style.layout.left = left; node->style.layout.width = width; node->style.layout.height = height; return 0;
}
pub int ui_vnode_background(borrowed mut ui_vnode_t* node, borrowed const char* value) { return node == NULL ? 1 : ui__replace_string(&node->style.graphics.background, value); }
pub int ui_vnode_color(borrowed mut ui_vnode_t* node, borrowed const char* value) { return node == NULL ? 1 : ui__replace_string(&node->style.graphics.color, value); }
pub int ui_vnode_border(borrowed mut ui_vnode_t* node, borrowed const char* value) { return node == NULL ? 1 : ui__replace_string(&node->style.graphics.border, value); }
pub int ui_vnode_font_family(borrowed mut ui_vnode_t* node, borrowed const char* value) { return node == NULL ? 1 : ui__replace_string(&node->style.graphics.font_family, value); }
pub int ui_vnode_font_size(borrowed mut ui_vnode_t* node, double value) { if (node == NULL) return 1; node->style.graphics.font_size = value; return 0; }
pub int ui_vnode_font_weight(borrowed mut ui_vnode_t* node, int value) { if (node == NULL) return 1; node->style.graphics.font_weight = value; return 0; }
pub int ui_vnode_font_decoration(borrowed mut ui_vnode_t* node, unsigned int value) { if (node == NULL) return 1; node->style.graphics.font_decoration = value; return 0; }

pub void ui_vnode_destroy(owned ui_vnode_t* node) {
    if (node == NULL) return;
    for (size_t i = 0; i < node->attrs.length; i++) {
        free(node->attrs.items[i].name);
        ui__value_destroy(&node->attrs.items[i].value);
    }
    for (size_t i = 0; i < node->children.length; i++) ui_vnode_destroy(node->children.items[i]);
    node->attrs.destroy(); node->children.destroy();
    free(node->key); free(node->tag); free(node->text);
    ui__style_destroy(&node->style);
    if (node->kind == UI_VNODE_COMPONENT) {
        ui_vnode_destroy(node->as.component.rendered);
        if (node->as.component.instance != NULL && node->as.component.instance_destroy != NULL) node->as.component.instance_destroy(node->as.component.instance);
        free(node->as.component.props);
    } else if (node->kind == UI_VNODE_CONTEXT_PROVIDER) {
        free(node->as.provider.value);
    }
    free(node);
}

#define UI_ELEMENT(name, tag_literal) ui_vnode_t* name = ui_vnode_element((tag_literal))
#define UI_TEXT(name, text_value) ui_vnode_t* name = ui_vnode_text((text_value))
#define UI_FRAGMENT(name) ui_vnode_t* name = ui_vnode_fragment()
#define UI_IMAGE(name, bitmap_value) ui_vnode_t* name = ui_vnode_image((bitmap_value))
#define UI_COMPONENT(name, component_fn, props_ptr, props_size) ui_vnode_t* name = ui_vnode_component((component_fn), (props_ptr), (props_size))
#define UI_PROVIDER(name, context_ptr, value_ptr, value_size) ui_vnode_t* name = ui_vnode_provider((context_ptr), (value_ptr), (value_size))
#define UI_CHILD(parent, child) ui_vnode_child((parent), (child))
#define UI_ATTR(parent, attr_name, attr_value) ui_vnode_attr((parent), (attr_name), (attr_value))

#endif
