# Raylib game examples

These eight programs implement the games described in [`games.md`](games.md) and [`games2.md`](games2.md). Each `.cp` file owns its entities, events, update rules, renderer, and `main`; methods that operate on an app are declared inside that app's struct. The examples intentionally do not share a game-state framework.

| Example | Controls |
| --- | --- |
| [`arkanoid.cp`](arkanoid.cp) | Left/right or A/D; mouse drag; Space to launch; M toggles mouse control. Bricks, lives, levels, and powerups. |
| [`space_invaders.cp`](space_invaders.cp) | Left/right or A/D; Space to start/fire. Formation movement, enemy fire, shields, lives, and waves. |
| [`tetris.cp`](tetris.cp) | Left/right, down, Up/X rotate, Z reverse-rotate, Space hard drop, C hold. Line clears, levels, previews, and landing ghost. |
| [`game_2048.cp`](game_2048.cp) | Arrow keys/WASD or mouse swipe; R restarts. 2/4 tile spawns, score, win, and no-moves states. |
| [`pong.cp`](pong.cp) | W/S and Up/Down move paddles; hold left mouse to steer the right paddle; Space serves/restarts; R resets. |
| [`snake.cp`](snake.cp) | Arrow keys/WASD steer; Space starts/pauses; R restarts. Fixed-step movement, queued turns, growth, and collisions. |
| [`asteroids.cp`](asteroids.cp) | Left/right or A/D rotate; Up/W thrust; hold Space to fire; R resets; Enter restarts. Wraparound, splitting, waves, lives. |
| [`game_of_life.cp`](game_of_life.cp) | Space runs/pauses; N steps; R randomizes; C clears; B toggles wrapping; +/- adjusts speed. Mouse paints/erases. |

## Tests and screenshots

Every source contains an idiomatic `@test` fixture. The fixtures initialize deterministic game states and use `@assert` for model behavior; they do not add command-line self-test branches to the game programs.

```sh
cpc test stdlib/examples/raylib/arkanoid.cp \
  stdlib/examples/raylib/space_invaders.cp \
  stdlib/examples/raylib/tetris.cp \
  stdlib/examples/raylib/game_2048.cp \
  stdlib/examples/raylib/pong.cp \
  stdlib/examples/raylib/snake.cp \
  stdlib/examples/raylib/asteroids.cp \
  stdlib/examples/raylib/game_of_life.cp
```

Fixtures normally test game logic without opening a window. To also render each fixture's final state and save PNGs, set `CPLUS_TEST_SCREENSHOT_DIR` to an existing directory:

```sh
mkdir -p /tmp/cplus-raylib-shots
CPLUS_TEST_SCREENSHOT_DIR=/tmp/cplus-raylib-shots xvfb-run -a cpc test \
  stdlib/examples/raylib/arkanoid.cp \
  stdlib/examples/raylib/space_invaders.cp \
  stdlib/examples/raylib/tetris.cp \
  stdlib/examples/raylib/game_2048.cp \
  stdlib/examples/raylib/pong.cp \
  stdlib/examples/raylib/snake.cp \
  stdlib/examples/raylib/asteroids.cp \
  stdlib/examples/raylib/game_of_life.cp
```

The screenshot branch creates a hidden Raylib window, invokes the same struct `render` method used by the game, reads its framebuffer with `LoadImageFromScreen()`, and exports one PNG per fixture. The bundled Linux header declares Raylib 6.0, but its `libraylib.a` currently initializes as Raylib 5.0 and uses the GLFW desktop backend. These screenshots therefore use `xvfb-run`; a true no-display run requires rebuilding/updating the TinyCC payload with Raylib 6's memory platform backend.

## Build and run

The `.cp` sources select platform link flags with `@if (os == ...)` and `comptime flags`; `cpc compile` and `cpc run` consume them automatically. To build directly on Linux with a host Raylib development package:

```sh
cpc transcode stdlib/examples/raylib/tetris.cp -o build/tetris.c
cc build/tetris.c -o build/tetris -lraylib -lGL -lm -lpthread -ldl -lrt \
  -lX11 -lXrandr -lXinerama -lXcursor -lXi
build/tetris
```

Use the corresponding source/output names for another game. Non-Linux direct builds need the platform's native Raylib development package and link options. The embedded TinyCC payload is useful for compiling against its Raylib archive, but its Linux desktop executable still depends on a compatible graphics runtime; the bundled sysroot is not a self-contained desktop runtime. In particular, `-static` cannot satisfy `-lGL` when only `libGL.so` is available.
