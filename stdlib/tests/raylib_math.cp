comptime import "stdlib:/graphics/raylib/math.cp";

@test "raymath value functions are available directly" {
    Vector3 left = {1.0f, 2.0f, 3.0f};
    Vector3 right = {3.0f, 2.0f, 1.0f};
    Vector3 sum = Vector3Add(left, right);
    Vector3 cross = Vector3CrossProduct(left, right);

    @assert(sum.x == 4.0f && sum.y == 4.0f && sum.z == 4.0f);
    @assert(cross.x == -4.0f && cross.y == 8.0f && cross.z == -4.0f);
    @assert(Vector3DotProduct(left, right) == 10.0f);
}

@test "raymath matrix functions are available directly" {
    Matrix identity = MatrixIdentity();
    Matrix composed = MatrixMultiply(identity, identity);
    @assert(composed.m0 == 1.0f && composed.m5 == 1.0f);
    @assert(composed.m10 == 1.0f && composed.m15 == 1.0f);
}
