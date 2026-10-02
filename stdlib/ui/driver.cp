#ifndef CPLUS_UI_DRIVER_CP
#define CPLUS_UI_DRIVER_CP

#include <stdint.h>

comptime import "stdlib:/ui/renderer.cp";

typedef int (*ui_driver_poll_fn)(borrowed void* instance, borrowed mut ui_event_t* event);
typedef uint64_t (*ui_driver_now_fn)(borrowed void* instance);
typedef int (*ui_driver_wait_fn)(borrowed void* instance, uint64_t timeout_ms);
typedef int (*ui_driver_viewport_fn)(borrowed void* instance, borrowed mut double* width, borrowed mut double* height);

typedef struct ui_driver_t {
    ui_renderer_t renderer;
    borrowed void* instance;
    ui_driver_poll_fn poll_event;
    ui_driver_now_fn now_ms;
    ui_driver_wait_fn wait_event;
    ui_driver_viewport_fn viewport;
    double default_width;
    double default_height;

    pub int init(
        borrowed mut *self,
        ui_renderer_t renderer,
        borrowed void* instance,
        ui_driver_poll_fn poll_event,
        ui_driver_now_fn now_ms,
        ui_driver_wait_fn wait_event,
        ui_driver_viewport_fn viewport,
        double default_width,
        double default_height
    ) {
        if (self == NULL || renderer.draw_box == NULL || renderer.draw_image == NULL) return 1;
        self->renderer = renderer;
        self->instance = instance;
        self->poll_event = poll_event;
        self->now_ms = now_ms;
        self->wait_event = wait_event;
        self->viewport = viewport;
        self->default_width = default_width;
        self->default_height = default_height;
        return 0;
    }

    pub int poll(borrowed *self, borrowed mut ui_event_t* event) {
        if (self == NULL || event == NULL || self->poll_event == NULL) return 0;
        return self->poll_event(self->instance, event);
    }

    pub uint64_t now(borrowed *self) {
        if (self == NULL || self->now_ms == NULL) return 0;
        return self->now_ms(self->instance);
    }

    pub int wait(borrowed *self, uint64_t timeout_ms) {
        if (self == NULL || self->wait_event == NULL) return 0;
        return self->wait_event(self->instance, timeout_ms);
    }

    pub int size(borrowed *self, borrowed mut double* width, borrowed mut double* height) {
        if (self == NULL || width == NULL || height == NULL) return 1;
        *width = self->default_width;
        *height = self->default_height;
        if (self->viewport != NULL) return self->viewport(self->instance, width, height);
        return 0;
    }

    pub int render(borrowed *self, borrowed const ui_vnode_t* root) {
        if (self == NULL || root == NULL) return 1;
        double width = 0.0;
        double height = 0.0;
        if (self->size(&width, &height) != 0) return 1;
        return self->renderer.render(root, width, height);
    }
} ui_driver_t;

#endif
