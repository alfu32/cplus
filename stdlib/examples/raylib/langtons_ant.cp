comptime import "stdlib:/graphics/raylib.cp";
comptime {
    @if (os == "linux") {
        comptime flags -lraylib -lGL -lm -lpthread -ldl -lrt -lX11 -lXrandr -lXinerama -lXcursor -lXi;
    } @else if (os == "windows") {
        comptime flags -lraylib -lopengl32 -lgdi32 -lwinmm -lshcore;
    } @else if (os == "macos") {
        comptime flags -lraylib -framework Foundation -framework AppKit -framework IOKit -framework OpenGL -framework CoreVideo -framework QuartzCore;
    }
}

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define ANT_BOARD_WIDTH 160
#define ANT_BOARD_HEIGHT 100
#define ANT_CELL_SIZE 6
#define ANT_SCREEN_WIDTH (ANT_BOARD_WIDTH * ANT_CELL_SIZE)
#define ANT_SCREEN_HEIGHT (ANT_BOARD_HEIGHT * ANT_CELL_SIZE + 44)

typedef enum ant_event_type_t {
    ANT_EVENT_KEYBOARD,
    ANT_EVENT_MOUSE,
    ANT_EVENT_CLOCK
} ant_event_type_t;

typedef enum ant_direction_t {
    ANT_UP,
    ANT_RIGHT,
    ANT_DOWN,
    ANT_LEFT
} ant_direction_t;

typedef enum ant_state_t {
    ANT_PAUSED,
    ANT_RUNNING
} ant_state_t;

typedef struct ant_event_t {
    ant_event_type_t type;
    int key;
    int mouse_x;
    int mouse_y;
    bool mouse_pressed;
    bool mouse_right;
    float delta_seconds;
} ant_event_t;

typedef struct langton_app_t {
    ant_state_t state;
    bool cells[ANT_BOARD_HEIGHT][ANT_BOARD_WIDTH];
    int ant_x;
    int ant_y;
    ant_direction_t direction;
    uint64_t generation;
    float step_interval;
    float accumulator;
    bool wrap_edges;

    pub void reset(borrowed mut *self) {
        memset(self->cells, 0, sizeof(self->cells));
        self->ant_x = ANT_BOARD_WIDTH / 2;
        self->ant_y = ANT_BOARD_HEIGHT / 2;
        self->direction = ANT_UP;
        self->generation = 0;
        self->accumulator = 0.0f;
    }

    pub void init(borrowed mut *self) {
        memset(self, 0, sizeof(*self));
        self->state = ANT_PAUSED;
        self->step_interval = 0.01f;
        self->wrap_edges = true;
        langton_app_t.reset(self);
    }

    pub void turn_left(borrowed mut *self) {
        self->direction = (ant_direction_t)((self->direction + 3) % 4);
    }

    pub void turn_right(borrowed mut *self) {
        self->direction = (ant_direction_t)((self->direction + 1) % 4);
    }

    pub bool move_forward(borrowed mut *self) {
        if (self->direction == ANT_UP) self->ant_y--;
        else if (self->direction == ANT_RIGHT) self->ant_x++;
        else if (self->direction == ANT_DOWN) self->ant_y++;
        else self->ant_x--;

        if (self->wrap_edges) {
            if (self->ant_x < 0) self->ant_x = ANT_BOARD_WIDTH - 1;
            if (self->ant_x >= ANT_BOARD_WIDTH) self->ant_x = 0;
            if (self->ant_y < 0) self->ant_y = ANT_BOARD_HEIGHT - 1;
            if (self->ant_y >= ANT_BOARD_HEIGHT) self->ant_y = 0;
            return true;
        }
        return self->ant_x >= 0 && self->ant_x < ANT_BOARD_WIDTH &&
               self->ant_y >= 0 && self->ant_y < ANT_BOARD_HEIGHT;
    }

    pub bool step(borrowed mut *self) {
        if (self->ant_x < 0 || self->ant_x >= ANT_BOARD_WIDTH ||
            self->ant_y < 0 || self->ant_y >= ANT_BOARD_HEIGHT) return false;
        bool* cell = &self->cells[self->ant_y][self->ant_x];
        if (*cell) langton_app_t.turn_left(self);
        else langton_app_t.turn_right(self);
        *cell = !*cell;
        bool inside = langton_app_t.move_forward(self);
        self->generation++;
        if (!inside) self->state = ANT_PAUSED;
        return inside;
    }

    pub void clock(borrowed mut *self, const ant_event_t* event) {
        if (self->state != ANT_RUNNING) return;
        float dt = event->delta_seconds;
        if (dt < 0.0f) dt = 0.0f;
        if (dt > 0.1f) dt = 0.1f;
        self->accumulator += dt;
        int budget = 10000;
        while (self->accumulator >= self->step_interval && budget-- > 0) {
            self->accumulator -= self->step_interval;
            if (!langton_app_t.step(self)) break;
        }
    }

    pub void edit_cell(borrowed mut *self, int mouse_x, int mouse_y, bool value) {
        int x = mouse_x / ANT_CELL_SIZE;
        int y = mouse_y / ANT_CELL_SIZE;
        if (x >= 0 && x < ANT_BOARD_WIDTH && y >= 0 && y < ANT_BOARD_HEIGHT)
            self->cells[y][x] = value;
    }

    pub void event(borrowed mut *self, const ant_event_t* event) {
        if (event->type == ANT_EVENT_KEYBOARD) {
            if (event->key == KEY_SPACE)
                self->state = self->state == ANT_RUNNING ? ANT_PAUSED : ANT_RUNNING;
            else if (event->key == KEY_N && self->state == ANT_PAUSED)
                langton_app_t.step(self);
            else if (event->key == KEY_R)
                langton_app_t.reset(self);
            else if (event->key == KEY_W)
                self->wrap_edges = !self->wrap_edges;
            else if (event->key == KEY_EQUAL || event->key == KEY_KP_ADD) {
                self->step_interval *= 0.5f;
                if (self->step_interval < 0.0001f) self->step_interval = 0.0001f;
            } else if (event->key == KEY_MINUS || event->key == KEY_KP_SUBTRACT) {
                self->step_interval *= 2.0f;
                if (self->step_interval > 1.0f) self->step_interval = 1.0f;
            }
        } else if (event->type == ANT_EVENT_MOUSE) {
            if (event->mouse_pressed)
                langton_app_t.edit_cell(self, event->mouse_x, event->mouse_y, !event->mouse_right);
        } else if (event->type == ANT_EVENT_CLOCK) {
            langton_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){22, 25, 31, 255});
        for (int y = 0; y < ANT_BOARD_HEIGHT; y++) {
            for (int x = 0; x < ANT_BOARD_WIDTH; x++) {
                if (self->cells[y][x])
                    DrawRectangle(x * ANT_CELL_SIZE, y * ANT_CELL_SIZE,
                                  ANT_CELL_SIZE, ANT_CELL_SIZE, RAYWHITE);
            }
        }
        Vector2 a = (Vector2){self->ant_x * ANT_CELL_SIZE + ANT_CELL_SIZE * 0.5f,
                              self->ant_y * ANT_CELL_SIZE + ANT_CELL_SIZE * 0.5f};
        if (self->ant_x >= 0 && self->ant_x < ANT_BOARD_WIDTH &&
            self->ant_y >= 0 && self->ant_y < ANT_BOARD_HEIGHT)
            DrawCircleV(a, ANT_CELL_SIZE * 0.55f, RED);
        DrawRectangle(0, ANT_BOARD_HEIGHT * ANT_CELL_SIZE, ANT_SCREEN_WIDTH, 44, (Color){8, 10, 14, 255});
        DrawText(TextFormat("GEN %llu   %s   %.4fs/step   wrap:%s",
                 (unsigned long long)self->generation,
                 self->state == ANT_RUNNING ? "RUN" : "PAUSE",
                 self->step_interval, self->wrap_edges ? "on" : "off"),
                 12, ANT_BOARD_HEIGHT * ANT_CELL_SIZE + 11, 20, RAYWHITE);
    }
} langton_app_t;

@test "langton deterministic state machine" {
    langton_app_t app;
    app.init();
    int cx = ANT_BOARD_WIDTH / 2;
    int cy = ANT_BOARD_HEIGHT / 2;
    @assert(!(app.cells[cy][cx] || app.direction != ANT_UP || app.generation != 0));
    app.step();
    @assert(!(!app.cells[cy][cx] || app.direction != ANT_RIGHT || app.ant_x != cx + 1 || app.ant_y != cy));
    app.step();
    @assert(!(app.direction != ANT_DOWN || app.ant_x != cx + 1 || app.ant_y != cy + 1 || app.generation != 2));
    app.reset();
    app.cells[cy][cx] = true;
    app.step();
    @assert(!(app.direction != ANT_LEFT || app.ant_x != cx - 1 || app.cells[cy][cx]));
}

int main(int argc, char** argv) {
    InitWindow(ANT_SCREEN_WIDTH, ANT_SCREEN_HEIGHT, "C-plus | Langton's Ant");
    defer CloseWindow();
    SetTargetFPS(120);
    langton_app_t app;
    app.init();
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            ant_event_t event = {0};
            event.type = ANT_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        if (IsMouseButtonDown(MOUSE_BUTTON_LEFT) || IsMouseButtonDown(MOUSE_BUTTON_RIGHT)) {
            Vector2 mouse = GetMousePosition();
            ant_event_t event = {0};
            event.type = ANT_EVENT_MOUSE;
            event.mouse_x = (int)mouse.x;
            event.mouse_y = (int)mouse.y;
            event.mouse_pressed = true;
            event.mouse_right = IsMouseButtonDown(MOUSE_BUTTON_RIGHT);
            app.event(&event);
        }
        ant_event_t clock = {0};
        clock.type = ANT_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
