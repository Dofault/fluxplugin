#version 330

// Flux sidebar: the scoreboard's background drawn like the HUD's panels (pack.ClientPack, client-pack.sidebar).
//
// The game draws the sidebar as two black fills whose right edge is 1 GUI pixel from the screen's: the title
// (exactly 9 pixels high) and the lines under it (9 per line + 1). Both keep the game's colour (vanilla look)
// unless an alpha is set (negative = keep the game's), and get the HUD panels' corners (cut by 2 then 1
// pixel): the title its top corners, the lines their bottom ones. Any other
// fill is drawn as usual.

// Can't moj_import in things used during startup, when resource packs don't exist.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
flat in vec2 fluxGui;
in vec2 fluxPos;
in vec2 fluxUv;

out vec4 fragColor;

const float TITLE_ALPHA = %TITLE_ALPHA%;
const float LINES_ALPHA = %LINES_ALPHA%;  // negative = the game's alpha

void main() {
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    if (fluxGui.x > 0.0 && all(lessThan(color.rgb, vec3(0.002)))) {
        float du = dFdx(fluxUv.x);
        float dv = dFdy(fluxUv.y);
        if (abs(du) > 1e-7 && abs(dv) > 1e-7) {
            // the fill's size (signed) and bounds in GUI pixels
            vec2 size = vec2(dFdx(fluxPos.x) / du, dFdy(fluxPos.y) / dv);
            vec2 a = fluxPos - fluxUv * size;
            vec2 lo = min(a, a + size);
            vec2 hi = max(a, a + size);
            vec2 extent = hi - lo;
            if (abs(hi.x - (fluxGui.x - 1.0)) < 0.05 && extent.x > 3.5 && extent.y > 0.5) {
                bool title = abs(extent.y - 9.0) < 0.05;
                float alpha = title ? TITLE_ALPHA : LINES_ALPHA;
                if (alpha >= 0.0) {
                    color = vec4(0.0, 0.0, 0.0, alpha);
                }
                float column = floor(min(fluxPos.x - lo.x, hi.x - fluxPos.x));
                float row = floor(title ? fluxPos.y - lo.y : hi.y - fluxPos.y);
                if ((column < 0.5 && row < 1.5) || (column < 1.5 && row < 0.5)) {
                    discard;
                }
            }
        }
    }
    fragColor = color * ColorModulator;
}
