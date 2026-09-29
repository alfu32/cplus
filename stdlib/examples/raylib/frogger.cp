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

#define FROGGER_SCREEN_WIDTH 900
#define FROGGER_SCREEN_HEIGHT 720
#define FROGGER_LANE_HEIGHT 52.0f
#define FROGGER_MAX_LANES 13
#define FROGGER_MAX_OBJECTS 64
#define FROGGER_GOAL_COUNT 5

typedef enum frogger_event_type_t {
    FROGGER_EVENT_KEYBOARD,
    FROGGER_EVENT_MOUSE,
    FROGGER_EVENT_CLOCK
} frogger_event_type_t;

typedef enum frogger_state_t {
    FROGGER_READY,
    FROGGER_PLAYING,
    FROGGER_LEVEL_COMPLETE,
    FROGGER_GAME_OVER
} frogger_state_t;

typedef enum frogger_lane_type_t {
    FROGGER_LANE_SAFE,
    FROGGER_LANE_ROAD,
    FROGGER_LANE_WATER,
    FROGGER_LANE_GOAL
} frogger_lane_type_t;

typedef enum frogger_object_type_t {
    FROGGER_CAR,
    FROGGER_TRUCK,
    FROGGER_LOG,
    FROGGER_TURTLE
} frogger_object_type_t;

typedef struct frogger_lane_t {
    frogger_lane_type_t type;
    float y;
} frogger_lane_t;

typedef struct frogger_object_t {
    frogger_object_type_t type;
    Rectangle bounds;
    float velocity_x;
    bool active;
} frogger_object_t;

typedef struct frogger_player_t {
    Vector2 position;
    Vector2 size;
    int lives;
} frogger_player_t;

typedef struct frogger_event_t {
    frogger_event_type_t type;
    int key;
    float delta_seconds;
} frogger_event_t;

typedef struct frogger_app_t {
    frogger_state_t state;
    frogger_player_t player;
    frogger_lane_t lanes[FROGGER_MAX_LANES];
    frogger_object_t objects[FROGGER_MAX_OBJECTS];
    bool goals[FROGGER_GOAL_COUNT];
    int lane_count;
    int object_count;
    int score;
    int level;
    float remaining_time;

    static pub float clamp(float value, float low, float high) {
        if (value < low) return low;
        if (value > high) return high;
        return value;
    }

    pub Rectangle player_bounds(borrowed const *self) {
        return (Rectangle){self->player.position.x, self->player.position.y,
                           self->player.size.x, self->player.size.y};
    }

    pub int current_lane(borrowed const *self) {
        float center_y = self->player.position.y + self->player.size.y * 0.5f;
        int lane = (int)(center_y / FROGGER_LANE_HEIGHT);
        if (lane < 0) lane = 0;
        if (lane >= self->lane_count) lane = self->lane_count - 1;
        return lane;
    }

    pub void add_object(borrowed mut *self, frogger_object_type_t type,
                        int lane, float x, float width, float velocity_x) {
        if (self->object_count >= FROGGER_MAX_OBJECTS) return;
        frogger_object_t* object = &self->objects[self->object_count++];
        object->type = type;
        object->bounds = (Rectangle){x,
            lane * FROGGER_LANE_HEIGHT + 8.0f,
            width, FROGGER_LANE_HEIGHT - 16.0f};
        object->velocity_x = velocity_x;
        object->active = true;
    }

    pub void reset_player(borrowed mut *self) {
        self->player.size = (Vector2){30.0f, 30.0f};
        self->player.position = (Vector2){
            FROGGER_SCREEN_WIDTH * 0.5f - self->player.size.x * 0.5f,
            (self->lane_count - 1) * FROGGER_LANE_HEIGHT + 11.0f
        };
    }

    pub void build_level(borrowed mut *self) {
        self->lane_count = FROGGER_MAX_LANES;
        self->object_count = 0;
        memset(self->lanes, 0, sizeof(self->lanes));
        memset(self->objects, 0, sizeof(self->objects));

        frogger_lane_type_t types[FROGGER_MAX_LANES] = {
            FROGGER_LANE_GOAL,
            FROGGER_LANE_WATER,
            FROGGER_LANE_WATER,
            FROGGER_LANE_WATER,
            FROGGER_LANE_WATER,
            FROGGER_LANE_WATER,
            FROGGER_LANE_SAFE,
            FROGGER_LANE_ROAD,
            FROGGER_LANE_ROAD,
            FROGGER_LANE_ROAD,
            FROGGER_LANE_ROAD,
            FROGGER_LANE_ROAD,
            FROGGER_LANE_SAFE
        };
        for (int i = 0; i < self->lane_count; i++) {
            self->lanes[i].type = types[i];
            self->lanes[i].y = i * FROGGER_LANE_HEIGHT;
        }

        float speed = 70.0f + self->level * 7.0f;
        frogger_app_t.add_object(self, FROGGER_LOG,    1,  30.0f, 150.0f,  speed * 0.85f);
        frogger_app_t.add_object(self, FROGGER_LOG,    1, 330.0f, 150.0f,  speed * 0.85f);
        frogger_app_t.add_object(self, FROGGER_LOG,    1, 630.0f, 150.0f,  speed * 0.85f);
        frogger_app_t.add_object(self, FROGGER_TURTLE, 2,  80.0f, 110.0f, -speed);
        frogger_app_t.add_object(self, FROGGER_TURTLE, 2, 360.0f, 110.0f, -speed);
        frogger_app_t.add_object(self, FROGGER_TURTLE, 2, 650.0f, 110.0f, -speed);
        frogger_app_t.add_object(self, FROGGER_LOG,    3,  10.0f, 185.0f,  speed * 1.15f);
        frogger_app_t.add_object(self, FROGGER_LOG,    3, 410.0f, 185.0f,  speed * 1.15f);
        frogger_app_t.add_object(self, FROGGER_TURTLE, 4, 120.0f, 125.0f, -speed * 0.9f);
        frogger_app_t.add_object(self, FROGGER_TURTLE, 4, 460.0f, 125.0f, -speed * 0.9f);
        frogger_app_t.add_object(self, FROGGER_LOG,    5,  70.0f, 165.0f,  speed);
        frogger_app_t.add_object(self, FROGGER_LOG,    5, 500.0f, 165.0f,  speed);

        frogger_app_t.add_object(self, FROGGER_CAR,   7,  30.0f,  72.0f, -speed * 1.25f);
        frogger_app_t.add_object(self, FROGGER_CAR,   7, 280.0f,  72.0f, -speed * 1.25f);
        frogger_app_t.add_object(self, FROGGER_CAR,   7, 540.0f,  72.0f, -speed * 1.25f);
        frogger_app_t.add_object(self, FROGGER_TRUCK, 8, 120.0f, 135.0f,  speed * 0.82f);
        frogger_app_t.add_object(self, FROGGER_TRUCK, 8, 570.0f, 135.0f,  speed * 0.82f);
        frogger_app_t.add_object(self, FROGGER_CAR,   9,  10.0f,  68.0f, -speed * 1.6f);
        frogger_app_t.add_object(self, FROGGER_CAR,   9, 210.0f,  68.0f, -speed * 1.6f);
        frogger_app_t.add_object(self, FROGGER_CAR,   9, 430.0f,  68.0f, -speed * 1.6f);
        frogger_app_t.add_object(self, FROGGER_CAR,   9, 690.0f,  68.0f, -speed * 1.6f);
        frogger_app_t.add_object(self, FROGGER_TRUCK,10,  75.0f, 145.0f,  speed);
        frogger_app_t.add_object(self, FROGGER_TRUCK,10, 490.0f, 145.0f,  speed);
        frogger_app_t.add_object(self, FROGGER_CAR,  11,  50.0f,  76.0f, -speed * 1.1f);
        frogger_app_t.add_object(self, FROGGER_CAR,  11, 320.0f,  76.0f, -speed * 1.1f);
        frogger_app_t.add_object(self, FROGGER_CAR,  11, 670.0f,  76.0f, -speed * 1.1f);
    }

    pub void init(borrowed mut *self) {
        memset(self, 0, sizeof(*self));
        self->state = FROGGER_READY;
        self->level = 1;
        self->player.lives = 3;
        self->remaining_time = 45.0f;
        frogger_app_t.build_level(self);
        frogger_app_t.reset_player(self);
    }

    pub void lose_life(borrowed mut *self) {
        self->player.lives--;
        if (self->player.lives <= 0) {
            self->state = FROGGER_GAME_OVER;
            return;
        }
        self->remaining_time = 45.0f;
        frogger_app_t.reset_player(self);
    }

    pub void move_player(borrowed mut *self, int dx, int dy) {
        if (self->state == FROGGER_READY) self->state = FROGGER_PLAYING;
        if (self->state != FROGGER_PLAYING) return;
        self->player.position.x += dx * FROGGER_LANE_HEIGHT;
        self->player.position.y += dy * FROGGER_LANE_HEIGHT;
        self->player.position.x = frogger_app_t.clamp(self->player.position.x, 0.0f,
            FROGGER_SCREEN_WIDTH - self->player.size.x);
        self->player.position.y = frogger_app_t.clamp(self->player.position.y, 0.0f,
            (self->lane_count - 1) * FROGGER_LANE_HEIGHT + 11.0f);
        if (dy < 0) self->score += 10;
    }

    pub void update_objects(borrowed mut *self, float dt) {
        for (int i = 0; i < self->object_count; i++) {
            frogger_object_t* object = &self->objects[i];
            if (!object->active) continue;
            object->bounds.x += object->velocity_x * dt;
            if (object->velocity_x > 0.0f && object->bounds.x > FROGGER_SCREEN_WIDTH + 20.0f)
                object->bounds.x = -object->bounds.width - 20.0f;
            else if (object->velocity_x < 0.0f && object->bounds.x + object->bounds.width < -20.0f)
                object->bounds.x = FROGGER_SCREEN_WIDTH + 20.0f;
        }
    }

    pub int carrier_at(borrowed const *self, Rectangle frog, int lane) {
        for (int i = 0; i < self->object_count; i++) {
            const frogger_object_t* object = &self->objects[i];
            if (!object->active) continue;
            if (object->type != FROGGER_LOG && object->type != FROGGER_TURTLE) continue;
            int object_lane = (int)(object->bounds.y / FROGGER_LANE_HEIGHT);
            if (object_lane == lane && CheckCollisionRecs(frog, object->bounds)) return i;
        }
        return -1;
    }

    pub bool vehicle_collision(borrowed const *self, Rectangle frog, int lane) {
        for (int i = 0; i < self->object_count; i++) {
            const frogger_object_t* object = &self->objects[i];
            if (!object->active) continue;
            if (object->type != FROGGER_CAR && object->type != FROGGER_TRUCK) continue;
            int object_lane = (int)(object->bounds.y / FROGGER_LANE_HEIGHT);
            if (object_lane == lane && CheckCollisionRecs(frog, object->bounds)) return true;
        }
        return false;
    }

    pub void reach_goal(borrowed mut *self) {
        float segment = (float)FROGGER_SCREEN_WIDTH / FROGGER_GOAL_COUNT;
        int goal = (int)((self->player.position.x + self->player.size.x * 0.5f) / segment);
        if (goal < 0) goal = 0;
        if (goal >= FROGGER_GOAL_COUNT) goal = FROGGER_GOAL_COUNT - 1;
        if (!self->goals[goal]) {
            self->goals[goal] = true;
            self->score += 100 + (int)self->remaining_time;
        }
        bool complete = true;
        for (int i = 0; i < FROGGER_GOAL_COUNT; i++) complete = complete && self->goals[i];
        if (complete) {
            self->state = FROGGER_LEVEL_COMPLETE;
            self->level++;
            memset(self->goals, 0, sizeof(self->goals));
            frogger_app_t.build_level(self);
            self->state = FROGGER_PLAYING;
        }
        self->remaining_time = 45.0f;
        frogger_app_t.reset_player(self);
    }

    pub void clock(borrowed mut *self, const frogger_event_t* event) {
        float dt = frogger_app_t.clamp(event->delta_seconds, 0.0f, 0.05f);
        frogger_app_t.update_objects(self, dt);
        if (self->state != FROGGER_PLAYING) return;

        self->remaining_time -= dt;
        if (self->remaining_time <= 0.0f) {
            frogger_app_t.lose_life(self);
            return;
        }

        int lane = frogger_app_t.current_lane(self);
        Rectangle frog = frogger_app_t.player_bounds(self);
        frogger_lane_type_t type = self->lanes[lane].type;

        if (type == FROGGER_LANE_ROAD) {
            if (frogger_app_t.vehicle_collision(self, frog, lane)) frogger_app_t.lose_life(self);
        } else if (type == FROGGER_LANE_WATER) {
            int carrier = frogger_app_t.carrier_at(self, frog, lane);
            if (carrier < 0) {
                frogger_app_t.lose_life(self);
                return;
            }
            self->player.position.x += self->objects[carrier].velocity_x * dt;
            if (self->player.position.x + self->player.size.x < 0.0f ||
                self->player.position.x > FROGGER_SCREEN_WIDTH) {
                frogger_app_t.lose_life(self);
            }
        } else if (type == FROGGER_LANE_GOAL) {
            frogger_app_t.reach_goal(self);
        }
    }

    pub void event(borrowed mut *self, const frogger_event_t* event) {
        if (event->type == FROGGER_EVENT_KEYBOARD) {
            if (event->key == KEY_LEFT || event->key == KEY_A) frogger_app_t.move_player(self, -1, 0);
            else if (event->key == KEY_RIGHT || event->key == KEY_D) frogger_app_t.move_player(self, 1, 0);
            else if (event->key == KEY_UP || event->key == KEY_W) frogger_app_t.move_player(self, 0, -1);
            else if (event->key == KEY_DOWN || event->key == KEY_S) frogger_app_t.move_player(self, 0, 1);
            else if (event->key == KEY_ENTER && self->state == FROGGER_GAME_OVER) frogger_app_t.init(self);
        } else if (event->type == FROGGER_EVENT_CLOCK) {
            frogger_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
        ClearBackground((Color){18, 24, 34, 255});
        for (int lane = 0; lane < self->lane_count; lane++) {
            Color color = (Color){48, 78, 47, 255};
            if (self->lanes[lane].type == FROGGER_LANE_ROAD) color = (Color){48, 49, 55, 255};
            else if (self->lanes[lane].type == FROGGER_LANE_WATER) color = (Color){28, 78, 135, 255};
            else if (self->lanes[lane].type == FROGGER_LANE_GOAL) color = (Color){31, 102, 55, 255};
            DrawRectangle(0, (int)self->lanes[lane].y, FROGGER_SCREEN_WIDTH, (int)FROGGER_LANE_HEIGHT, color);
            DrawLine(0, (int)(self->lanes[lane].y + FROGGER_LANE_HEIGHT), FROGGER_SCREEN_WIDTH,
                     (int)(self->lanes[lane].y + FROGGER_LANE_HEIGHT), (Color){255,255,255,28});
        }
        for (int g = 0; g < FROGGER_GOAL_COUNT; g++) {
            float width = (float)FROGGER_SCREEN_WIDTH / FROGGER_GOAL_COUNT;
            Rectangle slot = (Rectangle){g * width + 18.0f, 8.0f, width - 36.0f, 36.0f};
            DrawRectangleRec(slot, self->goals[g] ? LIME : DARKGREEN);
        }
        for (int i = 0; i < self->object_count; i++) {
            const frogger_object_t* object = &self->objects[i];
            Color color = object->type == FROGGER_CAR ? RED :
                          object->type == FROGGER_TRUCK ? ORANGE :
                          object->type == FROGGER_LOG ? BROWN : GREEN;
            DrawRectangleRec(object->bounds, color);
        }
        DrawRectangleRec(frogger_app_t.player_bounds(self), LIME);
        DrawText(TextFormat("SCORE %d   LIVES %d   LEVEL %d   TIME %.1f", self->score,
                 self->player.lives, self->level, self->remaining_time), 18, 684, 22, RAYWHITE);
        if (self->state == FROGGER_READY) DrawText("ARROWS/WASD TO START", 310, 330, 25, RAYWHITE);
        if (self->state == FROGGER_GAME_OVER) DrawText("GAME OVER - ENTER TO RESTART", 250, 330, 25, RED);
    }
} frogger_app_t;

@test "frogger movement, hazards and carriers" {
    frogger_app_t app;
    app.init();
    @assert(!(app.state != FROGGER_READY || app.player.lives != 3 || app.object_count <= 0));

    float start_y = app.player.position.y;
    app.move_player(0, -1);
    @assert(!(app.state != FROGGER_PLAYING || app.player.position.y >= start_y));

    app.player.position = (Vector2){app.objects[12].bounds.x, app.objects[12].bounds.y};
    int lives = app.player.lives;
    frogger_event_t tick = {0};
    tick.type = FROGGER_EVENT_CLOCK;
    tick.delta_seconds = 0.001f;
    app.clock(&tick);
    @assert(!(app.player.lives != lives - 1));

    app.reset_player();
    app.state = FROGGER_PLAYING;
    app.player.position = (Vector2){app.objects[0].bounds.x + 20.0f, app.objects[0].bounds.y};
    float carried_x = app.player.position.x;
    tick.delta_seconds = 0.02f;
    app.clock(&tick);
    @assert(!(app.player.position.x <= carried_x));
}

int main(int argc, char** argv) {
    InitWindow(FROGGER_SCREEN_WIDTH, FROGGER_SCREEN_HEIGHT, "C-plus | Frogger");
    defer CloseWindow();
    SetTargetFPS(120);
    frogger_app_t app;
    app.init();
    while (!WindowShouldClose()) {
        int key;
        while ((key = GetKeyPressed()) != 0) {
            frogger_event_t event = {0};
            event.type = FROGGER_EVENT_KEYBOARD;
            event.key = key;
            app.event(&event);
        }
        frogger_event_t clock = {0};
        clock.type = FROGGER_EVENT_CLOCK;
        clock.delta_seconds = GetFrameTime();
        app.event(&clock);
        BeginDrawing();
        app.render();
        EndDrawing();
    }
    return 0;
}
