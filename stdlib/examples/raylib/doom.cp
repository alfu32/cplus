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
#include <math.h>

#define DOOM_SCREEN_WIDTH 1024
#define DOOM_SCREEN_HEIGHT 640
#define DOOM_VIEW_HEIGHT 560
#define DOOM_MAX_WIDTH 32
#define DOOM_MAX_HEIGHT 24
#define DOOM_MAX_ENEMIES 32
#define DOOM_LEVEL_COUNT 8
#define DOOM_PI 3.14159265358979323846f
#define DOOM_FOV (DOOM_PI / 3.0f)
#define DOOM_MAX_DEPTH 30.0f

typedef enum doom_event_type_t {
    DOOM_EVENT_KEYBOARD,
    DOOM_EVENT_CLOCK
} doom_event_type_t;

typedef enum doom_state_t {
    DOOM_MENU,
    DOOM_PLAYING,
    DOOM_WON,
    DOOM_DEAD
} doom_state_t;

typedef struct doom_event_t {
    doom_event_type_t type;
    int key;
    float delta_seconds;
    float move_forward;
    float move_strafe;
    float turn_axis;
    bool fire_down;
} doom_event_t;

typedef struct doom_enemy_t {
    float x;
    float y;
    int health;
    bool alive;
    float attack_cooldown;
} doom_enemy_t;

typedef struct doom_level_def_t {
    borrowed const char* name;
    borrowed const char* map;
} doom_level_def_t;

static const doom_level_def_t DOOM_LEVELS[DOOM_LEVEL_COUNT] = {
    {
        "1. Entryway",
        "################\n"
        "#P.....#.......#\n"
        "#......#..E....#\n"
        "#......#.......#\n"
        "#..............#\n"
        "#..####........#\n"
        "#..............#\n"
        "#.........E....#\n"
        "#..............#\n"
        "#.....######...#\n"
        "#..............#\n"
        "#.............X#\n"
        "################\n"
    },
    {
        "2. Crossfire",
        "##################\n"
        "#P.......#.......#\n"
        "#.######.#.#####.#\n"
        "#......#.#.....#.#\n"
        "#.E....#.....E.#.#\n"
        "#......#####...#.#\n"
        "#..............#.#\n"
        "#.############.#.#\n"
        "#..............#.#\n"
        "#..E...........#.#\n"
        "#..............#X#\n"
        "##################\n"
    },
    {
        "3. The Fork",
        "####################\n"
        "#P.................#\n"
        "#.########.#########\n"
        "#.#......#.#.......#\n"
        "#.#.E....#.#...E...#\n"
        "#.#......#.#.......#\n"
        "#.#......#.#.......#\n"
        "#....#####.#####...#\n"
        "#..................#\n"
        "#.######....######.#\n"
        "#......E...........#\n"
        "#.................X#\n"
        "####################\n"
    },
    {
        "4. Courtyard",
        "####################\n"
        "#P.................#\n"
        "#..................#\n"
        "#...############...#\n"
        "#...#..........#...#\n"
        "#...#..E....E..#...#\n"
        "#...#..........#...#\n"
        "#...#....##....#...#\n"
        "#...#....##....#...#\n"
        "#...#..........#...#\n"
        "#...####....####...#\n"
        "#........E.........#\n"
        "#.................X#\n"
        "####################\n"
    },
    {
        "5. Zigzag",
        "#######################\n"
        "#P.....#..............#\n"
        "#####..#.############.#\n"
        "#......#.#............#\n"
        "#.######.#.############\n"
        "#.#....E.#............#\n"
        "#.#.############.####.#\n"
        "#.#............#....#.#\n"
        "#.############.#.E..#.#\n"
        "#............#.#....#.#\n"
        "############.#.####.#.#\n"
        "#......E.....#......#X#\n"
        "#######################\n"
    },
    {
        "6. Reactor",
        "######################\n"
        "#P...................#\n"
        "#..######....######..#\n"
        "#..#....#....#....#..#\n"
        "#..#.E..######..E.#..#\n"
        "#..#..............#..#\n"
        "#..####..######..##..#\n"
        "#......#.#....#.#....#\n"
        "#.E....#.#.E..#.#....#\n"
        "#......#.#....#.#....#\n"
        "#..#####.######.####.#\n"
        "#...................X#\n"
        "######################\n"
    },
    {
        "7. Gauntlet",
        "########################\n"
        "#P.....................#\n"
        "#.####################.#\n"
        "#.#..E...............#.#\n"
        "#.#.################.#.#\n"
        "#.#.#..............#.#.#\n"
        "#.#.#.E..........E.#.#.#\n"
        "#.#.#.############.#.#.#\n"
        "#.#.#..............#.#.#\n"
        "#.#.################.#.#\n"
        "#.#........E.........#.#\n"
        "#.####################.#\n"
        "#.....................X#\n"
        "########################\n"
    },
    {
        "8. Last Stand",
        "########################\n"
        "#P.....................#\n"
        "#..####..######..####..#\n"
        "#..#..#..#....#..#..#..#\n"
        "#..#E.#..#.EE.#..#.E#..#\n"
        "#..#..#..#....#..#..#..#\n"
        "#..####..######..####..#\n"
        "#......................#\n"
        "#..####..######..####..#\n"
        "#..#E.#..#....#..#.E#..#\n"
        "#..#..#..#.E..#..#..#..#\n"
        "#..####..######..####..#\n"
        "#.....................X#\n"
        "########################\n"
    }
};

typedef struct doom_app_t {
    doom_state_t state;
    char map[DOOM_MAX_HEIGHT][DOOM_MAX_WIDTH + 1];
    int map_width;
    int map_height;
    int selected_level;
    int current_level;
    float player_x;
    float player_y;
    float player_angle;
    int health;
    int ammo;
    int score;
    float fire_cooldown;
    float hurt_flash;
    doom_enemy_t enemies[DOOM_MAX_ENEMIES];
    int enemy_count;
    float zbuffer[DOOM_SCREEN_WIDTH];

    static pub float normalize_angle(float angle) {
        while (angle < -DOOM_PI) angle += 2.0f * DOOM_PI;
        while (angle > DOOM_PI) angle -= 2.0f * DOOM_PI;
        return angle;
    }

    static pub float clamp(float value, float low, float high) {
        if (value < low) return low;
        if (value > high) return high;
        return value;
    }

    pub bool solid(borrowed const *self, float x, float y) {
        int ix = (int)floorf(x);
        int iy = (int)floorf(y);
        if (ix < 0 || iy < 0 || ix >= self->map_width || iy >= self->map_height) return true;
        return self->map[iy][ix] == '#';
    }

    pub bool exit_at(borrowed const *self, float x, float y) {
        int ix = (int)floorf(x);
        int iy = (int)floorf(y);
        if (ix < 0 || iy < 0 || ix >= self->map_width || iy >= self->map_height) return false;
        return self->map[iy][ix] == 'X';
    }

    pub bool line_of_sight(borrowed const *self, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float distance = sqrtf(dx * dx + dy * dy);
        if (distance < 0.001f) return true;
        int steps = (int)(distance / 0.08f);
        if (steps < 1) steps = 1;
        for (int i = 1; i < steps; i++) {
            float t = (float)i / (float)steps;
            if (doom_app_t.solid(self, ax + dx * t, ay + dy * t)) return false;
        }
        return true;
    }

    pub void clear_map(borrowed mut *self) {
        for (int y = 0; y < DOOM_MAX_HEIGHT; y++) {
            for (int x = 0; x < DOOM_MAX_WIDTH; x++) self->map[y][x] = '#';
            self->map[y][DOOM_MAX_WIDTH] = '\0';
        }
    }

    pub bool load_level(borrowed mut *self, int level_index) {
        if (level_index < 0 || level_index >= DOOM_LEVEL_COUNT) return false;
        doom_app_t.clear_map(self);
        memset(self->enemies, 0, sizeof(self->enemies));
        self->enemy_count = 0;
        self->map_width = 0;
        self->map_height = 0;
        borrowed const char* source = DOOM_LEVELS[level_index].map;
        int x = 0;
        int y = 0;
        bool found_player = false;
        while (*source != '\0' && y < DOOM_MAX_HEIGHT) {
            char ch = *source++;
            if (ch == '\r') continue;
            if (ch == '\n') {
                if (x > self->map_width) self->map_width = x;
                self->map[y][x < DOOM_MAX_WIDTH ? x : DOOM_MAX_WIDTH] = '\0';
                y++;
                x = 0;
                continue;
            }
            if (x >= DOOM_MAX_WIDTH) continue;
            if (ch == 'P') {
                self->player_x = x + 0.5f;
                self->player_y = y + 0.5f;
                self->player_angle = 0.0f;
                self->map[y][x] = '.';
                found_player = true;
            } else if (ch == 'E') {
                if (self->enemy_count < DOOM_MAX_ENEMIES) {
                    doom_enemy_t* enemy = &self->enemies[self->enemy_count++];
                    enemy->x = x + 0.5f;
                    enemy->y = y + 0.5f;
                    enemy->health = 2;
                    enemy->alive = true;
                    enemy->attack_cooldown = 0.0f;
                }
                self->map[y][x] = '.';
            } else {
                self->map[y][x] = ch;
            }
            x++;
        }
        if (x > 0 && y < DOOM_MAX_HEIGHT) {
            if (x > self->map_width) self->map_width = x;
            self->map[y][x < DOOM_MAX_WIDTH ? x : DOOM_MAX_WIDTH] = '\0';
            y++;
        }
        self->map_height = y;
        self->current_level = level_index;
        self->health = 100;
        self->ammo = 50;
        self->score = 0;
        self->fire_cooldown = 0.0f;
        self->hurt_flash = 0.0f;
        return found_player && self->map_width > 0 && self->map_height > 0;
    }

    pub void init(borrowed mut *self) {
        memset(self, 0, sizeof(*self));
        self->state = DOOM_MENU;
        self->selected_level = 0;
        self->current_level = -1;
        doom_app_t.clear_map(self);
    }

    pub void start_selected_level(borrowed mut *self) {
        if (doom_app_t.load_level(self, self->selected_level)) self->state = DOOM_PLAYING;
    }

    pub bool enemy_blocks(borrowed const *self, float x, float y, int ignored) {
        for (int i = 0; i < self->enemy_count; i++) {
            if (i == ignored || !self->enemies[i].alive) continue;
            float dx = self->enemies[i].x - x;
            float dy = self->enemies[i].y - y;
            if (dx * dx + dy * dy < 0.22f) return true;
        }
        return false;
    }

    pub void move_player(borrowed mut *self, float forward, float strafe, float dt) {
        if (self->state != DOOM_PLAYING) return;
        float speed = 3.0f;
        float dx = (cosf(self->player_angle) * forward + cosf(self->player_angle + DOOM_PI * 0.5f) * strafe) * speed * dt;
        float dy = (sinf(self->player_angle) * forward + sinf(self->player_angle + DOOM_PI * 0.5f) * strafe) * speed * dt;
        float nx = self->player_x + dx;
        float ny = self->player_y + dy;
        if (!doom_app_t.solid(self, nx, self->player_y)) self->player_x = nx;
        if (!doom_app_t.solid(self, self->player_x, ny)) self->player_y = ny;
        if (doom_app_t.exit_at(self, self->player_x, self->player_y)) self->state = DOOM_WON;
    }

    pub void fire(borrowed mut *self) {
        if (self->state != DOOM_PLAYING || self->fire_cooldown > 0.0f || self->ammo <= 0) return;
        self->fire_cooldown = 0.22f;
        self->ammo--;
        int target = -1;
        float best_distance = DOOM_MAX_DEPTH;
        for (int i = 0; i < self->enemy_count; i++) {
            doom_enemy_t* enemy = &self->enemies[i];
            if (!enemy->alive) continue;
            float dx = enemy->x - self->player_x;
            float dy = enemy->y - self->player_y;
            float distance = sqrtf(dx * dx + dy * dy);
            if (distance >= best_distance) continue;
            float angle = doom_app_t.normalize_angle(atan2f(dy, dx) - self->player_angle);
            float tolerance = 0.055f + 0.18f / (distance + 1.0f);
            if (fabsf(angle) > tolerance) continue;
            if (!doom_app_t.line_of_sight(self, self->player_x, self->player_y, enemy->x, enemy->y)) continue;
            target = i;
            best_distance = distance;
        }
        if (target >= 0) {
            doom_enemy_t* enemy = &self->enemies[target];
            enemy->health--;
            if (enemy->health <= 0) {
                enemy->alive = false;
                self->score += 100;
            } else {
                self->score += 25;
            }
        }
    }

    pub void update_enemy(borrowed mut *self, int index, float dt) {
        doom_enemy_t* enemy = &self->enemies[index];
        if (!enemy->alive) return;
        if (enemy->attack_cooldown > 0.0f) enemy->attack_cooldown -= dt;
        float dx = self->player_x - enemy->x;
        float dy = self->player_y - enemy->y;
        float distance = sqrtf(dx * dx + dy * dy);
        if (distance < 0.7f) {
            if (enemy->attack_cooldown <= 0.0f) {
                self->health -= 10;
                self->hurt_flash = 0.14f;
                enemy->attack_cooldown = 0.75f;
                if (self->health <= 0) {
                    self->health = 0;
                    self->state = DOOM_DEAD;
                }
            }
            return;
        }
        if (distance > 9.0f || !doom_app_t.line_of_sight(self, enemy->x, enemy->y, self->player_x, self->player_y)) return;
        float speed = 0.85f * dt;
        float vx = dx / distance * speed;
        float vy = dy / distance * speed;
        float nx = enemy->x + vx;
        float ny = enemy->y + vy;
        if (!doom_app_t.solid(self, nx, enemy->y) && !doom_app_t.enemy_blocks(self, nx, enemy->y, index)) enemy->x = nx;
        if (!doom_app_t.solid(self, enemy->x, ny) && !doom_app_t.enemy_blocks(self, enemy->x, ny, index)) enemy->y = ny;
    }

    pub void clock(borrowed mut *self, borrowed const doom_event_t* event) {
        if (self->state != DOOM_PLAYING) return;
        float dt = doom_app_t.clamp(event->delta_seconds, 0.0f, 0.05f);
        self->player_angle = doom_app_t.normalize_angle(self->player_angle + event->turn_axis * 2.2f * dt);
        doom_app_t.move_player(self, event->move_forward, event->move_strafe, dt);
        if (event->fire_down) doom_app_t.fire(self);
        if (self->fire_cooldown > 0.0f) self->fire_cooldown -= dt;
        if (self->hurt_flash > 0.0f) self->hurt_flash -= dt;
        for (int i = 0; i < self->enemy_count && self->state == DOOM_PLAYING; i++) doom_app_t.update_enemy(self, i, dt);
    }

    pub void event(borrowed mut *self, borrowed const doom_event_t* event) {
        if (event->type == DOOM_EVENT_KEYBOARD) {
            if (self->state == DOOM_MENU) {
                if (event->key == KEY_UP || event->key == KEY_W) self->selected_level = (self->selected_level + DOOM_LEVEL_COUNT - 1) % DOOM_LEVEL_COUNT;
                else if (event->key == KEY_DOWN || event->key == KEY_S) self->selected_level = (self->selected_level + 1) % DOOM_LEVEL_COUNT;
                else if (event->key >= KEY_ONE && event->key <= KEY_EIGHT) {
                    self->selected_level = event->key - KEY_ONE;
                    doom_app_t.start_selected_level(self);
                } else if (event->key == KEY_ENTER || event->key == KEY_SPACE) {
                    doom_app_t.start_selected_level(self);
                }
            } else if (event->key == KEY_M) {
                self->state = DOOM_MENU;
            } else if ((self->state == DOOM_WON || self->state == DOOM_DEAD) && (event->key == KEY_ENTER || event->key == KEY_SPACE)) {
                self->state = DOOM_MENU;
            }
        } else if (event->type == DOOM_EVENT_CLOCK) {
            doom_app_t.clock(self, event);
        }
    }

    pub float cast_ray(borrowed mut *self, float angle, int screen_x) {
        float sin_a = sinf(angle);
        float cos_a = cosf(angle);
        float distance = 0.02f;
        while (distance < DOOM_MAX_DEPTH) {
            float x = self->player_x + cos_a * distance;
            float y = self->player_y + sin_a * distance;
            if (doom_app_t.solid(self, x, y)) break;
            distance += 0.025f;
        }
        float corrected = distance * cosf(angle - self->player_angle);
        if (corrected < 0.03f) corrected = 0.03f;
        if (screen_x >= 0 && screen_x < DOOM_SCREEN_WIDTH) self->zbuffer[screen_x] = corrected;
        return corrected;
    }

    pub void render_world(borrowed mut *self) {
        DrawRectangle(0, 0, DOOM_SCREEN_WIDTH, DOOM_VIEW_HEIGHT / 2, (Color){38, 42, 52, 255});
        DrawRectangle(0, DOOM_VIEW_HEIGHT / 2, DOOM_SCREEN_WIDTH, DOOM_VIEW_HEIGHT / 2, (Color){42, 34, 30, 255});
        for (int x = 0; x < DOOM_SCREEN_WIDTH; x += 2) {
            float camera = ((float)x / (float)DOOM_SCREEN_WIDTH) - 0.5f;
            float angle = self->player_angle + camera * DOOM_FOV;
            float distance = doom_app_t.cast_ray(self, angle, x);
            if (x + 1 < DOOM_SCREEN_WIDTH) self->zbuffer[x + 1] = distance;
            int wall_height = (int)(DOOM_VIEW_HEIGHT / distance);
            if (wall_height > DOOM_VIEW_HEIGHT * 2) wall_height = DOOM_VIEW_HEIGHT * 2;
            int top = DOOM_VIEW_HEIGHT / 2 - wall_height / 2;
            int shade = (int)(225.0f / (1.0f + distance * 0.12f));
            if (shade < 35) shade = 35;
            Color wall = {(unsigned char)shade, (unsigned char)(shade * 0.28f), (unsigned char)(shade * 0.22f), 255};
            DrawRectangle(x, top, 2, wall_height, wall);
        }

        for (int i = 0; i < self->enemy_count; i++) {
            doom_enemy_t* enemy = &self->enemies[i];
            if (!enemy->alive) continue;
            float dx = enemy->x - self->player_x;
            float dy = enemy->y - self->player_y;
            float distance = sqrtf(dx * dx + dy * dy);
            float relative = doom_app_t.normalize_angle(atan2f(dy, dx) - self->player_angle);
            if (fabsf(relative) >= DOOM_FOV * 0.62f || distance < 0.1f) continue;
            float corrected = distance * cosf(relative);
            int center_x = (int)((0.5f + relative / DOOM_FOV) * DOOM_SCREEN_WIDTH);
            if (center_x < 0 || center_x >= DOOM_SCREEN_WIDTH) continue;
            if (corrected >= self->zbuffer[center_x]) continue;
            int size = (int)(DOOM_VIEW_HEIGHT / corrected * 0.72f);
            if (size > 360) size = 360;
            int x = center_x - size / 2;
            int y = DOOM_VIEW_HEIGHT / 2 - size / 2;
            DrawRectangle(x, y, size, size, (Color){126, 22, 20, 255});
            DrawCircle(center_x - size / 5, y + size / 3, size / 14.0f, YELLOW);
            DrawCircle(center_x + size / 5, y + size / 3, size / 14.0f, YELLOW);
            DrawRectangle(center_x - size / 5, y + (size * 2) / 3, (size * 2) / 5, size / 12, BLACK);
        }

        DrawRectangle(DOOM_SCREEN_WIDTH / 2 - 9, DOOM_VIEW_HEIGHT / 2, 18, 2, RAYWHITE);
        DrawRectangle(DOOM_SCREEN_WIDTH / 2, DOOM_VIEW_HEIGHT / 2 - 9, 2, 18, RAYWHITE);
    }

    pub void render_minimap(borrowed const *self) {
        int scale = 6;
        int ox = DOOM_SCREEN_WIDTH - self->map_width * scale - 12;
        int oy = 10;
        for (int y = 0; y < self->map_height; y++) {
            for (int x = 0; x < self->map_width; x++) {
                char ch = self->map[y][x];
                Color color = ch == '#' ? (Color){90, 90, 100, 230} : (Color){25, 25, 30, 180};
                if (ch == 'X') color = GREEN;
                DrawRectangle(ox + x * scale, oy + y * scale, scale - 1, scale - 1, color);
            }
        }
        for (int i = 0; i < self->enemy_count; i++) {
            if (self->enemies[i].alive) DrawCircle(ox + (int)(self->enemies[i].x * scale), oy + (int)(self->enemies[i].y * scale), 2.0f, RED);
        }
        DrawCircle(ox + (int)(self->player_x * scale), oy + (int)(self->player_y * scale), 2.5f, YELLOW);
    }

    pub void render_menu(borrowed const *self) {
        ClearBackground((Color){13, 13, 16, 255});
        DrawText("C+ DOOM", 375, 48, 58, (Color){210, 38, 32, 255});
        DrawText("SELECT LEVEL", 412, 126, 24, LIGHTGRAY);
        for (int i = 0; i < DOOM_LEVEL_COUNT; i++) {
            int y = 176 + i * 44;
            bool selected = i == self->selected_level;
            if (selected) DrawRectangle(294, y - 7, 440, 37, (Color){82, 18, 18, 255});
            DrawText(DOOM_LEVELS[i].name, 320, y, 22, selected ? RAYWHITE : GRAY);
        }
        DrawText("UP/DOWN + ENTER   or   1-8", 337, 552, 20, LIGHTGRAY);
        DrawText("Levels are embedded as source strings", 326, 588, 18, GRAY);
    }

    pub void render(borrowed mut *self) {
        if (self->state == DOOM_MENU) {
            doom_app_t.render_menu(self);
            return;
        }
        ClearBackground(BLACK);
        doom_app_t.render_world(self);
        if (self->hurt_flash > 0.0f) DrawRectangle(0, 0, DOOM_SCREEN_WIDTH, DOOM_VIEW_HEIGHT, (Color){180, 0, 0, 70});
        DrawRectangle(0, DOOM_VIEW_HEIGHT, DOOM_SCREEN_WIDTH, DOOM_SCREEN_HEIGHT - DOOM_VIEW_HEIGHT, (Color){16, 16, 18, 255});
        DrawText(TextFormat("HEALTH %03d", self->health), 20, 580, 26, self->health > 25 ? RAYWHITE : RED);
        DrawText(TextFormat("AMMO %02d", self->ammo), 220, 580, 26, RAYWHITE);
        DrawText(TextFormat("SCORE %05d", self->score), 390, 580, 26, RAYWHITE);
        DrawText(DOOM_LEVELS[self->current_level].name, 650, 580, 20, LIGHTGRAY);
        doom_app_t.render_minimap(self);
        if (self->state == DOOM_WON) {
            DrawRectangle(220, 220, 584, 120, (Color){0, 0, 0, 220});
            DrawText("LEVEL COMPLETE", 332, 240, 40, GREEN);
            DrawText("ENTER TO RETURN TO LEVEL SELECT", 292, 298, 22, RAYWHITE);
        } else if (self->state == DOOM_DEAD) {
            DrawRectangle(220, 220, 584, 120, (Color){0, 0, 0, 220});
            DrawText("YOU DIED", 402, 240, 42, RED);
            DrawText("ENTER TO RETURN TO LEVEL SELECT", 292, 298, 22, RAYWHITE);
        }
    }
} doom_app_t;

@test "doom embedded levels and raycast model" {
    doom_app_t app;
    app.init();
    @assert(!(DOOM_LEVEL_COUNT != 8 || app.state != DOOM_MENU || app.selected_level != 0));

    @assert(!(!app.load_level(0) || app.map_width != 16 || app.map_height != 13));
    @assert(!(app.enemy_count != 2 || app.health != 100 || app.ammo != 50));
    @assert(!(app.solid(0.2f, 0.2f) != true || app.solid(app.player_x, app.player_y) != false));

    float old_x = app.player_x;
    app.state = DOOM_PLAYING;
    app.player_angle = 0.0f;
    app.move_player(1.0f, 0.0f, 0.1f);
    @assert(!(app.player_x <= old_x));

    app.state = DOOM_PLAYING;
    app.player_x = 1.5f;
    app.player_y = 1.5f;
    app.player_angle = 0.0f;
    app.enemy_count = 1;
    app.enemies[0] = (doom_enemy_t){3.5f, 1.5f, 1, true, 0.0f};
    app.map[1][2] = '.';
    app.map[1][3] = '.';
    int ammo = app.ammo;
    app.fire();
    @assert(!(app.enemies[0].alive || app.score != 100 || app.ammo != ammo - 1));

    for (int i = 0; i < DOOM_LEVEL_COUNT; i++) {
        @assert(!(!app.load_level(i) || app.map_width < 10 || app.map_height < 10));
    }
}

int main(int argc, char** argv) {
    InitWindow(DOOM_SCREEN_WIDTH, DOOM_SCREEN_HEIGHT, "C-plus | DOOM");
    defer CloseWindow();
    SetTargetFPS(120);
    doom_app_t app;
    app.init();
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            doom_event_t event = {0};
            event.type = DOOM_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        doom_event_t clock = {0};
        clock.type = DOOM_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.move_forward = (IsKeyDown(KEY_W) || IsKeyDown(KEY_UP) ? 1.0f : 0.0f) - (IsKeyDown(KEY_S) || IsKeyDown(KEY_DOWN) ? 1.0f : 0.0f);
        clock.move_strafe = (IsKeyDown(KEY_D) ? 1.0f : 0.0f) - (IsKeyDown(KEY_A) ? 1.0f : 0.0f);
        clock.turn_axis = (IsKeyDown(KEY_RIGHT) ? 1.0f : 0.0f) - (IsKeyDown(KEY_LEFT) ? 1.0f : 0.0f);
        clock.fire_down = IsKeyDown(KEY_SPACE);
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
