#!/usr/bin/env python3
"""Builds Flux's loading screen: the screen Minecraft shows while the resource pack loads (red Mojang screen).

    python3 gen_loading.py [--preview <dir>]

Writes, under Flux/src/main/resources/pack/loading/:
  gui.vsh, gui.fsh   core shaders of the GUI fills (pipeline "gui"), copied by ClientPack into the overlay of the pack
                     formats they are written for (26.2);
  logo_hook.glsl     lines ClientPack puts at the top of main() in MC GUI's position_tex_color.fsh: they hide the
                     Mojang Studios logo (that shader belongs to MC GUI, it cannot be replaced, only patched).

How it works (Minecraft 26.2, LoadingOverlay): the screen is a clear colour (or a full-screen fill when the game
fades in), the logo drawn additively with the pipeline mojang_logo, and a progress bar made of five white fills.
The vertex shader finds the fill of the progress (white, at the place the game computes from the GUI size) and
stretches it over the whole screen: the fragment shader then draws the background, the picture and our own bar,
reading the progress from where the fill ended. The four lines of the frame are collapsed. The full-screen fill of
the fade (brand red) draws the same screen without the bar, with the game's fade alpha.
The picture (logo + texts) is baked into the shader as 4-bit palette indices: no texture can be bound to "gui".
Animation clock: Globals.GameTime (moves only while a world is loaded: server pack pushes, F3+T in game);
the screen is still at the very first start, where the game has no world.

The preview renders the real shaders with OpenGL (moderngl, EGL) and the draw calls of the game.
"""
import argparse
import math
import pathlib

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent.parent / "src/main/resources/pack/loading"
LOGO = HERE / "logo.png"

TITLE = "INFLUENCRAFT"
SUBTITLE = "PLAY.INFLUENCRAFT.FR"
LOGO_SIZE = 72

# palette: 0 transparent, 1..11 logo, 12.. fixed
OUTLINE = (27, 11, 46)
GOLD_TOP = (255, 226, 122)
GOLD_BOTTOM = (245, 165, 36)
LAVENDER = (217, 198, 255)
LOGO_COLOURS = 11

FONT = {
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "F": ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
    "L": ["#....", "#....", "#....", "#....", "#....", "#....", "#####"],
    "N": ["#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"],
    "P": ["####.", "#...#", "#...#", "####.", "#....", "#....", "#...."],
    "R": ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "U": ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "Y": ["#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."],
    ".": [".", ".", ".", ".", ".", ".", "#"],
}

# picture layout (picture pixels): logo, title (font x2), subtitle (font x1); the bar is drawn by the shader below
GAP = 5
BAR_GAP = 7
BAR_W = 104
BAR_H = 7


def text_mask(text, scale):
    width = sum(len(FONT[c][0]) + 1 for c in text) - 1
    mask = [[False] * (width * scale) for _ in range(7 * scale)]
    x = 0
    for c in text:
        g = FONT[c]
        for gy, row in enumerate(g):
            for gx, ch in enumerate(row):
                if ch == "#":
                    for sy in range(scale):
                        for sx in range(scale):
                            mask[gy * scale + sy][(x + gx) * scale + sx] = True
        x += len(g[0]) + 1
    return mask


def build_picture():
    logo = Image.open(LOGO).convert("RGBA")
    box = logo.getbbox()
    logo = logo.crop(box)
    w, h = logo.size
    side = max(w, h)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(logo, ((side - w) // 2, (side - h) // 2))
    small = sq.resize((LOGO_SIZE, LOGO_SIZE), Image.LANCZOS)
    alpha = small.getchannel("A")
    rgb = Image.new("RGB", small.size, OUTLINE)
    rgb.paste(small.convert("RGB"), mask=alpha)
    q = rgb.quantize(colors=LOGO_COLOURS, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    pal = q.getpalette()[: LOGO_COLOURS * 3]
    logo_pal = [tuple(pal[i * 3:i * 3 + 3]) for i in range(LOGO_COLOURS)]

    title = text_mask(TITLE, 2)
    sub = text_mask(SUBTITLE, 1)
    tw, th = len(title[0]) + 2, len(title) + 2
    sw, sh = len(sub[0]) + 2, len(sub) + 2
    width = max(LOGO_SIZE, tw, sw) + 2
    height = LOGO_SIZE + GAP + th + GAP + sh
    px = [[0] * width for _ in range(height)]

    lx = (width - LOGO_SIZE) // 2
    for y in range(LOGO_SIZE):
        for x in range(LOGO_SIZE):
            if alpha.getpixel((x, y)) >= 128:
                px[y][lx + x] = 1 + q.getpixel((x, y))

    def stamp(mask, ox, oy, colour_of_row):
        h_, w_ = len(mask), len(mask[0])
        for y in range(-1, h_ + 1):
            for x in range(-1, w_ + 1):
                inside = 0 <= y < h_ and 0 <= x < w_ and mask[y][x]
                if inside:
                    px[oy + y][ox + x] = colour_of_row(y, h_)
                else:
                    near = any(0 <= y + dy < h_ and 0 <= x + dx < w_ and mask[y + dy][x + dx]
                               for dy in (-1, 0, 1) for dx in (-1, 0, 1))
                    if near:
                        px[oy + y][ox + x] = 12

    ty = LOGO_SIZE + GAP + 1
    stamp(title, (width - tw) // 2 + 1, ty, lambda y, h_: 13 if y < h_ // 2 else 14)
    sy = ty + th - 1 + GAP
    stamp(sub, (width - sw) // 2 + 1, sy, lambda y, h_: 15)
    palette = [(0, 0, 0)] + logo_pal + [OUTLINE, GOLD_TOP, GOLD_BOTTOM, LAVENDER]
    return px, palette


def glsl_vec3(c):
    return "vec3(%.4f, %.4f, %.4f)" % tuple(v / 255 for v in c)


def pack_picture(px):
    flat = [v for row in px for v in row]
    while len(flat) % 8:
        flat.append(0)
    words = []
    for i in range(0, len(flat), 8):
        w = 0
        for k in range(8):
            w |= flat[i + k] << (4 * k)
        words.append("%du" % w)
    lines = []
    for i in range(0, len(words), 12):
        lines.append("    " + ", ".join(words[i:i + 12]))
    return len(words), ",\n".join(lines)


HEADER = """#version 330
#extension GL_ARB_shader_draw_parameters : enable

// Flux loading screen: generated by Flux/assets/loading/gen_loading.py, do not edit by hand.
"""

VERTEX = HEADER + """
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
// 0: any fill, drawn as usual; 1: the progress of the loading screen; 2: the fade of the loading screen
flat out int fluxLoading;
// progress, interpolated as (progress * k, k): exact once divided (see the fragment shader)
out vec2 fluxProgress;

bool near(float a, float b) {
    return abs(a - b) < 0.01;
}

bool near2(vec2 a, vec2 b) {
    return near(a.x, b.x) && near(a.y, b.y);
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    fluxLoading = 0;
    fluxProgress = vec2(0.0);

    // GUI projection only (orthographic), visible fills only
    if (abs(ProjMat[2][3]) > 1e-5 || abs(ProjMat[3][3] - 1.0) > 1e-5 || Color.a <= 0.0) return;
    vec2 gui = ceil(vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]) - 1e-3);
    vec2 p = (ModelViewMat * vec4(Position, 1.0)).xy;
    // a fill writes (x1,y1) (x1,y0) (x0,y0) (x0,y1), x1 and y1 the larger: corner of this vertex on the screen
    // the GUI is drawn from one shared buffer at a base vertex that is not always a multiple of 4
#ifdef GL_ARB_shader_draw_parameters
    int corner = (gl_VertexID - gl_BaseVertexARB) % 4;
#else
    int corner = gl_VertexID % 4;
#endif
    vec2 ndc = vec2(corner < 2 ? 1.0 : -1.0, (corner == 0 || corner == 3) ? -1.0 : 1.0);

    // LoadingOverlay's layout, from the GUI size
    float logoHeight = min(gui.x * 0.75, gui.y) * 0.25;
    float halfWidth = floor(logoHeight * 2.0);
    float x0 = floor(gui.x * 0.5) - halfWidth;
    float x1 = floor(gui.x * 0.5) + halfWidth;
    float barY = floor(gui.y * 0.8325);

    // Every test is on this vertex's own corner: a fill of another screen that shares one coordinate with the bar
    // is left whole (each vertex decides alone, so a loose test would stretch half of a quad).
    bool right = corner < 2;
    bool bottom = corner == 0 || corner == 3;
    float y0 = barY - 5.0;
    float y1 = barY + 5.0;
    if (all(greaterThan(Color.rgb, vec3(0.999)))) {
        // the progress: fill(x0 + 2, y0 + 2, x0 + width, y1 - 2), width from 0 to x1 - x0 - 2
        bool rowP = near(p.y, bottom ? y1 - 2.0 : y0 + 2.0);
        bool colP = right ? p.x > x0 + 1.99 && p.x < x1 - 0.99 : p.x > x0 - 0.01 && p.x < x0 + 2.01;
        if (rowP && colP) {
            fluxLoading = 1;
            float progress = clamp((p.x - x0) / max(x1 - x0 - 2.0, 1.0), 0.0, 1.0);
            fluxProgress = right ? vec2(progress, 1.0) : vec2(0.0);
            gl_Position = vec4(ndc, gl_Position.z, 1.0);
            return;
        }
        // the frame: top, bottom, left and right lines, drawn by us
        bool frame =
                near2(p, vec2(right ? x1 - 1.0 : x0 + 1.0, bottom ? y0 + 1.0 : y0))
                || near2(p, vec2(right ? x1 - 1.0 : x0 + 1.0, bottom ? y1 : y1 - 1.0))
                || near2(p, vec2(right ? x0 + 1.0 : x0, bottom ? y1 : y0))
                || near2(p, vec2(right ? x1 : x1 - 1.0, bottom ? y1 : y0));
        if (frame) {
            gl_Position = vec4(-2.0, -2.0, 0.0, 1.0);
            return;
        }
    }
    // the fade: fill(0, 0, width, height, brand red)
    if (all(lessThan(abs(Color.rgb - vec3(239.0, 50.0, 61.0) / 255.0), vec3(0.002)))
            && near2(p, vec2(right ? gui.x : 0.0, bottom ? gui.y : 0.0))) {
        fluxLoading = 2;
    }
}
"""

FRAGMENT = HEADER + """
// Can't moj_import in things used during startup, when resource packs don't exist.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

in vec4 vertexColor;
flat in int fluxLoading;
in vec2 fluxProgress;

out vec4 fragColor;

const int ART_W = @ART_W@;
const int ART_H = @ART_H@;
const uint ART[@ART_N@] = uint[](
@ART@
);
const vec3 PALETTE[16] = vec3[](
@PALETTE@
);
const float BAR_GAP = @BAR_GAP@.0;
const float BAR_W = @BAR_W@.0;
const float BAR_H = @BAR_H@.0;

const vec3 SKY_TOP = @SKY_TOP@;
const vec3 SKY_BOTTOM = @SKY_BOTTOM@;
const vec3 GLOW = @GLOW@;
const vec3 RAY = @RAY@;
const vec3 FRAME_DARK = @FRAME_DARK@;
const vec3 FRAME_LIGHT = @FRAME_LIGHT@;
const vec3 BAR_EMPTY = @BAR_EMPTY@;
const vec3 FILL_TOP = @FILL_TOP@;
const vec3 FILL_BOTTOM = @FILL_BOTTOM@;
const vec3 SPARK_A = @SPARK_A@;
const vec3 SPARK_B = @SPARK_B@;
const vec3 SPARK_C = @SPARK_C@;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// ordered dithering on the picture's pixel grid: soft gradients that still look like pixels
float bayer(vec2 g) {
    ivec2 i = ivec2(mod(g, 4.0));
    int m[16] = int[](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
    return (float(m[i.y * 4 + i.x]) + 0.5) / 16.0;
}

vec3 steps(vec3 c, vec2 g) {
    return floor(c * 24.0 + bayer(g)) / 24.0;
}

int artAt(ivec2 a) {
    if (a.x < 0 || a.y < 0 || a.x >= ART_W || a.y >= ART_H) return 0;
    int i = a.y * ART_W + a.x;
    return int((ART[i >> 3] >> uint((i & 7) * 4)) & 15u);
}

// a 4-pointed star of radius r (grid pixels) around the centre
float star(vec2 d, float r) {
    vec2 a = abs(d);
    if (a.x < 0.5 && a.y < r + 0.5) return 1.0 - a.y / (r + 1.0);
    if (a.y < 0.5 && a.x < r + 0.5) return 1.0 - a.x / (r + 1.0);
    if (r > 1.5 && a.x < 1.5 && a.y < 1.5) return 0.55;
    return 0.0;
}

vec3 screen(bool bar, float progress) {
    vec2 size = ScreenSize;
    vec2 q = vec2(gl_FragCoord.x, size.y - gl_FragCoord.y);
    float t = GameTime * 1200.0;

    float blockH = float(ART_H) + BAR_GAP + BAR_H;
    float s = max(1.0, floor(min(size.x * 0.6 / float(ART_W), size.y * 0.62 / blockH)));
    vec2 origin = floor((size - vec2(float(ART_W), blockH) * s) * 0.5);
    vec2 g = floor((q - origin) / s);
    vec2 gs = (q - origin) / s;

    // background: night sky, glow and slow rays behind the logo
    vec2 centre = vec2(float(ART_W) * 0.5, @LOGO_CY@);
    float sky = clamp(q.y / size.y, 0.0, 1.0);
    vec3 col = mix(SKY_TOP, SKY_BOTTOM, sky);
    vec2 d = g + 0.5 - centre;
    float dist = length(d) / (min(size.x, size.y) / s);
    col = mix(col, GLOW, 0.55 * exp(-dist * dist * 9.0));
    float angle = atan(d.y, d.x) / 6.2831853;
    float rays = step(0.5, fract(angle * 14.0 + t * 0.015));
    col = mix(col, RAY, rays * 0.10 * exp(-dist * 3.5));
    col = steps(col, g);

    // sparkles rising in columns
    const float CELL = 22.0;
    vec2 rise = gs + vec2(0.0, t * 6.0);
    for (int ox = -1; ox <= 1; ox++) {
        for (int oy = -1; oy <= 1; oy++) {
            vec2 c = floor(rise / CELL) + vec2(ox, oy);
            float h = hash(c);
            if (h > 0.42) continue;
            vec2 at = (c + vec2(hash(c + 3.1), hash(c + 7.7)) * 0.8 + 0.1) * CELL;
            at.x += floor(sin(t * 0.8 + h * 40.0) * 2.0);
            vec2 rel = floor(rise) - floor(at);
            float r = h < 0.12 ? 2.0 : 1.0;
            float twinkle = 0.55 + 0.45 * sin(t * 3.0 + h * 90.0);
            float v = star(rel, r) * twinkle;
            if (v <= 0.0) continue;
            vec3 sc = h < 0.14 ? SPARK_A : (h < 0.28 ? SPARK_B : SPARK_C);
            col = mix(col, sc, v);
        }
    }

    // the picture
    int idx = artAt(ivec2(g));
    if (idx > 0) col = PALETTE[idx];

    // our bar, under the picture
    if (bar) {
        vec2 b = g - vec2(floor((float(ART_W) - BAR_W) * 0.5), float(ART_H) + BAR_GAP);
        if (b.x >= 0.0 && b.y >= 0.0 && b.x < BAR_W && b.y < BAR_H) {
            bool outer = b.x < 1.0 || b.y < 1.0 || b.x >= BAR_W - 1.0 || b.y >= BAR_H - 1.0;
            bool corner = (b.x < 1.0 || b.x >= BAR_W - 1.0) && (b.y < 1.0 || b.y >= BAR_H - 1.0);
            bool inner = b.x < 2.0 || b.y < 2.0 || b.x >= BAR_W - 2.0 || b.y >= BAR_H - 2.0;
            if (corner) {
                // rounded corner: keep the background
            } else if (outer) {
                col = FRAME_DARK;
            } else if (inner) {
                col = FRAME_LIGHT;
            } else {
                float inside = BAR_W - 4.0;
                float filled = floor(inside * progress + 0.5);
                float x = b.x - 2.0;
                if (x < filled) {
                    float y = (b.y - 2.0) / (BAR_H - 5.0);
                    col = mix(FILL_TOP, FILL_BOTTOM, y);
                    float stripe = mod(x - (b.y - 2.0) - floor(t * 8.0), 8.0);
                    if (stripe < 3.0) col = mix(col, vec3(1.0, 0.97, 0.85), 0.28);
                    if (b.y < 3.0) col = mix(col, vec3(1.0), 0.35);
                    if (x == filled - 1.0) col = mix(col, vec3(1.0), 0.5);
                } else {
                    col = BAR_EMPTY;
                }
            }
        }
    }
    return col;
}

void main() {
    if (fluxLoading == 1) {
        float k = fluxProgress.y;
        float progress = k > 1e-4 ? fluxProgress.x / k : 0.0;
        fragColor = vec4(screen(true, progress), 1.0) * ColorModulator;
        return;
    }
    if (fluxLoading == 2) {
        fragColor = vec4(screen(false, 0.0), vertexColor.a) * ColorModulator;
        return;
    }
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
"""

COLOURS = {
    "SKY_TOP": (18, 8, 36),
    "SKY_BOTTOM": (52, 16, 84),
    "GLOW": (122, 52, 196),
    "RAY": (190, 120, 255),
    "FRAME_DARK": OUTLINE,
    "FRAME_LIGHT": (155, 93, 229),
    "BAR_EMPTY": (22, 10, 40),
    "FILL_TOP": GOLD_TOP,
    "FILL_BOTTOM": GOLD_BOTTOM,
    "SPARK_A": (255, 215, 94),
    "SPARK_B": (255, 122, 217),
    "SPARK_C": (201, 166, 255),
}


def mojang_signature(png):
    """Lines put at the top of main() in position_tex_color.fsh: the Mojang Studios logo is discarded.

    The logo is always the game's own texture (512x512, white on transparent, read from the jar, never from a pack):
    a few texels tell it from any other picture of that size."""
    im = Image.open(png).convert("RGBA")
    w, h = im.size
    opaque, clear = [], []
    # spread the probes over the picture
    for y in range(8, h, 37):
        for x in range(8, w, 41):
            a = im.getpixel((x, y))
            if a[3] == 255 and a[:3] == (255, 255, 255) and len(opaque) < 3:
                opaque.append((x, y))
            elif a[3] == 0 and len(clear) < 3:
                clear.append((x, y))
    assert len(opaque) == 3 and len(clear) == 3, (opaque, clear)
    cond = ["textureSize(Sampler0, 0) == ivec2(%d, %d)" % (w, h)]
    cond += ["texelFetch(Sampler0, ivec2(%d, %d), 0).a > 0.99" % p for p in opaque]
    cond += ["texelFetch(Sampler0, ivec2(%d, %d), 0).a < 0.01" % p for p in clear]
    return ("    // Flux loading screen: the Mojang Studios logo is not drawn (gen_loading.py)\n"
            "    if (" + "\n            && ".join(cond) + ") discard;\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mojang", required=True, help="textures/gui/title/mojangstudios.png of the client jar")
    ap.add_argument("--preview", help="folder for the preview pictures")
    args = ap.parse_args()

    px, palette = build_picture()
    n, art = pack_picture(px)
    palette = (palette + [(0, 0, 0)] * 16)[:16]
    values = {
        "ART_W": str(len(px[0])), "ART_H": str(len(px)), "ART_N": str(n), "ART": art,
        "PALETTE": ",\n".join("    " + glsl_vec3(c) for c in palette),
        "BAR_GAP": str(BAR_GAP), "BAR_W": str(BAR_W), "BAR_H": str(BAR_H),
        "LOGO_CY": "%.1f" % (LOGO_SIZE * 0.5),
    }
    values.update({k: glsl_vec3(v) for k, v in COLOURS.items()})
    fragment = FRAGMENT
    for k, v in values.items():
        fragment = fragment.replace("@" + k + "@", v)
    assert "@" not in fragment
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "gui.vsh").write_text(VERTEX)
    (OUT / "gui.fsh").write_text(fragment)
    (OUT / "logo_hook.glsl").write_text(mojang_signature(args.mojang))
    print("picture %dx%d, %d words -> %s" % (len(px[0]), len(px), n, OUT))
    if args.preview:
        import preview
        preview.render(OUT, pathlib.Path(args.preview), px, palette)


if __name__ == "__main__":
    main()
