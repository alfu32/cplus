comptime import "stdlib:/graphics/raylib.cp";

int main(void) {
    InitWindow(960, 540, "C-plus with Raylib");
    defer CloseWindow();
    SetTargetFPS(60);

    while (!WindowShouldClose()) {
        BeginDrawing();
        ClearBackground(RAYWHITE);
        DrawText("Hello from C-plus", 32, 32, 24, DARKBLUE);
        EndDrawing();
    }
}
