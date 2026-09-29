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

#define LODE_SCREEN_WIDTH 980
#define LODE_SCREEN_HEIGHT 720
#define LODE_COLS 28
#define LODE_ROWS 18
#define LODE_CELL 32
#define LODE_MAX_ENEMIES 8
#define LODE_MAX_HOLES 12

typedef enum lode_event_type_t {
    LODE_EVENT_KEYBOARD,
    LODE_EVENT_MOUSE,
    LODE_EVENT_CLOCK
} lode_event_type_t;

typedef enum lode_state_t {
    LODE_READY,
    LODE_PLAYING,
    LODE_LEVEL_COMPLETE,
    LODE_GAME_OVER
} lode_state_t;

typedef enum lode_cell_t {
    LODE_EMPTY,
    LODE_BRICK,
    LODE_SOLID,
    LODE_LADDER,
    LODE_ROPE,
    LODE_GOLD,
    LODE_EXIT_LADDER
} lode_cell_t;

typedef struct lode_actor_t {
    int x;
    int y;
    bool alive;
} lode_actor_t;

typedef struct lode_enemy_t {
    lode_actor_t actor;
    float accumulator;
    float step_interval;
    float trapped_timer;
} lode_enemy_t;

typedef struct lode_hole_t {
    int x;
    int y;
    float timer;
    bool active;
} lode_hole_t;

typedef struct lode_event_t {
    lode_event_type_t type;
    int key;
    float delta_seconds;
    int move_dx;
    int move_dy;
} lode_event_t;

typedef struct lode_runner_app_t {
    lode_state_t state;
    lode_cell_t board[LODE_ROWS][LODE_COLS];
    lode_actor_t player;
    lode_enemy_t enemies[LODE_MAX_ENEMIES];
    lode_hole_t holes[LODE_MAX_HOLES];
    int enemy_count;
    int lives;
    int score;
    int level;
    int gold_remaining;
    int pending_dx;
    int pending_dy;
    float player_accumulator;
    float player_step_interval;

    static pub bool inside(int x, int y) {
        return x >= 0 && x < LODE_COLS && y >= 0 && y < LODE_ROWS;
    }

    pub bool hole_at(borrowed const *self, int x, int y) {
        for (int i = 0; i < LODE_MAX_HOLES; i++) {
            if (self->holes[i].active && self->holes[i].x == x && self->holes[i].y == y) return true;
        }
        return false;
    }

    pub bool solid_at(borrowed const *self, int x, int y) {
        if (!lode_runner_app_t.inside(x, y)) return true;
        lode_cell_t cell = self->board[y][x];
        if (cell == LODE_SOLID) return true;
        if (cell == LODE_BRICK && !lode_runner_app_t.hole_at(self, x, y)) return true;
        return false;
    }

    pub bool ladder_at(borrowed const *self, int x, int y) {
        if (!lode_runner_app_t.inside(x, y)) return false;
        lode_cell_t cell = self->board[y][x];
        return cell == LODE_LADDER || cell == LODE_EXIT_LADDER;
    }

    pub bool rope_at(borrowed const *self, int x, int y) {
        return lode_runner_app_t.inside(x, y) && self->board[y][x] == LODE_ROPE;
    }

    pub bool supported(borrowed const *self, int x, int y) {
        if (y >= LODE_ROWS - 1) return true;
        if (lode_runner_app_t.ladder_at(self, x, y)) return true;
        if (lode_runner_app_t.rope_at(self, x, y)) return true;
        return lode_runner_app_t.solid_at(self, x, y + 1);
    }

    pub bool passable(borrowed const *self, int x, int y) {
        return lode_runner_app_t.inside(x, y) && !lode_runner_app_t.solid_at(self, x, y);
    }

    pub void clear_level(borrowed mut *self) {
        memset(self->board, 0, sizeof(self->board));
        memset(self->holes, 0, sizeof(self->holes));
        memset(self->enemies, 0, sizeof(self->enemies));
        self->gold_remaining = 0;
    }

    pub void put(borrowed mut *self, int x, int y, lode_cell_t cell) {
        if (!lode_runner_app_t.inside(x, y)) return;
        if (self->board[y][x] == LODE_GOLD) self->gold_remaining--;
        self->board[y][x] = cell;
        if (cell == LODE_GOLD) self->gold_remaining++;
    }

    pub void build_level(borrowed mut *self) {
        lode_runner_app_t.clear_level(self);
        for (int x = 0; x < LODE_COLS; x++) self->board[LODE_ROWS - 1][x] = LODE_SOLID;
        for (int x = 2; x < 12; x++) self->board[14][x] = LODE_BRICK;
        for (int x = 15; x < 26; x++) self->board[14][x] = LODE_BRICK;
        for (int x = 6; x < 22; x++) self->board[10][x] = LODE_BRICK;
        for (int x = 1; x < 10; x++) self->board[6][x] = LODE_BRICK;
        for (int x = 18; x < 27; x++) self->board[6][x] = LODE_BRICK;
        for (int y = 10; y <= 16; y++) self->board[y][8] = LODE_LADDER;
        for (int y = 6; y <= 13; y++) self->board[y][19] = LODE_LADDER;
        for (int y = 2; y <= 9; y++) self->board[y][5] = LODE_LADDER;
        for (int x = 9; x <= 18; x++) self->board[8][x] = LODE_ROPE;
        for (int x = 12; x <= 23; x++) self->board[4][x] = LODE_ROPE;

        lode_runner_app_t.put(self, 4, 13, LODE_GOLD);
        lode_runner_app_t.put(self, 10, 9, LODE_GOLD);
        lode_runner_app_t.put(self, 22, 13, LODE_GOLD);
        lode_runner_app_t.put(self, 23, 5, LODE_GOLD);
        lode_runner_app_t.put(self, 14, 3, LODE_GOLD);

        self->player = (lode_actor_t){2, 16, true};
        self->enemy_count = 3 + self->level / 3;
        if (self->enemy_count > LODE_MAX_ENEMIES) self->enemy_count = LODE_MAX_ENEMIES;
        int starts[8][2] = {{25,16},{16,13},{24,9},{2,5},{20,3},{12,16},{13,9},{25,5}};
        for (int i = 0; i < self->enemy_count; i++) {
            self->enemies[i].actor = (lode_actor_t){starts[i][0], starts[i][1], true};
            self->enemies[i].step_interval = 0.18f + i * 0.015f;
            self->enemies[i].accumulator = 0.0f;
            self->enemies[i].trapped_timer = 0.0f;
        }
    }

    pub void init(borrowed mut *self) {
        memset(self, 0, sizeof(*self));
        self->state = LODE_READY;
        self->lives = 3;
        self->level = 1;
        self->player_step_interval = 0.095f;
        lode_runner_app_t.build_level(self);
    }

    pub void reveal_exit(borrowed mut *self) {
        for (int y = 0; y <= 5; y++) self->board[y][5] = LODE_EXIT_LADDER;
    }

    pub void collect_gold(borrowed mut *self) {
        if (self->board[self->player.y][self->player.x] != LODE_GOLD) return;
        self->board[self->player.y][self->player.x] = LODE_EMPTY;
        self->gold_remaining--;
        self->score += 100;
        if (self->gold_remaining == 0) lode_runner_app_t.reveal_exit(self);
    }

    pub void lose_life(borrowed mut *self) {
        self->lives--;
        if (self->lives <= 0) {
            self->state = LODE_GAME_OVER;
            return;
        }
        self->player = (lode_actor_t){2, 16, true};
        for (int i = 0; i < LODE_MAX_HOLES; i++) self->holes[i].active = false;
    }

    pub bool enemy_at(borrowed const *self, int x, int y) {
        for (int i = 0; i < self->enemy_count; i++) {
            if (self->enemies[i].actor.alive && self->enemies[i].actor.x == x && self->enemies[i].actor.y == y)
                return true;
        }
        return false;
    }

    pub void check_player_enemy(borrowed mut *self) {
        if (lode_runner_app_t.enemy_at(self, self->player.x, self->player.y)) lode_runner_app_t.lose_life(self);
    }

    pub void step_player(borrowed mut *self) {
        if (self->state != LODE_PLAYING) return;
        int x = self->player.x;
        int y = self->player.y;

        if (!lode_runner_app_t.supported(self, x, y) && !lode_runner_app_t.rope_at(self, x, y)) {
            if (lode_runner_app_t.passable(self, x, y + 1)) self->player.y++;
        } else if (self->pending_dy < 0 && lode_runner_app_t.ladder_at(self, x, y) && lode_runner_app_t.passable(self, x, y - 1)) {
            self->player.y--;
        } else if (self->pending_dy > 0 && lode_runner_app_t.passable(self, x, y + 1) &&
                   (lode_runner_app_t.ladder_at(self, x, y) || lode_runner_app_t.ladder_at(self, x, y + 1))) {
            self->player.y++;
        } else if (self->pending_dx != 0 && lode_runner_app_t.passable(self, x + self->pending_dx, y)) {
            self->player.x += self->pending_dx;
        }

        lode_runner_app_t.collect_gold(self);
        lode_runner_app_t.check_player_enemy(self);
        if (self->state == LODE_PLAYING && self->gold_remaining == 0 && self->player.y == 0 && self->player.x == 5) {
            self->state = LODE_LEVEL_COMPLETE;
            self->score += 500;
            self->level++;
            lode_runner_app_t.build_level(self);
            self->state = LODE_PLAYING;
        }
    }

    pub bool dig(borrowed mut *self, int dx) {
        if (self->state != LODE_PLAYING || dx == 0) return false;
        int x = self->player.x + dx;
        int y = self->player.y + 1;
        if (!lode_runner_app_t.inside(x, y) || self->board[y][x] != LODE_BRICK || lode_runner_app_t.hole_at(self, x, y)) return false;
        if (!lode_runner_app_t.passable(self, self->player.x + dx, self->player.y)) return false;
        for (int i = 0; i < LODE_MAX_HOLES; i++) {
            if (self->holes[i].active) continue;
            self->holes[i] = (lode_hole_t){x, y, 4.5f, true};
            return true;
        }
        return false;
    }

    pub void update_holes(borrowed mut *self, float dt) {
        for (int i = 0; i < LODE_MAX_HOLES; i++) {
            lode_hole_t* hole = &self->holes[i];
            if (!hole->active) continue;
            hole->timer -= dt;
            for (int e = 0; e < self->enemy_count; e++) {
                lode_enemy_t* enemy = &self->enemies[e];
                if (enemy->actor.alive && enemy->actor.x == hole->x && enemy->actor.y == hole->y) {
                    enemy->trapped_timer = 1.3f;
                }
            }
            if (hole->timer <= 0.0f) {
                for (int e = 0; e < self->enemy_count; e++) {
                    lode_enemy_t* enemy = &self->enemies[e];
                    if (enemy->actor.alive && enemy->actor.x == hole->x && enemy->actor.y == hole->y) {
                        enemy->actor.alive = false;
                        self->score += 150;
                    }
                }
                if (self->player.x == hole->x && self->player.y == hole->y) lode_runner_app_t.lose_life(self);
                hole->active = false;
            }
        }
    }

    static pub int enemy_distance(int x, int y, int tx, int ty) {
        int dx = x > tx ? x - tx : tx - x;
        int dy = y > ty ? y - ty : ty - y;
        return dx + dy;
    }

    pub void step_enemy(borrowed mut *self, int index) {
        lode_enemy_t* enemy = &self->enemies[index];
        if (!enemy->actor.alive) return;
        if (enemy->trapped_timer > 0.0f) return;
        int x = enemy->actor.x;
        int y = enemy->actor.y;

        if (!lode_runner_app_t.supported(self, x, y) && lode_runner_app_t.passable(self, x, y + 1)) {
            enemy->actor.y++;
            return;
        }

        int best_x = x;
        int best_y = y;
        int best_distance = lode_runner_app_t.enemy_distance(x, y, self->player.x, self->player.y);
        int dxs[4] = {-1, 1, 0, 0};
        int dys[4] = {0, 0, -1, 1};
        for (int d = 0; d < 4; d++) {
            int nx = x + dxs[d];
            int ny = y + dys[d];
            if (!lode_runner_app_t.passable(self, nx, ny)) continue;
            if (dys[d] < 0 && !lode_runner_app_t.ladder_at(self, x, y)) continue;
            if (dys[d] > 0 && !(lode_runner_app_t.ladder_at(self, x, y) || lode_runner_app_t.ladder_at(self, nx, ny))) continue;
            if (dxs[d] != 0 && !lode_runner_app_t.supported(self, x, y) && !lode_runner_app_t.rope_at(self, x, y)) continue;
            int distance = lode_runner_app_t.enemy_distance(nx, ny, self->player.x, self->player.y);
            if (distance < best_distance) {
                best_distance = distance;
                best_x = nx;
                best_y = ny;
            }
        }
        enemy->actor.x = best_x;
        enemy->actor.y = best_y;
    }

    pub void clock(borrowed mut *self, borrowed const lode_event_t* event) {
        if (self->state != LODE_PLAYING) return;
        self->pending_dx = event->move_dx;
        self->pending_dy = event->move_dy;
        float dt = event->delta_seconds;
        if (dt < 0.0f) dt = 0.0f;
        if (dt > 0.05f) dt = 0.05f;
        lode_runner_app_t.update_holes(self, dt);
        if (self->state != LODE_PLAYING) return;

        self->player_accumulator += dt;
        if (self->player_accumulator >= self->player_step_interval) {
            self->player_accumulator -= self->player_step_interval;
            lode_runner_app_t.step_player(self);
        }
        if (self->state != LODE_PLAYING) return;

        for (int i = 0; i < self->enemy_count; i++) {
            lode_enemy_t* enemy = &self->enemies[i];
            if (!enemy->actor.alive) continue;
            if (enemy->trapped_timer > 0.0f) {
                enemy->trapped_timer -= dt;
                if (enemy->trapped_timer < 0.0f) enemy->trapped_timer = 0.0f;
                continue;
            }
            enemy->accumulator += dt;
            if (enemy->accumulator >= enemy->step_interval) {
                enemy->accumulator -= enemy->step_interval;
                lode_runner_app_t.step_enemy(self, i);
                lode_runner_app_t.check_player_enemy(self);
                if (self->state != LODE_PLAYING) return;
            }
        }
    }

    pub void event(borrowed mut *self, borrowed const lode_event_t* event) {
        if (event->type == LODE_EVENT_KEYBOARD) {
            if (self->state == LODE_READY) self->state = LODE_PLAYING;
            if (event->key == KEY_LEFT || event->key == KEY_A) { self->pending_dx = -1; self->pending_dy = 0; }
            else if (event->key == KEY_RIGHT || event->key == KEY_D) { self->pending_dx = 1; self->pending_dy = 0; }
            else if (event->key == KEY_UP || event->key == KEY_W) { self->pending_dx = 0; self->pending_dy = -1; }
            else if (event->key == KEY_DOWN || event->key == KEY_S) { self->pending_dx = 0; self->pending_dy = 1; }
            else if (event->key == KEY_Z) lode_runner_app_t.dig(self, -1);
            else if (event->key == KEY_X) lode_runner_app_t.dig(self, 1);
            else if (event->key == KEY_ENTER && self->state == LODE_GAME_OVER) lode_runner_app_t.init(self);
        } else if (event->type == LODE_EVENT_CLOCK) {
            lode_runner_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){12, 15, 24, 255});
        int ox = (LODE_SCREEN_WIDTH - LODE_COLS * LODE_CELL) / 2;
        int oy = 70;
        for (int y = 0; y < LODE_ROWS; y++) {
            for (int x = 0; x < LODE_COLS; x++) {
                Rectangle r = {(float)(ox + x * LODE_CELL), (float)(oy + y * LODE_CELL), LODE_CELL - 1.0f, LODE_CELL - 1.0f};
                lode_cell_t cell = self->board[y][x];
                if (cell == LODE_BRICK && !lode_runner_app_t.hole_at(self, x, y)) DrawRectangleRec(r, (Color){154, 72, 46, 255});
                else if (cell == LODE_SOLID) DrawRectangleRec(r, (Color){80, 85, 97, 255});
                else if (cell == LODE_LADDER || cell == LODE_EXIT_LADDER) {
                    Color c = cell == LODE_EXIT_LADDER ? LIME : GOLD;
                    DrawRectangle(ox + x * LODE_CELL + 7, oy + y * LODE_CELL, 4, LODE_CELL, c);
                    DrawRectangle(ox + x * LODE_CELL + 21, oy + y * LODE_CELL, 4, LODE_CELL, c);
                    DrawLine(ox + x * LODE_CELL + 7, oy + y * LODE_CELL + 12, ox + x * LODE_CELL + 25, oy + y * LODE_CELL + 12, c);
                    DrawLine(ox + x * LODE_CELL + 7, oy + y * LODE_CELL + 24, ox + x * LODE_CELL + 25, oy + y * LODE_CELL + 24, c);
                } else if (cell == LODE_ROPE) DrawLine(ox + x * LODE_CELL, oy + y * LODE_CELL + 10, ox + (x + 1) * LODE_CELL, oy + y * LODE_CELL + 10, LIGHTGRAY);
                else if (cell == LODE_GOLD) DrawCircle(ox + x * LODE_CELL + 16, oy + y * LODE_CELL + 16, 7.0f, YELLOW);
            }
        }
        for (int i = 0; i < self->enemy_count; i++) {
            if (!self->enemies[i].actor.alive) continue;
            int x = ox + self->enemies[i].actor.x * LODE_CELL + 8;
            int y = oy + self->enemies[i].actor.y * LODE_CELL + 5;
            DrawRectangle(x, y, 16, 25, RED);
        }
        DrawRectangle(ox + self->player.x * LODE_CELL + 8, oy + self->player.y * LODE_CELL + 5, 16, 25, SKYBLUE);
        DrawText(TextFormat("SCORE %d   LIVES %d   LEVEL %d   GOLD %d", self->score, self->lives, self->level, self->gold_remaining), 22, 20, 24, RAYWHITE);
        DrawText("ARROWS/WASD MOVE   Z/X DIG LEFT/RIGHT", 270, 668, 20, LIGHTGRAY);
        if (self->state == LODE_READY) DrawText("MOVE TO START", 405, 46, 22, RAYWHITE);
        if (self->state == LODE_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 300, 330, 28, RED);
    }
} lode_runner_app_t;

@test "lode runner platform gravity digging and pursuit" {
    lode_runner_app_t app;
    app.init();
    @assert(!(app.state != LODE_READY || app.gold_remaining != 5 || app.enemy_count < 3));

    app.state = LODE_PLAYING;
    app.player = (lode_actor_t){3,13,true};
    app.board[14][4] = LODE_BRICK;
    app.board[13][4] = LODE_EMPTY;
    @assert(!(!app.dig(1) || !app.hole_at(4,14) || app.solid_at(4,14)));

    app.player = (lode_actor_t){12,7,true};
    app.board[8][12] = LODE_EMPTY;
    app.board[9][12] = LODE_EMPTY;
    app.pending_dx = 1;
    app.pending_dy = 0;
    app.step_player();
    @assert(!(app.player.y != 8));

    app.player = (lode_actor_t){10,16,true};
    app.enemies[0].actor = (lode_actor_t){14,16,true};
    app.enemies[0].trapped_timer = 0.0f;
    int old_distance = lode_runner_app_t.enemy_distance(app.enemies[0].actor.x, app.enemies[0].actor.y, app.player.x, app.player.y);
    app.step_enemy(0);
    int new_distance = lode_runner_app_t.enemy_distance(app.enemies[0].actor.x, app.enemies[0].actor.y, app.player.x, app.player.y);
    @assert(!(new_distance >= old_distance));

    app.gold_remaining = 1;
    app.player = (lode_actor_t){4,13,true};
    app.board[13][4] = LODE_GOLD;
    app.collect_gold();
    @assert(!(app.gold_remaining != 0 || app.board[0][5] != LODE_EXIT_LADDER));
}

int main(int argc, char** argv) {
    InitWindow(LODE_SCREEN_WIDTH, LODE_SCREEN_HEIGHT, "C-plus | Lode Runner");
    defer CloseWindow();
    SetTargetFPS(120);
    lode_runner_app_t app;
    app.init();
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            lode_event_t event = {0};
            event.type = LODE_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        lode_event_t clock = {0};
        clock.type = LODE_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.move_dx = (IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D) ? 1 : 0) -
                        (IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A) ? 1 : 0);
        clock.move_dy = (IsKeyDown(KEY_DOWN) || IsKeyDown(KEY_S) ? 1 : 0) -
                        (IsKeyDown(KEY_UP) || IsKeyDown(KEY_W) ? 1 : 0);
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
