#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

// Minecraft 26.2's item vertex shader, plus Flux's head pointer (api.client.Camera#pointer, FluxTopDown): the item
// flux:pointer worn by a player looking through another entity is drawn as a mouse pointer on his screen, placed from
// his head's turn every frame, without waiting for the server. Keep the numbers in line with Camera.java.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;

out vec2 texCoord0;

// Screen units (1 = half the screen's height at a 90 degree field of view) per degree of head turn, and the area the
// pointer stays in: Camera.POINTER_SPEED, POINTER_WIDTH, POINTER_HEIGHT.
const float FLUX_POINTER_SPEED = 0.025;
const float FLUX_POINTER_WIDTH = 1.15;
const float FLUX_POINTER_HEIGHT = 0.62;
// Size of the drawn pointer, same units, and how far in front of the eye it is drawn.
const float FLUX_POINTER_SIZE = 0.07;
const float FLUX_POINTER_DEPTH = 0.2;
// 1: the marked face of the model looks where the head looks; -1 if it turned out to look behind.
const float FLUX_POINTER_FACING = 1.0;

float fluxWrap(float a) {
    return a - 360.0 * floor((a + 180.0) / 360.0);
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);

    texCoord0 = UV0;

    // ---- Flux head pointer ------------------------------------------------------------------------------------
    // Its texture marks each corner with an almost transparent texel (254, 1, 200 + corner, 5): 0 top left,
    // 1 top right, 2 bottom left, 3 bottom right. Only in the world (perspective), never in a menu.
    ivec2 fluxSize = textureSize(Sampler0, 0);
    ivec4 fluxMark = ivec4(texelFetch(Sampler0, clamp(ivec2(UV0 * vec2(fluxSize)), ivec2(0), fluxSize - 1), 0) * 255.0 + 0.5);
    if (fluxMark.r == 254 && fluxMark.g == 1 && fluxMark.a == 5 && fluxMark.b >= 200 && fluxMark.b <= 203 && ProjMat[2][3] != 0.0) {
        int corner = fluxMark.b - 200;
        // the face's normal is where the head looks (world axes), the view matrix gives the camera's direction
        vec3 look = normalize(Normal) * FLUX_POINTER_FACING;
        vec3 camera = transpose(mat3(ModelViewMat)) * vec3(0.0, 0.0, -1.0);
        float headYaw = degrees(atan(-look.x, look.z));
        float headPitch = degrees(asin(clamp(-look.y, -1.0, 1.0)));
        float cameraYaw = degrees(atan(-camera.x, camera.z));
        float x = clamp(fluxWrap(headYaw - cameraYaw) * FLUX_POINTER_SPEED, -FLUX_POINTER_WIDTH, FLUX_POINTER_WIDTH);
        float y = clamp(-headPitch * FLUX_POINTER_SPEED, -FLUX_POINTER_HEIGHT, FLUX_POINTER_HEIGHT);
        // view space: x right, y up, the camera looks along -z; the tip (top left corner) on the pointed ray
        vec2 at = vec2(x, y) + vec2((corner == 1 || corner == 3) ? FLUX_POINTER_SIZE : 0.0,
                                    (corner >= 2) ? -FLUX_POINTER_SIZE : 0.0);
        gl_Position = ProjMat * vec4(at * FLUX_POINTER_DEPTH, -FLUX_POINTER_DEPTH, 1.0);
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;
        vertexColor = vec4(1.0);
        lightMapColor = vec4(1.0);
        overlayColor = vec4(0.0, 0.0, 0.0, 1.0);
    }
}
