#version 330

// Flux sidebar: the scoreboard's background drawn like the HUD's panels (pack.ClientPack, client-pack.sidebar).

// Can't moj_import in things used during startup, when resource packs don't exist.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
// GUI size of the screen, 0 when this is not the GUI projection (orthographic)
flat out vec2 fluxGui;
// position of this fragment in GUI pixels, and in the fill (0 to 1 from one corner to the other)
out vec2 fluxPos;
out vec2 fluxUv;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    bool ortho = abs(ProjMat[2][3]) < 1e-5 && abs(ProjMat[3][3] - 1.0) < 1e-5;
    fluxGui = ortho ? ceil(vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]) - 1e-3) : vec2(0.0);
    fluxPos = (ModelViewMat * vec4(Position, 1.0)).xy;
    // a fill writes its 4 corners in turn: (x0, y0) (x0, y1) (x1, y1) (x1, y0). The fragment shader reads the
    // fill's bounds from these two values, whichever way round the corners are.
    int corner = gl_VertexID % 4;
    fluxUv = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
}
