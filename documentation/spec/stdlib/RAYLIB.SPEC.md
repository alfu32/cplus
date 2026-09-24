# Raylib Standard-Library Bindings

Status: implemented as thin C-plus header modules for the Raylib 6.0 payload shipped with TinyCC.

## Design

These modules expose Raylib's native C API without renaming its functions, mirroring structs, or adding receiver methods. Raylib already provides a C interface; a second object-oriented API would duplicate its surface and risk drifting from the bundled version. Each module is a guarded header include, and `raylib.cp` is the umbrella import. You may also use `#include <raylib.h>` directly. The modules do not configure a window, allocate resources, or auto-link Raylib.

```c
comptime import "stdlib:/graphics/raylib.cp";

int main(void) {
    InitWindow(960, 540, "C-plus");
    defer CloseWindow();
    while (!WindowShouldClose()) {
        BeginDrawing();
        ClearBackground(RAYWHITE);
        DrawText("Hello", 24, 24, 24, DARKBLUE);
        EndDrawing();
    }
}
```

## Module and API Inventory

Every domain module includes `<raylib.h>`. The math module additionally includes `<raymath.h>`, and low-level rendering additionally includes `<rlgl.h>`. The headers define the public Raylib functions, constants, enums, and data types; the C-plus modules do not redeclare them.

| C-plus import | Raylib domain | Representative native types |
| --- | --- | --- |
| `raylib/core.cp` | Window/context lifecycle, drawing frames/modes, timing, monitors, logging, random values, VR | `VrDeviceInfo`, `VrStereoConfig` |
| `raylib/input.cp` | Keyboard, gamepad, mouse, touch | Raylib key/button/axis enums; `Vector2` |
| `raylib/gestures.cp` | Touch gesture detection | `Gesture` enum; `Vector2` |
| `raylib/camera.cp` | 2D/3D camera control, projections, rays | `Camera2D`, `Camera3D`/`Camera`, `Ray`, `RayCollision`, `BoundingBox` |
| `raylib/draw.cp` | Drawing state, blend/scissor modes, VR drawing | Raylib value types and mode enums |
| `raylib/shapes.cp` | 2D primitives, splines, 2D collisions | `Vector2`, `Rectangle`, `Color` |
| `raylib/textures.cp` | CPU images, GPU textures, render targets, color/pixel operations | `Image`, `Texture2D`, `RenderTexture2D`, `NPatchInfo` |
| `raylib/text.cp` | Fonts, text drawing/measurement, codepoints | `Font`, `GlyphInfo` |
| `raylib/models.cp` | Meshes, materials, models, animations, 3D collisions | `Mesh`, `Shader`, `Material`, `Transform`, `Model`, `ModelAnimation` |
| `raylib/audio.cp` | Audio device, decoded waves, sounds, music, streams | `Wave`, `Sound`, `Music`, `AudioStream` |
| `raylib/resources.cp` | File/path helpers, compression, hashing, automation | `FilePathList`, `AutomationEvent`, `AutomationEventList` |
| `raylib/math.cp` | Raylib math value helpers | `<raymath.h>` operations over `Vector2`, `Vector3`, `Vector4`, `Quaternion`, `Matrix` |
| `raylib/low_level.cp` | Low-level renderer access | `<rlgl.h>` declarations |
| `raylib.cp` | Imports every domain above | Same native declarations |

### Native type inventory

This is an inventory, not a second C-plus type system. The imported headers remain authoritative.

| Type family | Raylib 6.0 types |
| --- | --- |
| Value/math | `Vector2`, `Vector3`, `Vector4`, `Quaternion` (alias of `Vector4`), `Matrix`, `Color`, `Rectangle` |
| Image/texture/text | `Image`, `Texture` (`Texture2D`, `TextureCubemap` aliases), `RenderTexture` (`RenderTexture2D` alias), `NPatchInfo`, `GlyphInfo`, `Font` |
| Camera/collision | `Camera3D` (`Camera` alias), `Camera2D`, `Ray`, `RayCollision`, `BoundingBox` |
| Model/animation | `Mesh`, `Shader`, `MaterialMap`, `Material`, `Transform`, `ModelAnimPose` (pointer alias), `BoneInfo`, `ModelSkeleton`, `Model`, `ModelAnimation` |
| Audio | `Wave`, `AudioStream`, `Sound`, `Music`, opaque `rAudioBuffer` and `rAudioProcessor` |
| VR/files/automation | `VrDeviceInfo`, `VrStereoConfig`, `FilePathList`, `AutomationEvent`, `AutomationEventList` |
| Low-level renderer | `rlVertexBuffer`, `rlDrawCall`, `rlRenderBatch`, `rlglData`, `rl_float16` |

Raylib's matching `Unload*` functions define resource lifetimes. In particular, release sound aliases with `UnloadSoundAlias`; model-owned meshes/materials must not be unloaded separately; and `VrStereoConfig` has its own unload function. C-plus ownership annotations are hints, not enforced move semantics.

Raylib link dependencies are declared by the application, not the facade modules. A top-level `comptime flags -lraylib ...;` declaration is forwarded by `cpc compile`, `run`, and `test`; the game examples currently list Linux/X11 dependencies and require different flags on other platforms.

Raylib also distributes `raymath.h` and `rlgl.h`. Standalone `rcamera.h` and `rgestures.h` are not staged consistently across targets, so the domain modules use declarations from `raylib.h` instead. The payload does not include raygui/ImGui, networking, or a TUI.

## Build and Test

Import only the domain needed, or use `stdlib:/graphics/raylib.cp`. The native headers and library must exist in the selected TinyCC payload or external compiler environment. When using an embedded target payload without a custom `--sysroot`, C-plus adds that target's bundled Raylib headers and resolves `-lraylib` to its bundled archive. An explicit `--sysroot` remains authoritative. Raylib and platform dependencies are not linked automatically.

For a directly runnable Linux desktop executable, transcode and link with the host compiler and host Raylib development package:

```sh
cpc transcode stdlib/examples/raylib_hello.cp -o raylib-hello.c
cc raylib-hello.c -o raylib-hello \
  -lraylib -lGL -lm -lpthread -ldl -lrt -lX11 -lXrandr -lXinerama -lXcursor -lXi
```

The embedded Linux TinyCC payload provides `libraylib.a` and headers for compilation, but its dynamic output uses a bundled musl loader and the graphics stack still has host runtime dependencies. The sysroot is not a self-contained desktop runtime: its `libGL.so` requires further Mesa/X11 runtime libraries, and `-static` cannot link `-lGL` because no `libGL.a` is bundled. For local desktop execution, use the host compiler and host graphics stack as above. `cpc test stdlib/tests/raylib_math.cp` covers headless `raymath.h` operations; `./gradlew :cli:test` checks umbrella expansion and links against the bundled Linux archive without opening a window. macOS SDK and display/device runtime requirements remain host responsibilities.
