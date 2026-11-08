"""Life bar shown above dungeon monsters: a red bar with the monster's life written inside in QuinqueFive (the 5 px
font of the menus). Writes a font of its own into an ItemsAdder pack folder:

    <pack>/resourcepack/assets/fluxdungeons/font/health.json
    <pack>/resourcepack/assets/fluxdungeons/textures/font/health_bar.png      (one picture per step, STEPS + 1)
    <pack>/resourcepack/assets/fluxdungeons/textures/font/health_digits.png   (0-9, K, M)

    python3 gen_healthbar.py <ItemsAdder/contents/fluxdungeons>      (needs Pillow)

Chars of the font: U+E000 + step for the bar, '0'-'9' 'K' 'M' for the life, U+F001-U+F007 move back 1, 2, 4 ... 64
pixels, U+F011-U+F017 move forward as much. FluxDungeons (mob/Mobs.java) writes the text with the same numbers: keep
STEPS, BAR_W and the digit width (5) in line with it. The pictures may be retouched by hand (same size).
"""
import json
import os
import sys

from PIL import Image

STEPS = 32
BAR_W, BAR_H = 62, 8
FILL_X0, FILL_X1, FILL_Y0, FILL_Y1 = 1, 60, 1, 6   # inclusive: a 1 px outline around 6 rows of fill
BAR_ASCENT = 7

OUTLINE = (13, 13, 13, 255)
EMPTY = (52, 12, 20, 255)
FILL = [(255, 150, 150, 255), (255, 92, 92, 255), (247, 78, 82, 255), (236, 64, 74, 255), (222, 52, 66, 255),
        (200, 37, 58, 255)]                                                      # top to bottom, 6 rows
EDGE = (255, 205, 205, 255)
DIGIT, DIGIT_SHADOW = (255, 255, 255, 255), (70, 0, 12, 255)

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "..", "Flux", "assets", "metiers", "quinquefive.json")


def bar(step):
    im = Image.new("RGBA", (BAR_W, BAR_H), (0, 0, 0, 0))
    px = im.load()
    for x in range(BAR_W):
        for y in range(BAR_H):
            corner = (x in (0, BAR_W - 1)) and (y in (0, BAR_H - 1))
            if corner:
                continue
            if x in (0, BAR_W - 1) or y in (0, BAR_H - 1):
                px[x, y] = OUTLINE
            else:
                px[x, y] = EMPTY
    width = FILL_X1 - FILL_X0 + 1
    filled = round(width * step / STEPS)
    for i in range(filled):
        x = FILL_X0 + i
        for r, y in enumerate(range(FILL_Y0, FILL_Y1 + 1)):
            px[x, y] = FILL[r]
    if 0 < filled < width:
        x = FILL_X0 + filled - 1
        for y in range(FILL_Y0, FILL_Y1 + 1):
            px[x, y] = EDGE
    return im


def digits():
    font = json.load(open(FONT))
    chars = "0123456789KM"
    cell_w, cell_h = 5, 6
    im = Image.new("RGBA", (cell_w * len(chars), cell_h), (0, 0, 0, 0))
    px = im.load()
    for i, c in enumerate(chars):
        g = font[c]
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[i * cell_w + dx, dy + 1] = DIGIT_SHADOW
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[i * cell_w + dx, dy] = DIGIT
    return im, chars


def main(pack):
    root = os.path.join(pack, "resourcepack", "assets", "fluxdungeons")
    tex = os.path.join(root, "textures", "font")
    os.makedirs(tex, exist_ok=True)
    os.makedirs(os.path.join(root, "font"), exist_ok=True)
    sheet = Image.new("RGBA", (BAR_W * (STEPS + 1), BAR_H), (0, 0, 0, 0))
    for s in range(STEPS + 1):
        sheet.paste(bar(s), (s * BAR_W, 0))
    sheet.save(os.path.join(tex, "health_bar.png"))
    dig, chars = digits()
    dig.save(os.path.join(tex, "health_digits.png"))
    back = {chr(0xF001 + i): -(1 << i) for i in range(7)}
    fwd = {chr(0xF011 + i): (1 << i) for i in range(7)}
    font = {"providers": [
        {"type": "space", "advances": {**back, **fwd}},
        {"type": "bitmap", "file": "fluxdungeons:font/health_bar.png", "ascent": BAR_ASCENT, "height": BAR_H,
         "chars": ["".join(chr(0xE000 + s) for s in range(STEPS + 1))]},
        {"type": "bitmap", "file": "fluxdungeons:font/health_digits.png", "ascent": BAR_ASCENT - FILL_Y0, "height": 6,
         "chars": [chars]},
    ]}
    with open(os.path.join(root, "font", "health.json"), "w", encoding="utf-8") as f:
        json.dump(font, f, ensure_ascii=True, indent=1)
    print("written:", root)


if __name__ == "__main__":
    main(sys.argv[1])
