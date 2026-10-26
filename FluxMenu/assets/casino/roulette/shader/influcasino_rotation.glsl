// InfluCasino: rotation of one text glyph (casino roulette, FluxMenu menus).
// Called from the shared text shader (rendertype_text.vsh, overlay mythichud-26_1), after applyCustomHud().
// ASCII ONLY in this file: some GPU drivers refuse to compile GLSL with non-ASCII characters, even in comments.
// Marker: the glyph is declared IC_SHIFT px lower than its real place (ItemsAdder y_position lowered by IC_SHIFT).
// Any glyph whose vertices fall in that band is moved back up, then rotated around its center.
// (Color cannot be the marker: ItemsAdder re-colors the glyphs of menu titles.)
// MythicHUD only acts beyond 100000 px: no conflict.
// Glyph corners: gl_VertexID % 4 = 0 top left, 1 bottom left, 2 bottom right, 3 top right.
#define IC_SHIFT 5000.0
#define IC_SIZE 124.0
#define IC_TURNS_PER_SECOND 0.35

void influcasinoRotation() {
    if (Position.y < IC_SHIFT - 1000.0 || Position.y > IC_SHIFT + 1000.0) {
        return;
    }
    vec2 p = vec2(Position.x, Position.y - IC_SHIFT);
    // GameTime: fraction of the day (24000 ticks), so GameTime * 1200 = seconds
    float angle = GameTime * 1200.0 * IC_TURNS_PER_SECOND * 6.2831853;
    int v = gl_VertexID % 4;
    float h = IC_SIZE * 0.5;
    vec2 corner = vec2(h, h);
    if (v == 1) corner = vec2(h, -h);
    if (v == 2) corner = vec2(-h, -h);
    if (v == 3) corner = vec2(-h, h);
    vec2 center = p + corner;
    vec2 d = p - center;
    float s = sin(angle);
    float c = cos(angle);
    vec2 r = vec2(d.x * c - d.y * s, d.x * s + d.y * c);
    gl_Position = ProjMat * ModelViewMat * vec4(center + r, Position.z, 1.0);
}
