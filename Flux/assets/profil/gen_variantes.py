"""Five background proposals for the profile menu as a player's identity card (2026-10-29).

Same layout for all: row 0 back (0), menu icon (8) and the title; photo (the player's 3D bust: slots 18, 19, 20 and
28, drawn over columns 0 to 2, rows 1 to 3); 8 sections in columns 4 to 7, rows 2 and 3 (slots 22-25, 31-34).
Typography always white (on a dark band when the card is light).

    <venv with Pillow>/python3 gen_variantes.py <out dir> [bust preview png]
"""
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import generique_raccourci, poser, tampon_case, text, text_width, title  # noqa: E402

W, H = 176, 221
BOTTOM = 118
WHITE = (255, 255, 255, 255)
SECTIONS = (22, 23, 24, 25, 31, 32, 33, 34)
PHOTO = (6, 34, 62, 90)  # x0, y0, x1, y1 around slots 9-11 / 27-29 (bust)


def c(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def objet(case):
    return 8 + 18 * (case % 9), 18 + 18 * (case // 9)


def base(name):
    im = generique_raccourci(os.path.join(os.path.dirname(HERE), "menu_generique.png"), 0, BOTTOM)
    title(im, name, objet(0)[1])
    return im


def cases(im, colour=None):
    case = tampon_case()
    if colour:
        col = c(colour)
        shades = sorted(set(case.values()), key=lambda v: sum(v[:3]))
        fill = max(shades, key=lambda v: list(case.values()).count(v))
        table = {fill: mix((20, 15, 28, 255), col, .25)}
        rims = [v for v in shades if v != fill]
        for i, v in enumerate(rims):
            table[v] = mix((20, 15, 28, 255), col, i / max(1, len(rims) - 1))
        case = {k: table[v] for k, v in case.items()}
    for s in SECTIONS:
        poser(im, case, *objet(s))


def star(d, cx, cy, r, colour, inner=None):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=colour, outline=inner)


def crown(d, x, y, colour, dark):
    """Small charter crown (12 x 8)."""
    d.polygon([(x, y + 8), (x, y + 2), (x + 3, y + 5), (x + 6, y), (x + 9, y + 5), (x + 12, y + 2), (x + 12, y + 8)], fill=colour, outline=dark)
    for px in (x, x + 6, x + 12):
        d.point((px, y + (0 if px == x + 6 else 2) - 1), fill=c("ffd94d"))


def chip(d, x, y):
    """Gold smart-card chip (14 x 11)."""
    d.rounded_rectangle((x, y, x + 13, y + 10), radius=2, fill=c("e8b13a"), outline=c("8a5a14"))
    for yy in (y + 3, y + 7):
        d.line((x + 1, yy, x + 12, yy), fill=c("b8861c"))
    d.line((x + 6, y + 1, x + 6, y + 9), fill=c("b8861c"))
    d.rectangle((x + 4, y + 4, x + 8, y + 6), fill=c("ffd94d"))


def guilloche(d, box, colour, step=4, amp=2.0):
    x0, y0, x1, y1 = box
    for y in range(y0 + 2, y1 - 1, step):
        pts = [(x, y + amp * math.sin((x + y) / 5.0)) for x in range(x0 + 2, x1 - 1)]
        d.line(pts, fill=colour)


def signature(d, x, y, colour):
    pts = [(x + i, y + 3 * math.sin(i / 2.2) * (1 - i / 60) + (2 if 18 < i < 26 else 0)) for i in range(0, 46)]
    d.line(pts, fill=colour, width=1)
    d.line((x + 30, y + 5, x + 46, y + 2), fill=colour)


def barcode(d, x, y, w, h, colour):
    import random
    rnd = random.Random(7)
    xx = x
    while xx < x + w:
        bw = rnd.choice((1, 1, 2))
        d.rectangle((xx, y, xx + bw - 1, y + h), fill=colour)
        xx += bw + rnd.choice((1, 1, 2))


def photo_frame(d, outer, inner, fill):
    x0, y0, x1, y1 = PHOTO
    d.rectangle((x0, y0, x1, y1), fill=fill, outline=outer)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), outline=inner)


# ---------------------------------------------------------------- the five variants

def v1_classique():
    """Light lavender ID card, violet header band, guilloche, gold chip, hologram star, crown watermark, signature."""
    im = base("Carte d'identité")
    d = ImageDraw.Draw(im)
    card = (3, 31, 172, 112)
    d.rounded_rectangle(card, radius=6, fill=c("e7dcf6"), outline=c("3a1f6b"))
    d.rounded_rectangle((card[0] + 1, card[1] + 1, card[2] - 1, card[3] - 1), radius=5, outline=c("b376e5"))
    guilloche(d, (66, 52, 170, 110), c("d6c6ef"))
    d.rounded_rectangle((66, 34, 169, 50), radius=3, fill=c("5b2d9e"))
    text(im, "Influencraft", 117, 36, colour=WHITE)
    text(im, "Joueur", 117, 44, colour=WHITE)
    photo_frame(d, c("3a1f6b"), c("b376e5"), c("cbb8ea"))
    signature(d, 10, 98, c("3a1f6b"))
    chip(d, 152, 94)
    star(d, 58, 88, 6, c("ffd94d"), c("e060a0"))
    crown(d, 122, 96, c("d9c8f2"), c("cbb8ea"))
    cases(im, "5b2d9e")
    return im


def v2_passeport():
    """Open passport: two cream pages, fold in the middle, violet cover edges, visa stamps."""
    im = base("Passeport")
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 30, 173, 113), radius=4, fill=c("3a1f6b"))
    d.rectangle((5, 33, 86, 110), fill=c("f4ecdd"))
    d.rectangle((89, 33, 170, 110), fill=c("f4ecdd"))
    for i, col in enumerate(("c9bda8", "ddd1bd", "ebe1cf")):
        d.line((86 - i, 33, 86 - i, 110), fill=c(col))
        d.line((89 + i, 33, 89 + i, 110), fill=c(col))
    guilloche(d, (5, 33, 86, 110), c("e6dac6"), step=5)
    guilloche(d, (89, 33, 170, 110), c("e6dac6"), step=5)
    d.rectangle((92, 35, 167, 49), fill=c("5b2d9e"))
    text(im, "Influencraft", 129, 36, colour=WHITE)
    text(im, "Joueur", 129, 44, colour=WHITE)
    photo_frame(d, c("3a1f6b"), c("b376e5"), c("ddd0f0"))
    # visa stamps
    d.ellipse((64, 92, 82, 108), outline=c("c8256a"))
    d.ellipse((66, 94, 80, 106), outline=c("c8256a"))
    star(d, 73, 100, 4, c("c8256a"))
    d.rounded_rectangle((8, 94, 42, 106), radius=2, fill=c("5b2d9e"), outline=c("3a1f6b"))
    text(im, "Valide", 25, 97, colour=WHITE)
    cases(im, "5b2d9e")
    return im


def v3_neon():
    """Dark holographic card: neon frame violet to pink to cyan, holo stripes, glowing crown, chip."""
    im = base("Carte de joueur")
    d = ImageDraw.Draw(im)
    card = (3, 31, 172, 112)
    d.rounded_rectangle(card, radius=6, fill=c("181024"))
    # neon frame: gradient along the perimeter
    stops = [c("b376e5"), c("e060a0"), c("2bc4e8"), c("b376e5")]
    per = []
    x0, y0, x1, y1 = card
    per += [(x, y0) for x in range(x0 + 4, x1 - 3)] + [(x1, y) for y in range(y0 + 4, y1 - 3)]
    per += [(x, y1) for x in range(x1 - 4, x0 + 3, -1)] + [(x0, y) for y in range(y1 - 4, y0 + 3, -1)]
    for i, (x, y) in enumerate(per):
        t = i / len(per) * 3
        k = int(t)
        col = mix(stops[k], stops[k + 1], t - k)
        d.point((x, y), fill=col)
        d.point((x + (1 if x == x0 else -1 if x == x1 else 0), y + (1 if y == y0 else -1 if y == y1 else 0)), fill=mix(col, (24, 16, 36, 255), .5))
    for k in range(-80, 200, 14):
        d.line((k, 112, k + 80, 31), fill=c("231637"))
    text(im, "Influencraft", 110, 38, colour=WHITE)
    crown(d, 154, 36, c("f0a800"), c("8a5a14"))
    photo_frame(d, c("e060a0"), c("2bc4e8"), c("20152f"))
    chip(d, 152, 94)
    star(d, 70, 100, 5, c("ffd94d"))
    cases(im, "b376e5")
    return im


def v4_badge():
    """Staff-badge style: clip on top, gold header band, corner brackets on the photo, barcode."""
    im = base("Carte de joueur")
    d = ImageDraw.Draw(im)
    card = (3, 31, 172, 112)
    d.rounded_rectangle(card, radius=5, fill=c("2b1d45"), outline=c("0d0a12"))
    d.rounded_rectangle((card[0] + 1, card[1] + 1, card[2] - 1, card[3] - 1), radius=4, outline=c("5b2d9e"))
    d.rectangle((66, 33, 170, 50), fill=c("f0a800"))
    d.rectangle((66, 49, 170, 50), fill=c("8a5a14"))
    text(im, "Influencraft", 118, 35, colour=WHITE)
    text(im, "Membre", 118, 43, colour=WHITE)
    x0, y0, x1, y1 = PHOTO
    d.rectangle((x0, y0, x1, y1), fill=c("3a2a5c"))
    for (ax, ay, dx, dy) in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        d.line((ax, ay, ax + 6 * dx, ay), fill=c("ffd94d"), width=1)
        d.line((ax, ay, ax, ay + 6 * dy), fill=c("ffd94d"), width=1)
    barcode(d, 8, 95, 50, 10, c("e7dcf6"))
    d.rectangle((150, 94, 167, 108), fill=c("e7dcf6"))
    for yy in range(96, 107, 2):
        for xx in range(152, 166, 2):
            if (xx * 7 + yy * 3) % 5 < 3:
                d.point((xx, yy), fill=c("2b1d45"))
    cases(im, "f0a800")
    return im


def v5_amethyste():
    """Minecraft amethyst: deep violet card, amethyst block border and crystal clusters, item-frame photo."""
    im = base("Carte de joueur")
    d = ImageDraw.Draw(im)
    card = (3, 31, 172, 112)
    d.rectangle(card, fill=c("53379c"))
    # amethyst block border: 4 px checker of the three amethyst shades
    shades = [c("8d6acc"), c("cfa0f3"), c("6a4bb5"), c("b38ef3")]
    x0, y0, x1, y1 = card
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            if x - x0 < 3 or x1 - x < 3 or y - y0 < 3 or y1 - y < 3:
                d.point((x, y), fill=shades[((x // 2) * 3 + (y // 2) * 5) % 4])
    d.rectangle((x0 + 3, y0 + 3, x1 - 3, y1 - 3), fill=c("2a1a4e"))
    for k in range(0, 200, 6):
        d.line((x0 + 3 + k, y0 + 3, x0 + 3, y0 + 3 + k), fill=c("2f1e57"))
    d.rectangle((x0 + 3, y0 + 3, x1 - 3, y1 - 3), outline=c("1a0f33"))

    def cluster(cx, cy, flip=1):
        for dx, h, col in ((-3, 6, "8d6acc"), (0, 9, "cfa0f3"), (3, 5, "b38ef3")):
            d.polygon([(cx + dx - 1, cy), (cx + dx + 1, cy), (cx + dx, cy - h * flip)], fill=c(col))

    cluster(162, 108)
    cluster(70, 108)
    cluster(160, 40, -1)
    # item-frame photo (oak frame)
    x0p, y0p, x1p, y1p = PHOTO
    d.rectangle((x0p - 1, y0p - 1, x1p + 1, y1p + 1), fill=c("6b4a24"))
    d.rectangle((x0p + 1, y0p + 1, x1p - 1, y1p - 1), fill=c("9c6d36"))
    d.rectangle((x0p + 3, y0p + 3, x1p - 3, y1p - 3), fill=c("3b2a1a"))
    text(im, "Influencraft", 117, 37, colour=WHITE)
    text(im, "Joueur", 117, 45, colour=WHITE)
    cases(im, "cfa0f3")
    return im


VARIANTS = [("1 classique", v1_classique), ("2 passeport", v2_passeport), ("3 neon", v3_neon),
            ("4 badge", v4_badge), ("5 amethyste", v5_amethyste)]


if __name__ == "__main__":
    out = sys.argv[1]
    bust = Image.open(sys.argv[2]).convert("RGBA") if len(sys.argv) > 2 else None
    tiles = []
    for name, fn in VARIANTS:
        im = fn()
        im.save(os.path.join(out, "menu_profil_v%s.png" % name.split()[0]))
        view = im.copy()
        if bust is not None:
            b = bust.resize((bust.width // 4, bust.height // 4), Image.NEAREST)
            mask = Image.new("RGBA", b.size, (0, 0, 0, 0))
            bg = b.getpixel((0, 0))
            for y in range(b.height):
                for x in range(b.width):
                    p = b.getpixel((x, y))
                    if p[:3] != bg[:3]:
                        mask.putpixel((x, y), p)
            view.alpha_composite(mask, (34 - b.width // 2, 62 - b.height // 2))
        tiles.append((name, view.crop((0, 0, W, 116)).resize((W * 3, 116 * 3), Image.NEAREST)))
    sheet = Image.new("RGBA", (W * 3 * 2 + 30, (116 * 3 + 40) * 3), (20, 16, 28, 255))
    for i, (name, t) in enumerate(tiles):
        x, y = (i % 2) * (W * 3 + 30), (i // 2) * (116 * 3 + 40)
        sheet.alpha_composite(t, (x, y + 30))
        lbl = Image.new("RGBA", (text_width(name.upper()) + 2, 7), (0, 0, 0, 0))
        text(lbl, name, lbl.width // 2, 0, colour=WHITE)
        sheet.alpha_composite(lbl.resize((lbl.width * 3, lbl.height * 3), Image.NEAREST), (x + 6, y + 4))
    sheet.save(os.path.join(out, "variantes_profil.png"))
    print("variantes_profil.png ->", out)
