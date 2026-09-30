#ifndef CPLUS_UI_REACT_CP
#define CPLUS_UI_REACT_CP

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

comptime import "stdlib:/containers/dynamic_list.cp";

typedef struct ui_runtime_t ui_runtime_t;
typedef struct ui_render_context_t ui_render_context_t;
typedef struct ui_vnode_t ui_vnode_t;
typedef struct ui_component_instance_t ui_component_instance_t;
typedef struct ui_future_t ui_future_t;
typedef struct ui_context_t ui_context_t;

typedef ui_vnode_t* ui_vnode_ref_t;
typedef ui_component_instance_t* ui_component_instance_ref_t;
comptime typedef dynamic_list(ui_vnode_ref_t) ui_vnode_list_t;
comptime typedef dynamic_list(ui_component_instance_ref_t) ui_component_instance_list_t;

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

typedef enum ui_vnode_kind_t {
    UI_VNODE_ELEMENT = 1,
    UI_VNODE_TEXT,
    UI_VNODE_FRAGMENT,
    UI_VNODE_COMPONENT,
    UI_VNODE_CONTEXT_PROVIDER
} ui_vnode_kind_t;

typedef enum ui_patch_type_t {
    UI_PATCH_MOUNT = 1,
    UI_PATCH_UNMOUNT,
    UI_PATCH_MOVE,
    UI_PATCH_SET_ATTRIBUTE,
    UI_PATCH_REMOVE_ATTRIBUTE,
    UI_PATCH_SET_TEXT
} ui_patch_type_t;

typedef struct ui_patch_t {
    ui_patch_type_t type;
    uint64_t node_id;
    uint64_t parent_id;
    size_t index;
    ui_vnode_kind_t kind;
    borrowed const char* tag;
    borrowed const char* text;
    borrowed const char* name;
    ui_value_t value;
} ui_patch_t;

typedef struct ui_driver_t {
    borrowed void* user;
    int (*begin_frame)(borrowed void* user);
    int (*apply_patch)(borrowed void* user, borrowed const ui_patch_t* patch);
    int (*end_frame)(borrowed void* user);
    int (*poll_event)(borrowed void* user, borrowed mut ui_event_t* out_event);
    uint64_t (*now_ms)(borrowed void* user);
    int (*wait)(borrowed void* user, uint64_t timeout_ms);
} ui_driver_t;

typedef ui_vnode_t* (*ui_component_fn)(borrowed mut ui_render_context_t* context, borrowed const void* props);
typedef void (*ui_cleanup_fn)(borrowed void* user);
typedef ui_cleanup_fn (*ui_effect_fn)(borrowed void* user);
typedef void (*ui_timer_fn)(borrowed mut ui_runtime_t* runtime, borrowed void* user);

struct ui_context_t {
    const char* name;
    owned void* default_value;
    size_t value_size;
};

typedef struct ui_provider_frame_t {
    borrowed ui_context_t* context;
    borrowed const void* value;
    size_t value_size;
    borrowed struct ui_provider_frame_t* parent;
} ui_provider_frame_t;

typedef enum ui_future_status_t {
    UI_FUTURE_PENDING = 0,
    UI_FUTURE_RESOLVED,
    UI_FUTURE_REJECTED,
    UI_FUTURE_CANCELLED
} ui_future_status_t;

struct ui_future_t {
    ui_future_status_t status;
    owned void* value;
    size_t value_size;
    int error_code;
    ui_component_instance_list_t subscribers;
};

typedef enum ui_hook_kind_t {
    UI_HOOK_STATE = 1,
    UI_HOOK_EFFECT,
    UI_HOOK_FUTURE
} ui_hook_kind_t;

typedef struct ui_state_t {
    owned void* value;
    size_t size;
    borrowed ui_runtime_t* runtime;
    borrowed ui_component_instance_t* owner;
} ui_state_t;

typedef struct ui_hook_t {
    ui_hook_kind_t kind;
    union {
        ui_state_t state;
        struct {
            ui_effect_fn effect;
            ui_cleanup_fn cleanup;
            borrowed void* user;
            owned unsigned char* deps;
            size_t deps_size;
            int pending;
        } effect;
        struct {
            borrowed ui_future_t* future;
        } future;
    } as;
} ui_hook_t;
comptime typedef dynamic_list(ui_hook_t) ui_hook_list_t;

struct ui_component_instance_t {
    ui_component_fn component;
    ui_hook_list_t hooks;
    size_t hook_cursor;
    int dirty;
    int mounted;
    borrowed ui_runtime_t* runtime;
    borrowed ui_vnode_t* owner;
};

struct ui_vnode_t {
    ui_vnode_kind_t kind;
    uint64_t id;
    char* key;
    char* tag;
    char* text;
    ui_attr_list_t attrs;
    ui_vnode_list_t children;
    union {
        struct {
            ui_component_fn render;
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
    } as;
};

typedef struct ui_timer_t {
    uint64_t id;
    uint64_t due_ms;
    uint64_t interval_ms;
    ui_timer_fn callback;
    borrowed void* user;
    int active;
} ui_timer_t;
comptime typedef dynamic_list(ui_timer_t) ui_timer_list_t;

struct ui_render_context_t {
    borrowed ui_runtime_t* runtime;
    borrowed ui_component_instance_t* instance;
    borrowed ui_provider_frame_t* providers;
    int error;
};

struct ui_runtime_t {
    ui_driver_t driver;
    owned ui_vnode_t* root;
    ui_timer_list_t timers;
    uint64_t next_node_id;
    uint64_t next_timer_id;
    int dirty;
    int running;
    int last_error;
};

static char* ui__strdup(borrowed const char* text) {
    if (text == NULL) return NULL;
    size_t n = strlen(text) + 1;
    char* copy = malloc(n);
    if (copy != NULL) memcpy(copy, text, n);
    return copy;
}

static void* ui__memdup(borrowed const void* value, size_t size) {
    if (size == 0) return NULL;
    if (value == NULL) return NULL;
    void* copy = malloc(size);
    if (copy != NULL) memcpy(copy, value, size);
    return copy;
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

static ui_vnode_t* ui__vnode_new(ui_vnode_kind_t kind) {
    ui_vnode_t* node = calloc(1, sizeof(ui_vnode_t));
    if (node == NULL) return NULL;
    node->kind = kind;
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
    char* copy = key == NULL ? NULL : ui__strdup(key);
    if (key != NULL && copy == NULL) return 1;
    free(node->key); node->key = copy; return 0;
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

static void ui__future_unsubscribe(borrowed mut ui_future_t* future, borrowed ui_component_instance_t* instance);
static void ui__instance_destroy(borrowed mut ui_component_instance_t* instance);

pub void ui_vnode_destroy(owned ui_vnode_t* node) {
    if (node == NULL) return;
    for (size_t i = 0; i < node->attrs.length; i++) {
        free(node->attrs.items[i].name);
        ui__value_destroy(&node->attrs.items[i].value);
    }
    for (size_t i = 0; i < node->children.length; i++) ui_vnode_destroy(node->children.items[i]);
    node->attrs.destroy(); node->children.destroy();
    free(node->key); free(node->tag); free(node->text);
    if (node->kind == UI_VNODE_COMPONENT) {
        ui_vnode_destroy(node->as.component.rendered);
        ui__instance_destroy(node->as.component.instance);
        free(node->as.component.props);
    } else if (node->kind == UI_VNODE_CONTEXT_PROVIDER) {
        free(node->as.provider.value);
    }
    free(node);
}

pub int ui_context_init(borrowed mut ui_context_t* context, borrowed const char* name, borrowed const void* default_value, size_t size) {
    if (context == NULL || default_value == NULL || size == 0) return 1;
    memset(context, 0, sizeof(*context));
    context->name = name;
    context->default_value = ui__memdup(default_value, size);
    context->value_size = size;
    return context->default_value == NULL;
}

pub void ui_context_destroy(borrowed mut ui_context_t* context) {
    if (context == NULL) return;
    free(context->default_value); memset(context, 0, sizeof(*context));
}

pub borrowed const void* ui_use_context_raw(borrowed ui_render_context_t* render, borrowed ui_context_t* context, size_t expected_size) {
    if (render == NULL || context == NULL || expected_size != context->value_size) { if (render) render->error = 1; return NULL; }
    ui_provider_frame_t* frame = render->providers;
    while (frame != NULL) {
        if (frame->context == context) {
            if (frame->value_size != expected_size) { render->error = 1; return NULL; }
            return frame->value;
        }
        frame = frame->parent;
    }
    return context->default_value;
}

static int ui__future_subscribe(borrowed mut ui_future_t* future, borrowed ui_component_instance_t* instance) {
    if (future == NULL || instance == NULL) return 1;
    for (size_t i = 0; i < future->subscribers.length; i++) if (future->subscribers.items[i] == instance) return 0;
    return future->subscribers.add(instance);
}

static void ui__future_unsubscribe(borrowed mut ui_future_t* future, borrowed ui_component_instance_t* instance) {
    if (future == NULL || instance == NULL) return;
    for (size_t i = 0; i < future->subscribers.length; i++) {
        if (future->subscribers.items[i] == instance) {
            if (i + 1 < future->subscribers.length) memmove(&future->subscribers.items[i], &future->subscribers.items[i + 1], (future->subscribers.length - i - 1) * sizeof(ui_component_instance_ref_t));
            future->subscribers.length--;
            return;
        }
    }
}

pub int ui_future_init(borrowed mut ui_future_t* future) {
    if (future == NULL) return 1;
    memset(future, 0, sizeof(*future));
    future->status = UI_FUTURE_PENDING;
    return future->subscribers.init();
}

static void ui__future_wake(borrowed mut ui_future_t* future) {
    for (size_t i = 0; i < future->subscribers.length; i++) {
        ui_component_instance_t* instance = future->subscribers.items[i];
        if (instance != NULL && instance->runtime != NULL) { instance->dirty = 1; instance->runtime->dirty = 1; }
    }
}

pub int ui_future_resolve(borrowed mut ui_future_t* future, borrowed const void* value, size_t size) {
    if (future == NULL || future->status != UI_FUTURE_PENDING || (size > 0 && value == NULL)) return 1;
    if (size > 0) { future->value = ui__memdup(value, size); if (future->value == NULL) return 1; }
    future->value_size = size; future->status = UI_FUTURE_RESOLVED; ui__future_wake(future); return 0;
}

pub int ui_future_reject(borrowed mut ui_future_t* future, int error_code) {
    if (future == NULL || future->status != UI_FUTURE_PENDING) return 1;
    future->error_code = error_code; future->status = UI_FUTURE_REJECTED; ui__future_wake(future); return 0;
}

pub int ui_future_cancel(borrowed mut ui_future_t* future) {
    if (future == NULL || future->status != UI_FUTURE_PENDING) return 1;
    future->status = UI_FUTURE_CANCELLED; ui__future_wake(future); return 0;
}

pub void ui_future_destroy(borrowed mut ui_future_t* future) {
    if (future == NULL) return;
    free(future->value); future->subscribers.destroy(); memset(future, 0, sizeof(*future));
}

static ui_hook_t* ui__next_hook(borrowed mut ui_render_context_t* render, ui_hook_kind_t kind) {
    if (render == NULL || render->instance == NULL) return NULL;
    ui_component_instance_t* instance = render->instance;
    size_t index = instance->hook_cursor++;
    if (index < instance->hooks.length) {
        ui_hook_t* hook = &instance->hooks.items[index];
        if (hook->kind != kind) { render->error = 1; return NULL; }
        return hook;
    }
    ui_hook_t hook; memset(&hook, 0, sizeof(hook)); hook.kind = kind;
    if (instance->hooks.add(hook) != 0) { render->error = 1; return NULL; }
    return &instance->hooks.items[index];
}

pub borrowed ui_state_t* ui_use_state_raw(borrowed mut ui_render_context_t* render, size_t size, borrowed const void* initial_value) {
    if (size == 0 || initial_value == NULL) { if (render) render->error = 1; return NULL; }
    size_t before = render->instance->hook_cursor;
    ui_hook_t* hook = ui__next_hook(render, UI_HOOK_STATE);
    if (hook == NULL) return NULL;
    if (before >= render->instance->hooks.length - 1 && hook->as.state.value == NULL) {
        hook->as.state.value = ui__memdup(initial_value, size);
        if (hook->as.state.value == NULL) { render->error = 1; return NULL; }
        hook->as.state.size = size; hook->as.state.runtime = render->runtime; hook->as.state.owner = render->instance;
    }
    if (hook->as.state.size != size) { render->error = 1; return NULL; }
    return &hook->as.state;
}

pub int ui_state_set_raw(borrowed mut ui_state_t* state, borrowed const void* value, size_t size) {
    if (state == NULL || value == NULL || size != state->size) return 1;
    if (memcmp(state->value, value, size) == 0) return 0;
    memcpy(state->value, value, size);
    if (state->owner != NULL) state->owner->dirty = 1;
    if (state->runtime != NULL) state->runtime->dirty = 1;
    return 0;
}

pub int ui_use_effect_raw(borrowed mut ui_render_context_t* render, ui_effect_fn effect, borrowed void* user, borrowed const void* deps, size_t deps_size) {
    if (effect == NULL) { render->error = 1; return 1; }
    ui_hook_t* hook = ui__next_hook(render, UI_HOOK_EFFECT);
    if (hook == NULL) return 1;
    int changed = hook->as.effect.effect == NULL;
    if (!changed) {
        if (hook->as.effect.deps_size != deps_size) changed = 1;
        else if (deps_size > 0 && memcmp(hook->as.effect.deps, deps, deps_size) != 0) changed = 1;
    }
    if (changed) {
        unsigned char* copy = NULL;
        if (deps_size > 0) { copy = ui__memdup(deps, deps_size); if (copy == NULL) { render->error = 1; return 1; } }
        free(hook->as.effect.deps); hook->as.effect.deps = copy; hook->as.effect.deps_size = deps_size; hook->as.effect.pending = 1;
    }
    hook->as.effect.effect = effect; hook->as.effect.user = user;
    return 0;
}

pub borrowed ui_future_t* ui_use_future(borrowed mut ui_render_context_t* render, borrowed mut ui_future_t* future) {
    if (future == NULL) { render->error = 1; return NULL; }
    ui_hook_t* hook = ui__next_hook(render, UI_HOOK_FUTURE);
    if (hook == NULL) return NULL;
    if (hook->as.future.future != future) {
        ui__future_unsubscribe(hook->as.future.future, render->instance);
        if (ui__future_subscribe(future, render->instance) != 0) { render->error = 1; return NULL; }
        hook->as.future.future = future;
    }
    return future;
}

static void ui__instance_destroy(borrowed mut ui_component_instance_t* instance) {
    if (instance == NULL) return;
    for (size_t i = 0; i < instance->hooks.length; i++) {
        ui_hook_t* hook = &instance->hooks.items[i];
        if (hook->kind == UI_HOOK_STATE) free(hook->as.state.value);
        else if (hook->kind == UI_HOOK_EFFECT) { if (hook->as.effect.cleanup) hook->as.effect.cleanup(hook->as.effect.user); free(hook->as.effect.deps); }
        else if (hook->kind == UI_HOOK_FUTURE) ui__future_unsubscribe(hook->as.future.future, instance);
    }
    instance->hooks.destroy(); free(instance);
}

static ui_component_instance_t* ui__instance_new(borrowed ui_runtime_t* runtime, borrowed ui_vnode_t* owner, ui_component_fn component) {
    ui_component_instance_t* instance = calloc(1, sizeof(*instance));
    if (instance == NULL) return NULL;
    if (instance->hooks.init() != 0) { free(instance); return NULL; }
    instance->component = component; instance->runtime = runtime; instance->owner = owner; instance->dirty = 1;
    return instance;
}

static ui_vnode_t* ui__render_component(borrowed mut ui_runtime_t* runtime, borrowed mut ui_vnode_t* node, borrowed ui_provider_frame_t* providers) {
    ui_component_instance_t* instance = node->as.component.instance;
    if (instance == NULL) {
        instance = ui__instance_new(runtime, node, node->as.component.render);
        if (instance == NULL) { runtime->last_error = 1; return NULL; }
        node->as.component.instance = instance;
    }
    instance->owner = node; instance->hook_cursor = 0;
    ui_render_context_t render; render.runtime = runtime; render.instance = instance; render.providers = providers; render.error = 0;
    ui_vnode_t* result = node->as.component.render(&render, node->as.component.props);
    if (render.error || result == NULL || instance->hook_cursor != instance->hooks.length) {
        runtime->last_error = 1;
        if (result != NULL) ui_vnode_destroy(result);
        return NULL;
    }
    instance->dirty = 0; instance->mounted = 1;
    return result;
}

static int ui__emit(borrowed mut ui_runtime_t* runtime, borrowed const ui_patch_t* patch) {
    if (runtime->driver.apply_patch == NULL) return 0;
    int result = runtime->driver.apply_patch(runtime->driver.user, patch);
    if (result != 0) runtime->last_error = result;
    return result;
}

static int ui__begin(borrowed mut ui_runtime_t* runtime) { return runtime->driver.begin_frame ? runtime->driver.begin_frame(runtime->driver.user) : 0; }
static int ui__end(borrowed mut ui_runtime_t* runtime) { return runtime->driver.end_frame ? runtime->driver.end_frame(runtime->driver.user) : 0; }

static ui_attr_t* ui__find_attr(borrowed ui_vnode_t* node, borrowed const char* name) {
    for (size_t i = 0; i < node->attrs.length; i++) if (strcmp(node->attrs.items[i].name, name) == 0) return &node->attrs.items[i];
    return NULL;
}

static int ui__mount(borrowed mut ui_runtime_t* runtime, borrowed mut ui_vnode_t* node, uint64_t parent_id, size_t index, borrowed ui_provider_frame_t* providers) {
    if (node == NULL) return 0;
    node->id = runtime->next_node_id++;
    ui_patch_t patch; memset(&patch, 0, sizeof(patch)); patch.type = UI_PATCH_MOUNT; patch.node_id = node->id; patch.parent_id = parent_id; patch.index = index; patch.kind = node->kind; patch.tag = node->tag; patch.text = node->text;
    if (ui__emit(runtime, &patch) != 0) return 1;
    for (size_t i = 0; i < node->attrs.length; i++) {
        memset(&patch, 0, sizeof(patch)); patch.type = UI_PATCH_SET_ATTRIBUTE; patch.node_id = node->id; patch.name = node->attrs.items[i].name; patch.value = node->attrs.items[i].value;
        if (ui__emit(runtime, &patch) != 0) return 1;
    }
    if (node->kind == UI_VNODE_COMPONENT) {
        node->as.component.rendered = ui__render_component(runtime, node, providers);
        if (node->as.component.rendered == NULL) return 1;
        return ui__mount(runtime, node->as.component.rendered, node->id, 0, providers);
    }
    if (node->kind == UI_VNODE_CONTEXT_PROVIDER) {
        ui_provider_frame_t frame; frame.context = node->as.provider.context; frame.value = node->as.provider.value; frame.value_size = node->as.provider.value_size; frame.parent = providers;
        for (size_t i = 0; i < node->children.length; i++) if (ui__mount(runtime, node->children.items[i], node->id, i, &frame) != 0) return 1;
        return 0;
    }
    for (size_t i = 0; i < node->children.length; i++) if (ui__mount(runtime, node->children.items[i], node->id, i, providers) != 0) return 1;
    return 0;
}

static int ui__unmount(borrowed mut ui_runtime_t* runtime, borrowed ui_vnode_t* node) {
    if (node == NULL) return 0;
    ui_patch_t patch; memset(&patch, 0, sizeof(patch)); patch.type = UI_PATCH_UNMOUNT; patch.node_id = node->id;
    return ui__emit(runtime, &patch);
}

static int ui__same_identity(borrowed ui_vnode_t* a, borrowed ui_vnode_t* b) {
    if (a->kind != b->kind) return 0;
    if ((a->key == NULL) != (b->key == NULL)) return 0;
    if (a->key && strcmp(a->key, b->key) != 0) return 0;
    if (a->kind == UI_VNODE_ELEMENT) return strcmp(a->tag, b->tag) == 0;
    if (a->kind == UI_VNODE_COMPONENT) return a->as.component.render == b->as.component.render;
    if (a->kind == UI_VNODE_CONTEXT_PROVIDER) return a->as.provider.context == b->as.provider.context;
    return 1;
}

static int ui__reconcile(borrowed mut ui_runtime_t* runtime, borrowed mut ui_vnode_t* old_node, borrowed mut ui_vnode_t* new_node, uint64_t parent_id, size_t index, borrowed ui_provider_frame_t* providers);

static int ui__reconcile_children(borrowed mut ui_runtime_t* runtime, borrowed mut ui_vnode_t* old_node, borrowed mut ui_vnode_t* new_node, borrowed ui_provider_frame_t* providers) {
    size_t old_count = old_node->children.length;
    size_t new_count = new_node->children.length;
    unsigned char* used = old_count == 0 ? NULL : calloc(old_count, 1);
    if (old_count > 0 && used == NULL) return 1;
    for (size_t ni = 0; ni < new_count; ni++) {
        ui_vnode_t* next = new_node->children.items[ni];
        size_t oi = (size_t)-1;
        if (next != NULL && next->key != NULL) {
            for (size_t j = 0; j < old_count; j++) {
                ui_vnode_t* prev = old_node->children.items[j];
                if (!used[j] && prev != NULL && prev->key != NULL && ui__same_identity(prev, next)) { oi = j; break; }
            }
        } else if (ni < old_count && !used[ni]) {
            ui_vnode_t* prev = old_node->children.items[ni];
            if (prev == NULL || prev->key == NULL) oi = ni;
        }
        if (oi == (size_t)-1) {
            if (ui__mount(runtime, next, new_node->id, ni, providers) != 0) { free(used); return 1; }
            continue;
        }
        used[oi] = 1;
        ui_vnode_t* prev = old_node->children.items[oi];
        if (oi != ni && prev != NULL) {
            ui_patch_t move; memset(&move, 0, sizeof(move)); move.type = UI_PATCH_MOVE; move.node_id = prev->id; move.parent_id = new_node->id; move.index = ni;
            if (ui__emit(runtime, &move) != 0) { free(used); return 1; }
        }
        if (ui__reconcile(runtime, prev, next, new_node->id, ni, providers) != 0) { free(used); return 1; }
    }
    for (size_t oi = 0; oi < old_count; oi++) {
        if (!used[oi] && ui__unmount(runtime, old_node->children.items[oi]) != 0) { free(used); return 1; }
    }
    free(used);
    return 0;
}

static int ui__reconcile(borrowed mut ui_runtime_t* runtime, borrowed mut ui_vnode_t* old_node, borrowed mut ui_vnode_t* new_node, uint64_t parent_id, size_t index, borrowed ui_provider_frame_t* providers) {
    if (old_node == NULL) return ui__mount(runtime, new_node, parent_id, index, providers);
    if (new_node == NULL) return ui__unmount(runtime, old_node);
    if (!ui__same_identity(old_node, new_node)) { if (ui__unmount(runtime, old_node) != 0) return 1; return ui__mount(runtime, new_node, parent_id, index, providers); }
    new_node->id = old_node->id;
    ui_patch_t patch; memset(&patch, 0, sizeof(patch));
    if (new_node->kind == UI_VNODE_TEXT) {
        if (strcmp(old_node->text, new_node->text) != 0) { patch.type = UI_PATCH_SET_TEXT; patch.node_id = new_node->id; patch.text = new_node->text; if (ui__emit(runtime, &patch) != 0) return 1; }
        return 0;
    }
    for (size_t i = 0; i < old_node->attrs.length; i++) {
        ui_attr_t* next = ui__find_attr(new_node, old_node->attrs.items[i].name);
        if (next == NULL) { memset(&patch,0,sizeof(patch)); patch.type = UI_PATCH_REMOVE_ATTRIBUTE; patch.node_id = new_node->id; patch.name = old_node->attrs.items[i].name; if (ui__emit(runtime,&patch)!=0) return 1; }
    }
    for (size_t i = 0; i < new_node->attrs.length; i++) {
        ui_attr_t* prev = ui__find_attr(old_node, new_node->attrs.items[i].name);
        if (prev == NULL || !ui__value_equal(&prev->value, &new_node->attrs.items[i].value)) { memset(&patch,0,sizeof(patch)); patch.type = UI_PATCH_SET_ATTRIBUTE; patch.node_id = new_node->id; patch.name = new_node->attrs.items[i].name; patch.value = new_node->attrs.items[i].value; if (ui__emit(runtime,&patch)!=0) return 1; }
    }
    if (new_node->kind == UI_VNODE_COMPONENT) {
        new_node->as.component.instance = old_node->as.component.instance; old_node->as.component.instance = NULL;
        new_node->as.component.instance->owner = new_node;
        new_node->as.component.rendered = ui__render_component(runtime, new_node, providers);
        if (new_node->as.component.rendered == NULL) return 1;
        return ui__reconcile(runtime, old_node->as.component.rendered, new_node->as.component.rendered, new_node->id, 0, providers);
    }
    ui_provider_frame_t frame; ui_provider_frame_t* child_providers = providers;
    if (new_node->kind == UI_VNODE_CONTEXT_PROVIDER) { frame.context = new_node->as.provider.context; frame.value = new_node->as.provider.value; frame.value_size = new_node->as.provider.value_size; frame.parent = providers; child_providers = &frame; }
    return ui__reconcile_children(runtime, old_node, new_node, child_providers);
}

static void ui__flush_effects_node(borrowed ui_vnode_t* node) {
    if (node == NULL) return;
    if (node->kind == UI_VNODE_COMPONENT && node->as.component.instance != NULL) {
        ui_component_instance_t* instance = node->as.component.instance;
        for (size_t i = 0; i < instance->hooks.length; i++) {
            ui_hook_t* hook = &instance->hooks.items[i];
            if (hook->kind == UI_HOOK_EFFECT && hook->as.effect.pending) {
                if (hook->as.effect.cleanup) hook->as.effect.cleanup(hook->as.effect.user);
                hook->as.effect.cleanup = hook->as.effect.effect(hook->as.effect.user);
                hook->as.effect.pending = 0;
            }
        }
        ui__flush_effects_node(node->as.component.rendered);
    }
    for (size_t i = 0; i < node->children.length; i++) ui__flush_effects_node(node->children.items[i]);
}

static int ui__refresh_dirty(borrowed mut ui_runtime_t* runtime, borrowed mut ui_vnode_t* node, borrowed ui_provider_frame_t* providers) {
    if (node == NULL) return 0;
    if (node->kind == UI_VNODE_COMPONENT) {
        ui_component_instance_t* instance = node->as.component.instance;
        if (instance != NULL && instance->dirty) {
            ui_vnode_t* old_rendered = node->as.component.rendered;
            ui_vnode_t* next = ui__render_component(runtime, node, providers);
            if (next == NULL) return 1;
            node->as.component.rendered = next;
            if (ui__reconcile(runtime, old_rendered, next, node->id, 0, providers) != 0) return 1;
            ui_vnode_destroy(old_rendered);
        }
        return ui__refresh_dirty(runtime, node->as.component.rendered, providers);
    }
    ui_provider_frame_t frame; ui_provider_frame_t* child_providers = providers;
    if (node->kind == UI_VNODE_CONTEXT_PROVIDER) { frame.context = node->as.provider.context; frame.value = node->as.provider.value; frame.value_size = node->as.provider.value_size; frame.parent = providers; child_providers = &frame; }
    for (size_t i = 0; i < node->children.length; i++) if (ui__refresh_dirty(runtime, node->children.items[i], child_providers) != 0) return 1;
    return 0;
}

static ui_vnode_t* ui__find_node(borrowed ui_vnode_t* node, uint64_t id) {
    if (node == NULL) return NULL;
    if (node->id == id) return node;
    if (node->kind == UI_VNODE_COMPONENT) { ui_vnode_t* found = ui__find_node(node->as.component.rendered, id); if (found) return found; }
    for (size_t i = 0; i < node->children.length; i++) { ui_vnode_t* found = ui__find_node(node->children.items[i], id); if (found) return found; }
    return NULL;
}

pub int ui_runtime_dispatch(borrowed mut ui_runtime_t* runtime, borrowed const ui_event_t* event) {
    if (runtime == NULL || event == NULL) return 1;
    if (event->type == UI_EVENT_QUIT) { runtime->running = 0; return 0; }
    ui_vnode_t* node = ui__find_node(runtime->root, event->target_id);
    if (node == NULL) return 0;
    for (size_t i = 0; i < node->attrs.length; i++) {
        ui_value_t* value = &node->attrs.items[i].value;
        if (value->kind == UI_VALUE_HANDLER && value->as.handler.type == event->type && value->as.handler.callback != NULL) {
            int result = value->as.handler.callback(event, value->as.handler.user);
            if (result != 0) return result;
        }
    }
    return 0;
}

pub int ui_runtime_init(borrowed mut ui_runtime_t* runtime, ui_driver_t driver) {
    if (runtime == NULL || driver.apply_patch == NULL || driver.poll_event == NULL || driver.now_ms == NULL) return 1;
    memset(runtime, 0, sizeof(*runtime)); runtime->driver = driver; runtime->next_node_id = 1; runtime->next_timer_id = 1; runtime->running = 1;
    return runtime->timers.init();
}

pub int ui_runtime_mount(borrowed mut ui_runtime_t* runtime, owned ui_vnode_t* root) {
    if (runtime == NULL || root == NULL || runtime->root != NULL) return 1;
    if (ui__begin(runtime) != 0) return 1;
    runtime->root = root;
    int result = ui__mount(runtime, root, 0, 0, NULL);
    if (ui__end(runtime) != 0) result = 1;
    if (result == 0) ui__flush_effects_node(root);
    return result;
}

pub int ui_runtime_update_root(borrowed mut ui_runtime_t* runtime, owned ui_vnode_t* next_root) {
    if (runtime == NULL || next_root == NULL || runtime->root == NULL) return 1;
    ui_vnode_t* old = runtime->root;
    if (ui__begin(runtime) != 0) return 1;
    int result = ui__reconcile(runtime, old, next_root, 0, 0, NULL);
    if (ui__end(runtime) != 0) result = 1;
    if (result == 0) { runtime->root = next_root; ui_vnode_destroy(old); ui__flush_effects_node(next_root); }
    else ui_vnode_destroy(next_root);
    return result;
}

pub uint64_t ui_set_timeout(borrowed mut ui_runtime_t* runtime, uint64_t delay_ms, ui_timer_fn callback, borrowed void* user) {
    if (runtime == NULL || callback == NULL) return 0;
    ui_timer_t timer; timer.id = runtime->next_timer_id++; timer.due_ms = runtime->driver.now_ms(runtime->driver.user) + delay_ms; timer.interval_ms = 0; timer.callback = callback; timer.user = user; timer.active = 1;
    return runtime->timers.add(timer) == 0 ? timer.id : 0;
}

pub uint64_t ui_set_interval(borrowed mut ui_runtime_t* runtime, uint64_t interval_ms, ui_timer_fn callback, borrowed void* user) {
    if (runtime == NULL || callback == NULL || interval_ms == 0) return 0;
    ui_timer_t timer; timer.id = runtime->next_timer_id++; timer.due_ms = runtime->driver.now_ms(runtime->driver.user) + interval_ms; timer.interval_ms = interval_ms; timer.callback = callback; timer.user = user; timer.active = 1;
    return runtime->timers.add(timer) == 0 ? timer.id : 0;
}

pub int ui_cancel_timer(borrowed mut ui_runtime_t* runtime, uint64_t id) {
    if (runtime == NULL || id == 0) return 1;
    for (size_t i = 0; i < runtime->timers.length; i++) if (runtime->timers.items[i].id == id) { runtime->timers.items[i].active = 0; return 0; }
    return 1;
}

static void ui__run_timers(borrowed mut ui_runtime_t* runtime) {
    uint64_t now = runtime->driver.now_ms(runtime->driver.user);
    for (size_t i = 0; i < runtime->timers.length; i++) {
        ui_timer_t* timer = &runtime->timers.items[i];
        if (timer->active && now >= timer->due_ms) {
            if (timer->interval_ms == 0) timer->active = 0;
            else { do { timer->due_ms += timer->interval_ms; } while (timer->due_ms <= now); }
            timer->callback(runtime, timer->user);
        }
    }
}

static uint64_t ui__next_wait(borrowed ui_runtime_t* runtime) {
    uint64_t now = runtime->driver.now_ms(runtime->driver.user);
    uint64_t best = 1000;
    for (size_t i = 0; i < runtime->timers.length; i++) {
        ui_timer_t* timer = &runtime->timers.items[i];
        if (!timer->active) continue;
        if (timer->due_ms <= now) return 0;
        uint64_t delta = timer->due_ms - now;
        if (delta < best) best = delta;
    }
    return best;
}

pub int ui_runtime_tick(borrowed mut ui_runtime_t* runtime) {
    if (runtime == NULL) return 1;
    ui_event_t event;
    for (;;) {
        memset(&event, 0, sizeof(event));
        int polled = runtime->driver.poll_event(runtime->driver.user, &event);
        if (polled < 0) return polled;
        if (polled == 0) break;
        int dispatched = ui_runtime_dispatch(runtime, &event);
        if (dispatched != 0) return dispatched;
    }
    ui__run_timers(runtime);
    if (runtime->dirty && runtime->root != NULL) {
        runtime->dirty = 0;
        if (ui__begin(runtime) != 0) return 1;
        int result = ui__refresh_dirty(runtime, runtime->root, NULL);
        if (ui__end(runtime) != 0) result = 1;
        if (result != 0) return result;
        ui__flush_effects_node(runtime->root);
    }
    return runtime->last_error;
}

pub int ui_runtime_run(borrowed mut ui_runtime_t* runtime) {
    if (runtime == NULL) return 1;
    runtime->running = 1;
    while (runtime->running) {
        int result = ui_runtime_tick(runtime);
        if (result != 0) return result;
        if (runtime->driver.wait != NULL) {
            result = runtime->driver.wait(runtime->driver.user, ui__next_wait(runtime));
            if (result != 0) return result;
        }
    }
    return 0;
}

pub void ui_runtime_stop(borrowed mut ui_runtime_t* runtime) { if (runtime) runtime->running = 0; }

pub void ui_runtime_destroy(borrowed mut ui_runtime_t* runtime) {
    if (runtime == NULL) return;
    ui_vnode_destroy(runtime->root); runtime->root = NULL; runtime->timers.destroy(); memset(runtime, 0, sizeof(*runtime));
}

#define UI_ELEMENT(name, tag_literal) ui_vnode_t* name = ui_vnode_element((tag_literal))
#define UI_TEXT(name, text_value) ui_vnode_t* name = ui_vnode_text((text_value))
#define UI_FRAGMENT(name) ui_vnode_t* name = ui_vnode_fragment()
#define UI_COMPONENT(name, component_fn, props_ptr, props_size) ui_vnode_t* name = ui_vnode_component((component_fn), (props_ptr), (props_size))
#define UI_PROVIDER(name, context_ptr, value_ptr, value_size) ui_vnode_t* name = ui_vnode_provider((context_ptr), (value_ptr), (value_size))
#define UI_CHILD(parent, child) ui_vnode_child((parent), (child))
#define UI_ATTR(parent, attr_name, attr_value) ui_vnode_attr((parent), (attr_name), (attr_value))
#define use_state(context, T, initial_value) ui_use_state_raw((context), sizeof(T), &(T){(initial_value)})
#define state_value(T, state) (*(T*)((state)->value))
#define state_set(T, state, new_value) ui_state_set_raw((state), &(T){(new_value)}, sizeof(T))
#define use_context(context, T, context_object) ((const T*)ui_use_context_raw((context), (context_object), sizeof(T)))
#define use_effect(context, effect_fn, user_ptr, deps_ptr, deps_size) ui_use_effect_raw((context), (effect_fn), (user_ptr), (deps_ptr), (deps_size))
#define use_future(context, future_ptr) ui_use_future((context), (future_ptr))

#endif
