#!/usr/bin/env python3
"""Pictures of the dungeon selector page (FluxDungeons pages.DungeonPages), drawn here from our own boss previews.

For each dungeon of DUNGEONS: its boss (first view of assets/monstres/<boss>_apercu.png, background cut out) standing
in front of a doorway tinted with the dungeon's colour, as chunky pixel art (drawn at half size, scaled twice, nearest
neighbour: the page engine draws pictures as they are). Writes into src/main/resources/pages/dungeons/ :
  <id>.png       320 x 256, the picture in the middle of the page
  <id>_icon.png  48 x 48, the icon of the list
  default.png, default_icon.png  for a dungeon without a picture (an empty doorway)
A new dungeon: add a line to DUNGEONS, run the script, rebuild the plugin. Needs Pillow (a venv):
<venv>/python3 gen_dungeon_art.py
"""
import os
import random
from collections import deque

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PREVIEWS = os.path.normpath(os.path.join(HERE, "..", "monstres"))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "pages", "dungeons"))

# dungeon id -> (boss preview, colour of the dungeon's name)
DUNGEONS = {
    "roche": ("colosse_abime", "#B8B2AA"),
    "moisson": ("hamster_moissonneur", "#F0A800"),
    "pecheur": ("loutre_marees", "#43B7B0"),
    "mineur": ("trex_cuivre", "#E3826C"),
    "miner": ("salamandrex", "#70CC98"),
    "defenseur": ("ossacorne", "#C8C8C8"),
    "enchanteur": ("evocateur_demesure", "#B376E5"),
    "herboriste": ("renard_sylvestre", "#83A85A"),
    "sylviculteur": ("rapace_liane", "#8C6A3E"),
    "taupe": ("taupe_champs", "#96765A"),
}
# previews laid out on several rows: the size of one view
CELL = {"salamandrex": 420}

W, H = 160, 128     # the picture, before the x2 scale
ICON = 24


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def cutout(boss):
    """The first view of a preview sheet, its flat background made transparent (flood from the borders), trimmed."""
    sheet = Image.open(os.path.join(PREVIEWS, boss + "_apercu.png")).convert("RGBA")
    c = CELL.get(boss, sheet.height)
    im = sheet.crop((0, 0, c, c))
    px = im.load()
    bg = px[0, 0][:3]

    def near(p):
        return abs(p[0] - bg[0]) + abs(p[1] - bg[1]) + abs(p[2] - bg[2]) <= 14

    seen = [[False] * c for _ in range(c)]
    queue = deque()
    for i in range(c):
        for x, y in ((i, 0), (i, c - 1), (0, i), (c - 1, i)):
            if not seen[y][x] and near(px[x, y]):
                seen[y][x] = True
                queue.append((x, y))
    while queue:
        x, y = queue.popleft()
        px[x, y] = (0, 0, 0, 0)
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < c and 0 <= ny < c and not seen[ny][nx] and near(px[nx, ny]):
                seen[ny][nx] = True
                queue.append((nx, ny))
    return im.crop(im.getbbox())


def fit(im, w, h):
    """Scaled down to fit w x h, edges kept hard (no half transparent pixel)."""
    k = min(w / im.width, h / im.height)
    small = im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)
    px = small.load()
    for y in range(small.height):
        for x in range(small.width):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255) if a >= 128 else (0, 0, 0, 0)
    return small


def scene(color, seed):
    """A hall of dark stone tinted with the colour: back wall, a lit doorway between two pillars, a floor."""
    r = random.Random(seed)
    dark, tint = (16, 17, 24), rgb(color)
    im = Image.new("RGBA", (W, H))
    px = im.load()
    floor = 96
    for y in range(H):
        for x in range(W):
            if y < floor:
                # wall: bricks 16 x 8, offset one row in two
                row = y // 8
                bx = (x + (8 if row % 2 else 0)) % 16
                base = mix(dark, tint, 0.16 + 0.10 * (y / floor))
                if y % 8 == 7 or bx == 15:
                    base = mix(base, (0, 0, 0), 0.45)
                elif r.random() < 0.08:
                    base = mix(base, tint, 0.10)
            else:
                # floor: slabs seen from above, lighter towards the viewer
                base = mix(dark, tint, 0.26 + 0.20 * ((y - floor) / (H - floor)))
                if (y - floor) % 8 == 0 or (x + (y - floor) // 8 * 10) % 24 == 0:
                    base = mix(base, (0, 0, 0), 0.4)
            px[x, y] = base + (255,)
    d = ImageDraw.Draw(im)
    # the doorway: a dark frame, a glow of the dungeon's colour inside
    d.rectangle((52, 18, 107, floor - 1), fill=mix(dark, tint, 0.10) + (255,))
    for i in range(22):
        t = i / 21
        d.rectangle((58 + i // 3, 24 + i * 3, 101 - i // 3, floor - 1), fill=mix(mix(dark, tint, 0.34), tint, 0.5 * t * t) + (255,))
    # pillars and lintel
    for x0 in (40, 108):
        d.rectangle((x0, 10, x0 + 11, floor + 3), fill=mix(dark, tint, 0.34) + (255,))
        d.rectangle((x0, 10, x0 + 1, floor + 3), fill=mix(dark, tint, 0.50) + (255,))
        d.rectangle((x0 + 10, 10, x0 + 11, floor + 3), fill=mix(dark, tint, 0.18) + (255,))
        d.rectangle((x0 - 2, floor, x0 + 13, floor + 3), fill=mix(dark, tint, 0.40) + (255,))
    d.rectangle((36, 6, 123, 13), fill=mix(dark, tint, 0.38) + (255,))
    d.rectangle((36, 6, 123, 7), fill=mix(dark, tint, 0.54) + (255,))
    d.rectangle((36, 12, 123, 13), fill=mix(dark, tint, 0.18) + (255,))
    # light on the floor in front of the door
    for i in range(10):
        d.rectangle((56 - i * 2, floor + 4 + i * 2, 103 + i * 2, floor + 5 + i * 2), fill=mix(mix(dark, tint, 0.36), tint, 0.22 * (1 - i / 10)) + (255,))
    # darker corners
    for y in range(H):
        for x in range(W):
            edge = max(abs(x - W / 2) / (W / 2), abs(y - H / 2) / (H / 2))
            if edge > 0.72:
                px[x, y] = mix(px[x, y][:3], (0, 0, 0), min(0.55, (edge - 0.72) * 1.6)) + (255,)
    return im


def picture(boss, color, seed):
    im = scene(color, seed)
    if boss:
        cut = fit(cutout(boss), 112, 84)
        x, base = (W - cut.width) // 2, 116
        shadow = ImageDraw.Draw(im)
        shadow.ellipse((x + 4, base - 5, x + cut.width - 4, base + 4), fill=(0, 0, 0, 255))
        im.alpha_composite(cut, (x, base - cut.height))
    return im.resize((W * 2, H * 2), Image.NEAREST)


def icon(boss, color):
    im = Image.new("RGBA", (ICON, ICON), (0, 0, 0, 0))
    if boss:
        cut = fit(cutout(boss), ICON, ICON)
        im.alpha_composite(cut, ((ICON - cut.width) // 2, ICON - cut.height - (ICON - cut.height) // 2))
    else:
        # a small doorway
        d, tint = ImageDraw.Draw(im), rgb(color)
        d.rectangle((4, 3, 19, 22), fill=mix((16, 17, 24), tint, 0.45) + (255,))
        d.rectangle((7, 7, 16, 22), fill=mix((16, 17, 24), tint, 0.9) + (255,))
        d.rectangle((2, 1, 21, 4), fill=mix((16, 17, 24), tint, 0.6) + (255,))
    return im.resize((ICON * 2, ICON * 2), Image.NEAREST)


def main():
    os.makedirs(OUT, exist_ok=True)
    for n, (dungeon, (boss, color)) in enumerate(sorted(DUNGEONS.items())):
        picture(boss, color, 100 + n).save(os.path.join(OUT, dungeon + ".png"))
        icon(boss, color).save(os.path.join(OUT, dungeon + "_icon.png"))
        print("wrote", dungeon)
    picture(None, "#8A8A92", 1).save(os.path.join(OUT, "default.png"))
    icon(None, "#8A8A92").save(os.path.join(OUT, "default_icon.png"))


if __name__ == "__main__":
    main()
