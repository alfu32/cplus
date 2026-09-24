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

#define SNAKE_BOARD_WIDTH 30
#define SNAKE_BOARD_HEIGHT 22
#define SNAKE_CELL_SIZE 27
#define SNAKE_BOARD_LEFT 75
#define SNAKE_BOARD_TOP 92
#define SNAKE_SCREEN_WIDTH 960
#define SNAKE_SCREEN_HEIGHT 720
#define SNAKE_MAX_SEGMENTS (SNAKE_BOARD_WIDTH * SNAKE_BOARD_HEIGHT)

typedef struct snake_grid_pos_t {
    int x;
    int y;
} snake_grid_pos_t;

typedef enum snake_direction_t {
    SNAKE_UP,
    SNAKE_DOWN,
    SNAKE_LEFT,
    SNAKE_RIGHT
} snake_direction_t;

typedef enum snake_state_t {
    SNAKE_READY,
    SNAKE_PLAYING,
    SNAKE_PAUSED,
    SNAKE_GAME_OVER,
    SNAKE_WON
} snake_state_t;

typedef enum snake_event_type_t {
    SNAKE_EVENT_KEYBOARD,
    SNAKE_EVENT_CLOCK
} snake_event_type_t;

typedef struct snake_event_t {
    snake_event_type_t type;
    int key;
    float delta_seconds;
} snake_event_t;

typedef struct snake_app_t {
    snake_state_t state;
    snake_grid_pos_t segments[SNAKE_MAX_SEGMENTS];
    int length;
    snake_direction_t direction;
    snake_direction_t next_direction;
    snake_grid_pos_t food;
    float step_interval;
    float step_accumulator;
    int score;
    uint32_t random_state;

    pub uint32_t random(borrowed mut *self) {
        uint32_t value = self->random_state;
        if (value == 0) value = 0x9e3779b9u;
        value ^= value << 13;
        value ^= value >> 17;
        value ^= value << 5;
        self->random_state = value;
        return value;
    }

    static pub bool same_cell(snake_grid_pos_t left, snake_grid_pos_t right) {
        return left.x == right.x && left.y == right.y;
    }

    pub void spawn_food(borrowed mut *self) {
        int empty_count = SNAKE_MAX_SEGMENTS - self->length;
        if (empty_count <= 0) { self->state = SNAKE_WON; return; }
        int selected = (int)(snake_app_t.random(self) % (uint32_t)empty_count);
        for (int y = 0; y < SNAKE_BOARD_HEIGHT; y++) {
            for (int x = 0; x < SNAKE_BOARD_WIDTH; x++) {
                snake_grid_pos_t candidate = {x, y};
                bool occupied = false;
                for (int segment = 0; segment < self->length; segment++)
                    if (snake_app_t.same_cell(candidate, self->segments[segment])) { occupied = true; break; }
                if (!occupied && selected-- == 0) { self->food = candidate; return; }
            }
        }
    }

    pub void init(borrowed mut *self, uint32_t seed) {
        memset(self, 0, sizeof(*self));
        self->state = SNAKE_READY;
        self->length = 4;
        self->segments[0] = (snake_grid_pos_t){SNAKE_BOARD_WIDTH / 2, SNAKE_BOARD_HEIGHT / 2};
        self->segments[1] = (snake_grid_pos_t){SNAKE_BOARD_WIDTH / 2 - 1, SNAKE_BOARD_HEIGHT / 2};
        self->segments[2] = (snake_grid_pos_t){SNAKE_BOARD_WIDTH / 2 - 2, SNAKE_BOARD_HEIGHT / 2};
        self->segments[3] = (snake_grid_pos_t){SNAKE_BOARD_WIDTH / 2 - 3, SNAKE_BOARD_HEIGHT / 2};
        self->direction = self->next_direction = SNAKE_RIGHT;
        self->step_interval = 0.12f;
        self->random_state = seed;
        snake_app_t.spawn_food(self);
    }
    pub void set_direction(borrowed mut *self, snake_direction_t direction) {
        bool opposite = (self->direction == SNAKE_UP && direction == SNAKE_DOWN) ||
            (self->direction == SNAKE_DOWN && direction == SNAKE_UP) ||
            (self->direction == SNAKE_LEFT && direction == SNAKE_RIGHT) ||
            (self->direction == SNAKE_RIGHT && direction == SNAKE_LEFT);
        if (!opposite) self->next_direction = direction;
    }

    pub void step(borrowed mut *self) {
        if (self->state != SNAKE_PLAYING || self->length <= 0) return;
        self->direction = self->next_direction;
        snake_grid_pos_t head = self->segments[0];
        if (self->direction == SNAKE_UP) head.y--;
        else if (self->direction == SNAKE_DOWN) head.y++;
        else if (self->direction == SNAKE_LEFT) head.x--;
        else head.x++;

        if (head.x < 0 || head.x >= SNAKE_BOARD_WIDTH || head.y < 0 || head.y >= SNAKE_BOARD_HEIGHT) {
            self->state = SNAKE_GAME_OVER;
            return;
        }
        bool growing = snake_app_t.same_cell(head, self->food);
        int collision_limit = growing ? self->length : self->length - 1;
        for (int segment = 0; segment < collision_limit; segment++) {
            if (snake_app_t.same_cell(head, self->segments[segment])) {
                self->state = SNAKE_GAME_OVER;
                return;
            }
        }

        if (growing && self->length >= SNAKE_MAX_SEGMENTS) {
            self->state = SNAKE_WON;
            return;
        }
        if (growing) self->length++;
        for (int segment = self->length - 1; segment > 0; segment--)
            self->segments[segment] = self->segments[segment - 1];
        self->segments[0] = head;
        if (growing) {
            self->score += 10;
            self->step_interval *= 0.985f;
            if (self->step_interval < 0.055f) self->step_interval = 0.055f;
            snake_app_t.spawn_food(self);
        }
    }

    pub void event(borrowed mut *self, const snake_event_t* event) {
        if (event->type == SNAKE_EVENT_KEYBOARD) {
            if (event->key == KEY_R) {
                snake_app_t.init(self, self->random_state ^ 0xa341316cu);
            } else if (event->key == KEY_SPACE) {
                if (self->state == SNAKE_READY || self->state == SNAKE_PAUSED) self->state = SNAKE_PLAYING;
                else if (self->state == SNAKE_PLAYING) self->state = SNAKE_PAUSED;
                else snake_app_t.init(self, self->random_state ^ 0xc8013ea4u);
            } else if (event->key == KEY_UP || event->key == KEY_W) snake_app_t.set_direction(self, SNAKE_UP);
            else if (event->key == KEY_DOWN || event->key == KEY_S) snake_app_t.set_direction(self, SNAKE_DOWN);
            else if (event->key == KEY_LEFT || event->key == KEY_A) snake_app_t.set_direction(self, SNAKE_LEFT);
            else if (event->key == KEY_RIGHT || event->key == KEY_D) snake_app_t.set_direction(self, SNAKE_RIGHT);
        } else if (event->type == SNAKE_EVENT_CLOCK && self->state == SNAKE_PLAYING) {
            self->step_accumulator += event->delta_seconds > 0.05f ? 0.05f : event->delta_seconds;
            while (self->step_accumulator >= self->step_interval && self->state == SNAKE_PLAYING) {
                self->step_accumulator -= self->step_interval;
                snake_app_t.step(self);
            }
        }
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){13, 20, 31, 255});
        DrawText("SNAKE", SNAKE_BOARD_LEFT, 28, 31, RAYWHITE);
        DrawText(TextFormat("SCORE  %d", self->score), SNAKE_BOARD_LEFT + 170, 34, 20, GOLD);
        DrawText("ARROWS / WASD  MOVE     SPACE  PAUSE     R  RESTART", SNAKE_BOARD_LEFT + 370, 36, 15, LIGHTGRAY);
        DrawRectangle(SNAKE_BOARD_LEFT - 3, SNAKE_BOARD_TOP - 3,
                    SNAKE_BOARD_WIDTH * SNAKE_CELL_SIZE + 6,
                    SNAKE_BOARD_HEIGHT * SNAKE_CELL_SIZE + 6, (Color){57, 70, 88, 255});
        DrawRectangle(SNAKE_BOARD_LEFT, SNAKE_BOARD_TOP,
                    SNAKE_BOARD_WIDTH * SNAKE_CELL_SIZE,
                    SNAKE_BOARD_HEIGHT * SNAKE_CELL_SIZE, (Color){20, 30, 43, 255});
        for (int x = 0; x <= SNAKE_BOARD_WIDTH; x++)
            DrawLine(SNAKE_BOARD_LEFT + x * SNAKE_CELL_SIZE, SNAKE_BOARD_TOP,
                    SNAKE_BOARD_LEFT + x * SNAKE_CELL_SIZE,
                    SNAKE_BOARD_TOP + SNAKE_BOARD_HEIGHT * SNAKE_CELL_SIZE, (Color){34, 45, 59, 255});
        for (int y = 0; y <= SNAKE_BOARD_HEIGHT; y++)
            DrawLine(SNAKE_BOARD_LEFT, SNAKE_BOARD_TOP + y * SNAKE_CELL_SIZE,
                    SNAKE_BOARD_LEFT + SNAKE_BOARD_WIDTH * SNAKE_CELL_SIZE,
                    SNAKE_BOARD_TOP + y * SNAKE_CELL_SIZE, (Color){34, 45, 59, 255});
        DrawRectangle(SNAKE_BOARD_LEFT + self->food.x * SNAKE_CELL_SIZE + 4,
                    SNAKE_BOARD_TOP + self->food.y * SNAKE_CELL_SIZE + 4,
                    SNAKE_CELL_SIZE - 8, SNAKE_CELL_SIZE - 8, (Color){238, 84, 84, 255});
        for (int segment = self->length - 1; segment >= 0; segment--) {
            snake_grid_pos_t cell = self->segments[segment];
            Color color = segment == 0 ? (Color){168, 245, 128, 255} : (Color){88, 190, 104, 255};
            DrawRectangle(SNAKE_BOARD_LEFT + cell.x * SNAKE_CELL_SIZE + 2,
                        SNAKE_BOARD_TOP + cell.y * SNAKE_CELL_SIZE + 2,
                        SNAKE_CELL_SIZE - 4, SNAKE_CELL_SIZE - 4, color);
        }
        if (self->state == SNAKE_READY || self->state == SNAKE_PAUSED ||
            self->state == SNAKE_GAME_OVER || self->state == SNAKE_WON) {
            const char* title = self->state == SNAKE_READY ? "PRESS SPACE TO START" :
                self->state == SNAKE_PAUSED ? "PAUSED - SPACE TO RESUME" :
                self->state == SNAKE_WON ? "BOARD CLEARED!" : "GAME OVER";
            DrawRectangle(267, 333, 426, 70, (Color){0, 0, 0, 205});
            DrawText(title, 292, 352, 24, self->state == SNAKE_GAME_OVER ? RED : RAYWHITE);
        }
    }
} snake_app_t;

@test "snake model and render" {
    snake_app_t app;
    app.init(12345u);
    snake_event_t key = {0};
    key.type = SNAKE_EVENT_KEYBOARD;
    key.key = KEY_UP;
    app.event(&key);
    key.key = KEY_LEFT;
    app.event(&key);
    @assert(!(app.next_direction != SNAKE_UP));

    app.state = SNAKE_PLAYING;
    app.direction = SNAKE_RIGHT;
    app.next_direction = SNAKE_RIGHT;
    app.segments[0] = (snake_grid_pos_t){5, 5};
    app.length = 3;
    app.food = (snake_grid_pos_t){6, 5};
    app.step();
    @assert(!(app.length != 4 || app.score != 10 || app.segments[0].x != 6 || app.segments[0].y != 5));

    app.direction = SNAKE_RIGHT;
    app.next_direction = SNAKE_RIGHT;
    app.length = 5;
    app.segments[0] = (snake_grid_pos_t){5, 5};
    app.segments[1] = (snake_grid_pos_t){5, 4};
    app.segments[2] = (snake_grid_pos_t){6, 4};
    app.segments[3] = (snake_grid_pos_t){6, 5};
    app.segments[4] = (snake_grid_pos_t){6, 6};
    app.food = (snake_grid_pos_t){0, 0};
    app.state = SNAKE_PLAYING;
    app.step();
    @assert(!(app.state != SNAKE_GAME_OVER));

    app.init(23456u);
    app.state = SNAKE_PLAYING;
    app.direction = app.next_direction = SNAKE_RIGHT;
    app.segments[0] = (snake_grid_pos_t){SNAKE_BOARD_WIDTH - 1, 10};
    app.step();
    @assert(!(app.state != SNAKE_GAME_OVER));


    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(SNAKE_SCREEN_WIDTH, SNAKE_SCREEN_HEIGHT, "C-plus test render");
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
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/snake.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}

int main(void) {


    InitWindow(SNAKE_SCREEN_WIDTH, SNAKE_SCREEN_HEIGHT, "C-plus | Snake");
    defer CloseWindow();
    SetTargetFPS(120);
    snake_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 17u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            snake_event_t event = {0};
            event.type = SNAKE_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        snake_event_t clock = {0};
        clock.type = SNAKE_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
