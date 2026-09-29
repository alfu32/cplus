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

#define QBERT_SCREEN_WIDTH 900
#define QBERT_SCREEN_HEIGHT 720
#define QBERT_ROWS 7
#define QBERT_TILE_COUNT ((QBERT_ROWS * (QBERT_ROWS + 1)) / 2)
#define QBERT_MAX_ENEMIES 4

typedef enum qbert_event_type_t {
    QBERT_EVENT_KEYBOARD,
    QBERT_EVENT_MOUSE,
    QBERT_EVENT_CLOCK
} qbert_event_type_t;

typedef enum qbert_state_t {
    QBERT_READY,
    QBERT_PLAYING,
    QBERT_LEVEL_COMPLETE,
    QBERT_GAME_OVER
} qbert_state_t;

typedef enum qbert_move_t {
    QBERT_UP_LEFT,
    QBERT_UP_RIGHT,
    QBERT_DOWN_LEFT,
    QBERT_DOWN_RIGHT
} qbert_move_t;

typedef struct qbert_tile_t {
    uint8_t state;
    uint8_t target_state;
} qbert_tile_t;

typedef struct qbert_actor_t {
    int row;
    int column;
    bool active;
} qbert_actor_t;

typedef struct qbert_enemy_t {
    qbert_actor_t actor;
    float step_interval;
    float accumulator;
} qbert_enemy_t;

typedef struct qbert_event_t {
    qbert_event_type_t type;
    int key;
    float delta_seconds;
} qbert_event_t;

typedef struct qbert_app_t {
    qbert_state_t state;
    qbert_tile_t tiles[QBERT_TILE_COUNT];
    qbert_actor_t player;
    qbert_enemy_t enemies[QBERT_MAX_ENEMIES];
    int enemy_count;
    int lives;
    int level;
    int score;
    uint32_t random_state;

    static pub int tile_index(int row, int column) {
        if (row < 0 || row >= QBERT_ROWS || column < 0 || column > row) return -1;
        return (row * (row + 1)) / 2 + column;
    }

    static pub uint32_t random_next(mut uint32_t* state) {
        uint32_t x = *state;
        if (x == 0u) x = 0x6d2b79f5u;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        *state = x;
        return x;
    }

    static pub Vector2 tile_center(int row, int column) {
        float tile_w = 86.0f;
        float tile_h = 54.0f;
        float x = QBERT_SCREEN_WIDTH * 0.5f + (column - row * 0.5f) * tile_w;
        float y = 105.0f + row * tile_h;
        return (Vector2){x, y};
    }

    static pub bool neighbor(int row, int column, qbert_move_t move, mut int* out_row, mut int* out_column) {
        int next_row = row;
        int next_column = column;
        switch (move) {
            case QBERT_UP_LEFT:    next_row--; next_column--; break;
            case QBERT_UP_RIGHT:   next_row--; break;
            case QBERT_DOWN_LEFT:  next_row++; break;
            case QBERT_DOWN_RIGHT: next_row++; next_column++; break;
        }
        *out_row = next_row;
        *out_column = next_column;
        return qbert_app_t.tile_index(next_row, next_column) >= 0;
    }

    pub void reset_board(borrowed mut *self) {
        for (int i = 0; i < QBERT_TILE_COUNT; i++) {
            self->tiles[i].state = 0;
            self->tiles[i].target_state = 1;
        }
        self->player = (qbert_actor_t){0, 0, true};
        int first = qbert_app_t.tile_index(0, 0);
        self->tiles[first].state = self->tiles[first].target_state;
    }

    pub void spawn_enemies(borrowed mut *self) {
        self->enemy_count = 2 + (self->level > 2 ? 1 : 0);
        if (self->enemy_count > QBERT_MAX_ENEMIES) self->enemy_count = QBERT_MAX_ENEMIES;
        for (int i = 0; i < QBERT_MAX_ENEMIES; i++) {
            self->enemies[i].actor = (qbert_actor_t){0, 0, false};
            self->enemies[i].step_interval = 0.72f - self->level * 0.035f + i * 0.08f;
            if (self->enemies[i].step_interval < 0.28f) self->enemies[i].step_interval = 0.28f;
            self->enemies[i].accumulator = 0.0f;
        }
        for (int i = 0; i < self->enemy_count; i++) {
            self->enemies[i].actor.row = QBERT_ROWS - 1;
            self->enemies[i].actor.column = (i * 2 + 1) % QBERT_ROWS;
            self->enemies[i].actor.active = true;
        }
    }

    pub void init(borrowed mut *self, uint32_t seed) {
        memset(self, 0, sizeof(*self));
        self->state = QBERT_READY;
        self->lives = 3;
        self->level = 1;
        self->random_state = seed == 0u ? 1u : seed;
        qbert_app_t.reset_board(self);
        qbert_app_t.spawn_enemies(self);
    }

    pub bool board_complete(borrowed const *self) {
        for (int i = 0; i < QBERT_TILE_COUNT; i++) {
            if (self->tiles[i].state != self->tiles[i].target_state) return false;
        }
        return true;
    }

    pub void lose_life(borrowed mut *self) {
        self->lives--;
        if (self->lives <= 0) {
            self->state = QBERT_GAME_OVER;
            return;
        }
        self->player = (qbert_actor_t){0, 0, true};
        qbert_app_t.spawn_enemies(self);
    }

    pub void land_player(borrowed mut *self) {
        int index = qbert_app_t.tile_index(self->player.row, self->player.column);
        if (index < 0) return;
        if (self->tiles[index].state != self->tiles[index].target_state) {
            self->tiles[index].state = self->tiles[index].target_state;
            self->score += 25;
        }
        if (qbert_app_t.board_complete(self)) {
            self->state = QBERT_LEVEL_COMPLETE;
            self->score += 500;
            self->level++;
            qbert_app_t.reset_board(self);
            qbert_app_t.spawn_enemies(self);
            self->state = QBERT_PLAYING;
        }
    }

    pub void move_player(borrowed mut *self, qbert_move_t move) {
        if (self->state == QBERT_READY) self->state = QBERT_PLAYING;
        if (self->state != QBERT_PLAYING) return;
        int row = 0;
        int column = 0;
        if (!qbert_app_t.neighbor(self->player.row, self->player.column, move, &row, &column)) {
            qbert_app_t.lose_life(self);
            return;
        }
        self->player.row = row;
        self->player.column = column;
        qbert_app_t.land_player(self);
    }

    pub bool actor_collision(borrowed const *self, borrowed const qbert_actor_t* actor) {
        return actor->active && actor->row == self->player.row && actor->column == self->player.column;
    }

    pub void step_enemy(borrowed mut *self, int enemy_index) {
        qbert_enemy_t* enemy = &self->enemies[enemy_index];
        if (!enemy->actor.active) return;

        int best_row = enemy->actor.row;
        int best_column = enemy->actor.column;
        int best_distance = 999;
        qbert_move_t moves[4] = {QBERT_UP_LEFT, QBERT_UP_RIGHT, QBERT_DOWN_LEFT, QBERT_DOWN_RIGHT};
        int start = (int)(qbert_app_t.random_next(&self->random_state) % 4u);
        for (int j = 0; j < 4; j++) {
            qbert_move_t move = moves[(start + j) % 4];
            int row = 0;
            int column = 0;
            if (!qbert_app_t.neighbor(enemy->actor.row, enemy->actor.column, move, &row, &column)) continue;
            int distance = row > self->player.row ? row - self->player.row : self->player.row - row;
            int dc = column > self->player.column ? column - self->player.column : self->player.column - column;
            distance += dc;
            if (distance < best_distance) {
                best_distance = distance;
                best_row = row;
                best_column = column;
            }
        }
        enemy->actor.row = best_row;
        enemy->actor.column = best_column;
        if (qbert_app_t.actor_collision(self, &enemy->actor)) qbert_app_t.lose_life(self);
    }

    pub void clock(borrowed mut *self, borrowed const qbert_event_t* event) {
        if (self->state != QBERT_PLAYING) return;
        float dt = event->delta_seconds;
        if (dt < 0.0f) dt = 0.0f;
        if (dt > 0.05f) dt = 0.05f;
        for (int i = 0; i < self->enemy_count; i++) {
            qbert_enemy_t* enemy = &self->enemies[i];
            enemy->accumulator += dt;
            if (enemy->accumulator >= enemy->step_interval) {
                enemy->accumulator -= enemy->step_interval;
                qbert_app_t.step_enemy(self, i);
                if (self->state == QBERT_GAME_OVER) return;
            }
        }
    }

    pub void event(borrowed mut *self, borrowed const qbert_event_t* event) {
        if (event->type == QBERT_EVENT_KEYBOARD) {
            if (event->key == KEY_LEFT || event->key == KEY_A) qbert_app_t.move_player(self, QBERT_DOWN_LEFT);
            else if (event->key == KEY_RIGHT || event->key == KEY_D) qbert_app_t.move_player(self, QBERT_DOWN_RIGHT);
            else if (event->key == KEY_UP || event->key == KEY_W) qbert_app_t.move_player(self, QBERT_UP_RIGHT);
            else if (event->key == KEY_DOWN || event->key == KEY_S) qbert_app_t.move_player(self, QBERT_UP_LEFT);
            else if (event->key == KEY_ENTER && self->state == QBERT_GAME_OVER) qbert_app_t.init(self, self->random_state + 1u);
        } else if (event->type == QBERT_EVENT_CLOCK) {
            qbert_app_t.clock(self, event);
        }
    }

    static pub void draw_tile(int row, int column, uint8_t state) {
        Vector2 c = qbert_app_t.tile_center(row, column);
        Color top = state ? (Color){245, 190, 52, 255} : (Color){72, 112, 205, 255};
        Color left = state ? (Color){200, 125, 42, 255} : (Color){44, 72, 145, 255};
        Color right = state ? (Color){230, 148, 47, 255} : (Color){55, 88, 170, 255};
        Vector2 a = {c.x, c.y - 22.0f};
        Vector2 b = {c.x + 42.0f, c.y};
        Vector2 d = {c.x, c.y + 22.0f};
        Vector2 e = {c.x - 42.0f, c.y};
        Vector2 down = {c.x, c.y + 48.0f};
        DrawTriangle(a, b, e, top);
        DrawTriangle(b, d, e, top);
        DrawTriangle(e, d, down, left);
        DrawTriangle(d, b, down, right);
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){16, 16, 38, 255});
        for (int row = 0; row < QBERT_ROWS; row++) {
            for (int column = 0; column <= row; column++) {
                int index = qbert_app_t.tile_index(row, column);
                qbert_app_t.draw_tile(row, column, self->tiles[index].state);
            }
        }
        for (int i = 0; i < self->enemy_count; i++) {
            if (!self->enemies[i].actor.active) continue;
            Vector2 c = qbert_app_t.tile_center(self->enemies[i].actor.row, self->enemies[i].actor.column);
            DrawCircle((int)c.x, (int)c.y - 28, 14.0f, RED);
        }
        Vector2 p = qbert_app_t.tile_center(self->player.row, self->player.column);
        DrawCircle((int)p.x, (int)p.y - 30, 16.0f, ORANGE);
        DrawText(TextFormat("SCORE %d   LIVES %d   LEVEL %d", self->score, self->lives, self->level), 18, 18, 24, RAYWHITE);
        DrawText("A/D = DOWN DIAGONALS   W/S = UP DIAGONALS", 205, 675, 20, LIGHTGRAY);
        if (self->state == QBERT_READY) DrawText("MOVE TO START", 355, 55, 26, RAYWHITE);
        if (self->state == QBERT_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 260, 350, 28, RED);
    }
} qbert_app_t;

@test "qbert graph traversal and tile transitions" {
    qbert_app_t app;
    app.init(123u);
    @assert(!(qbert_app_t.tile_index(6, 6) != 27 || qbert_app_t.tile_index(3, 4) != -1));

    app.move_player(QBERT_DOWN_RIGHT);
    @assert(!(app.state != QBERT_PLAYING || app.player.row != 1 || app.player.column != 1));
    int landed = qbert_app_t.tile_index(1, 1);
    @assert(!(app.tiles[landed].state != app.tiles[landed].target_state || app.score != 25));

    app.player = (qbert_actor_t){0, 0, true};
    int lives = app.lives;
    app.move_player(QBERT_UP_LEFT);
    @assert(!(app.lives != lives - 1));

    app.state = QBERT_PLAYING;
    app.player = (qbert_actor_t){3, 1, true};
    app.enemies[0].actor = (qbert_actor_t){4, 1, true};
    int pursuit_lives = app.lives;
    app.step_enemy(0);
    @assert(!(app.lives != pursuit_lives - 1));
}

int main(int argc, char** argv) {
    InitWindow(QBERT_SCREEN_WIDTH, QBERT_SCREEN_HEIGHT, "C-plus | Q*bert");
    defer CloseWindow();
    SetTargetFPS(120);
    qbert_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 1u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            qbert_event_t event = {0};
            event.type = QBERT_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        qbert_event_t clock = {0};
        clock.type = QBERT_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
