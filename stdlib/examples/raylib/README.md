# Raylib game examples

These four programs implement the game models described in [`games.md`](games.md). Each game is self-contained: its board/entities, events, update rules, renderer, and `main` live in its own `.cp` file. They intentionally do not share a game-state or entity framework.

Each loop drains Raylib's queued keyboard presses with `GetKeyPressed()`, samples mouse state when that game uses it, then sends a `CLOCK` event with `GetFrameTime()`. State changes happen in event/update handlers; rendering only reads state. Run a compiled program with `--self-test` to run deterministic logic checks without opening a window.

| Example | Controls |
| --- | --- |
| [`arkanoid.cp`](arkanoid.cp) | Left/right or A/D; mouse drag; Space to launch; M toggles mouse control. Breakable, reinforced, and unbreakable bricks; lives, levels, and wide/multiball/slow/sticky powerups. |
| [`space_invaders.cp`](space_invaders.cp) | Left/right or A/D; Space to start/fire. Formation reversals, enemy fire, destructible shields, lives, score, and waves. |
| [`tetris.cp`](tetris.cp) | Left/right, down, Up/X rotate, Z reverse-rotate, Space hard drop, C hold. Includes line clears, levels, next/hold previews, and a landing ghost. |
| [`game_2048.cp`](game_2048.cp) | Arrow keys/WASD or mouse swipe; R restarts. Includes 2/4 tile spawns, score, win, and no-moves states. |

Build and run one game from the repository root (the link flags below are for Linux):

```sh
./gradlew -Ptarget=linux-x86_64 :cli:fatJar
java -jar cli/build/libs/c-plus.jar compile stdlib/examples/raylib/tetris.cp \
  -o build/tetris -dynamic -lraylib -lGL -lm -lpthread -ldl -lrt \
  -lX11 -lXrandr -lXinerama -lXcursor -lXi
build/tetris
build/tetris --self-test
```

Use the corresponding source/output names for the other games. A windowing session is required to play; `--self-test` is headless. Raylib and platform link dependencies are selected for the compiler target, so non-Linux targets need their native Raylib link options instead of the Linux list above.

Each `--self-test` runs deterministic rules checks before any window initialization. The checks cover Arkanoid collision/damage, Invaders movement/fire/hits, Tetris hold/placement/line clearing, and 2048 compression/merging/spawn behavior. The implementation order follows `games.md`: Arkanoid, Space Invaders, Tetris, then 2048.
