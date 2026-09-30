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
#define DOOM_MAX_PICKUPS 64
#define DOOM_LEVEL_COUNT 8
#define DOOM_PI 3.14159265358979323846f
#define DOOM_FOV (DOOM_PI / 3.0f)
#define DOOM_MAX_DEPTH 30.0f


/* Inline symbolic pixmap system. Packed colors are 0xRRGGBBAA. */
typedef struct doom_palette_color_t {
    char index;
    borrowed const char* name;
    uint32_t value;
} doom_palette_color_t;

typedef enum map_sym_type_t {
    t_wall,
    t_floor,
    t_character
} map_sym_type_t;

typedef struct doom_texture_def_t {
    char symbol;
    map_sym_type_t type;
    borrowed const char* name;
    borrowed const char* pixels;
    int width;
    int height;
} doom_texture_def_t;

static const doom_palette_color_t DOOM_COLORS[] = {
    {'r', "red",         0x771100ffu},
    {'1', "red-up",      0x992211ffu},
    {'2', "red-down",    0x550000ffu},
    {'3', "red-high",    0xff3300ffu},
    {'g', "green",       0x117700ffu},
    {'5', "green-up",    0x229911ffu},
    {'6', "green-down",  0x005500ffu},
    {'=', "grey",        0x7f7f7fffu},
    {'0', "floor-red",   0x24100cffu},
    {'d', "floor-dark",  0x171719ffu},
    {'y', "exit",        0xe0b820ffu},
    {'b', "blue",        0x1664d8ffu},
    {'c', "cyan",        0x33d6e8ffu},
    {'w', "white",       0xf0f0f0ffu},
    {'o', "orange",      0xff8a20ffu},
    {'p', "purple",      0x8e44adffu},
    {' ', "transparent", 0x00000000u}
};
#define DOOM_COLOR_COUNT ((int)(sizeof(DOOM_COLORS) / sizeof(DOOM_COLORS[0])))

static const char DOOM_TEX_BRICK[] =
    "111111111111111112\n"
    "rrrrrrrrrrrrrrrr12\n"
    "rrrrrrrrrrrrrrrr12\n"
    "rrrrrrrrrrrrrrrr12\n"
    "222222222222222222\n"
    "111111112211111112\n"
    "rrrrrrr122rrrrrr12\n"
    "rrrrrrr122rrrrrr12\n"
    "rrrrrrr122rrrrrr12";

static const char DOOM_TEX_ENEMY[] =
    "    =====    \n"
    "  ==g=r=g==  \n"
    "  ====r====  \n"
    "  =========  \n"
    "  ==rrrrr==  \n"
    "  ==rrrrr==  \n"
    "    =====    \n"
    "======r======\n"
    "== ===r=== ==\n"
    "== ===r=== ==\n"
    "== ===r=== ==\n"
    "== ===r=== ==\n"
    "== ===r=== ==\n"
    "== ===r=== ==\n"
    "r= =======  r\n"
    "  =========  \n"
    "  ===   ===  \n"
    "  ===   ===  \n"
    "  ===   ===  ";

static const char DOOM_TEX_FLOOR[] =
    "0d0d\n"
    "d0d0\n"
    "0d0d\n"
    "d0d0";

static const char DOOM_TEX_EXIT[] =
    "yyyy\n"
    "y==y\n"
    "y==y\n"
    "yyyy";

static const char DOOM_TEX_HEALTH[] =
    "   rrr   \n"
    "   rrr   \n"
    "rrrrrrrrr\n"
    "rrrrrrrrr\n"
    "rrrrrrrrr\n"
    "   rrr   \n"
    "   rrr   \n"
    "         \n"
    "  =====  ";

static const char DOOM_TEX_STAMINA[] =
    "   ccc   \n"
    "  ccccc  \n"
    " cccwccc \n"
    "cccwwwccc\n"
    " cccwccc \n"
    "  ccccc  \n"
    "   ccc   \n"
    "    c    \n"
    "  =====  ";

static const char DOOM_TEX_SHOTGUN[] =
    "         \n"
    " wwwwwww \n"
    "==wwwwwww\n"
    "   ooo   \n"
    "    oo   \n"
    "    oo   \n"
    "         \n"
    "         \n"
    "  =====  ";

static const char DOOM_TEX_CHAINGUN[] =
    "         \n"
    " wwwwww  \n"
    "======== \n"
    " bbbbbb  \n"
    "   pp    \n"
    "   pp    \n"
    "         \n"
    "         \n"
    "  =====  ";

static const doom_texture_def_t DOOM_TEXTURES[] = {
    {'#', t_wall,      "brick", DOOM_TEX_BRICK, 18, 9},
    {'E', t_character, "enemy", DOOM_TEX_ENEMY, 13, 19},
    {'H', t_character, "health", DOOM_TEX_HEALTH, 9, 9},
    {'S', t_character, "stamina", DOOM_TEX_STAMINA, 9, 9},
    {'G', t_character, "shotgun", DOOM_TEX_SHOTGUN, 9, 9},
    {'C', t_character, "chaingun", DOOM_TEX_CHAINGUN, 9, 9},
    {'.', t_floor,     "floor", DOOM_TEX_FLOOR, 4, 4},
    {'X', t_floor,     "exit",  DOOM_TEX_EXIT,  4, 4}
};
#define DOOM_TEXTURE_COUNT ((int)(sizeof(DOOM_TEXTURES) / sizeof(DOOM_TEXTURES[0])))

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
    bool sprint_down;
} doom_event_t;

typedef struct doom_enemy_t {
    float x;
    float y;
    int health;
    bool alive;
    float attack_cooldown;
    char texture_symbol;
} doom_enemy_t;

typedef enum doom_weapon_t {
    DOOM_WEAPON_PISTOL,
    DOOM_WEAPON_SHOTGUN,
    DOOM_WEAPON_CHAINGUN
} doom_weapon_t;

typedef enum doom_pickup_type_t {
    DOOM_PICKUP_HEALTH,
    DOOM_PICKUP_STAMINA,
    DOOM_PICKUP_SHOTGUN,
    DOOM_PICKUP_CHAINGUN
} doom_pickup_type_t;

typedef struct doom_pickup_t {
    float x;
    float y;
    doom_pickup_type_t type;
    bool active;
    char texture_symbol;
} doom_pickup_t;

typedef struct doom_level_def_t {
    borrowed const char* name;
    borrowed const char* map;
} doom_level_def_t;

static const doom_level_def_t DOOM_LEVELS[DOOM_LEVEL_COUNT] = {
    {
        "1. Entryway",
        "################\n"
        "#P..H..#..S....#\n"
        "#......#..E.G..#\n"
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
        "#P..S....#..H....#\n"
        "#.######.#.#####.#\n"
        "#......#.#.....#.#\n"
        "#.E..G.#.....E.#.#\n"
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
        "#P...H...S.........#\n"
        "#.########.#########\n"
        "#.#......#.#.......#\n"
        "#.#.E..G.#.#...E...#\n"
        "#.#......#.#.......#\n"
        "#.#......#.#.......#\n"
        "#....#####.#####...#\n"
        "#..................#\n"
        "#.######....######.#\n"
        "#......E.....C.....#\n"
        "#.................X#\n"
        "####################\n"
    },
    {
        "4. Courtyard",
        "####################\n"
        "#P...H...S.........#\n"
        "#..................#\n"
        "#...############...#\n"
        "#...#..........#...#\n"
        "#...#..E.G..E..#...#\n"
        "#...#..........#...#\n"
        "#...#....##....#...#\n"
        "#...#....##....#...#\n"
        "#...#..........#...#\n"
        "#...####....####...#\n"
        "#....C...E.........#\n"
        "#.................X#\n"
        "####################\n"
    },
    {
        "5. Zigzag",
        "#######################\n"
        "#P..H..#....S.........#\n"
        "#####..#.############.#\n"
        "#......#.#............#\n"
        "#.######.#.############\n"
        "#.#..G.E.#............#\n"
        "#.#.############.####.#\n"
        "#.#............#....#.#\n"
        "#.############.#.E..#.#\n"
        "#............#.#....#.#\n"
        "############.#.####.#.#\n"
        "#....C.E.....#......#X#\n"
        "#######################\n"
    },
    {
        "6. Reactor",
        "######################\n"
        "#P..S.....H..........#\n"
        "#..######....######..#\n"
        "#..#....#....#....#..#\n"
        "#..#.E.G######..E.#..#\n"
        "#..#..............#..#\n"
        "#..####..######..##..#\n"
        "#......#.#....#.#....#\n"
        "#.E..C.#.#.E..#.#....#\n"
        "#......#.#....#.#....#\n"
        "#..#####.######.####.#\n"
        "#...................X#\n"
        "######################\n"
    },
    {
        "7. Gauntlet",
        "########################\n"
        "#P...H....S............#\n"
        "#.####################.#\n"
        "#.#..E..G............#.#\n"
        "#.#.################.#.#\n"
        "#.#.#..............#.#.#\n"
        "#.#.#.E..........E.#.#.#\n"
        "#.#.#.############.#.#.#\n"
        "#.#.#..............#.#.#\n"
        "#.#.################.#.#\n"
        "#.#....C...E.........#.#\n"
        "#.####################.#\n"
        "#.....................X#\n"
        "########################\n"
    },
    {
        "8. Last Stand",
        "########################\n"
        "#P...H....S............#\n"
        "#..####..######..####..#\n"
        "#..#..#..#....#..#..#..#\n"
        "#..#E.#G.#.EE.#..#.E#..#\n"
        "#..#..#..#....#..#..#..#\n"
        "#..####..######..####..#\n"
        "#......................#\n"
        "#..####..######..####..#\n"
        "#..#E.#..#..C.#..#.E#..#\n"
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
    float stamina;
    int ammo;
    doom_weapon_t weapon;
    uint32_t weapons_owned;
    int score;
    float fire_cooldown;
    float hurt_flash;
    doom_enemy_t enemies[DOOM_MAX_ENEMIES];
    int enemy_count;
    doom_pickup_t pickups[DOOM_MAX_PICKUPS];
    int pickup_count;
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


    static pub borrowed const doom_texture_def_t* texture_for(char symbol, map_sym_type_t type) {
        for (int i = 0; i < DOOM_TEXTURE_COUNT; i++) {
            if (DOOM_TEXTURES[i].symbol == symbol && DOOM_TEXTURES[i].type == type) return &DOOM_TEXTURES[i];
        }
        return NULL;
    }

    static pub uint32_t palette_value(char index) {
        for (int i = 0; i < DOOM_COLOR_COUNT; i++) {
            if (DOOM_COLORS[i].index == index) return DOOM_COLORS[i].value;
        }
        return 0xff00ffffu;
    }

    static pub Color unpack_rgba(uint32_t value) {
        Color color;
        color.r = (unsigned char)((value >> 24) & 0xffu);
        color.g = (unsigned char)((value >> 16) & 0xffu);
        color.b = (unsigned char)((value >> 8) & 0xffu);
        color.a = (unsigned char)(value & 0xffu);
        return color;
    }

    static pub char texture_pixel(borrowed const doom_texture_def_t* texture, int x, int y) {
        if (texture == NULL || x < 0 || y < 0 || x >= texture->width || y >= texture->height) return ' ';
        int stride = texture->width + 1;
        return texture->pixels[y * stride + x];
    }

    static pub Color texture_color(borrowed const doom_texture_def_t* texture, int x, int y) {
        return doom_app_t.unpack_rgba(doom_app_t.palette_value(doom_app_t.texture_pixel(texture, x, y)));
    }

    static pub int wrap_texel(float unit, int size) {
        float f = unit - floorf(unit);
        if (f < 0.0f) f += 1.0f;
        int result = (int)(f * (float)size);
        if (result < 0) result = 0;
        if (result >= size) result = size - 1;
        return result;
    }

    pub bool solid(borrowed const *self, float x, float y) {
        int ix = (int)floorf(x);
        int iy = (int)floorf(y);
        if (ix < 0 || iy < 0 || ix >= self->map_width || iy >= self->map_height) return true;
        char symbol = self->map[iy][ix];
        return doom_app_t.texture_for(symbol, t_wall) != NULL;
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
        memset(self->pickups, 0, sizeof(self->pickups));
        self->pickup_count = 0;
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
                    enemy->texture_symbol = 'E';
                }
                self->map[y][x] = '.';
            } else if (ch == 'H' || ch == 'S' || ch == 'G' || ch == 'C') {
                if (self->pickup_count < DOOM_MAX_PICKUPS) {
                    doom_pickup_t* pickup = &self->pickups[self->pickup_count++];
                    pickup->x = x + 0.5f;
                    pickup->y = y + 0.5f;
                    pickup->active = true;
                    pickup->texture_symbol = ch;
                    if (ch == 'H') pickup->type = DOOM_PICKUP_HEALTH;
                    else if (ch == 'S') pickup->type = DOOM_PICKUP_STAMINA;
                    else if (ch == 'G') pickup->type = DOOM_PICKUP_SHOTGUN;
                    else pickup->type = DOOM_PICKUP_CHAINGUN;
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
        self->stamina = 100.0f;
        self->ammo = 50;
        self->weapon = DOOM_WEAPON_PISTOL;
        self->weapons_owned = 1u << DOOM_WEAPON_PISTOL;
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

    pub void apply_pickup(borrowed mut *self, mut doom_pickup_t* pickup) {
        if (pickup == NULL || !pickup->active) return;
        if (pickup->type == DOOM_PICKUP_HEALTH) {
            if (self->health >= 100) return;
            self->health += 30;
            if (self->health > 100) self->health = 100;
            self->score += 10;
        } else if (pickup->type == DOOM_PICKUP_STAMINA) {
            if (self->stamina >= 100.0f) return;
            self->stamina += 45.0f;
            if (self->stamina > 100.0f) self->stamina = 100.0f;
            self->score += 10;
        } else if (pickup->type == DOOM_PICKUP_SHOTGUN) {
            self->weapons_owned |= 1u << DOOM_WEAPON_SHOTGUN;
            self->weapon = DOOM_WEAPON_SHOTGUN;
            self->ammo += 12;
            self->score += 50;
        } else if (pickup->type == DOOM_PICKUP_CHAINGUN) {
            self->weapons_owned |= 1u << DOOM_WEAPON_CHAINGUN;
            self->weapon = DOOM_WEAPON_CHAINGUN;
            self->ammo += 30;
            self->score += 75;
        }
        pickup->active = false;
    }

    pub void collect_pickups(borrowed mut *self) {
        for (int i = 0; i < self->pickup_count; i++) {
            doom_pickup_t* pickup = &self->pickups[i];
            if (!pickup->active) continue;
            float dx = pickup->x - self->player_x;
            float dy = pickup->y - self->player_y;
            if (dx * dx + dy * dy < 0.28f) doom_app_t.apply_pickup(self, pickup);
        }
    }

    static pub borrowed const char* weapon_name(doom_weapon_t weapon) {
        if (weapon == DOOM_WEAPON_SHOTGUN) return "SHOTGUN";
        if (weapon == DOOM_WEAPON_CHAINGUN) return "CHAINGUN";
        return "PISTOL";
    }

    pub void select_weapon(borrowed mut *self, doom_weapon_t weapon) {
        if ((self->weapons_owned & (1u << weapon)) != 0u) self->weapon = weapon;
    }

    pub void move_player(borrowed mut *self, float forward, float strafe, float dt, bool sprinting) {

        if (self->state != DOOM_PLAYING) return;
        float speed = 3.0f;
        bool moving = fabsf(forward) > 0.01f || fabsf(strafe) > 0.01f;
        if (sprinting && moving && self->stamina > 0.0f) speed = 5.0f;
        float dx = (cosf(self->player_angle) * forward + cosf(self->player_angle + DOOM_PI * 0.5f) * strafe) * speed * dt;
        float dy = (sinf(self->player_angle) * forward + sinf(self->player_angle + DOOM_PI * 0.5f) * strafe) * speed * dt;
        float nx = self->player_x + dx;
        float ny = self->player_y + dy;
        if (!doom_app_t.solid(self, nx, self->player_y)) self->player_x = nx;
        if (!doom_app_t.solid(self, self->player_x, ny)) self->player_y = ny;
        doom_app_t.collect_pickups(self);
        if (doom_app_t.exit_at(self, self->player_x, self->player_y)) self->state = DOOM_WON;
    }

    pub void fire(borrowed mut *self) {
        if (self->state != DOOM_PLAYING || self->fire_cooldown > 0.0f || self->ammo <= 0) return;
        int ammo_cost = 1;
        int damage = 1;
        if (self->weapon == DOOM_WEAPON_SHOTGUN) {
            self->fire_cooldown = 0.48f;
            ammo_cost = 2;
            damage = 2;
        } else if (self->weapon == DOOM_WEAPON_CHAINGUN) {
            self->fire_cooldown = 0.09f;
        } else {
            self->fire_cooldown = 0.22f;
        }
        if (self->ammo < ammo_cost) return;
        self->ammo -= ammo_cost;
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
            enemy->health -= damage;
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
        bool moving = fabsf(event->move_forward) > 0.01f || fabsf(event->move_strafe) > 0.01f;
        doom_app_t.move_player(self, event->move_forward, event->move_strafe, dt, event->sprint_down);
        if (event->sprint_down && moving && self->stamina > 0.0f) {
            self->stamina -= 32.0f * dt;
            if (self->stamina < 0.0f) self->stamina = 0.0f;
        } else {
            self->stamina += 16.0f * dt;
            if (self->stamina > 100.0f) self->stamina = 100.0f;
        }
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
            } else if (self->state == DOOM_PLAYING && event->key == KEY_ONE) {
                doom_app_t.select_weapon(self, DOOM_WEAPON_PISTOL);
            } else if (self->state == DOOM_PLAYING && event->key == KEY_TWO) {
                doom_app_t.select_weapon(self, DOOM_WEAPON_SHOTGUN);
            } else if (self->state == DOOM_PLAYING && event->key == KEY_THREE) {
                doom_app_t.select_weapon(self, DOOM_WEAPON_CHAINGUN);
            } else if (event->key == KEY_M) {
                self->state = DOOM_MENU;
            } else if ((self->state == DOOM_WON || self->state == DOOM_DEAD) && (event->key == KEY_ENTER || event->key == KEY_SPACE)) {
                self->state = DOOM_MENU;
            }
        } else if (event->type == DOOM_EVENT_CLOCK) {
            doom_app_t.clock(self, event);
        }
    }

    pub float cast_ray(borrowed mut *self, float angle, int screen_x, mut float* out_u, mut char* out_symbol) {
        float sin_a = sinf(angle);
        float cos_a = cosf(angle);
        float distance = 0.02f;
        float hit_x = self->player_x;
        float hit_y = self->player_y;
        char symbol = '#';
        while (distance < DOOM_MAX_DEPTH) {
            hit_x = self->player_x + cos_a * distance;
            hit_y = self->player_y + sin_a * distance;
            int ix = (int)floorf(hit_x);
            int iy = (int)floorf(hit_y);
            if (ix < 0 || iy < 0 || ix >= self->map_width || iy >= self->map_height) {
                symbol = '#';
                break;
            }
            symbol = self->map[iy][ix];
            if (doom_app_t.texture_for(symbol, t_wall) != NULL) break;
            distance += 0.025f;
        }
        float fx = hit_x - floorf(hit_x);
        float fy = hit_y - floorf(hit_y);
        float edge_x = fminf(fx, 1.0f - fx);
        float edge_y = fminf(fy, 1.0f - fy);
        float u = edge_x < edge_y ? fy : fx;
        if (out_u != NULL) *out_u = u;
        if (out_symbol != NULL) *out_symbol = symbol;
        float corrected = distance * cosf(angle - self->player_angle);
        if (corrected < 0.03f) corrected = 0.03f;
        if (screen_x >= 0 && screen_x < DOOM_SCREEN_WIDTH) self->zbuffer[screen_x] = corrected;
        return corrected;
    }

    pub void render_floor(borrowed const *self) {
        int horizon = DOOM_VIEW_HEIGHT / 2;
        for (int y = horizon + 1; y < DOOM_VIEW_HEIGHT; y += 2) {
            float corrected_distance = ((float)DOOM_VIEW_HEIGHT * 0.5f) / (float)(y - horizon);
            for (int x = 0; x < DOOM_SCREEN_WIDTH; x += 2) {
                float camera = ((float)x / (float)DOOM_SCREEN_WIDTH) - 0.5f;
                float angle = self->player_angle + camera * DOOM_FOV;
                float ray_distance = corrected_distance / cosf(angle - self->player_angle);
                float wx = self->player_x + cosf(angle) * ray_distance;
                float wy = self->player_y + sinf(angle) * ray_distance;
                int mx = (int)floorf(wx);
                int my = (int)floorf(wy);
                char symbol = '.';
                if (mx >= 0 && my >= 0 && mx < self->map_width && my < self->map_height) symbol = self->map[my][mx];
                borrowed const doom_texture_def_t* texture = doom_app_t.texture_for(symbol, t_floor);
                if (texture == NULL) texture = doom_app_t.texture_for('.', t_floor);
                int tx = doom_app_t.wrap_texel(wx, texture->width);
                int ty = doom_app_t.wrap_texel(wy, texture->height);
                Color color = doom_app_t.texture_color(texture, tx, ty);
                float shade = 1.0f / (1.0f + corrected_distance * 0.08f);
                if (shade < 0.22f) shade = 0.22f;
                color.r = (unsigned char)((float)color.r * shade);
                color.g = (unsigned char)((float)color.g * shade);
                color.b = (unsigned char)((float)color.b * shade);
                DrawRectangle(x, y, 2, 2, color);
            }
        }
    }

    pub void render_character_texture(
        borrowed mut *self,
        borrowed const doom_texture_def_t* texture,
        float world_x,
        float world_y
    ) {
        if (texture == NULL) return;
        float dx = world_x - self->player_x;
        float dy = world_y - self->player_y;
        float distance = sqrtf(dx * dx + dy * dy);
        float relative = doom_app_t.normalize_angle(atan2f(dy, dx) - self->player_angle);
        if (fabsf(relative) >= DOOM_FOV * 0.62f || distance < 0.1f) return;
        float corrected = distance * cosf(relative);
        int center_x = (int)((0.5f + relative / DOOM_FOV) * DOOM_SCREEN_WIDTH);
        int sprite_height = (int)(DOOM_VIEW_HEIGHT / corrected * 0.92f);
        if (sprite_height > 420) sprite_height = 420;
        int sprite_width = sprite_height * texture->width / texture->height;
        if (sprite_width < 1) sprite_width = 1;
        int left = center_x - sprite_width / 2;
        int top = DOOM_VIEW_HEIGHT / 2 - sprite_height / 2;
        int virtual_px_w = sprite_width / texture->width;
        int virtual_px_h = sprite_height / texture->height;
        if (virtual_px_w < 1) virtual_px_w = 1;
        if (virtual_px_h < 1) virtual_px_h = 1;
        for (int ty = 0; ty < texture->height; ty++) {
            for (int tx = 0; tx < texture->width; tx++) {
                Color color = doom_app_t.texture_color(texture, tx, ty);
                if (color.a == 0) continue;
                int sx = left + tx * sprite_width / texture->width;
                int sy = top + ty * sprite_height / texture->height;
                int sw = (tx + 1) * sprite_width / texture->width - tx * sprite_width / texture->width;
                int sh = (ty + 1) * sprite_height / texture->height - ty * sprite_height / texture->height;
                if (sw < 1) sw = virtual_px_w;
                if (sh < 1) sh = virtual_px_h;
                int probe_x = sx + sw / 2;
                if (probe_x < 0 || probe_x >= DOOM_SCREEN_WIDTH) continue;
                if (corrected >= self->zbuffer[probe_x]) continue;
                DrawRectangle(sx, sy, sw, sh, color);
            }
        }
    }

    pub void render_world(borrowed mut *self) {
        DrawRectangle(0, 0, DOOM_SCREEN_WIDTH, DOOM_VIEW_HEIGHT / 2, (Color){38, 42, 52, 255});
        doom_app_t.render_floor(self);
        for (int x = 0; x < DOOM_SCREEN_WIDTH; x += 2) {
            float camera = ((float)x / (float)DOOM_SCREEN_WIDTH) - 0.5f;
            float angle = self->player_angle + camera * DOOM_FOV;
            float hit_u = 0.0f;
            char wall_symbol = '#';
            float distance = doom_app_t.cast_ray(self, angle, x, &hit_u, &wall_symbol);
            if (x + 1 < DOOM_SCREEN_WIDTH) self->zbuffer[x + 1] = distance;
            borrowed const doom_texture_def_t* texture = doom_app_t.texture_for(wall_symbol, t_wall);
            if (texture == NULL) texture = doom_app_t.texture_for('#', t_wall);
            int wall_height = (int)(DOOM_VIEW_HEIGHT / distance);
            if (wall_height > DOOM_VIEW_HEIGHT * 2) wall_height = DOOM_VIEW_HEIGHT * 2;
            int top = DOOM_VIEW_HEIGHT / 2 - wall_height / 2;
            int tx = doom_app_t.wrap_texel(hit_u, texture->width);
            float shade = 1.0f / (1.0f + distance * 0.10f);
            if (shade < 0.25f) shade = 0.25f;
            for (int sy = 0; sy < wall_height; sy += 2) {
                int screen_y = top + sy;
                if (screen_y < 0 || screen_y >= DOOM_VIEW_HEIGHT) continue;
                int ty = (int)((float)sy / (float)wall_height * (float)texture->height);
                if (ty >= texture->height) ty = texture->height - 1;
                Color color = doom_app_t.texture_color(texture, tx, ty);
                color.r = (unsigned char)((float)color.r * shade);
                color.g = (unsigned char)((float)color.g * shade);
                color.b = (unsigned char)((float)color.b * shade);
                DrawRectangle(x, screen_y, 2, 2, color);
            }
        }

        for (int i = 0; i < self->pickup_count; i++) {
            doom_pickup_t* pickup = &self->pickups[i];
            if (!pickup->active) continue;
            borrowed const doom_texture_def_t* texture = doom_app_t.texture_for(pickup->texture_symbol, t_character);
            doom_app_t.render_character_texture(self, texture, pickup->x, pickup->y);
        }

        for (int i = 0; i < self->enemy_count; i++) {
            doom_enemy_t* enemy = &self->enemies[i];
            if (!enemy->alive) continue;
            borrowed const doom_texture_def_t* texture = doom_app_t.texture_for(enemy->texture_symbol, t_character);
            doom_app_t.render_character_texture(self, texture, enemy->x, enemy->y);
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
        for (int i = 0; i < self->pickup_count; i++) {
            if (self->pickups[i].active) DrawCircle(ox + (int)(self->pickups[i].x * scale), oy + (int)(self->pickups[i].y * scale), 1.5f, SKYBLUE);
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
        DrawText(TextFormat("HEALTH %03d", self->health), 20, 575, 24, self->health > 25 ? RAYWHITE : RED);
        DrawText(TextFormat("STAM %03d", (int)self->stamina), 188, 575, 24, self->stamina > 20.0f ? SKYBLUE : ORANGE);
        DrawText(TextFormat("AMMO %02d", self->ammo), 345, 575, 24, RAYWHITE);
        DrawText(doom_app_t.weapon_name(self->weapon), 485, 575, 22, LIGHTGRAY);
        DrawText(TextFormat("SCORE %05d", self->score), 625, 575, 22, RAYWHITE);
        DrawText(DOOM_LEVELS[self->current_level].name, 820, 578, 16, LIGHTGRAY);
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
    app.move_player(1.0f, 0.0f, 0.1f, false);
    @assert(!(app.player_x <= old_x));

    app.state = DOOM_PLAYING;
    app.player_x = 1.5f;
    app.player_y = 1.5f;
    app.player_angle = 0.0f;
    app.enemy_count = 1;
    app.enemies[0] = (doom_enemy_t){3.5f, 1.5f, 1, true, 0.0f, 'E'};
    app.map[1][2] = '.';
    app.map[1][3] = '.';
    int ammo = app.ammo;
    app.fire();
    @assert(!(app.enemies[0].alive || app.score != 100 || app.ammo != ammo - 1));

    borrowed const doom_texture_def_t* brick = doom_app_t.texture_for('#', t_wall);
    borrowed const doom_texture_def_t* enemy_texture = doom_app_t.texture_for('E', t_character);
    borrowed const doom_texture_def_t* floor_texture = doom_app_t.texture_for('.', t_floor);
    @assert(!(brick == NULL || brick->width != 18 || brick->height != 9));
    @assert(!(enemy_texture == NULL || enemy_texture->width != 13 || enemy_texture->height != 19));
    @assert(!(floor_texture == NULL || floor_texture->width != 4 || floor_texture->height != 4));
    Color transparent = doom_app_t.unpack_rgba(doom_app_t.palette_value(' '));
    @assert(!(transparent.a != 0));
    @assert(!(doom_app_t.texture_for('H', t_character) == NULL || doom_app_t.texture_for('S', t_character) == NULL));
    @assert(!(doom_app_t.texture_for('G', t_character) == NULL || doom_app_t.texture_for('C', t_character) == NULL));

    doom_pickup_t hp = {app.player_x, app.player_y, DOOM_PICKUP_HEALTH, true, 'H'};
    app.health = 60;
    app.apply_pickup(&hp);
    @assert(!(app.health != 90 || hp.active));
    doom_pickup_t stamina = {app.player_x, app.player_y, DOOM_PICKUP_STAMINA, true, 'S'};
    app.stamina = 20.0f;
    app.apply_pickup(&stamina);
    @assert(!(app.stamina < 64.9f || app.stamina > 65.1f || stamina.active));
    doom_pickup_t shotgun = {app.player_x, app.player_y, DOOM_PICKUP_SHOTGUN, true, 'G'};
    int before_ammo = app.ammo;
    app.apply_pickup(&shotgun);
    @assert(!(app.weapon != DOOM_WEAPON_SHOTGUN || (app.weapons_owned & (1u << DOOM_WEAPON_SHOTGUN)) == 0u || app.ammo != before_ammo + 12 || shotgun.active));
    doom_pickup_t chaingun = {app.player_x, app.player_y, DOOM_PICKUP_CHAINGUN, true, 'C'};
    before_ammo = app.ammo;
    app.apply_pickup(&chaingun);
    @assert(!(app.weapon != DOOM_WEAPON_CHAINGUN || (app.weapons_owned & (1u << DOOM_WEAPON_CHAINGUN)) == 0u || app.ammo != before_ammo + 30 || chaingun.active));
    app.select_weapon(DOOM_WEAPON_PISTOL);
    @assert(!(app.weapon != DOOM_WEAPON_PISTOL));
    app.select_weapon(DOOM_WEAPON_SHOTGUN);
    @assert(!(app.weapon != DOOM_WEAPON_SHOTGUN));

    app.stamina = 50.0f;
    doom_event_t sprint = {0};
    sprint.type = DOOM_EVENT_CLOCK;
    sprint.delta_seconds = 0.05f;
    sprint.move_forward = 1.0f;
    sprint.sprint_down = true;
    app.state = DOOM_PLAYING;
    app.clock(&sprint);
    @assert(!(app.stamina >= 50.0f));

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
        clock.sprint_down = IsKeyDown(KEY_LEFT_SHIFT) || IsKeyDown(KEY_RIGHT_SHIFT);
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
