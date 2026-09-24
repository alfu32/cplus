comptime import "stdlib:/graphics/raylib.cp";
comptime flags -lGL -lm -lpthread -ldl -lrt -lXrandr -lXinerama -lXcursor -lXi -lraylib;

#include <stdbool.h>
#include <stdint.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

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
} arkanoid_app_t;

static float arkanoid_clamp(float value, float low, float high) {
    if (value < low) return low;
    if (value > high) return high;
    return value;
}

static uint32_t arkanoid_random(arkanoid_app_t* app) {
    uint32_t value = app->random_state;
    if (value == 0) value = 0x9e3779b9u;
    value ^= value << 13;
    value ^= value >> 17;
    value ^= value << 5;
    app->random_state = value;
    return value;
}

static bool arkanoid_circle_hits_rectangle(Vector2 center, float radius, Rectangle rectangle) {
    float nearest_x = arkanoid_clamp(center.x, rectangle.x, rectangle.x + rectangle.width);
    float nearest_y = arkanoid_clamp(center.y, rectangle.y, rectangle.y + rectangle.height);
    float dx = center.x - nearest_x;
    float dy = center.y - nearest_y;
    return dx * dx + dy * dy <= radius * radius;
}

static void arkanoid_build_level(arkanoid_app_t* app) {
    app->destructible_bricks = 0;
    for (int row = 0; row < ARKANOID_ROWS; row++) {
        for (int column = 0; column < ARKANOID_COLUMNS; column++) {
            int index = row * ARKANOID_COLUMNS + column;
            arkanoid_brick_t* brick = &app->bricks[index];
            brick->bounds = (Rectangle){72.0f + column * 78.0f, 96.0f + row * 34.0f, 68.0f, 25.0f};
            brick->alive = true;
            if (row == 2 && (column == 4 || column == 5)) {
                brick->type = ARKANOID_BRICK_INDESTRUCTIBLE;
                brick->health = -1;
            } else if (row == 0 || (app->level > 1 && row == 1)) {
                brick->type = ARKANOID_BRICK_STRONG;
                brick->health = 2;
                app->destructible_bricks++;
            } else {
                brick->type = ARKANOID_BRICK_NORMAL;
                brick->health = 1;
                app->destructible_bricks++;
            }
        }
    }
}

static void arkanoid_attach_ball(arkanoid_app_t* app) {
    arkanoid_ball_t* ball = &app->balls[0];
    ball->active = true;
    ball->attached_to_paddle = true;
    ball->radius = 9.0f;
    ball->velocity = (Vector2){245.0f, -300.0f};
    ball->position = (Vector2){app->paddle.x + app->paddle.width * 0.5f,
                               app->paddle.y - ball->radius - 1.0f};
    for (int i = 1; i < ARKANOID_MAX_BALLS; i++) app->balls[i].active = false;
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) app->powerups[i].active = false;
}

static void arkanoid_init(arkanoid_app_t* app, uint32_t seed) {
    memset(app, 0, sizeof(*app));
    app->state = ARKANOID_READY;
    app->level = 1;
    app->lives = 3;
    app->random_state = seed;
    app->paddle_speed = 560.0f;
    app->paddle = (Rectangle){370.0f, 625.0f, 160.0f, 18.0f};
    arkanoid_build_level(app);
    arkanoid_attach_ball(app);
}

static void arkanoid_release_attached(arkanoid_app_t* app) {
    if (app->state == ARKANOID_READY) app->state = ARKANOID_PLAYING;
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
        if (app->balls[i].active && app->balls[i].attached_to_paddle) {
            app->balls[i].attached_to_paddle = false;
        }
    }
}

static void arkanoid_spawn_powerup(arkanoid_app_t* app, Vector2 position) {
    if (arkanoid_random(app) % 4u != 0u) return;
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) {
        if (!app->powerups[i].active) {
            app->powerups[i].active = true;
            app->powerups[i].type = (arkanoid_powerup_type_t)(arkanoid_random(app) % 4u);
            app->powerups[i].bounds = (Rectangle){position.x - 10.0f, position.y - 10.0f, 20.0f, 20.0f};
            return;
        }
    }
}

static void arkanoid_apply_powerup(arkanoid_app_t* app, arkanoid_powerup_type_t type) {
    if (type == ARKANOID_POWERUP_WIDE) {
        app->wide_seconds = 12.0f;
        app->paddle.width = 220.0f;
    } else if (type == ARKANOID_POWERUP_SLOW) {
        if (app->slow_seconds <= 0.0f) {
            for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
                app->balls[i].velocity.x *= 0.72f;
                app->balls[i].velocity.y *= 0.72f;
            }
        }
        app->slow_seconds = 10.0f;
    } else if (type == ARKANOID_POWERUP_STICKY) {
        app->sticky_seconds = 10.0f;
    } else {
        arkanoid_ball_t source = app->balls[0];
        for (int i = 1; i < ARKANOID_MAX_BALLS; i++) {
            if (!app->balls[i].active && source.active) {
                app->balls[i] = source;
                app->balls[i].attached_to_paddle = false;
                app->balls[i].velocity.x = -source.velocity.x;
                app->balls[i].active = true;
                source = app->balls[i];
            }
        }
    }
}

static void arkanoid_damage_brick(arkanoid_app_t* app, int index) {
    arkanoid_brick_t* brick = &app->bricks[index];
    if (!brick->alive || brick->type == ARKANOID_BRICK_INDESTRUCTIBLE) return;
    brick->health--;
    if (brick->health <= 0) {
        brick->alive = false;
        app->destructible_bricks--;
        app->score += brick->type == ARKANOID_BRICK_STRONG ? 25 : 10;
        arkanoid_spawn_powerup(app, (Vector2){brick->bounds.x + brick->bounds.width * 0.5f,
                                             brick->bounds.y + brick->bounds.height * 0.5f});
    } else {
        app->score += 5;
    }
}

static void arkanoid_update_paddle(arkanoid_app_t* app, const arkanoid_event_t* event) {
    if (app->mouse_control && event->mouse_down) {
        app->paddle.x = event->position.x - app->paddle.width * 0.5f;
    } else {
        if (event->left_down) app->paddle.x -= app->paddle_speed * event->delta_seconds;
        if (event->right_down) app->paddle.x += app->paddle_speed * event->delta_seconds;
    }
    app->paddle.x = arkanoid_clamp(app->paddle.x, 30.0f, ARKANOID_SCREEN_WIDTH - 30.0f - app->paddle.width);
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
        if (app->balls[i].active && app->balls[i].attached_to_paddle) {
            app->balls[i].position.x = app->paddle.x + app->paddle.width * 0.5f;
            app->balls[i].position.y = app->paddle.y - app->balls[i].radius - 1.0f;
        }
    }
}

static void arkanoid_update_ball(arkanoid_app_t* app, arkanoid_ball_t* ball, float dt) {
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

    if (ball->velocity.y > 0.0f && arkanoid_circle_hits_rectangle(ball->position, ball->radius, app->paddle)) {
        float offset = (ball->position.x - (app->paddle.x + app->paddle.width * 0.5f)) /
                       (app->paddle.width * 0.5f);
        ball->position.y = app->paddle.y - ball->radius - 0.5f;
        ball->velocity.x = offset * 350.0f;
        ball->velocity.y = -(float)fabs(ball->velocity.y);
        if (app->sticky_seconds > 0.0f) ball->attached_to_paddle = true;
    }

    for (int i = 0; i < ARKANOID_BRICK_COUNT; i++) {
        arkanoid_brick_t* brick = &app->bricks[i];
        if (!brick->alive || !arkanoid_circle_hits_rectangle(ball->position, ball->radius, brick->bounds)) continue;
        if (previous.y + ball->radius <= brick->bounds.y || previous.y - ball->radius >= brick->bounds.y + brick->bounds.height) {
            ball->velocity.y = -ball->velocity.y;
        } else {
            ball->velocity.x = -ball->velocity.x;
        }
        arkanoid_damage_brick(app, i);
        break;
    }
    if (ball->position.y - ball->radius > ARKANOID_SCREEN_HEIGHT) ball->active = false;
}

static void arkanoid_update_powerups(arkanoid_app_t* app, float dt) {
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) {
        arkanoid_powerup_t* powerup = &app->powerups[i];
        if (!powerup->active) continue;
        powerup->bounds.y += 150.0f * dt;
        if (CheckCollisionRecs(powerup->bounds, app->paddle)) {
            arkanoid_apply_powerup(app, powerup->type);
            powerup->active = false;
        } else if (powerup->bounds.y > ARKANOID_SCREEN_HEIGHT) {
            powerup->active = false;
        }
    }
}

static void arkanoid_clock(arkanoid_app_t* app, const arkanoid_event_t* event) {
    float dt = arkanoid_clamp(event->delta_seconds, 0.0f, 0.05f);
    arkanoid_update_paddle(app, event);
    if (app->state != ARKANOID_PLAYING) return;

    if (app->wide_seconds > 0.0f) {
        app->wide_seconds -= dt;
        if (app->wide_seconds <= 0.0f) app->paddle.width = 160.0f;
    }
    if (app->slow_seconds > 0.0f) {
        app->slow_seconds -= dt;
        if (app->slow_seconds <= 0.0f) {
            for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
                app->balls[i].velocity.x /= 0.72f;
                app->balls[i].velocity.y /= 0.72f;
            }
        }
    }
    if (app->sticky_seconds > 0.0f) app->sticky_seconds -= dt;

    int steps = (int)(dt / 0.008f) + 1;
    float step = steps == 0 ? 0.0f : dt / steps;
    for (int s = 0; s < steps; s++) {
        for (int i = 0; i < ARKANOID_MAX_BALLS; i++) arkanoid_update_ball(app, &app->balls[i], step);
    }
    arkanoid_update_powerups(app, dt);

    if (app->destructible_bricks == 0) {
        app->level++;
        app->state = ARKANOID_LEVEL_COMPLETE;
        arkanoid_build_level(app);
        arkanoid_attach_ball(app);
        app->state = ARKANOID_READY;
    }

    bool any_ball = false;
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) any_ball = any_ball || app->balls[i].active;
    if (!any_ball) {
        app->lives--;
        if (app->lives <= 0) {
            app->state = ARKANOID_GAME_OVER;
        } else {
            arkanoid_attach_ball(app);
            app->state = ARKANOID_READY;
        }
    }
}

static void arkanoid_event(arkanoid_app_t* app, const arkanoid_event_t* event) {
    if (event->type == ARKANOID_EVENT_KEYBOARD) {
        if (event->key == KEY_M) app->mouse_control = !app->mouse_control;
        if (event->key == KEY_SPACE && app->state == ARKANOID_READY) arkanoid_release_attached(app);
        if (event->key == KEY_ENTER && app->state == ARKANOID_GAME_OVER) arkanoid_init(app, app->random_state + 1u);
    } else if (event->type == ARKANOID_EVENT_MOUSE) {
        if (event->mouse_down && app->state == ARKANOID_READY) arkanoid_release_attached(app);
    } else {
        arkanoid_clock(app, event);
    }
}

static void arkanoid_render(const arkanoid_app_t* app) {
    ClearBackground((Color){12, 18, 35, 255});
    DrawRectangle(25, 55, ARKANOID_SCREEN_WIDTH - 50, ARKANOID_SCREEN_HEIGHT - 75, (Color){20, 29, 52, 255});
    DrawText(TextFormat("LEVEL %d    SCORE %d    LIVES %d", app->level, app->score, app->lives), 32, 20, 22, RAYWHITE);
    for (int i = 0; i < ARKANOID_BRICK_COUNT; i++) {
        const arkanoid_brick_t* brick = &app->bricks[i];
        if (!brick->alive) continue;
        Color color = brick->type == ARKANOID_BRICK_INDESTRUCTIBLE ? DARKGRAY :
                      brick->type == ARKANOID_BRICK_STRONG ? (brick->health == 2 ? ORANGE : GOLD) : SKYBLUE;
        DrawRectangleRec(brick->bounds, color);
        DrawRectangleLinesEx(brick->bounds, 1.0f, (Color){8, 12, 22, 255});
    }
    DrawRectangleRec(app->paddle, app->wide_seconds > 0.0f ? GOLD : RAYWHITE);
    for (int i = 0; i < ARKANOID_MAX_BALLS; i++) {
        if (app->balls[i].active) DrawCircleV(app->balls[i].position, app->balls[i].radius, WHITE);
    }
    for (int i = 0; i < ARKANOID_MAX_POWERUPS; i++) {
        if (app->powerups[i].active) DrawRectangleRec(app->powerups[i].bounds, LIME);
    }
    if (app->state == ARKANOID_READY) DrawText("SPACE / CLICK TO LAUNCH", 300, 365, 26, RAYWHITE);
    if (app->state == ARKANOID_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 240, 365, 26, RED);
}

static int arkanoid_self_test(void) {
    arkanoid_app_t app;
    arkanoid_init(&app, 12345u);
    if (app.state != ARKANOID_READY || app.destructible_bricks != 48) return 1;
    if (!arkanoid_circle_hits_rectangle((Vector2){5.0f, 5.0f}, 2.0f, (Rectangle){6.0f, 4.0f, 4.0f, 4.0f})) return 2;
    if (arkanoid_circle_hits_rectangle((Vector2){0.0f, 0.0f}, 1.0f, (Rectangle){5.0f, 5.0f, 2.0f, 2.0f})) return 3;
    arkanoid_release_attached(&app);
    arkanoid_ball_t* ball = &app.balls[0];
    arkanoid_brick_t* brick = &app.bricks[10];
    ball->position = (Vector2){brick->bounds.x + brick->bounds.width * 0.5f,
                               brick->bounds.y + brick->bounds.height + 15.0f};
    ball->velocity = (Vector2){0.0f, -260.0f};
    arkanoid_event_t clock = {0};
    clock.type = ARKANOID_EVENT_CLOCK;
    clock.delta_seconds = 0.08f;
    arkanoid_clock(&app, &clock);
    if (app.score <= 0 || brick->health >= 1) return 4;
    return 0;
}

int main(int argc, char** argv) {
    if (argc > 1 && strcmp(argv[1], "--self-test") == 0) {
        int result = arkanoid_self_test();
        if (result == 0) puts("Arkanoid self-test: PASS");
        return result;
    }

    InitWindow(ARKANOID_SCREEN_WIDTH, ARKANOID_SCREEN_HEIGHT, "C-plus | Arkanoid");
    defer CloseWindow();
    SetTargetFPS(120);
    arkanoid_app_t app;
    arkanoid_init(&app, (uint32_t)(GetTime() * 100000.0) + 1u);
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            arkanoid_event_t event = {0};
            event.type = ARKANOID_EVENT_KEYBOARD;
            event.key = key;
            arkanoid_event(&app, &event);
        }
        Vector2 mouse = GetMousePosition();
        arkanoid_event_t mouse_event = {0};
        mouse_event.type = ARKANOID_EVENT_MOUSE;
        mouse_event.position = mouse;
        mouse_event.mouse_down = IsMouseButtonDown(MOUSE_BUTTON_LEFT);
        arkanoid_event(&app, &mouse_event);

        arkanoid_event_t clock = {0};
        clock.type = ARKANOID_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.left_down = IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A);
        clock.right_down = IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D);
        clock.position = mouse;
        clock.mouse_down = mouse_event.mouse_down;
        arkanoid_event(&app, &clock);

        BeginDrawing();
        arkanoid_render(&app);
        EndDrawing();
    }
    return 0;
}
