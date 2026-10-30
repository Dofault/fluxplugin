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
