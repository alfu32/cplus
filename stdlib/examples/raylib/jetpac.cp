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

#define JETPAC_SCREEN_WIDTH 960
#define JETPAC_SCREEN_HEIGHT 640
#define JETPAC_MAX_ENEMIES 32
#define JETPAC_MAX_PROJECTILES 32
#define JETPAC_MAX_ITEMS 16
#define JETPAC_MAX_PLATFORMS 8

typedef enum jetpac_event_type_t {
    JETPAC_EVENT_KEYBOARD,
    JETPAC_EVENT_MOUSE,
    JETPAC_EVENT_CLOCK
} jetpac_event_type_t;

typedef enum jetpac_state_t {
    JETPAC_READY,
    JETPAC_PLAYING,
    JETPAC_LEVEL_COMPLETE,
    JETPAC_GAME_OVER
} jetpac_state_t;

typedef enum jetpac_item_type_t {
    JETPAC_ROCKET_PART,
    JETPAC_FUEL,
    JETPAC_TREASURE
} jetpac_item_type_t;

typedef struct jetpac_player_t {
    Vector2 position;
    Vector2 velocity;
    Vector2 size;
    bool grounded;
    bool carrying;
    int carried_item;
    int facing;
    int lives;
} jetpac_player_t;

typedef struct jetpac_enemy_t {
    Vector2 position;
    Vector2 velocity;
    float radius;
    bool alive;
} jetpac_enemy_t;

typedef struct jetpac_projectile_t {
    Vector2 position;
    Vector2 velocity;
    float lifetime;
    bool active;
} jetpac_projectile_t;

typedef struct jetpac_item_t {
    jetpac_item_type_t type;
    Vector2 position;
    bool active;
    bool collected;
} jetpac_item_t;

typedef struct jetpac_rocket_t {
    Vector2 position;
    int assembled_parts;
    int required_parts;
    float fuel;
    float fuel_required;
    bool ready;
} jetpac_rocket_t;

typedef struct jetpac_event_t {
    jetpac_event_type_t type;
    int key;
    float delta_seconds;
    float move_axis;
    bool thrust_down;
    bool fire_down;
} jetpac_event_t;

typedef struct jetpac_app_t {
    jetpac_state_t state;
    jetpac_player_t player;
    jetpac_rocket_t rocket;
    jetpac_enemy_t enemies[JETPAC_MAX_ENEMIES];
    jetpac_projectile_t projectiles[JETPAC_MAX_PROJECTILES];
    jetpac_item_t items[JETPAC_MAX_ITEMS];
    Rectangle platforms[JETPAC_MAX_PLATFORMS];
    int platform_count;
    int enemy_count;
    int item_count;
    int level;
    int score;
    uint32_t random_state;
    float fire_cooldown;
    float enemy_spawn_timer;

    static pub float clamp(float value, float low, float high) {
        if (value < low) return low;
        if (value > high) return high;
        return value;
    }

    pub uint32_t random(borrowed mut *self) {
        uint32_t value = self->random_state;
        if (value == 0) value = 0xa341316cu;
        value ^= value << 13;
        value ^= value >> 17;
        value ^= value << 5;
        self->random_state = value;
        return value;
    }

    pub Rectangle player_bounds(borrowed const *self) {
        return (Rectangle){self->player.position.x, self->player.position.y,
                           self->player.size.x, self->player.size.y};
    }

    pub Rectangle rocket_bounds(borrowed const *self) {
        return (Rectangle){self->rocket.position.x, self->rocket.position.y, 70.0f, 105.0f};
    }

    pub Rectangle item_bounds(borrowed const *self, int index) {
        return (Rectangle){self->items[index].position.x - 9.0f,
                           self->items[index].position.y - 9.0f, 18.0f, 18.0f};
    }

    pub void spawn_enemy(borrowed mut *self) {
        for (int i = 0; i < JETPAC_MAX_ENEMIES; i++) {
            if (!self->enemies[i].alive) {
                jetpac_enemy_t* enemy = &self->enemies[i];
                enemy->alive = true;
                enemy->radius = 13.0f;
                bool left = (jetpac_app_t.random(self) & 1u) == 0u;
                enemy->position = (Vector2){left ? -20.0f : JETPAC_SCREEN_WIDTH + 20.0f,
                    70.0f + (float)(jetpac_app_t.random(self) % 430u)};
                float speed = 80.0f + self->level * 7.0f + (float)(jetpac_app_t.random(self) % 55u);
                enemy->velocity = (Vector2){left ? speed : -speed,
                    ((int)(jetpac_app_t.random(self) % 61u) - 30) * 0.5f};
                return;
            }
        }
    }

    pub void add_item(borrowed mut *self, jetpac_item_type_t type, float x, float y) {
        if (self->item_count >= JETPAC_MAX_ITEMS) return;
        jetpac_item_t* item = &self->items[self->item_count++];
        item->type = type;
        item->position = (Vector2){x, y};
        item->active = true;
        item->collected = false;
    }

    pub void build_level(borrowed mut *self) {
        memset(self->enemies, 0, sizeof(self->enemies));
        memset(self->projectiles, 0, sizeof(self->projectiles));
        memset(self->items, 0, sizeof(self->items));
        self->platform_count = 5;
        self->platforms[0] = (Rectangle){0.0f, 590.0f, 960.0f, 50.0f};
        self->platforms[1] = (Rectangle){85.0f, 455.0f, 220.0f, 18.0f};
        self->platforms[2] = (Rectangle){390.0f, 370.0f, 185.0f, 18.0f};
        self->platforms[3] = (Rectangle){680.0f, 475.0f, 205.0f, 18.0f};
        self->platforms[4] = (Rectangle){715.0f, 260.0f, 165.0f, 18.0f};
        self->rocket.position = (Vector2){430.0f, 485.0f};
        self->rocket.required_parts = 3;
        self->rocket.assembled_parts = 1;
        self->rocket.fuel = 0.0f;
        self->rocket.fuel_required = 3.0f;
        self->rocket.ready = false;
        self->item_count = 0;
        jetpac_app_t.add_item(self, JETPAC_ROCKET_PART, 180.0f, 425.0f);
        jetpac_app_t.add_item(self, JETPAC_ROCKET_PART, 790.0f, 230.0f);
        jetpac_app_t.add_item(self, JETPAC_FUEL, 480.0f, 340.0f);
        jetpac_app_t.add_item(self, JETPAC_FUEL, 790.0f, 445.0f);
        jetpac_app_t.add_item(self, JETPAC_FUEL, 235.0f, 425.0f);
        jetpac_app_t.add_item(self, JETPAC_TREASURE, 610.0f, 540.0f);
        self->enemy_spawn_timer = 0.5f;
        for (int i = 0; i < 4 + self->level; i++) jetpac_app_t.spawn_enemy(self);
    }

    pub void reset_player(borrowed mut *self) {
        self->player.position = (Vector2){110.0f, 410.0f};
        self->player.velocity = (Vector2){0.0f, 0.0f};
        self->player.size = (Vector2){28.0f, 36.0f};
        self->player.grounded = false;
        self->player.carrying = false;
        self->player.carried_item = -1;
        self->player.facing = 1;
    }

    pub void init(borrowed mut *self, uint32_t seed) {
        memset(self, 0, sizeof(*self));
        self->state = JETPAC_READY;
        self->level = 1;
        self->player.lives = 3;
        self->random_state = seed;
        jetpac_app_t.build_level(self);
        jetpac_app_t.reset_player(self);
    }

    pub void fire(borrowed mut *self) {
        if (self->state == JETPAC_READY) self->state = JETPAC_PLAYING;
        if (self->state != JETPAC_PLAYING || self->fire_cooldown > 0.0f) return;
        for (int i = 0; i < JETPAC_MAX_PROJECTILES; i++) {
            if (!self->projectiles[i].active) {
                jetpac_projectile_t* shot = &self->projectiles[i];
                shot->active = true;
                shot->lifetime = 1.6f;
                shot->position = (Vector2){self->player.position.x + self->player.size.x * 0.5f,
                                           self->player.position.y + self->player.size.y * 0.42f};
                shot->velocity = (Vector2){self->player.facing * 520.0f, 0.0f};
                self->fire_cooldown = 0.18f;
                return;
            }
        }
    }

    pub void pickup(borrowed mut *self, int item_index) {
        if (self->player.carrying || item_index < 0 || item_index >= self->item_count) return;
        jetpac_item_t* item = &self->items[item_index];
        if (!item->active || item->collected) return;
        item->active = false;
        self->player.carrying = true;
        self->player.carried_item = item_index;
    }

    pub void deliver_item(borrowed mut *self) {
        if (!self->player.carrying) return;
        int index = self->player.carried_item;
        if (index < 0 || index >= self->item_count) return;
        jetpac_item_t* item = &self->items[index];
        if (item->type == JETPAC_ROCKET_PART) {
            if (self->rocket.assembled_parts < self->rocket.required_parts) {
                self->rocket.assembled_parts++;
                self->score += 250;
            }
        } else if (item->type == JETPAC_FUEL) {
            if (self->rocket.assembled_parts >= self->rocket.required_parts && self->rocket.fuel < self->rocket.fuel_required) {
                self->rocket.fuel += 1.0f;
                self->score += 150;
            }
        } else {
            self->score += 500;
        }
        item->collected = true;
        self->player.carrying = false;
        self->player.carried_item = -1;
        self->rocket.ready = self->rocket.assembled_parts >= self->rocket.required_parts &&
                             self->rocket.fuel >= self->rocket.fuel_required;
    }

    pub void respawn_player(borrowed mut *self) {
        self->player.lives--;
        if (self->player.lives <= 0) {
            self->state = JETPAC_GAME_OVER;
            return;
        }
        if (self->player.carrying) {
            int index = self->player.carried_item;
            if (index >= 0 && index < self->item_count && !self->items[index].collected)
                self->items[index].active = true;
        }
        jetpac_app_t.reset_player(self);
    }

    pub void integrate_player(borrowed mut *self, float dt, float move_axis, bool thrust_down) {
        float acceleration = 620.0f;
        float drag = 5.5f;
        self->player.velocity.x += move_axis * acceleration * dt;
        if (move_axis == 0.0f) self->player.velocity.x *= 1.0f / (1.0f + drag * dt);
        if (move_axis < 0.0f) self->player.facing = -1;
        if (move_axis > 0.0f) self->player.facing = 1;
        self->player.velocity.x = jetpac_app_t.clamp(self->player.velocity.x, -230.0f, 230.0f);
        if (thrust_down) self->player.velocity.y -= 760.0f * dt;
        self->player.velocity.y += 620.0f * dt;
        self->player.velocity.y = jetpac_app_t.clamp(self->player.velocity.y, -290.0f, 360.0f);

        self->player.position.x += self->player.velocity.x * dt;
        if (self->player.position.x + self->player.size.x < 0.0f) self->player.position.x = JETPAC_SCREEN_WIDTH;
        if (self->player.position.x > JETPAC_SCREEN_WIDTH) self->player.position.x = -self->player.size.x;

        float old_bottom = self->player.position.y + self->player.size.y;
        self->player.position.y += self->player.velocity.y * dt;
        self->player.grounded = false;
        Rectangle player = jetpac_app_t.player_bounds(self);
        if (self->player.velocity.y >= 0.0f) {
            for (int i = 0; i < self->platform_count; i++) {
                Rectangle platform = self->platforms[i];
                float new_bottom = self->player.position.y + self->player.size.y;
                bool horizontal = player.x + player.width > platform.x && player.x < platform.x + platform.width;
                if (horizontal && old_bottom <= platform.y && new_bottom >= platform.y) {
                    self->player.position.y = platform.y - self->player.size.y;
                    self->player.velocity.y = 0.0f;
                    self->player.grounded = true;
                    break;
                }
            }
        }
        if (self->player.position.y > JETPAC_SCREEN_HEIGHT + 80.0f) jetpac_app_t.respawn_player(self);
    }

    pub void update_projectiles(borrowed mut *self, float dt) {
        for (int i = 0; i < JETPAC_MAX_PROJECTILES; i++) {
            jetpac_projectile_t* shot = &self->projectiles[i];
            if (!shot->active) continue;
            shot->position.x += shot->velocity.x * dt;
            shot->position.y += shot->velocity.y * dt;
            shot->lifetime -= dt;
            if (shot->lifetime <= 0.0f || shot->position.x < -30.0f || shot->position.x > JETPAC_SCREEN_WIDTH + 30.0f) {
                shot->active = false;
                continue;
            }
            for (int e = 0; e < JETPAC_MAX_ENEMIES; e++) {
                jetpac_enemy_t* enemy = &self->enemies[e];
                if (!enemy->alive) continue;
                float dx = shot->position.x - enemy->position.x;
                float dy = shot->position.y - enemy->position.y;
                if (dx * dx + dy * dy <= enemy->radius * enemy->radius) {
                    enemy->alive = false;
                    shot->active = false;
                    self->score += 100;
                    break;
                }
            }
        }
    }

    pub void update_enemies(borrowed mut *self, float dt) {
        Rectangle player = jetpac_app_t.player_bounds(self);
        Vector2 center = (Vector2){player.x + player.width * 0.5f, player.y + player.height * 0.5f};
        for (int i = 0; i < JETPAC_MAX_ENEMIES; i++) {
            jetpac_enemy_t* enemy = &self->enemies[i];
            if (!enemy->alive) continue;
            float dy = center.y - enemy->position.y;
            enemy->velocity.y += jetpac_app_t.clamp(dy * 0.9f, -45.0f, 45.0f) * dt;
            enemy->velocity.y = jetpac_app_t.clamp(enemy->velocity.y, -65.0f, 65.0f);
            enemy->position.x += enemy->velocity.x * dt;
            enemy->position.y += enemy->velocity.y * dt;
            if (enemy->position.x < -80.0f) enemy->position.x = JETPAC_SCREEN_WIDTH + 70.0f;
            if (enemy->position.x > JETPAC_SCREEN_WIDTH + 80.0f) enemy->position.x = -70.0f;
            if (enemy->position.y < 30.0f) enemy->position.y = 30.0f;
            if (enemy->position.y > 560.0f) enemy->position.y = 560.0f;
            if (CheckCollisionCircleRec(enemy->position, enemy->radius, player)) {
                jetpac_app_t.respawn_player(self);
                return;
            }
        }
    }

    pub void update_items(borrowed mut *self) {
        Rectangle player = jetpac_app_t.player_bounds(self);
        if (!self->player.carrying) {
            for (int i = 0; i < self->item_count; i++) {
                if (self->items[i].active && CheckCollisionRecs(player, jetpac_app_t.item_bounds(self, i))) {
                    jetpac_app_t.pickup(self, i);
                    break;
                }
            }
        }
        if (self->player.carrying) {
            int index = self->player.carried_item;
            if (index >= 0 && index < self->item_count)
                self->items[index].position = (Vector2){self->player.position.x + self->player.size.x * 0.5f,
                                                       self->player.position.y - 12.0f};
            Rectangle delivery = jetpac_app_t.rocket_bounds(self);
            delivery.x -= 20.0f; delivery.width += 40.0f;
            delivery.y -= 20.0f; delivery.height += 40.0f;
            if (CheckCollisionRecs(player, delivery)) jetpac_app_t.deliver_item(self);
        }
    }

    pub void clock(borrowed mut *self, const jetpac_event_t* event) {
        if (self->state == JETPAC_READY && (event->move_axis != 0.0f || event->thrust_down || event->fire_down))
            self->state = JETPAC_PLAYING;
        if (self->state != JETPAC_PLAYING) return;
        float dt = jetpac_app_t.clamp(event->delta_seconds, 0.0f, 0.05f);
        if (self->fire_cooldown > 0.0f) self->fire_cooldown -= dt;
        jetpac_app_t.integrate_player(self, dt, event->move_axis, event->thrust_down);
        if (self->state == JETPAC_GAME_OVER) return;
        if (event->fire_down) jetpac_app_t.fire(self);
        jetpac_app_t.update_projectiles(self, dt);
        jetpac_app_t.update_enemies(self, dt);
        if (self->state == JETPAC_GAME_OVER) return;
        jetpac_app_t.update_items(self);

        self->enemy_spawn_timer -= dt;
        if (self->enemy_spawn_timer <= 0.0f) {
            jetpac_app_t.spawn_enemy(self);
            self->enemy_spawn_timer = 2.5f - jetpac_app_t.clamp(self->level * 0.12f, 0.0f, 1.3f);
        }
        if (self->rocket.ready && CheckCollisionRecs(jetpac_app_t.player_bounds(self), jetpac_app_t.rocket_bounds(self))) {
            self->state = JETPAC_LEVEL_COMPLETE;
            self->score += 1000;
            self->level++;
            jetpac_app_t.build_level(self);
            jetpac_app_t.reset_player(self);
            self->state = JETPAC_PLAYING;
        }
    }

    pub void event(borrowed mut *self, const jetpac_event_t* event) {
        if (event->type == JETPAC_EVENT_KEYBOARD) {
            if (event->key == KEY_ENTER && self->state == JETPAC_GAME_OVER)
                jetpac_app_t.init(self, self->random_state + 1u);
            else if (event->key == KEY_SPACE)
                jetpac_app_t.fire(self);
        } else if (event->type == JETPAC_EVENT_CLOCK) {
            jetpac_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){10, 13, 28, 255});
        for (int i = 0; i < self->platform_count; i++) DrawRectangleRec(self->platforms[i], (Color){72, 78, 96, 255});
        Rectangle rocket = jetpac_app_t.rocket_bounds(self);
        DrawRectangleRec(rocket, self->rocket.ready ? LIME : LIGHTGRAY);
        DrawText(TextFormat("%d/%d", self->rocket.assembled_parts, self->rocket.required_parts),
                 (int)rocket.x + 14, (int)rocket.y + 15, 18, BLACK);
        DrawText(TextFormat("F %.0f/%.0f", self->rocket.fuel, self->rocket.fuel_required),
                 (int)rocket.x + 7, (int)rocket.y + 48, 16, BLACK);
        for (int i = 0; i < self->item_count; i++) {
            const jetpac_item_t* item = &self->items[i];
            if (!item->active || item->collected) continue;
            Color color = item->type == JETPAC_ROCKET_PART ? SKYBLUE : item->type == JETPAC_FUEL ? GOLD : MAGENTA;
            DrawCircleV(item->position, 9.0f, color);
        }
        for (int i = 0; i < JETPAC_MAX_ENEMIES; i++) {
            if (self->enemies[i].alive) DrawCircleV(self->enemies[i].position, self->enemies[i].radius, RED);
        }
        for (int i = 0; i < JETPAC_MAX_PROJECTILES; i++) {
            if (self->projectiles[i].active) DrawCircleV(self->projectiles[i].position, 3.0f, YELLOW);
        }
        DrawRectangleRec(jetpac_app_t.player_bounds(self), RAYWHITE);
        DrawText(TextFormat("SCORE %d   LIVES %d   LEVEL %d", self->score, self->player.lives, self->level),
                 18, 16, 22, RAYWHITE);
        if (self->state == JETPAC_READY) DrawText("A/D MOVE  W/UP THRUST  SPACE FIRE", 270, 65, 22, RAYWHITE);
        if (self->state == JETPAC_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 285, 300, 26, RED);
    }
} jetpac_app_t;

@test "jetpac pickups, delivery, projectiles and platforms" {
    jetpac_app_t app;
    app.init(1234u);
    @assert(!(app.state != JETPAC_READY || app.player.lives != 3 || app.item_count < 6));

    app.state = JETPAC_PLAYING;
    int part = 0;
    app.pickup(part);
    @assert(!(!app.player.carrying || app.player.carried_item != part || app.items[part].active));
    int before_parts = app.rocket.assembled_parts;
    app.deliver_item();
    @assert(!(app.player.carrying || app.rocket.assembled_parts != before_parts + 1 || !app.items[part].collected));

    app.fire_cooldown = 0.0f;
    app.fire();
    bool active_shot = false;
    for (int i = 0; i < JETPAC_MAX_PROJECTILES; i++) active_shot = active_shot || app.projectiles[i].active;
    @assert(!(!active_shot));

    app.player.position = (Vector2){100.0f, 400.0f};
    app.player.velocity = (Vector2){0.0f, 180.0f};
    app.integrate_player(0.25f, 0.0f, false);
    @assert(!(app.player.position.y + app.player.size.y > app.platforms[1].y + 0.1f));
}

int main(int argc, char** argv) {
    InitWindow(JETPAC_SCREEN_WIDTH, JETPAC_SCREEN_HEIGHT, "C-plus | Jetpac");
    defer CloseWindow();
    SetTargetFPS(120);
    jetpac_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 1u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            jetpac_event_t event = {0};
            event.type = JETPAC_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        jetpac_event_t clock = {0};
        clock.type = JETPAC_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.move_axis = (IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D) ? 1.0f : 0.0f) -
                          (IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A) ? 1.0f : 0.0f);
        clock.thrust_down = IsKeyDown(KEY_UP) || IsKeyDown(KEY_W);
        clock.fire_down = IsKeyDown(KEY_SPACE);
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
