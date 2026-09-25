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


