"""Offering menu backgrounds (FluxOffering, menus/offering.yml), one per rank: menu_offrande_<rank>.

The suite's chest (chest_6.png: generic violet panel, chest and inventory) with the player's inventory part untouched
(the offering menu shows it, so the player sees what he can deposit). In the chest part only the used cells stay:
  slot 0 back, slot 8 offering icon (first row rule) with the title « OFFRANDE » between them, 10-16 / 19-25 / 28-34 requested items (tinted with the rank's
  colour), 18 / 26 previous / next page (dark cells), 40 deposit all (gold), 49 filter.
A ribbon of the rank's colour runs behind the title row, with the stars of its tier.

Usage: python gen_fonds.py <output folder> [preview folder]  (Pillow)
"""
import os
import sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.dirname(HERE)
sys.path.insert(0, ASSETS)
from fond_lib import poser, tampon_case, title  # noqa: E402

RANKS = [("citoyen", "9AD29A", 0), ("repute", "6AC7C7", 0), ("populaire", "5CA8E6", 1), ("eminent", "4E72E6", 1),
         ("figure", "8A5CE6", 1), ("influenceur", "D15CE6", 2), ("icone", "E65C9F", 2), ("legende", "FFD94D", 2),
         ("mythe", "E6A85C", 3), ("souverain", "E65C5C", 3), ("supreme", "FF0033", 3)]
LIST = list(range(10, 17)) + list(range(19, 26)) + list(range(28, 35))
CONTOUR = (21, 17, 27, 255)
OR, OR_CLAIR = (240, 168, 0, 255), (255, 217, 77, 255)
CHEST_BOTTOM = 124  # last pixel row of the chest part


def c(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def objet(case):
    return 8 + 18 * (case % 9), 18 + 18 * (case // 9)


def tint(tampon, colour, strength=1.0):
    """The cell in the rank's colour, same ramp as the rank cells of the rank menu (rangs/gen_fond.py), softened."""
    col = c(colour)
    ramp = [CONTOUR] + [mix(CONTOUR, col, t) for t in (.25, .4, .55, .7, .85)] + [col, mix(col, (255, 255, 255, 255), .35)]
    shades = sorted({v for v in tampon.values()}, key=lambda v: sum(v[:3]))
    table = {v: ramp[round(i * (len(ramp) - 1) / max(1, len(shades) - 1))] for i, v in enumerate(shades)}
    return {k: mix(v, table[v], strength) for k, v in tampon.items()}


def dark(d, n):
    x, y = objet(n)
    d.rectangle([x - 1, y - 1, x + 16, y + 16], (37, 29, 48, 255))
    d.rectangle([x, y, x + 15, y + 15], (29, 23, 38, 255))


def star(im, x, y, col):
    px = im.load()
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        px[x + dx, y + dy] = col


def background(rank, colour, tier, chest, generic, case):
    im = chest.copy()
    # chest part: empty generic panel (the cells of chest_6 are erased), inventory part untouched
    im.paste(generic.crop((4, 16, 172, CHEST_BOTTOM + 1)), (4, 16))
    d = ImageDraw.Draw(im)
    col = c(colour)
    # ribbon of the rank's colour over the whole top of the panel, frame included (only the dark outer outline and the
    # transparent corners are kept), with the tier's stars
    px = im.load()
    for y in range(0, 15):
        for x in range(im.width):
            p = px[x, y]
            if p[3] == 0 or p[:3] == CONTOUR[:3]:
                continue
            t = abs(x - 88) / 88
            shade = mix(mix(CONTOUR, col, .55), mix(CONTOUR, col, .25), t)
            if y <= 2:
                shade = mix(shade, mix(col, (255, 255, 255, 255), .3), .6)
            px[x, y] = shade
    d.line([(1, 14), (174, 14)], CONTOUR)
    for k in range(tier + 1):
        star(im, 88 - 3 * tier + 6 * k, 9, OR_CLAIR)
    for n in LIST:
        poser(im, tint(case, colour, .75), *objet(n))
    for n in (0, 8, 49):
        poser(im, case, *objet(n))
    title(im, "Offrande", objet(0)[1])
    for n in (18, 26):
        dark(d, n)
    gold = tint(case, "F0A800")
    poser(im, gold, *objet(40))
    return im


if __name__ == "__main__":
    out = sys.argv[1]
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    os.makedirs(out, exist_ok=True)
    chest = Image.open(os.path.join(ASSETS, "chest_6.png")).convert("RGBA")
    generic = Image.open(os.path.join(ASSETS, "menu_generique.png")).convert("RGBA")
    case = tampon_case()
    sheet = Image.new("RGBA", (176 * 2 * 6 + 70, 221 * 2 * 2 + 30), (16, 16, 16, 255)) if preview else None
    for i, (rank, colour, tier) in enumerate(RANKS):
        im = background(rank, colour, tier, chest, generic, case)
        im.save(os.path.join(out, "menu_offrande_%s.png" % rank))
        if preview:
            sheet.alpha_composite(im.resize((352, 442), Image.NEAREST), (10 + (i % 6) * 362, 10 + (i // 6) * 452))
    if preview:
        os.makedirs(preview, exist_ok=True)
        sheet.save(os.path.join(preview, "planche_offrandes.png"))
