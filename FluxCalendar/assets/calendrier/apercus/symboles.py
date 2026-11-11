"""V and X of the Flux buttons drawn alone (no square) on the Holographic calendar. Preview only."""
import os, sys
sys.argv = [sys.argv[0], sys.argv[1], os.path.join(sys.argv[2], "_tmp")]
OUT = os.path.dirname(sys.argv[2])
import themes12 as T
import marques as M
from PIL import Image, ImageDraw, ImageFont
g, H = T.g, T.H
CHECK = ["......#.", ".....##.", ".#..##..", ".####...", "..##....", "........"]
CLOSE = [".#....#.", "..#..#..", "...##...", "...##...", "..#..#..", ".#....#."]


def symbol(rows, fill, k=1, outline=None, shadow=None, light=None, at=(1, 1)):
    pts = {(x, y) for y, r in enumerate(rows) for x, c in enumerate(r) if c == "#"}
    big = {(x * k + i, y * k + j) for x, y in pts for i in range(k) for j in range(k)}
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    ox, oy = at
    def put(x, y, col):
        if 0 <= x + ox < 16 and 0 <= y + oy < 16:
            im.putpixel((x + ox, y + oy), col)
    if shadow:
        for x, y in big:
            put(x + 1, y + 1, shadow)
    if outline:
        for x, y in big:
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                if (x + dx, y + dy) not in big:
                    put(x + dx, y + dy, outline)
    for x, y in big:
        put(x, y, fill)
    if light:                                   # top pixel of each column lighter, as a gloss
        for x, y in big:
            if (x, y - 1) not in big:
                put(x, y, light)
    return im


GREEN, GREEN_D, RED, RED_D = H("#5DC73A"), H("#1F5A16"), H("#FF5C5C"), H("#6E1414")
CYAN, CYAN_L, CYAN_D = H("#7FE8FF"), H("#E9FDFF"), H("#1A3A6A")
MAG, MAG_L, MAG_D = H("#FF4FD8"), H("#FFC2F1"), H("#3A0A33")
WHITE, BLACK = H("#FFFFFF"), H("#000000")
VARIANTS = [
    ("A Petit, vert / rouge, contour", symbol(CHECK, GREEN, 1, GREEN_D), symbol(CLOSE, RED, 1, RED_D)),
    ("B Petit, holo cyan / rose", symbol(CHECK, CYAN, 1, CYAN_D, light=CYAN_L), symbol(CLOSE, MAG, 1, MAG_D, light=MAG_L)),
    ("C Grand, vert / rouge, contour", symbol(CHECK, GREEN, 2, GREEN_D, at=(0, 1)), symbol(CLOSE, RED, 2, RED_D, at=(0, 1))),
    ("D Grand, holo cyan / rose", symbol(CHECK, CYAN, 2, CYAN_D, light=CYAN_L, at=(0, 1)),
     symbol(CLOSE, MAG, 2, MAG_D, light=MAG_L, at=(0, 1))),
    ("E Grand, blanc / rose, ombre", symbol(CHECK, WHITE, 2, shadow=H("#3FA8E8"), at=(0, 1)),
     symbol(CLOSE, MAG, 2, shadow=MAG_D, at=(0, 1))),
    ("F Grand, plat sans contour", symbol(CHECK, CYAN, 2, at=(0, 1)), symbol(CLOSE, MAG, 2, at=(0, 1))),
]

name, t = T.THEMES[10]
bg = T.render(t)
out = os.path.join(OUT, "symboles")
os.makedirs(out, exist_ok=True)
shots = []
for label, ok, ko in VARIANTS:
    M.preview(bg, t, 3, ok, ko).save(os.path.join(out, label.split()[0] + ".png"))
    shots.append((label, M.preview(bg, t, 2, ok, ko)))
pw, ph = shots[0][1].size
cols, gap, lab = 3, 24, 26
board = Image.new("RGBA", (cols * pw + (cols + 1) * gap, 2 * (ph + lab) + 3 * gap), (44, 44, 48, 255))
d = ImageDraw.Draw(board)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
for i, (label, p) in enumerate(shots):
    x = gap + (i % cols) * (pw + gap); y = gap + (i // cols) * (ph + lab + gap)
    d.text((x, y), label, fill=(235, 235, 235, 255), font=font)
    board.alpha_composite(p, (x, y + lab))
board.save(os.path.join(out, "planche_symboles.png"))
print("ok")
