#include <stdio.h>
#include <string.h>

comptime import "stdlib:/ui/react.cp";

typedef struct fake_ui_t {
    int begin_count;
    int end_count;
    int box_count;
    int image_count;
    char last_text[128];
    uint64_t last_node_id;
    ui_event_t events[16];
    size_t event_count;
    size_t event_index;
    uint64_t now;
    double width;
    double height;
} fake_ui_t;

int fake_begin(void* instance, const ui_layout_result_t* layout) {
    (void)layout;
    fake_ui_t* fake = (fake_ui_t*)instance;
    fake->begin_count++;
    fake->box_count = 0;
    fake->image_count = 0;
    fake->last_text[0] = '\0';
    return 0;
}

int fake_box(void* instance, const ui_drawable_box_t* box) {
    fake_ui_t* fake = (fake_ui_t*)instance;
    fake->box_count++;
    fake->last_node_id = box->node_id;
    if (box->text != NULL) snprintf(fake->last_text, sizeof(fake->last_text), "%s", box->text);
    return 0;
}

int fake_image(void* instance, const ui_drawable_image_t* image) {
    (void)image;
    fake_ui_t* fake = (fake_ui_t*)instance;
    fake->image_count++;
    return 0;
}

int fake_end(void* instance, const ui_layout_result_t* layout) {
    (void)layout;
    fake_ui_t* fake = (fake_ui_t*)instance;
    fake->end_count++;
    return 0;
}

int fake_poll(void* instance, ui_event_t* event) {
    fake_ui_t* fake = (fake_ui_t*)instance;
    if (fake->event_index >= fake->event_count) return 0;
    *event = fake->events[fake->event_index++];
    return 1;
}

uint64_t fake_now(void* instance) {
    return ((fake_ui_t*)instance)->now;
}

int fake_wait(void* instance, uint64_t timeout_ms) {
    (void)instance;
    (void)timeout_ms;
    return 0;
}

int fake_viewport(void* instance, double* width, double* height) {
    fake_ui_t* fake = (fake_ui_t*)instance;
    *width = fake->width;
    *height = fake->height;
    return 0;
}

ui_driver_t fake_driver(fake_ui_t* fake) {
    ui_renderer_t renderer;
    memset(&renderer, 0, sizeof(renderer));
    renderer.init(fake, fake_begin, fake_box, fake_image, fake_end);

    ui_driver_t driver;
    memset(&driver, 0, sizeof(driver));
    driver.init(renderer, fake, fake_poll, fake_now, fake_wait, fake_viewport, 800, 600);
    return driver;
}

@test "runtime mount expands components and commits through renderer" {
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;

    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);
    ui_vnode_t* root = ui_vnode_t.text("hello");
    @assert(runtime.mount(root) == 0);
    @assert(root->id != 0);
    @assert(fake.begin_count == 1);
    @assert(fake.end_count == 1);
    @assert(fake.box_count == 1);
    @assert(strcmp(fake.last_text, "hello") == 0);
    runtime.destroy();
}

ui_state_t* counter_state = NULL;
ui_vnode_t* counter_component(ui_render_context_t* context, const void* props) {
    (void)props;
    int initial = 0;
    counter_state = context->use_state(&initial, sizeof(initial));
    if (counter_state == NULL) return NULL;
    int* value = (int*)counter_state->value();
    char text[32];
    snprintf(text, sizeof(text), "%d", value == NULL ? -1 : *value);
    return ui_vnode_t.text(text);
}

@test "use_state rerenders component and preserves host identity" {
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);

    ui_vnode_t* root = ui_vnode_t.component(counter_component, NULL, 0);
    @assert(runtime.mount(root) == 0);
    @assert(counter_state != NULL);
    uint64_t node_id = root->as.component.rendered->id;
    int frames = fake.begin_count;

    int next = 7;
    @assert(counter_state->set(&next, sizeof(next)) == 0);
    @assert(runtime.tick() == 0);
    @assert(fake.begin_count == frames + 1);
    @assert(strcmp(fake.last_text, "7") == 0);
    @assert(root->as.component.rendered->id == node_id);

    runtime.destroy();
    counter_state = NULL;
}

int effect_runs = 0;
int effect_cleanups = 0;
int effect_dependency = 1;

void effect_cleanup(void* user) {
    (void)user;
    effect_cleanups++;
}

ui_cleanup_fn effect_body(void* user) {
    (void)user;
    effect_runs++;
    return effect_cleanup;
}

ui_vnode_t* effect_component(ui_render_context_t* context, const void* props) {
    (void)props;
    if (context->use_effect(effect_body, NULL, &effect_dependency, sizeof(effect_dependency)) != 0) return NULL;
    return ui_vnode_t.text("effect");
}

@test "use_effect commits after render and cleans changed dependency" {
    effect_runs = 0;
    effect_cleanups = 0;
    effect_dependency = 1;
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);

    ui_vnode_t* root = ui_vnode_t.component(effect_component, NULL, 0);
    @assert(runtime.mount(root) == 0);
    @assert(effect_runs == 1);
    @assert(effect_cleanups == 0);

    root->as.component.instance->dirty = 1;
    @assert(runtime.tick() == 0);
    @assert(effect_runs == 1);
    @assert(effect_cleanups == 0);

    effect_dependency = 2;
    root->as.component.instance->dirty = 1;
    @assert(runtime.tick() == 0);
    @assert(effect_runs == 2);
    @assert(effect_cleanups == 1);

    runtime.destroy();
    @assert(effect_cleanups == 2);
}

ui_context_t number_context;
int observed_context = 0;

ui_vnode_t* context_reader(ui_render_context_t* context, const void* props) {
    (void)props;
    const int* value = (const int*)context->use_context(&number_context);
    if (value == NULL) return NULL;
    observed_context = *value;
    return ui_vnode_t.text("context");
}

@test "use_context reads nearest provider" {
    int default_value = 3;
    int provided = 42;
    observed_context = 0;
    @assert(number_context.init("number", &default_value, sizeof(default_value)) == 0);

    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);

    ui_vnode_t* root = ui_vnode_t.provider(&number_context, &provided, sizeof(provided));
    ui_vnode_t* reader = ui_vnode_t.component(context_reader, NULL, 0);
    @assert(root->child(reader) == 0);
    @assert(runtime.mount(root) == 0);
    @assert(observed_context == 42);

    runtime.destroy();
    number_context.destroy();
}

ui_future_t* watched_future = NULL;
int future_renders = 0;

ui_vnode_t* future_component(ui_render_context_t* context, const void* props) {
    (void)props;
    future_renders++;
    ui_future_t* future = context->use_future(watched_future);
    if (future == NULL) return NULL;
    return future->status == UI_FUTURE_RESOLVED ? ui_vnode_t.text("resolved") : ui_vnode_t.text("pending");
}

@test "future resolution invalidates subscribed component" {
    ui_future_t future;
    @assert(future.init() == 0);
    watched_future = &future;
    future_renders = 0;

    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);
    ui_vnode_t* root = ui_vnode_t.component(future_component, NULL, 0);
    @assert(runtime.mount(root) == 0);
    @assert(future_renders == 1);
    @assert(strcmp(fake.last_text, "pending") == 0);

    int payload = 99;
    @assert(future.resolve(&payload, sizeof(payload)) == 0);
    @assert(runtime.tick() == 0);
    @assert(future_renders == 2);
    @assert(strcmp(fake.last_text, "resolved") == 0);
    @assert(*(const int*)future.get() == 99);

    runtime.destroy();
    future.destroy();
    watched_future = NULL;
}

int clicks = 0;
int click_handler(const ui_event_t* event, void* user) {
    (void)event;
    (*(int*)user)++;
    return 0;
}

@test "driver events dispatch through vnode handlers" {
    clicks = 0;
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);

    ui_vnode_t* root = ui_vnode_t.element("button");
    @assert(root->attr("onClick", ui_value_t.handler(UI_EVENT_MOUSE_UP, click_handler, &clicks)) == 0);
    @assert(runtime.mount(root) == 0);

    fake.events[0].type = UI_EVENT_MOUSE_UP;
    fake.events[0].target_id = root->id;
    fake.event_count = 1;
    @assert(runtime.tick() == 0);
    @assert(clicks == 1);

    runtime.destroy();
}

int timer_hits = 0;
void timer_callback(ui_runtime_t* runtime, void* user) {
    (void)runtime;
    (*(int*)user)++;
}

@test "timeouts and intervals use driver time" {
    timer_hits = 0;
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);

    @assert(runtime.set_timeout(10, timer_callback, &timer_hits) != 0);
    uint64_t interval = runtime.set_interval(5, timer_callback, &timer_hits);
    @assert(interval != 0);

    fake.now = 4;
    @assert(runtime.tick() == 0);
    @assert(timer_hits == 0);
    fake.now = 5;
    @assert(runtime.tick() == 0);
    @assert(timer_hits == 1);
    fake.now = 10;
    @assert(runtime.tick() == 0);
    @assert(timer_hits == 3);
    @assert(runtime.cancel_timer(interval) == 0);
    fake.now = 20;
    @assert(runtime.tick() == 0);
    @assert(timer_hits == 3);

    runtime.destroy();
}

@test "keyed update preserves node identities" {
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);

    ui_vnode_t* first = ui_vnode_t.element("list");
    ui_vnode_t* a = ui_vnode_t.element("item");
    ui_vnode_t* b = ui_vnode_t.element("item");
    @assert(a->key("a") == 0);
    @assert(b->key("b") == 0);
    first->child(a);
    first->child(b);
    @assert(runtime.mount(first) == 0);
    uint64_t a_id = a->id;
    uint64_t b_id = b->id;

    ui_vnode_t* second = ui_vnode_t.element("list");
    ui_vnode_t* b2 = ui_vnode_t.element("item");
    ui_vnode_t* a2 = ui_vnode_t.element("item");
    b2->key("b");
    a2->key("a");
    second->child(b2);
    second->child(a2);
    @assert(runtime.update(second) == 0);
    @assert(b2->id == b_id);
    @assert(a2->id == a_id);

    runtime.destroy();
}

typedef struct text_props_t { const char* text; } text_props_t;
ui_vnode_t* props_component(ui_render_context_t* context, const void* props) {
    (void)context;
    const text_props_t* typed = (const text_props_t*)props;
    return ui_vnode_t.text(typed->text);
}

@test "component update rerenders with new props while preserving host id" {
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);

    text_props_t first_props = {"one"};
    ui_vnode_t* first = ui_vnode_t.component(props_component, &first_props, sizeof(first_props));
    @assert(runtime.mount(first) == 0);
    uint64_t text_id = first->as.component.rendered->id;

    text_props_t second_props = {"two"};
    ui_vnode_t* second = ui_vnode_t.component(props_component, &second_props, sizeof(second_props));
    @assert(runtime.update(second) == 0);
    @assert(second->as.component.rendered->id == text_id);
    @assert(strcmp(fake.last_text, "two") == 0);

    runtime.destroy();
}

int conditional_hook = 1;
ui_vnode_t* invalid_hook_component(ui_render_context_t* context, const void* props) {
    (void)props;
    int one = 1;
    int two = 2;
    if (context->use_state(&one, sizeof(one)) == NULL) return NULL;
    if (conditional_hook && context->use_state(&two, sizeof(two)) == NULL) return NULL;
    return ui_vnode_t.text("hooks");
}

@test "hook order changes are rejected" {
    conditional_hook = 1;
    fake_ui_t fake;
    memset(&fake, 0, sizeof(fake));
    fake.width = 800;
    fake.height = 600;
    ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&fake)) == 0);
    ui_vnode_t* root = ui_vnode_t.component(invalid_hook_component, NULL, 0);
    @assert(runtime.mount(root) == 0);

    conditional_hook = 0;
    root->as.component.instance->dirty = 1;
    @assert(runtime.tick() != 0);

    runtime.destroy();
}
