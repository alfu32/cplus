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
    UI_ELEMENT(parent, "panel");
    UI_TEXT(child, "hello");
    @assert(parent != NULL && child != NULL);
    ui_vnode_list_t* children = &parent->children;
    @assert(children->add(child) == 0);
    @assert(children->size() == 1);
    @assert(*children->get(0) == child);
    ui_vnode_destroy(parent);
}

@test "mount emits backend neutral patches" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(ui_runtime_init(&runtime, fake_driver(&f)) == 0);
    UI_ELEMENT(root, "box"); UI_TEXT(text, "hello");
    @assert(UI_ATTR(root,"role",ui_value_string("main")) == 0);
    @assert(UI_CHILD(root,text) == 0);
    @assert(ui_runtime_mount(&runtime,root) == 0);
    @assert(f.begin_count == 1 && f.end_count == 1);
    @assert(f.patch_count == 3);
    @assert(f.patches[0].type == UI_PATCH_MOUNT && strcmp(f.patches[0].tag,"box") == 0);
    @assert(f.patches[1].type == UI_PATCH_SET_ATTRIBUTE && strcmp(f.patches[1].name,"role") == 0);
    @assert(f.patches[2].type == UI_PATCH_MOUNT && strcmp(f.patches[2].text,"hello") == 0);
    ui_runtime_destroy(&runtime);
}

ui_state_t* counter_state = NULL;
ui_vnode_t* counter_component(ui_render_context_t* context, const void* props) {
    (void)props;
    counter_state = use_state(context, int, 0);
    if (counter_state == NULL) return NULL;
    char value[32]; snprintf(value,sizeof(value),"%d",state_value(int,counter_state));
    return ui_vnode_text(value);
}

@test "use_state schedules reconciliation" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_COMPONENT(root,counter_component,NULL,0);
    @assert(ui_runtime_mount(&runtime,root) == 0);
    size_t before=f.patch_count;
    @assert(state_set(int,counter_state,7) == 0);
    @assert(ui_runtime_tick(&runtime) == 0);
    @assert(f.patch_count == before + 1);
    @assert(f.patches[f.patch_count-1].type == UI_PATCH_SET_TEXT);
    @assert(strcmp(f.patches[f.patch_count-1].text,"7") == 0);
    ui_runtime_destroy(&runtime); counter_state=NULL;
}

int effect_runs=0; int effect_cleanups=0; int effect_dep=1;
void effect_cleanup(void* user) { (void)user; effect_cleanups++; }
ui_cleanup_fn test_effect(void* user) { (void)user; effect_runs++; return effect_cleanup; }
ui_vnode_t* effect_component(ui_render_context_t* context, const void* props) {
    (void)props;
    if (use_effect(context,test_effect,NULL,&effect_dep,sizeof(effect_dep)) != 0) return NULL;
    return ui_vnode_text("effect");
}

@test "use_effect runs after commit and cleans on dependency change" {
    effect_runs=0; effect_cleanups=0; effect_dep=1;
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_COMPONENT(root,effect_component,NULL,0);
    @assert(ui_runtime_mount(&runtime,root) == 0);
    @assert(effect_runs == 1 && effect_cleanups == 0);
    root->as.component.instance->dirty=1; runtime.dirty=1;
    @assert(ui_runtime_tick(&runtime) == 0);
    @assert(effect_runs == 1);
    effect_dep=2; root->as.component.instance->dirty=1; runtime.dirty=1;
    @assert(ui_runtime_tick(&runtime) == 0);
    @assert(effect_runs == 2 && effect_cleanups == 1);
    ui_runtime_destroy(&runtime);
    @assert(effect_cleanups == 2);
}

ui_context_t number_context;
int observed_context=0;
ui_vnode_t* context_reader(ui_render_context_t* context, const void* props) {
    (void)props;
    const int* value = use_context(context,int,&number_context);
    if (value == NULL) return NULL;
    observed_context=*value;
    return ui_vnode_text("context");
}

@test "context provider scopes component reads" {
    int def=3, provided=42; observed_context=0;
    @assert(ui_context_init(&number_context,"number",&def,sizeof(def)) == 0);
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime;
    @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_PROVIDER(root,&number_context,&provided,sizeof(provided)); UI_COMPONENT(reader,context_reader,NULL,0);
    @assert(UI_CHILD(root,reader) == 0);
    @assert(ui_runtime_mount(&runtime,root) == 0);
    @assert(observed_context == 42);
    ui_runtime_destroy(&runtime); ui_context_destroy(&number_context);
}

int future_renders=0; ui_future_t* watched_future=NULL;
ui_vnode_t* future_component(ui_render_context_t* context, const void* props) {
    (void)props; future_renders++;
    ui_future_t* future=use_future(context,watched_future);
    if (future == NULL) return NULL;
    if (future->status == UI_FUTURE_RESOLVED) return ui_vnode_text("resolved");
    return ui_vnode_text("pending");
}

@test "future completion wakes subscribed component" {
    ui_future_t future; @assert(ui_future_init(&future) == 0); watched_future=&future; future_renders=0;
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_COMPONENT(root,future_component,NULL,0); @assert(ui_runtime_mount(&runtime,root) == 0); @assert(future_renders == 1);
    int payload=99; @assert(ui_future_resolve(&future,&payload,sizeof(payload)) == 0); @assert(ui_runtime_tick(&runtime) == 0);
    @assert(future_renders == 2); @assert(strcmp(f.patches[f.patch_count-1].text,"resolved") == 0);
    ui_runtime_destroy(&runtime); ui_future_destroy(&future); watched_future=NULL;
}

int clicks=0;
int click_handler(const ui_event_t* event, void* user) { (void)event; (*(int*)user)++; return 0; }
@test "driver events dispatch to vnode handlers" {
    clicks=0; fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_ELEMENT(root,"button"); @assert(UI_ATTR(root,"onClick",ui_value_handler(UI_EVENT_MOUSE_UP,click_handler,&clicks)) == 0); @assert(ui_runtime_mount(&runtime,root) == 0);
    f.events[0].type=UI_EVENT_MOUSE_UP; f.events[0].target_id=root->id; f.event_count=1;
    @assert(ui_runtime_tick(&runtime) == 0); @assert(clicks == 1);
    ui_runtime_destroy(&runtime);
}

int timer_hits=0;
void timer_callback(ui_runtime_t* runtime, void* user) { (void)runtime; (*(int*)user)++; }
@test "timeouts and intervals use driver clock" {
    timer_hits=0; fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    @assert(ui_set_timeout(&runtime,10,timer_callback,&timer_hits) != 0);
    uint64_t interval=ui_set_interval(&runtime,5,timer_callback,&timer_hits); @assert(interval != 0);
    f.now=4; @assert(ui_runtime_tick(&runtime) == 0); @assert(timer_hits == 0);
    f.now=5; @assert(ui_runtime_tick(&runtime) == 0); @assert(timer_hits == 1);
    f.now=10; @assert(ui_runtime_tick(&runtime) == 0); @assert(timer_hits == 3);
    @assert(ui_cancel_timer(&runtime,interval) == 0); f.now=20; @assert(ui_runtime_tick(&runtime) == 0); @assert(timer_hits == 3);
    ui_runtime_destroy(&runtime);
}

@test "root reconciliation updates text without remount" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_ELEMENT(first,"label"); UI_TEXT(t1,"old"); @assert(UI_CHILD(first,t1)==0); @assert(ui_runtime_mount(&runtime,first)==0);
    size_t before=f.patch_count; UI_ELEMENT(second,"label"); UI_TEXT(t2,"new"); @assert(UI_CHILD(second,t2)==0); @assert(ui_runtime_update_root(&runtime,second)==0);
    @assert(f.patch_count == before + 1); @assert(f.patches[f.patch_count-1].type == UI_PATCH_SET_TEXT); @assert(strcmp(f.patches[f.patch_count-1].text,"new") == 0);
    ui_runtime_destroy(&runtime);
}

@test "keyed children preserve identity and emit move patches" {
    fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_ELEMENT(first,"list"); UI_ELEMENT(a,"item"); UI_ELEMENT(b,"item");
    @assert(ui_vnode_key(a,"a") == 0); @assert(ui_vnode_key(b,"b") == 0); @assert(UI_CHILD(first,a)==0); @assert(UI_CHILD(first,b)==0); @assert(ui_runtime_mount(&runtime,first)==0);
    uint64_t aid=a->id, bid=b->id; size_t before=f.patch_count;
    UI_ELEMENT(second,"list"); UI_ELEMENT(b2,"item"); UI_ELEMENT(a2,"item");
    @assert(ui_vnode_key(b2,"b") == 0); @assert(ui_vnode_key(a2,"a") == 0); @assert(UI_CHILD(second,b2)==0); @assert(UI_CHILD(second,a2)==0);
    @assert(ui_runtime_update_root(&runtime,second)==0);
    @assert(b2->id == bid && a2->id == aid);
    int moves=0; for(size_t i=before;i<f.patch_count;i++) if(f.patches[i].type==UI_PATCH_MOVE) moves++;
    @assert(moves == 2);
    ui_runtime_destroy(&runtime);
}

int conditional_hooks=1;
ui_vnode_t* invalid_hooks_component(ui_render_context_t* context, const void* props) {
    (void)props; ui_state_t* first=use_state(context,int,1); if(first==NULL) return NULL;
    if(conditional_hooks) { ui_state_t* second=use_state(context,int,2); if(second==NULL) return NULL; }
    return ui_vnode_text("hooks");
}
@test "hook order changes are rejected" {
    conditional_hooks=1; fake_driver_state_t f; memset(&f,0,sizeof(f)); ui_runtime_t runtime; @assert(ui_runtime_init(&runtime,fake_driver(&f)) == 0);
    UI_COMPONENT(root,invalid_hooks_component,NULL,0); @assert(ui_runtime_mount(&runtime,root)==0);
    conditional_hooks=0; root->as.component.instance->dirty=1; runtime.dirty=1;
    @assert(ui_runtime_tick(&runtime) != 0);
    ui_runtime_destroy(&runtime);
}
