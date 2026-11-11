"""FluxPortal: the swirl filling a portal's hole (2026-11-11), in the server's one pack (ItemsAdder content folder).

Writes into <ItemsAdder/contents/fluxportal>:
    resourcepack/assets/fluxportal/items/surface.json            item model definition (fluxportal:surface)
    resourcepack/assets/fluxportal/models/item/surface.json      a flat square seen from both sides
    resourcepack/assets/fluxportal/textures/item/surface.png     FRAMES frames of SIZE x SIZE, one under the other
    resourcepack/assets/fluxportal/textures/item/surface.png.mcmeta

    <venv with Pillow>/python3 gen_portal.py <ItemsAdder/contents/fluxportal> [preview.png]   then /iazip

Pixel art, no gold: a disc of deep violet turning into a spiral of the server's violets and pinks, brighter towards a
pale rim, a few twinkling stars. The disc is round inside a square picture (the corners stay transparent) and a
little see-through, so the world behind still shows faintly.
"""
import json
import math
import os
import sys

from PIL import Image

SIZE = 32
FRAMES = 16
FRAMETIME = 2

DEEP = (30, 10, 60)        # #1E0A3C
VIOLET = (91, 45, 158)     # #5B2D9E
LIGHT = (179, 118, 229)    # #B376E5
PINK = (224, 96, 160)      # #E060A0
PALE = (236, 214, 255)
STAR = (255, 245, 255)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def frame(f):
    im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = im.load()
    c = (SIZE - 1) / 2
    rmax = SIZE / 2 - 0.5
    phase = 2 * math.pi * f / FRAMES
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = x - c, y - c
            r = math.hypot(dx, dy)
            if r > rmax:
                continue
            k = r / rmax
            a = math.atan2(dy, dx)
            # three arms winding inwards, turning with the frames
            arm = (math.sin(3 * a - 7 * k + phase) + 1) / 2
            arm = round(arm * 3) / 3                       # posterised: flat pixel-art bands
            base = mix(DEEP, VIOLET, k * k * 1.6)
            colour = mix(base, LIGHT if arm < 0.6 else PINK, arm * (0.1 + 0.9 * k * k))
            if k > 0.86:                                   # pale rim, under the particle ring
                colour = mix(colour, PALE, (k - 0.86) / 0.14 * 0.8)
            alpha = round(170 + 70 * k)
            px[x, y] = colour + (alpha,)
    # a few stars twinkling at fixed places, one frame on, one off
    for i, (sx, sy) in enumerate([(9, 11), (21, 8), (24, 19), (12, 23), (16, 15), (7, 18)]):
        if (f + i * 3) % 6 < 2 and math.hypot(sx - c, sy - c) < rmax - 2:
            px[sx, sy] = STAR + (255,)
    return im


def main(pack, preview=None):
    root = os.path.join(pack, "resourcepack", "assets", "fluxportal")
    for d in ("items", "models/item", "textures/item"):
        os.makedirs(os.path.join(root, d), exist_ok=True)
    strip = Image.new("RGBA", (SIZE, SIZE * FRAMES), (0, 0, 0, 0))
    for f in range(FRAMES):
        strip.paste(frame(f), (0, f * SIZE))
    strip.save(os.path.join(root, "textures", "item", "surface.png"))
    with open(os.path.join(root, "textures", "item", "surface.png.mcmeta"), "w") as fh:
        json.dump({"animation": {"frametime": FRAMETIME, "interpolate": False}}, fh)
    with open(os.path.join(root, "items", "surface.json"), "w") as fh:
        json.dump({"model": {"type": "minecraft:model", "model": "fluxportal:item/surface"}}, fh)
    face = {"uv": [0, 0, 16, 16], "texture": "#swirl"}
    model = {"textures": {"swirl": "fluxportal:item/surface", "particle": "fluxportal:item/surface"},
             "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "shade": False, "faces": {"north": face, "south": face}}]}
    with open(os.path.join(root, "models", "item", "surface.json"), "w") as fh:
        json.dump(model, fh, indent=1)
    if preview:
        row = Image.new("RGBA", (SIZE * 4, SIZE), (60, 60, 60, 255))
        for i, f in enumerate((0, 4, 8, 12)):
            fr = frame(f)
            row.paste(fr, (i * SIZE, 0), fr)
        row.resize((row.width * 8, row.height * 8), Image.NEAREST).save(preview)
    print("written:", root)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
