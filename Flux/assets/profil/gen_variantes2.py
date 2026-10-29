"""Profile menu, round 2 of proposals (2026-10-29): the whole menu IS the identity card.

Colours: the generic theme's violets only (read from menu_generique.png), white / off-white, the charter's gold
(chip) and pink (small details). Slot cases: the original case of the theme, untinted. Kept from round 1 « classique »:
light card body, gold chip, the signature.
Layout unchanged: row 0 back (0), title, menu icon (8) in the header band; photo = the 3D bust (columns 0 to 2, rows 1
to 3); 8 sections in columns 4 to 7, rows 2 and 3; signature under the photo (row 4).

    <venv with Pillow>/python3 gen_variantes2.py <out dir> [bust preview png]
"""
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import poser, tampon_case, text, text_width, title  # noqa: E402

W, H = 176, 221
WHITE = (255, 255, 255, 255)
# generic theme (menu_generique.png)
OUTLINE, RIM, MID = (21, 17, 27, 255), (127, 102, 163, 255), (89, 70, 116, 255)
DARK = [(63, 50, 83, 255), (58, 46, 76, 255), (52, 41, 66, 255), (47, 37, 60, 255), (44, 35, 57, 255),
        (40, 31, 51, 255), (35, 28, 46, 255), (31, 24, 40, 255)]
PAPER, PAPER_LINE, PAPER_SHADE = (240, 236, 245, 255), (226, 219, 234, 255), (214, 205, 226, 255)
GOLD, GOLD_DARK, GOLD_LIGHT, PINK = (240, 168, 0, 255), (138, 90, 20, 255), (255, 217, 77, 255), (224, 96, 160, 255)
SECTIONS = (22, 23, 24, 25, 31, 32, 33, 34)
PHOTO = (6, 34, 62, 90)
CARD = (0, 0, 175, 113)


def objet(case):
    return 8 + 18 * (case % 9), 18 + 18 * (case // 9)


def new():
    return Image.new("RGBA", (W, H), (0, 0, 0, 0))


def card(im, body, radius=8):
    """The menu's own outline: a rounded card, outline + light rim of the theme."""
    d = ImageDraw.Draw(im)
    d.rounded_rectangle(CARD, radius=radius, fill=OUTLINE)
    d.rounded_rectangle((CARD[0] + 1, CARD[1] + 1, CARD[2] - 1, CARD[3] - 1), radius=radius - 1, fill=RIM)
    d.rounded_rectangle((CARD[0] + 2, CARD[1] + 2, CARD[2] - 2, CARD[3] - 2), radius=radius - 2, fill=body)
    return d


def header(im, d, name, h=33):
    """Dark band of the theme across the top (row 0: back, title, icon), cut to the card's rounded corners, with a
    light rim under it."""
    band = Image.new("RGBA", im.size, (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    for y in range(2, h):
        bd.line((2, y, CARD[2] - 2, y), fill=DARK[min(len(DARK) - 1, (y - 2) * len(DARK) // max(1, h - 2))])
    bd.line((2, h, CARD[2] - 2, h), fill=RIM)
    bd.line((2, h + 1, CARD[2] - 2, h + 1), fill=OUTLINE)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((CARD[0] + 2, CARD[1] + 2, CARD[2] - 2, CARD[3] - 2), radius=6, fill=255)
    im.paste(band, (0, 0), Image.composite(band, Image.new("RGBA", im.size, (0, 0, 0, 0)), mask).getchannel("A"))
    title(im, name, objet(0)[1])


def cases(im):
    case = tampon_case()
    for s in SECTIONS:
        poser(im, case, *objet(s))


def guilloche(d, box, colour, step=4, amp=1.6):
    x0, y0, x1, y1 = box
    for y in range(y0 + 2, y1 - 1, step):
        d.line([(x, y + amp * math.sin((x + 2 * y) / 6.0)) for x in range(x0 + 1, x1)], fill=colour)


def signature(d, x, y, colour):
    pts = [(x + i, y + 3 * math.sin(i / 2.2) * (1 - i / 60) + (2 if 18 < i < 26 else 0)) for i in range(0, 46)]
    d.line(pts, fill=colour, width=1)
    d.line((x + 30, y + 5, x + 46, y + 2), fill=colour)


def chip(d, x, y):
    d.rounded_rectangle((x, y, x + 13, y + 10), radius=2, fill=GOLD, outline=GOLD_DARK)
    for yy in (y + 3, y + 7):
        d.line((x + 1, yy, x + 12, yy), fill=(200, 140, 20, 255))
    d.line((x + 6, y + 1, x + 6, y + 9), fill=(200, 140, 20, 255))
    d.rectangle((x + 4, y + 4, x + 8, y + 6), fill=GOLD_LIGHT)


def star(d, cx, cy, r, colour, outline=None):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=colour, outline=outline)


def photo(d, fill, frame=OUTLINE, rim=RIM):
    x0, y0, x1, y1 = PHOTO
    d.rectangle((x0, y0, x1, y1), fill=fill, outline=frame)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), outline=rim)


def label(im, word, cx, y, colour=WHITE):
    text(im, word, cx, y, colour=colour)


# ---------------------------------------------------------------- variants

def v1():
    """White card: theme header band, guilloche paper, chip, hologram star, signature line."""
    im = new()
    d = card(im, PAPER)
    guilloche(d, (3, 36, 172, 111), PAPER_LINE)
    header(im, d, "Carte d'identité")
    photo(d, PAPER_SHADE)
    star(d, 59, 87, 5, GOLD_LIGHT, PINK)
    d.line((8, 106, 60, 106), fill=PAPER_SHADE)
    signature(d, 10, 99, DARK[0])
    chip(d, 154, 94)
    cases(im)
    return im


def v2():
    """Split card: the photo side in the theme's dark violet, the data side white; chip on the seam."""
    im = new()
    d = card(im, PAPER)
    guilloche(d, (68, 36, 172, 111), PAPER_LINE)
    for y in range(34, 112):
        col = DARK[min(len(DARK) - 1, (y - 34) * len(DARK) // 78)]
        d.line((2, y, 66, y), fill=col)
    d.line((67, 34, 67, 111), fill=RIM)
    d.line((68, 34, 68, 111), fill=OUTLINE)
    header(im, d, "Carte d'identité")
    photo(d, DARK[2], OUTLINE, MID)
    signature(d, 10, 99, PAPER)
    chip(d, 61, 94)
    star(d, 160, 99, 5, GOLD_LIGHT, PINK)
    cases(im)
    return im


def v3():
    """White card crossed by a diagonal sash in the theme's violets, crown watermark."""
    im = new()
    d = card(im, PAPER)
    guilloche(d, (3, 36, 172, 111), PAPER_LINE, step=5)
    for k, col in ((0, MID), (6, RIM), (8, MID)):
        d.polygon([(100 + k, 111), (110 + k, 111), (175, 50 + k), (175, 40 + k)], fill=col)
    header(im, d, "Carte de joueur")
    photo(d, PAPER_SHADE)
    # crown watermark
    d.polygon([(118, 104), (118, 96), (122, 100), (127, 93), (132, 100), (136, 96), (136, 104)], fill=PAPER_SHADE)
    signature(d, 10, 99, DARK[0])
    chip(d, 154, 94)
    cases(im)
    return im


def v4():
    """White card with a thick theme border (the generic frame redrawn as the card's plastic edge), corner notch."""
    im = new()
    d = ImageDraw.Draw(im)
    d.rounded_rectangle(CARD, radius=10, fill=OUTLINE)
    d.rounded_rectangle((1, 1, 174, 112), radius=9, fill=RIM)
    d.rounded_rectangle((2, 2, 173, 111), radius=8, fill=MID)
    d.rounded_rectangle((4, 4, 171, 109), radius=7, fill=DARK[3])
    d.rounded_rectangle((5, 35, 170, 108), radius=5, fill=PAPER, outline=OUTLINE)
    guilloche(d, (6, 37, 169, 107), PAPER_LINE)
    title(im, "Carte d'identité", objet(0)[1])
    d.polygon([(150, 109), (171, 88), (171, 109)], fill=DARK[3])
    photo(d, PAPER_SHADE)
    signature(d, 10, 98, DARK[0])
    chip(d, 72, 94)
    star(d, 160, 96, 5, GOLD_LIGHT, PINK)
    cases(im)
    return im


def v5():
    """Theme-dark card (like the other menus) with white « paper » fields: photo, sections and signature on white."""
    im = new()
    d = card(im, DARK[4])
    for y in range(34, 112):
        d.line((2, y, CARD[2] - 2, y), fill=DARK[min(len(DARK) - 1, 2 + (y - 34) * 6 // 78)])
    header(im, d, "Carte de joueur")
    d.rounded_rectangle((4, 32, 64, 92), radius=3, fill=PAPER, outline=OUTLINE)
    photo(d, PAPER_SHADE)
    d.rounded_rectangle((4, 95, 64, 109), radius=3, fill=PAPER, outline=OUTLINE)
    signature(d, 10, 98, DARK[0])
    x0, y0 = objet(22)
    x1, y1 = objet(34)
    d.rounded_rectangle((x0 - 4, y0 - 4, x1 + 19, y1 + 19), radius=4, fill=PAPER, outline=OUTLINE)
    chip(d, 154, 94)
    star(d, 160, 44, 5, GOLD_LIGHT, PINK)
    cases(im)
    return im


VARIANTS = [("1", v1), ("2", v2), ("3", v3), ("4", v4), ("5", v5)]


if __name__ == "__main__":
    out = sys.argv[1]
    bust = Image.open(sys.argv[2]).convert("RGBA") if len(sys.argv) > 2 else None
    tiles = []
    for name, fn in VARIANTS:
        im = fn()
        im.save(os.path.join(out, "menu_profil_r2_%s.png" % name))
        view = Image.new("RGBA", (W, 116), (60, 60, 60, 255))
        view.alpha_composite(im.crop((0, 0, W, 116)))
        if bust is not None:
            b = bust.resize((bust.width // 4, bust.height // 4), Image.NEAREST)
            bg = b.getpixel((0, 0))
            m = Image.new("RGBA", b.size, (0, 0, 0, 0))
            for y in range(b.height):
                for x in range(b.width):
                    p = b.getpixel((x, y))
                    if p[:3] != bg[:3]:
                        m.putpixel((x, y), p)
            view.alpha_composite(m, (34 - b.width // 2, 62 - b.height // 2))
        tiles.append((name, view.resize((W * 3, 116 * 3), Image.NEAREST)))
    sheet = Image.new("RGBA", (W * 3 * 2 + 30, (116 * 3 + 40) * 3), (20, 16, 28, 255))
    for i, (name, t) in enumerate(tiles):
        x, y = (i % 2) * (W * 3 + 30), (i // 2) * (116 * 3 + 40)
        sheet.alpha_composite(t, (x, y + 30))
        lbl = Image.new("RGBA", (text_width(name) + 2, 7), (0, 0, 0, 0))
        text(lbl, name, lbl.width // 2, 0, colour=WHITE)
        sheet.alpha_composite(lbl.resize((lbl.width * 3, lbl.height * 3), Image.NEAREST), (x + 6, y + 4))
    sheet.save(os.path.join(out, "variantes_profil_2.png"))
    print("variantes_profil_2.png ->", out)
