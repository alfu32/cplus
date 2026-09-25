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
#include <stdlib.h>
#include <stdlib.h>

#define LIFE_BOARD_WIDTH 195
#define LIFE_BOARD_HEIGHT 135
#define LIFE_CELL_SIZE 6
#define LIFE_BOARD_LEFT 60
#define LIFE_BOARD_TOP 104
#define LIFE_SCREEN_WIDTH 1280
#define LIFE_SCREEN_HEIGHT 960

typedef enum life_state_t {
    LIFE_PAUSED,
    LIFE_RUNNING
} life_state_t;

typedef enum life_event_type_t {
    LIFE_EVENT_KEYBOARD,
    LIFE_EVENT_MOUSE,
    LIFE_EVENT_CLOCK
} life_event_type_t;

typedef struct life_event_t {
    life_event_type_t type;
    int key;
    int mouse_button;
    int mouse_x;
    int mouse_y;
    bool mouse_down;
    float delta_seconds;
} life_event_t;

typedef struct life_app_t {
    life_state_t state;
    uint8_t cells[2][LIFE_BOARD_HEIGHT][LIFE_BOARD_WIDTH];
    int current_buffer;
    float step_interval;
    float step_accumulator;
    uint64_t generation;
    uint32_t random_state;
    bool wrap_edges;

    pub uint32_t random(borrowed mut *self) {
        uint32_t value = self->random_state;
        if (value == 0) value = 0x6d2b79f5u;
        value ^= value << 13;
        value ^= value >> 17;
        value ^= value << 5;
        self->random_state = value;
        return value;
    }

    pub void clear(borrowed mut *self) {
        memset(self->cells, 0, sizeof(self->cells));
        self->current_buffer = 0;
        self->generation = 0;
        self->step_accumulator = 0.0f;
    }

    pub void randomize(borrowed mut *self) {
        life_app_t.clear(self);
        for (int y = 0; y < LIFE_BOARD_HEIGHT; y++)
            for (int x = 0; x < LIFE_BOARD_WIDTH; x++)
                self->cells[self->current_buffer][y][x] = life_app_t.random(self) % 100u < 28u ? 1u : 0u;
    }

    pub void init(borrowed mut *self, uint32_t seed) {
        memset(self, 0, sizeof(*self));
        self->state = LIFE_PAUSED;
        self->step_interval = 0.16f;
        self->random_state = seed;
    }

    pub int cell(borrowed const *self, int buffer, int x, int y) {
        if (self->wrap_edges) {
            if (x < 0) x += LIFE_BOARD_WIDTH;
            else if (x >= LIFE_BOARD_WIDTH) x -= LIFE_BOARD_WIDTH;
            if (y < 0) y += LIFE_BOARD_HEIGHT;
            else if (y >= LIFE_BOARD_HEIGHT) y -= LIFE_BOARD_HEIGHT;
            return self->cells[buffer][y][x];
        }
        if (x < 0 || x >= LIFE_BOARD_WIDTH || y < 0 || y >= LIFE_BOARD_HEIGHT) return 0;
        return self->cells[buffer][y][x];
    }

    pub void step(borrowed mut *self) {
        int source = self->current_buffer;
        int destination = 1 - source;
        for (int y = 0; y < LIFE_BOARD_HEIGHT; y++) {
            for (int x = 0; x < LIFE_BOARD_WIDTH; x++) {
                int neighbors = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        if (dx != 0 || dy != 0) neighbors += life_app_t.cell(self, source, x + dx, y + dy);
                int alive = self->cells[source][y][x] != 0;
                self->cells[destination][y][x] = (uint8_t)((neighbors == 3 || (alive && neighbors == 2)) ? 1 : 0);
            }
        }
        self->current_buffer = destination;
        self->generation++;
    }
    pub void paint(borrowed mut *self, const life_event_t* event) {
        if (!event->mouse_down) return;
        if (event->mouse_x < LIFE_BOARD_LEFT || event->mouse_y < LIFE_BOARD_TOP ||
            event->mouse_x >= LIFE_BOARD_LEFT + LIFE_BOARD_WIDTH * LIFE_CELL_SIZE ||
            event->mouse_y >= LIFE_BOARD_TOP + LIFE_BOARD_HEIGHT * LIFE_CELL_SIZE) return;
        int x = (event->mouse_x - LIFE_BOARD_LEFT) / LIFE_CELL_SIZE;
        int y = (event->mouse_y - LIFE_BOARD_TOP) / LIFE_CELL_SIZE;
        if (x < 0 || x >= LIFE_BOARD_WIDTH || y < 0 || y >= LIFE_BOARD_HEIGHT) return;
        uint8_t value = event->mouse_button == MOUSE_BUTTON_RIGHT ? 0u : 1u;
        self->cells[self->current_buffer][y][x] = value;
        self->cells[1 - self->current_buffer][y][x] = value;
    }

    pub void event(borrowed mut *self, const life_event_t* event) {
        if (event->type == LIFE_EVENT_KEYBOARD) {
            if (event->key == KEY_SPACE) self->state = self->state == LIFE_RUNNING ? LIFE_PAUSED : LIFE_RUNNING;
            else if (event->key == KEY_N) life_app_t.step(self);
            else if (event->key == KEY_C) life_app_t.clear(self);
            else if (event->key == KEY_R) life_app_t.randomize(self);
            else if (event->key == KEY_B) self->wrap_edges = !self->wrap_edges;
            else if (event->key == KEY_EQUAL || event->key == KEY_KP_ADD) {
                self->step_interval *= 0.8f;
                if (self->step_interval < 0.035f) self->step_interval = 0.035f;
            } else if (event->key == KEY_MINUS || event->key == KEY_KP_SUBTRACT) {
                self->step_interval *= 1.25f;
                if (self->step_interval > 1.0f) self->step_interval = 1.0f;
            }
        } else if (event->type == LIFE_EVENT_MOUSE) {
            life_app_t.paint(self, event);
        } else if (event->type == LIFE_EVENT_CLOCK && self->state == LIFE_RUNNING) {
            self->step_accumulator += event->delta_seconds > 0.05f ? 0.05f : event->delta_seconds;
            while (self->step_accumulator >= self->step_interval) {
                self->step_accumulator -= self->step_interval;
                life_app_t.step(self);
            }
        }
    }

    pub uint64_t alive_count(borrowed const *self) {
        uint64_t alive = 0;
        for (int y = 0; y < LIFE_BOARD_HEIGHT; y++)
            for (int x = 0; x < LIFE_BOARD_WIDTH; x++)
                alive += self->cells[self->current_buffer][y][x] != 0 ? 1u : 0u;
        return alive;
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){11, 18, 29, 255});
        DrawText("CONWAY'S GAME OF LIFE", LIFE_BOARD_LEFT, 24, 30, RAYWHITE);
        DrawText(TextFormat("GENERATION %d", (int)self->generation), 550, 30, 20, GOLD);
        DrawText(TextFormat("CELLS %d", (int)life_app_t.alive_count(self)), 790, 30, 20, SKYBLUE);
        DrawText("SPACE RUN/PAUSE   N STEP   R RANDOM   C CLEAR   B WRAP   +/- SPEED", 186, 72, 17, LIGHTGRAY);
        DrawRectangle(LIFE_BOARD_LEFT - 2, LIFE_BOARD_TOP - 2,
                      LIFE_BOARD_WIDTH * LIFE_CELL_SIZE + 4,
                      LIFE_BOARD_HEIGHT * LIFE_CELL_SIZE + 4, (Color){53, 68, 84, 255});
        DrawRectangle(LIFE_BOARD_LEFT, LIFE_BOARD_TOP,
                      LIFE_BOARD_WIDTH * LIFE_CELL_SIZE,
                      LIFE_BOARD_HEIGHT * LIFE_CELL_SIZE, (Color){14, 28, 36, 255});
        for (int y = 0; y < LIFE_BOARD_HEIGHT; y++) {
            for (int x = 0; x < LIFE_BOARD_WIDTH; x++) {
                if (self->cells[self->current_buffer][y][x] == 0) continue;
                Color color = ((x + y + (int)self->generation) & 1) == 0 ?
                    (Color){113, 222, 174, 255} : (Color){84, 190, 163, 255};
                DrawRectangle(LIFE_BOARD_LEFT + x * LIFE_CELL_SIZE + 2,
                              LIFE_BOARD_TOP + y * LIFE_CELL_SIZE + 2,
                              LIFE_CELL_SIZE - 3, LIFE_CELL_SIZE - 3, color);
            }
        }
        if (self->state == LIFE_PAUSED) DrawText("PAUSED", LIFE_SCREEN_WIDTH - 115, LIFE_SCREEN_HEIGHT - 36, 18, GOLD);
        DrawText(self->wrap_edges ? "TOROIDAL EDGES" : "FIXED EDGES", LIFE_BOARD_LEFT, LIFE_SCREEN_HEIGHT - 34, 17, LIGHTGRAY);
    }
} life_app_t;

@test "game of life model and render" {
    life_app_t app;
    app.init(97531u);
    app.cells[0][10][9] = 1;
    app.cells[0][10][10] = 1;
    app.cells[0][10][11] = 1;
    app.step();
    @assert(!(app.generation != 1 || app.cells[app.current_buffer][9][10] != 1 ||
        app.cells[app.current_buffer][10][10] != 1 || app.cells[app.current_buffer][11][10] != 1));
    @assert(!(app.cells[app.current_buffer][10][9] != 0 || app.cells[app.current_buffer][10][11] != 0));
    app.step();
    @assert(!(app.cells[app.current_buffer][10][9] != 1 || app.cells[app.current_buffer][10][10] != 1 ||
        app.cells[app.current_buffer][10][11] != 1));

    app.clear();
    app.wrap_edges = true;
    app.cells[app.current_buffer][10][LIFE_BOARD_WIDTH - 1] = 1;
    app.cells[app.current_buffer][10][0] = 1;
    app.cells[app.current_buffer][10][1] = 1;
    app.step();
    @assert(!(app.cells[app.current_buffer][9][0] != 1 || app.cells[app.current_buffer][11][0] != 1));

    life_event_t mouse = {0};
    mouse.type = LIFE_EVENT_MOUSE;
    mouse.mouse_down = true;
    mouse.mouse_button = MOUSE_BUTTON_LEFT;
    mouse.mouse_x = LIFE_BOARD_LEFT + 4 * LIFE_CELL_SIZE + 5;
    mouse.mouse_y = LIFE_BOARD_TOP + 3 * LIFE_CELL_SIZE + 5;
    app.event(&mouse);
    @assert(!(app.cells[app.current_buffer][3][4] != 1));


    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(LIFE_SCREEN_WIDTH, LIFE_SCREEN_HEIGHT, "C-plus test render");
        @assert(IsWindowReady());
        if (IsWindowReady()) {
            defer CloseWindow();
            BeginDrawing();
            app.render();
            EndDrawing();
            Image screenshot = LoadImageFromScreen();
            @assert(screenshot.data != NULL);
            if (screenshot.data != NULL) {
                char screenshot_path[1024];
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/game-of-life.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}

int main(void) {


    InitWindow(LIFE_SCREEN_WIDTH, LIFE_SCREEN_HEIGHT, "C-plus | Conway's Game of Life");
    defer CloseWindow();
    SetTargetFPS(120);
    life_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 41u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            life_event_t event = {0};
            event.type = LIFE_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        int mouse_button = IsMouseButtonDown(MOUSE_BUTTON_RIGHT) ? MOUSE_BUTTON_RIGHT : MOUSE_BUTTON_LEFT;
        life_event_t mouse = {0};
        mouse.type = LIFE_EVENT_MOUSE;
        mouse.mouse_down = IsMouseButtonDown(MOUSE_BUTTON_LEFT) || IsMouseButtonDown(MOUSE_BUTTON_RIGHT);
        mouse.mouse_button = mouse_button;
        Vector2 mouse_position = GetMousePosition();
        mouse.mouse_x = (int)mouse_position.x;
        mouse.mouse_y = (int)mouse_position.y;
        app.event(&mouse);

        life_event_t clock = {0};
        clock.type = LIFE_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
