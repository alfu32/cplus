#ifndef CPLUS_UI_REACT_CP
#define CPLUS_UI_REACT_CP

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

comptime import "stdlib:/ui/driver.cp";

/* ---------- state ---------- */

typedef struct ui_state_t {
    owned void* bytes;
    size_t size;
    borrowed int* dirty;

    pub int init(
        borrowed mut *self,
        borrowed const void* initial_value,
        size_t size,
        borrowed int* dirty
    ) {
        if (self == NULL || size == 0 || initial_value == NULL || dirty == NULL) return 1;
        memset(self, 0, sizeof(*self));
        self->bytes = malloc(size);
        if (self->bytes == NULL) return 1;
        memcpy(self->bytes, initial_value, size);
        self->size = size;
        self->dirty = dirty;
        return 0;
    }

    pub borrowed void* value(borrowed *self) {
        return self == NULL ? NULL : self->bytes;
    }

    pub int set(borrowed mut *self, borrowed const void* value, size_t size) {
        if (self == NULL || value == NULL || self->bytes == NULL || size != self->size) return 1;
        if (memcmp(self->bytes, value, size) == 0) return 0;
        memcpy(self->bytes, value, size);
        if (self->dirty != NULL) *self->dirty = 1;
        return 0;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        free(self->bytes);
        memset(self, 0, sizeof(*self));
    }
} ui_state_t;

/* ---------- futures ---------- */

typedef int* ui_dirty_ref_t;
comptime typedef dynamic_list(ui_dirty_ref_t) ui_dirty_ref_list_t;

typedef enum ui_future_status_t {
    UI_FUTURE_PENDING = 0,
    UI_FUTURE_RESOLVED,
    UI_FUTURE_REJECTED,
    UI_FUTURE_CANCELLED
} ui_future_status_t;

typedef struct ui_future_t {
    ui_future_status_t status;
    owned void* value;
    size_t value_size;
    int error;
    ui_dirty_ref_list_t subscribers;

    priv void notify(borrowed mut *self) {
        if (self == NULL) return;
        for (size_t i = 0; i < self->subscribers.length; i++) {
            int* dirty = self->subscribers.items[i];
            if (dirty != NULL) *dirty = 1;
        }
    }

    pub int init(borrowed mut *self) {
        if (self == NULL) return 1;
        memset(self, 0, sizeof(*self));
        self->status = UI_FUTURE_PENDING;
        return self->subscribers.init();
    }

    pub int subscribe(borrowed mut *self, borrowed int* dirty) {
        if (self == NULL || dirty == NULL) return 1;
        for (size_t i = 0; i < self->subscribers.length; i++) {
            if (self->subscribers.items[i] == dirty) return 0;
        }
        return self->subscribers.add(dirty);
    }

    pub void unsubscribe(borrowed mut *self, borrowed int* dirty) {
        if (self == NULL || dirty == NULL) return;
        for (size_t i = 0; i < self->subscribers.length; i++) {
            if (self->subscribers.items[i] != dirty) continue;
            for (size_t j = i + 1; j < self->subscribers.length; j++) {
                self->subscribers.items[j - 1] = self->subscribers.items[j];
            }
            self->subscribers.length--;
            return;
        }
    }

    pub int resolve(borrowed mut *self, borrowed const void* value, size_t size) {
        if (self == NULL || self->status != UI_FUTURE_PENDING) return 1;
        if (size > 0 && value == NULL) return 1;

        if (size > 0) {
            self->value = malloc(size);
            if (self->value == NULL) return 1;
            memcpy(self->value, value, size);
        }
        self->value_size = size;
        self->status = UI_FUTURE_RESOLVED;
        self->notify();
        return 0;
    }

    pub int reject(borrowed mut *self, int error) {
        if (self == NULL || self->status != UI_FUTURE_PENDING) return 1;
        self->error = error;
        self->status = UI_FUTURE_REJECTED;
        self->notify();
        return 0;
    }

    pub int cancel(borrowed mut *self) {
        if (self == NULL || self->status != UI_FUTURE_PENDING) return 1;
        self->status = UI_FUTURE_CANCELLED;
        self->notify();
        return 0;
    }

    pub borrowed const void* get(borrowed const *self) {
        if (self == NULL || self->status != UI_FUTURE_RESOLVED) return NULL;
        return self->value;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        free(self->value);
        self->subscribers.destroy();
        memset(self, 0, sizeof(*self));
    }
} ui_future_t;

/* ---------- effects ---------- */

typedef void (*ui_cleanup_fn)(borrowed void* user);
typedef ui_cleanup_fn (*ui_effect_fn)(borrowed void* user);

typedef struct ui_effect_hook_t {
    ui_effect_fn effect;
    borrowed void* user;
    owned void* dependencies;
    size_t dependency_size;
    ui_cleanup_fn cleanup;
    int pending;

    static priv owned void* copy_dependencies(borrowed const void* dependencies, size_t size) {
        if (size == 0) return NULL;
        if (dependencies == NULL) return NULL;
        void* copy = malloc(size);
        if (copy != NULL) memcpy(copy, dependencies, size);
        return copy;
    }

    pub int init(
        borrowed mut *self,
        ui_effect_fn effect,
        borrowed void* user,
        borrowed const void* dependencies,
        size_t dependency_size
    ) {
        if (self == NULL || effect == NULL || (dependency_size > 0 && dependencies == NULL)) return 1;
        memset(self, 0, sizeof(*self));
        self->effect = effect;
        self->user = user;
        self->dependency_size = dependency_size;
        if (dependency_size > 0) {
            self->dependencies = ui_effect_hook_t.copy_dependencies(dependencies, dependency_size);
            if (self->dependencies == NULL) return 1;
        }
        self->pending = 1;
        return 0;
    }

    pub int update(
        borrowed mut *self,
        ui_effect_fn effect,
        borrowed void* user,
        borrowed const void* dependencies,
        size_t dependency_size
    ) {
        if (self == NULL || effect == NULL || (dependency_size > 0 && dependencies == NULL)) return 1;

        int changed = self->effect != effect || self->user != user || self->dependency_size != dependency_size;
        if (!changed && dependency_size > 0) {
            changed = memcmp(self->dependencies, dependencies, dependency_size) != 0;
        }
        if (!changed) return 0;

        void* copy = ui_effect_hook_t.copy_dependencies(dependencies, dependency_size);
        if (dependency_size > 0 && copy == NULL) return 1;
        free(self->dependencies);
        self->dependencies = copy;
        self->dependency_size = dependency_size;
        self->effect = effect;
        self->user = user;
        self->pending = 1;
        return 0;
    }

    pub void flush(borrowed mut *self) {
        if (self == NULL || !self->pending) return;
        if (self->cleanup != NULL) self->cleanup(self->user);
        self->cleanup = self->effect == NULL ? NULL : self->effect(self->user);
        self->pending = 0;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        if (self->cleanup != NULL) self->cleanup(self->user);
        free(self->dependencies);
        memset(self, 0, sizeof(*self));
    }
} ui_effect_hook_t;

/* ---------- hooks and contexts ---------- */

typedef enum ui_hook_kind_t {
    UI_HOOK_STATE = 1,
    UI_HOOK_EFFECT,
    UI_HOOK_FUTURE
} ui_hook_kind_t;

typedef struct ui_hook_t {
    ui_hook_kind_t kind;
    union {
        ui_state_t state;
        ui_effect_hook_t effect;
        borrowed ui_future_t* future;
    } as;

    pub void destroy(borrowed mut *self, borrowed int* dirty) {
        if (self == NULL) return;
        if (self->kind == UI_HOOK_STATE) {
            ui_state_t* state = &self->as.state;
            state->destroy();
        } else if (self->kind == UI_HOOK_EFFECT) {
            ui_effect_hook_t* effect = &self->as.effect;
            effect->destroy();
        } else if (self->kind == UI_HOOK_FUTURE && self->as.future != NULL) {
            ui_future_t* future = self->as.future;
            future->unsubscribe(dirty);
        }
        memset(self, 0, sizeof(*self));
    }
} ui_hook_t;

comptime typedef dynamic_list(ui_hook_t) ui_hook_list_t;

typedef struct ui_context_t {
    string name;
    owned void* default_value;
    size_t value_size;

    pub int init(
        borrowed mut *self,
        borrowed const char* name,
        borrowed const void* default_value,
        size_t value_size
    ) {
        if (self == NULL || default_value == NULL || value_size == 0) return 1;
        memset(self, 0, sizeof(*self));
        if (self->name.init() != 0) return 1;
        if (self->name.assign(name == NULL ? "" : name) != 0) {
            self->name.destroy();
            return 1;
        }
        self->default_value = malloc(value_size);
        if (self->default_value == NULL) {
            self->name.destroy();
            return 1;
        }
        memcpy(self->default_value, default_value, value_size);
        self->value_size = value_size;
        return 0;
    }

    pub borrowed const void* value(borrowed const *self) {
        return self == NULL ? NULL : self->default_value;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        self->name.destroy();
        free(self->default_value);
        memset(self, 0, sizeof(*self));
    }
} ui_context_t;

typedef struct ui_context_binding_t {
    borrowed ui_context_t* context;
    borrowed const void* value;
    size_t value_size;
} ui_context_binding_t;

comptime typedef dynamic_list(ui_context_binding_t) ui_context_binding_list_t;

typedef struct ui_component_instance_t {
    ui_hook_list_t hooks;
    int dirty;
    int rendered_once;

    static pub owned ui_component_instance_t* create(void) {
        ui_component_instance_t* instance = calloc(1, sizeof(ui_component_instance_t));
        if (instance == NULL) return NULL;
        if (instance->hooks.init() != 0) {
            free(instance);
            return NULL;
        }
        instance->dirty = 1;
        return instance;
    }

    pub void flush_effects(borrowed mut *self) {
        if (self == NULL) return;
        for (size_t i = 0; i < self->hooks.length; i++) {
            ui_hook_t* hook = &self->hooks.items[i];
            if (hook->kind == UI_HOOK_EFFECT) {
                ui_effect_hook_t* effect = &hook->as.effect;
                effect->flush();
            }
        }
    }

    pub void destroy(owned *self) {
        if (self == NULL) return;
        for (size_t i = 0; i < self->hooks.length; i++) self->hooks.items[i].destroy(&self->dirty);
        self->hooks.destroy();
        free(self);
    }
} ui_component_instance_t;

typedef struct ui_render_context_t {
    borrowed ui_component_instance_t* component;
    borrowed ui_context_binding_list_t* bindings;
    size_t hook_index;
    int failed;

    pub void init(
        borrowed mut *self,
        borrowed ui_component_instance_t* component,
        borrowed ui_context_binding_list_t* bindings
    ) {
        memset(self, 0, sizeof(*self));
        self->component = component;
        self->bindings = bindings;
    }

    priv borrowed ui_hook_t* next_hook(borrowed mut *self, ui_hook_kind_t kind) {
        if (self == NULL || self->component == NULL || self->failed) return NULL;
        size_t index = self->hook_index++;

        if (index < self->component->hooks.length) {
            ui_hook_t* hook = &self->component->hooks.items[index];
            if (hook->kind != kind) {
                self->failed = 1;
                return NULL;
            }
            return hook;
        }

        if (self->component->rendered_once) {
            self->failed = 1;
            return NULL;
        }

        ui_hook_t hook;
        memset(&hook, 0, sizeof(hook));
        hook.kind = kind;
        if (self->component->hooks.add(hook) != 0) {
            self->failed = 1;
            return NULL;
        }
        return &self->component->hooks.items[self->component->hooks.length - 1];
    }

    pub borrowed ui_state_t* use_state(
        borrowed mut *self,
        borrowed const void* initial_value,
        size_t value_size
    ) {
        ui_hook_t* hook = self->next_hook(UI_HOOK_STATE);
        if (hook == NULL) return NULL;
        if (hook->as.state.bytes == NULL) {
            ui_state_t* state = &hook->as.state;
            if (state->init(initial_value, value_size, &self->component->dirty) != 0) {
                self->failed = 1;
                return NULL;
            }
        } else if (hook->as.state.size != value_size) {
            self->failed = 1;
            return NULL;
        }
        return &hook->as.state;
    }

    pub int use_effect(
        borrowed mut *self,
        ui_effect_fn effect,
        borrowed void* user,
        borrowed const void* dependencies,
        size_t dependency_size
    ) {
        ui_hook_t* hook = self->next_hook(UI_HOOK_EFFECT);
        if (hook == NULL) return 1;
        ui_effect_hook_t* effect_hook = &hook->as.effect;
        if (effect_hook->effect == NULL) {
            if (effect_hook->init(effect, user, dependencies, dependency_size) != 0) {
                self->failed = 1;
                return 1;
            }
        } else if (effect_hook->update(effect, user, dependencies, dependency_size) != 0) {
            self->failed = 1;
            return 1;
        }
        return 0;
    }

    pub borrowed const void* use_context(
        borrowed *self,
        borrowed const ui_context_t* context
    ) {
        if (self == NULL || context == NULL) return NULL;
        if (self->bindings != NULL) {
            for (size_t i = self->bindings->length; i > 0; i--) {
                borrowed const ui_context_binding_t* binding = &self->bindings->items[i - 1];
                if (binding->context == context) return binding->value;
            }
        }
        return context->default_value;
    }

    pub borrowed ui_future_t* use_future(borrowed mut *self, borrowed ui_future_t* future) {
        if (future == NULL) {
            self->failed = 1;
            return NULL;
        }
        ui_hook_t* hook = self->next_hook(UI_HOOK_FUTURE);
        if (hook == NULL) return NULL;

        if (hook->as.future != future) {
            if (hook->as.future != NULL) {
                ui_future_t* previous = hook->as.future;
                previous->unsubscribe(&self->component->dirty);
            }
            if (future->subscribe(&self->component->dirty) != 0) {
                self->failed = 1;
                return NULL;
            }
            hook->as.future = future;
        }
        return future;
    }

    pub int finish(borrowed mut *self) {
        if (self == NULL || self->component == NULL || self->failed) return 1;
        if (self->component->rendered_once && self->hook_index != self->component->hooks.length) {
            self->failed = 1;
            return 1;
        }
        self->component->rendered_once = 1;
        return 0;
    }
} ui_render_context_t;

/* ---------- timers ---------- */

typedef void (*ui_timer_callback_fn)(borrowed mut ui_runtime_t* runtime, borrowed void* user);

typedef struct ui_timer_t {
    uint64_t id;
    uint64_t due_ms;
    uint64_t interval_ms;
    ui_timer_callback_fn callback;
    borrowed void* user;
    int active;
} ui_timer_t;

comptime typedef dynamic_list(ui_timer_t) ui_timer_list_t;

/* ---------- runtime ---------- */

typedef struct ui_runtime_t {
    ui_driver_t driver;
    owned ui_vnode_t* root;
    ui_timer_list_t timers;
    uint64_t next_node_id;
    uint64_t next_timer_id;
    int running;

    static priv int same_identity(
        borrowed const ui_vnode_t* previous,
        borrowed const ui_vnode_t* next
    ) {
        if (previous == NULL || next == NULL || previous->kind != next->kind) return 0;

        borrowed const char* previous_key = previous->key_value.data == NULL ? "" : previous->key_value.data;
        borrowed const char* next_key = next->key_value.data == NULL ? "" : next->key_value.data;
        if (strcmp(previous_key, next_key) != 0) return 0;

        if (previous->kind == UI_VNODE_ELEMENT) {
            borrowed const char* previous_tag = previous->tag_value.data == NULL ? "" : previous->tag_value.data;
            borrowed const char* next_tag = next->tag_value.data == NULL ? "" : next->tag_value.data;
            return strcmp(previous_tag, next_tag) == 0;
        }
        if (previous->kind == UI_VNODE_COMPONENT) return previous->as.component.render == next->as.component.render;
        if (previous->kind == UI_VNODE_CONTEXT_PROVIDER) return previous->as.provider.context == next->as.provider.context;
        return 1;
    }

    static priv int key_claimed(
        borrowed const ui_vnode_list_t* children,
        size_t before_index,
        borrowed const ui_vnode_t* candidate
    ) {
        if (children == NULL || candidate == NULL) return 0;
        borrowed const char* candidate_key = candidate->key_value.data == NULL ? "" : candidate->key_value.data;
        if (candidate_key[0] == '\0') return 0;

        for (size_t i = 0; i < before_index && i < children->length; i++) {
            borrowed const ui_vnode_t* child = children->items[i];
            if (child == NULL) continue;
            borrowed const char* key = child->key_value.data == NULL ? "" : child->key_value.data;
            if (strcmp(key, candidate_key) == 0 && ui_runtime_t.same_identity(child, candidate)) return 1;
        }
        return 0;
    }

    priv void transfer_identity(
        borrowed mut *self,
        borrowed mut ui_vnode_t* previous,
        borrowed mut ui_vnode_t* next
    ) {
        if (!ui_runtime_t.same_identity(previous, next)) return;

        next->id = previous->id;
        if (previous->kind == UI_VNODE_COMPONENT) {
            next->as.component.instance = previous->as.component.instance;
            previous->as.component.instance = NULL;
            if (next->as.component.instance != NULL) next->as.component.instance->dirty = 1;
            next->as.component.rendered = previous->as.component.rendered;
            previous->as.component.rendered = NULL;
        }

        size_t previous_count = previous->children.length;
        for (size_t next_index = 0; next_index < next->children.length; next_index++) {
            ui_vnode_t* next_child = next->children.items[next_index];
            if (next_child == NULL) continue;

            borrowed const char* next_key = next_child->key_value.data == NULL ? "" : next_child->key_value.data;
            size_t match = (size_t)-1;

            if (next_key[0] != '\0') {
                if (ui_runtime_t.key_claimed(&next->children, next_index, next_child)) continue;

                for (size_t previous_index = 0; previous_index < previous_count; previous_index++) {
                    ui_vnode_t* previous_child = previous->children.items[previous_index];
                    if (previous_child == NULL) continue;
                    borrowed const char* previous_key = previous_child->key_value.data == NULL ? "" : previous_child->key_value.data;
                    if (strcmp(previous_key, next_key) == 0 && ui_runtime_t.same_identity(previous_child, next_child)) {
                        match = previous_index;
                        break;
                    }
                }
            } else if (next_index < previous_count) {
                ui_vnode_t* previous_child = previous->children.items[next_index];
                if (previous_child != NULL) {
                    borrowed const char* previous_key = previous_child->key_value.data == NULL ? "" : previous_child->key_value.data;
                    if (previous_key[0] == '\0' && ui_runtime_t.same_identity(previous_child, next_child)) match = next_index;
                }
            }

            if (match != (size_t)-1) self->transfer_identity(previous->children.items[match], next_child);
        }
    }

    priv void release_components(borrowed mut *self, borrowed mut ui_vnode_t* node) {
        if (node == NULL) return;
        (void)self;

        if (node->kind == UI_VNODE_COMPONENT) {
            if (node->as.component.rendered != NULL) self->release_components(node->as.component.rendered);
            if (node->as.component.instance != NULL) {
                ui_component_instance_t* instance = node->as.component.instance;
                node->as.component.instance = NULL;
                instance->destroy();
            }
        }

        for (size_t i = 0; i < node->children.length; i++) self->release_components(node->children.items[i]);
    }

    priv void dispose(borrowed mut *self, owned ui_vnode_t* node) {
        if (node == NULL) return;
        self->release_components(node);
        node->destroy();
    }

    priv int refresh_node(
        borrowed mut *self,
        borrowed mut ui_vnode_t* node,
        borrowed mut ui_context_binding_list_t* bindings,
        borrowed mut int* changed
    ) {
        if (node == NULL) return 0;

        if (node->kind == UI_VNODE_COMPONENT) {
            if (node->as.component.instance == NULL) {
                node->as.component.instance = ui_component_instance_t.create();
                if (node->as.component.instance == NULL) return 1;
            }

            ui_component_instance_t* instance = node->as.component.instance;
            if (instance->dirty || node->as.component.rendered == NULL) {
                ui_render_context_t context;
                context.init(instance, bindings);
                ui_vnode_t* next_rendered = node->as.component.render(&context, node->as.component.props);
                if (next_rendered == NULL || context.finish() != 0) {
                    if (next_rendered != NULL) next_rendered->destroy();
                    return 1;
                }

                if (node->as.component.rendered != NULL) {
                    self->transfer_identity(node->as.component.rendered, next_rendered);
                    self->dispose(node->as.component.rendered);
                }
                node->as.component.rendered = next_rendered;
                instance->dirty = 0;
                *changed = 1;
            }
            return self->refresh_node(node->as.component.rendered, bindings, changed);
        }

        if (node->kind == UI_VNODE_CONTEXT_PROVIDER) {
            ui_context_binding_t binding;
            binding.context = node->as.provider.context;
            binding.value = node->as.provider.value;
            binding.value_size = node->as.provider.value_size;
            if (bindings->add(binding) != 0) return 1;
            for (size_t i = 0; i < node->children.length; i++) {
                if (self->refresh_node(node->children.items[i], bindings, changed) != 0) {
                    bindings->length--;
                    return 1;
                }
            }
            bindings->length--;
            return 0;
        }

        if (node->kind != UI_VNODE_FRAGMENT && node->id == 0) {
            node->id = self->next_node_id++;
            *changed = 1;
        }

        for (size_t i = 0; i < node->children.length; i++) {
            if (self->refresh_node(node->children.items[i], bindings, changed) != 0) return 1;
        }
        return 0;
    }

    priv void flush_effects(borrowed mut *self, borrowed mut ui_vnode_t* node) {
        if (node == NULL) return;
        (void)self;
        if (node->kind == UI_VNODE_COMPONENT) {
            if (node->as.component.instance != NULL) {
                ui_component_instance_t* instance = node->as.component.instance;
                instance->flush_effects();
            }
            self->flush_effects(node->as.component.rendered);
        }
        for (size_t i = 0; i < node->children.length; i++) self->flush_effects(node->children.items[i]);
    }

    priv borrowed ui_vnode_t* find_node(borrowed mut *self, borrowed mut ui_vnode_t* node, uint64_t id) {
        (void)self;
        if (node == NULL) return NULL;
        if (node->id == id && id != 0) return node;
        if (node->kind == UI_VNODE_COMPONENT) {
            ui_vnode_t* found = self->find_node(node->as.component.rendered, id);
            if (found != NULL) return found;
        }
        for (size_t i = 0; i < node->children.length; i++) {
            ui_vnode_t* found = self->find_node(node->children.items[i], id);
            if (found != NULL) return found;
        }
        return NULL;
    }

    priv int process_timers(borrowed mut *self) {
        uint64_t now = self->driver.now();
        for (size_t i = 0; i < self->timers.length; i++) {
            ui_timer_t* timer = &self->timers.items[i];
            if (!timer->active || timer->callback == NULL || now < timer->due_ms) continue;

            timer->callback(self, timer->user);
            if (timer->interval_ms == 0) {
                timer->active = 0;
            } else {
                do timer->due_ms += timer->interval_ms;
                while (timer->due_ms <= now);
            }
        }
        return 0;
    }

    priv uint64_t wait_timeout(borrowed *self) {
        uint64_t now = self->driver.now();
        uint64_t timeout = 16;
        for (size_t i = 0; i < self->timers.length; i++) {
            borrowed const ui_timer_t* timer = &self->timers.items[i];
            if (!timer->active) continue;
            if (timer->due_ms <= now) return 0;
            uint64_t remaining = timer->due_ms - now;
            if (remaining < timeout) timeout = remaining;
        }
        return timeout;
    }

    pub int init(borrowed mut *self, ui_driver_t driver) {
        if (self == NULL) return 1;
        memset(self, 0, sizeof(*self));
        self->driver = driver;
        self->next_node_id = 1;
        self->next_timer_id = 1;
        return self->timers.init();
    }

    pub int mount(borrowed mut *self, owned ui_vnode_t* root) {
        if (self == NULL || root == NULL) return 1;
        if (self->root != NULL) self->dispose(self->root);
        self->root = root;

        ui_context_binding_list_t bindings;
        if (bindings.init() != 0) return 1;
        int changed = 1;
        int error = self->refresh_node(self->root, &bindings, &changed);
        bindings.destroy();
        if (error != 0) return error;

        error = self->driver.render(self->root);
        if (error == 0) self->flush_effects(self->root);
        return error;
    }

    pub int update(borrowed mut *self, owned ui_vnode_t* next_root) {
        if (self == NULL || next_root == NULL) return 1;
        if (self->root == NULL) return self->mount(next_root);

        self->transfer_identity(self->root, next_root);
        self->dispose(self->root);
        self->root = next_root;

        ui_context_binding_list_t bindings;
        if (bindings.init() != 0) return 1;
        int changed = 1;
        int error = self->refresh_node(self->root, &bindings, &changed);
        bindings.destroy();
        if (error != 0) return error;

        error = self->driver.render(self->root);
        if (error == 0) self->flush_effects(self->root);
        return error;
    }

    pub int dispatch(borrowed mut *self, borrowed const ui_event_t* event) {
        if (self == NULL || event == NULL) return 1;
        if (event->type == UI_EVENT_QUIT) {
            self->running = 0;
            return 0;
        }
        if (event->target_id == 0 || self->root == NULL) return 0;

        ui_vnode_t* target = self->find_node(self->root, event->target_id);
        if (target == NULL) return 0;

        for (size_t i = 0; i < target->attrs.length; i++) {
            ui_value_t* value = &target->attrs.items[i].value;
            if (value->kind != UI_VALUE_HANDLER) continue;
            if (value->as.handler.type != event->type || value->as.handler.callback == NULL) continue;
            int error = value->as.handler.callback(event, value->as.handler.user);
            if (error != 0) return error;
        }
        return 0;
    }

    pub uint64_t set_timeout(
        borrowed mut *self,
        uint64_t delay_ms,
        ui_timer_callback_fn callback,
        borrowed void* user
    ) {
        if (self == NULL || callback == NULL) return 0;
        ui_timer_t timer;
        memset(&timer, 0, sizeof(timer));
        timer.id = self->next_timer_id++;
        timer.due_ms = self->driver.now() + delay_ms;
        timer.callback = callback;
        timer.user = user;
        timer.active = 1;
        return self->timers.add(timer) == 0 ? timer.id : 0;
    }

    pub uint64_t set_interval(
        borrowed mut *self,
        uint64_t interval_ms,
        ui_timer_callback_fn callback,
        borrowed void* user
    ) {
        if (self == NULL || callback == NULL || interval_ms == 0) return 0;
        ui_timer_t timer;
        memset(&timer, 0, sizeof(timer));
        timer.id = self->next_timer_id++;
        timer.due_ms = self->driver.now() + interval_ms;
        timer.interval_ms = interval_ms;
        timer.callback = callback;
        timer.user = user;
        timer.active = 1;
        return self->timers.add(timer) == 0 ? timer.id : 0;
    }

    pub int cancel_timer(borrowed mut *self, uint64_t id) {
        if (self == NULL || id == 0) return 1;
        for (size_t i = 0; i < self->timers.length; i++) {
            if (self->timers.items[i].id == id && self->timers.items[i].active) {
                self->timers.items[i].active = 0;
                return 0;
            }
        }
        return 1;
    }

    pub int tick(borrowed mut *self) {
        if (self == NULL) return 1;

        ui_event_t event;
        for (;;) {
            memset(&event, 0, sizeof(event));
            int polled = self->driver.poll(&event);
            if (polled < 0) return 1;
            if (polled == 0) break;
            if (self->dispatch(&event) != 0) return 1;
        }

        if (self->process_timers() != 0) return 1;
        if (self->root == NULL) return 0;

        ui_context_binding_list_t bindings;
        if (bindings.init() != 0) return 1;
        int changed = 0;
        int error = self->refresh_node(self->root, &bindings, &changed);
        bindings.destroy();
        if (error != 0) return error;

        if (changed) {
            error = self->driver.render(self->root);
            if (error == 0) self->flush_effects(self->root);
        }
        return error;
    }

    pub int run(borrowed mut *self) {
        if (self == NULL) return 1;
        self->running = 1;
        while (self->running) {
            if (self->tick() != 0) return 1;
            if (!self->running) break;
            if (self->driver.wait(self->wait_timeout()) != 0) return 1;
        }
        return 0;
    }

    pub void stop(borrowed mut *self) {
        if (self != NULL) self->running = 0;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        if (self->root != NULL) self->dispose(self->root);
        self->root = NULL;
        self->timers.destroy();
        memset(self, 0, sizeof(*self));
    }
} ui_runtime_t;

#endif
