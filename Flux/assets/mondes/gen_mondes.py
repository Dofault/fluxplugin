#!/usr/bin/env python3
"""Fond du menu Mondes de /menu2 (Flux, Labs) : pack ItemsAdder « influmondes ».

    <venv avec Pillow>/python3 gen_mondes.py <menu_generique.png> <contents/influmondes> [dossier d'aperçus]

Même trame que l'accueil Villes et Pays (ville/gen_ville.py) : menu_metier.png à côté de menu_generique.png
fournit l'en-tête et l'onglet du titre, fond générique raccourci au coffre, cases arrondies de l'utilisateur.

Disposition (coffre de 6 rangées, inventaire masqué) :
- rangées 0 à 2 : quatre cartes illustrées des mondes ressource (2 colonnes x 3 rangées chacune), dans l'ordre
  de déblocage, quatre largeurs identiques sur toute la largeur du panneau (elles ne suivent donc pas les colonnes :
  boutons Atlas 0-1, Nether 2-3, End 5-6, Stratum 7-8, colonne 4 sans bouton, à cheval sur Nether et End) ;
- rangée 3 : étiquette « CONTINENTS » ;
- rangées 4 et 5 : Valdor, continent I (colonnes 0 à 3) et le continent II à venir (colonnes 5 à 8, voilé de
  brume, cadenas dessiné dans le fond). Colonne 4 : cases Utopia (40) et Retour (49).

Écrit textures/font/gui/menu_mondes.png, cadenas_monde.png et config.yml dans <contents/influmondes>.
Relancer après toute modification, puis /iareload, /iazip, /fluxmenu reload.
"""
import json
import math
import os
import random
import sys

from PIL import Image

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(ICI, ".."))
from fond_lib import generique_raccourci, objet, poser, tampon_case  # noqa: E402

GENERIQUE, SORTIE = sys.argv[1], sys.argv[2]
APERCUS = sys.argv[3] if len(sys.argv) > 3 else None
GUI = os.path.dirname(os.path.abspath(GENERIQUE))
METIER = os.path.join(GUI, "menu_metier.png")
# Cadenas du continent à venir : même texture que le bouton influitems:lock.
CADENAS = os.path.join(GUI, "..", "..", "..", "..", "influitems", "textures", "lock.png")
POLICE = json.load(open(os.path.join(ICI, "..", "metiers", "quinquefive.json")))
POLICE["Ô"] = POLICE["O"]
POLICE[" "] = ["...", "...", "...", "...", "..."]
ACCENTS = {"Ô": [(1, -1), (2, -2), (3, -1)]}


def hexa(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def melange(a, b, k):
    k = max(0.0, min(1.0, k))
    return tuple(int(a[i] + (b[i] - a[i]) * k) for i in range(3)) + (255,)


NOIR = hexa("15111b")
OR_VIF, OR_CHAUD = hexa("ffd94d"), hexa("f0a800")
BLANC = hexa("ffffff")
CADRE_CLAIR, CADRE_LAVANDE = hexa("e2cdff"), hexa("c49bff")
OR_CADRE, OR_CADRE_OMBRE = hexa("f2c85b"), hexa("a8822a")


class Toile:
    """Petite toile de pixel art : px() mélange les couleurs semi-transparentes, hors cadre ignoré."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.p = self.im.load()

    def px(self, x, y, c):
        x, y = int(x), int(y)
        if not (0 <= x < self.w and 0 <= y < self.h):
            return
        if len(c) == 4 and c[3] < 255:
            fond = self.p[x, y]
            k = c[3] / 255
            c = tuple(int(fond[i] + (c[i] - fond[i]) * k) for i in range(3)) + (max(fond[3], c[3]),)
        self.p[x, y] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(int(y0), int(y1) + 1):
            for x in range(int(x0), int(x1) + 1):
                self.px(x, y, c)

    def ciel(self, y0, y1, couleurs):
        """Dégradé vertical tramé en damier (même trame que le fond générique), couleurs réparties de y0 à y1."""
        n = len(couleurs) - 1
        for y in range(y0, y1 + 1):
            k = (y - y0) / max(1, y1 - y0) * n
            i = min(n - 1, int(k))
            base = melange(couleurs[i], couleurs[i + 1], k - i)
            alt = melange(base, couleurs[0], 0.12)
            for x in range(self.w):
                self.px(x, y, base if (x + y) % 2 else alt)

    def etoiles(self, rnd, n, y_max, couleurs):
        for _ in range(n):
            self.px(rnd.randrange(self.w), rnd.randrange(y_max), rnd.choice(couleurs))

    def scintillement(self, x, y, c=OR_VIF):
        for d in (-1, 1):
            self.px(x + d, y, c[:3] + (170,))
            self.px(x, y + d, c[:3] + (170,))
        self.px(x, y, BLANC)

    def relief(self, base, amp, periode, phase, y_max, c, c2=None):
        """Silhouette ondulée (collines, montagnes) remplie jusqu'en y_max ; renvoie la hauteur par colonne."""
        hauteurs = []
        for x in range(self.w):
            y = int(base - amp * math.sin(phase + x / periode) - amp * 0.4 * math.sin(phase * 2 + x / (periode / 2.3)))
            hauteurs.append(y)
            for yy in range(y, y_max + 1):
                self.px(x, yy, c if c2 is None or (x + yy) % 2 else c2)
        return hauteurs

    def sapin(self, x, sol, h, c, tronc=hexa("4a3020")):
        for i in range(h):
            r = (i + 1) // 2
            self.rect(x - r, sol - h + i, x + r, sol - h + i, c)
        self.px(x, sol, tronc)

    def couronne(self, cx, y):
        """Petite couronne dorée (motif de la charte), 7 x 5."""
        for dx in (-3, 0, 3):
            self.px(cx + dx, y, OR_VIF)
        for dx in (-3, -2, 0, 2, 3):
            self.px(cx + dx, y + 1, OR_VIF)
        self.rect(cx - 3, y + 2, cx + 3, y + 3, OR_CHAUD)
        self.px(cx - 1, y + 2, hexa("e060a0"))
        self.px(cx + 1, y + 2, hexa("e060a0"))
        self.rect(cx - 3, y + 4, cx + 3, y + 4, hexa("a8822a"))


# ── Illustrations des mondes (cartes 29 x 48) ──────────────────────────────
def atlas(w, h):
    """Monde ressource de base : jour, collines et sapins en surface, coupe du sous-sol riche en minerais."""
    rnd = random.Random(3)
    t = Toile(w, h)
    t.ciel(0, 27, [hexa("3f86d6"), hexa("7ec0ee"), hexa("c9ecfb")])
    # soleil et nuages
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            if dx * dx + dy * dy <= 9:
                t.px(22 + dx, 6 + dy, hexa("fff1a8") if dx * dx + dy * dy <= 3 else OR_VIF)
    for x0, y0, lg in ((2, 9, 8), (13, 3, 6)):
        t.rect(x0, y0, x0 + lg, y0 + 1, hexa("ffffff", 230))
        t.rect(x0 + 2, y0 - 1, x0 + lg - 3, y0 - 1, hexa("ffffff", 230))
        t.rect(x0 + 1, y0 + 2, x0 + lg - 1, y0 + 2, hexa("dcefff", 200))
    # montagnes lointaines enneigées
    sommets = t.relief(19, 4, 3.2, 0.8, 27, hexa("7c9cc4"), hexa("86a6cc"))
    for x, y in enumerate(sommets):
        if y <= 17:
            t.rect(x, y, x, y + 1, hexa("eef4ff"))
    # collines et sapins
    t.relief(24, 2, 4.5, 2.0, 30, hexa("4f9a3e"), hexa("57a545"))
    sol = t.relief(28, 1, 6, 0.4, 30, hexa("62b84a"), hexa("6cc24f"))
    for x in (3, 7, 21, 25):
        t.sapin(x, sol[x] - 1, rnd.choice((6, 7, 8)), hexa("2f6b3b"))
    t.sapin(14, sol[14] - 1, 9, hexa("27593a"))
    # coupe du sol : terre, pierre, ardoise des profondeurs
    for x in range(w):
        for y in range(sol[x] + 1, h):
            prof = y - sol[x]
            if prof <= 3:
                c = hexa("7a5233") if (x + y) % 2 else hexa("845b39")
            elif y < h - 7:
                c = hexa("6d6a7a") if (x + y) % 2 else hexa("65627a")
            else:
                c = hexa("3e3a4c") if (x + y) % 2 else hexa("443f53")
            t.px(x, y, c)
        t.px(x, sol[x], hexa("7fd35a"))
    # grotte éclairée d'une torche
    for dy in range(-2, 3):
        for dx in range(-6, 7):
            if dx * dx / 36 + dy * dy / 6.5 <= 1:
                t.px(6 + dx, 40 + dy, hexa("2a2733"))
    t.px(3, 39, hexa("ffb04d"))
    t.px(3, 40, hexa("6b4a2a"))
    t.px(3, 38, hexa("ffb04d", 110))
    # minerais : charbon, fer, or, redstone, diamants au fond
    for x, y, c, hl in ((20, 34, "1f1f24", "3a3a44"), (4, 35, "1f1f24", "3a3a44"), (24, 39, "d6a88a", "f2d2bd"),
                        (14, 37, "d6a88a", "f2d2bd"), (17, 42, "ffd94d", "fff1a8"), (25, 44, "e03a3a", "ff8a8a"),
                        (10, 44, "5ee6e6", "d4ffff"), (19, 45, "5ee6e6", "d4ffff")):
        t.rect(x, y, x + 1, y + 1, hexa(c))
        t.px(x, y, hexa(hl))
    return t.im


def nether(w, h):
    """Plafond de netherrack, pierre lumineuse, coulée de lave, forteresse au loin et mer de lave."""
    rnd = random.Random(7)
    t = Toile(w, h)
    t.ciel(0, h - 1, [hexa("2a0808"), hexa("5a1310"), hexa("a3301a")])
    # plafond déchiqueté et stalactites
    for x in range(w):
        bas = 3 + int(2 * math.sin(x / 2.1)) + (rnd.randrange(0, 3) if x % 5 == 0 else 0)
        for y in range(bas + 1):
            t.px(x, y, hexa("6e1f1f") if (x + y) % 2 else hexa("7d2626"))
        t.px(x, bas + 1, hexa("4a1414"))
    for x0, lg in ((4, 7), (19, 9), (12, 4)):
        for i in range(lg):
            t.px(x0, 4 + i, hexa("6e1f1f"))
            if i < lg - 3:
                t.px(x0 + 1, 4 + i, hexa("5c1818"))
    # pierre lumineuse
    for x, y in ((8, 5), (23, 4), (15, 6)):
        t.rect(x, y, x + 2, y + 1, hexa("f0a800"))
        t.px(x + 1, y, hexa("ffe39a"))
        t.px(x, y + 2, hexa("ffd27a"))
    # coulée de lave depuis le plafond
    for y in range(5, h - 8):
        t.px(25, y, hexa("ff7a1a"))
        t.px(26, y, hexa("ffb03a") if y % 3 else hexa("ffd94d"))
    # forteresse au loin : pont crénelé sur piliers, fenêtres orangées
    pont = 27
    t.rect(0, pont, 22, pont + 2, hexa("2a0f14"))
    for x in range(0, 23, 2):
        t.px(x, pont - 1, hexa("2a0f14"))
    for x in (3, 11, 19):
        t.rect(x, pont + 3, x + 2, h - 6, hexa("2a0f14"))
    t.rect(8, pont - 9, 15, pont - 1, hexa("2a0f14"))
    for x in range(8, 16, 2):
        t.px(x, pont - 10, hexa("2a0f14"))
    for x, y in ((10, pont - 6), (13, pont - 6), (10, pont - 3)):
        t.px(x, y, hexa("f0a800"))
    # falaises de netherrack au premier plan, nylium carmin
    for x in range(w):
        haut = h - 9 + int(3 * math.sin(x / 3.0 + 1)) if x < 9 else h
        for y in range(haut, h):
            t.px(x, y, hexa("5a1a1a") if (x + y) % 2 else hexa("621d1d"))
        if haut < h:
            t.px(x, haut, hexa("b0243a"))
    t.rect(4, h - 15, 4, h - 12, hexa("6e2a1a"))
    t.rect(2, h - 17, 6, h - 16, hexa("c4243a"))
    t.px(3, h - 16, hexa("ff8a3a"))
    # mer de lave et braises
    for y in range(h - 6, h):
        for x in range(9, w):
            t.px(x, y, hexa("ff6a00") if (x + y) % 2 else hexa("ff8a1a"))
    for x in range(10, w, 4):
        t.px(x + (x // 4) % 2, h - 6, hexa("ffd94d"))
    for _ in range(9):
        t.px(rnd.randrange(w), rnd.randrange(8, h - 8), hexa("ffb04d", 200))
    return t.im


def end(w, h):
    """Ciel étoilé violet, île de pierre de l'End, piliers d'obsidienne et cristaux, dragon au loin."""
    rnd = random.Random(11)
    t = Toile(w, h)
    t.ciel(0, h - 1, [hexa("07060f"), hexa("140a2a"), hexa("2a1250")])
    t.etoiles(rnd, 22, h - 14, [BLANC, hexa("d9c4ff"), hexa("b376e5"), OR_VIF])
    t.scintillement(5, 6)
    # dragon : silhouette ailes déployées, œil rose
    dx, dy = 15, 9
    for i, lg in enumerate((1, 3, 5, 3)):
        t.rect(dx - 5 - i, dy + i - 1, dx - 5 - i + lg, dy + i - 1, hexa("0d0b14"))
        t.rect(dx + 5 + i - lg, dy + i - 1, dx + 5 + i, dy + i - 1, hexa("0d0b14"))
    t.rect(dx - 3, dy + 1, dx + 3, dy + 2, hexa("0d0b14"))
    t.rect(dx + 4, dy + 1, dx + 6, dy + 1, hexa("0d0b14"))
    t.px(dx + 6, dy + 1, hexa("e060a0"))
    t.px(dx - 4, dy + 3, hexa("0d0b14"))
    t.px(dx - 5, dy + 4, hexa("0d0b14"))
    # piliers d'obsidienne et cristaux de l'End
    ile = h - 12
    for x, haut in ((3, 22), (12, 30), (22, 17)):
        t.rect(x, ile - haut, x + 3, ile, hexa("1a1028"))
        t.rect(x, ile - haut, x, ile, hexa("3a2a5a"))
        cy = ile - haut - 4
        for r, c in ((3, hexa("b376e5", 70)), (2, hexa("e060a0", 150))):
            for yy in range(-r, r + 1):
                for xx in range(-r, r + 1):
                    if abs(xx) + abs(yy) <= r:
                        t.px(x + 1 + xx + 1, cy + yy, c)
        t.px(x + 2, cy, BLANC)
    # île de pierre de l'End, dessous qui s'effrite
    for x in range(w):
        haut = ile + int(1.5 * math.sin(x / 2.5))
        for y in range(haut, h):
            prof = y - haut
            if prof == 0:
                c = hexa("f3efb8")
            elif prof < 5:
                c = hexa("e2dc98") if (x + y) % 2 else hexa("d5cf88")
            else:
                c = hexa("a49e5e") if (x + y) % 2 else hexa("958f52")
            t.px(x, y, c)
    # plantes de chorus : tige, branches qui remontent, fleurs claires
    for x0, haut in ((8, 9), (19, 7)):
        tige, fleur = hexa("8f5fb0"), hexa("e3c7ff")
        t.rect(x0, ile - haut, x0, ile - 1, tige)
        t.rect(x0 - 2, ile - 4, x0 - 1, ile - 4, tige)
        t.rect(x0 - 2, ile - 6, x0 - 2, ile - 5, tige)
        t.rect(x0 + 1, ile - 6, x0 + 2, ile - 6, tige)
        t.rect(x0 + 2, ile - 8, x0 + 2, ile - 7, tige)
        for fx, fy in ((x0, ile - haut - 1), (x0 - 2, ile - 7), (x0 + 2, ile - 9)):
            t.px(fx, fy, fleur)
            t.px(fx, fy + 1, hexa("b98ad8"))
    return t.im


def stratum(w, h):
    """Monde élite glacial : aurore boréale, couronne, pics enneigés truffés de filons (bien plus riche en minerais
    que le vanilla), lac gelé et cristaux rares."""
    rnd = random.Random(19)
    t = Toile(w, h)
    t.ciel(0, h - 1, [hexa("060d1f"), hexa("10244a"), hexa("2b4f80")])
    t.etoiles(rnd, 16, 24, [BLANC, hexa("cfe8ff")])
    # aurore : rideaux verticaux ondulés (vert, bleu, violet de la charte)
    for couleur, base, amp, phase in ((hexa("2ecf7b"), 15, 4, 0.0), (hexa("2bc4e8"), 19, 3, 1.7), (hexa("b376e5"), 12, 3, 3.1)):
        for x in range(w):
            y = base + int(amp * math.sin(phase + x / 3.6))
            for k in range(7):
                t.px(x, y - k, couleur[:3] + (int(140 * (1 - k / 7)),))
    t.couronne(14, 2)
    # pics enneigés : roche, crêtes de neige, ombre bleutée
    for cx, haut, demi in ((7, 17, 9), (20, 25, 11)):
        sommet = h - 14 - haut
        for y in range(sommet, h - 13):
            r = int((y - sommet) * demi / haut) + 1
            for x in range(cx - r, cx + r + 1):
                k = (y - sommet) / haut
                if k < 0.35 or (x - cx) % 5 == (y // 2) % 5 and k < 0.7:
                    c = hexa("f2f7ff") if x <= cx else hexa("b9cae4")
                else:
                    c = hexa("46587a") if x <= cx else hexa("35435f")
                    if k >= 0.45 and rnd.random() < 0.16:
                        c = hexa(rnd.choice(("5ee6e6", "ffd94d", "2ecf7b", "d6a88a", "5ee6e6")))
                t.px(x, y, c)
    # lac gelé et reflets
    for y in range(h - 13, h):
        for x in range(w):
            t.px(x, y, hexa("8fd0ee") if (x + y) % 2 else hexa("9ad8f2"))
    for x0, y0, lg in ((3, h - 11, 6), (15, h - 8, 8), (8, h - 4, 5)):
        t.rect(x0, y0, x0 + lg, y0, hexa("e6f8ff"))
    # rochers et grappes de cristaux rares (améthyste, diamant)
    for x0, c, hl in ((1, "b376e5", "e9d4ff"), (22, "5ee6e6", "d4ffff")):
        t.rect(x0, h - 3, x0 + 5, h - 1, hexa("2a3044"))
        t.rect(x0 + 1, h - 4, x0 + 4, h - 4, hexa("2a3044"))
        t.rect(x0 + 2, h - 9, x0 + 3, h - 5, hexa(c))
        t.px(x0 + 2, h - 10, hexa(hl))
        t.px(x0 + 2, h - 8, hexa(hl))
        t.px(x0, h - 6, hexa(c))
        t.px(x0 + 1, h - 5, hexa(c))
        t.px(x0 + 5, h - 7, hexa(c))
        t.px(x0 + 4, h - 6, hexa(c))
        t.px(x0 + 5, h - 8, hexa(hl))
    # flocons
    for _ in range(10):
        t.px(rnd.randrange(w), rnd.randrange(14, h - 14), hexa("ffffff", 200))
    return t.im


# ── Illustrations des continents (cartes 66 x 30) ──────────────────────────
NIVEAUX_VILLE = ("ffd54f", "ffb02e", "ff922b", "ff6b35", "e63946")


def maison(t, x, sol, toit, haute=False):
    hm = 4 if haute else 3
    t.rect(x, sol - hm, x + 3, sol, hexa("efe2c6"))
    t.rect(x - 1, sol - hm - 1, x + 4, sol - hm - 1, hexa(toit))
    t.rect(x, sol - hm - 2, x + 3, sol - hm - 2, hexa(toit))
    t.px(x + 1, sol - 1, hexa("5b2d9e"))
    t.px(x + 3, sol - hm + 1, OR_VIF)


def valdor(w, h):
    """Continent des villes au soleil couchant : plaines, rivière, villes et leurs drapeaux menthe."""
    rnd = random.Random(23)
    t = Toile(w, h)
    t.ciel(0, 14, [hexa("4b4fb8"), hexa("b073c0"), hexa("ffc477")])
    for dy in range(-4, 1):
        for dx in range(-5, 6):
            if dx * dx + dy * dy <= 22:
                t.px(51 + dx, 13 + dy, hexa("fff1a8") if dx * dx + dy * dy <= 8 else OR_VIF)
    t.etoiles(rnd, 5, 6, [hexa("ffffff", 200)])
    t.relief(12, 2, 5, 1.0, 16, hexa("7a68b0"), hexa("8270b8"))
    sol = t.relief(15, 1, 7, 0.2, h - 1, hexa("5aae47"), hexa("63b84d"))
    for y in range(20, h):
        for x in range(w):
            if (x + y) % 2:
                t.px(x, y, hexa("4f9f3f"))
    # rivière sinueuse jusqu'à l'horizon
    for y in range(min(sol), h):
        k = (y - 14) / (h - 14)
        cx = int(30 + 9 * math.sin(y / 3.3)) + int(k * 4)
        lg = 1 + int(k * 4)
        t.rect(cx - lg, y, cx + lg, y, hexa("2bc4e8"))
        t.px(cx - lg + 1, y, hexa("9be9f8"))
    # villes : maisons aux toits des niveaux de ville, drapeau menthe, limites revendiquées en pointillés
    for vx, vy, n in ((4, 22, 4), (44, 24, 4), (18, 18, 3), (54, 19, 2)):
        for i in range(n):
            maison(t, vx + i * 5, vy - (1 if i % 2 else 0), NIVEAUX_VILLE[(vx + i) % 5], haute=(i == n // 2))
        mx = vx + (n // 2) * 5 + 1
        t.rect(mx, vy - 11, mx, vy - 6, hexa("2a2040"))
        t.rect(mx + 1, vy - 11, mx + 3, vy - 10, hexa("97fc79"))
        for x in range(vx - 2, vx + n * 5 + 1, 2):
            t.px(x, vy + 2, hexa("97fc79"))
    # bosquets
    for _ in range(14):
        x, y = rnd.randrange(w), rnd.randrange(17, h - 1)
        t.rect(x, y, x + 1, y, hexa("2f6b3b"))
        t.px(x, y - 1, hexa("3c8048"))
    return t.im


def continent_2(w, h):
    """Continent à venir : océan nocturne, îles devinées dans la brume, navire au loin. Volontairement voilé."""
    rnd = random.Random(29)
    t = Toile(w, h)
    t.ciel(0, 12, [hexa("101a33"), hexa("23365e"), hexa("3d5a86")])
    t.etoiles(rnd, 10, 9, [BLANC, hexa("cfe8ff")])
    for y in range(12, h):
        for x in range(w):
            t.px(x, y, hexa("1d4a73") if (x + y) % 2 else hexa("22547f"))
    for _ in range(26):
        x, y = rnd.randrange(w - 2), rnd.randrange(14, h)
        t.rect(x, y, x + 2, y, hexa("5fa6d4"))
    # îles de l'archipel
    for cx, demi, haut in ((10, 9, 6), (36, 12, 8), (58, 7, 4)):
        for x in range(cx - demi, cx + demi + 1):
            y0 = 13 - int(haut * (1 - ((x - cx) / demi) ** 2))
            t.rect(x, y0, x, 13, hexa("1f3b35"))
        t.rect(cx - 1, 13 - haut - 4, cx - 1, 13 - haut, hexa("2a2a22"))
        t.rect(cx - 4, 13 - haut - 5, cx + 2, 13 - haut - 5, hexa("1f3b35"))
    # navire : coque et voile
    t.rect(46, 19, 52, 20, hexa("3a2a22"))
    t.rect(48, 13, 48, 18, hexa("3a2a22"))
    t.rect(49, 13, 51, 17, hexa("d8d0c0"))
    # brume : bandes laiteuses
    for y0, a in ((8, 70), (14, 90), (22, 60)):
        for y in range(y0, y0 + 3):
            for x in range(w):
                t.px(x, y, hexa("c8c8e0", int(a * (0.6 + 0.4 * math.sin(x / 6 + y0)))))
    # voile : continent pas encore découvert
    for y in range(h):
        for x in range(w):
            t.px(x, y, hexa("2a2440", 90))
    return t.im


# ── Cadres, textes et en-tête (repris de ville/gen_ville.py) ──────────────
def carte(im, x0, y0, x1, y1):
    """Cadre fin des cartes : noir, lavande (liseré clair en haut), noir, petits coins dorés."""
    px = im.load()
    for i, c in enumerate((NOIR, CADRE_LAVANDE, NOIR)):
        for x in range(x0 + i, x1 - i + 1):
            px[x, y0 + i] = CADRE_CLAIR if i == 1 else c
            px[x, y1 - i] = c
        for y in range(y0 + i, y1 - i + 1):
            px[x0 + i, y] = c
            px[x1 - i, y] = c
    ix0, iy0, ix1, iy1 = x0 + 3, y0 + 3, x1 - 3, y1 - 3
    for d in range(3):
        for k in range(3 - d):
            c = OR_CADRE_OMBRE if k == 2 - d else OR_CADRE
            px[ix0 + k, iy0 + d] = c
            px[ix1 - k, iy0 + d] = c
            px[ix0 + k, iy1 - d] = c
            px[ix1 - k, iy1 - d] = c


def entete(im):
    """En-tête du menu Métiers (bord du haut, bandeau sombre, ligne) recopié sans son onglet ni son curseur."""
    m = Image.open(METIER).convert("RGBA")
    mp, px = m.load(), im.load()
    for y in range(0, 16):
        for x in range(176):
            if 3 <= y <= 15 and 6 <= x <= 169:
                px[x, y] = mp[8 + (x % 2), y]
            else:
                px[x, y] = mp[x, y]


def largeur_texte(txt):
    return sum(len(POLICE[c][0]) + 1 for c in txt) - 1


def texte(im, txt, cx, y, couleur=hexa("ffffff"), ombre=hexa("000000")):
    """Texte QuinqueFive centré sur cx, ombre d'un pixel dessous."""
    x = cx - largeur_texte(txt) // 2
    px = im.load()
    for c in txt:
        g = POLICE[c]
        for dy, ligne in enumerate(g):
            for dx, ch in enumerate(ligne):
                if ch == "#":
                    px[x + dx, y + dy + 1] = ombre
        for dy, ligne in enumerate(g):
            for dx, ch in enumerate(ligne):
                if ch == "#":
                    px[x + dx, y + dy] = couleur
        for dx, dy in ACCENTS.get(c, []):
            px[x + dx, y + dy] = couleur
        x += len(g[0]) + 1
    return x


def onglet(im, txt, cx):
    """Onglet du titre encastré dans le bandeau, relevé sur l'onglet « METIERS »."""
    w = largeur_texte(txt) + 12
    x0, x1 = cx - w // 2, cx - w // 2 + w
    px = im.load()
    fond, reflet = hexa("594674"), hexa("7f66a3")
    for x in range(x0 + 1, x1):
        px[x, 3] = NOIR
    for y in range(4, 15):
        px[x0, y] = NOIR
        px[x1, y] = NOIR
        for x in range(x0 + 1, x1):
            px[x, y] = fond
    px[x0 + 1, 4] = NOIR
    px[x1 - 1, 4] = NOIR
    for x in range(x0 + 2, x1 - 1):
        px[x, 5] = reflet
    texte(im, txt, cx, 7)


def plaque(im, txt, cx, y0):
    """Plaque de titre (trame de l'onglet) posée à cheval sur le bas d'une carte."""
    w = largeur_texte(txt) + 10
    px = im.load()
    for yy in range(y0, y0 + 10):
        for xx in range(cx - w // 2, cx + w // 2 + 1):
            bord = yy in (y0, y0 + 9) or xx in (cx - w // 2, cx + w // 2)
            px[xx, yy] = NOIR if bord else hexa("594674")
    for xx in range(cx - w // 2 + 1, cx + w // 2):
        px[xx, y0 + 1] = hexa("7f66a3")
    texte(im, txt, cx, y0 + 3)


def numero(im, txt, x0, y0):
    """Pastille du numéro de continent (chiffres romains) dans le coin haut gauche de la carte."""
    w = largeur_texte(txt) + 6
    px = im.load()
    for yy in range(y0, y0 + 9):
        for xx in range(x0, x0 + w):
            bord = yy in (y0, y0 + 8) or xx in (x0, x0 + w - 1)
            px[xx, yy] = NOIR if bord else hexa("2a1a44")
    texte(im, txt, x0 + w // 2, y0 + 2, couleur=OR_VIF)


def etoile(im, cx, cy):
    """Étoile dorée à quatre branches, cœur blanc (motif de la charte)."""
    px = im.load()
    for d in (1, 2):
        for x, y in ((cx + d, cy), (cx - d, cy), (cx, cy + d), (cx, cy - d)):
            px[x, y] = OR_VIF if d == 1 else OR_CHAUD
    px[cx, cy] = BLANC


# ── Fond du menu ───────────────────────────────────────────────────────────
HAUT, BAS = 1, 139
# Cartes des mondes : rangées 0 à 2, panneau de x 7 à 168 partagé en 4 cartes de 39 px, 2 px d'écart.
LARGEUR_CARTE, ECART = 39, 2
MONDES = [((7 + i * (LARGEUR_CARTE + ECART), 17, 7 + i * (LARGEUR_CARTE + ECART) + LARGEUR_CARTE - 1, 70), dessin)
          for i, dessin in enumerate((atlas, nether, end, stratum))]
# Continents : 4 colonnes x rangées 4 et 5.
VALDOR = (7, 89, 78, 124)
CONTINENT_2 = (97, 89, 168, 124)
CASES = [40, 49]  # Utopia, Retour


def fond_mondes():
    im = generique_raccourci(os.path.abspath(GENERIQUE), HAUT, BAS)
    entete(im)
    for (x0, y0, x1, y1), dessin in MONDES:
        im.alpha_composite(dessin(x1 - x0 - 5, y1 - y0 - 5), (x0 + 3, y0 + 3))
        carte(im, x0, y0, x1, y1)
    for (x0, y0, x1, y1), dessin in ((VALDOR, valdor), (CONTINENT_2, continent_2)):
        im.alpha_composite(dessin(x1 - x0 - 5, y1 - y0 - 5), (x0 + 3, y0 + 3))
        carte(im, x0, y0, x1, y1)
    cadenas = Image.open(CADENAS).convert("RGBA")
    x0, y0, x1, y1 = CONTINENT_2
    im.alpha_composite(cadenas, ((x0 + x1) // 2 - 8, y0 + 5))
    plaque(im, "VALDOR", (VALDOR[0] + VALDOR[2]) // 2, VALDOR[3] - 5)
    plaque(im, "BIENTÔT", (x0 + x1) // 2, y1 - 5)
    numero(im, "I", VALDOR[0] + 4, VALDOR[1] + 4)
    numero(im, "II", x0 + 4, y0 + 4)
    px = im.load()
    # étiquette « CONTINENTS » entre deux filets dorés
    fin = texte(im, "CONTINENTS", 88, 78, ombre=hexa("0f0c13"))
    debut = 88 - largeur_texte("CONTINENTS") // 2
    for x in list(range(14, debut - 5)) + list(range(fin + 4, 163)):
        if x % 2 == 0:
            px[x, 80] = OR_CADRE
    etoile(im, 11, 80)
    etoile(im, 165, 80)
    onglet(im, "MONDES", 88)
    tampon = tampon_case()
    for c in CASES:
        poser(im, tampon, *objet(c))
    return im


# Cadenas d'un monde verrouillé : glyphe de titre 16 x 16 (même texture que influitems:lock), posé au centre de la
# carte par un calque title-layers de mondes.yml, seulement pour le joueur sans la permission du monde. Un objet de
# menu décalé sur deux cases était rogné à sa case par le jeu, même avec oversized_in_gui (essai du 2026-10-24).
# Haut du cadenas à y = 36 (centre de la rangée 1) : y_position = 13 - 36.
CADENAS_Y = 36


def avance(png):
    """Avance d'un glyphe de police bitmap : le jeu la calcule jusqu'à la dernière colonne non transparente (+ 1 px
    d'espacement), pas sur la largeur de l'image. Le cadenas a une colonne vide à droite : 16, pas 17."""
    im = Image.open(png).convert("RGBA")
    return max(x for x in range(im.width) for y in range(im.height) if im.getpixel((x, y))[3]) + 2


def calques_cadenas():
    """Texte des title-layers de mondes.yml : du curseur laissé par le fond (x = 177, soit -8 puis 176 + 1) au bord
    gauche du cadenas, centré sur la partie visible de l'image, puis retour à 177."""
    im = Image.open(CADENAS).convert("RGBA")
    visibles = [x for x in range(im.width) if any(im.getpixel((x, y))[3] for y in range(im.height))]
    milieu = (visibles[0] + visibles[-1] + 1) / 2
    out = {}
    for nom, ((x0, _, x1, _), _) in zip(("atlas", "nether", "end", "stratum"), MONDES):
        x = math.floor((x0 + x1 + 1) / 2 - milieu + 0.5)
        out[nom] = ":offset_%d::cadenas_monde::offset_%d:" % (x - 177, 177 - (x + avance(CADENAS)))
    return out


def main():
    gui = os.path.join(SORTIE, "textures", "font", "gui")
    os.makedirs(gui, exist_ok=True)
    im = fond_mondes()
    im.save(os.path.join(gui, "menu_mondes.png"))
    Image.open(CADENAS).convert("RGBA").save(os.path.join(gui, "cadenas_monde.png"))
    with open(os.path.join(SORTIE, "config.yml"), "w") as f:
        f.write("\n".join([
            "# Généré par plugins/fluxplugin/Flux/assets/mondes/gen_mondes.py : ne pas modifier à la main.",
            "# Menu Mondes de /menu2 (Flux, Labs) : fond illustré des mondes et continents.",
            "info:",
            "  namespace: influmondes",
            "font_images:",
            "  # Fond plein écran 176 x 221 (coffre de 6 rangées, inventaire masqué).",
            "  menu_mondes:",
            "    suggest_in_command: false",
            "    show_in_gui: false",
            "    path: font/gui/menu_mondes.png",
            "    scale_ratio: 221",
            "    y_position: 13",
            "  # Cadenas d'un monde verrouillé, ajouté au titre par les title-layers de mondes.yml.",
            "  cadenas_monde:",
            "    suggest_in_command: false",
            "    show_in_gui: false",
            "    path: font/gui/cadenas_monde.png",
            "    scale_ratio: 16",
            "    y_position: %d" % (13 - CADENAS_Y),
        ]) + "\n")
    if APERCUS:
        os.makedirs(APERCUS, exist_ok=True)
        im.resize((176 * 4, 221 * 4), Image.NEAREST).save(os.path.join(APERCUS, "menu_mondes.png"))
    print("menu_mondes ->", SORTIE)
    for nom, texte_calque in calques_cadenas().items():
        print("  calque cadenas_%s : %s" % (nom, texte_calque))


main()
