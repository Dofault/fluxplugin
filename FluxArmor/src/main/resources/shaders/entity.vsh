#version 410
#extension GL_KHR_shader_subgroup_basic : enable
#extension GL_KHR_shader_subgroup_quad : enable
#extension GL_ARB_shader_draw_parameters : enable

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#moj_import <minecraft:light.glsl>
#endif
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

// FluxArmor: armour in real 3D, see plugins/fluxplugin/FluxArmor and docs/technique/fluxarmor.md.
#if defined(PER_FACE_LIGHTING) && defined(NO_OVERLAY) && defined(ALPHA_CUTOUT) && !defined(EMISSIVE)
#define FA_ARMOR
#endif

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

#ifdef FA_ARMOR
out float faValid;
#endif

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

#ifdef FA_ARMOR
    // ---- FluxArmor: armour in real 3D (objmc principle) ----------------------------------------------------------
    // Each face of the vanilla armour box becomes a face of the Blockbench model, read from the layer's texture.
    // Data layer: mark (73, 65, 82) at (0, 0); flat fallback layer: (73, 65, 70).
    faValid = 1.0;
    ivec2 faSize = textureSize(Sampler0, 0);
    ivec4 faMark = ivec4(texelFetch(Sampler0, ivec2(0, 0), 0) * 255.0 + 0.5);
    if (faSize.x >= 32 && faSize.y >= 16 && faMark.a == 255 && faMark.rg == ivec2(73, 65)
            && (faMark.b == 82 || faMark.b == 70)) {
        // The rank of a vertex in the draw needs gl_BaseVertexARB (Minecraft draws with a base vertex that changes
        // from frame to frame), the limb frame needs subgroupQuadBroadcast. Without them (macOS, Vulkan, old
        // drivers): flat fallback layer, data layers hidden.
#if defined(GL_KHR_shader_subgroup_quad) && defined(GL_ARB_shader_draw_parameters) && !defined(VULKAN)
        if (faMark.b == 70) {
            gl_Position = FA_HIDDEN;
            return;
        }
        // The 4 vertices of a face share their positions. Everything above depends only on the draw's texture: the
        // 4 lanes of the quad get here together, as subgroupQuadBroadcast requires.
        int faId = gl_VertexID - gl_BaseVertexARB;
        vec3 faP[4] = vec3[4](subgroupQuadBroadcast(Position, 0u), subgroupQuadBroadcast(Position, 1u),
                              subgroupQuadBroadcast(Position, 2u), subgroupQuadBroadcast(Position, 3u));
        int faV0 = subgroupQuadBroadcast(faId, 0u), faV1 = subgroupQuadBroadcast(faId, 1u);
        int faV2 = subgroupQuadBroadcast(faId, 2u), faV3 = subgroupQuadBroadcast(faId, 3u);
        int faRank = faId & 3, faFirst = faId - faRank;
        bool faAligned = faV0 == faFirst && faV1 == faFirst + 1 && faV2 == faFirst + 2 && faV3 == faFirst + 3;

        // Boxes of the draw, in ModelPart order (24 vertices each), and face of the box (Cube order).
        int faBoxes = max(faTexel(1, 0).g, 1);
        int faSlot = (faId / 4) % 6;
        int faBox = (faId / 24) % faBoxes;
        int faRow = 1 + faBox * 6 + faSlot;
        ivec3 faFace = faTexel(0, faRow);
        if ((faFace.r & 1) == 0) {
            gl_Position = FA_HIDDEN;
            return;
        }
        if (!faAligned) {
            // The GPU did not group the 4 vertices of the face: the fragment shader drops the face (faValid very
            // negative on its triangles), it never stretches. Depends only on the face, not on the vertex.
            faValid = -1000.0;
            return;
        }

        // Box frame: its two axes in the plane of the face come from the 4 corners (signed sum), the third one from
        // their cross product, oriented by the vanilla normal (also right when mirrored).
        ivec3 faDims = faTexel(2 + 2 * faBox, 0);
        ivec3 faInfo = faTexel(3 + 2 * faBox, 0);
        bool faMirror = faInfo.r == 1;
        vec3 faHalf = vec3(faDims) * 0.5 + float(faInfo.g) / 100.0;
        int faN = FA_AXIS[faSlot], faA = (faN + 1) % 3, faB = (faN + 2) % 3;
        vec3 faEa = vec3(0.0), faEb = vec3(0.0), faF = vec3(0.0);
        float faSn = 0.0;
        for (int k = 0; k < 4; k++) {
            ivec3 s = FA_CORNERS[faSlot * 4 + (faMirror ? 3 - k : k)];
            if (faMirror) s.x = -s.x;
            faEa += float(s[faA]) * faP[k];
            faEb += float(s[faB]) * faP[k];
            faF += faP[k];
            faSn = float(s[faN]);
        }
        faEa /= 4.0 * faHalf[faA];
        faEb /= 4.0 * faHalf[faB];
        faF *= 0.25;
        float faScale = 0.5 * (length(faEa) + length(faEb));
        vec3 faC = cross(faEa, faEb);
        if (faScale < 1e-6 || length(faC) < 1e-12) {
            gl_Position = FA_HIDDEN;
            return;
        }
        faC = normalize(faC);
        vec3 faEn = faC * faScale * (dot(faC, Normal) * faSn >= 0.0 ? 1.0 : -1.0);
        mat3 faFrame;
        faFrame[faA] = faEa;
        faFrame[faB] = faEb;
        faFrame[faN] = faEn;
        vec3 faCenter = faF - faSn * faHalf[faN] * faEn;

        // Model corner carried by this vertex.
        int faX = 2 + 4 * faRank;
        vec3 faPos = faCenter + faFrame * faOffset(faX, faRow);
        vec3 faNormal = normalize(faFrame * (vec3(faTexel(1, faRow)) / 127.5 - 1.0));
        gl_Position = ProjMat * ModelViewMat * vec4(faPos, 1.0);
        sphericalVertexDistance = fog_spherical_distance(faPos);
        cylindricalVertexDistance = fog_cylindrical_distance(faPos);
        texCoord0 = vec2(faFixed(faX + 2, faRow), faFixed(faX + 3, faRow)) / vec2(faSize);
        origTexCoord0 = texCoord0;
        vNormal = faNormal;
        // The model keeps its back faces: same light on both sides, the one of its real normal.
        if ((faFace.r & 2) != 0) {
            vertexPerFaceColorFront = Color;
            vertexPerFaceColorBack = Color;
            lightMapColor = vec4(1.0);
        } else {
            vec2 faLight = minecraft_compute_light(Light0_Direction, Light1_Direction, faNormal);
            vertexPerFaceColorFront = minecraft_mix_light_separate(faLight, Color);
            vertexPerFaceColorBack = vertexPerFaceColorFront;
        }
        return;
#else
        if (faMark.b == 82) {
            gl_Position = FA_HIDDEN;
            return;
        }
#endif
    }
#endif

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
