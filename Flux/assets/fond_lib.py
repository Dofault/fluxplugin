"""Outils communs des fonds de menus Flux (Pillow) : fond générique raccourci, tampon de case.

Utilisé par les gen_fond*.py des menus (familiers, ...). Le fond générique (menu_generique.png) est un
panneau plein écran (176 x 221) : bord haut et ornements (rangées 0 à 9), dégradé tramé en damier, bord bas
et ornements (211 à 220). La case « tampon » est la case arrondie dessinée par l'utilisateur le 2026-10-15
(menus Collection), relevée par différence entre son image et la même base sans cases.
"""
from PIL import Image

BLOC_HAUT = range(0, 10)
BLOC_BAS = range(211, 221)


def generique_raccourci(generique, haut, bas):
    """Fond générique dont le cadre va de y haut à y bas (inclus), dégradé entier compressé entre les deux :
    chaque rangée reprend la rangée d'origine proportionnelle, de même parité (damier aligné)."""
    src = Image.open(generique).convert("RGBA")
    sp = src.load()
    W, H = src.size
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = im.load()

    def copier(y_src, y_dst):
        for x in range(W):
            px[x, y_dst] = sp[x, y_src]

    for y in BLOC_HAUT:
        copier(y, haut + y)
    debut, fin = haut + len(BLOC_HAUT), bas - len(BLOC_BAS) + 1
    src_debut, src_fin = BLOC_HAUT.stop, BLOC_BAS.start - 1
    for y in range(debut, fin):
        s = src_debut + round((y - debut) * (src_fin - src_debut) / max(1, fin - 1 - debut))
        if (s - y) % 2:
            s = s - 1 if s > src_debut else s + 1
        copier(s, y)
    for i, y in enumerate(BLOC_BAS):
        copier(y, fin + i)
    return im


def releve_tampon(avec_cases, sans_cases, x, y, marge=3):
    """Pixels qui diffèrent autour de la case dont l'objet est en (x, y) : {(dx, dy): couleur}."""
    a, b = avec_cases.load(), sans_cases.load()
    tampon = {}
    for dy in range(-marge, 16 + marge):
        for dx in range(-marge, 16 + marge):
            if a[x + dx, y + dy] != b[x + dx, y + dy]:
                tampon[(dx, dy)] = a[x + dx, y + dy]
    return tampon


def poser(im, tampon, x, y):
    px = im.load()
    for (dx, dy), c in tampon.items():
        px[x + dx, y + dy] = c


def objet(case, rangees_coffre=6):
    """Position (x, y) de l'objet d'une case de fenêtre de coffre (cases du joueur après le coffre)."""
    if case < rangees_coffre * 9:
        return 8 + 18 * (case % 9), 18 + 18 * (case // 9)
    i = case - rangees_coffre * 9
    if i < 27:
        return 8 + 18 * (i % 9), 18 + 18 * rangees_coffre + 14 + 18 * (i // 9)
    return 8 + 18 * (i - 27), 18 + 18 * rangees_coffre + 14 + 58


def tampon_case(png=None):
    """Case arrondie de l'utilisateur (18 x 18, de -1 à 16 autour de l'objet) : assets/case_arrondie.png, relevée
    le 2026-10-15 sur son fond violet des catégories de collections (celui-ci est depuis passé au thème crème)."""
    import os
    png = png or os.path.join(os.path.dirname(os.path.abspath(__file__)), "case_arrondie.png")
    im = Image.open(png).convert("RGBA")
    return {(x - 1, y - 1): im.getpixel((x, y)) for y in range(im.height) for x in range(im.width) if im.getpixel((x, y))[3]}


# ---------------------------------------------------------------- menu titles (QuinqueFive, 5 px high)

_FONT = None
# accented capitals: base letter + extra pixels (relative to the letter's top left)
_ACCENTS = {"É": ("E", [(3, -2), (2, -1)]), "È": ("E", [(1, -2), (2, -1)]), "Ê": ("E", [(1, -1), (2, -2), (3, -1)]),
            "Ô": ("O", [(1, -1), (2, -2), (3, -1)]), "À": ("A", [(1, -2), (2, -1)])}
WHITE, SHADOW = (255, 255, 255, 255), (0, 0, 0, 255)


def _font():
    global _FONT
    if _FONT is None:
        import json
        import os
        _FONT = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "metiers", "quinquefive.json")))
    return _FONT


def _glyph(c):
    base, extra = _ACCENTS.get(c, (c, []))
    return _font().get(base), extra


def text_width(txt):
    w = 0
    for c in txt.upper():
        g, _ = _glyph(c)
        w += (len(g[0]) if g else 3) + 1
    return w - 1


def text(im, txt, cx, y, colour=WHITE, shadow=SHADOW):
    """QuinqueFive capitals centred on cx, top at y, with a one-pixel shadow below (as the pass title)."""
    x = cx - text_width(txt) // 2
    px = im.load()
    for c in txt.upper():
        g, extra = _glyph(c)
        if not g:
            x += 4
            continue
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[x + dx, y + dy + 1] = shadow
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[x + dx, y + dy] = colour
        for dx, dy in extra:
            px[x + dx, y + dy] = colour
        x += len(g[0]) + 1


def title(im, txt, row_top, cx=88):
    """Menu title on the menu's first row (between the back button and the menu icon), as in the pass."""
    text(im, txt, cx, row_top + 6)
