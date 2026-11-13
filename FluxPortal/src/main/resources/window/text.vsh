#version 330
// FluxPortal window shader. Adapted from RPE-portals by ResourcePack AI (GPL-3.0): the far side of a portal drawn
// from block layers stored in maps. Changes: a rectangular hole (half width, half height), no opening animation,
// an open flag. This file is distributed under the GPL-3.0 like its origin.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;

// The window: where this fragment is, in camera-relative world axes, and the
// portal it belongs to, worked out once per vertex from the header.
out vec3 spwPos;
flat out float spwOn;
flat out vec3 spwCentre;
flat out vec3 spwAcross;
flat out vec3 spwFront;
flat out vec2 spwHalf;
flat out float spwSlab;
flat out float spwGridY;
flat out vec3 spwEye;
flat out float spwReversed;
flat out float spwFloorY;
flat out float spwEnv;
flat out float spwNearEnv;
flat out vec3 spwFog;
flat out ivec2 spwFarBlock;
flat out float spwFarFacing;

// The window's tables and functions only in the world's text: the game compiles this shader once per kind of
// text (eight of them), the interface's and see-through ones never draw a window.
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
@COMMON@

// The eye, from the matrix that takes camera-relative world space to the
// screen: view bobbing is a translation in it, so the eye is not the origin.
vec3 spw_eye_of(mat4 m) {
    mat3 a = mat3(m[0].xyw, m[1].xyw, m[2].xyw);
    if (abs(determinant(a)) < 1e-12) return vec3(0.0);
    return -(inverse(a) * m[3].xyw);
}

int spw_pair(int i) {
    return spw_field(i) * 200 + spw_field(i + 1);
}

// How far the portal's block is from the camera's, along one axis, from the
// header's coordinate modulo 40000. In floats: GLSL leaves % of a negative int
// undefined, and a driver really does answer nonsense for one.
int spw_near(int v, int camera) {
    float c = mod(float(camera), 40000.0);
    return int(mod(float(v) - c + 20000.0, 40000.0)) - 20000;
}
#endif

// Hooks filled by Flux (api.client.TextShader): other text drawn by the suite, as the HUD.
// @FLUX_DECLARATIONS

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    spwPos = Position;
    spwOn = 0.0;
    spwCentre = vec3(0.0);
    spwAcross = vec3(1.0, 0.0, 0.0);
    spwFront = vec3(0.0, 0.0, 1.0);
    spwHalf = vec2(0.0);
    spwSlab = 0.0;
    spwGridY = 0.0;
    spwEye = vec3(0.0);
    spwReversed = ProjMat[2][2] > -0.5 ? 1.0 : 0.0;
    spwFloorY = 0.0;
    spwEnv = 0.0;
    spwNearEnv = 0.0;
    spwFog = vec3(0.0);
    spwFarBlock = ivec2(0);
    spwFarFacing = 0.0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    if (spw_is_window_map()) {
        int slab = spw_field(2);
        int facing = spw_field(10);
        int bx = spw_pair(4);
        int by = spw_pair(6) - 1000;
        int bz = spw_pair(8);
        float yFrac = float(spw_field(11)) / 199.0;
        // Half the hole's width and height (in twentieths of a block), and whether the portal is open.
        vec2 holeHalf = vec2(float(spw_field(12)), float(spw_field(13))) / 20.0;
        bool isOpen = spw_field(14) == 1;
        if (slab >= 0 && facing >= 0 && facing < 4) {
            ivec2 dirs[4] = ivec2[](ivec2(0, 1), ivec2(-1, 0), ivec2(0, -1), ivec2(1, 0));
            ivec2 n = dirs[facing];
            ivec2 s = dirs[(facing + 1) % 4];
            vec3 front = vec3(float(n.x), 0.0, float(n.y));
            vec3 across = vec3(float(s.x), 0.0, float(s.y));
            // The middle: on a block edge along the front, mid-block across it.
            vec3 frac = vec3(n.x != 0 ? 0.0 : 0.5, yFrac, n.y != 0 ? 0.0 : 0.5);
            vec3 block = vec3(float(spw_near(bx, CameraBlockPos.x)), float(by - CameraBlockPos.y), float(spw_near(bz, CameraBlockPos.z)));
            vec3 centre = block + frac + CameraOffset;

            // The maps tile the hole a block at a time, each in its own frame, so
            // nothing is culled while any of the window is on screen: each quad is
            // drawn where it is, and the fragment works out what is behind it.
            // On whenever it is a window: a shut portal draws nothing, where the map
            // itself would draw noise in the frame.
            spwOn = 1.0;
            spwCentre = centre;
            spwAcross = across;
            spwFront = front;
            spwHalf = isOpen ? holeHalf : vec2(0.0);
            spwSlab = float(slab);
            spwGridY = float(by + SPW_BASE - CameraBlockPos.y) + CameraOffset.y;
            spwFloorY = float(by + SPW_BASE);
            spwEye = spw_eye_of(ProjMat * ModelViewMat);
            // Nothing to draw when shut, or from behind: the whole quad is moved off
            // screen, so not one of its pixels is run only to be thrown away.
            if (!isOpen || dot(spwEye - centre, front) <= 0.0) {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                spwOn = 0.0;
            }
            spwEnv = float(spw_field(SPW_ENV));
            spwFog = vec3(float(spw_field(SPW_ENV + 1)), float(spw_field(SPW_ENV + 2)), float(spw_field(SPW_ENV + 3))) / 199.0;
            spwNearEnv = float(spw_field(SPW_ENV + 4));
            spwFarBlock = ivec2(
                    spw_field(SPW_FAR) * 8000000 + spw_field(SPW_FAR + 1) * 40000 + spw_field(SPW_FAR + 2) * 200 + spw_field(SPW_FAR + 3),
                    spw_field(SPW_FAR + 4) * 8000000 + spw_field(SPW_FAR + 5) * 40000 + spw_field(SPW_FAR + 6) * 200 + spw_field(SPW_FAR + 7));
            spwFarFacing = float(spw_field(SPW_FAR + 8));
        }
    }
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

#ifdef IS_GUI
    // @FLUX_GUI_VERTEX
#endif
}
