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

#define TETRIS_BOARD_WIDTH 10
#define TETRIS_BOARD_HEIGHT 20
#define TETRIS_CELL_SIZE 30
#define TETRIS_SCREEN_WIDTH 720
#define TETRIS_SCREEN_HEIGHT 700

typedef enum tetris_event_type_t {
    TETRIS_EVENT_KEYBOARD,
    TETRIS_EVENT_MOUSE,
    TETRIS_EVENT_CLOCK
} tetris_event_type_t;

typedef enum tetromino_type_t {
    TETROMINO_I,
    TETROMINO_J,
    TETROMINO_L,
    TETROMINO_O,
    TETROMINO_S,
    TETROMINO_T,
    TETROMINO_Z,
    TETROMINO_NONE
} tetromino_type_t;

typedef enum tetris_state_t {
    TETRIS_PLAYING,
    TETRIS_GAME_OVER
} tetris_state_t;

typedef struct tetris_piece_t {
    tetromino_type_t type;
    int x;
    int y;
    int rotation;
} tetris_piece_t;

typedef struct tetris_board_t {
    unsigned char cells[TETRIS_BOARD_HEIGHT][TETRIS_BOARD_WIDTH];
} tetris_board_t;

typedef struct tetris_event_t {
    tetris_event_type_t type;
    int key;
    Vector2 position;
    float delta_seconds;
    bool soft_drop_down;
} tetris_event_t;

typedef struct tetris_app_t {
    tetris_state_t state;
    tetris_board_t board;
    tetris_piece_t active;
    tetromino_type_t next;
    tetromino_type_t hold;
    bool has_hold;
    bool hold_used;
    float fall_accumulator;
    int level;
    int lines;
    int score;
    uint32_t random_state;

pub uint32_t random(borrowed mut *self) {
    uint32_t value = self->random_state;
    if (value == 0) value = 0xc2b2ae35u;
    value ^= value << 13;
    value ^= value >> 17;
    value ^= value << 5;
    self->random_state = value;
    return value;
}

static pub bool piece_cell(tetromino_type_t type, int rotation, int cell, int* out_x, int* out_y) {
    static const unsigned char shapes[7][4][4] = {
        {{0,0,0,0},{1,1,1,1},{0,0,0,0},{0,0,0,0}},
        {{1,0,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
        {{0,0,1,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
        {{0,0,0,0},{0,1,1,0},{0,1,1,0},{0,0,0,0}},
        {{0,1,1,0},{1,1,0,0},{0,0,0,0},{0,0,0,0}},
        {{0,1,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
        {{1,1,0,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}}
    };
    if (type < TETROMINO_I || type > TETROMINO_Z) return false;
    int seen = 0;
    for (int row = 0; row < 4; row++) {
        for (int column = 0; column < 4; column++) {
            if (shapes[type][row][column] == 0) continue;
            if (seen++ != cell) continue;
            int x = column;
            int y = row;
            for (int turn = 0; turn < rotation; turn++) {
                int old_x = x;
                x = 3 - y;
                y = old_x;
            }
            *out_x = x;
            *out_y = y;
            return true;
        }
    }
    return false;
}

pub bool can_place(borrowed const *self, tetromino_type_t type, int x, int y, int rotation) {
    for (int cell = 0; cell < 4; cell++) {
        int offset_x;
        int offset_y;
        if (!tetris_app_t.piece_cell(type, rotation, cell, &offset_x, &offset_y)) return false;
        int board_x = x + offset_x;
        int board_y = y + offset_y;
        if (board_x < 0 || board_x >= TETRIS_BOARD_WIDTH || board_y >= TETRIS_BOARD_HEIGHT) return false;
        if (board_y >= 0 && self->board.cells[board_y][board_x] != 0) return false;
    }
    return true;
}

pub void spawn(borrowed mut *self) {
    self->active = (tetris_piece_t){self->next, 3, 0, 0};
    self->next = (tetromino_type_t)(tetris_app_t.random(self) % 7u);
    self->hold_used = false;
    if (!tetris_app_t.can_place(self, self->active.type, self->active.x, self->active.y, self->active.rotation))
        self->state = TETRIS_GAME_OVER;
}

pub void init(borrowed mut *self, uint32_t seed) {
    memset(self, 0, sizeof(*self));
    self->state = TETRIS_PLAYING;
    self->level = 1;
    self->hold = TETROMINO_NONE;
    self->random_state = seed;
    self->next = (tetromino_type_t)(tetris_app_t.random(self) % 7u);
    tetris_app_t.spawn(self);
}

pub int clear_lines(borrowed mut *self) {
    int cleared = 0;
    for (int row = TETRIS_BOARD_HEIGHT - 1; row >= 0; row--) {
        bool full = true;
        for (int column = 0; column < TETRIS_BOARD_WIDTH; column++)
            full = full && self->board.cells[row][column] != 0;
        if (!full) continue;
        for (int move_row = row; move_row > 0; move_row--)
            memcpy(self->board.cells[move_row], self->board.cells[move_row - 1], TETRIS_BOARD_WIDTH);
        memset(self->board.cells[0], 0, TETRIS_BOARD_WIDTH);
        cleared++;
        row++;
    }
    if (cleared > 0) {
        static const int points[5] = {0, 100, 300, 500, 800};
        self->lines += cleared;
        self->score += points[cleared > 4 ? 4 : cleared] * self->level;
        self->level = self->lines / 10 + 1;
    }
    return cleared;
}

pub void lock_piece(borrowed mut *self) {
    for (int cell = 0; cell < 4; cell++) {
        int offset_x;
        int offset_y;
        tetris_app_t.piece_cell(self->active.type, self->active.rotation, cell, &offset_x, &offset_y);
        int x = self->active.x + offset_x;
        int y = self->active.y + offset_y;
        if (y < 0) {
            self->state = TETRIS_GAME_OVER;
            return;
        }
        self->board.cells[y][x] = (unsigned char)(self->active.type + 1);
    }
    tetris_app_t.clear_lines(self);
    tetris_app_t.spawn(self);
}

pub bool move(borrowed mut *self, int dx, int dy) {
    if (!tetris_app_t.can_place(self, self->active.type, self->active.x + dx, self->active.y + dy, self->active.rotation))
        return false;
    self->active.x += dx;
    self->active.y += dy;
    return true;
}

pub void rotate(borrowed mut *self, int direction) {
    int rotation = (self->active.rotation + direction + 4) % 4;
    static const int kicks[] = {0, -1, 1, -2, 2};
    for (int i = 0; i < 5; i++) {
        if (tetris_app_t.can_place(self, self->active.type, self->active.x + kicks[i], self->active.y, rotation)) {
            self->active.x += kicks[i];
            self->active.rotation = rotation;
            return;
        }
    }
}

pub void hold_piece(borrowed mut *self) {
    if (self->hold_used) return;
    tetromino_type_t outgoing = self->active.type;
    if (self->has_hold) {
        self->active.type = self->hold;
    } else {
        self->active.type = self->next;
        self->next = (tetromino_type_t)(tetris_app_t.random(self) % 7u);
        self->has_hold = true;
    }
    self->hold = outgoing;
    self->active.x = 3;
    self->active.y = 0;
    self->active.rotation = 0;
    self->hold_used = true;
    if (!tetris_app_t.can_place(self, self->active.type, self->active.x, self->active.y, self->active.rotation))
        self->state = TETRIS_GAME_OVER;
}

pub void hard_drop(borrowed mut *self) {
    int distance = 0;
    while (tetris_app_t.move(self, 0, 1)) distance++;
    self->score += distance * 2;
    tetris_app_t.lock_piece(self);
}

pub void key(borrowed mut *self, int key) {
    if (self->state == TETRIS_GAME_OVER) {
        if (key == KEY_ENTER) tetris_app_t.init(self, self->random_state + 1u);
        return;
    }
    if (key == KEY_LEFT || key == KEY_A) tetris_app_t.move(self, -1, 0);
    else if (key == KEY_RIGHT || key == KEY_D) tetris_app_t.move(self, 1, 0);
    else if (key == KEY_DOWN || key == KEY_S) {
        if (tetris_app_t.move(self, 0, 1)) self->score++;
        else tetris_app_t.lock_piece(self);
    }
    else if (key == KEY_UP || key == KEY_X) tetris_app_t.rotate(self, 1);
    else if (key == KEY_Z) tetris_app_t.rotate(self, -1);
    else if (key == KEY_SPACE) tetris_app_t.hard_drop(self);
    else if (key == KEY_C) tetris_app_t.hold_piece(self);
}

static pub float drop_interval(int level) {
    float interval = 0.8f;
    for (int i = 1; i < level && interval > 0.08f; i++) interval *= 0.82f;
    return interval < 0.08f ? 0.08f : interval;
}

pub void clock(borrowed mut *self, const tetris_event_t* event) {
    if (self->state != TETRIS_PLAYING) return;
    float dt = event->delta_seconds;
    if (dt < 0.0f) dt = 0.0f;
    if (dt > 0.1f) dt = 0.1f;
    self->fall_accumulator += dt;
    float interval = event->soft_drop_down ? 0.055f : tetris_app_t.drop_interval(self->level);
    while (self->fall_accumulator >= interval && self->state == TETRIS_PLAYING) {
        self->fall_accumulator -= interval;
        if (tetris_app_t.move(self, 0, 1)) {
            if (event->soft_drop_down) self->score++;
        } else {
            tetris_app_t.lock_piece(self);
        }
    }
}

pub void event(borrowed mut *self, const tetris_event_t* event) {
    if (event->type == TETRIS_EVENT_KEYBOARD) tetris_app_t.key(self, event->key);
    else if (event->type == TETRIS_EVENT_CLOCK) tetris_app_t.clock(self, event);
    else (void)event->position;
}

static pub Color piece_color(int kind) {
    static const Color colors[7] = {SKYBLUE, BLUE, ORANGE, YELLOW, GREEN, PURPLE, RED};
    if (kind < 0 || kind >= 7) return GRAY;
    return colors[kind];
}

static pub void draw_cell(int x, int y, Color color) {
    int screen_x = 50 + x * TETRIS_CELL_SIZE;
    int screen_y = 43 + y * TETRIS_CELL_SIZE;
    DrawRectangle(screen_x + 1, screen_y + 1, TETRIS_CELL_SIZE - 2, TETRIS_CELL_SIZE - 2, color);
    DrawRectangleLines(screen_x + 1, screen_y + 1, TETRIS_CELL_SIZE - 2, TETRIS_CELL_SIZE - 2, (Color){20, 25, 40, 255});
}

static pub void draw_preview(tetromino_type_t type, int left, int top) {
    if (type == TETROMINO_NONE) return;
    for (int cell = 0; cell < 4; cell++) {
        int x;
        int y;
        tetris_app_t.piece_cell(type, 0, cell, &x, &y);
        DrawRectangle(left + x * 19, top + y * 19, 17, 17, tetris_app_t.piece_color(type));
    }
}

pub void render(borrowed const *self) {
    ClearBackground((Color){16, 20, 34, 255});
    DrawText("TETRIS", 430, 35, 34, RAYWHITE);
    DrawText(TextFormat("SCORE %d", self->score), 430, 95, 22, RAYWHITE);
    DrawText(TextFormat("LINES %d", self->lines), 430, 130, 22, RAYWHITE);
    DrawText(TextFormat("LEVEL %d", self->level), 430, 165, 22, RAYWHITE);
    DrawText("NEXT", 430, 225, 20, LIGHTGRAY);
    tetris_app_t.draw_preview(self->next, 440, 255);
    DrawText("HOLD", 560, 225, 20, LIGHTGRAY);
    if (self->has_hold) tetris_app_t.draw_preview(self->hold, 565, 255);

    DrawRectangle(48, 41, TETRIS_BOARD_WIDTH * TETRIS_CELL_SIZE + 4,
                  TETRIS_BOARD_HEIGHT * TETRIS_CELL_SIZE + 4, BLACK);
    for (int row = 0; row < TETRIS_BOARD_HEIGHT; row++) {
        for (int column = 0; column < TETRIS_BOARD_WIDTH; column++) {
            int value = self->board.cells[row][column];
            Color color = value == 0 ? (Color){31, 36, 54, 255} : tetris_app_t.piece_color(value - 1);
            tetris_app_t.draw_cell(column, row, color);
        }
    }
    if (self->state == TETRIS_PLAYING) {
        int ghost_y = self->active.y;
        while (tetris_app_t.can_place(self, self->active.type, self->active.x, ghost_y + 1, self->active.rotation)) ghost_y++;
        for (int cell = 0; cell < 4; cell++) {
            int x;
            int y;
            tetris_app_t.piece_cell(self->active.type, self->active.rotation, cell, &x, &y);
            if (ghost_y + y >= 0) tetris_app_t.draw_cell(self->active.x + x, ghost_y + y, (Color){90, 100, 125, 255});
            if (self->active.y + y >= 0)
                tetris_app_t.draw_cell(self->active.x + x, self->active.y + y, tetris_app_t.piece_color(self->active.type));
        }
    } else {
        DrawRectangle(70, 300, 260, 74, (Color){10, 10, 15, 220});
        DrawText("GAME OVER", 105, 310, 30, RED);
        DrawText("ENTER TO RESTART", 101, 345, 17, RAYWHITE);
    }
}

} tetris_app_t;

@test "tetris model and render" {
    tetris_app_t app;
    app.init(13579u);
    @assert(!(app.state != TETRIS_PLAYING || app.active.type > TETROMINO_Z));
    @assert(!(app.can_place(TETROMINO_I, -20, 0, 0)));
    tetris_event_t key = {0};
    key.type = TETRIS_EVENT_KEYBOARD;
    key.key = KEY_C;
    tetromino_type_t held = app.active.type;
    app.event(&key);
    @assert(!(!app.has_hold || app.hold != held || !app.hold_used));

    memset(app.board.cells, 0, sizeof(app.board.cells));
    for (int column = 0; column < TETRIS_BOARD_WIDTH; column++)
        if (column != 4 && column != 5) app.board.cells[TETRIS_BOARD_HEIGHT - 1][column] = 1;
    app.active = (tetris_piece_t){TETROMINO_O, 3, 17, 0};
    app.state = TETRIS_PLAYING;
    app.lock_piece();
    @assert(!(app.lines != 1 || app.score < 100 || app.state != TETRIS_PLAYING));
    bool cleared_row_matches = true;
    for (int column = 0; column < TETRIS_BOARD_WIDTH; column++) {
        unsigned char value = app.board.cells[TETRIS_BOARD_HEIGHT - 1][column];
        if ((column == 4 || column == 5) != (value != 0)) cleared_row_matches = false;
    }
    @assert(cleared_row_matches);

    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(TETRIS_SCREEN_WIDTH, TETRIS_SCREEN_HEIGHT, "C-plus test render");
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
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/tetris.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}



int main(int argc, char** argv) {
        InitWindow(TETRIS_SCREEN_WIDTH, TETRIS_SCREEN_HEIGHT, "C-plus | Tetris");
    defer CloseWindow();
    SetTargetFPS(120);
    tetris_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 5u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            tetris_event_t event = {0};
            event.type = TETRIS_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        Vector2 mouse = GetMousePosition();
        tetris_event_t mouse_event = {0};
        mouse_event.type = TETRIS_EVENT_MOUSE;
        mouse_event.position = mouse;
        app.event(&mouse_event);

        tetris_event_t clock = {0};
        clock.type = TETRIS_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.soft_drop_down = IsKeyDown(KEY_DOWN) || IsKeyDown(KEY_S);
        app.event(&clock);

        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
