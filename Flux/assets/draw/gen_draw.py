#!/usr/bin/env python3
"""Pictures of the animated draw (Flux api.draw.Draws), written into an ItemsAdder contents folder.

  fluxdraw_fonts/resourcepack/assets/
    fluxdraw/font/banner.json + fluxdraw/textures/font/banner_<n>.png   the banner behind the draw (3 glyphs of 200 px: the client refuses font pictures over 256 px)
    minecraft/textures/block/fluxdraw_spark.png (+ .mcmeta)         twinkling star, animated by the client
    minecraft/textures/block/fluxdraw_shine.png (+ .mcmeta)         glowing frame with a light sweep

The two small ones sit in the blocks atlas: a text shows them with <sprite:block/fluxdraw_spark> and the client
animates them itself (no packet). One banner glyph only: never one glyph per frame (docs/technique/pack-ressources.md).

Usage: gen_draw.py [<ItemsAdder contents folder>]   (default: Labs)
"""
import json, math, os, struct, sys, zlib

BLUE = (43, 196, 232)
LIGHT = (166, 228, 245)
NAVY = (16, 58, 104)


def png(path, w, h, px):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = b"".join(b"\x00" + bytes(c for p in px[y * w:(y + 1) * w] for c in p) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def over(dst, rgb, a):
    """Alpha-blends rgb with alpha a (0..1) over an RGBA pixel."""
    a = max(0.0, min(1.0, a))
    if a <= 0:
        return dst
    da = dst[3] / 255
    oa = a + da * (1 - a)
    if oa <= 0:
        return (0, 0, 0, 0)
    c = [round((rgb[i] * a + dst[i] * da * (1 - a)) / oa) for i in range(3)]
    return (c[0], c[1], c[2], round(oa * 255))


def smooth(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


# ------------------------------------------------------------------ banner (300 x 60 gui pixels, drawn at 2x)

def banner(out):
    W, H, S = 600, 120, 2
    px = [(0, 0, 0, 0)] * (W * H)
    for y in range(H):
        for x in range(W):
            # no hard border anywhere: long fade to nothing at both ends (eased twice, so the panel melts in
            # without a visible start), short fade at the top and the bottom
            ends = smooth(0, 240, min(x, W - 1 - x))
            edge = ends * ends * smooth(0, 16, min(y, H - 1 - y))
            a = (0.90 - 0.10 * y / H) * edge
            p = over((0, 0, 0, 0), NAVY, a)
            # blue glow in the middle
            d = ((x - W / 2) / (W * 0.32)) ** 2 + ((y - H * 0.55) / (H * 0.55)) ** 2
            p = over(p, BLUE, 0.30 * math.exp(-d * 2.2) * edge)
            # thin lines at the top and the bottom, brightest in the middle
            for ly in (7, H - 8):
                if abs(y - ly) <= 1:
                    p = over(p, BLUE, (0.95 if y == ly else 0.35) * math.exp(-((x - W / 2) / (W * 0.30)) ** 2))
            px[y * W + x] = p
    # a few small stars
    for sx, sy, s in ((150, 30, 5), (190, 92, 3), (450, 30, 5), (410, 92, 3), (235, 18, 2), (365, 18, 2)):
        for dy in range(-s, s + 1):
            for dx in range(-s, s + 1):
                k = abs(dx) + abs(dy)
                if (dx == 0 or dy == 0) and k <= s or k <= 1:
                    i = (sy + dy) * W + sx + dx
                    px[i] = over(px[i], LIGHT, 1 - k / (s + 1))
    # the client refuses a font picture wider than 256 px: three pieces of 200, joined by a -1 space (a bitmap
    # glyph advances its width + 1)
    PIECES = 3
    pw = W // PIECES
    providers = [{"type": "space", "advances": {"\uf801": -1}}]
    for k in range(PIECES):
        piece = [px[y * W + k * pw + x] for y in range(H) for x in range(pw)]
        png(os.path.join(out, "fluxdraw/textures/font/banner_%d.png" % k), pw, H, piece)
        providers.append({"type": "bitmap", "file": "fluxdraw:font/banner_%d.png" % k, "ascent": 10, "height": H // S,
                          "chars": [chr(0xE000 + k)]})
    old = os.path.join(out, "fluxdraw/textures/font/banner.png")
    if os.path.exists(old):
        os.remove(old)
    font = {"providers": providers}
    path = os.path.join(out, "fluxdraw/font/banner.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(font, f)


# ------------------------------------------------------------------ animated sprites (16 x 16 frames)

def strip(out, name, frames, frametime, draw):
    N = 16
    px = []
    for f in range(frames):
        frame = [(0, 0, 0, 0)] * (N * N)
        draw(frame, f / frames, N)
        px += frame
    base = os.path.join(out, "minecraft/textures/block/" + name)
    png(base + ".png", N, N * frames, px)
    with open(base + ".png.mcmeta", "w") as fh:
        json.dump({"animation": {"frametime": frametime, "interpolate": True}}, fh)


def spark(frame, t, N):
    # a four-pointed star breathing in and out
    s = 2.5 + 4.5 * (0.5 - 0.5 * math.cos(t * 2 * math.pi))
    c = (N - 1) / 2
    for y in range(N):
        for x in range(N):
            dx, dy = abs(x - c), abs(y - c)
            arm = max(0.0, 1 - (dx * dy) ** 0.5 / 1.2) * max(0.0, 1 - max(dx, dy) / s)
            core = math.exp(-(dx * dx + dy * dy) / 3.0)
            a = min(1.0, arm + core * 0.9)
            if a > 0.03:
                col = tuple(round(LIGHT[i] + (255 - LIGHT[i]) * core) for i in range(3))
                frame[y * N + x] = over((0, 0, 0, 0), col, a)


def shine(frame, t, N):
    # a rounded frame whose light sweeps diagonally
    band = -N + t * 3 * N
    for y in range(N):
        for x in range(N):
            ring = min(x, y, N - 1 - x, N - 1 - y)
            if ring > 1 or (x in (0, N - 1) and y in (0, N - 1)):
                continue
            light = math.exp(-((x + y) - band) ** 2 / 18.0)
            col = tuple(round(BLUE[i] + (255 - BLUE[i]) * light) for i in range(3))
            frame[y * N + x] = over((0, 0, 0, 0), col, 0.75 + 0.25 * light if ring == 0 else 0.35 + 0.4 * light)


def main():
    contents = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents"
    out = os.path.join(contents, "fluxdraw_fonts/resourcepack/assets")
    banner(out)
    strip(out, "fluxdraw_spark", 16, 2, spark)
    strip(out, "fluxdraw_shine", 20, 2, shine)

    print("written in", out)


if __name__ == "__main__":
    main()
