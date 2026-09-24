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
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#define ARKANOID_SCREEN_WIDTH 900
#define ARKANOID_SCREEN_HEIGHT 700
#define ARKANOID_COLUMNS 10
#define ARKANOID_ROWS 5
#define ARKANOID_BRICK_COUNT (ARKANOID_COLUMNS * ARKANOID_ROWS)
#define ARKANOID_MAX_BALLS 8
#define ARKANOID_MAX_POWERUPS 24

typedef enum arkanoid_event_type_t {
    ARKANOID_EVENT_KEYBOARD,
    ARKANOID_EVENT_MOUSE,
    ARKANOID_EVENT_CLOCK
} arkanoid_event_type_t;

typedef enum arkanoid_state_t {
    ARKANOID_READY,
    ARKANOID_PLAYING,
    ARKANOID_LEVEL_COMPLETE,
    ARKANOID_GAME_OVER
} arkanoid_state_t;

typedef enum arkanoid_brick_type_t {
    ARKANOID_BRICK_NORMAL,
    ARKANOID_BRICK_STRONG,
    ARKANOID_BRICK_INDESTRUCTIBLE
} arkanoid_brick_type_t;

typedef enum arkanoid_powerup_type_t {
    ARKANOID_POWERUP_WIDE,
    ARKANOID_POWERUP_MULTIBALL,
    ARKANOID_POWERUP_SLOW,
    ARKANOID_POWERUP_STICKY
} arkanoid_powerup_type_t;

typedef struct arkanoid_ball_t {
    Vector2 position;
    Vector2 velocity;
    float radius;
    bool active;
    bool attached_to_paddle;
} arkanoid_ball_t;

typedef struct arkanoid_brick_t {
    Rectangle bounds;
    arkanoid_brick_type_t type;
    int health;
    bool alive;
} arkanoid_brick_t;

typedef struct arkanoid_powerup_t {
    Rectangle bounds;
    arkanoid_powerup_type_t type;
    bool active;
} arkanoid_powerup_t;

typedef struct arkanoid_event_t {
    arkanoid_event_type_t type;
    int key;
    Vector2 position;
    float delta_seconds;
    bool left_down;
    bool right_down;
    bool mouse_down;
} arkanoid_event_t;

typedef struct arkanoid_app_t {
    arkanoid_state_t state;
    Rectangle paddle;
    arkanoid_ball_t balls[ARKANOID_MAX_BALLS];
    arkanoid_brick_t bricks[ARKANOID_BRICK_COUNT];
    arkanoid_powerup_t powerups[ARKANOID_MAX_POWERUPS];
    int level;
    int score;
    int lives;
    int destructible_bricks;
    uint32_t random_state;
    float paddle_speed;
    float wide_seconds;
    float slow_seconds;
    float sticky_seconds;
    bool mouse_control;

static pub float clamp(float value, float low, float high) {
    if (value < low) return low;
    if (value > high) return high;
    return value;
}

pub uint32_t random(borrowed mut *self) {
    uint32_t value = self->random_state;
    if (value == 0) value = 0x9e3779b9u;
    value ^= value << 13;
    value ^= value >> 17;
    value ^= value << 5;
    self->random_state = value;
    return value;
}

static pub bool circle_hits_rectangle(Vector2 center, float radius, Rectangle rectangle) {
    float nearest_x = arkanoid_app_t.clamp(center.x, rectangle.x, rectangle.x + rectangle.width);
    float nearest_y = arkanoid_app_t.clamp(center.y, rectangle.y, rectangle.y + rectangle.height);
    float dx = center.x - nearest_x;
    float dy = center.y - nearest_y;
    return dx * dx + dy * dy <= radius * radius;
}

pub void build_level(borrowed mut *self) {
    self->destructible_bricks = 0;
    for (int row = 0; row < ARKANOID_ROWS; row++) {
        for (int column = 0; column < ARKANOID_COLUMNS; column++) {
            int index = row * ARKANOID_COLUMNS + column;
            arkanoid_brick_t* brick = &self->bricks[index];
            brick->bounds = (Rectangle){72.0f + column * 78.0f, 96.0f + row * 34.0f, 68.0f, 25.0f};
            brick->alive = true;
            if (row == 2 && (column == 4 || column == 5)) {
                brick->type = ARKANOID_BRICK_INDESTRUCTIBLE;
                brick->health = -1;
            } else if (row == 0 || (self->level > 1 && row == 1)) {
                brick->type = ARKANOID_BRICK_STRONG;
                brick->health = 2;
                self->destructible_bricks++;
            } else {
                brick->type = ARKANOID_BRICK_NORMAL;
                brick->health = 1;
                self->destructible_bricks++;
            }
        }
    }
}

pub void attach_ball(borrowed mut *self) {
    arkanoid_ball_t* ball = &self->balls[0];
    ball->active = true;
    ball->attached_to_paddle = true;
    ball->radius = 9.0f;
    ball->velocity = (Vector2){245.0f, -300.0f};
    ball->position = (Vector2){self->paddle.x + self->paddle.width * 0.5f,
                               self->paddle.y - ball->radius - 1.0f};
    for (int i = 1; i < ARKANOID_MAX_BALLS; i++) self->balls[i].active = false;
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) self->powerups[i].active = false;
}

pub void init(borrowed mut *self, uint32_t seed) {
    memset(self, 0, sizeof(*self));
    self->state = ARKANOID_READY;
    self->level = 1;
    self->lives = 3;
    self->random_state = seed;
    self->paddle_speed = 560.0f;
    self->paddle = (Rectangle){370.0f, 625.0f, 160.0f, 18.0f};
    arkanoid_app_t.build_level(self);
    arkanoid_app_t.attach_ball(self);
}

pub void release_attached(borrowed mut *self) {
    if (self->state == ARKANOID_READY) self->state = ARKANOID_PLAYING;
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
        if (self->balls[i].active && self->balls[i].attached_to_paddle) {
            self->balls[i].attached_to_paddle = false;
        }
    }
}

pub void spawn_powerup(borrowed mut *self, Vector2 position) {
    if (arkanoid_app_t.random(self) % 4u != 0u) return;
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) {
        if (!self->powerups[i].active) {
            self->powerups[i].active = true;
            self->powerups[i].type = (arkanoid_powerup_type_t)(arkanoid_app_t.random(self) % 4u);
            self->powerups[i].bounds = (Rectangle){position.x - 10.0f, position.y - 10.0f, 20.0f, 20.0f};
            return;
        }
    }
}

pub void apply_powerup(borrowed mut *self, arkanoid_powerup_type_t type) {
    if (type == ARKANOID_POWERUP_WIDE) {
        self->wide_seconds = 12.0f;
        self->paddle.width = 220.0f;
    } else if (type == ARKANOID_POWERUP_SLOW) {
        if (self->slow_seconds <= 0.0f) {
            for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
                self->balls[i].velocity.x *= 0.72f;
                self->balls[i].velocity.y *= 0.72f;
            }
        }
        self->slow_seconds = 10.0f;
    } else if (type == ARKANOID_POWERUP_STICKY) {
        self->sticky_seconds = 10.0f;
    } else {
        arkanoid_ball_t source = self->balls[0];
        for (int i = 1; i < ARKANOID_MAX_BALLS; i++) {
            if (!self->balls[i].active && source.active) {
                self->balls[i] = source;
                self->balls[i].attached_to_paddle = false;
                self->balls[i].velocity.x = -source.velocity.x;
                self->balls[i].active = true;
                source = self->balls[i];
            }
        }
    }
}

pub void damage_brick(borrowed mut *self, int index) {
    arkanoid_brick_t* brick = &self->bricks[index];
    if (!brick->alive || brick->type == ARKANOID_BRICK_INDESTRUCTIBLE) return;
    brick->health--;
    if (brick->health <= 0) {
        brick->alive = false;
        self->destructible_bricks--;
        self->score += brick->type == ARKANOID_BRICK_STRONG ? 25 : 10;
        arkanoid_app_t.spawn_powerup(self, (Vector2){brick->bounds.x + brick->bounds.width * 0.5f,
                                             brick->bounds.y + brick->bounds.height * 0.5f});
    } else {
        self->score += 5;
    }
}

pub void update_paddle(borrowed mut *self, const arkanoid_event_t* event) {
    if (self->mouse_control && event->mouse_down) {
        self->paddle.x = event->position.x - self->paddle.width * 0.5f;
    } else {
        if (event->left_down) self->paddle.x -= self->paddle_speed * event->delta_seconds;
        if (event->right_down) self->paddle.x += self->paddle_speed * event->delta_seconds;
    }
    self->paddle.x = arkanoid_app_t.clamp(self->paddle.x, 30.0f, ARKANOID_SCREEN_WIDTH - 30.0f - self->paddle.width);
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
        if (self->balls[i].active && self->balls[i].attached_to_paddle) {
            self->balls[i].position.x = self->paddle.x + self->paddle.width * 0.5f;
            self->balls[i].position.y = self->paddle.y - self->balls[i].radius - 1.0f;
        }
    }
}

pub void update_ball(borrowed mut *self, arkanoid_ball_t* ball, float dt) {
    if (!ball->active || ball->attached_to_paddle) return;
    Vector2 previous = ball->position;
    ball->position.x += ball->velocity.x * dt;
    ball->position.y += ball->velocity.y * dt;

    if (ball->position.x - ball->radius < 28.0f) {
        ball->position.x = 28.0f + ball->radius;
        ball->velocity.x = (float)fabs(ball->velocity.x);
    } else if (ball->position.x + ball->radius > ARKANOID_SCREEN_WIDTH - 28.0f) {
        ball->position.x = ARKANOID_SCREEN_WIDTH - 28.0f - ball->radius;
        ball->velocity.x = -(float)fabs(ball->velocity.x);
    }
    if (ball->position.y - ball->radius < 58.0f) {
        ball->position.y = 58.0f + ball->radius;
        ball->velocity.y = (float)fabs(ball->velocity.y);
    }

    if (ball->velocity.y > 0.0f && arkanoid_app_t.circle_hits_rectangle(ball->position, ball->radius, self->paddle)) {
        float offset = (ball->position.x - (self->paddle.x + self->paddle.width * 0.5f)) /
                       (self->paddle.width * 0.5f);
        ball->position.y = self->paddle.y - ball->radius - 0.5f;
        ball->velocity.x = offset * 350.0f;
        ball->velocity.y = -(float)fabs(ball->velocity.y);
        if (self->sticky_seconds > 0.0f) ball->attached_to_paddle = true;
    }

    for (int i = 0; i < ARKANOID_BRICK_COUNT; i++) {
        arkanoid_brick_t* brick = &self->bricks[i];
        if (!brick->alive || !arkanoid_app_t.circle_hits_rectangle(ball->position, ball->radius, brick->bounds)) continue;
        if (previous.y + ball->radius <= brick->bounds.y || previous.y - ball->radius >= brick->bounds.y + brick->bounds.height) {
            ball->velocity.y = -ball->velocity.y;
        } else {
            ball->velocity.x = -ball->velocity.x;
        }
        arkanoid_app_t.damage_brick(self, i);
        break;
    }
    if (ball->position.y - ball->radius > ARKANOID_SCREEN_HEIGHT) ball->active = false;
}

pub void update_powerups(borrowed mut *self, float dt) {
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) {
        arkanoid_powerup_t* powerup = &self->powerups[i];
        if (!powerup->active) continue;
        powerup->bounds.y += 150.0f * dt;
        if (CheckCollisionRecs(powerup->bounds, self->paddle)) {
            arkanoid_app_t.apply_powerup(self, powerup->type);
            powerup->active = false;
        } else if (powerup->bounds.y > ARKANOID_SCREEN_HEIGHT) {
            powerup->active = false;
        }
    }
}

pub void clock(borrowed mut *self, const arkanoid_event_t* event) {
    float dt = arkanoid_app_t.clamp(event->delta_seconds, 0.0f, 0.05f);
    arkanoid_app_t.update_paddle(self, event);
    if (self->state != ARKANOID_PLAYING) return;

    if (self->wide_seconds > 0.0f) {
        self->wide_seconds -= dt;
        if (self->wide_seconds <= 0.0f) self->paddle.width = 160.0f;
    }
    if (self->slow_seconds > 0.0f) {
        self->slow_seconds -= dt;
        if (self->slow_seconds <= 0.0f) {
            for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
                self->balls[i].velocity.x /= 0.72f;
                self->balls[i].velocity.y /= 0.72f;
            }
        }
    }
    if (self->sticky_seconds > 0.0f) self->sticky_seconds -= dt;

    int steps = (int)(dt / 0.008f) + 1;
    float step = steps == 0 ? 0.0f : dt / steps;
    for (int s = 0; s < steps; s++) {
        for (int i = 0; i < ARKANOID_MAX_BALLS; i++) arkanoid_app_t.update_ball(self, &self->balls[i], step);
    }
    arkanoid_app_t.update_powerups(self, dt);

    if (self->destructible_bricks == 0) {
        self->level++;
        self->state = ARKANOID_LEVEL_COMPLETE;
        arkanoid_app_t.build_level(self);
        arkanoid_app_t.attach_ball(self);
        self->state = ARKANOID_READY;
    }

    bool any_ball = false;
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) any_ball = any_ball || self->balls[i].active;
    if (!any_ball) {
        self->lives--;
        if (self->lives <= 0) {
            self->state = ARKANOID_GAME_OVER;
        } else {
            arkanoid_app_t.attach_ball(self);
            self->state = ARKANOID_READY;
        }
    }
}

pub void event(borrowed mut *self, const arkanoid_event_t* event) {
    if (event->type == ARKANOID_EVENT_KEYBOARD) {
        if (event->key == KEY_M) self->mouse_control = !self->mouse_control;
        if (event->key == KEY_SPACE && self->state == ARKANOID_READY) arkanoid_app_t.release_attached(self);
        if (event->key == KEY_ENTER && self->state == ARKANOID_GAME_OVER) arkanoid_app_t.init(self, self->random_state + 1u);
    } else if (event->type == ARKANOID_EVENT_MOUSE) {
        if (event->mouse_down && self->state == ARKANOID_READY) arkanoid_app_t.release_attached(self);
    } else {
        arkanoid_app_t.clock(self, event);
    }
}

pub void render(borrowed const *self) {
    ClearBackground((Color){12, 18, 35, 255});
    DrawRectangle(25, 55, ARKANOID_SCREEN_WIDTH - 50, ARKANOID_SCREEN_HEIGHT - 75, (Color){20, 29, 52, 255});
    DrawText(TextFormat("LEVEL %d    SCORE %d    LIVES %d", self->level, self->score, self->lives), 32, 20, 22, RAYWHITE);
    for (int i = 0; i < ARKANOID_BRICK_COUNT; i++) {
        const arkanoid_brick_t* brick = &self->bricks[i];
        if (!brick->alive) continue;
        Color color = brick->type == ARKANOID_BRICK_INDESTRUCTIBLE ? DARKGRAY :
                      brick->type == ARKANOID_BRICK_STRONG ? (brick->health == 2 ? ORANGE : GOLD) : SKYBLUE;
        DrawRectangleRec(brick->bounds, color);
        DrawRectangleLinesEx(brick->bounds, 1.0f, (Color){8, 12, 22, 255});
    }
    DrawRectangleRec(self->paddle, self->wide_seconds > 0.0f ? GOLD : RAYWHITE);
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
        if (self->balls[i].active) DrawCircleV(self->balls[i].position, self->balls[i].radius, WHITE);
    }
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) {
        if (self->powerups[i].active) DrawRectangleRec(self->powerups[i].bounds, LIME);
    }
    if (self->state == ARKANOID_READY) DrawText("SPACE / CLICK TO LAUNCH", 300, 365, 26, RAYWHITE);
    if (self->state == ARKANOID_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 240, 365, 26, RED);
}

} arkanoid_app_t;

@test "arkanoid model and render" {
    arkanoid_app_t app;
    app.init(12345u);
    @assert(!(app.state != ARKANOID_READY || app.destructible_bricks != 48));
    @assert(!(!arkanoid_app_t.circle_hits_rectangle((Vector2){5.0f, 5.0f}, 2.0f, (Rectangle){6.0f, 4.0f, 4.0f, 4.0f})));
    @assert(!(arkanoid_app_t.circle_hits_rectangle((Vector2){0.0f, 0.0f}, 1.0f, (Rectangle){5.0f, 5.0f, 2.0f, 2.0f})));
    app.release_attached();
    arkanoid_ball_t* ball = &app.balls[0];
    arkanoid_brick_t* brick = &app.bricks[10];
    ball->position = (Vector2){brick->bounds.x + brick->bounds.width * 0.5f,
                               brick->bounds.y + brick->bounds.height + 15.0f};
    ball->velocity = (Vector2){0.0f, -260.0f};
    arkanoid_event_t clock = {0};
    clock.type = ARKANOID_EVENT_CLOCK;
    clock.delta_seconds = 0.08f;
    app.clock(&clock);
    @assert(!(app.score <= 0 || brick->health >= 1));


    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(ARKANOID_SCREEN_WIDTH, ARKANOID_SCREEN_HEIGHT, "C-plus test render");
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
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/arkanoid.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}



int main(int argc, char** argv) {
        InitWindow(ARKANOID_SCREEN_WIDTH, ARKANOID_SCREEN_HEIGHT, "C-plus | Arkanoid");
    defer CloseWindow();
    SetTargetFPS(120);
    arkanoid_app_t app;
    app.init((uint32_t)(GetTime() * 100000.0) + 1u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            arkanoid_event_t event = {0};
            event.type = ARKANOID_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        Vector2 mouse = GetMousePosition();
        arkanoid_event_t mouse_event = {0};
        mouse_event.type = ARKANOID_EVENT_MOUSE;
        mouse_event.position = mouse;
        mouse_event.mouse_down = IsMouseButtonDown(MOUSE_BUTTON_LEFT);
        app.event(&mouse_event);

        arkanoid_event_t clock = {0};
        clock.type = ARKANOID_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.left_down = IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A);
        clock.right_down = IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D);
        clock.position = mouse;
        clock.mouse_down = mouse_event.mouse_down;
        app.event(&clock);

        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
