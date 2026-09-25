- [x] Pong — paddle/ball collision, simplest continuous simulation.
- [x] Arkanoid — continuous physics + many static colliders 
- [x] Space Invaders entity groups + projectiles
- [x] Breakout — basically pre-Arkanoid; useful as a stripped-down brick breaker.
- [x] Snake — discrete grid movement, self-collision, clock-driven stepping.
- [ ] Pac-Man — tile map, pathfinding-ish enemy behavior, collectibles, mode changes.
- [x] Asteroids — vector movement, rotation, wraparound, projectile/entity lifecycle.
- [ ] Galaga / Galaxian — formation enemies plus attack trajectories.
- [ ] Centipede — segmented enemy motion over a grid.
- [ ] Frogger — lanes, moving obstacles, collision zones.
- [ ] Q*bert — graph/grid traversal with tile state changes.
- [ ] Bomberman — grid world, timers, explosions, propagation.
- [ ] Lode Runner — platform/grid mechanics, AI pursuit.
- [ ] Sokoban — pure deterministic puzzle state, no clock required.
- [ ] Minesweeper — board generation, reveal propagation, flags.
- [ ] Connect Four — tiny discrete board-state machine.
- [ ] Chess / Checkers / Reversi — rule-heavy discrete state.
- [ ] Simon — timed input sequences.
- [ ] Memory / Concentration — card state and delayed transitions.
- [ ] Lunar Lander — continuous physics with thrust and gravity.
- [ ] Missile Command — targeting, explosions, projectile interception.
- [ ] Defender — scrolling world and multiple entity systems.
- [ ] Jetpac — platform movement, pickups, enemies.
- [ ] Prince-of-Persia-like platformer — animation/state-machine heavy.
- [ ] Dr. Mario / Puyo Puyo — falling pieces plus matching/chain reactions.
- [ ] Columns — falling-block variant with match-three rules.
- [ ] Bejeweled-style match-3 — board mutation, cascades, animation.
- [ ] Lights Out — tiny graph/state transformation puzzle.
- [x] Conway's Game of Life — pure clock-driven cellular automaton.
- [ ] Langton's Ant — tiny state machine on a grid.
- [ ] Breakthrough / Othello — compact turn-based board games.
- [x] 2048         → pure state transform 
- [x] Tetris       → grid + gravity

# BATCH 1

Below is a clean model for all four as **event-driven state machines** rendered by raylib.

The common architecture can be:

```text
keyboard events ─┐
mouse events ────┼──> app__handle_event(state, event)
clock events ────┘
                         │
                         ▼
                    application state
                         │
                         ▼
                    app__render(state)
                         │
                         ▼
                       raylib
```

The important point is that **clock events advance simulation**. The render loop itself does not mutate game state.

---

# 1. Arkanoid

## App model

```c
typedef struct vec2_t {
    float x;
    float y;
} vec2_t;

typedef struct paddle_t {
    vec2_t position;
    vec2_t size;
    float speed;
} paddle_t;

typedef struct ball_t {
    vec2_t position;
    vec2_t velocity;
    float radius;
    bool active;
    bool attached_to_paddle;
} ball_t;

typedef enum brick_type_t {
    BRICK_NORMAL,
    BRICK_STRONG,
    BRICK_INDESTRUCTIBLE
} brick_type_t;

typedef struct brick_t {
    vec2_t position;
    vec2_t size;

    brick_type_t type;

    int health;
    bool alive;
} brick_t;

typedef enum powerup_type_t {
    POWERUP_WIDE_PADDLE,
    POWERUP_MULTIBALL,
    POWERUP_SLOW_BALL,
    POWERUP_STICKY
} powerup_type_t;

typedef struct powerup_t {
    vec2_t position;
    vec2_t velocity;

    powerup_type_t type;
    bool active;
} powerup_t;

typedef enum arkanoid_state_t {
    ARKANOID_READY,
    ARKANOID_PLAYING,
    ARKANOID_LEVEL_COMPLETE,
    ARKANOID_GAME_OVER
} arkanoid_state_t;

typedef struct arkanoid_app_t {
    arkanoid_state_t state;

    paddle_t paddle;

    ball_t *balls;
    int ball_count;

    brick_t *bricks;
    int brick_count;

    powerup_t *powerups;
    int powerup_count;

    int level;
    int score;
    int lives;

    float width;
    float height;
} arkanoid_app_t;
```

## Event handling

### Keyboard

```text
LEFT / A
    → paddle moves left

RIGHT / D
    → paddle moves right

SPACE
    → release attached ball

ESC
    → pause/exit
```

Mouse can optionally control the paddle:

```text
MOUSE_MOVE(x)
    → paddle.x = x
```

### Clock

```text
CLOCK(dt)
    │
    ├─ move paddle
    ├─ move balls
    ├─ ball ↔ walls collisions
    ├─ ball ↔ paddle collisions
    ├─ ball ↔ brick collisions
    │       ├─ damage brick
    │       ├─ score
    │       └─ maybe spawn powerup
    ├─ move powerups
    ├─ paddle ↔ powerup collision
    ├─ remove dead balls
    └─ detect level/game completion
```

## Schematic

```text
                      keyboard/mouse
                           │
                           ▼
                      ┌─────────┐
                      │ Paddle  │
                      └────┬────┘
                           │
                           │ collision
                           ▼
┌─────────┐          ┌───────────┐          ┌─────────┐
│  Walls  │◄────────►│   Balls   │◄────────►│ Bricks  │
└─────────┘          └─────┬─────┘          └────┬────┘
                           │                     │
                           │                     ▼
                           │                ┌──────────┐
                           │                │ Powerups │
                           │                └────┬─────┘
                           │                     │
                           └─────────────────────┘
                                   paddle
                                  collision
```

---

# 2. Space Invaders

## App model

```c
typedef struct player_t {
    vec2_t position;
    vec2_t size;

    float speed;

    int lives;
} player_t;

typedef enum alien_type_t {
    ALIEN_SMALL,
    ALIEN_MEDIUM,
    ALIEN_LARGE
} alien_type_t;

typedef struct alien_t {
    vec2_t position;
    vec2_t size;

    alien_type_t type;

    bool alive;
} alien_t;

typedef enum projectile_owner_t {
    PROJECTILE_PLAYER,
    PROJECTILE_ALIEN
} projectile_owner_t;

typedef struct projectile_t {
    vec2_t position;
    vec2_t velocity;

    projectile_owner_t owner;

    bool active;
} projectile_t;

typedef struct shield_cell_t {
    bool occupied;
    int health;
} shield_cell_t;

typedef struct shield_t {
    vec2_t position;

    shield_cell_t cells[16][8];
} shield_t;

typedef enum invaders_state_t {
    INVADERS_READY,
    INVADERS_PLAYING,
    INVADERS_WAVE_COMPLETE,
    INVADERS_GAME_OVER
} invaders_state_t;

typedef struct invaders_app_t {
    invaders_state_t state;

    player_t player;

    alien_t *aliens;
    int alien_count;

    projectile_t *projectiles;
    int projectile_count;

    shield_t *shields;
    int shield_count;

    int formation_direction;
    float formation_speed;

    float alien_fire_timer;

    int wave;
    int score;
} invaders_app_t;
```

## Keyboard

```text
LEFT / A
    → player movement left

RIGHT / D
    → player movement right

SPACE
    → fire projectile
```

Mouse could alternatively map horizontal position directly:

```text
MOUSE_MOVE(x)
    → player.x = x

MOUSE_BUTTON_LEFT
    → fire
```

## Clock

```text
CLOCK(dt)
    │
    ├─ move player
    ├─ update alien formation
    │       │
    │       ├─ move horizontally
    │       └─ on border:
    │              reverse
    │              move downward
    │
    ├─ generate alien shots
    ├─ move all projectiles
    ├─ player shot ↔ alien
    ├─ alien shot ↔ player
    ├─ projectile ↔ shields
    ├─ alien ↔ shields
    ├─ remove dead entities
    ├─ increase alien speed
    └─ check wave/game-over
```

## Schematic

```text
                      clock
                        │
                        ▼
                ┌─────────────────┐
                │ Alien Formation │
                └───────┬─────────┘
                        │
                    alien shots
                        │
                        ▼
                 ┌─────────────┐
                 │ Projectiles │
                 └───┬─────┬───┘
                     │     │
                     ▼     ▼
                ┌───────┐ ┌─────────┐
                │Shield │ │ Player  │
                └───────┘ └────┬────┘
                               │
                        keyboard/mouse
```

A useful distinction here is that the alien formation should probably be modeled as a **single aggregate**, not 40 independent moving aliens:

```c
typedef struct alien_formation_t {
    alien_t aliens[55];

    vec2_t position;
    int direction;

    float speed;
    float step_down;
} alien_formation_t;
```

Then alien coordinates are:

```text
world position =
    formation position
    +
    alien local position
```

Much cleaner.

---

# 3. Tetris

This one is much more discrete than Arkanoid or Invaders.

## App model

```c
#define TETRIS_WIDTH  10
#define TETRIS_HEIGHT 20

typedef enum tetromino_type_t {
    TETROMINO_I,
    TETROMINO_J,
    TETROMINO_L,
    TETROMINO_O,
    TETROMINO_S,
    TETROMINO_T,
    TETROMINO_Z
} tetromino_type_t;

typedef struct tetromino_t {
    tetromino_type_t type;

    int x;
    int y;

    int rotation;
} tetromino_t;

typedef struct tetris_board_t {
    uint8_t cells[TETRIS_HEIGHT][TETRIS_WIDTH];
} tetris_board_t;

typedef enum tetris_state_t {
    TETRIS_PLAYING,
    TETRIS_GAME_OVER
} tetris_state_t;

typedef struct tetris_app_t {
    tetris_state_t state;

    tetris_board_t board;

    tetromino_t active;
    tetromino_type_t next;

    tetromino_type_t hold;
    bool has_hold;
    bool hold_used;

    float fall_accumulator;
    float fall_interval;

    int level;
    int lines;
    int score;
} tetris_app_t;
```

The important modeling decision:

```text
board
+
active falling piece
```

The active piece should **not yet be written into the board**.

Rendering combines them:

```text
render(board)
render(active_piece)
```

Only when the piece locks:

```text
active piece
      ↓
merge into board
      ↓
clear complete rows
      ↓
spawn next piece
```

## Keyboard events

```text
LEFT
    → try x - 1

RIGHT
    → try x + 1

DOWN
    → soft drop

UP / X
    → rotate clockwise

Z
    → rotate counterclockwise

SPACE
    → hard drop

C
    → hold
```

Every operation has the same structure:

```text
proposed state
      ↓
collision test
      ↓
valid?
 ┌────┴────┐
yes       no
 │         │
apply    ignore
```

## Clock

```text
CLOCK(dt)
    │
    ▼
fall_accumulator += dt
    │
    ▼
fall_accumulator >= fall_interval?
       │
      yes
       │
       ▼
try move active down
       │
       ├─ success → continue
       │
       └─ blocked
             │
             ▼
           lock
             │
             ▼
        clear lines
             │
             ▼
        spawn next
```

## Schematic

```text
keyboard
   │
   ▼
┌─────────────────┐
│ Active Tetromino│
└───────┬─────────┘
        │
        │ collision/query
        ▼
 ┌─────────────┐
 │    Board    │
 └──────┬──────┘
        ▲
        │ lock
        │
        └──────── active piece


clock
  │
  └──────────> gravity
```

---

# 4. 2048

This one is the simplest because it has essentially no continuous simulation.

## App model

```c
#define BOARD_SIZE 4

typedef struct game_2048_board_t {
    uint32_t cells[BOARD_SIZE][BOARD_SIZE];
} game_2048_board_t;

typedef enum game_2048_state_t {
    GAME_2048_PLAYING,
    GAME_2048_WON,
    GAME_2048_GAME_OVER
} game_2048_state_t;

typedef struct game_2048_app_t {
    game_2048_state_t state;

    game_2048_board_t board;

    uint64_t score;

    uint32_t random_state;
} game_2048_app_t;
```

A cell contains:

```text
0
2
4
8
16
...
2048
```

or alternatively the exponent:

```text
0 = empty
1 = 2
2 = 4
3 = 8
...
11 = 2048
```

The exponent representation is actually quite elegant:

```c
uint8_t cells[4][4];
```

Then:

```text
displayed value = 1 << cell
```

for nonzero cells.

## Keyboard

```text
LEFT
RIGHT
UP
DOWN
```

Each input produces one atomic transition:

```text
direction
    │
    ▼
extract rows/columns
    │
    ▼
compress
    │
    ▼
merge equal adjacent values
    │
    ▼
compress again
    │
    ▼
did board change?
   │
   ├─ no → nothing
   │
   └─ yes
        │
        ▼
    spawn 2/4
        │
        ▼
    check win/loss
```

Mouse could support swipes:

```text
mouse_down
   ↓
remember start

mouse_up
   ↓
calculate dx/dy
   ↓
dominant axis
   ↓
LEFT / RIGHT / UP / DOWN event
```

## Clock

Strictly speaking, the **logical 2048 game needs no clock**.

Clock events are only needed for presentation:

```text
CLOCK(dt)
    ├─ tile slide animation
    ├─ merge animation
    └─ spawn animation
```

This gives a useful architectural split:

```text
logical board
    │
    ▼
animation state
    │
    ▼
render
```

Do not make the logical game depend on animation completion.

---
# BATCH 2
# Common C+ application abstraction

Since all four use the same event domains, I'd normalize them around something like:

```c
typedef enum app_event_type_t {
    EVENT_KEYBOARD,
    EVENT_MOUSE,
    EVENT_CLOCK
} app_event_type_t;
```

with:

```c
typedef struct keyboard_event_t {
    int key;
    bool pressed;
    bool released;
    bool repeated;
} keyboard_event_t;

typedef struct mouse_event_t {
    float x;
    float y;

    float dx;
    float dy;

    int button;

    bool pressed;
    bool released;
} mouse_event_t;

typedef struct clock_event_t {
    double dt;
    double time;
} clock_event_t;
```

and:

```c
typedef struct app_event_t {
    app_event_type_t type;

    union {
        keyboard_event_t keyboard;
        mouse_event_t mouse;
        clock_event_t clock;
    };
} app_event_t;
```

Each application then conceptually exposes:

```c
app__init(&state);

app__event(&state, &event);

app__render(&state);
```

So the runner is always:

```c
while (!WindowShouldClose()) {

    collect_keyboard_events();
    collect_mouse_events();

    clock_event_t clock = {
        .dt = GetFrameTime(),
        .time = GetTime()
    };

    app__event(&app, &clock_event);

    BeginDrawing();

    app__render(&app);

    EndDrawing();
}
```

The architecture becomes:

```text
                   ┌──────────┐
keyboard ─────────►│          │
                   │          │
mouse ────────────►│   APP    │────► app state
                   │          │
clock ────────────►│          │
                   └────┬─────┘
                        │
                        │ read-only
                        ▼
                   ┌──────────┐
                   │ renderer │
                   └────┬─────┘
                        │
                        ▼
                     raylib
```

And the four games differ primarily in their transition system:

```text
Arkanoid
    continuous physics + collisions

Invaders
    continuous movement + discrete formation behavior + projectiles

Tetris
    discrete grid transitions + clock-driven gravity

2048
    purely discrete board transformations
```

That gives you four quite different workloads while keeping exactly the same C+ application/event/render architecture.


Below are matching app models and schematics for those four, using only `keyboard`, `mouse`, and `clock` events.

# 1. Pong

## App model

```c
typedef struct vec2_t {
    float x;
    float y;
} vec2_t;

typedef struct pong_paddle_t {
    vec2_t position;
    vec2_t size;
    float speed;
} pong_paddle_t;

typedef struct pong_ball_t {
    vec2_t position;
    vec2_t velocity;
    float radius;
} pong_ball_t;

typedef enum pong_state_t {
    PONG_READY,
    PONG_PLAYING,
    PONG_POINT_SCORED,
    PONG_GAME_OVER
} pong_state_t;

typedef struct pong_app_t {
    pong_state_t state;

    pong_paddle_t left;
    pong_paddle_t right;
    pong_ball_t ball;

    int left_score;
    int right_score;
    int winning_score;

    float width;
    float height;
} pong_app_t;
```

## Events

```text
KEYBOARD
  W/S       → left paddle
  UP/DOWN   → right paddle
  SPACE     → serve/restart

MOUSE
  optional:
  mouse.y   → one paddle position

CLOCK(dt)
  → move paddles
  → move ball
  → wall collision
  → paddle collision
  → detect goal
  → update score
  → reset ball
```

## Schematic

```text
 keyboard                     keyboard
    │                            │
    ▼                            ▼
┌────────┐                  ┌────────┐
│ Paddle │◄──── collision ─►│  Ball  │◄──── collision ─► walls
│  Left  │                  └───┬────┘
└────────┘                      │
                                │ collision
                                ▼
                           ┌─────────┐
                           │ Paddle  │
                           │  Right  │
                           └─────────┘

clock ───────────────► motion + collisions
```

Pong is basically:

```text
continuous state
+
collision response
+
score state machine
```

---

# 2. Snake

## App model

```c
typedef struct grid_pos_t {
    int x;
    int y;
} grid_pos_t;

typedef enum snake_direction_t {
    SNAKE_UP,
    SNAKE_DOWN,
    SNAKE_LEFT,
    SNAKE_RIGHT
} snake_direction_t;

typedef struct snake_t {
    grid_pos_t *segments;
    int length;
    int capacity;

    snake_direction_t direction;
    snake_direction_t next_direction;
} snake_t;

typedef enum snake_state_t {
    SNAKE_READY,
    SNAKE_PLAYING,
    SNAKE_GAME_OVER
} snake_state_t;

typedef struct snake_app_t {
    snake_state_t state;

    snake_t snake;
    grid_pos_t food;

    int board_width;
    int board_height;

    float step_interval;
    float step_accumulator;

    int score;
    uint32_t random_state;
} snake_app_t;
```

The important distinction is:

```text
direction
next_direction
```

so multiple keyboard events between two clock ticks do not corrupt movement.

## Events

```text
KEYBOARD
  UP/W     → next_direction = UP
  DOWN/S   → next_direction = DOWN
  LEFT/A   → next_direction = LEFT
  RIGHT/D  → next_direction = RIGHT

  opposite direction is rejected
```

Clock:

```text
CLOCK(dt)
    │
    ▼
accumulator += dt
    │
    ▼
accumulator >= step_interval ?
    │
   yes
    │
    ▼
apply next_direction
    │
    ▼
new_head = head + direction
    │
    ├─ hit wall/self → GAME_OVER
    │
    └─ otherwise
          │
          ├─ food?
          │    ├─ yes → grow + spawn food
          │    └─ no  → remove tail
          │
          ▼
       advance
```

## Schematic

```text
keyboard
   │
   ▼
┌────────────────┐
│ next_direction │
└───────┬────────┘
        │
        │ on clock step
        ▼
   ┌─────────┐
   │  Snake  │
   └────┬────┘
        │
        ├────► wall collision
        │
        ├────► self collision
        │
        └────► food collision
                   │
                   ▼
                growth
```

Snake is:

```text
discrete spatial state
+
periodic clock transition
```

---

# 3. Asteroids

This is the best of the four for testing a more general entity model.

## App model

```c
typedef struct asteroid_transform_t {
    vec2_t position;
    float rotation;
} asteroid_transform_t;

typedef struct asteroid_motion_t {
    vec2_t velocity;
    float angular_velocity;
} asteroid_motion_t;

typedef enum asteroid_entity_type_t {
    ENTITY_SHIP,
    ENTITY_ASTEROID,
    ENTITY_BULLET
} asteroid_entity_type_t;

typedef struct asteroid_entity_t {
    uint32_t id;

    asteroid_entity_type_t type;

    asteroid_transform_t transform;
    asteroid_motion_t motion;

    float radius;

    bool alive;
} asteroid_entity_t;

typedef struct ship_t {
    uint32_t entity_id;

    float thrust;
    float turn_speed;

    float fire_cooldown;
    float fire_timer;

    int lives;
} ship_t;

typedef enum asteroids_state_t {
    ASTEROIDS_READY,
    ASTEROIDS_PLAYING,
    ASTEROIDS_GAME_OVER
} asteroids_state_t;

typedef struct asteroids_app_t {
    asteroids_state_t state;

    asteroid_entity_t *entities;
    int entity_count;
    int entity_capacity;

    ship_t ship;

    float width;
    float height;

    int score;
    int wave;

    uint32_t next_entity_id;
    uint32_t random_state;
} asteroids_app_t;
```

You could push this farther toward ECS:

```c
entity_id
    │
    ├─ transform component
    ├─ velocity component
    ├─ collider component
    ├─ render component
    └─ lifetime component
```

but for a small example, a tagged entity struct is probably enough.

## Events

Keyboard:

```text
LEFT/A
  → rotate ship left

RIGHT/D
  → rotate ship right

UP/W
  → thrust

SPACE
  → fire

R
  → restart
```

Mouse could optionally provide aiming:

```text
mouse position
  → desired ship angle

mouse left
  → fire
```

Clock:

```text
CLOCK(dt)
    │
    ├─ rotate ship
    ├─ apply thrust
    ├─ integrate velocity
    ├─ move every entity
    ├─ world wraparound
    ├─ update bullet lifetime
    ├─ bullet ↔ asteroid collision
    │       ├─ destroy bullet
    │       ├─ destroy asteroid
    │       ├─ spawn smaller asteroids
    │       └─ add score
    ├─ ship ↔ asteroid collision
    ├─ remove dead entities
    └─ spawn next wave
```

## Schematic

```text
                   keyboard/mouse
                         │
                         ▼
                      ┌──────┐
                      │ Ship │
                      └──┬───┘
                         │
                    bullets
                         │
                         ▼
              ┌──────────────────┐
              │    Entity Pool   │
              │                  │
              │ ship             │
              │ bullets          │
              │ asteroids        │
              └────────┬─────────┘
                       │
                       ▼
               movement/integration
                       │
                       ▼
                  collisions
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
        destruction          spawning
```

Asteroids is:

```text
dynamic entities
+
continuous integration
+
entity creation/destruction
+
collision graph
```

---

# 4. Conway's Game of Life

This one is especially clean because the logical application can be driven almost entirely by clock events.

## App model

```c
typedef enum life_state_t {
    LIFE_PAUSED,
    LIFE_RUNNING
} life_state_t;

typedef struct life_board_t {
    int width;
    int height;

    uint8_t *cells;
    uint8_t *next_cells;
} life_board_t;

typedef struct life_app_t {
    life_state_t state;

    life_board_t board;

    float step_interval;
    float step_accumulator;

    uint64_t generation;

    bool wrap_edges;
} life_app_t;
```

You want two buffers:

```text
current generation
next generation
```

because all cells must observe the same previous state.

## Events

Keyboard:

```text
SPACE
  → toggle run/pause

N
  → advance one generation

C
  → clear board

R
  → randomize board

+ / -
  → change simulation rate
```

Mouse:

```text
LEFT CLICK / DRAG
  → set cell alive

RIGHT CLICK / DRAG
  → set cell dead
```

Clock:

```text
CLOCK(dt)
    │
    ▼
if running:
    accumulator += dt
        │
        ▼
    enough for step?
        │
       yes
        │
        ▼
    for every cell:
        count 8 neighbors
        │
        ├─ alive + 2/3 neighbors → alive
        ├─ alive + otherwise     → dead
        ├─ dead + 3 neighbors    → alive
        └─ otherwise             → dead
        │
        ▼
    swap buffers
        │
        ▼
    generation++
```

## Schematic

```text
               clock
                 │
                 ▼
        ┌─────────────────┐
        │ current board   │
        └────────┬────────┘
                 │
         neighbor evaluation
                 │
                 ▼
        ┌─────────────────┐
        │ next board      │
        └────────┬────────┘
                 │
              swap
                 │
                 └──────────► current board


mouse ───────────────► edit cells

keyboard ────────────► pause/run/step/reset
```

Mathematically:

```text
B(t + 1) = F(B(t))
```

where `F` is the Life transition function.

Unlike the other games:

```text
no player entity
no physics
no collision system
no mandatory keyboard input
```

The clock alone can drive the entire application indefinitely.

---

# Combined spectrum

These four are a very good progression:

```text
Pong
  │
  └─ continuous state
     continuous collision physics

Snake
  │
  └─ discrete state
     periodic clock transitions

Asteroids
  │
  └─ continuous state
     dynamic entity population
     creation/destruction

Game of Life
  │
  └─ cellular state
     global synchronous transformation
```

All eight games you now have form an interesting little application-model test set:

```text
Arkanoid       continuous physics + many static colliders
Pong           minimal continuous physics
Space Invaders entity groups + projectiles
Asteroids      dynamic entities + continuous physics
Snake          discrete clocked movement
Tetris         discrete grid + gravity
2048           event-driven state transformation
Game of Life   autonomous clock-driven transformation
```

That is a broad enough set to expose whether your C+ event/application model is actually general rather than accidentally tailored to one style of game.

# BATCH 3

Below I’d model them in the same C+ style already used by your raylib examples: methods inside the app struct, `pub`/`static pub`, receiver as `borrowed mut *self`, event dispatch through `event()`, mutation only from events, and `render()` reading app state.

The common event shape remains:

```c
typedef enum app_event_type_t {
    APP_EVENT_KEYBOARD,
    APP_EVENT_MOUSE,
    APP_EVENT_CLOCK
} app_event_type_t;
```

---

# Frogger

Frogger is mostly **lane simulation + player grid movement + moving carriers/hazards**.

```c
#define FROGGER_MAX_LANES 16
#define FROGGER_MAX_OBJECTS 96

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

typedef enum frogger_object_type_t {
    FROGGER_CAR,
    FROGGER_TRUCK,
    FROGGER_LOG,
    FROGGER_TURTLE
} frogger_object_type_t;

typedef struct frogger_object_t {
    frogger_object_type_t type;
    Rectangle bounds;
    float velocity_x;
    bool active;
} frogger_object_t;

typedef enum frogger_lane_type_t {
    FROGGER_LANE_SAFE,
    FROGGER_LANE_ROAD,
    FROGGER_LANE_WATER,
    FROGGER_LANE_GOAL
} frogger_lane_type_t;

typedef struct frogger_lane_t {
    frogger_lane_type_t type;
    float y;
} frogger_lane_t;

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
    int lane_count;

    frogger_object_t objects[FROGGER_MAX_OBJECTS];

    int score;
    int level;
    float remaining_time;

    pub void init(borrowed mut *self) {
        /* initialise lanes, objects and player */
    }

    pub void move_player(borrowed mut *self, int dx, int dy) {
        /* discrete grid hop */
    }

    pub void clock(borrowed mut *self, const frogger_event_t* event) {
        float dt = event->delta_seconds;

        /*
         * move cars/logs/turtles
         * wrap lane objects
         * determine current lane
         * road collision
         * water support
         * carry frog with log
         * timer
         */
    }

    pub void event(borrowed mut *self, const frogger_event_t* event) {
        if (event->type == FROGGER_EVENT_KEYBOARD) {
            if (event->key == KEY_LEFT)  frogger_app_t.move_player(self, -1, 0);
            if (event->key == KEY_RIGHT) frogger_app_t.move_player(self,  1, 0);
            if (event->key == KEY_UP)    frogger_app_t.move_player(self,  0,-1);
            if (event->key == KEY_DOWN)  frogger_app_t.move_player(self,  0, 1);
        } else if (event->type == FROGGER_EVENT_CLOCK) {
            frogger_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
        /* raylib drawing */
    }
} frogger_app_t;
```

The interesting bit is that road and water lanes have opposite semantics:

```text
ROAD:
player ∩ vehicle
        │
        ▼
       DEAD


WATER:
player ∩ carrier ?
      │
   ┌──┴───┐
  yes     no
   │       │
carried   DEAD
```

Full schematic:

```text
keyboard
   │
   ▼
┌─────────┐
│ Frog    │
└────┬────┘
     │
     ▼
 determine lane
     │
 ┌───┼───────────────┐
 ▼   ▼               ▼
safe road           water
     │                │
     ▼                ▼
 vehicles         logs/turtles
     │                │
collision?        supported?
     │                │
     ▼                ▼
 death          move with carrier

clock
  │
  ├─ move lane objects
  ├─ wrap objects
  ├─ carry frog
  └─ decrement timer
```

---

# Connect Four

This is almost purely a **deterministic discrete state transition system**.

```c
#define CONNECT4_COLUMNS 7
#define CONNECT4_ROWS 6

typedef enum connect4_event_type_t {
    CONNECT4_EVENT_KEYBOARD,
    CONNECT4_EVENT_MOUSE,
    CONNECT4_EVENT_CLOCK
} connect4_event_type_t;

typedef enum connect4_cell_t {
    CONNECT4_EMPTY,
    CONNECT4_RED,
    CONNECT4_YELLOW
} connect4_cell_t;

typedef enum connect4_state_t {
    CONNECT4_PLAYING,
    CONNECT4_WON,
    CONNECT4_DRAW
} connect4_state_t;

typedef struct connect4_event_t {
    connect4_event_type_t type;

    int key;

    float mouse_x;
    float mouse_y;
    bool mouse_pressed;

    float delta_seconds;
} connect4_event_t;

typedef struct connect4_app_t {
    connect4_state_t state;

    connect4_cell_t board[CONNECT4_ROWS][CONNECT4_COLUMNS];

    connect4_cell_t current_player;
    connect4_cell_t winner;

    int selected_column;
    int move_count;

    pub void init(borrowed mut *self) {
        memset(self, 0, sizeof(*self));

        self->state = CONNECT4_PLAYING;
        self->current_player = CONNECT4_RED;
        self->selected_column = 3;
    }

    pub int find_free_row(borrowed const *self, int column) {
        for (int row = CONNECT4_ROWS - 1; row >= 0; row--) {
            if (self->board[row][column] == CONNECT4_EMPTY)
                return row;
        }

        return -1;
    }

    pub bool has_four(
        borrowed const *self,
        int row,
        int column,
        int dx,
        int dy
    ) {
        /* count same-colored cells in both directions */
    }

    pub bool check_win(
        borrowed const *self,
        int row,
        int column
    ) {
        return
            connect4_app_t.has_four(self, row, column, 1, 0) ||
            connect4_app_t.has_four(self, row, column, 0, 1) ||
            connect4_app_t.has_four(self, row, column, 1, 1) ||
            connect4_app_t.has_four(self, row, column, 1,-1);
    }

    pub void drop(borrowed mut *self, int column) {
        if (self->state != CONNECT4_PLAYING)
            return;

        int row = connect4_app_t.find_free_row(self, column);
        if (row < 0)
            return;

        self->board[row][column] = self->current_player;
        self->move_count++;

        if (connect4_app_t.check_win(self, row, column)) {
            self->winner = self->current_player;
            self->state = CONNECT4_WON;
            return;
        }

        if (self->move_count == CONNECT4_ROWS * CONNECT4_COLUMNS) {
            self->state = CONNECT4_DRAW;
            return;
        }

        self->current_player =
            self->current_player == CONNECT4_RED
                ? CONNECT4_YELLOW
                : CONNECT4_RED;
    }

    pub void event(borrowed mut *self, const connect4_event_t* event) {
        /* arrows/mouse choose column, SPACE/click drops */
    }

    pub void render(borrowed const *self) {
        /* board + discs */
    }
} connect4_app_t;
```

Schematic:

```text
keyboard/mouse
      │
      ▼
select column
      │
      ▼
     DROP
      │
      ▼
find lowest empty cell
      │
      ▼
place token
      │
      ▼
check 4 axes

 horizontal ─────
 vertical   │
 diagonal   ╲
 diagonal   ╱
      │
      ├─ four found ───► WON
      │
      ├─ board full ───► DRAW
      │
      └─ otherwise ────► switch player
```

The clock is unnecessary logically. You could use it only for drop animations.

---

# Jetpac

Jetpac is a good combination of **platform physics, free-flight movement, pickups and dynamic enemies**.

```c
#define JETPAC_MAX_ENEMIES 48
#define JETPAC_MAX_PROJECTILES 32
#define JETPAC_MAX_ITEMS 16
#define JETPAC_MAX_PLATFORMS 12

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

    bool grounded;

    bool carrying;
    int carried_item;

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

    int level;
    int score;

    uint32_t random_state;

    pub void fire(borrowed mut *self) {
        /* allocate projectile slot */
    }

    pub void pickup(borrowed mut *self, int item_index) {
        /* attach item logically to player */
    }

    pub void deliver_item(borrowed mut *self) {
        /*
         * rocket part -> assembly
         * fuel        -> rocket fuel
         * treasure    -> score
         */
    }

    pub void clock(borrowed mut *self, const jetpac_event_t* event) {
        float dt = event->delta_seconds;

        /*
         * horizontal acceleration
         * jetpack thrust
         * gravity
         * player integration
         * platform collision
         *
         * enemies
         * bullets
         * pickups
         * delivery to rocket
         * enemy/player collision
         */
    }

    pub void event(borrowed mut *self, const jetpac_event_t* event) {
        if (event->type == JETPAC_EVENT_CLOCK)
            jetpac_app_t.clock(self, event);
    }

    pub void render(borrowed const *self) {
    }
} jetpac_app_t;
```

Conceptually:

```text
                     keyboard
                        │
          ┌─────────────┼────────────┐
          ▼             ▼            ▼
       movement       thrust        fire
          │             │            │
          └──────┬──────┘            ▼
                 ▼               projectiles
              player                 │
                 │                   ▼
       ┌─────────┼─────────┐       enemies
       ▼         ▼         ▼
   platforms   items     enemies
                 │
               pickup
                 │
                 ▼
             carried item
                 │
                 ▼
               rocket
          ┌──────┴──────┐
          ▼             ▼
       assembly        fuel
```

Its level loop is particularly nice:

```text
assemble rocket
      ↓
collect fuel
      ↓
rocket full
      ↓
launch
      ↓
next level
```

---

# Columns

Columns resembles Tetris structurally, but its main operation is **match detection and cascades**, not line completion.

```c
#define COLUMNS_WIDTH 6
#define COLUMNS_HEIGHT 13

typedef enum columns_event_type_t {
    COLUMNS_EVENT_KEYBOARD,
    COLUMNS_EVENT_MOUSE,
    COLUMNS_EVENT_CLOCK
} columns_event_type_t;

typedef enum columns_gem_t {
    COLUMNS_EMPTY,
    COLUMNS_RED,
    COLUMNS_GREEN,
    COLUMNS_BLUE,
    COLUMNS_YELLOW,
    COLUMNS_PURPLE,
    COLUMNS_CYAN
} columns_gem_t;

typedef enum columns_state_t {
    COLUMNS_PLAYING,
    COLUMNS_CLEARING,
    COLUMNS_GAME_OVER
} columns_state_t;

typedef struct columns_piece_t {
    int x;
    int y;

    columns_gem_t gems[3];
} columns_piece_t;

typedef struct columns_event_t {
    columns_event_type_t type;

    int key;
    float delta_seconds;
} columns_event_t;

typedef struct columns_app_t {
    columns_state_t state;

    columns_gem_t board[COLUMNS_HEIGHT][COLUMNS_WIDTH];

    columns_piece_t active;
    columns_piece_t next;

    float fall_accumulator;
    float fall_interval;

    int score;
    int level;

    uint32_t random_state;

    pub void rotate_piece(borrowed mut *self) {
        columns_gem_t bottom = self->active.gems[2];

        self->active.gems[2] = self->active.gems[1];
        self->active.gems[1] = self->active.gems[0];
        self->active.gems[0] = bottom;
    }

    pub bool can_move(
        borrowed const *self,
        int dx,
        int dy
    ) {
        /* test all 3 vertical gems */
    }

    pub void lock_piece(borrowed mut *self) {
        /* merge active column into board */
    }

    pub bool mark_matches(borrowed mut *self) {
        /*
         * horizontal
         * vertical
         * diagonal down-right
         * diagonal down-left
         */
    }

    pub void collapse(borrowed mut *self) {
        /* gravity each board column independently */
    }

    pub void resolve(borrowed mut *self) {
        while (columns_app_t.mark_matches(self)) {
            /* remove marked gems */
            columns_app_t.collapse(self);
        }
    }

    pub void clock(borrowed mut *self, const columns_event_t* event) {
        self->fall_accumulator += event->delta_seconds;

        if (self->fall_accumulator < self->fall_interval)
            return;

        self->fall_accumulator -= self->fall_interval;

        if (columns_app_t.can_move(self, 0, 1)) {
            self->active.y++;
            return;
        }

        columns_app_t.lock_piece(self);
        columns_app_t.resolve(self);

        /* spawn next */
    }

    pub void event(borrowed mut *self, const columns_event_t* event) {
        if (event->type == COLUMNS_EVENT_KEYBOARD) {
            /* left/right/down/rotate */
        } else if (event->type == COLUMNS_EVENT_CLOCK) {
            columns_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
    }
} columns_app_t;
```

The core difference from Tetris:

```text
falling column
     │
     ▼
    lock
     │
     ▼
┌───────────────┐
│ search runs   │
│ length >= 3   │
└──────┬────────┘
       │
       ▼
   remove gems
       │
       ▼
     gravity
       │
       ▼
new matches?
   │       │
  yes      no
   │       │
   └───────┘
   cascade
```

Match directions:

```text
───── horizontal
  │   vertical
  ╲   diagonal
  ╱   diagonal
```

So the application is:

```text
active falling state
        +
persistent board
        +
cascade resolver
```

---

# Langton's Ant

This one is extremely compact and fits your event model particularly well.

The logical model is:

```text
board + ant position + ant direction
```

Each clock step performs exactly:

```text
read cell
   ↓
turn
   ↓
flip cell
   ↓
move forward
```

C+ model:

```c
#define ANT_BOARD_WIDTH 160
#define ANT_BOARD_HEIGHT 100

typedef enum ant_event_type_t {
    ANT_EVENT_KEYBOARD,
    ANT_EVENT_MOUSE,
    ANT_EVENT_CLOCK
} ant_event_type_t;

typedef enum ant_direction_t {
    ANT_UP,
    ANT_RIGHT,
    ANT_DOWN,
    ANT_LEFT
} ant_direction_t;

typedef enum ant_state_t {
    ANT_PAUSED,
    ANT_RUNNING
} ant_state_t;

typedef struct ant_event_t {
    ant_event_type_t type;

    int key;

    int mouse_x;
    int mouse_y;
    bool mouse_pressed;

    float delta_seconds;
} ant_event_t;

typedef struct langton_app_t {
    ant_state_t state;

    bool cells[ANT_BOARD_HEIGHT][ANT_BOARD_WIDTH];

    int ant_x;
    int ant_y;
    ant_direction_t direction;

    uint64_t generation;

    float step_interval;
    float accumulator;

    bool wrap_edges;

    pub void reset(borrowed mut *self) {
        memset(self->cells, 0, sizeof(self->cells));

        self->ant_x = ANT_BOARD_WIDTH / 2;
        self->ant_y = ANT_BOARD_HEIGHT / 2;
        self->direction = ANT_UP;

        self->generation = 0;
    }

    pub void turn_left(borrowed mut *self) {
        self->direction =
            (ant_direction_t)((self->direction + 3) % 4);
    }

    pub void turn_right(borrowed mut *self) {
        self->direction =
            (ant_direction_t)((self->direction + 1) % 4);
    }

    pub void move_forward(borrowed mut *self) {
        switch (self->direction) {
            case ANT_UP:    self->ant_y--; break;
            case ANT_RIGHT: self->ant_x++; break;
            case ANT_DOWN:  self->ant_y++; break;
            case ANT_LEFT:  self->ant_x--; break;
        }

        if (self->wrap_edges) {
            if (self->ant_x < 0)
                self->ant_x = ANT_BOARD_WIDTH - 1;

            if (self->ant_x >= ANT_BOARD_WIDTH)
                self->ant_x = 0;

            if (self->ant_y < 0)
                self->ant_y = ANT_BOARD_HEIGHT - 1;

            if (self->ant_y >= ANT_BOARD_HEIGHT)
                self->ant_y = 0;
        }
    }

    pub void step(borrowed mut *self) {
        bool *cell = &self->cells[self->ant_y][self->ant_x];

        if (*cell)
            langton_app_t.turn_left(self);
        else
            langton_app_t.turn_right(self);

        *cell = !*cell;

        langton_app_t.move_forward(self);

        self->generation++;
    }

    pub void clock(borrowed mut *self, const ant_event_t* event) {
        if (self->state != ANT_RUNNING)
            return;

        self->accumulator += event->delta_seconds;

        while (self->accumulator >= self->step_interval) {
            self->accumulator -= self->step_interval;
            langton_app_t.step(self);
        }
    }

    pub void event(borrowed mut *self, const ant_event_t* event) {
        if (event->type == ANT_EVENT_KEYBOARD) {
            if (event->key == KEY_SPACE)
                self->state =
                    self->state == ANT_RUNNING
                        ? ANT_PAUSED
                        : ANT_RUNNING;

            if (event->key == KEY_N && self->state == ANT_PAUSED)
                langton_app_t.step(self);

            if (event->key == KEY_R)
                langton_app_t.reset(self);

        } else if (event->type == ANT_EVENT_CLOCK) {
            langton_app_t.clock(self, event);
        }
    }

    pub void render(borrowed const *self) {
    }
} langton_app_t;
```

Schematic:

```text
                      clock
                        │
                        ▼
                   ┌────────┐
                   │  cell  │
                   └───┬────┘
                       │
             ┌─────────┴─────────┐
             │                   │
          white                black
             │                   │
        turn right           turn left
             │                   │
             └─────────┬─────────┘
                       ▼
                    flip cell
                       │
                       ▼
                  move forward
                       │
                       ▼
                generation++
```

Or mathematically:

```text
(cell, x, y, direction)
          │
          ▼
          F
          │
          ▼
(cell', x', y', direction')
```

One clock step is one complete deterministic state transition.

---

These five extend your current set in useful directions:

| Game              | Primary model                            |
| ----------------- | ---------------------------------------- |
| **Frogger**       | lanes + moving hazards/carriers          |
| **Connect Four**  | turn-based deterministic board           |
| **Jetpac**        | physics + pickups + objectives + enemies |
| **Columns**       | falling state + board cascades           |
| **Langton's Ant** | tiny deterministic cellular agent        |

Combined with what you already have:

```text
Pong             continuous collision
Arkanoid         continuous physics + static field
Asteroids        dynamic entities
Space Invaders   formation + projectiles
Jetpac           platform physics + objectives

Snake            discrete clocked movement
Frogger          discrete player + continuous environment

Tetris           falling-piece grid
Columns          falling-piece + cascade grid
2048             atomic board transformation
Connect Four     turn-based board

Game of Life     synchronous cellular automaton
Langton's Ant    asynchronous single-agent automaton
```

That is becoming a particularly good **C+ raylib example suite**, because each game exercises a materially different application-state topology rather than being five cosmetic variations of the same game loop.

