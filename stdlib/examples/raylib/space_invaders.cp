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

#define INVADERS_SCREEN_WIDTH 960
#define INVADERS_SCREEN_HEIGHT 720
#define INVADERS_ROWS 5
#define INVADERS_COLUMNS 11
#define INVADERS_MAX_ALIENS (INVADERS_ROWS * INVADERS_COLUMNS)
#define INVADERS_MAX_PROJECTILES 96
#define INVADERS_SHIELD_COUNT 4
#define INVADERS_SHIELD_ROWS 8
#define INVADERS_SHIELD_COLUMNS 16

typedef enum invaders_event_type_t {
    INVADERS_EVENT_KEYBOARD,
    INVADERS_EVENT_MOUSE,
    INVADERS_EVENT_CLOCK
} invaders_event_type_t;

typedef enum invaders_state_t {
    INVADERS_READY,
    INVADERS_PLAYING,
    INVADERS_WAVE_COMPLETE,
    INVADERS_GAME_OVER
} invaders_state_t;

typedef enum invaders_alien_type_t {
    INVADERS_ALIEN_SMALL,
    INVADERS_ALIEN_MEDIUM,
    INVADERS_ALIEN_LARGE
} invaders_alien_type_t;

typedef enum invaders_projectile_owner_t {
    INVADERS_PROJECTILE_PLAYER,
    INVADERS_PROJECTILE_ALIEN
} invaders_projectile_owner_t;

typedef struct invaders_player_t {
    Vector2 position;
    Vector2 size;
    float speed;
    int lives;
} invaders_player_t;

typedef struct invaders_alien_t {
    Rectangle bounds;
    invaders_alien_type_t type;
    bool alive;
} invaders_alien_t;

typedef struct invaders_projectile_t {
    Rectangle bounds;
    Vector2 velocity;
    invaders_projectile_owner_t owner;
    bool active;
} invaders_projectile_t;

typedef struct invaders_shield_t {
    Vector2 position;
    unsigned char health[INVADERS_SHIELD_ROWS][INVADERS_SHIELD_COLUMNS];
} invaders_shield_t;

typedef struct invaders_event_t {
    invaders_event_type_t type;
    int key;
    Vector2 position;
    float delta_seconds;
    bool left_down;
    bool right_down;
} invaders_event_t;

typedef struct invaders_app_t {
    invaders_state_t state;
    invaders_player_t player;
    invaders_alien_t aliens[INVADERS_MAX_ALIENS];
    invaders_projectile_t projectiles[INVADERS_MAX_PROJECTILES];
    invaders_shield_t shields[INVADERS_SHIELD_COUNT];
    int alien_count;
    int wave;
    int score;
    int formation_direction;
    float formation_speed;
    float alien_fire_timer;
    float player_fire_cooldown;
    uint32_t random_state;

pub uint32_t random(borrowed mut *self) {
    uint32_t value = self->random_state;
    if (value == 0) value = 0x85ebca6bu;
    value ^= value << 13;
    value ^= value >> 17;
    value ^= value << 5;
    self->random_state = value;
    return value;
}

pub void build_wave(borrowed mut *self) {
    self->alien_count = INVADERS_MAX_ALIENS;
    self->formation_direction = 1;
    self->formation_speed = 18.0f + self->wave * 5.0f;
    self->alien_fire_timer = 0.8f;
    for (int row = 0; row < INVADERS_ROWS; row++) {
        for (int column = 0; column < INVADERS_COLUMNS; column++) {
            int index = row * INVADERS_COLUMNS + column;
            invaders_alien_t* alien = &self->aliens[index];
            alien->type = row == 0 ? INVADERS_ALIEN_LARGE :
                          row < 3 ? INVADERS_ALIEN_MEDIUM : INVADERS_ALIEN_SMALL;
            alien->bounds = (Rectangle){145.0f + column * 59.0f, 82.0f + row * 47.0f, 34.0f, 25.0f};
            alien->alive = true;
        }
    }
}

pub void init_shields(borrowed mut *self) {
    for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
        invaders_shield_t* shield = &self->shields[shield_index];
        shield->position = (Vector2){125.0f + shield_index * 225.0f, 535.0f};
        for (int row = 0; row < INVADERS_SHIELD_ROWS; row++) {
            for (int column = 0; column < INVADERS_SHIELD_COLUMNS; column++) {
                bool cutout = row >= 5 && column >= 5 && column <= 10;
                shield->health[row][column] = cutout ? 0 : 3;
            }
        }
    }
}

pub void init(borrowed mut *self, uint32_t seed) {
    memset(self, 0, sizeof(*self));
    self->state = INVADERS_READY;
    self->wave = 1;
    self->random_state = seed;
    self->player.position = (Vector2){INVADERS_SCREEN_WIDTH * 0.5f, 650.0f};
    self->player.size = (Vector2){46.0f, 24.0f};
    self->player.speed = 390.0f;
    self->player.lives = 3;
    invaders_app_t.build_wave(self);
    invaders_app_t.init_shields(self);
}

pub int find_projectile(borrowed mut *self) {
    for (int i = 0; i < INVADERS_MAX_PROJECTILES; i++) {
        if (!self->projectiles[i].active) return i;
    }
    return -1;
}

pub void fire_player(borrowed mut *self) {
    if (self->player_fire_cooldown > 0.0f) return;
    int slot = invaders_app_t.find_projectile(self);
    if (slot < 0) return;
    invaders_projectile_t* shot = &self->projectiles[slot];
    shot->active = true;
    shot->owner = INVADERS_PROJECTILE_PLAYER;
    shot->bounds = (Rectangle){self->player.position.x - 2.5f,
                               self->player.position.y - self->player.size.y * 0.5f - 17.0f, 5.0f, 17.0f};
    shot->velocity = (Vector2){0.0f, -510.0f};
    self->player_fire_cooldown = 0.28f;
}

pub int random_live_alien(borrowed mut *self) {
    int live_count = 0;
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) live_count += self->aliens[i].alive ? 1 : 0;
    if (live_count == 0) return -1;
    int wanted = (int)(invaders_app_t.random(self) % (uint32_t)live_count);
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        if (!self->aliens[i].alive) continue;
        if (wanted-- == 0) return i;
    }
    return -1;
}

pub void fire_alien(borrowed mut *self) {
    int alien_index = invaders_app_t.random_live_alien(self);
    int slot = invaders_app_t.find_projectile(self);
    if (alien_index < 0 || slot < 0) return;
    const Rectangle alien = self->aliens[alien_index].bounds;
    invaders_projectile_t* shot = &self->projectiles[slot];
    shot->active = true;
    shot->owner = INVADERS_PROJECTILE_ALIEN;
    shot->bounds = (Rectangle){alien.x + alien.width * 0.5f - 3.0f, alien.y + alien.height, 6.0f, 14.0f};
    shot->velocity = (Vector2){0.0f, 270.0f + self->wave * 12.0f};
}

pub bool damage_shield(borrowed mut *self, Rectangle projectile) {
    for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
        invaders_shield_t* shield = &self->shields[shield_index];
        for (int row = 0; row < INVADERS_SHIELD_ROWS; row++) {
            for (int column = 0; column < INVADERS_SHIELD_COLUMNS; column++) {
                if (shield->health[row][column] == 0) continue;
                Rectangle cell = {shield->position.x + column * 4.0f, shield->position.y + row * 4.0f, 4.2f, 4.2f};
                if (!CheckCollisionRecs(projectile, cell)) continue;
                shield->health[row][column]--;
                return true;
            }
        }
    }
    return false;
}

pub void erode_shields(borrowed mut *self) {
    for (int alien_index = 0; alien_index < INVADERS_MAX_ALIENS; alien_index++) {
        invaders_alien_t* alien = &self->aliens[alien_index];
        if (!alien->alive) continue;
        for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
            invaders_shield_t* shield = &self->shields[shield_index];
            for (int row = 0; row < INVADERS_SHIELD_ROWS; row++) {
                for (int column = 0; column < INVADERS_SHIELD_COLUMNS; column++) {
                    if (shield->health[row][column] == 0) continue;
                    Rectangle cell = {shield->position.x + column * 4.0f, shield->position.y + row * 4.0f, 4.2f, 4.2f};
                    if (CheckCollisionRecs(alien->bounds, cell)) shield->health[row][column] = 0;
                }
            }
        }
    }
}

pub void collision_pass(borrowed mut *self) {
    Rectangle player_bounds = {self->player.position.x - self->player.size.x * 0.5f,
                               self->player.position.y - self->player.size.y * 0.5f,
                               self->player.size.x, self->player.size.y};
    for (int shot_index = 0; shot_index < INVADERS_MAX_PROJECTILES; shot_index++) {
        invaders_projectile_t* shot = &self->projectiles[shot_index];
        if (!shot->active) continue;
        if (invaders_app_t.damage_shield(self, shot->bounds)) {
            shot->active = false;
            continue;
        }
        if (shot->owner == INVADERS_PROJECTILE_PLAYER) {
            for (int alien_index = 0; alien_index < INVADERS_MAX_ALIENS; alien_index++) {
                invaders_alien_t* alien = &self->aliens[alien_index];
                if (!alien->alive || !CheckCollisionRecs(shot->bounds, alien->bounds)) continue;
                alien->alive = false;
                shot->active = false;
                self->alien_count--;
                self->score += alien->type == INVADERS_ALIEN_LARGE ? 30 :
                              alien->type == INVADERS_ALIEN_MEDIUM ? 20 : 10;
                break;
            }
        } else if (CheckCollisionRecs(shot->bounds, player_bounds)) {
            shot->active = false;
            self->player.lives--;
            if (self->player.lives <= 0) self->state = INVADERS_GAME_OVER;
            self->player.position.x = INVADERS_SCREEN_WIDTH * 0.5f;
        }
    }
    invaders_app_t.erode_shields(self);
}

pub void update_formation(borrowed mut *self, float dt) {
    float edge_left = 10000.0f;
    float edge_right = -10000.0f;
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        if (!self->aliens[i].alive) continue;
        if (self->aliens[i].bounds.x < edge_left) edge_left = self->aliens[i].bounds.x;
        if (self->aliens[i].bounds.x + self->aliens[i].bounds.width > edge_right)
            edge_right = self->aliens[i].bounds.x + self->aliens[i].bounds.width;
    }
    bool hit_edge = (self->formation_direction > 0 && edge_right + self->formation_speed * dt > INVADERS_SCREEN_WIDTH - 28.0f) ||
                    (self->formation_direction < 0 && edge_left - self->formation_speed * dt < 28.0f);
    if (hit_edge) {
        self->formation_direction = -self->formation_direction;
        for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
            if (self->aliens[i].alive) self->aliens[i].bounds.y += 18.0f;
        }
    }
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        invaders_alien_t* alien = &self->aliens[i];
        if (!alien->alive) continue;
        alien->bounds.x += self->formation_direction * self->formation_speed * dt;
        if (alien->bounds.y + alien->bounds.height >= self->player.position.y - 22.0f)
            self->state = INVADERS_GAME_OVER;
    }
}

pub void clock(borrowed mut *self, const invaders_event_t* event) {
    float dt = event->delta_seconds;
    if (dt < 0.0f) dt = 0.0f;
    if (dt > 0.05f) dt = 0.05f;
    if (self->player_fire_cooldown > 0.0f) self->player_fire_cooldown -= dt;
    if (self->state != INVADERS_PLAYING) return;

    if (event->left_down) self->player.position.x -= self->player.speed * dt;
    if (event->right_down) self->player.position.x += self->player.speed * dt;
    self->player.position.x = Clamp(self->player.position.x, 30.0f, INVADERS_SCREEN_WIDTH - 30.0f);

    invaders_app_t.update_formation(self, dt);
    self->alien_fire_timer -= dt;
    if (self->alien_fire_timer <= 0.0f) {
        invaders_app_t.fire_alien(self);
        self->alien_fire_timer = 1.0f - self->wave * 0.035f;
        if (self->alien_fire_timer < 0.28f) self->alien_fire_timer = 0.28f;
    }
    for (int i = 0; i < INVADERS_MAX_PROJECTILES; i++) {
        invaders_projectile_t* shot = &self->projectiles[i];
        if (!shot->active) continue;
        shot->bounds.x += shot->velocity.x * dt;
        shot->bounds.y += shot->velocity.y * dt;
        if (shot->bounds.y + shot->bounds.height < 0.0f || shot->bounds.y > INVADERS_SCREEN_HEIGHT)
            shot->active = false;
    }
    invaders_app_t.collision_pass(self);
    if (self->state == INVADERS_PLAYING && self->alien_count <= 0) {
        self->wave++;
        self->state = INVADERS_WAVE_COMPLETE;
        invaders_app_t.build_wave(self);
        invaders_app_t.init_shields(self);
        self->state = INVADERS_PLAYING;
    }
}

pub void event(borrowed mut *self, const invaders_event_t* event) {
    if (event->type == INVADERS_EVENT_KEYBOARD) {
        if (event->key == KEY_SPACE && self->state == INVADERS_READY) self->state = INVADERS_PLAYING;
        if (event->key == KEY_SPACE && self->state == INVADERS_PLAYING) invaders_app_t.fire_player(self);
        if (event->key == KEY_ENTER && self->state == INVADERS_GAME_OVER) invaders_app_t.init(self, self->random_state + 1u);
    } else if (event->type == INVADERS_EVENT_MOUSE) {
        /* Raylib mouse position is available for future aiming/control variants. */
        (void)event->position;
    } else {
        invaders_app_t.clock(self, event);
    }
}

pub void render(borrowed const *self) {
    ClearBackground((Color){5, 10, 23, 255});
    DrawText(TextFormat("SCORE %06d    WAVE %d    LIVES %d", self->score, self->wave, self->player.lives),
             28, 24, 23, RAYWHITE);
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        const invaders_alien_t* alien = &self->aliens[i];
        if (!alien->alive) continue;
        Color color = alien->type == INVADERS_ALIEN_LARGE ? RED :
                      alien->type == INVADERS_ALIEN_MEDIUM ? ORANGE : LIME;
        DrawRectangleRec(alien->bounds, color);
        DrawRectangle((int)alien->bounds.x + 6, (int)alien->bounds.y + 7, 5, 5, BLACK);
        DrawRectangle((int)(alien->bounds.x + alien->bounds.width) - 11, (int)alien->bounds.y + 7, 5, 5, BLACK);
    }
    Vector2 left = {self->player.position.x, self->player.position.y - 18.0f};
    Vector2 right = {self->player.position.x - self->player.size.x * 0.5f, self->player.position.y + 12.0f};
    Vector2 tip = {self->player.position.x + self->player.size.x * 0.5f, self->player.position.y + 12.0f};
    DrawTriangle(left, right, tip, SKYBLUE);
    for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
        const invaders_shield_t* shield = &self->shields[shield_index];
        for (int row = 0; row < INVADERS_SHIELD_ROWS; row++) {
            for (int column = 0; column < INVADERS_SHIELD_COLUMNS; column++) {
                unsigned char health = shield->health[row][column];
                if (health == 0) continue;
                Color color = health == 3 ? GREEN : health == 2 ? LIME : GOLD;
                DrawRectangle((int)(shield->position.x + column * 4.0f),
                              (int)(shield->position.y + row * 4.0f), 4, 4, color);
            }
        }
    }
    for (int i = 0; i < INVADERS_MAX_PROJECTILES; i++) {
        if (!self->projectiles[i].active) continue;
        DrawRectangleRec(self->projectiles[i].bounds,
                         self->projectiles[i].owner == INVADERS_PROJECTILE_PLAYER ? YELLOW : RED);
    }
    if (self->state == INVADERS_READY) DrawText("PRESS SPACE TO START", 345, 350, 28, RAYWHITE);
    if (self->state == INVADERS_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 275, 350, 28, RED);
}

pub int count_live_projectiles(borrowed const *self) {
    int count = 0;
    for (int i = 0; i < INVADERS_MAX_PROJECTILES; i++) count += self->projectiles[i].active ? 1 : 0;
    return count;
}

} invaders_app_t;

@test "space invaders model and render" {
    invaders_app_t app;
    app.init(24680u);
    @assert(!(app.alien_count != INVADERS_MAX_ALIENS || app.wave != 1));
    app.state = INVADERS_PLAYING;
    float before = app.player.position.x;
    invaders_event_t clock = {0};
    clock.type = INVADERS_EVENT_CLOCK;
    clock.delta_seconds = 0.02f;
    clock.left_down = true;
    app.clock(&clock);
    @assert(!(app.player.position.x >= before));

    invaders_event_t key = {0};
    key.type = INVADERS_EVENT_KEYBOARD;
    key.key = KEY_SPACE;
    app.event(&key);
    @assert(!(app.count_live_projectiles() != 1));

    invaders_projectile_t* shot = &app.projectiles[0];
    invaders_alien_t* alien = &app.aliens[0];
    shot->active = true;
    shot->owner = INVADERS_PROJECTILE_PLAYER;
    shot->bounds = alien->bounds;
    int score_before = app.score;
    app.collision_pass();
    @assert(!(alien->alive || app.alien_count != INVADERS_MAX_ALIENS - 1 || app.score <= score_before));


    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(INVADERS_SCREEN_WIDTH, INVADERS_SCREEN_HEIGHT, "C-plus test render");
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
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/space-invaders.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}



int main(int argc, char** argv) {
        InitWindow(INVADERS_SCREEN_WIDTH, INVADERS_SCREEN_HEIGHT, "C-plus | Space Invaders");
    defer CloseWindow();
    SetTargetFPS(120);
    invaders_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 3u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            invaders_event_t event = {0};
            event.type = INVADERS_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        Vector2 mouse = GetMousePosition();
        invaders_event_t mouse_event = {0};
        mouse_event.type = INVADERS_EVENT_MOUSE;
        mouse_event.position = mouse;
        app.event(&mouse_event);

        invaders_event_t clock = {0};
        clock.type = INVADERS_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.left_down = IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A);
        clock.right_down = IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D);
        app.event(&clock);

        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
