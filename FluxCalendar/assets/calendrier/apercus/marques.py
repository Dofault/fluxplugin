"""Variants of the day marks (claimed / missed) on the Holographic Future calendar. Preview only."""
import os, sys
sys.argv = [sys.argv[0], sys.argv[1], os.path.join(sys.argv[2], "_tmp")] + sys.argv[3:]
OUT = os.path.dirname(sys.argv[2])
import themes12 as T
from PIL import Image, ImageDraw, ImageFont
g, ui, H = T.g, T.ui, T.H
BTN = "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents/fluxui/textures/btn/"


def btn(name, size=16):
    im = Image.open(BTN + name + ".png").convert("RGBA")
    if size == 16:
        return im
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    out.alpha_composite(im.resize((size, size), Image.NEAREST), (0, 0))
    return out


def chip(fill_a, fill_b, edge, symbol, ink, small=True):
    """Rounded square chip with a vertical gradient, symbol drawn in pixels (top-left 12 x 12, room for the count)."""
    s = 12 if small else 16
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for y in range(s):
        t = y / (s - 1)
        col = tuple(int(fill_a[i] + (fill_b[i] - fill_a[i]) * t) for i in range(3)) + (255,)
        d.line([(1 if y in (0, s - 1) else 0, y), (s - 2 if y in (0, s - 1) else s - 1, y)], fill=col)
    for x, y in ((1, 0), (s - 2, 0), (0, 1), (s - 1, 1), (0, s - 2), (s - 1, s - 2), (1, s - 1), (s - 2, s - 1)):
        im.putpixel((x, y), edge)
    d.line([(2, 0), (s - 3, 0)], fill=edge); d.line([(2, s - 1), (s - 3, s - 1)], fill=edge)
    d.line([(0, 2), (0, s - 3)], fill=edge); d.line([(s - 1, 2), (s - 1, s - 3)], fill=edge)
    d.line([(2, 1), (s - 3, 1)], fill=(255, 255, 255, 140))          # gloss
    if symbol == "tick":
        d.line([(3, 6), (5, 8), (9, 3)], fill=ink, width=2)
    else:
        d.line([(3, 3), (8, 8)], fill=ink, width=2); d.line([(8, 3), (3, 8)], fill=ink, width=2)
    return im


def gem(colours, broken=False):
    a, b, c = colours
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    rows = ["...#...", "..###..", ".#####.", "#######", ".#####.", "..###..", "...#..."]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                col = a if y < 3 else b if y < 5 else c
                if broken and (x + y) % 3 == 0:
                    continue
                im.putpixel((x + 3, y + 3), col)
    im.putpixel((5, 4), (255, 255, 255, 255))
    return im


def fill_cell(col, symbol=None, ink=None):
    """The whole case tinted (translucent over the slot), a small mark top-left."""
    im = Image.new("RGBA", (16, 16), col)
    if symbol:
        d = ImageDraw.Draw(im)
        if symbol == "tick":
            d.line([(2, 5), (4, 7), (8, 2)], fill=ink, width=1)
        else:
            d.line([(2, 2), (7, 7)], fill=ink); d.line([(7, 2), (2, 7)], fill=ink)
    return im


CYAN, VIOLET, MAG = H("#7FE8FF"), H("#7A6AF0"), H("#FF4FD8")
VARIANTS = [
    ("A Boutons Flux carrés (actuels)", btn("btn_check_green"), btn("btn_close_red")),
    ("B Boutons Flux carrés réduits", btn("btn_check_green", 11), btn("btn_close_red", 11)),
    ("C Boutons Flux réduits, couleurs holo", btn("btn_check_blue", 11), btn("btn_close_purple", 11)),
    ("D Puces holographiques", chip(H("#B8F6FF"), H("#3FA8E8"), H("#1A3A6A"), "tick", (255, 255, 255, 255)),
     chip(H("#5B4E8A"), H("#2A2450"), H("#14102A"), "cross", MAG)),
    ("E Gemmes (pleine / brisée)", gem((H("#E9FDFF"), CYAN, H("#2F7FB8"))), gem((H("#A9A0C8"), H("#6A6290"), H("#3A3460")), True)),
    ("F Case teintée", fill_cell((127, 232, 255, 110), "tick", (255, 255, 255, 255)),
     fill_cell((40, 20, 60, 150), "cross", MAG)),
]


def preview(bg, t, k, ok, ko):
    old = g.mark
    def mark(kind):
        return ok if kind == "tick" else ko if kind == "cross" else old(kind)
    g.mark = mark
    try:
        return T.preview(bg, t, k)
    finally:
        g.mark = old


if __name__ == "__main__":
    name, t = T.THEMES[10]
    bg = T.render(t)
    out = os.path.join(OUT, "marques")
    os.makedirs(out, exist_ok=True)
    shots = []
    for label, ok, ko in VARIANTS:
        preview(bg, t, 3, ok, ko).save(os.path.join(out, label.split()[0] + ".png"))
        shots.append((label, preview(bg, t, 2, ok, ko)))
    pw, ph = shots[0][1].size
    cols, gap, lab = 3, 24, 26
    board = Image.new("RGBA", (cols * pw + (cols + 1) * gap, 2 * (ph + lab) + 3 * gap), (44, 44, 48, 255))
    d = ImageDraw.Draw(board)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
    for i, (label, p) in enumerate(shots):
        x = gap + (i % cols) * (pw + gap); y = gap + (i // cols) * (ph + lab + gap)
        d.text((x, y), label, fill=(235, 235, 235, 255), font=font)
        board.alpha_composite(p, (x, y + lab))
    board.save(os.path.join(out, "planche_marques.png"))
    print("ok")

