#version 330

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#moj_import <minecraft:light.glsl>
#endif
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
#ifndef NO_OVERLAY
uniform sampler2D Sampler1;
#endif

#ifndef EMISSIVE
uniform sampler2D Sampler2;
#endif

out float sphericalVertexDistance;
out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif

#ifndef EMISSIVE
out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
out vec4 overlayColor;
#endif

out vec2 texCoord0;

flat out vec4 tint;
flat out vec3 vNormal;
out vec2 origTexCoord0;
flat out int isTopLayer;
flat out int isHead;
out float vGameTime;

#define VANILLA_SKIN_TEX_SIZE 64.
#define BONE_TEX_SIZE 16.
#define BONE_TEX_SIZE_NORM BONE_TEX_SIZE / VANILLA_SKIN_TEX_SIZE
#define PER_BONE_VTXS 24
#define PER_BONE_VTXS_BOTH_LAYERS 48

#define IS_NOT_GUI ProjMat[3][2] != -2.
#define MIGHT_BE_TOP_LAYER UV0.x > 0.5
#define b1 gl_VertexID % 12

#define handle_top_layer(index) if (a2 == index && MIGHT_BE_TOP_LAYER) isTopLayer = 1;

#define NIGHT_COLOR normalize(vec3(42.0 / 255.0, 42.0 / 255.0, 72.0 / 255.0))
#define DAY_COLOR normalize(vec3(1.0, 1.0, 1.0))
float qq1(sampler2D lightMap) {
    vec3 sunLight = texture(lightMap, vec2(0.5 / 16.0, 15.5 / 16.0)).rgb;
    float sunLightLength = length(sunLight);
    if (sunLightLength == 0.0) return 0.0;
    sunLight /= sunLightLength;
    return clamp(pow(length(sunLight -  NIGHT_COLOR) / length(DAY_COLOR -  NIGHT_COLOR), 4.0), 0.0, 1.0);
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif

#ifndef EMISSIVE
    lightMapColor = sample_lightmap(Sampler2, UV2);
#endif

#ifndef NO_OVERLAY
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif

    texCoord0 = UV0;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

    isTopLayer = 0;
    isHead = 0;
    origTexCoord0 = UV0;
    vNormal = Normal;
    tint = Color;
    vGameTime = GameTime;
    if(vGameTime <= 0) {
        vGameTime = 0.5;
    }

    //%START_EMOTES%
    vec2 textureDimensions = vec2(textureSize(Sampler0, 0));

    bool xx2 = textureDimensions.x != VANILLA_SKIN_TEX_SIZE || textureDimensions.y != VANILLA_SKIN_TEX_SIZE;
    bool xx1 = UV0.y <= 0.25;
    if(xx1 || IS_NOT_GUI || !xx2) {
        if (textureDimensions.x < 25.0 || textureDimensions.y < 1.0) return;
        if (texelFetch(Sampler0, ivec2(0, 0), 0).a != 1.0) return;
        if (texelFetch(Sampler0, ivec2(24, 0), 0).a != 1.0) return;

        bool IS_SLIM = length(texture(Sampler0, vec2(54. / 64., 20. / 64.)).rgb) == 0.0;

        vec2 aa0 = UV0;
        int a2 = (gl_VertexID / PER_BONE_VTXS) % 14;
        int a1 = gl_VertexID / PER_BONE_VTXS_BOTH_LAYERS;
        int a0 = a2 % 2;

        if(a1 == 6) {
            isHead = 1;
        }
        else if (a1 == 7) {
            int flippedA0 = 1 - a0;
            aa0.x = UV0.x * 2.0 - a0;

            if (IS_SLIM) {
                if (aa0.x < 0.375) {
                    aa0.x *= 8.0 / 7.0;
                }
                else if (aa0.x > 0.625 && aa0.x < 0.875) {
                    int i = b1;
                    aa0.x = (i == 0 || i == 3) ? (10.0 / 14.0) : (11.0 / 14.0);
                }
                aa0.x *= 0.21875;
            }
            else {
                aa0.x *= BONE_TEX_SIZE_NORM;
            }

            aa0.y = UV0.y / 0.25;
            if (aa0.y < 0.75) {
                aa0.y *= 0.5;
            }

            aa0.x += (16.0 + flippedA0 * BONE_TEX_SIZE) / VANILLA_SKIN_TEX_SIZE;
            aa0.y = aa0.y * BONE_TEX_SIZE_NORM + 0.75;

            aa0.x -= (16.0 / 64.0);

            if (a2 == 1 && UV0.x <= 0.5)
            isTopLayer = 1;
        }
        else if (a1 == 8) {
            handle_top_layer(2);
            aa0.y = UV0.y / (BONE_TEX_SIZE_NORM);
            if (aa0.y < 0.75) {
                aa0.y *= 0.5;
            }

            float i1 = UV0.x;
            if(a2 != 2) {
                i1 = i1 - 0.5;
            }

            i1 /= 0.5;

            if (i1 < 0.375) {
                i1 /= 1.5;
            }
            else if (i1 > 0.625 && i1 < 0.875) {
                i1 /= 1.125;
            }
            if (a2 == 2) {
                aa0.y = aa0.y * BONE_TEX_SIZE_NORM + BONE_TEX_SIZE_NORM;
            }
            else {
                aa0.y = aa0.y * BONE_TEX_SIZE_NORM + 0.5;
            }
            aa0.x = i1 * 0.375 + BONE_TEX_SIZE_NORM;
        }
        else if (a1 == 9) {
            handle_top_layer(6);
            aa0.x = UV0.x * 2 - a0;
            if (IS_SLIM) {
                if (aa0.x < 0.375) {
                    aa0.x *= 8. / 7.;
                }
                else if (aa0.x > 0.625 && aa0.x < 0.875) {
                    int i = b1;
                    if(i == 0 || i == 3) {
                        aa0.x = 11. / 14.;
                    }
                    else {
                        aa0.x = 10. / 14.;
                    }
                }
                aa0.x *= 0.21875;
            }
            else {
                aa0.x *= BONE_TEX_SIZE_NORM;
            }

            aa0.y = UV0.y / 0.25;
            if (aa0.y < 0.75) {
                aa0.y *= 0.5;
            }
            aa0.x = aa0.x + (32. + a0 * BONE_TEX_SIZE) / VANILLA_SKIN_TEX_SIZE;
            aa0.y = aa0.y * BONE_TEX_SIZE_NORM + 0.75;

            if(a2 == 6 && UV0.x >= 0.5 && UV0.y == 0.25)
            isTopLayer = 1;
        }
        else if (a1 == 10) {
            handle_top_layer(8);
            aa0.x = UV0.x * 2 - a0;
            if (IS_SLIM) {
                if (aa0.x < 0.375) {
                    aa0.x *= 8. / 7.;
                }
                else if (aa0.x > 0.625 && aa0.x < 0.875) {
                    int i = b1;
                    if(i == 0 || i == 3) {
                        aa0.x = 11. / 14.;
                    }
                    else {
                        aa0.x = 10. / 14.;
                    }
                }
                aa0.x *= 0.21875;
            }
            else {
                aa0.x *= (BONE_TEX_SIZE_NORM);
            }

            aa0.y = UV0.y / 0.25;
            if (aa0.y < 0.75) {
                aa0.y *= 0.5;
            }
            aa0.x = aa0.x + 0.625;
            aa0.y = aa0.y * (BONE_TEX_SIZE_NORM) + (a0 + 1) * 0.25;
        }
        else if (a1 == 11) {
            handle_top_layer(8);
            aa0.y = UV0.y / (BONE_TEX_SIZE_NORM);
            if (aa0.y < 0.75) {
                aa0.y *= 0.5;
            }

            if (a2 == 8) {
                aa0.x = UV0.x / 0.5 * BONE_TEX_SIZE_NORM;
                aa0.y = aa0.y * BONE_TEX_SIZE_NORM + BONE_TEX_SIZE_NORM;
            }
            else {
                aa0.x = (UV0.x - 0.5) / 0.5 * BONE_TEX_SIZE_NORM;
                aa0.y = aa0.y * BONE_TEX_SIZE_NORM + 0.5;
            }
        }
        else {
            return;
        }

        texCoord0 = aa0;

#ifndef EMISSIVE
        float qq2 = qq1(Sampler2);
        if (qq2 >= 0.2) {
            lightMapColor.rgb = mix(lightMapColor.rgb, vec3(1.0), 0.6);
        }
#endif
    }
    //%END_EMOTES%
}
