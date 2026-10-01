#include <stdio.h>
#include <string.h>
comptime import "stdlib:/ui/react.cp";

typedef struct fake_driver_state_t {
    ui_patch_t patches[256];
    char text_copies[256][64];
    char name_copies[256][64];
    size_t patch_count;
    ui_event_t events[16];
    size_t event_count;
    size_t event_cursor;
    uint64_t now;
    int begin_count;
    int end_count;
} fake_driver_state_t;

int fake_begin(void* user) { ((fake_driver_state_t*)user)->begin_count++; return 0; }
int fake_end(void* user) { ((fake_driver_state_t*)user)->end_count++; return 0; }
int fake_apply(void* user, const ui_patch_t* patch) {
    fake_driver_state_t* state = user;
    if (state->patch_count >= 256) return 1;
    size_t i = state->patch_count++;
    state->patches[i] = *patch;
    if (patch->text) { snprintf(state->text_copies[i],64,"%s",patch->text); state->patches[i].text = state->text_copies[i]; }
    if (patch->name) { snprintf(state->name_copies[i],64,"%s",patch->name); state->patches[i].name = state->name_copies[i]; }
    return 0;
}
int fake_poll(void* user, ui_event_t* out) {
    fake_driver_state_t* state = user;
    if (state->event_cursor >= state->event_count) return 0;
    *out = state->events[state->event_cursor++]; return 1;
}
uint64_t fake_now(void* user) { return ((fake_driver_state_t*)user)->now; }
int fake_wait(void* user, uint64_t timeout) { (void)user; (void)timeout; return 0; }

ui_driver_t fake_driver(fake_driver_state_t* state) {
    ui_driver_t d; memset(&d,0,sizeof(d)); d.user=state; d.begin_frame=fake_begin; d.apply_patch=fake_apply; d.end_frame=fake_end; d.poll_event=fake_poll; d.now_ms=fake_now; d.wait=fake_wait; return d;
}

@test "vnode children use dynamic list add by value" {
    ui_vnode_t* parent = ui_vnode_t.element("panel");
    ui_vnode_t* child = ui_vnode_t.text_node("hello");
    @assert(parent != NULL && child != NULL);
    ui_vnode_list_t* children = &parent->children;
    @assert(children->add(child) == 0);
    @assert(children->size() == 1);
    @assert(*children->get(0) == child);
    parent->destroy();
}

@test "mount emits backend neutral patches" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(runtime.init( fake_driver(&f)) == 0);
    ui_vnode_t* root = ui_vnode_t.element("box"); ui_vnode_t* text = ui_vnode_t.text_node("hello");
    @assert(UI_ATTR(root,"role",ui_value_t.string("main")) == 0);
    @assert(UI_CHILD(root,text) == 0);
    @assert(runtime.mount(root) == 0);
    @assert(f.begin_count == 1 && f.end_count == 1);
    @assert(f.patch_count == 3);
    @assert(f.patches[0].type == UI_PATCH_MOUNT && strcmp(f.patches[0].tag,"box") == 0);
    @assert(f.patches[1].type == UI_PATCH_SET_ATTRIBUTE && strcmp(f.patches[1].name,"role") == 0);
    @assert(f.patches[2].type == UI_PATCH_MOUNT && strcmp(f.patches[2].text,"hello") == 0);
    runtime.destroy();
}

ui_state_t* counter_state = NULL;
ui_vnode_t* counter_component(ui_render_context_t* context, const void* props) {
    (void)props;
    counter_state = use_state(context, int, 0);
    if (counter_state == NULL) return NULL;
    char value[32]; snprintf(value,sizeof(value),"%d",state_value(int,counter_state));
    return ui_vnode_t.text_node(value);
}

@test "use_state schedules reconciliation" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* root = ui_vnode_t.component(counter_component, NULL, 0);
    @assert(runtime.mount(root) == 0);
    size_t before=f.patch_count;
    @assert(state_set(int,counter_state,7) == 0);
    @assert(runtime.tick() == 0);
    @assert(f.patch_count == before + 1);
    @assert(f.patches[f.patch_count-1].type == UI_PATCH_SET_TEXT);
    @assert(strcmp(f.patches[f.patch_count-1].text,"7") == 0);
    runtime.destroy(); counter_state=NULL;
}

int effect_runs=0; int effect_cleanups=0; int effect_dep=1;
void effect_cleanup(void* user) { (void)user; effect_cleanups++; }
ui_cleanup_fn test_effect(void* user) { (void)user; effect_runs++; return effect_cleanup; }
ui_vnode_t* effect_component(ui_render_context_t* context, const void* props) {
    (void)props;
    if (use_effect(context,test_effect,NULL,&effect_dep,sizeof(effect_dep)) != 0) return NULL;
    return ui_vnode_t.text_node("effect");
}

@test "use_effect runs after commit and cleans on dependency change" {
    effect_runs=0; effect_cleanups=0; effect_dep=1;
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* root = ui_vnode_t.component(effect_component, NULL, 0);
    @assert(runtime.mount(root) == 0);
    @assert(effect_runs == 1 && effect_cleanups == 0);
    root->as.component.instance->dirty=1; runtime.dirty=1;
    @assert(runtime.tick() == 0);
    @assert(effect_runs == 1);
    effect_dep=2; root->as.component.instance->dirty=1; runtime.dirty=1;
    @assert(runtime.tick() == 0);
    @assert(effect_runs == 2 && effect_cleanups == 1);
    runtime.destroy();
    @assert(effect_cleanups == 2);
}

ui_context_t number_context;
int observed_context=0;
ui_vnode_t* context_reader(ui_render_context_t* context, const void* props) {
    (void)props;
    const int* value = use_context(context,int,&number_context);
    if (value == NULL) return NULL;
    observed_context=*value;
    return ui_vnode_t.text_node("context");
}

@test "context provider scopes component reads" {
    int def=3, provided=42; observed_context=0;
    @assert(number_context.init("number",&def,sizeof(def)) == 0);
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* root = ui_vnode_t.provider(&number_context, &provided, sizeof(provided)); ui_vnode_t* reader = ui_vnode_t.component(context_reader, NULL, 0);
    @assert(UI_CHILD(root,reader) == 0);
    @assert(runtime.mount(root) == 0);
    @assert(observed_context == 42);
    runtime.destroy(); number_context.destroy();
}

int future_renders=0; ui_future_t* watched_future=NULL;
ui_vnode_t* future_component(ui_render_context_t* context, const void* props) {
    (void)props; future_renders++;
    ui_future_t* future=use_future(context,watched_future);
    if (future == NULL) return NULL;
    if (future->status == UI_FUTURE_RESOLVED) return ui_vnode_t.text_node("resolved");
    return ui_vnode_t.text_node("pending");
}

@test "future completion wakes subscribed component" {
    ui_future_t future; @assert(future.init() == 0); watched_future=&future; future_renders=0;
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* root = ui_vnode_t.component(future_component, NULL, 0); @assert(runtime.mount(root) == 0); @assert(future_renders == 1);
    int payload=99; @assert(future.resolve(&payload,sizeof(payload)) == 0); @assert(runtime.tick() == 0);
    @assert(future_renders == 2); @assert(strcmp(f.patches[f.patch_count-1].text,"resolved") == 0);
    runtime.destroy(); future.destroy(); watched_future=NULL;
}

int clicks=0;
int click_handler(const ui_event_t* event, void* user) { (void)event; (*(int*)user)++; return 0; }
@test "driver events dispatch to vnode handlers" {
    clicks=0; fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* root = ui_vnode_t.element("button"); @assert(UI_ATTR(root,"onClick",ui_value_t.handler(UI_EVENT_MOUSE_UP,click_handler,&clicks)) == 0); @assert(runtime.mount(root) == 0);
    f.events[0].type=UI_EVENT_MOUSE_UP; f.events[0].target_id=root->id; f.event_count=1;
    @assert(runtime.tick() == 0); @assert(clicks == 1);
    runtime.destroy();
}

int timer_hits=0;
void timer_callback(ui_runtime_t* runtime, void* user) { (void)runtime; (*(int*)user)++; }
@test "timeouts and intervals use driver clock" {
    timer_hits=0; fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(runtime.init(fake_driver(&f)) == 0);
    @assert(runtime.set_timeout(10,timer_callback,&timer_hits) != 0);
    uint64_t interval=runtime.set_interval(5,timer_callback,&timer_hits); @assert(interval != 0);
    f.now=4; @assert(runtime.tick() == 0); @assert(timer_hits == 0);
    f.now=5; @assert(runtime.tick() == 0); @assert(timer_hits == 1);
    f.now=10; @assert(runtime.tick() == 0); @assert(timer_hits == 3);
    @assert(runtime.cancel_timer(interval) == 0); f.now=20; @assert(runtime.tick() == 0); @assert(timer_hits == 3);
    runtime.destroy();
}

@test "root reconciliation updates text without remount" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* first = ui_vnode_t.element("label"); ui_vnode_t* t1 = ui_vnode_t.text_node("old"); @assert(UI_CHILD(first,t1)==0); @assert(runtime.mount(first)==0);
    size_t before=f.patch_count; ui_vnode_t* second = ui_vnode_t.element("label"); ui_vnode_t* t2 = ui_vnode_t.text_node("new"); @assert(UI_CHILD(second,t2)==0); @assert(runtime.update_root(second)==0);
    @assert(f.patch_count == before + 1); @assert(f.patches[f.patch_count-1].type == UI_PATCH_SET_TEXT); @assert(strcmp(f.patches[f.patch_count-1].text,"new") == 0);
    runtime.destroy();
}

@test "keyed children preserve identity and emit move patches" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* first = ui_vnode_t.element("list"); ui_vnode_t* a = ui_vnode_t.element("item"); ui_vnode_t* b = ui_vnode_t.element("item");
    @assert(a->key_set("a") == 0); @assert(b->key_set("b") == 0); @assert(UI_CHILD(first,a)==0); @assert(UI_CHILD(first,b)==0); @assert(runtime.mount(first)==0);
    uint64_t aid=a->id, bid=b->id; size_t before=f.patch_count;
    ui_vnode_t* second = ui_vnode_t.element("list"); ui_vnode_t* b2 = ui_vnode_t.element("item"); ui_vnode_t* a2 = ui_vnode_t.element("item");
    @assert(b2->key_set("b") == 0); @assert(a2->key_set("a") == 0); @assert(UI_CHILD(second,b2)==0); @assert(UI_CHILD(second,a2)==0);
    @assert(runtime.update_root(second)==0);
    @assert(b2->id == bid && a2->id == aid);
    int moves=0; for(size_t i=before;i<f.patch_count;i++) if(f.patches[i].type==UI_PATCH_MOVE) moves++;
    @assert(moves == 2);
    runtime.destroy();
}

int conditional_hooks=1;
ui_vnode_t* invalid_hooks_component(ui_render_context_t* context, const void* props) {
    (void)props; ui_state_t* first=use_state(context,int,1); if(first==NULL) return NULL;
    if(conditional_hooks) { ui_state_t* second=use_state(context,int,2); if(second==NULL) return NULL; }
    return ui_vnode_t.text_node("hooks");
}
@test "hook order changes are rejected" {
    conditional_hooks=1; fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(runtime.init(fake_driver(&f)) == 0);
    ui_vnode_t* root = ui_vnode_t.component(invalid_hooks_component, NULL, 0); @assert(runtime.mount(root)==0);
    conditional_hooks=0; root->as.component.instance->dirty=1; runtime.dirty=1;
    @assert(runtime.tick() != 0);
    runtime.destroy();
}
