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
} invaders_app_t;

static uint32_t invaders_random(invaders_app_t* app) {
    uint32_t value = app->random_state;
    if (value == 0) value = 0x85ebca6bu;
    value ^= value << 13;
    value ^= value >> 17;
    value ^= value << 5;
    app->random_state = value;
    return value;
}

static void invaders_build_wave(invaders_app_t* app) {
    app->alien_count = INVADERS_MAX_ALIENS;
    app->formation_direction = 1;
    app->formation_speed = 18.0f + app->wave * 5.0f;
    app->alien_fire_timer = 0.8f;
    for (int row = 0; row < INVADERS_ROWS; row++) {
        for (int column = 0; column < INVADERS_COLUMNS; column++) {
            int index = row * INVADERS_COLUMNS + column;
            invaders_alien_t* alien = &app->aliens[index];
            alien->type = row == 0 ? INVADERS_ALIEN_LARGE :
                          row < 3 ? INVADERS_ALIEN_MEDIUM : INVADERS_ALIEN_SMALL;
            alien->bounds = (Rectangle){145.0f + column * 59.0f, 82.0f + row * 47.0f, 34.0f, 25.0f};
            alien->alive = true;
        }
    }
}

static void invaders_init_shields(invaders_app_t* app) {
    for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
        invaders_shield_t* shield = &app->shields[shield_index];
        shield->position = (Vector2){125.0f + shield_index * 225.0f, 535.0f};
        for (int row = 0; row < INVADERS_SHIELD_ROWS; row++) {
            for (int column = 0; column < INVADERS_SHIELD_COLUMNS; column++) {
                bool cutout = row >= 5 && column >= 5 && column <= 10;
                shield->health[row][column] = cutout ? 0 : 3;
            }
        }
    }
}

static void invaders_init(invaders_app_t* app, uint32_t seed) {
    memset(app, 0, sizeof(*app));
    app->state = INVADERS_READY;
    app->wave = 1;
    app->random_state = seed;
    app->player.position = (Vector2){INVADERS_SCREEN_WIDTH * 0.5f, 650.0f};
    app->player.size = (Vector2){46.0f, 24.0f};
    app->player.speed = 390.0f;
    app->player.lives = 3;
    invaders_build_wave(app);
    invaders_init_shields(app);
}

static int invaders_find_projectile(invaders_app_t* app) {
    for (int i = 0; i < INVADERS_MAX_PROJECTILES; i++) {
        if (!app->projectiles[i].active) return i;
    }
    return -1;
}

static void invaders_fire_player(invaders_app_t* app) {
    if (app->player_fire_cooldown > 0.0f) return;
    int slot = invaders_find_projectile(app);
    if (slot < 0) return;
    invaders_projectile_t* shot = &app->projectiles[slot];
    shot->active = true;
    shot->owner = INVADERS_PROJECTILE_PLAYER;
    shot->bounds = (Rectangle){app->player.position.x - 2.5f,
                               app->player.position.y - app->player.size.y * 0.5f - 17.0f, 5.0f, 17.0f};
    shot->velocity = (Vector2){0.0f, -510.0f};
    app->player_fire_cooldown = 0.28f;
}

static int invaders_random_live_alien(invaders_app_t* app) {
    int live_count = 0;
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) live_count += app->aliens[i].alive ? 1 : 0;
    if (live_count == 0) return -1;
    int wanted = (int)(invaders_random(app) % (uint32_t)live_count);
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        if (!app->aliens[i].alive) continue;
        if (wanted-- == 0) return i;
    }
    return -1;
}

static void invaders_fire_alien(invaders_app_t* app) {
    int alien_index = invaders_random_live_alien(app);
    int slot = invaders_find_projectile(app);
    if (alien_index < 0 || slot < 0) return;
    const Rectangle alien = app->aliens[alien_index].bounds;
    invaders_projectile_t* shot = &app->projectiles[slot];
    shot->active = true;
    shot->owner = INVADERS_PROJECTILE_ALIEN;
    shot->bounds = (Rectangle){alien.x + alien.width * 0.5f - 3.0f, alien.y + alien.height, 6.0f, 14.0f};
    shot->velocity = (Vector2){0.0f, 270.0f + app->wave * 12.0f};
}

static bool invaders_damage_shield(invaders_app_t* app, Rectangle projectile) {
    for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
        invaders_shield_t* shield = &app->shields[shield_index];
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

static void invaders_erode_shields(invaders_app_t* app) {
    for (int alien_index = 0; alien_index < INVADERS_MAX_ALIENS; alien_index++) {
        invaders_alien_t* alien = &app->aliens[alien_index];
        if (!alien->alive) continue;
        for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
            invaders_shield_t* shield = &app->shields[shield_index];
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

static void invaders_collision_pass(invaders_app_t* app) {
    Rectangle player_bounds = {app->player.position.x - app->player.size.x * 0.5f,
                               app->player.position.y - app->player.size.y * 0.5f,
                               app->player.size.x, app->player.size.y};
    for (int shot_index = 0; shot_index < INVADERS_MAX_PROJECTILES; shot_index++) {
        invaders_projectile_t* shot = &app->projectiles[shot_index];
        if (!shot->active) continue;
        if (invaders_damage_shield(app, shot->bounds)) {
            shot->active = false;
            continue;
        }
        if (shot->owner == INVADERS_PROJECTILE_PLAYER) {
            for (int alien_index = 0; alien_index < INVADERS_MAX_ALIENS; alien_index++) {
                invaders_alien_t* alien = &app->aliens[alien_index];
                if (!alien->alive || !CheckCollisionRecs(shot->bounds, alien->bounds)) continue;
                alien->alive = false;
                shot->active = false;
                app->alien_count--;
                app->score += alien->type == INVADERS_ALIEN_LARGE ? 30 :
                              alien->type == INVADERS_ALIEN_MEDIUM ? 20 : 10;
                break;
            }
        } else if (CheckCollisionRecs(shot->bounds, player_bounds)) {
            shot->active = false;
            app->player.lives--;
            if (app->player.lives <= 0) app->state = INVADERS_GAME_OVER;
            app->player.position.x = INVADERS_SCREEN_WIDTH * 0.5f;
        }
    }
    invaders_erode_shields(app);
}

static void invaders_update_formation(invaders_app_t* app, float dt) {
    float edge_left = 10000.0f;
    float edge_right = -10000.0f;
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        if (!app->aliens[i].alive) continue;
        if (app->aliens[i].bounds.x < edge_left) edge_left = app->aliens[i].bounds.x;
        if (app->aliens[i].bounds.x + app->aliens[i].bounds.width > edge_right)
            edge_right = app->aliens[i].bounds.x + app->aliens[i].bounds.width;
    }
    bool hit_edge = (app->formation_direction > 0 && edge_right + app->formation_speed * dt > INVADERS_SCREEN_WIDTH - 28.0f) ||
                    (app->formation_direction < 0 && edge_left - app->formation_speed * dt < 28.0f);
    if (hit_edge) {
        app->formation_direction = -app->formation_direction;
        for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
            if (app->aliens[i].alive) app->aliens[i].bounds.y += 18.0f;
        }
    }
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        invaders_alien_t* alien = &app->aliens[i];
        if (!alien->alive) continue;
        alien->bounds.x += app->formation_direction * app->formation_speed * dt;
        if (alien->bounds.y + alien->bounds.height >= app->player.position.y - 22.0f)
            app->state = INVADERS_GAME_OVER;
    }
}

static void invaders_clock(invaders_app_t* app, const invaders_event_t* event) {
    float dt = event->delta_seconds;
    if (dt < 0.0f) dt = 0.0f;
    if (dt > 0.05f) dt = 0.05f;
    if (app->player_fire_cooldown > 0.0f) app->player_fire_cooldown -= dt;
    if (app->state != INVADERS_PLAYING) return;

    if (event->left_down) app->player.position.x -= app->player.speed * dt;
    if (event->right_down) app->player.position.x += app->player.speed * dt;
    app->player.position.x = Clamp(app->player.position.x, 30.0f, INVADERS_SCREEN_WIDTH - 30.0f);

    invaders_update_formation(app, dt);
    app->alien_fire_timer -= dt;
    if (app->alien_fire_timer <= 0.0f) {
        invaders_fire_alien(app);
        app->alien_fire_timer = 1.0f - app->wave * 0.035f;
        if (app->alien_fire_timer < 0.28f) app->alien_fire_timer = 0.28f;
    }
    for (int i = 0; i < INVADERS_MAX_PROJECTILES; i++) {
        invaders_projectile_t* shot = &app->projectiles[i];
        if (!shot->active) continue;
        shot->bounds.x += shot->velocity.x * dt;
        shot->bounds.y += shot->velocity.y * dt;
        if (shot->bounds.y + shot->bounds.height < 0.0f || shot->bounds.y > INVADERS_SCREEN_HEIGHT)
            shot->active = false;
    }
    invaders_collision_pass(app);
    if (app->state == INVADERS_PLAYING && app->alien_count <= 0) {
        app->wave++;
        app->state = INVADERS_WAVE_COMPLETE;
        invaders_build_wave(app);
        invaders_init_shields(app);
        app->state = INVADERS_PLAYING;
    }
}

static void invaders_event(invaders_app_t* app, const invaders_event_t* event) {
    if (event->type == INVADERS_EVENT_KEYBOARD) {
        if (event->key == KEY_SPACE && app->state == INVADERS_READY) app->state = INVADERS_PLAYING;
        if (event->key == KEY_SPACE && app->state == INVADERS_PLAYING) invaders_fire_player(app);
        if (event->key == KEY_ENTER && app->state == INVADERS_GAME_OVER) invaders_init(app, app->random_state + 1u);
    } else if (event->type == INVADERS_EVENT_MOUSE) {
        /* Raylib mouse position is available for future aiming/control variants. */
        (void)event->position;
    } else {
        invaders_clock(app, event);
    }
}

static void invaders_render(const invaders_app_t* app) {
    ClearBackground((Color){5, 10, 23, 255});
    DrawText(TextFormat("SCORE %06d    WAVE %d    LIVES %d", app->score, app->wave, app->player.lives),
             28, 24, 23, RAYWHITE);
    for (int i = 0; i < INVADERS_MAX_ALIENS; i++) {
        const invaders_alien_t* alien = &app->aliens[i];
        if (!alien->alive) continue;
        Color color = alien->type == INVADERS_ALIEN_LARGE ? RED :
                      alien->type == INVADERS_ALIEN_MEDIUM ? ORANGE : LIME;
        DrawRectangleRec(alien->bounds, color);
        DrawRectangle((int)alien->bounds.x + 6, (int)alien->bounds.y + 7, 5, 5, BLACK);
        DrawRectangle((int)(alien->bounds.x + alien->bounds.width) - 11, (int)alien->bounds.y + 7, 5, 5, BLACK);
    }
    Vector2 left = {app->player.position.x, app->player.position.y - 18.0f};
    Vector2 right = {app->player.position.x - app->player.size.x * 0.5f, app->player.position.y + 12.0f};
    Vector2 tip = {app->player.position.x + app->player.size.x * 0.5f, app->player.position.y + 12.0f};
    DrawTriangle(left, right, tip, SKYBLUE);
    for (int shield_index = 0; shield_index < INVADERS_SHIELD_COUNT; shield_index++) {
        const invaders_shield_t* shield = &app->shields[shield_index];
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
        if (!app->projectiles[i].active) continue;
        DrawRectangleRec(app->projectiles[i].bounds,
                         app->projectiles[i].owner == INVADERS_PROJECTILE_PLAYER ? YELLOW : RED);
    }
    if (app->state == INVADERS_READY) DrawText("PRESS SPACE TO START", 345, 350, 28, RAYWHITE);
    if (app->state == INVADERS_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 275, 350, 28, RED);
}

static int invaders_count_live_projectiles(const invaders_app_t* app) {
    int count = 0;
    for (int i = 0; i < INVADERS_MAX_PROJECTILES; i++) count += app->projectiles[i].active ? 1 : 0;
    return count;
}

static int invaders_self_test(void) {
    invaders_app_t app;
    invaders_init(&app, 24680u);
    if (app.alien_count != INVADERS_MAX_ALIENS || app.wave != 1) return 1;
    app.state = INVADERS_PLAYING;
    float before = app.player.position.x;
    invaders_event_t clock = {0};
    clock.type = INVADERS_EVENT_CLOCK;
    clock.delta_seconds = 0.02f;
    clock.left_down = true;
    invaders_clock(&app, &clock);
    if (app.player.position.x >= before) return 2;

    invaders_event_t key = {0};
    key.type = INVADERS_EVENT_KEYBOARD;
    key.key = KEY_SPACE;
    invaders_event(&app, &key);
    if (invaders_count_live_projectiles(&app) != 1) return 3;

    invaders_projectile_t* shot = &app.projectiles[0];
    invaders_alien_t* alien = &app.aliens[0];
    shot->active = true;
    shot->owner = INVADERS_PROJECTILE_PLAYER;
    shot->bounds = alien->bounds;
    int score_before = app.score;
    invaders_collision_pass(&app);
    if (alien->alive || app.alien_count != INVADERS_MAX_ALIENS - 1 || app.score <= score_before) return 4;
    return 0;
}

int main(int argc, char** argv) {
    if (argc > 1 && strcmp(argv[1], "--self-test") == 0) {
        int result = invaders_self_test();
        if (result == 0) puts("Space Invaders self-test: PASS");
        return result;
    }

    InitWindow(INVADERS_SCREEN_WIDTH, INVADERS_SCREEN_HEIGHT, "C-plus | Space Invaders");
    defer CloseWindow();
    SetTargetFPS(120);
    invaders_app_t app;
    invaders_init(&app, (uint32_t)(GetTime() * 100000.0) + 3u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            invaders_event_t event = {0};
            event.type = INVADERS_EVENT_KEYBOARD;
            event.key = key;
            invaders_event(&app, &event);
        }
        Vector2 mouse = GetMousePosition();
        invaders_event_t mouse_event = {0};
        mouse_event.type = INVADERS_EVENT_MOUSE;
        mouse_event.position = mouse;
        invaders_event(&app, &mouse_event);

        invaders_event_t clock = {0};
        clock.type = INVADERS_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.left_down = IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A);
        clock.right_down = IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D);
        invaders_event(&app, &clock);

        BeginDrawing();
        invaders_render(&app);
        EndDrawing();
    }
    return 0;
}
