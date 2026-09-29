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

#define BOMBERMAN_SCREEN_WIDTH 900
#define BOMBERMAN_SCREEN_HEIGHT 720
#define BOMBERMAN_COLS 15
#define BOMBERMAN_ROWS 11
#define BOMBERMAN_CELL 52
#define BOMBERMAN_MAX_BOMBS 16
#define BOMBERMAN_MAX_ENEMIES 8

typedef enum bomberman_event_type_t {
    BOMBERMAN_EVENT_KEYBOARD,
    BOMBERMAN_EVENT_MOUSE,
    BOMBERMAN_EVENT_CLOCK
} bomberman_event_type_t;

typedef enum bomberman_state_t {
    BOMBERMAN_READY,
    BOMBERMAN_PLAYING,
    BOMBERMAN_LEVEL_COMPLETE,
    BOMBERMAN_GAME_OVER
} bomberman_state_t;

typedef enum bomberman_cell_t {
    BOMBERMAN_FLOOR,
    BOMBERMAN_WALL,
    BOMBERMAN_CRATE
} bomberman_cell_t;

typedef struct bomberman_bomb_t {
    int x;
    int y;
    int radius;
    float timer;
    bool active;
} bomberman_bomb_t;

typedef struct bomberman_enemy_t {
    int x;
    int y;
    int dx;
    int dy;
    float accumulator;
    float step_interval;
    bool alive;
} bomberman_enemy_t;

typedef struct bomberman_event_t {
    bomberman_event_type_t type;
    int key;
    float delta_seconds;
} bomberman_event_t;

typedef struct bomberman_app_t {
    bomberman_state_t state;
    bomberman_cell_t board[BOMBERMAN_ROWS][BOMBERMAN_COLS];
    float flames[BOMBERMAN_ROWS][BOMBERMAN_COLS];
    bomberman_bomb_t bombs[BOMBERMAN_MAX_BOMBS];
    bomberman_enemy_t enemies[BOMBERMAN_MAX_ENEMIES];
    int enemy_count;
    int player_x;
    int player_y;
    int lives;
    int score;
    int level;
    int bomb_capacity;
    int blast_radius;
    uint32_t random_state;

    static pub uint32_t random_next(mut uint32_t* state) {
        uint32_t x = *state;
        if (x == 0u) x = 0x9e3779b9u;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        *state = x;
        return x;
    }

    static pub bool inside(int x, int y) {
        return x >= 0 && x < BOMBERMAN_COLS && y >= 0 && y < BOMBERMAN_ROWS;
    }

    pub int bomb_at(borrowed const *self, int x, int y) {
        for (int i = 0; i < BOMBERMAN_MAX_BOMBS; i++) {
            if (self->bombs[i].active && self->bombs[i].x == x && self->bombs[i].y == y) return i;
        }
        return -1;
    }

    pub bool cell_walkable(borrowed const *self, int x, int y) {
        if (!bomberman_app_t.inside(x, y)) return false;
        if (self->board[y][x] != BOMBERMAN_FLOOR) return false;
        return bomberman_app_t.bomb_at(self, x, y) < 0;
    }

    pub void build_level(borrowed mut *self) {
        memset(self->board, 0, sizeof(self->board));
        memset(self->flames, 0, sizeof(self->flames));
        memset(self->bombs, 0, sizeof(self->bombs));
        memset(self->enemies, 0, sizeof(self->enemies));

        for (int y = 0; y < BOMBERMAN_ROWS; y++) {
            for (int x = 0; x < BOMBERMAN_COLS; x++) {
                bool border = x == 0 || y == 0 || x == BOMBERMAN_COLS - 1 || y == BOMBERMAN_ROWS - 1;
                bool pillar = (x % 2 == 0) && (y % 2 == 0);
                if (border || pillar) self->board[y][x] = BOMBERMAN_WALL;
                else self->board[y][x] = BOMBERMAN_FLOOR;
            }
        }

        for (int y = 1; y < BOMBERMAN_ROWS - 1; y++) {
            for (int x = 1; x < BOMBERMAN_COLS - 1; x++) {
                if (self->board[y][x] != BOMBERMAN_FLOOR) continue;
                if ((x <= 2 && y == 1) || (x == 1 && y <= 2)) continue;
                uint32_t r = bomberman_app_t.random_next(&self->random_state);
                if ((r % 100u) < 46u) self->board[y][x] = BOMBERMAN_CRATE;
            }
        }

        self->player_x = 1;
        self->player_y = 1;
        self->enemy_count = 3 + self->level / 2;
        if (self->enemy_count > BOMBERMAN_MAX_ENEMIES) self->enemy_count = BOMBERMAN_MAX_ENEMIES;
        int spawned = 0;
        for (int y = BOMBERMAN_ROWS - 2; y >= 1 && spawned < self->enemy_count; y--) {
            for (int x = BOMBERMAN_COLS - 2; x >= 1 && spawned < self->enemy_count; x--) {
                if (self->board[y][x] != BOMBERMAN_FLOOR) continue;
                if (x + y < 8) continue;
                bomberman_enemy_t* enemy = &self->enemies[spawned++];
                enemy->x = x;
                enemy->y = y;
                enemy->dx = -1;
                enemy->dy = 0;
                enemy->step_interval = 0.36f - self->level * 0.015f;
                if (enemy->step_interval < 0.17f) enemy->step_interval = 0.17f;
                enemy->alive = true;
            }
        }
        self->enemy_count = spawned;
    }

    pub void init(borrowed mut *self, uint32_t seed) {
        memset(self, 0, sizeof(*self));
        self->state = BOMBERMAN_READY;
        self->lives = 3;
        self->level = 1;
        self->bomb_capacity = 2;
        self->blast_radius = 2;
        self->random_state = seed == 0u ? 1u : seed;
        bomberman_app_t.build_level(self);
    }

    pub int active_bomb_count(borrowed const *self) {
        int count = 0;
        for (int i = 0; i < BOMBERMAN_MAX_BOMBS; i++) if (self->bombs[i].active) count++;
        return count;
    }

    pub bool place_bomb(borrowed mut *self) {
        if (self->state == BOMBERMAN_READY) self->state = BOMBERMAN_PLAYING;
        if (self->state != BOMBERMAN_PLAYING) return false;
        if (bomberman_app_t.active_bomb_count(self) >= self->bomb_capacity) return false;
        if (bomberman_app_t.bomb_at(self, self->player_x, self->player_y) >= 0) return false;
        for (int i = 0; i < BOMBERMAN_MAX_BOMBS; i++) {
            if (self->bombs[i].active) continue;
            self->bombs[i] = (bomberman_bomb_t){self->player_x, self->player_y, self->blast_radius, 2.2f, true};
            return true;
        }
        return false;
    }

    pub void move_player(borrowed mut *self, int dx, int dy) {
        if (self->state == BOMBERMAN_READY) self->state = BOMBERMAN_PLAYING;
        if (self->state != BOMBERMAN_PLAYING) return;
        int nx = self->player_x + dx;
        int ny = self->player_y + dy;
        if (bomberman_app_t.cell_walkable(self, nx, ny)) {
            self->player_x = nx;
            self->player_y = ny;
        }
    }

    pub void lose_life(borrowed mut *self) {
        self->lives--;
        if (self->lives <= 0) {
            self->state = BOMBERMAN_GAME_OVER;
            return;
        }
        self->player_x = 1;
        self->player_y = 1;
        memset(self->bombs, 0, sizeof(self->bombs));
        memset(self->flames, 0, sizeof(self->flames));
    }

    pub void ignite(borrowed mut *self, int x, int y) {
        if (!bomberman_app_t.inside(x, y)) return;
        if (self->flames[y][x] < 0.42f) self->flames[y][x] = 0.42f;
        int bomb = bomberman_app_t.bomb_at(self, x, y);
        if (bomb >= 0) self->bombs[bomb].timer = 0.0f;
        for (int i = 0; i < self->enemy_count; i++) {
            if (self->enemies[i].alive && self->enemies[i].x == x && self->enemies[i].y == y) {
                self->enemies[i].alive = false;
                self->score += 100;
            }
        }
    }

    pub void damage_player_if_flame(borrowed mut *self) {
        if (self->state == BOMBERMAN_PLAYING && self->flames[self->player_y][self->player_x] > 0.0f)
            bomberman_app_t.lose_life(self);
    }

    pub void propagate(borrowed mut *self, int x, int y, int dx, int dy, int radius) {
        for (int step = 1; step <= radius; step++) {
            int nx = x + dx * step;
            int ny = y + dy * step;
            if (!bomberman_app_t.inside(nx, ny)) return;
            if (self->board[ny][nx] == BOMBERMAN_WALL) return;
            if (self->board[ny][nx] == BOMBERMAN_CRATE) {
                self->board[ny][nx] = BOMBERMAN_FLOOR;
                self->flames[ny][nx] = 0.42f;
                self->score += 10;
                return;
            }
            bomberman_app_t.ignite(self, nx, ny);
        }
    }

    pub void explode(borrowed mut *self, int index) {
        if (index < 0 || index >= BOMBERMAN_MAX_BOMBS || !self->bombs[index].active) return;
        bomberman_bomb_t bomb = self->bombs[index];
        self->bombs[index].active = false;
        bomberman_app_t.ignite(self, bomb.x, bomb.y);
        bomberman_app_t.propagate(self, bomb.x, bomb.y,  1,  0, bomb.radius);
        bomberman_app_t.propagate(self, bomb.x, bomb.y, -1,  0, bomb.radius);
        bomberman_app_t.propagate(self, bomb.x, bomb.y,  0,  1, bomb.radius);
        bomberman_app_t.propagate(self, bomb.x, bomb.y,  0, -1, bomb.radius);
    }

    pub int living_enemies(borrowed const *self) {
        int count = 0;
        for (int i = 0; i < self->enemy_count; i++) if (self->enemies[i].alive) count++;
        return count;
    }

    pub void step_enemy(borrowed mut *self, int index) {
        bomberman_enemy_t* enemy = &self->enemies[index];
        if (!enemy->alive) return;
        int dirs[4][2] = {{1,0},{-1,0},{0,1},{0,-1}};
        int start = (int)(bomberman_app_t.random_next(&self->random_state) % 4u);
        int best = -1;
        int best_distance = 999;
        for (int k = 0; k < 4; k++) {
            int d = (start + k) % 4;
            int nx = enemy->x + dirs[d][0];
            int ny = enemy->y + dirs[d][1];
            if (!bomberman_app_t.cell_walkable(self, nx, ny)) continue;
            int dist = nx > self->player_x ? nx - self->player_x : self->player_x - nx;
            dist += ny > self->player_y ? ny - self->player_y : self->player_y - ny;
            if (dist < best_distance) {
                best_distance = dist;
                best = d;
            }
        }
        if (best >= 0) {
            enemy->dx = dirs[best][0];
            enemy->dy = dirs[best][1];
            enemy->x += enemy->dx;
            enemy->y += enemy->dy;
        }
        if (enemy->x == self->player_x && enemy->y == self->player_y) bomberman_app_t.lose_life(self);
    }

    pub void clock(borrowed mut *self, borrowed const bomberman_event_t* event) {
        if (self->state != BOMBERMAN_PLAYING) return;
        float dt = event->delta_seconds;
        if (dt < 0.0f) dt = 0.0f;
        if (dt > 0.05f) dt = 0.05f;

        for (int y = 0; y < BOMBERMAN_ROWS; y++) {
            for (int x = 0; x < BOMBERMAN_COLS; x++) {
                if (self->flames[y][x] > 0.0f) {
                    self->flames[y][x] -= dt;
                    if (self->flames[y][x] < 0.0f) self->flames[y][x] = 0.0f;
                }
            }
        }

        for (int i = 0; i < BOMBERMAN_MAX_BOMBS; i++) {
            if (!self->bombs[i].active) continue;
            self->bombs[i].timer -= dt;
        }
        bool exploded = true;
        while (exploded) {
            exploded = false;
            for (int i = 0; i < BOMBERMAN_MAX_BOMBS; i++) {
                if (self->bombs[i].active && self->bombs[i].timer <= 0.0f) {
                    bomberman_app_t.explode(self, i);
                    exploded = true;
                }
            }
        }
        bomberman_app_t.damage_player_if_flame(self);
        if (self->state != BOMBERMAN_PLAYING) return;

        for (int i = 0; i < self->enemy_count; i++) {
            bomberman_enemy_t* enemy = &self->enemies[i];
            if (!enemy->alive) continue;
            if (self->flames[enemy->y][enemy->x] > 0.0f) {
                enemy->alive = false;
                self->score += 100;
                continue;
            }
            enemy->accumulator += dt;
            if (enemy->accumulator >= enemy->step_interval) {
                enemy->accumulator -= enemy->step_interval;
                bomberman_app_t.step_enemy(self, i);
                if (self->state != BOMBERMAN_PLAYING) return;
            }
        }

        if (bomberman_app_t.living_enemies(self) == 0) {
            self->state = BOMBERMAN_LEVEL_COMPLETE;
            self->score += 500;
            self->level++;
            bomberman_app_t.build_level(self);
            self->state = BOMBERMAN_PLAYING;
        }
    }

    pub void event(borrowed mut *self, borrowed const bomberman_event_t* event) {
        if (event->type == BOMBERMAN_EVENT_KEYBOARD) {
            if (event->key == KEY_LEFT || event->key == KEY_A) bomberman_app_t.move_player(self, -1, 0);
            else if (event->key == KEY_RIGHT || event->key == KEY_D) bomberman_app_t.move_player(self, 1, 0);
            else if (event->key == KEY_UP || event->key == KEY_W) bomberman_app_t.move_player(self, 0, -1);
            else if (event->key == KEY_DOWN || event->key == KEY_S) bomberman_app_t.move_player(self, 0, 1);
            else if (event->key == KEY_SPACE) bomberman_app_t.place_bomb(self);
            else if (event->key == KEY_ENTER && self->state == BOMBERMAN_GAME_OVER) bomberman_app_t.init(self, self->random_state + 1u);
        } else if (event->type == BOMBERMAN_EVENT_CLOCK) {
            bomberman_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){20, 24, 27, 255});
        int offset_x = (BOMBERMAN_SCREEN_WIDTH - BOMBERMAN_COLS * BOMBERMAN_CELL) / 2;
        int offset_y = 82;
        for (int y = 0; y < BOMBERMAN_ROWS; y++) {
            for (int x = 0; x < BOMBERMAN_COLS; x++) {
                Rectangle r = {(float)(offset_x + x * BOMBERMAN_CELL), (float)(offset_y + y * BOMBERMAN_CELL), BOMBERMAN_CELL - 2.0f, BOMBERMAN_CELL - 2.0f};
                Color color = (Color){52, 86, 55, 255};
                if (self->board[y][x] == BOMBERMAN_WALL) color = (Color){70, 78, 92, 255};
                else if (self->board[y][x] == BOMBERMAN_CRATE) color = (Color){141, 91, 48, 255};
                DrawRectangleRec(r, color);
                if (self->flames[y][x] > 0.0f) DrawRectangleRec(r, (Color){255, 179, 35, 235});
            }
        }
        for (int i = 0; i < BOMBERMAN_MAX_BOMBS; i++) {
            if (!self->bombs[i].active) continue;
            int cx = offset_x + self->bombs[i].x * BOMBERMAN_CELL + BOMBERMAN_CELL / 2;
            int cy = offset_y + self->bombs[i].y * BOMBERMAN_CELL + BOMBERMAN_CELL / 2;
            DrawCircle(cx, cy, 16.0f, BLACK);
        }
        for (int i = 0; i < self->enemy_count; i++) {
            if (!self->enemies[i].alive) continue;
            int cx = offset_x + self->enemies[i].x * BOMBERMAN_CELL + BOMBERMAN_CELL / 2;
            int cy = offset_y + self->enemies[i].y * BOMBERMAN_CELL + BOMBERMAN_CELL / 2;
            DrawCircle(cx, cy, 17.0f, RED);
        }
        DrawRectangle(offset_x + self->player_x * BOMBERMAN_CELL + 12, offset_y + self->player_y * BOMBERMAN_CELL + 10,
                      BOMBERMAN_CELL - 26, BOMBERMAN_CELL - 22, SKYBLUE);
        DrawText(TextFormat("SCORE %d   LIVES %d   LEVEL %d   ENEMIES %d", self->score, self->lives, self->level,
                 bomberman_app_t.living_enemies(self)), 22, 20, 24, RAYWHITE);
        DrawText("ARROWS/WASD MOVE   SPACE BOMB", 275, 680, 21, LIGHTGRAY);
        if (self->state == BOMBERMAN_READY) DrawText("MOVE OR BOMB TO START", 318, 51, 23, RAYWHITE);
        if (self->state == BOMBERMAN_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 260, 350, 28, RED);
    }
} bomberman_app_t;

@test "bomberman bombs propagation and destructible cells" {
    bomberman_app_t app;
    app.init(7u);
    memset(app.board, 0, sizeof(app.board));
    for (int x = 0; x < BOMBERMAN_COLS; x++) { app.board[0][x] = BOMBERMAN_WALL; app.board[BOMBERMAN_ROWS-1][x] = BOMBERMAN_WALL; }
    for (int y = 0; y < BOMBERMAN_ROWS; y++) { app.board[y][0] = BOMBERMAN_WALL; app.board[y][BOMBERMAN_COLS-1] = BOMBERMAN_WALL; }
    app.state = BOMBERMAN_PLAYING;
    app.player_x = 3;
    app.player_y = 3;
    @assert(!(!app.place_bomb() || app.active_bomb_count() != 1));

    app.player_x = 1;
    app.player_y = 1;
    app.board[3][5] = BOMBERMAN_CRATE;
    app.bombs[0].timer = 0.0f;
    bomberman_event_t tick = {0};
    tick.type = BOMBERMAN_EVENT_CLOCK;
    tick.delta_seconds = 0.01f;
    app.clock(&tick);
    @assert(!(app.board[3][5] != BOMBERMAN_FLOOR || app.flames[3][5] <= 0.0f));

    memset(app.flames, 0, sizeof(app.flames));
    app.board[3][4] = BOMBERMAN_WALL;
    app.bombs[0] = (bomberman_bomb_t){3,3,3,0.0f,true};
    app.explode(0);
    @assert(!(app.flames[3][4] != 0.0f || app.flames[3][5] != 0.0f));

    app.board[3][4] = BOMBERMAN_FLOOR;
    app.bombs[0] = (bomberman_bomb_t){3,3,3,0.0f,true};
    app.bombs[1] = (bomberman_bomb_t){4,3,2,9.0f,true};
    app.explode(0);
    @assert(!(app.bombs[1].timer > 0.0f));
}

int main(int argc, char** argv) {
    InitWindow(BOMBERMAN_SCREEN_WIDTH, BOMBERMAN_SCREEN_HEIGHT, "C-plus | Bomberman");
    defer CloseWindow();
    SetTargetFPS(120);
    bomberman_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 1u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            bomberman_event_t event = {0};
            event.type = BOMBERMAN_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        bomberman_event_t clock = {0};
        clock.type = BOMBERMAN_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
