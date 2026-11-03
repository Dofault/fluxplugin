#!/usr/bin/env python3
"""FluxTags: tag style proposals (2026-10-30, round 2), to choose the next look of every tag.

Pure pixel art: every image is 16 px high (no smoothing, no TTF), letters of a 5 px pixel font (7 px at most), so the
name takes less than half of the height. In the chat the image keeps the height of today's tags (DISPLAY = 9 GUI px):
a 16 px image shown 9 px high is ~44 % narrower than the same name drawn in a 9 px image. The free height holds the
progression icon (pixel art, few tones), often above the name so that it costs no width.

Every style is a function (name, colour, tier or None) -> 16 px image. tier None = a tag that does not evolve (rank,
special): no rarity icon.

    <venv with Pillow>/python3 gen_propositions.py <out dir>
Writes propositions_grades.png: each style zoomed, then in a mock chat at GUI scale 2 and 3.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from tag_styles import RARITIES, gemme  # noqa: E402

sys.path.insert(0, os.path.join(HERE, "..", "..", "Flux", "assets"))
from fond_lib import _glyph as q5_glyph  # noqa: E402

H = 16           # image height
DISPLAY = 9      # height in the chat (GUI px), as today's tags
WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)
NONE = (0, 0, 0, 0)
NIGHT = (30, 10, 60, 255)    # #1E0A3C of the charter


def c(h):
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def light(col, t):
    return mix(col, WHITE, t)


def dark(col, t):
    return mix(col, BLACK, t)


def tones(col):
    """The five tones of a colour used by every drawing: outline, shade, base, light, highlight."""
    return {"o": dark(col, .65), "-": dark(col, .3), "#": col, "+": light(col, .4), "*": light(col, .75)}


# ---------------------------------------------------------------- pixel fonts

# 3 px wide capitals, 5 px high (M, N, W wider): a narrower sibling of QuinqueFive
TINY = {
    "A": [".#.", "#.#", "###", "#.#", "#.#"], "B": ["##.", "#.#", "##.", "#.#", "##."], "C": [".##", "#..", "#..", "#..", ".##"],
    "D": ["##.", "#.#", "#.#", "#.#", "##."], "E": ["###", "#..", "##.", "#..", "###"], "F": ["###", "#..", "##.", "#..", "#.."],
    "G": [".##", "#..", "#.#", "#.#", ".##"], "H": ["#.#", "#.#", "###", "#.#", "#.#"], "I": ["###", ".#.", ".#.", ".#.", "###"],
    "J": ["..#", "..#", "..#", "#.#", ".#."], "K": ["#.#", "#.#", "##.", "#.#", "#.#"], "L": ["#..", "#..", "#..", "#..", "###"],
    "M": ["#...#", "##.##", "#.#.#", "#...#", "#...#"], "N": ["#..#", "##.#", "#.##", "#..#", "#..#"],
    "O": [".#.", "#.#", "#.#", "#.#", ".#."], "P": ["##.", "#.#", "##.", "#..", "#.."], "Q": [".#.", "#.#", "#.#", "##.", ".##"],
    "R": ["##.", "#.#", "##.", "#.#", "#.#"], "S": [".##", "#..", ".#.", "..#", "##."], "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "U": ["#.#", "#.#", "#.#", "#.#", "###"], "V": ["#.#", "#.#", "#.#", "#.#", ".#."],
    "W": ["#...#", "#...#", "#.#.#", "##.##", "#...#"], "X": ["#.#", "#.#", ".#.", "#.#", "#.#"],
    "Y": ["#.#", "#.#", ".#.", ".#.", ".#."], "Z": ["###", "..#", ".#.", "#..", "###"], " ": ["..", "..", "..", "..", ".."],
}
# condensed capitals, 3 px wide, 7 px high (the tallest allowed: under half of the image)
COND = {
    "A": [".#.", "#.#", "#.#", "###", "#.#", "#.#", "#.#"], "B": ["##.", "#.#", "#.#", "##.", "#.#", "#.#", "##."],
    "C": [".##", "#..", "#..", "#..", "#..", "#..", ".##"], "D": ["##.", "#.#", "#.#", "#.#", "#.#", "#.#", "##."],
    "E": ["###", "#..", "#..", "##.", "#..", "#..", "###"], "F": ["###", "#..", "#..", "##.", "#..", "#..", "#.."],
    "G": [".##", "#..", "#..", "#.#", "#.#", "#.#", ".##"], "H": ["#.#", "#.#", "#.#", "###", "#.#", "#.#", "#.#"],
    "I": ["#", "#", "#", "#", "#", "#", "#"], "J": ["..#", "..#", "..#", "..#", "..#", "#.#", ".#."],
    "K": ["#.#", "#.#", "##.", "##.", "#.#", "#.#", "#.#"], "L": ["#..", "#..", "#..", "#..", "#..", "#..", "###"],
    "M": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    "N": ["#..#", "##.#", "##.#", "#.##", "#.##", "#..#", "#..#"],
    "O": [".#.", "#.#", "#.#", "#.#", "#.#", "#.#", ".#."], "P": ["##.", "#.#", "#.#", "##.", "#..", "#..", "#.."],
    "Q": [".#.", "#.#", "#.#", "#.#", "#.#", "##.", ".##"], "R": ["##.", "#.#", "#.#", "##.", "#.#", "#.#", "#.#"],
    "S": [".##", "#..", "#..", ".#.", "..#", "..#", "##."], "T": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", ".#."],
    "U": ["#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "###"], "V": ["#.#", "#.#", "#.#", "#.#", "#.#", ".#.", ".#."],
    "W": ["#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"],
    "X": ["#.#", "#.#", "#.#", ".#.", "#.#", "#.#", "#.#"], "Y": ["#.#", "#.#", "#.#", ".#.", ".#.", ".#.", ".#."],
    "Z": ["###", "..#", "..#", ".#.", "#..", "#..", "###"], " ": ["..", "..", "..", "..", "..", "..", ".."],
}
# accents of the 3 px fonts, in the 2 rows above the letter
ACC3 = {"É": ("E", [(2, -2), (1, -1)]), "È": ("E", [(0, -2), (1, -1)]), "Ê": ("E", [(0, -1), (1, -2), (2, -1)]),
        "Ô": ("O", [(0, -1), (1, -2), (2, -1)]), "Î": ("I", [(0, -1), (1, -2), (2, -1)]), "À": ("A", [(0, -2), (1, -1)])}


def glyph(font, ch):
    if font == "q5":
        g, extra = q5_glyph(ch)
        return g or ["..."] * 5, extra
    base, extra = ACC3.get(ch, (ch, []))
    table = TINY if font == "tiny" else COND
    g = table.get(base, table[" "])
    if base == "I" and font == "cond" and extra:  # Î on a 1 px wide I: one dot
        extra = [(0, -2)]
    return g, extra


def text_width(txt, font="q5"):
    return sum(len(glyph(font, ch)[0][0]) + 1 for ch in txt.upper()) - 1


def text_height(font):
    return 7 if font == "cond" else 5


def write(im, txt, x, y, font="q5", colour=WHITE, shadow=None, outline=None):
    """Pixel capitals, top left at (x, y), accents above y; shadow = one px below, outline = 1 px all around."""
    pts = []
    for ch in txt.upper():
        g, extra = glyph(font, ch)
        pts += [(x + dx, y + dy) for dy, r in enumerate(g) for dx, v in enumerate(r) if v == "#"]
        pts += [(x + dx, y + dy) for dx, dy in extra]
        x += len(g[0]) + 1
    px = im.load()

    def put(p, col):
        if 0 <= p[0] < im.width and 0 <= p[1] < im.height:
            px[p] = col
    if outline:
        for a, b in pts:
            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, 1), (1, -1), (-1, -1)):
                put((a + ox, b + oy), outline)
    if shadow:
        for a, b in pts:
            put((a, b + 1), shadow)
    for p in pts:
        put(p, colour)


# ---------------------------------------------------------------- pixel icons (legend: see tones())

ICONS = {
    "gemme": [".ooooooo.", "o*+#+#+-o", "o+#####-o", ".o+###-o.", "..o+#-o..", "...o#o...", "....o...."],
    "etoile": ["....o....", "...o*o...", "...o+o...", "oooo+#ooo", "o*++##--o", ".o+###-o.", "..o###o..",
               ".o#-o-#o.", ".oo...oo."],
    "couronne": [".o..o..o.", "o*oo+oo-o", "o+#+#+#-o", "o+#####-o", "o*******o", "ooooooooo"],
    "orbe": ["..oooo..", ".o*+##o.", "o*+####o", "o+####-o", "o#####-o", "o####--o", ".o#---o.", "..oooo.."],
    "flamme": ["...o.....", "..o*o..o.", ".o+#-oo#o", ".o+*#-#-o", "o+#**#--o", "o#*++*#-o", ".o#++#-o.", "..ooooo.."],
}


def icon(im, name, x, y, col):
    t = tones(col)
    px = im.load()
    for dy, r in enumerate(ICONS[name]):
        for dx, v in enumerate(r):
            if v in t:
                px[x + dx, y + dy] = t[v]


def icon_size(name):
    return len(ICONS[name][0]), len(ICONS[name])


def chevrons(im, x, y, col, n):
    """n stacked chevrons (1 per tier, Commun 1 ... Fabuleux 6), 7 px wide, every 3 px."""
    t = tones(col)
    shape = ["##...##", ".##.##.", "..###..", "...#..."]
    t = {"#": t["+"]}
    px = im.load()
    for i in range(n):
        for dy, r in enumerate(shape):
            for dx, v in enumerate(r):
                if v in t:
                    px[x + dx, y + i * 3 + dy] = t[v]


def bars(im, x, bottom, col, lit, off):
    """6 bars growing from 2 to 7 px, the first `lit` in the colour, the others in `off`."""
    d = ImageDraw.Draw(im)
    for i in range(6):
        d.line((x + i * 2, bottom - 1 - i, x + i * 2, bottom), fill=light(col, .55) if i < lit else off)


def pips(im, cx, y, col, lit, off):
    """6 dots of 2x2 centred on cx, the first `lit` lit."""
    d = ImageDraw.Draw(im)
    x0 = cx - (6 * 3 - 1) // 2
    for i in range(6):
        d.rectangle((x0 + i * 3, y, x0 + i * 3 + 1, y + 1), fill=(light(col, .6) if i < lit else off))


# ---------------------------------------------------------------- plates

def plate(im, box, col, style="relief"):
    """Pixel plate: dark outline, then either 'relief' (light inner line on top, darker lower band, as the reference
    tag), 'plein' (flat), or 'nuit' (night violet with an edge of the colour)."""
    x0, y0, x1, y1 = box
    d = ImageDraw.Draw(im)
    t = tones(col)
    if style == "nuit":
        d.rectangle(box, fill=NIGHT, outline=t["#"])
        d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=mix(NIGHT, col, .35))
        return
    d.rectangle(box, fill=t["#"], outline=t["o"])
    if style == "relief":
        mid = (y0 + y1) // 2 + 1
        d.rectangle((x0 + 1, mid, x1 - 1, y1 - 1), fill=dark(col, .15))
        d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=t["+"])
        d.line((x0 + 1, y1 - 1, x1 - 1, y1 - 1), fill=t["-"])


def cut_corners(im, box):
    x0, y0, x1, y1 = box
    for x, y in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        im.putpixel((x, y), NONE)


def new(w):
    return Image.new("RGBA", (w, H), NONE)


def tcol(tier):
    return c(RARITIES[tier][1])


def name_on(im, name, x, top, bottom, col, font="q5", how="shadow"):
    """Name centred vertically between top and bottom (letter rows only)."""
    y = top + (bottom - top + 1 - text_height(font)) // 2
    if how == "outline":
        write(im, name, x, y, font, outline=dark(col, .7))
    else:
        write(im, name, x, y, font, shadow=dark(col, .6))


# ---------------------------------------------------------------- the styles (16 px high)

def s_actuel(name, colour, tier):
    return gemme(name, colour, tier if tier is not None else 0)


def s_couronne(name, colour, tier):
    """Plate in the lower part, the rarity crown sits on it, centred above the name: no width for the icon."""
    col = c(colour)
    w = text_width(name) + 8
    im = new(w)
    plate(im, (0, 6, w - 1, 15), col)
    name_on(im, name, 4, 7, 14, col)
    if tier is not None:
        iw, ih = icon_size("couronne")
        icon(im, "couronne", (w - iw) // 2, 7 - ih, tcol(tier))
    return im


def s_medaillon(name, colour, tier):
    """Round medallion 16x16 holding the icon, the name plate tucked behind it."""
    col = c(colour)
    left = 12 if tier is not None else 0
    w = left + text_width(name) + 8
    im = new(w)
    plate(im, (left, 3, w - 1, 12), col)
    name_on(im, name, left + 4 + (2 if tier is not None else 0), 4, 11, col)
    if tier is not None:
        tc = tones(tcol(tier))
        d = ImageDraw.Draw(im)
        d.ellipse((0, 0, 15, 15), fill=tc["o"])
        d.ellipse((1, 1, 14, 14), fill=tc["#"])
        d.ellipse((2, 2, 13, 13), fill=tc["-"])
        icon(im, "orbe", 4, 4, light(tcol(tier), .25))
    return im


def s_pancarte(name, colour, tier):
    """Hanging sign: two chains, the board under them, the gem on the board."""
    col = c(colour)
    left = 12 if tier is not None else 4
    w = left + text_width(name) + 5
    im = new(w)
    for x in (3, w - 4):
        for y in range(0, 5):
            im.putpixel((x, y), dark(col, .5) if y % 2 == 0 else dark(col, .2))
    plate(im, (0, 4, w - 1, 15), col)
    name_on(im, name, left, 5, 14, col)
    if tier is not None:
        icon(im, "gemme", 2, 6, tcol(tier))
    return im


def s_chevrons(name, colour, tier):
    """Military stripes: one chevron per tier on a shield (Mythique = 4), the name plate beside it."""
    col = c(colour)
    left = 10 if tier is not None else 0
    w = left + text_width(name) + 8
    im = new(w)
    plate(im, (left - 1 if left else 0, 3, w - 1, 12), col)
    name_on(im, name, left + 4, 4, 11, col)
    if tier is not None:
        tc = tones(tcol(tier))
        d = ImageDraw.Draw(im)
        d.polygon([(0, 0), (10, 0), (10, 11), (5, 15), (0, 11)], fill=tc["o"])
        d.polygon([(1, 1), (9, 1), (9, 10), (5, 14), (1, 10)], fill=NIGHT)
        chevrons(im, 2, 2, tcol(tier), tier + 1)
    return im


def s_barres(name, colour, tier):
    """Progression bars (signal style) after the name: 6 bars, Mythique = 4 lit."""
    col = c(colour)
    right = 14 if tier is not None else 0
    w = text_width(name) + 8 + right
    im = new(w)
    plate(im, (0, 2, w - 1, 13), col)
    name_on(im, name, 4, 3, 12, col)
    if tier is not None:
        ImageDraw.Draw(im).rectangle((w - right, 3, w - 2, 12), fill=dark(col, .55))
        bars(im, w - right + 1, 11, tcol(tier), tier + 1, dark(col, .35))
    return im


def s_points(name, colour, tier):
    """Six dots above the name show the tier (Mythique = 4 lit): readable at a glance, no width."""
    col = c(colour)
    w = max(text_width(name) + 8, 22)
    im = new(w)
    plate(im, (0, 5, w - 1, 15), col)
    name_on(im, name, (w - text_width(name)) // 2, 6, 14, col)
    if tier is not None:
        d = ImageDraw.Draw(im)
        d.rectangle((w // 2 - 10, 0, w // 2 + 9, 5), fill=dark(col, .65))
        d.rectangle((w // 2 - 9, 1, w // 2 + 8, 5), fill=dark(col, .35))
        pips(im, w // 2, 2, tcol(tier), tier + 1, dark(col, .6))
    return im


def s_hexa(name, colour, tier):
    """Pointed ends on both sides, the star in the left point."""
    col = c(colour)
    tip = 6
    left = tip + (6 if tier is not None else 0)
    w = left + text_width(name) + tip + 2
    im = new(w)
    d = ImageDraw.Draw(im)
    t = tones(col)
    d.polygon([(0, 7), (tip, 1), (w - 1 - tip, 1), (w - 1, 7), (w - 1, 8), (w - 1 - tip, 14), (tip, 14), (0, 8)],
              fill=t["o"])
    d.polygon([(1, 7), (tip, 2), (w - 1 - tip, 2), (w - 2, 7), (w - 2, 8), (w - 1 - tip, 13), (tip, 13), (1, 8)],
              fill=t["#"])
    d.line((tip, 2, w - 1 - tip, 2), fill=t["+"])
    d.polygon([(2, 8), (w - 3, 8), (w - 1 - tip, 12), (tip, 12)], fill=dark(col, .15))
    name_on(im, name, left, 2, 13, col)
    if tier is not None:
        icon(im, "etoile", 2, 3, tcol(tier))
    return im


def s_ruban(name, colour, tier):
    """Ribbon: the band, its folded tails behind at both ends, the orb on the band."""
    col = c(colour)
    left = 12 if tier is not None else 6
    w = left + text_width(name) + 6
    im = new(w)
    d = ImageDraw.Draw(im)
    for x0, x1, edge, step in ((0, 6, 0, 1), (w - 7, w - 1, w - 1, -1)):
        d.rectangle((x0, 6, x1, 14), fill=dark(col, .4), outline=dark(col, .7))
        for y in range(10, 15):  # swallowtail notch on the outer side
            for i in range(3 - abs(y - 12)):
                im.putpixel((edge + i * step, y), NONE)
    plate(im, (3, 2, w - 4, 11), col)
    name_on(im, name, left, 3, 10, col)
    if tier is not None:
        icon(im, "orbe", 3, 3, tcol(tier))
    return im


def s_onglet(name, colour, tier):
    """Folder: a tab rises from the left of the plate and holds the flame of the tier."""
    col = c(colour)
    im = new(max(text_width(name) + 8, 14))
    w = im.width
    d = ImageDraw.Draw(im)
    if tier is not None:
        d.rectangle((1, 0, 13, 7), fill=tones(col)["o"])
        d.rectangle((2, 1, 12, 7), fill=dark(tcol(tier), .5))
    plate(im, (0, 7, w - 1, 15), col)
    name_on(im, name, 4, 8, 14, col)
    if tier is not None:
        icon(im, "flamme", 3, 0, tcol(tier))
    return im


def s_bicolore(name, colour, tier):
    """Two blocks: a 16x16 rarity square with the gem, the name on night violet in a light tint of the colour."""
    col = c(colour)
    left = 15 if tier is not None else 0
    w = left + text_width(name) + 8
    im = new(w)
    plate(im, (left, 2, w - 1, 13), col, "nuit")
    write(im, name, left + 4, 5, colour=light(col, .55), shadow=BLACK)
    if tier is not None:
        tc = tcol(tier)
        plate(im, (0, 0, 15, 15), tc)
        icon(im, "gemme", 3, 4, light(tc, .2))
    return im


def s_relief(name, colour, tier):
    """The reference tag in pixels: full-height plate, light inner border, two bands, outlined letters."""
    col = c(colour)
    left = 13 if tier is not None else 5
    w = left + text_width(name) + 6
    im = new(w)
    t = tones(col)
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, 15), fill=t["#"], outline=t["o"])
    d.rectangle((1, 8, w - 2, 14), fill=dark(col, .15))
    d.rectangle((1, 1, w - 2, 14), outline=t["+"])
    d.line((2, 14, w - 2, 14), fill=t["-"])
    cut_corners(im, (0, 0, w - 1, 15))
    name_on(im, name, left, 1, 14, col, how="outline")
    if tier is not None:
        icon(im, "orbe", 3, 4, tcol(tier))
    return im


def s_mini(name, colour, tier):
    """Narrowest: 3 px letters, crown above (no width), small plate."""
    col = c(colour)
    im = new(max(text_width(name, "tiny") + 8, 13))
    w = im.width
    plate(im, (0, 7, w - 1, 15), col)
    name_on(im, name, (w - text_width(name, "tiny")) // 2, 8, 14, col, font="tiny")
    if tier is not None:
        iw, ih = icon_size("couronne")
        icon(im, "couronne", (w - iw) // 2, 8 - ih, tcol(tier))
    return im


def s_haut(name, colour, tier):
    """Tallest letters allowed (7 px condensed, under half the height), gem above the end of the name."""
    col = c(colour)
    w = text_width(name, "cond") + 8
    im = new(w)
    plate(im, (0, 6, w - 1, 15), col)
    write(im, name, 4, 8, "cond", outline=dark(col, .7))
    if tier is not None:
        icon(im, "gemme", (w - 9) // 2, 0, tcol(tier))
    return im


# (title, French description shown on the sheet, style)
STYLES = [
    ("0. Actuel", "Le tag de métier d'aujourd'hui (9 px de haut), pour comparer.", s_actuel),
    ("1. Couronne posée", "La couronne de rareté posée sur la plaque, au-dessus du nom : l'icône ne coûte aucune largeur.", s_couronne),
    ("2. Médaillon", "Un médaillon rond de 16 px tient l'orbe de rareté, la plaque du nom passe derrière.", s_medaillon),
    ("3. Pancarte", "Une pancarte suspendue à deux chaînes, la gemme sur la planche.", s_pancarte),
    ("4. Galons", "Un galon par palier sur un écusson (Mythique = 4), comme un grade militaire.", s_chevrons),
    ("5. Barres", "6 barres de progression après le nom, comme du réseau (Mythique = 4 allumées).", s_barres),
    ("6. Points", "6 points au-dessus du nom (Mythique = 4 allumés) : aucune largeur pour l'icône.", s_points),
    ("7. Hexagone", "Plaque à bouts pointus, l'étoile dans la pointe gauche.", s_hexa),
    ("8. Ruban", "Ruban avec ses queues repliées derrière, l'orbe sur le ruban.", s_ruban),
    ("9. Onglet", "Un onglet sort de la plaque comme un dossier et porte la flamme du palier : aucune largeur.", s_onglet),
    ("10. Bicolore", "Un carré de rareté 16x16 avec la gemme, le nom sur fond violet nuit.", s_bicolore),
    ("11. Relief (ta référence)", "La plaque de ta référence en pixel : pleine hauteur, bordure claire, deux bandes.", s_relief),
    ("12. Mini", "Lettres de 3 px de large et couronne au-dessus : le plus étroit.", s_mini),
    ("13. Grandes lettres", "Lettres étroites de 7 px (moins de la moitié de la hauteur), gemme au-dessus.", s_haut),
]

# ---------------------------------------------------------------- preview sheet

LABEL = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
SMALL = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 17)
LINE = 9  # chat line height (GUI px)


def chat_width(im):
    h = DISPLAY if im.height == H else im.height  # today's tags are drawn at their own height
    return max(1, round(im.width * h / im.height)), h


def chat_mock(tags, gui):
    """Three chat lines at GUI scale `gui`, the tag shown DISPLAY px high and scaled pixel by pixel (nearest), as the
    client does."""
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", round(7.3 * gui))
    lines = [(tags[0], "Dofault", "qui vient couper du bois ?"), (None, "Alex", "je suis à Valdor"),
             (tags[1], "Amandine", "moi, j'arrive")]
    out = Image.new("RGBA", (230 * gui, len(lines) * LINE * gui + 2 * gui), (22, 26, 18, 255))
    d = ImageDraw.Draw(out)
    for i, (tag, who, msg) in enumerate(lines):
        top = gui + i * LINE * gui
        x = 2 * gui
        if tag:
            gw, gh = chat_width(tag)
            ci = tag.resize((gw * gui, gh * gui), Image.NEAREST)
            out.alpha_composite(ci, (x, top + (LINE * gui - ci.height) // 2))
            x += ci.width + 4 * gui
        d.text((x + gui, top + gui), "%s : %s" % (who, msg), font=font, fill=(40, 40, 40))
        d.text((x, top), "%s : %s" % (who, msg), font=font, fill=(255, 255, 255))
    return out


def sheet(path):
    myth, colour = 3, RARITIES[3][1]
    zoom = 8
    base_w = chat_width(s_actuel("Sylviculteur", colour, myth))[0]
    pieces, sizes = [], []
    for title, desc, fn in STYLES:
        job, rank = fn("Sylviculteur", colour, myth), fn("Éminent", "4e72e6", None)
        jw = chat_width(job)[0]
        sizes.append((title, job.size, jw))
        big = job.resize((job.width * zoom, job.height * zoom), Image.NEAREST)
        rb = rank.resize((rank.width * zoom, rank.height * zoom), Image.NEAREST)
        m2, m3 = chat_mock([job, rank], 2), chat_mock([job, rank], 3)
        h = 76 + max(big.height, rb.height) + 20 + 26 + m3.height + 24
        p = Image.new("RGBA", (1500, h), (36, 30, 48, 255))
        d = ImageDraw.Draw(p)
        d.text((20, 12), title, font=LABEL, fill=(255, 217, 77))
        diff = "" if jw == base_w else "  (%+d %%)" % round((jw - base_w) * 100 / base_w)
        d.text((20, 44), "%s   |   image %d x %d, largeur dans le tchat %d px%s" % (desc, job.width, job.height, jw, diff),
               font=SMALL, fill=(200, 190, 220))
        y = 76
        p.alpha_composite(big, (20, y))
        p.alpha_composite(rb, (40 + big.width, y))
        y += max(big.height, rb.height) + 20
        d.text((20, y), "Tchat, interface 2", font=SMALL, fill=(170, 160, 190))
        d.text((40 + m2.width, y), "Tchat, interface 3", font=SMALL, fill=(170, 160, 190))
        y += 26
        p.alpha_composite(m2, (20, y))
        p.alpha_composite(m3, (40 + m2.width, y))
        pieces.append(p)
    out = Image.new("RGBA", (1500, sum(p.height for p in pieces) + 4 * len(pieces)), (20, 16, 28, 255))
    y = 0
    for p in pieces:
        out.alpha_composite(p, (0, y))
        y += p.height + 4
    out.save(path)
    return sizes


if __name__ == "__main__":
    dest = sys.argv[1] if len(sys.argv) > 1 else HERE
    for t, size, jw in sheet(os.path.join(dest, "propositions_grades.png")):
        print("%-28s image %-9s chat %d px" % (t, "%dx%d" % size, jw))
