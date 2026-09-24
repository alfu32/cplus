# Raylib game examples

These four programs implement the game models described in [`games.md`](games.md). Each game is self-contained: its board/entities, events, update rules, renderer, and `main` live in its own `.cp` file. They intentionally do not share a game-state or entity framework.

Each loop drains Raylib's queued keyboard presses with `GetKeyPressed()`, samples mouse state when that game uses it, then sends a `CLOCK` event with `GetFrameTime()`. State changes happen in event/update handlers; rendering only reads state. Run a compiled program with `--self-test` to run deterministic logic checks without opening a window.

| Example | Controls |
| --- | --- |
| [`arkanoid.cp`](arkanoid.cp) | Left/right or A/D; mouse drag; Space to launch; M toggles mouse control. Breakable, reinforced, and unbreakable bricks; lives, levels, and wide/multiball/slow/sticky powerups. |
| [`space_invaders.cp`](space_invaders.cp) | Left/right or A/D; Space to start/fire. Formation reversals, enemy fire, destructible shields, lives, score, and waves. |
| [`tetris.cp`](tetris.cp) | Left/right, down, Up/X rotate, Z reverse-rotate, Space hard drop, C hold. Includes line clears, levels, next/hold previews, and a landing ghost. |
| [`game_2048.cp`](game_2048.cp) | Arrow keys/WASD or mouse swipe; R restarts. Includes 2/4 tile spawns, score, win, and no-moves states. |

For a directly runnable Linux desktop executable, transcode to C and link with the host C compiler and host Raylib development package (the link flags below are for Linux):

The `.cp` examples select Linux, Windows, or macOS link dependencies with `@if (os == ...)` and `comptime flags`; `cpc compile` and `cpc run` consume the matching branch automatically. The generated C displays the selected flags in a comment for manual builds. The direct `cc` command below is specifically for Linux.

```sh
cpc transcode stdlib/examples/raylib/tetris.cp -o build/tetris.c
cc build/tetris.c -o build/tetris -lraylib -lGL -lm -lpthread -ldl -lrt \
  -lX11 -lXrandr -lXinerama -lXcursor -lXi
build/tetris
build/tetris --self-test
```

Use the corresponding source/output names for the other games. A windowing session is required to play; `--self-test` is headless. This route needs Raylib headers and libraries installed for the host C compiler. The embedded Linux TinyCC payload can compile against its Raylib archive, but its dynamic output uses the bundled musl loader and still depends on a compatible graphics runtime; the sysroot is not a self-contained desktop runtime. In particular, `-static` cannot satisfy `-lGL` because the payload provides `libGL.so`, not `libGL.a`. Non-Linux hosts need their native Raylib link options and host compiler.

Each `--self-test` runs deterministic rules checks before any window initialization. The checks cover Arkanoid collision/damage, Invaders movement/fire/hits, Tetris hold/placement/line clearing, and 2048 compression/merging/spawn behavior. The implementation order follows `games.md`: Arkanoid, Space Invaders, Tetris, then 2048.
