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
#include <math.h>

#define GAME_2048_BOARD_SIZE 4
#define GAME_2048_SCREEN_WIDTH 700
#define GAME_2048_SCREEN_HEIGHT 760
#define GAME_2048_CELL_SIZE 126
#define GAME_2048_GAP 12
#define GAME_2048_BOARD_X 62
#define GAME_2048_BOARD_Y 165

typedef enum game_2048_event_type_t {
    GAME_2048_EVENT_KEYBOARD,
    GAME_2048_EVENT_MOUSE,
    GAME_2048_EVENT_CLOCK
} game_2048_event_type_t;

typedef enum game_2048_state_t {
    GAME_2048_PLAYING,
    GAME_2048_WON,
    GAME_2048_GAME_OVER
} game_2048_state_t;

typedef enum game_2048_direction_t {
    GAME_2048_LEFT,
    GAME_2048_RIGHT,
    GAME_2048_UP,
    GAME_2048_DOWN
} game_2048_direction_t;

typedef struct game_2048_board_t {
    unsigned char cells[GAME_2048_BOARD_SIZE][GAME_2048_BOARD_SIZE];
} game_2048_board_t;

typedef struct game_2048_event_t {
    game_2048_event_type_t type;
    int key;
    Vector2 position;
    bool pressed;
    int mouse_button;
    float delta_seconds;
} game_2048_event_t;

typedef struct game_2048_app_t {
    game_2048_state_t state;
    game_2048_board_t board;
    uint64_t score;
    uint32_t random_state;
    Vector2 mouse_start;
    bool mouse_dragging;

    pub uint32_t random(borrowed mut *self) {
        uint32_t value = self->random_state;
        if (value == 0) value = 0x27d4eb2du;
        value ^= value << 13;
        value ^= value >> 17;
        value ^= value << 5;
        self->random_state = value;
        return value;
    }

    pub int spawn(borrowed mut *self);

    pub void init(borrowed mut *self, uint32_t seed) {
        memset(self, 0, sizeof(*self));
        self->state = GAME_2048_PLAYING;
        self->random_state = seed;
        game_2048_app_t.spawn(self);
        game_2048_app_t.spawn(self);
    }

    static pub uint32_t value(unsigned char exponent) {
        if (exponent == 0 || exponent > 31) return 0;
        return (uint32_t)1u << exponent;
    }

    pub int spawn(borrowed mut *self) {
        int empty_count = 0;
        for (int row = 0; row < GAME_2048_BOARD_SIZE; row++)
            for (int column = 0; column < GAME_2048_BOARD_SIZE; column++)
                empty_count += self->board.cells[row][column] == 0 ? 1 : 0;
        if (empty_count == 0) return 0;

        int selected = (int)(game_2048_app_t.random(self) % (uint32_t)empty_count);
        for (int row = 0; row < GAME_2048_BOARD_SIZE; row++) {
            for (int column = 0; column < GAME_2048_BOARD_SIZE; column++) {
                if (self->board.cells[row][column] != 0) continue;
                if (selected-- != 0) continue;
                self->board.cells[row][column] = game_2048_app_t.random(self) % 10u == 0u ? 2 : 1;
                return 1;
            }
        }
        return 0;
    }

    pub void get_cell(borrowed const *self, game_2048_direction_t direction, int line, int offset, int* out_row, int* out_column) {
        if (direction == GAME_2048_LEFT) {
            *out_row = line;
            *out_column = offset;
        } else if (direction == GAME_2048_RIGHT) {
            *out_row = line;
            *out_column = GAME_2048_BOARD_SIZE - 1 - offset;
        } else if (direction == GAME_2048_UP) {
            *out_row = offset;
            *out_column = line;
        } else {
            *out_row = GAME_2048_BOARD_SIZE - 1 - offset;
            *out_column = line;
        }
        (void)self;
    }

    pub bool slide(borrowed mut *self, game_2048_direction_t direction) {
        bool changed = false;
        for (int line = 0; line < GAME_2048_BOARD_SIZE; line++) {
            unsigned char source[GAME_2048_BOARD_SIZE] = {0};
            unsigned char result[GAME_2048_BOARD_SIZE] = {0};
            int count = 0;
            for (int offset = 0; offset < GAME_2048_BOARD_SIZE; offset++) {
                int row;
                int column;
                game_2048_app_t.get_cell(self, direction, line, offset, &row, &column);
                unsigned char value = self->board.cells[row][column];
                if (value != 0) source[count++] = value;
            }

            int written = 0;
            for (int index = 0; index < count; index++) {
                if (index + 1 < count && source[index] == source[index + 1] && source[index] < 31) {
                    unsigned char merged = source[index] + 1;
                    result[written++] = merged;
                    self->score += game_2048_app_t.value(merged);
                    index++;
                } else {
                    result[written++] = source[index];
                }
            }

            for (int offset = 0; offset < GAME_2048_BOARD_SIZE; offset++) {
                int row;
                int column;
                game_2048_app_t.get_cell(self, direction, line, offset, &row, &column);
                if (self->board.cells[row][column] != result[offset]) changed = true;
                self->board.cells[row][column] = result[offset];
            }
        }
        return changed;
    }

    pub bool has_moves(borrowed const *self) {
        for (int row = 0; row < GAME_2048_BOARD_SIZE; row++) {
            for (int column = 0; column < GAME_2048_BOARD_SIZE; column++) {
                unsigned char value = self->board.cells[row][column];
                if (value == 0) return true;
                if (column + 1 < GAME_2048_BOARD_SIZE && value == self->board.cells[row][column + 1]) return true;
                if (row + 1 < GAME_2048_BOARD_SIZE && value == self->board.cells[row + 1][column]) return true;
            }
        }
        return false;
    }

    pub bool has_won(borrowed const *self) {
        for (int row = 0; row < GAME_2048_BOARD_SIZE; row++)
            for (int column = 0; column < GAME_2048_BOARD_SIZE; column++)
                if (self->board.cells[row][column] >= 11) return true;
        return false;
    }

    pub void move(borrowed mut *self, game_2048_direction_t direction) {
        if (self->state == GAME_2048_GAME_OVER) return;
        if (!game_2048_app_t.slide(self, direction)) return;
        game_2048_app_t.spawn(self);
        if (game_2048_app_t.has_won(self)) self->state = GAME_2048_WON;
        else if (!game_2048_app_t.has_moves(self)) self->state = GAME_2048_GAME_OVER;
    }

    pub void key(borrowed mut *self, int key) {
        if (key == KEY_R) {
            game_2048_app_t.init(self, self->random_state + 1u);
            return;
        }
        if (key == KEY_LEFT || key == KEY_A) game_2048_app_t.move(self, GAME_2048_LEFT);
        else if (key == KEY_RIGHT || key == KEY_D) game_2048_app_t.move(self, GAME_2048_RIGHT);
        else if (key == KEY_UP || key == KEY_W) game_2048_app_t.move(self, GAME_2048_UP);
        else if (key == KEY_DOWN || key == KEY_S) game_2048_app_t.move(self, GAME_2048_DOWN);
    }

    pub void mouse(borrowed mut *self, const game_2048_event_t* event) {
        if (event->mouse_button != MOUSE_BUTTON_LEFT) return;
        if (event->pressed) {
            self->mouse_start = event->position;
            self->mouse_dragging = true;
            return;
        }
        if (!self->mouse_dragging) return;
        self->mouse_dragging = false;
        float dx = event->position.x - self->mouse_start.x;
        float dy = event->position.y - self->mouse_start.y;
        if (fabsf(dx) < 35.0f && fabsf(dy) < 35.0f) return;
        if (fabsf(dx) > fabsf(dy)) game_2048_app_t.move(self, dx < 0 ? GAME_2048_LEFT : GAME_2048_RIGHT);
        else game_2048_app_t.move(self, dy < 0 ? GAME_2048_UP : GAME_2048_DOWN);
    }

    pub void event(borrowed mut *self, const game_2048_event_t* event) {
        if (event->type == GAME_2048_EVENT_KEYBOARD) game_2048_app_t.key(self, event->key);
        else if (event->type == GAME_2048_EVENT_MOUSE) game_2048_app_t.mouse(self, event);
        else (void)event->delta_seconds;
    }

    static pub Color color(unsigned char exponent) {
        static const Color colors[] = {
            {205, 193, 180, 255}, {238, 228, 218, 255}, {237, 224, 200, 255},
            {242, 177, 121, 255}, {245, 149, 99, 255}, {246, 124, 95, 255},
            {246, 94, 59, 255}, {237, 207, 114, 255}, {237, 204, 97, 255},
            {237, 200, 80, 255}, {237, 197, 63, 255}, {237, 194, 46, 255}
        };
        if (exponent == 0) return (Color){187, 173, 160, 255};
        if (exponent < sizeof(colors) / sizeof(colors[0])) return colors[exponent];
        return GOLD;
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){250, 248, 239, 255});
        DrawText("2048", 62, 38, 48, (Color){75, 67, 58, 255});
        DrawText(TextFormat("SCORE %llu", (unsigned long long)self->score), 65, 100, 22, (Color){75, 67, 58, 255});
        DrawText("ARROWS / WASD / SWIPE", 352, 54, 17, (Color){119, 110, 101, 255});
        DrawText("R RESTARTS", 352, 82, 17, (Color){119, 110, 101, 255});

        DrawRectangle(GAME_2048_BOARD_X - 12, GAME_2048_BOARD_Y - 12,
                      GAME_2048_BOARD_SIZE * GAME_2048_CELL_SIZE + (GAME_2048_BOARD_SIZE + 1) * GAME_2048_GAP,
                      GAME_2048_BOARD_SIZE * GAME_2048_CELL_SIZE + (GAME_2048_BOARD_SIZE + 1) * GAME_2048_GAP,
                      (Color){187, 173, 160, 255});
        for (int row = 0; row < GAME_2048_BOARD_SIZE; row++) {
            for (int column = 0; column < GAME_2048_BOARD_SIZE; column++) {
                unsigned char exponent = self->board.cells[row][column];
                int x = GAME_2048_BOARD_X + column * (GAME_2048_CELL_SIZE + GAME_2048_GAP);
                int y = GAME_2048_BOARD_Y + row * (GAME_2048_CELL_SIZE + GAME_2048_GAP);
                DrawRectangleRounded((Rectangle){(float)x, (float)y, GAME_2048_CELL_SIZE, GAME_2048_CELL_SIZE}, 0.08f, 6,
                                     game_2048_app_t.color(exponent));
                if (exponent == 0) continue;
                uint32_t value = game_2048_app_t.value(exponent);
                const char* label = TextFormat("%u", value);
                int font_size = exponent < 3 ? 42 : exponent < 6 ? 36 : 29;
                Color text_color = exponent <= 2 ? (Color){104, 94, 83, 255} : RAYWHITE;
                DrawText(label, x + (GAME_2048_CELL_SIZE - MeasureText(label, font_size)) / 2,
                         y + (GAME_2048_CELL_SIZE - font_size) / 2, font_size, text_color);
            }
        }
        if (self->state == GAME_2048_WON) {
            DrawRectangle(62, 720, 570, 34, (Color){237, 194, 46, 240});
            DrawText("2048! KEEP PLAYING OR PRESS R", 96, 727, 18, RAYWHITE);
        } else if (self->state == GAME_2048_GAME_OVER) {
            DrawRectangle(62, 720, 570, 34, (Color){110, 96, 82, 245});
            DrawText("NO MOVES LEFT - PRESS R", 140, 727, 18, RAYWHITE);
        }
    }

    pub int count_tiles(borrowed const *self) {
        int count = 0;
        for (int row = 0; row < GAME_2048_BOARD_SIZE; row++)
            for (int column = 0; column < GAME_2048_BOARD_SIZE; column++)
                count += self->board.cells[row][column] != 0 ? 1 : 0;
        return count;
    }

} game_2048_app_t;

@test "game 2048 model and render" {
    game_2048_app_t app;
    app.init(97531u);
    @assert(!(app.count_tiles() != 2));

    memset(app.board.cells, 0, sizeof(app.board.cells));
    app.board.cells[0][0] = 1;
    app.board.cells[0][1] = 1;
    app.board.cells[0][2] = 2;
    app.board.cells[0][3] = 2;
    @assert(!(!app.slide(GAME_2048_LEFT)));
    @assert(!(app.board.cells[0][0] != 2 || app.board.cells[0][1] != 3 ||
        app.board.cells[0][2] != 0 || app.board.cells[0][3] != 0));
    @assert(!(app.score != 12));

    memset(app.board.cells, 0, sizeof(app.board.cells));
    app.board.cells[0][0] = 1;
    app.board.cells[0][1] = 1;
    @assert(!(!app.slide(GAME_2048_RIGHT) || app.board.cells[0][3] != 2 ||
        app.board.cells[0][0] != 0 || app.board.cells[0][1] != 0));

    memset(app.board.cells, 0, sizeof(app.board.cells));
    app.board.cells[0][2] = 1;
    app.board.cells[1][2] = 1;
    @assert(!(!app.slide(GAME_2048_DOWN) || app.board.cells[3][2] != 2 ||
        app.board.cells[0][2] != 0 || app.board.cells[1][2] != 0));

    memset(app.board.cells, 0, sizeof(app.board.cells));
    app.board.cells[1][0] = 1;
    app.board.cells[2][0] = 1;
    @assert(!(!app.slide(GAME_2048_UP) || app.board.cells[0][0] != 2 ||
        app.board.cells[1][0] != 0 || app.board.cells[2][0] != 0));

    memset(app.board.cells, 0, sizeof(app.board.cells));
    app.board.cells[0][0] = 1;
    app.board.cells[0][1] = 1;
    app.board.cells[0][2] = 1;
    app.board.cells[0][3] = 1;
    app.score = 0;
    @assert(!(!app.slide(GAME_2048_LEFT)));
    @assert(!(app.board.cells[0][0] != 2 || app.board.cells[0][1] != 2 || app.score != 8));

    memset(app.board.cells, 0, sizeof(app.board.cells));
    app.board.cells[2][1] = 1;
    app.board.cells[2][2] = 1;
    app.score = 0;
    app.move(GAME_2048_LEFT);
    @assert(!(app.count_tiles() != 2 || app.score != 4));


    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(GAME_2048_SCREEN_WIDTH, GAME_2048_SCREEN_HEIGHT, "C-plus test render");
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
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/game-2048.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}



int main(int argc, char** argv) {
        InitWindow(GAME_2048_SCREEN_WIDTH, GAME_2048_SCREEN_HEIGHT, "C-plus | 2048");
    defer CloseWindow();
    SetTargetFPS(60);
    game_2048_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 7u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            game_2048_event_t event = {0};
            event.type = GAME_2048_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        Vector2 mouse = GetMousePosition();
        if (IsMouseButtonPressed(MOUSE_BUTTON_LEFT) || IsMouseButtonReleased(MOUSE_BUTTON_LEFT)) {
            game_2048_event_t event = {0};
            event.type = GAME_2048_EVENT_MOUSE;
            event.position = mouse;
            event.mouse_button = MOUSE_BUTTON_LEFT;
            event.pressed = IsMouseButtonPressed(MOUSE_BUTTON_LEFT);
            app.event(&event);
        }
        game_2048_event_t clock = {0};
        clock.type = GAME_2048_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        app.event(&clock);

        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
