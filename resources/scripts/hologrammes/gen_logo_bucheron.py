#!/usr/bin/env python3
"""Source of the Bûcheron gate's logo (2026-11-16: the job Sylviculteur became Bûcheron): « DONJON » kept from the
former logo sources/donjon_sylviculteur_logo.png, the line under it drawn here in its style: blocky capitals leaning
right, a grass top (two blocks, lit edge), dirt below, a darker extrusion down-right and a dark outline, the colours
read from the former letters. Writes sources/donjon_bucheron_logo.png (same width and height as the former source),
read by gen_donjon_sylviculteur.py.

    <venv with Pillow>/python3 gen_logo_bucheron.py [--apercu image.png]
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
SRC = HERE / "sources" / "donjon_sylviculteur_logo.png"
OUT = HERE / "sources" / "donjon_bucheron_logo.png"
WORD = "BÛCHERON"
SPLIT = 122            # first row of the former word line (under DONJON)

# bold 6 x 7 capitals, strokes two blocks wide as the former logo's (rows top to bottom); Û: its accent in rows 0-1
FONT = {
    "B": ["#####.", "##..##", "##..##", "#####.", "##..##", "##..##", "#####."],
    "Û": ["..##..", ".#..#.", "##..##", "##..##", "##..##", "##..##", ".####."],
    "C": [".#####", "##....", "##....", "##....", "##....", "##....", ".#####"],
    "H": ["##..##", "##..##", "##..##", "######", "##..##", "##..##", "##..##"],
    "E": ["######", "##....", "##....", "#####.", "##....", "##....", "######"],
    "R": ["#####.", "##..##", "##..##", "#####.", "##.##.", "##..##", "##..##"],
    "O": [".####.", "##..##", "##..##", "##..##", "##..##", "##..##", ".####."],
    "N": ["##..##", "###.##", "######", "##.###", "##..##", "##..##", "##..##"],
}
LINE = (43, 4, 12, 255)
GRASS = [(44, 137, 4), (79, 152, 16), (121, 193, 15), (147, 206, 19)]
GRASS_LIGHT = (196, 236, 92)
DIRT = [(80, 33, 10), (102, 50, 8), (118, 62, 9), (134, 69, 17)]
SIDE_GRASS = (30, 92, 3)
SIDE_DIRT = (58, 24, 8)


def main():
    src = Image.open(SRC).convert("RGBA")
    W, H = src.size
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.alpha_composite(src.crop((0, 0, W, SPLIT)), (0, 0))
    r = random.Random(16)
    line_h = H - SPLIT - 8
    b = max(6, min((line_h - 14) // 7, (W - 40) // (len(WORD) * 7 + 2)))   # 7 rows + extrusion under DONJON, in the width
    shear = 0.16                            # lean to the right, per pixel of height
    ext = max(4, b // 3)                    # extrusion down-right
    pitch = 7 * b
    width = len(WORD) * pitch - b + int(7 * b * shear) + ext
    x0 = (W - width) // 2
    y0 = SPLIT + 4
    # cells of every letter in pixels (before the lean)
    cells = []
    for i, ch in enumerate(WORD):
        for ry, row in enumerate(FONT[ch]):
            for rx, c in enumerate(row):
                if c == "#":
                    cells.append((x0 + i * pitch + rx * b, y0 + ry * b, ry, ch))
    mask = Image.new("L", (W, H), 0)
    face = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    side = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    md, fd, sd = ImageDraw.Draw(mask), ImageDraw.Draw(face), ImageDraw.Draw(side)

    def lean(x, y):
        return x + int((y0 + 7 * b - y) * shear)

    for x, y, ry, ch in cells:
        for yy in range(y, y + b):
            xl = lean(x, yy)
            # extrusion first (behind), then the face
            sd.line((xl + ext, yy + ext, xl + b - 1 + ext, yy + ext), fill=SIDE_GRASS if ry < 2 else SIDE_DIRT)
            md.line((xl, yy, xl + b - 1 + ext, yy + ext), fill=255)
    for x, y, ry, ch in cells:
        top_rows = 3 if ch != "Û" else 5      # the grass covers the letter's three top rows (the U's under its accent)
        for yy in range(y, y + b):
            xl = lean(x, yy)
            for xx in range(xl, xl + b):
                k = (yy - y0) / (7 * b)
                if ry < top_rows:
                    c = GRASS[min(3, max(0, int((top_rows - ry - (yy - y) / b) / top_rows * 3 + (r.random() < 0.3))))]
                else:
                    c = DIRT[min(3, int((1 - k) * 3 + r.random() * 1.2))]
                fd.point((xx, yy), fill=c + (255,))
    face_px, side_px = face.load(), side.load()
    # lit top edge of the grass: the face's pixels with nothing of the face right above them
    for y in range(H - 1, 0, -1):
        for x in range(W):
            if face_px[x, y][3] and not face_px[x, y - 1][3] and face_px[x, y][:3] in [g for g in GRASS]:
                face_px[x, y] = GRASS_LIGHT + (255,)
    word = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    word.alpha_composite(side)
    word.alpha_composite(face)
    # dark outline, 3 px around everything drawn
    grown = mask
    from PIL import ImageFilter
    grown = mask.filter(ImageFilter.MaxFilter(7))
    outline = Image.new("RGBA", (W, H), LINE)
    outline.putalpha(grown)
    out.alpha_composite(outline)
    out.alpha_composite(word)
    out.save(OUT)
    print(f"{OUT} {W} x {H}, blocks of {b} px, word {width} px wide")
    if "--apercu" in sys.argv:
        prev = Image.new("RGBA", (W, H), (70, 70, 70, 255))
        prev.alpha_composite(out)
        prev.save(sys.argv[sys.argv.index("--apercu") + 1])


if __name__ == "__main__":
    main()
