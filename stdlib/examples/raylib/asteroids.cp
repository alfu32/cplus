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
#include <stdlib.h>
#include <math.h>

#define ASTEROIDS_SCREEN_WIDTH 960
#define ASTEROIDS_SCREEN_HEIGHT 720
#define ASTEROIDS_MAX_ASTEROIDS 96
#define ASTEROIDS_MAX_BULLETS 64
#define ASTEROIDS_PI 3.14159265358979323846f

typedef enum asteroids_event_type_t {
    ASTEROIDS_EVENT_KEYBOARD,
    ASTEROIDS_EVENT_CLOCK
} asteroids_event_type_t;

typedef enum asteroids_state_t {
    ASTEROIDS_READY,
    ASTEROIDS_PLAYING,
    ASTEROIDS_GAME_OVER
} asteroids_state_t;

typedef enum asteroid_size_t {
    ASTEROID_SMALL,
    ASTEROID_MEDIUM,
    ASTEROID_LARGE
} asteroid_size_t;

typedef struct asteroid_t {
    Vector2 position;
    Vector2 velocity;
    float radius;
    float rotation;
    float spin;
    asteroid_size_t size;
    bool alive;
} asteroid_t;

typedef struct asteroid_bullet_t {
    Vector2 position;
    Vector2 velocity;
    float lifetime;
    bool active;
} asteroid_bullet_t;

typedef struct asteroid_ship_t {
    Vector2 position;
    Vector2 velocity;
    float rotation;
    float turn_speed;
    float thrust;
    float fire_cooldown;
    float invulnerable_seconds;
    int lives;
} asteroid_ship_t;

typedef struct asteroids_event_t {
    asteroids_event_type_t type;
    int key;
    float delta_seconds;
    float turn_axis;
    bool thrust_down;
    bool fire_down;
} asteroids_event_t;

typedef struct asteroids_app_t {
    asteroids_state_t state;
    asteroid_ship_t ship;
    asteroid_t asteroids[ASTEROIDS_MAX_ASTEROIDS];
    asteroid_bullet_t bullets[ASTEROIDS_MAX_BULLETS];
    int score;
    int wave;
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

pub float random_range(borrowed mut *self, float minimum, float maximum) {
    float unit = (float)(asteroids_app_t.random(self) & 0x00ffffffu) / 16777215.0f;
    return minimum + (maximum - minimum) * unit;
}

static pub float clamp(float value, float minimum, float maximum) {
    if (value < minimum) return minimum;
    if (value > maximum) return maximum;
    return value;
}

static pub Vector2 add(Vector2 left, Vector2 right) {
    return (Vector2){left.x + right.x, left.y + right.y};
}

static pub Vector2 scale(Vector2 value, float scalar) {
    return (Vector2){value.x * scalar, value.y * scalar};
}

static pub float distance_squared(Vector2 left, Vector2 right) {
    float dx = fabsf(left.x - right.x);
    float dy = fabsf(left.y - right.y);
    if (dx > ASTEROIDS_SCREEN_WIDTH * 0.5f) dx = ASTEROIDS_SCREEN_WIDTH - dx;
    if (dy > ASTEROIDS_SCREEN_HEIGHT * 0.5f) dy = ASTEROIDS_SCREEN_HEIGHT - dy;
    return dx * dx + dy * dy;
}
static pub Vector2 forward(float rotation) {
    return (Vector2){(float)sinf(rotation), -(float)cosf(rotation)};
}

pub int count(borrowed const *self) {
    int count = 0;
    for (int i = 0; i < ASTEROIDS_MAX_ASTEROIDS; i++) count += self->asteroids[i].alive ? 1 : 0;
    return count;
}

static pub float radius(asteroid_size_t size) {
    return size == ASTEROID_LARGE ? 42.0f : size == ASTEROID_MEDIUM ? 26.0f : 15.0f;
}

pub void add_rock(borrowed mut *self, asteroid_size_t size, Vector2 position, Vector2 velocity) {
    for (int i = 0; i < ASTEROIDS_MAX_ASTEROIDS; i++) {
        if (self->asteroids[i].alive) continue;
        self->asteroids[i] = (asteroid_t){
            position, velocity, asteroids_app_t.radius(size), asteroids_app_t.random_range(self, 0.0f, 360.0f),
            asteroids_app_t.random_range(self, -75.0f, 75.0f), size, true
        };
        return;
    }
}

pub void spawn_wave(borrowed mut *self) {
    self->wave++;
    int count = 3 + self->wave;
    if (count > 9) count = 9;
    for (int i = 0; i < count; i++) {
        Vector2 position;
        if ((i & 1) == 0) {
            position = (Vector2){asteroids_app_t.random_range(self, 0.0f, ASTEROIDS_SCREEN_WIDTH),
                                 (i & 2) ? 0.0f : (float)ASTEROIDS_SCREEN_HEIGHT};
        } else {
            position = (Vector2){(i & 2) ? 0.0f : (float)ASTEROIDS_SCREEN_WIDTH,
                                 asteroids_app_t.random_range(self, 0.0f, ASTEROIDS_SCREEN_HEIGHT)};
        }
        if (asteroids_app_t.distance_squared(position, self->ship.position) < 150.0f * 150.0f)
            position.x = fmodf(position.x + ASTEROIDS_SCREEN_WIDTH * 0.5f, (float)ASTEROIDS_SCREEN_WIDTH);
        float angle = asteroids_app_t.random_range(self, 0.0f, ASTEROIDS_PI * 2.0f);
        float speed = asteroids_app_t.random_range(self, 34.0f, 78.0f) + self->wave * 4.0f;
        asteroids_app_t.add_rock(self, ASTEROID_LARGE, position, (Vector2){(float)cosf(angle) * speed, (float)sinf(angle) * speed});
    }
}

pub void init(borrowed mut *self, uint32_t seed) {
    memset(self, 0, sizeof(*self));
    self->state = ASTEROIDS_READY;
    self->random_state = seed;
    self->ship.position = (Vector2){ASTEROIDS_SCREEN_WIDTH * 0.5f, ASTEROIDS_SCREEN_HEIGHT * 0.5f};
    self->ship.turn_speed = 3.8f;
    self->ship.thrust = 235.0f;
    self->ship.lives = 3;
    asteroids_app_t.spawn_wave(self);
}

static pub void wrap(Vector2* position) {
    if (position->x < 0.0f) position->x += ASTEROIDS_SCREEN_WIDTH;
    if (position->x >= ASTEROIDS_SCREEN_WIDTH) position->x -= ASTEROIDS_SCREEN_WIDTH;
    if (position->y < 0.0f) position->y += ASTEROIDS_SCREEN_HEIGHT;
    if (position->y >= ASTEROIDS_SCREEN_HEIGHT) position->y -= ASTEROIDS_SCREEN_HEIGHT;
}

pub void fire(borrowed mut *self) {
    if (self->ship.fire_cooldown > 0.0f) return;
    for (int i = 0; i < ASTEROIDS_MAX_BULLETS; i++) {
        if (self->bullets[i].active) continue;
        Vector2 forward = asteroids_app_t.forward(self->ship.rotation);
        self->bullets[i].position = asteroids_app_t.add(self->ship.position, asteroids_app_t.scale(forward, 18.0f));
        self->bullets[i].velocity = asteroids_app_t.add(self->ship.velocity, asteroids_app_t.scale(forward, 500.0f));
        self->bullets[i].lifetime = 1.35f;
        self->bullets[i].active = true;
        self->ship.fire_cooldown = 0.19f;
        return;
    }
}

pub void split_rock(borrowed mut *self, asteroid_t* rock) {
    asteroid_t source = *rock;
    rock->alive = false;
    static const int score_by_size[] = {100, 50, 20};
    self->score += score_by_size[source.size];
    if (source.size == ASTEROID_SMALL) return;
    asteroid_size_t smaller = source.size == ASTEROID_LARGE ? ASTEROID_MEDIUM : ASTEROID_SMALL;
    for (int split = 0; split < 2; split++) {
        float angle = asteroids_app_t.random_range(self, 0.0f, ASTEROIDS_PI * 2.0f);
        float speed = 70.0f + asteroids_app_t.random_range(self, 0.0f, 65.0f);
        Vector2 velocity = (Vector2){(float)cosf(angle) * speed + source.velocity.x * 0.35f,
                                     (float)sinf(angle) * speed + source.velocity.y * 0.35f};
        asteroids_app_t.add_rock(self, smaller, source.position, velocity);
    }
}

pub void update(borrowed mut *self, const asteroids_event_t* event) {
    float dt = asteroids_app_t.clamp(event->delta_seconds, 0.0f, 0.05f);
    self->ship.fire_cooldown -= dt;
    if (self->ship.fire_cooldown < 0.0f) self->ship.fire_cooldown = 0.0f;
    self->ship.invulnerable_seconds -= dt;
    if (self->ship.invulnerable_seconds < 0.0f) self->ship.invulnerable_seconds = 0.0f;
    if (self->state != ASTEROIDS_PLAYING) return;

    self->ship.rotation += event->turn_axis * self->ship.turn_speed * dt;
    if (event->thrust_down) {
        self->ship.velocity = asteroids_app_t.add(self->ship.velocity,
            asteroids_app_t.scale(asteroids_app_t.forward(self->ship.rotation), self->ship.thrust * dt));
    }
    self->ship.velocity = asteroids_app_t.scale(self->ship.velocity, 1.0f - dt * 0.16f);
    float ship_speed = (float)sqrtf(self->ship.velocity.x * self->ship.velocity.x + self->ship.velocity.y * self->ship.velocity.y);
    if (ship_speed > 360.0f) self->ship.velocity = asteroids_app_t.scale(self->ship.velocity, 360.0f / ship_speed);
    self->ship.position = asteroids_app_t.add(self->ship.position, asteroids_app_t.scale(self->ship.velocity, dt));
    asteroids_app_t.wrap(&self->ship.position);
    if (event->fire_down) asteroids_app_t.fire(self);

    for (int i = 0; i < ASTEROIDS_MAX_ASTEROIDS; i++) {
        asteroid_t* rock = &self->asteroids[i];
        if (!rock->alive) continue;
        rock->position = asteroids_app_t.add(rock->position, asteroids_app_t.scale(rock->velocity, dt));
        rock->rotation += rock->spin * dt;
        asteroids_app_t.wrap(&rock->position);
    }
    for (int i = 0; i < ASTEROIDS_MAX_BULLETS; i++) {
        asteroid_bullet_t* bullet = &self->bullets[i];
        if (!bullet->active) continue;
        bullet->position = asteroids_app_t.add(bullet->position, asteroids_app_t.scale(bullet->velocity, dt));
        asteroids_app_t.wrap(&bullet->position);
        bullet->lifetime -= dt;
        if (bullet->lifetime <= 0.0f) bullet->active = false;
    }

    for (int bullet_index = 0; bullet_index < ASTEROIDS_MAX_BULLETS; bullet_index++) {
        asteroid_bullet_t* bullet = &self->bullets[bullet_index];
        if (!bullet->active) continue;
        for (int rock_index = 0; rock_index < ASTEROIDS_MAX_ASTEROIDS; rock_index++) {
            asteroid_t* rock = &self->asteroids[rock_index];
            if (!rock->alive) continue;
            float hit_radius = rock->radius + 4.0f;
            if (asteroids_app_t.distance_squared(bullet->position, rock->position) > hit_radius * hit_radius) continue;
            bullet->active = false;
            asteroids_app_t.split_rock(self, rock);
            break;
        }
    }

    if (self->ship.invulnerable_seconds <= 0.0f) {
        for (int i = 0; i < ASTEROIDS_MAX_ASTEROIDS; i++) {
            asteroid_t* rock = &self->asteroids[i];
            float hit_radius = rock->radius + 13.0f;
            if (!rock->alive || asteroids_app_t.distance_squared(self->ship.position, rock->position) > hit_radius * hit_radius) continue;
            self->ship.lives--;
            self->ship.position = (Vector2){ASTEROIDS_SCREEN_WIDTH * 0.5f, ASTEROIDS_SCREEN_HEIGHT * 0.5f};
            self->ship.velocity = (Vector2){0.0f, 0.0f};
            self->ship.invulnerable_seconds = 2.0f;
            if (self->ship.lives <= 0) self->state = ASTEROIDS_GAME_OVER;
            break;
        }
    }
    if (asteroids_app_t.count(self) == 0) asteroids_app_t.spawn_wave(self);
}

pub void event(borrowed mut *self, const asteroids_event_t* event) {
    if (event->type == ASTEROIDS_EVENT_KEYBOARD) {
        if (event->key == KEY_R) asteroids_app_t.init(self, self->random_state ^ 0x165667b1u);
        else if (event->key == KEY_SPACE && self->state == ASTEROIDS_READY) self->state = ASTEROIDS_PLAYING;
        else if (event->key == KEY_ENTER && self->state == ASTEROIDS_GAME_OVER)
            asteroids_app_t.init(self, self->random_state ^ 0xd3a2646cu);
    } else if (event->type == ASTEROIDS_EVENT_CLOCK) {
        asteroids_app_t.update(self, event);
    }
}

static pub void draw_ship(const asteroid_ship_t* ship) {
    Vector2 forward = asteroids_app_t.forward(ship->rotation);
    Vector2 side = (Vector2){forward.y, -forward.x};
    Vector2 tip = asteroids_app_t.add(ship->position, asteroids_app_t.scale(forward, 20.0f));
    Vector2 rear = asteroids_app_t.add(ship->position, asteroids_app_t.scale(forward, -13.0f));
    Vector2 left = asteroids_app_t.add(rear, asteroids_app_t.scale(side, 13.0f));
    Vector2 right = asteroids_app_t.add(rear, asteroids_app_t.scale(side, -13.0f));
    DrawTriangle(tip, left, right, RAYWHITE);
}

pub void render(borrowed const *self) {
    ClearBackground((Color){8, 12, 25, 255});
    for (int i = 0; i < ASTEROIDS_MAX_ASTEROIDS; i++) {
        const asteroid_t* rock = &self->asteroids[i];
        if (!rock->alive) continue;
        Color color = rock->size == ASTEROID_LARGE ? (Color){140, 151, 173, 255} :
            rock->size == ASTEROID_MEDIUM ? (Color){177, 165, 137, 255} : (Color){205, 188, 154, 255};
        DrawPoly(rock->position, 8, rock->radius, rock->rotation, color);
    }
    for (int i = 0; i < ASTEROIDS_MAX_BULLETS; i++)
        if (self->bullets[i].active) DrawCircleV(self->bullets[i].position, 3.0f, GOLD);
    if (self->ship.invulnerable_seconds <= 0.0f || ((int)(self->ship.invulnerable_seconds * 10.0f) & 1) == 0)
        asteroids_app_t.draw_ship(&self->ship);
    if (self->ship.invulnerable_seconds > 0.0f)
        DrawCircleLines((int)self->ship.position.x, (int)self->ship.position.y, 25.0f, SKYBLUE);
    DrawText(TextFormat("SCORE  %06d", self->score), 25, 20, 23, RAYWHITE);
    DrawText(TextFormat("WAVE %d", self->wave), 400, 24, 20, GOLD);
    DrawText(TextFormat("LIVES %d", self->ship.lives), ASTEROIDS_SCREEN_WIDTH - 150, 20, 22, RAYWHITE);
    DrawText("LEFT/RIGHT OR A/D: TURN   UP/W: THRUST   SPACE: FIRE", 215, ASTEROIDS_SCREEN_HEIGHT - 34, 17, LIGHTGRAY);
    if (self->state == ASTEROIDS_READY || self->state == ASTEROIDS_GAME_OVER) {
        const char* message = self->state == ASTEROIDS_READY ? "PRESS SPACE TO START" : "GAME OVER - ENTER TO RESTART";
        DrawRectangle(230, 303, 500, 76, (Color){0, 0, 0, 215});
        DrawText(message, 252, 328, 25, self->state == ASTEROIDS_GAME_OVER ? RED : RAYWHITE);
    }
}
} asteroids_app_t;

@test "asteroids model and render" {
    asteroids_app_t app;
    app.init(54321u);
    app.state = ASTEROIDS_PLAYING;
    memset(app.asteroids, 0, sizeof(app.asteroids));
    memset(app.bullets, 0, sizeof(app.bullets));
    app.wave = 1;
    app.asteroids[0] = (asteroid_t){{300.0f, 300.0f}, {0.0f, 0.0f}, 42.0f, 0.0f, 0.0f, ASTEROID_LARGE, true};
    app.bullets[0] = (asteroid_bullet_t){{300.0f, 300.0f}, {0.0f, 0.0f}, 1.0f, true};
    asteroids_event_t clock = {0};
    clock.type = ASTEROIDS_EVENT_CLOCK;
    clock.delta_seconds = 0.01f;
    app.event(&clock);
    @assert(!(app.score != 20 || app.count() != 2));
    @assert(!(app.bullets[0].active));
    bool fragments_are_medium = true;
    for (int i = 0; i < ASTEROIDS_MAX_ASTEROIDS; i++)
        if (app.asteroids[i].alive && app.asteroids[i].size != ASTEROID_MEDIUM) fragments_are_medium = false;
    @assert(fragments_are_medium);

    int wrap_index = -1;
    for (int i = 0; i < ASTEROIDS_MAX_ASTEROIDS; i++)
        if (app.asteroids[i].alive) { wrap_index = i; break; }
    @assert(!(wrap_index < 0));
    app.asteroids[wrap_index].position = (Vector2){ASTEROIDS_SCREEN_WIDTH - 1.0f, 200.0f};
    app.asteroids[wrap_index].velocity = (Vector2){100.0f, 0.0f};
    clock.delta_seconds = 0.05f;
    app.event(&clock);
    @assert(!(app.asteroids[wrap_index].position.x >= 10.0f));

    memset(app.asteroids, 0, sizeof(app.asteroids));
    memset(app.bullets, 0, sizeof(app.bullets));
    app.ship.position = (Vector2){300.0f, 300.0f};
    app.ship.lives = 2;
    app.ship.invulnerable_seconds = 0.0f;
    app.asteroids[0] = (asteroid_t){{300.0f, 300.0f}, {0.0f, 0.0f}, 15.0f, 0.0f, 0.0f, ASTEROID_SMALL, true};
    app.event(&clock);
    @assert(!(app.ship.lives != 1 || app.ship.invulnerable_seconds <= 0.0f));


    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(ASTEROIDS_SCREEN_WIDTH, ASTEROIDS_SCREEN_HEIGHT, "C-plus test render");
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
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/asteroids.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}

int main(void) {


    InitWindow(ASTEROIDS_SCREEN_WIDTH, ASTEROIDS_SCREEN_HEIGHT, "C-plus | Asteroids");
    defer CloseWindow();
    SetTargetFPS(120);
    asteroids_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 29u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            asteroids_event_t event = {0};
            event.type = ASTEROIDS_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        asteroids_event_t clock = {0};
        clock.type = ASTEROIDS_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.turn_axis = (float)(IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D)) -
            (float)(IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A));
        clock.thrust_down = IsKeyDown(KEY_UP) || IsKeyDown(KEY_W);
        clock.fire_down = IsKeyDown(KEY_SPACE);
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
