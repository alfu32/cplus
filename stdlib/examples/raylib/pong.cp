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

#define PONG_SCREEN_WIDTH 960
#define PONG_SCREEN_HEIGHT 600
#define PONG_PADDLE_WIDTH 14.0f
#define PONG_PADDLE_HEIGHT 92.0f
#define PONG_BALL_RADIUS 9.0f
#define PONG_WINNING_SCORE 7

typedef enum pong_event_type_t {
    PONG_EVENT_KEYBOARD,
    PONG_EVENT_MOUSE,
    PONG_EVENT_CLOCK
} pong_event_type_t;

typedef enum pong_state_t {
    PONG_READY,
    PONG_PLAYING,
    PONG_POINT_SCORED,
    PONG_GAME_OVER
} pong_state_t;

typedef struct pong_paddle_t {
    Vector2 position;
    float speed;
} pong_paddle_t;

typedef struct pong_ball_t {
    Vector2 position;
    Vector2 velocity;
    float radius;
} pong_ball_t;

typedef struct pong_event_t {
    pong_event_type_t type;
    int key;
    float delta_seconds;
    float left_axis;
    float right_axis;
    float mouse_y;
    bool mouse_control;
} pong_event_t;

typedef struct pong_app_t {
    pong_state_t state;
    pong_paddle_t left;
    pong_paddle_t right;
    pong_ball_t ball;
    int left_score;
    int right_score;
    int winning_score;
    int serve_direction;
    float point_timer;

    static pub float clamp(float value, float minimum, float maximum) {
        if (value < minimum) return minimum;
        if (value > maximum) return maximum;
        return value;
    }

    pub void reset_ball(borrowed mut *self) {
        self->ball.position = (Vector2){PONG_SCREEN_WIDTH * 0.5f, PONG_SCREEN_HEIGHT * 0.5f};
        self->ball.velocity = (Vector2){0.0f, 0.0f};
        self->ball.radius = PONG_BALL_RADIUS;
    }

    pub void init(borrowed mut *self) {
        memset(self, 0, sizeof(*self));
        self->state = PONG_READY;
        self->left.position = (Vector2){48.0f, PONG_SCREEN_HEIGHT * 0.5f};
        self->right.position = (Vector2){PONG_SCREEN_WIDTH - 48.0f, PONG_SCREEN_HEIGHT * 0.5f};
        self->left.speed = self->right.speed = 420.0f;
        self->winning_score = PONG_WINNING_SCORE;
        self->serve_direction = 1;
        pong_app_t.reset_ball(self);
    }

    pub void serve(borrowed mut *self) {
        self->state = PONG_PLAYING;
        self->ball.position = (Vector2){PONG_SCREEN_WIDTH * 0.5f, PONG_SCREEN_HEIGHT * 0.5f};
        self->ball.velocity = (Vector2){self->serve_direction * 360.0f, 145.0f};
    }
    pub void score(borrowed mut *self, bool left_player_scored) {
        if (left_player_scored) {
            self->left_score++;
            self->serve_direction = -1;
        } else {
            self->right_score++;
            self->serve_direction = 1;
        }
        pong_app_t.reset_ball(self);
        if (self->left_score >= self->winning_score || self->right_score >= self->winning_score) {
            self->state = PONG_GAME_OVER;
        } else {
            self->state = PONG_POINT_SCORED;
            self->point_timer = 0.8f;
        }
    }

    static pub bool ball_overlaps_paddle(const pong_ball_t* ball, const pong_paddle_t* paddle) {
        return ball->position.y + ball->radius >= paddle->position.y - PONG_PADDLE_HEIGHT * 0.5f &&
            ball->position.y - ball->radius <= paddle->position.y + PONG_PADDLE_HEIGHT * 0.5f;
    }

    pub void clock(borrowed mut *self, const pong_event_t* event) {
        float dt = pong_app_t.clamp(event->delta_seconds, 0.0f, 0.05f);
        self->left.position.y += event->left_axis * self->left.speed * dt;
        self->right.position.y += event->right_axis * self->right.speed * dt;
        self->left.position.y = pong_app_t.clamp(self->left.position.y, PONG_PADDLE_HEIGHT * 0.5f,
                                        PONG_SCREEN_HEIGHT - PONG_PADDLE_HEIGHT * 0.5f);
        self->right.position.y = pong_app_t.clamp(self->right.position.y, PONG_PADDLE_HEIGHT * 0.5f,
                                        PONG_SCREEN_HEIGHT - PONG_PADDLE_HEIGHT * 0.5f);

        if (self->state == PONG_POINT_SCORED) {
            self->point_timer -= dt;
            if (self->point_timer <= 0.0f) self->state = PONG_READY;
            return;
        }
        if (self->state != PONG_PLAYING) return;

        self->ball.position.x += self->ball.velocity.x * dt;
        self->ball.position.y += self->ball.velocity.y * dt;
        if (self->ball.position.y - self->ball.radius < 0.0f) {
            self->ball.position.y = self->ball.radius;
            self->ball.velocity.y = (float)fabs(self->ball.velocity.y);
        } else if (self->ball.position.y + self->ball.radius > PONG_SCREEN_HEIGHT) {
            self->ball.position.y = PONG_SCREEN_HEIGHT - self->ball.radius;
            self->ball.velocity.y = -(float)fabs(self->ball.velocity.y);
        }

        float left_face = self->left.position.x + PONG_PADDLE_WIDTH * 0.5f;
        if (self->ball.velocity.x < 0.0f && self->ball.position.x - self->ball.radius <= left_face &&
            self->ball.position.x > self->left.position.x && pong_app_t.ball_overlaps_paddle(&self->ball, &self->left)) {
            float offset = (self->ball.position.y - self->left.position.y) / (PONG_PADDLE_HEIGHT * 0.5f);
            self->ball.position.x = left_face + self->ball.radius;
            self->ball.velocity.x = (float)fabs(self->ball.velocity.x) * 1.035f;
            self->ball.velocity.y += offset * 105.0f;
        }
        float right_face = self->right.position.x - PONG_PADDLE_WIDTH * 0.5f;
        if (self->ball.velocity.x > 0.0f && self->ball.position.x + self->ball.radius >= right_face &&
            self->ball.position.x < self->right.position.x && pong_app_t.ball_overlaps_paddle(&self->ball, &self->right)) {
            float offset = (self->ball.position.y - self->right.position.y) / (PONG_PADDLE_HEIGHT * 0.5f);
            self->ball.position.x = right_face - self->ball.radius;
            self->ball.velocity.x = -(float)fabs(self->ball.velocity.x) * 1.035f;
            self->ball.velocity.y += offset * 105.0f;
        }

        if (self->ball.position.x + self->ball.radius < 0.0f) pong_app_t.score(self, false);
        else if (self->ball.position.x - self->ball.radius > PONG_SCREEN_WIDTH) pong_app_t.score(self, true);
    }

    pub void event(borrowed mut *self, const pong_event_t* event) {
        if (event->type == PONG_EVENT_KEYBOARD) {
            if (event->key == KEY_R) {
                pong_app_t.init(self);
            } else if (event->key == KEY_SPACE && (self->state == PONG_READY || self->state == PONG_GAME_OVER)) {
                if (self->state == PONG_GAME_OVER) pong_app_t.init(self);
                pong_app_t.serve(self);
            }
        } else if (event->type == PONG_EVENT_MOUSE && event->mouse_control) {
            self->right.position.y = event->mouse_y;
        } else if (event->type == PONG_EVENT_CLOCK) {
            pong_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){14, 20, 32, 255});
        for (int y = 12; y < PONG_SCREEN_HEIGHT; y += 30) DrawRectangle(PONG_SCREEN_WIDTH / 2 - 2, y, 4, 14, (Color){76, 87, 108, 255});
        DrawText(TextFormat("%d", self->left_score), PONG_SCREEN_WIDTH / 2 - 90, 34, 48, RAYWHITE);
        DrawText(TextFormat("%d", self->right_score), PONG_SCREEN_WIDTH / 2 + 48, 34, 48, RAYWHITE);
        DrawRectangle((int)(self->left.position.x - PONG_PADDLE_WIDTH * 0.5f),
                    (int)(self->left.position.y - PONG_PADDLE_HEIGHT * 0.5f),
                    (int)PONG_PADDLE_WIDTH, (int)PONG_PADDLE_HEIGHT, SKYBLUE);
        DrawRectangle((int)(self->right.position.x - PONG_PADDLE_WIDTH * 0.5f),
                    (int)(self->right.position.y - PONG_PADDLE_HEIGHT * 0.5f),
                    (int)PONG_PADDLE_WIDTH, (int)PONG_PADDLE_HEIGHT, ORANGE);
        DrawCircleV(self->ball.position, (int)self->ball.radius, RAYWHITE);
        if (self->state == PONG_READY) DrawText("SPACE TO SERVE", PONG_SCREEN_WIDTH / 2 - 98, PONG_SCREEN_HEIGHT - 55, 20, RAYWHITE);
        if (self->state == PONG_GAME_OVER) {
            const char* winner = self->left_score > self->right_score ? "LEFT PLAYER WINS" : "RIGHT PLAYER WINS";
            DrawRectangle(250, 244, 460, 102, (Color){0, 0, 0, 210});
            DrawText(winner, 320, 258, 30, GOLD);
            DrawText("SPACE: PLAY AGAIN   R: RESET", 302, 302, 18, RAYWHITE);
        }
        DrawText("W/S    UP/DOWN    MOUSE: HOLD LEFT BUTTON    R: RESET", 210, PONG_SCREEN_HEIGHT - 25, 15, LIGHTGRAY);
    }
} pong_app_t;

@test "pong model and render" {
    pong_app_t app;
    app.init();
    app.serve();
    app.ball.position = (Vector2){app.left.position.x + PONG_PADDLE_WIDTH, app.left.position.y};
    app.ball.velocity = (Vector2){-300.0f, 0.0f};
    pong_event_t clock = {0};
    clock.type = PONG_EVENT_CLOCK;
    clock.delta_seconds = 0.02f;
    app.event(&clock);
    @assert(!(app.ball.velocity.x <= 0.0f));
    app.ball.position = (Vector2){0.0f, 40.0f};
    app.ball.velocity = (Vector2){-500.0f, 0.0f};
    app.event(&clock);
    @assert(!(app.right_score != 1 || app.state != PONG_POINT_SCORED));
    app.left_score = app.winning_score - 1;
    app.ball.position = (Vector2){PONG_SCREEN_WIDTH, 40.0f};
    app.ball.velocity = (Vector2){500.0f, 0.0f};
    app.state = PONG_PLAYING;
    app.event(&clock);
    @assert(!(app.state != PONG_GAME_OVER || app.left_score != app.winning_score));


    const char* screenshot_directory = getenv("CPLUS_TEST_SCREENSHOT_DIR");
    if (screenshot_directory != NULL) {
        SetConfigFlags(FLAG_WINDOW_HIDDEN);
        InitWindow(PONG_SCREEN_WIDTH, PONG_SCREEN_HEIGHT, "C-plus test render");
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
                snprintf(screenshot_path, sizeof(screenshot_path), "%s/pong.png", screenshot_directory);
                @assert(ExportImage(screenshot, screenshot_path));
                UnloadImage(screenshot);
            }
        }
    }

}

int main(void) {


    InitWindow(PONG_SCREEN_WIDTH, PONG_SCREEN_HEIGHT, "C-plus | Pong");
    defer CloseWindow();
    SetTargetFPS(120);
    pong_app_t app;
    app.init();
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            pong_event_t event = {0};
            event.type = PONG_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        pong_event_t clock = {0};
        clock.type = PONG_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        clock.left_axis = (float)(IsKeyDown(KEY_S) - IsKeyDown(KEY_W));
        clock.right_axis = (float)(IsKeyDown(KEY_DOWN) - IsKeyDown(KEY_UP));
        pong_event_t mouse = {0};
        mouse.type = PONG_EVENT_MOUSE;
        mouse.mouse_control = IsMouseButtonDown(MOUSE_BUTTON_LEFT);
        mouse.mouse_y = GetMouseY();
        app.event(&mouse);
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
