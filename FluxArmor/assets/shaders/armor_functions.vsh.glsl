#ifdef FA_ARMOR
// ---- FluxArmor: reading the armour layers (format described at the top of PackBuilder.java) ------------------

// Signs of the 4 corners of each face of the vanilla box (-1: min side, +1: max side, box frame), in the order
// ModelPart.Cube sends them: faces down, up, west, north, east, south, vertices of the polygon.
// Mirrored box (left limbs): x flipped and vertex order reversed.
const ivec3 FA_CORNERS[24] = ivec3[24](
    ivec3( 1, -1,  1), ivec3(-1, -1,  1), ivec3(-1, -1, -1), ivec3( 1, -1, -1),
    ivec3( 1,  1, -1), ivec3(-1,  1, -1), ivec3(-1,  1,  1), ivec3( 1,  1,  1),
    ivec3(-1, -1, -1), ivec3(-1, -1,  1), ivec3(-1,  1,  1), ivec3(-1,  1, -1),
    ivec3( 1, -1, -1), ivec3(-1, -1, -1), ivec3(-1,  1, -1), ivec3( 1,  1, -1),
    ivec3( 1, -1,  1), ivec3( 1, -1, -1), ivec3( 1,  1, -1), ivec3( 1,  1,  1),
    ivec3(-1, -1,  1), ivec3( 1, -1,  1), ivec3( 1,  1,  1), ivec3(-1,  1,  1)
);
// Axis perpendicular to each face (0 x, 1 y, 2 z).
const int FA_AXIS[6] = int[6](1, 1, 0, 2, 0, 2);
// Point outside the screen: a face whose 4 vertices are sent there draws no pixel.
const vec4 FA_HIDDEN = vec4(-3.0, -3.0, -3.0, 1.0);

ivec3 faTexel(int x, int y) {
    return ivec3(texelFetch(Sampler0, ivec2(x, y), 0).rgb * 255.0 + 0.5);
}

// Position of a model corner (pixels, vanilla box frame): 16 bits per axis over two texels.
vec3 faOffset(int x, int y) {
    ivec3 a = faTexel(x, y), b = faTexel(x + 1, y);
    return vec3(float(a.r * 256 + a.g), float(a.b * 256 + b.r), float(b.g * 256 + b.b)) / 256.0 - 128.0;
}

// Texture coordinate in texels, 24 bits with 8 after the point.
float faFixed(int x, int y) {
    ivec3 t = faTexel(x, y);
    return float(t.r * 65536 + t.g * 256 + t.b) / 256.0;
}
#endif
