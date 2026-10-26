"""Symboles de la machine à sous (24 x 24), dessinés par programme puis ramenés à leur taille.

Chaque symbole est dessiné à 8x (formes pleines, dégradés par superposition) puis réduit : les bords
sont lissés, un liseré sombre d'un pixel est ajouté après réduction. Importé par gen_slots.py.
"""
import math

from PIL import Image, ImageDraw, ImageFilter

S = 8          # échelle de dessin
N = 24         # taille finale
CONTOUR = (26, 5, 5, 255)


def rgb(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


class Toile:
    def __init__(self):
        self.im = Image.new("RGBA", (N * S, N * S), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)

    def ellipse(self, cx, cy, rx, ry, couleur):
        self.d.ellipse([(cx - rx) * S, (cy - ry) * S, (cx + rx) * S, (cy + ry) * S], fill=couleur)

    def poly(self, pts, couleur):
        self.d.polygon([(x * S, y * S) for x, y in pts], fill=couleur)

    def ligne(self, pts, couleur, w):
        self.d.line([(x * S, y * S) for x, y in pts], fill=couleur, width=int(w * S), joint="curve")

    def rect(self, x0, y0, x1, y1, couleur):
        self.d.rectangle([x0 * S, y0 * S, x1 * S - 1, y1 * S - 1], fill=couleur)

    def masque(self):
        """Masque alpha courant (pour limiter une lueur ou une ombre à la forme)."""
        return self.im.split()[3]

    def fini(self):
        im = self.im.resize((N, N), Image.LANCZOS)
        # seuil d'alpha : bords nets, pas de demi-transparence (rendu pixel-art)
        px = im.load()
        for y in range(N):
            for x in range(N):
                r, g, b, a = px[x, y]
                px[x, y] = (r, g, b, 255) if a >= 110 else (0, 0, 0, 0)
        return contour(im)


def contour(im):
    """Liseré sombre d'un pixel autour de la forme (voisins en croix et diagonales)."""
    out = im.copy()
    px, po = im.load(), out.load()
    for y in range(im.height):
        for x in range(im.width):
            if px[x, y][3]:
                continue
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < im.width and 0 <= yy < im.height and px[xx, yy][3] and px[xx, yy][:3] != CONTOUR[:3]:
                        po[x, y] = CONTOUR
    return out


def deplacer(im, dx, dy):
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.alpha_composite(im, (dx, dy))
    return out


def eclairer(t, couleur_haut, couleur_bas):
    """Dégradé vertical sur la forme dessinée (haut éclairé, bas plus sombre)."""
    w, h = t.im.size
    deg = Image.new("RGBA", (w, h))
    dd = ImageDraw.Draw(deg)
    for y in range(h):
        f = y / (h - 1)
        c = tuple(int(couleur_haut[i] * (1 - f) + couleur_bas[i] * f) for i in range(3)) + (255,)
        dd.line([(0, y), (w, y)], fill=c)
    masque = t.masque()
    base = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    base.paste(deg, (0, 0), masque)
    t.im = base
    t.d = ImageDraw.Draw(t.im)


# ------------------------------------------------------------------ symboles

def cerise():
    t = Toile()
    rouge, ombre, reflet = rgb("D41A28"), rgb("8A0A14"), rgb("FF9A90")
    # tiges
    t.ligne([(7.5, 14), (10.5, 8), (14.5, 3.5)], rgb("3A8A2A"), 1.2)
    t.ligne([(16.5, 15), (15.5, 8), (14.5, 3.5)], rgb("3A8A2A"), 1.2)
    # feuille
    t.poly([(14.5, 3.8), (18, 2), (21.5, 3.5), (18.5, 6)], rgb("48B040"))
    t.poly([(14.5, 3.8), (18, 2), (21.5, 3.5)], rgb("78D868"))
    for cx, cy in ((7.5, 16.5), (16.5, 17.5)):
        t.ellipse(cx, cy, 5.2, 5.2, ombre)
        t.ellipse(cx - 0.4, cy - 0.5, 4.7, 4.7, rouge)
        t.ellipse(cx - 1.8, cy - 2.0, 1.5, 1.1, reflet)
    return t.fini()


def citron():
    t = Toile()
    jaune, ombre, reflet = rgb("FFD94D"), rgb("D9A400"), rgb("FFF6B0")
    # corps ovale à bouts pointus, penché
    pts = []
    for i in range(48):
        a = i / 48 * 2 * math.pi
        x = math.cos(a) * 9.6
        y = math.sin(a) * 6.2
        # pointes : on pince les extrémités
        y *= 1 - 0.25 * (abs(math.cos(a)) ** 6)
        r = math.radians(-28)
        pts.append((12 + x * math.cos(r) - y * math.sin(r), 13 + x * math.sin(r) + y * math.cos(r)))
    t.poly(pts, ombre)
    pts2 = [(12 + (x - 12) * 0.93 - 0.3, 13 + (y - 13) * 0.9 - 0.5) for x, y in pts]
    t.poly(pts2, jaune)
    t.ellipse(9.5, 10.5, 3.0, 1.2, reflet)
    t.poly([(17.5, 5), (21, 3), (22, 6.5), (19, 7.5)], rgb("48B040"))
    return t.fini()


def prune():
    t = Toile()
    violet, ombre, reflet = rgb("8A4FD0"), rgb("4A1E86"), rgb("D9B6FF")
    t.ellipse(12, 14, 8.6, 8.2, ombre)
    t.ellipse(11.6, 13.6, 8.0, 7.6, violet)
    t.ellipse(14.5, 16.5, 4.5, 4.2, rgb("6A33B0"))
    t.ellipse(8.4, 10.2, 2.6, 1.6, reflet)
    # sillon et queue
    t.ligne([(12, 6.2), (12.3, 14), (12, 21)], rgb("6A33B0"), 0.5)
    t.ligne([(12, 6), (13, 2.8)], rgb("6B4A22"), 1.1)
    t.poly([(13, 3.8), (16.5, 1.8), (20, 3.2), (16.8, 5.8)], rgb("48B040"))
    return t.fini()


def cloche():
    t = Toile()
    or_, ombre, clair = rgb("F0A800"), rgb("B0681E"), rgb("FFF3B0")
    # corps de la cloche
    corps = [(12, 3), (15.5, 4.5), (17, 9), (17.5, 14), (19.5, 18), (21, 19.5), (21, 21), (3, 21), (3, 19.5), (4.5, 18),
             (6.5, 14), (7, 9), (8.5, 4.5)]
    t.poly(corps, ombre)
    t.poly([(12 + (x - 12) * 0.9, 12 + (y - 12) * 0.92 - 0.3) for x, y in corps], or_)
    t.poly([(9, 8), (10.5, 5.5), (12, 5), (11, 9), (10, 15), (8, 18), (6.5, 17)], clair)
    t.ellipse(12, 2.8, 1.6, 1.6, ombre)
    t.ellipse(12, 2.6, 1.2, 1.2, or_)
    # battant
    t.ellipse(12, 21.2, 2.6, 2.0, rgb("8A4A12"))
    t.ellipse(11.8, 20.9, 2.0, 1.5, ombre)
    return t.fini()


def etoile():
    t = Toile()
    cx, cy, R, r = 12, 12.8, 11.2, 4.7
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rad = R if i % 2 == 0 else r
        pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    t.poly(pts, rgb("D98A00"))
    pts2 = [(cx + (x - cx) * 0.86, cy + (y - cy) * 0.86) for x, y in pts]
    t.poly(pts2, rgb("FFD94D"))
    # facettes : moitié basse plus orangée, reflet en haut à gauche
    for i in range(5):
        a = -math.pi / 2 + i * 2 * math.pi / 5
        b = a + math.pi / 5
        tip = (cx + R * 0.86 * math.cos(a), cy + R * 0.86 * math.sin(a))
        v = (cx + r * 0.86 * math.cos(b), cy + r * 0.86 * math.sin(b))
        t.poly([tip, v, (cx, cy)], rgb("F0A800"))
    t.poly([(cx - 2.5, cy - 4), (cx, cy - 8.5), (cx + 0.5, cy - 3), (cx, cy)], rgb("FFF3B0"))
    t.ellipse(cx, cy, 1.4, 1.4, rgb("FFF3B0"))
    return t.fini()


def diamant():
    t = Toile()
    contour_ = [(6, 3.5), (18, 3.5), (22.5, 9.5), (12, 21.5), (1.5, 9.5)]
    t.poly(contour_, rgb("1A8AB0"))
    # facettes
    t.poly([(6, 3.5), (10, 3.5), (8.5, 9.5), (1.5, 9.5)], rgb("7FE3F8"))
    t.poly([(10, 3.5), (14, 3.5), (15.5, 9.5), (8.5, 9.5)], rgb("B8F2FF"))
    t.poly([(14, 3.5), (18, 3.5), (22.5, 9.5), (15.5, 9.5)], rgb("4FC8E8"))
    t.poly([(1.5, 9.5), (8.5, 9.5), (12, 21.5)], rgb("2BC4E8"))
    t.poly([(8.5, 9.5), (15.5, 9.5), (12, 21.5)], rgb("1E9CC8"))
    t.poly([(15.5, 9.5), (22.5, 9.5), (12, 21.5)], rgb("1A78A8"))
    t.ellipse(7.5, 6.3, 1.1, 0.8, rgb("FFFFFF"))
    return t.fini()


def couronne():
    t = Toile()
    or_, ombre, clair = rgb("F0A800"), rgb("B0681E"), rgb("FFF3B0")
    corps = [(3, 8), (7.5, 13), (12, 4.5), (16.5, 13), (21, 8), (19.5, 19.5), (4.5, 19.5)]
    t.poly(corps, ombre)
    t.poly([(3.6, 9.6), (7.6, 14), (12, 6.4), (16.4, 14), (20.4, 9.6), (19, 18.6), (5, 18.6)], or_)
    t.poly([(4.6, 11), (7.6, 14), (9.2, 11), (12, 6.4), (11.2, 12), (7.5, 18.6), (5, 18.6)], clair)
    # bandeau et joyaux
    t.rect(4.5, 18, 19.5, 21, ombre)
    t.rect(5, 18, 19, 20, rgb("FFD94D"))
    t.ellipse(12, 14.5, 1.7, 1.7, rgb("D41A28"))
    t.ellipse(7.2, 16, 1.2, 1.2, rgb("2ECF7B"))
    t.ellipse(16.8, 16, 1.2, 1.2, rgb("2ECF7B"))
    for x, y in ((3, 7.6), (12, 4), (21, 7.6)):
        t.ellipse(x, y, 1.6, 1.6, rgb("FFF3B0"))
    return t.fini()


# Le 7 : gabarit vectoriel (barre du haut et diagonale)
def sept():
    t = Toile()
    # contour or
    forme = [(3.5, 3.5), (21, 3.5), (21, 7), (11.5, 21.5), (6.5, 21.5), (14.5, 8.5), (3.5, 8.5)]
    t.poly(forme, rgb("F0A800"))
    inter = [(5, 5), (19.4, 5), (19.4, 6.6), (10.4, 20.2), (8.3, 20.2), (16.2, 7), (5, 7)]
    inter = [(4.9, 4.9), (19.5, 4.9), (19.5, 6.8), (10.6, 20.4), (8.2, 20.4), (16.4, 7.2), (4.9, 7.2)]
    t.poly(inter, rgb("D41A28"))
    t.poly([(4.9, 4.9), (19.5, 4.9), (19.5, 5.9), (4.9, 5.9)], rgb("FF7A70"))
    t.poly([(16.6, 7.2), (19.5, 6.8), (10.6, 20.4), (8.2, 20.4)], rgb("A8101C"))
    t.poly([(15.5, 7.6), (16.6, 7.2), (9.6, 20), (8.7, 20)], rgb("FF9A90"))
    return t.fini()


SYMBOLES = {"cerise": cerise, "cloche": cloche, "citron": citron, "couronne": couronne, "prune": prune,
            "sept": sept, "etoile": etoile, "diamant": diamant}
# Ordre sur le rouleau (de haut en bas) : DOIT être le même que SYMBOLS dans Slots.java.
ORDRE = ["cerise", "cloche", "citron", "couronne", "prune", "sept", "etoile", "diamant"]

if __name__ == "__main__":
    import sys
    planche = Image.new("RGBA", (N * 8 * 4 + 40, N * 4 * 1 + 10), (255, 246, 232, 255))
    for i, nom in enumerate(ORDRE):
        im = SYMBOLES[nom]()
        planche.alpha_composite(im.resize((N * 4, N * 4), Image.NEAREST), (5 + i * (N * 4 + 4), 5))
    planche.save(sys.argv[1])
