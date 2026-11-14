#version 330

// Minecraft 26.2 gui.fsh (plain colored rectangles of the interface), written by Flux.
// Translucent black backgrounds (chat, chat input, scoreboard, vanilla tab list) are drawn as light as the panels of
// Flux's HUD: black at 39/255. Only pure black at alpha 0.51 or less is touched, so opaque fills, colored fills and the
// darkened screen behind menus (rgb 16,16,16) stay vanilla. A chat line fading out keeps fading below 39/255.

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;

out vec4 fragColor;

const float HUD_PANEL_ALPHA = 39.0 / 255.0;

void main() {
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    if (max(color.r, max(color.g, color.b)) < 0.002 && color.a <= 0.51) {
        color.a = min(color.a, HUD_PANEL_ALPHA);
    }
    fragColor = color * ColorModulator;
}
