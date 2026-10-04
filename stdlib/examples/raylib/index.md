Based on the current `raylib.examples.plan.md` backlog and the implementations completed afterward, this is the follow-up. The original plan lists the broader game suite and its initial completion state. raylib-examples.plan

| Game | Specification status | Implementation status |
|---|---|---|
| Pong | ✅ Complete | ✅ Complete |
| Arkanoid | ✅ Complete | ✅ Complete |
| Space Invaders | ✅ Complete | ✅ Complete |
| Breakout | ⚠️ Basic / derived from Arkanoid | ✅ Complete |
| Snake | ✅ Complete | ✅ Complete |
| Pac-Man | 📝 Backlog description only | ⬜ Not implemented |
| Asteroids | ✅ Complete | ✅ Complete |
| Galaga / Galaxian | 📝 Backlog description only | ⬜ Not implemented |
| Centipede | 📝 Backlog description only | ⬜ Not implemented |
| Frogger | ✅ Complete | ✅ Complete |
| Q*bert | ✅ Complete | ✅ Complete |
| Bomberman | ✅ Complete | ✅ Complete |
| Lode Runner | ✅ Complete | ✅ Complete |
| Sokoban | 📝 Backlog description only | ⬜ Not implemented |
| Minesweeper | 📝 Backlog description only | ⬜ Not implemented |
| Connect Four | ✅ Complete | ⬜ Not implemented |
| Chess / Checkers / Reversi | 📝 Backlog description only | ⬜ Not implemented |
| Simon | 📝 Backlog description only | ⬜ Not implemented |
| Memory / Concentration | 📝 Backlog description only | ⬜ Not implemented |
| Lunar Lander | 📝 Backlog description only | ⬜ Not implemented |
| Missile Command | 📝 Backlog description only | ⬜ Not implemented |
| Defender | 📝 Backlog description only | ⬜ Not implemented |
| Jetpac | ✅ Complete | ✅ Complete |
| Prince-of-Persia-like | 📝 Backlog description only | ⬜ Not implemented |
| Dr. Mario / Puyo Puyo | 📝 Backlog description only | ⬜ Not implemented |
| Columns | ✅ Complete | ⬜ Not implemented |
| Bejeweled-style Match-3 | 📝 Backlog description only | ⬜ Not implemented |
| Lights Out | 📝 Backlog description only | ⬜ Not implemented |
| Conway's Game of Life | ✅ Complete | ✅ Complete |
| Langton's Ant | ✅ Complete | ✅ Complete |
| Breakthrough / Othello | 📝 Backlog description only | ⬜ Not implemented |
| 2048 | ✅ Complete | ✅ Complete |
| Tetris | ✅ Complete | ✅ Complete |
| **DOOM-like raycaster** | ✅ Complete + extended | ✅ Complete + extended |

The detailed specification already covers the common C+ event/state/render architecture and gives concrete models for the original core examples. raylib-examples.plan Frogger, Jetpac, Connect Four, Columns and Langton's Ant were subsequently specified in detail as additional application-state topologies. raylib-examples.plan raylib-examples.plan raylib-examples.plan

For **DOOM**, "complete + extended" currently means substantially beyond the original specification: raycasting, inline maps, level selector, 24 levels including architecture-derived maps, symbolic pixmap textures, textured walls/floors, sprite enemies, bosses, health bars, pickups, stamina, multiple weapons, weapon cycling, minimap/facing direction, and 96×72 map support.

So numerically, the current suite is approximately:

**16 implemented games**, **18 with substantive specifications** (counting Breakout as only partially specified), and **17 backlog/unimplemented concepts**.

The most obvious next implementations, because their specifications already exist, are **Connect Four** and **Columns**.