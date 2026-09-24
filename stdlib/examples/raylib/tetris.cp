comptime import "stdlib:/graphics/raylib.cp";

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

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
} tetris_app_t;

static const unsigned char tetris_shapes[7][4][4] = {
    {{0,0,0,0},{1,1,1,1},{0,0,0,0},{0,0,0,0}},
    {{1,0,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
    {{0,0,1,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
    {{0,0,0,0},{0,1,1,0},{0,1,1,0},{0,0,0,0}},
    {{0,1,1,0},{1,1,0,0},{0,0,0,0},{0,0,0,0}},
    {{0,1,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
    {{1,1,0,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}}
};

static uint32_t tetris_random(tetris_app_t* app) {
    uint32_t value = app->random_state;
    if (value == 0) value = 0xc2b2ae35u;
    value ^= value << 13;
    value ^= value >> 17;
    value ^= value << 5;
    app->random_state = value;
    return value;
}

static bool tetris_piece_cell(tetromino_type_t type, int rotation, int cell, int* out_x, int* out_y) {
    if (type < TETROMINO_I || type > TETROMINO_Z) return false;
    int seen = 0;
    for (int row = 0; row < 4; row++) {
        for (int column = 0; column < 4; column++) {
            if (tetris_shapes[type][row][column] == 0) continue;
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

static bool tetris_can_place(const tetris_app_t* app, tetromino_type_t type, int x, int y, int rotation) {
    for (int cell = 0; cell < 4; cell++) {
        int offset_x;
        int offset_y;
        if (!tetris_piece_cell(type, rotation, cell, &offset_x, &offset_y)) return false;
        int board_x = x + offset_x;
        int board_y = y + offset_y;
        if (board_x < 0 || board_x >= TETRIS_BOARD_WIDTH || board_y >= TETRIS_BOARD_HEIGHT) return false;
        if (board_y >= 0 && app->board.cells[board_y][board_x] != 0) return false;
    }
    return true;
}

static void tetris_spawn(tetris_app_t* app) {
    app->active = (tetris_piece_t){app->next, 3, 0, 0};
    app->next = (tetromino_type_t)(tetris_random(app) % 7u);
    app->hold_used = false;
    if (!tetris_can_place(app, app->active.type, app->active.x, app->active.y, app->active.rotation))
        app->state = TETRIS_GAME_OVER;
}

static void tetris_init(tetris_app_t* app, uint32_t seed) {
    memset(app, 0, sizeof(*app));
    app->state = TETRIS_PLAYING;
    app->level = 1;
    app->hold = TETROMINO_NONE;
    app->random_state = seed;
    app->next = (tetromino_type_t)(tetris_random(app) % 7u);
    tetris_spawn(app);
}

static int tetris_clear_lines(tetris_app_t* app) {
    int cleared = 0;
    for (int row = TETRIS_BOARD_HEIGHT - 1; row >= 0; row--) {
        bool full = true;
        for (int column = 0; column < TETRIS_BOARD_WIDTH; column++)
            full = full && app->board.cells[row][column] != 0;
        if (!full) continue;
        for (int move_row = row; move_row > 0; move_row--)
            memcpy(app->board.cells[move_row], app->board.cells[move_row - 1], TETRIS_BOARD_WIDTH);
        memset(app->board.cells[0], 0, TETRIS_BOARD_WIDTH);
        cleared++;
        row++;
    }
    if (cleared > 0) {
        static const int points[5] = {0, 100, 300, 500, 800};
        app->lines += cleared;
        app->score += points[cleared > 4 ? 4 : cleared] * app->level;
        app->level = app->lines / 10 + 1;
    }
    return cleared;
}

static void tetris_lock_piece(tetris_app_t* app) {
    for (int cell = 0; cell < 4; cell++) {
        int offset_x;
        int offset_y;
        tetris_piece_cell(app->active.type, app->active.rotation, cell, &offset_x, &offset_y);
        int x = app->active.x + offset_x;
        int y = app->active.y + offset_y;
        if (y < 0) {
            app->state = TETRIS_GAME_OVER;
            return;
        }
        app->board.cells[y][x] = (unsigned char)(app->active.type + 1);
    }
    tetris_clear_lines(app);
    tetris_spawn(app);
}

static bool tetris_move(tetris_app_t* app, int dx, int dy) {
    if (!tetris_can_place(app, app->active.type, app->active.x + dx, app->active.y + dy, app->active.rotation))
        return false;
    app->active.x += dx;
    app->active.y += dy;
    return true;
}

static void tetris_rotate(tetris_app_t* app, int direction) {
    int rotation = (app->active.rotation + direction + 4) % 4;
    static const int kicks[] = {0, -1, 1, -2, 2};
    for (int i = 0; i < 5; i++) {
        if (tetris_can_place(app, app->active.type, app->active.x + kicks[i], app->active.y, rotation)) {
            app->active.x += kicks[i];
            app->active.rotation = rotation;
            return;
        }
    }
}

static void tetris_hold_piece(tetris_app_t* app) {
    if (app->hold_used) return;
    tetromino_type_t outgoing = app->active.type;
    if (app->has_hold) {
        app->active.type = app->hold;
    } else {
        app->active.type = app->next;
        app->next = (tetromino_type_t)(tetris_random(app) % 7u);
        app->has_hold = true;
    }
    app->hold = outgoing;
    app->active.x = 3;
    app->active.y = 0;
    app->active.rotation = 0;
    app->hold_used = true;
    if (!tetris_can_place(app, app->active.type, app->active.x, app->active.y, app->active.rotation))
        app->state = TETRIS_GAME_OVER;
}

static void tetris_hard_drop(tetris_app_t* app) {
    int distance = 0;
    while (tetris_move(app, 0, 1)) distance++;
    app->score += distance * 2;
    tetris_lock_piece(app);
}

static void tetris_key(tetris_app_t* app, int key) {
    if (app->state == TETRIS_GAME_OVER) {
        if (key == KEY_ENTER) tetris_init(app, app->random_state + 1u);
        return;
    }
    if (key == KEY_LEFT || key == KEY_A) tetris_move(app, -1, 0);
    else if (key == KEY_RIGHT || key == KEY_D) tetris_move(app, 1, 0);
    else if (key == KEY_DOWN || key == KEY_S) {
        if (tetris_move(app, 0, 1)) app->score++;
        else tetris_lock_piece(app);
    }
    else if (key == KEY_UP || key == KEY_X) tetris_rotate(app, 1);
    else if (key == KEY_Z) tetris_rotate(app, -1);
    else if (key == KEY_SPACE) tetris_hard_drop(app);
    else if (key == KEY_C) tetris_hold_piece(app);
}

static float tetris_drop_interval(int level) {
    float interval = 0.8f;
    for (int i = 1; i < level && interval > 0.08f; i++) interval *= 0.82f;
    return interval < 0.08f ? 0.08f : interval;
}

static void tetris_clock(tetris_app_t* app, const tetris_event_t* event) {
    if (app->state != TETRIS_PLAYING) return;
    float dt = event->delta_seconds;
    if (dt < 0.0f) dt = 0.0f;
    if (dt > 0.1f) dt = 0.1f;
    app->fall_accumulator += dt;
    float interval = event->soft_drop_down ? 0.055f : tetris_drop_interval(app->level);
    while (app->fall_accumulator >= interval && app->state == TETRIS_PLAYING) {
        app->fall_accumulator -= interval;
        if (tetris_move(app, 0, 1)) {
            if (event->soft_drop_down) app->score++;
        } else {
            tetris_lock_piece(app);
        }
    }
}

static void tetris_event(tetris_app_t* app, const tetris_event_t* event) {
    if (event->type == TETRIS_EVENT_KEYBOARD) tetris_key(app, event->key);
    else if (event->type == TETRIS_EVENT_CLOCK) tetris_clock(app, event);
    else (void)event->position;
}

static Color tetris_piece_color(int kind) {
    static const Color colors[7] = {SKYBLUE, BLUE, ORANGE, YELLOW, GREEN, PURPLE, RED};
    if (kind < 0 || kind >= 7) return GRAY;
    return colors[kind];
}

static void tetris_draw_cell(int x, int y, Color color) {
    int screen_x = 50 + x * TETRIS_CELL_SIZE;
    int screen_y = 43 + y * TETRIS_CELL_SIZE;
    DrawRectangle(screen_x + 1, screen_y + 1, TETRIS_CELL_SIZE - 2, TETRIS_CELL_SIZE - 2, color);
    DrawRectangleLines(screen_x + 1, screen_y + 1, TETRIS_CELL_SIZE - 2, TETRIS_CELL_SIZE - 2, (Color){20, 25, 40, 255});
}

static void tetris_draw_preview(tetromino_type_t type, int left, int top) {
    if (type == TETROMINO_NONE) return;
    for (int cell = 0; cell < 4; cell++) {
        int x;
        int y;
        tetris_piece_cell(type, 0, cell, &x, &y);
        DrawRectangle(left + x * 19, top + y * 19, 17, 17, tetris_piece_color(type));
    }
}

static void tetris_render(const tetris_app_t* app) {
    ClearBackground((Color){16, 20, 34, 255});
    DrawText("TETRIS", 430, 35, 34, RAYWHITE);
    DrawText(TextFormat("SCORE %d", app->score), 430, 95, 22, RAYWHITE);
    DrawText(TextFormat("LINES %d", app->lines), 430, 130, 22, RAYWHITE);
    DrawText(TextFormat("LEVEL %d", app->level), 430, 165, 22, RAYWHITE);
    DrawText("NEXT", 430, 225, 20, LIGHTGRAY);
    tetris_draw_preview(app->next, 440, 255);
    DrawText("HOLD", 560, 225, 20, LIGHTGRAY);
    if (app->has_hold) tetris_draw_preview(app->hold, 565, 255);

    DrawRectangle(48, 41, TETRIS_BOARD_WIDTH * TETRIS_CELL_SIZE + 4,
                  TETRIS_BOARD_HEIGHT * TETRIS_CELL_SIZE + 4, BLACK);
    for (int row = 0; row < TETRIS_BOARD_HEIGHT; row++) {
        for (int column = 0; column < TETRIS_BOARD_WIDTH; column++) {
            int value = app->board.cells[row][column];
            Color color = value == 0 ? (Color){31, 36, 54, 255} : tetris_piece_color(value - 1);
            tetris_draw_cell(column, row, color);
        }
    }
    if (app->state == TETRIS_PLAYING) {
        int ghost_y = app->active.y;
        while (tetris_can_place(app, app->active.type, app->active.x, ghost_y + 1, app->active.rotation)) ghost_y++;
        for (int cell = 0; cell < 4; cell++) {
            int x;
            int y;
            tetris_piece_cell(app->active.type, app->active.rotation, cell, &x, &y);
            if (ghost_y + y >= 0) tetris_draw_cell(app->active.x + x, ghost_y + y, (Color){90, 100, 125, 255});
            if (app->active.y + y >= 0)
                tetris_draw_cell(app->active.x + x, app->active.y + y, tetris_piece_color(app->active.type));
        }
    } else {
        DrawRectangle(70, 300, 260, 74, (Color){10, 10, 15, 220});
        DrawText("GAME OVER", 105, 310, 30, RED);
        DrawText("ENTER TO RESTART", 101, 345, 17, RAYWHITE);
    }
}

static int tetris_self_test(void) {
    tetris_app_t app;
    tetris_init(&app, 13579u);
    if (app.state != TETRIS_PLAYING || app.active.type > TETROMINO_Z) return 1;
    if (tetris_can_place(&app, TETROMINO_I, -20, 0, 0)) return 2;
    tetris_event_t key = {0};
    key.type = TETRIS_EVENT_KEYBOARD;
    key.key = KEY_C;
    tetromino_type_t held = app.active.type;
    tetris_event(&app, &key);
    if (!app.has_hold || app.hold != held || !app.hold_used) return 3;

    memset(app.board.cells, 0, sizeof(app.board.cells));
    for (int column = 0; column < TETRIS_BOARD_WIDTH; column++)
        if (column != 4 && column != 5) app.board.cells[TETRIS_BOARD_HEIGHT - 1][column] = 1;
    app.active = (tetris_piece_t){TETROMINO_O, 3, 17, 0};
    app.state = TETRIS_PLAYING;
    tetris_lock_piece(&app);
    if (app.lines != 1 || app.score < 100 || app.state != TETRIS_PLAYING) return 4;
    for (int column = 0; column < TETRIS_BOARD_WIDTH; column++) {
        unsigned char value = app.board.cells[TETRIS_BOARD_HEIGHT - 1][column];
        if ((column == 4 || column == 5) != (value != 0)) return 5;
    }
    return 0;
}

int main(int argc, char** argv) {
    if (argc > 1 && strcmp(argv[1], "--self-test") == 0) {
        int result = tetris_self_test();
        if (result == 0) puts("Tetris self-test: PASS");
        return result;
    }

    InitWindow(TETRIS_SCREEN_WIDTH, TETRIS_SCREEN_HEIGHT, "C-plus | Tetris");
    defer CloseWindow();
    SetTargetFPS(120);
    tetris_app_t app;
    tetris_init(&app, (uint32_t)(GetTime() * 100000.0) + 5u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            tetris_event_t event = {0};
            event.type = TETRIS_EVENT_KEYBOARD;
            event.key = key;
            tetris_event(&app, &event);
        }
        Vector2 mouse = GetMousePosition();
        tetris_event_t mouse_event = {0};
        mouse_event.type = TETRIS_EVENT_MOUSE;
        mouse_event.position = mouse;
        tetris_event(&app, &mouse_event);

        tetris_event_t clock = {0};
        clock.type = TETRIS_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.soft_drop_down = IsKeyDown(KEY_DOWN) || IsKeyDown(KEY_S);
        tetris_event(&app, &clock);

        BeginDrawing();
        tetris_render(&app);
        EndDrawing();
    }
    return 0;
}
