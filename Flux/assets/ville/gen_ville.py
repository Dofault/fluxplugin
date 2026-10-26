#!/usr/bin/env python3
"""Visuels des menus Ville (FluxLands + Flux) : pack ItemsAdder « influville ».

    <venv avec Pillow>/python3 gen_ville.py <menu_generique.png> <contents/influville> [dossier d'aperçus]

menu_metier.png doit se trouver à côté de menu_generique.png (même dossier font/gui d'influmenu) : son en-tête
(bord du haut, bandeau, onglet du titre) et le cadre doré de son illustration servent de modèle à tous les fonds.

Écrit dans <contents/influville> :
- textures/icones/*.png : icônes de boutons 16 x 16 au gabarit exact des boutons influitems (contour, reflet
  en haut, bande en bas, pictogramme dans la teinte foncée de la tuile, ombre portée d'un pixel) ;
- textures/icones/niveau_1..7.png : les 7 niveaux de ville (de Lotissement à Métropole), des bâtiments qui
  grandissent, toit à la couleur du niveau (charte, Lands/levels.yml) ;
- textures/icones/barre_1..9.png : jauges du graphique des recettes (menu Commerce) ;
- textures/font/gui/*.png : fonds de menus 176 x 221 (en-tête et onglet du menu Métiers, fond générique
  raccourci au coffre, cases arrondies de l'utilisateur, bandeau « ligne d'horizon » de ville sur les rangées 0
  et 1 dans le cadre doré du menu Métiers) ;
- config.yml : items et font_images.
Relancer après toute modification, puis /iareload, /iazip, /fluxmenu reload.
"""
import json
import os
import random
import sys

from PIL import Image

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(ICI, ".."))
from fond_lib import generique_raccourci, objet, poser, tampon_case  # noqa: E402

GENERIQUE, SORTIE = sys.argv[1], sys.argv[2]
APERCUS = sys.argv[3] if len(sys.argv) > 3 else None
METIER = os.path.join(os.path.dirname(os.path.abspath(GENERIQUE)), "menu_metier.png")
POLICE = json.load(open(os.path.join(ICI, "..", "metiers", "quinquefive.json")))
# Accents et ponctuation absents de QuinqueFive : lettre de base, accent ajouté au rendu (ACCENTS).
POLICE["É"] = POLICE["È"] = POLICE["E"]
POLICE["Ô"] = POLICE["O"]
POLICE[" "] = ["...", "...", "...", "...", "..."]
POLICE["'"] = ["#", "#", ".", ".", "."]
ACCENTS = {"É": [(3, -2), (2, -1)], "È": [(1, -2), (2, -1)], "Ô": [(1, -1), (2, -2), (3, -1)]}


def hexa(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


NOIR = hexa("15111b")

# ── Icônes : gabarit des tuiles influitems ─────────────────────────────────
# B reflet, C tuile, D bords clairs, E pictogramme / bande, F ombre du pictogramme (relevés sur influitems).
PALETTES = {
    "lavande": ("ebe6f3", "9b86c6", "beb0d9", "45365f", "b1a0d2"),
    "vert": ("dbecde", "4fa45c", "8cc395", "2f6b3b", "75b87f"),
    "or": ("f5ead5", "d19a2e", "e1bd77", "8a5a14", "dbb05b"),
    "bleu": ("d8e8f4", "3f8ccc", "82b4dd", "25557f", "6aa5d6"),
    "orange": ("f4e6d8", "cc863f", "ddb082", "7f5225", "d69e62"),
    "rose": ("f5dfea", "cf5f9a", "df97bd", "7a2d58", "d87bab"),
    "rouge": ("f5dcdd", "cf5058", "df8d92", "8a2c38", "d86f77"),
    # Menthe : couleur réservée aux Villes dans la charte (#97fc79), assombrie pour une tuile lisible.
    "menthe": ("e0f6da", "5fb950", "9bd68f", "2d6a2a", "7fca71"),
}

GABARIT = [
    "................",
    "..AAAAAAAAAAAA..",
    ".AABBBBBBBBBBAA.",
    ".ABCCCCCCCCCCBA.",
    ".ADCCCCCCCCCCDA.",
    ".ADCCCCCCCCCCDA.",
    ".ADCCCCCCCCCCDA.",
    ".ADCCCCCCCCCCDA.",
    ".ADCCCCCCCCCCDA.",
    ".ADCCCCCCCCCCDA.",
    ".ADCCCCCCCCCCDA.",
    ".ADEEEEEEEEEEDA.",
    ".ADDDDDDDDDDDDA.",
    ".AAEEEEEEEEEEAA.",
    "..AAAAAAAAAAAA..",
    "................",
]

# Pictogrammes 8 x 6 (rangées 4 à 9, colonnes 4 à 11), ombre ajoutée dessous.
PICTOS = {
    "ville": ["...#....", "..###.#.", "..#.#.#.", "#.###.##", "#.#.#.##", "########"],
    "banque": ["...##...", ".######.", "........", ".#.##.#.", ".#.##.#.", "########"],
    "habitants": ["...##...", ".#.##.#.", ".#....#.", "#.####.#", "#.####.#", "..####.."],
    "echoppe": ["########", "#.#.#.#.", ".######.", ".#....#.", ".#.##.#.", ".######."],
    "warp": ["..#.....", ".#####..", ".######.", ".#####..", "..#.....", ".###...."],
    "annuaire": ["##.#####", "........", "##.#####", "........", "##.#####", "........"],
    "commerce": ["...##...", "########", ".#....#.", "###..###", "...##...", ".######."],
    "spawn": [".#####..", ".######.", ".#####..", ".#......", ".#......", "###....."],
    "epingle": ["..###...", ".#####..", ".#####..", "..###...", "...#....", "...#...."],
    "lier": [".###....", "#...#...", "#..####.", ".####..#", "...#...#", "....###."],
    "changer": [".....#..", ".######.", ".....#..", "..#.....", ".######.", "..#....."],
    "chunks": ["###.###.", "#.#.#.#.", "###.###.", "###.###.", "#.#.#.#.", "###.###."],
    "entretien": ["..##..#.", ".#..#.#.", ".#..#.#.", "..##.###", "......#.", "........"],
    "nation": [".######.", ".#.##.#.", ".######.", ".#....#.", ".#......", ".#......"],
    "niveau": ["......##", "......##", "....####", "....####", "..######", "########"],
    "cle": ["........", ".##.....", "#..#####", "#..##.#.", ".##.....", "........"],
    "vente": ["...#####", "..#....#", ".#.##..#", "#..##..#", ".#.....#", "..######"],
    "parcelle": ["#.#.#.#.", "########", "#......#", "#......#", "########", "#.#.#.#."],
    "agence": ["..#.....", ".###....", "#####.##", "#.#.#.##", "#.#.#..#", "#####..#"],
    "roles": ["#..##..#", "##.##.##", "########", "########", ".######.", "........"],
    "parametres": [".######.", "#.##...#", ".######.", ".######.", "#...##.#", ".######."],
    "deposer": ["...##...", "...##...", ".######.", "..####..", "#..##..#", "########"],
    "retirer": ["...##...", "..####..", ".######.", "...##...", "#..##..#", "########"],
    "renommer": ["......##", ".....###", "....###.", "...###..", ".####...", ".##....."],
    "message": [".######.", "#......#", "#.####.#", "#......#", ".######.", ".#......"],
    "periode": [".######.", "..####..", "...##...", "..#..#..", ".######.", "........"],
    "taille": ["########", "#.#.#.##", "#.#.#.##", "#......#", "########", "........"],
    "budget": ["..####..", ".#....#.", "..####..", ".#....#.", "..####..", "........"],
    "pays": [".#......", ".######.", ".#####..", ".######.", ".#......", "###....."],
    "capitale": ["#.#..#.#", "########", "#.####.#", "#.#..#.#", "#.#..#.#", "########"],
    "inviter_ville": [".####...", ".####.#.", ".#######", ".#....#.", ".#......", "###....."],
    "quitter": ["####....", "#..#..#.", "#..#####", "#..#..#.", "#..#....", "####...."],
    "guerre": ["#......#", ".#....#.", "..#..#..", "...##...", "..#..#..", ".#....#."],
    "effets": ["...#....", "..###...", "#######.", "..###...", "...#..#.", "......#."],
    "taxes": ["##...#..", "##..#...", "...#....", "..#..##.", ".#...##.", "........"],
}

# Icône -> (pictogramme, palette). Les icônes génériques (ajouter, retirer, filtre, réglages...) restent
# celles d'influitems : toute action d'ajout ou de création (fonder, publier, vendre...) prend le « + » vert
# influitems:add (décision du 2026-10-18), d'où la suppression de l'ancienne icône « fonder ».
ICONES = {
    "ville": ("ville", "menthe"),
    "annuaire": ("annuaire", "menthe"),
    "habitants": ("habitants", "menthe"),
    "chunks": ("chunks", "menthe"),
    "changer_ville": ("changer", "menthe"),
    "parcelle": ("parcelle", "menthe"),
    "nation": ("nation", "lavande"),
    "niveau": ("niveau", "lavande"),
    "entretien": ("entretien", "lavande"),
    "roles": ("roles", "lavande"),
    "parametres": ("parametres", "lavande"),
    "renommer": ("renommer", "lavande"),
    "periode": ("periode", "lavande"),
    "taille": ("taille", "lavande"),
    "banque": ("banque", "or"),
    "commerce": ("commerce", "or"),
    "budget": ("budget", "or"),
    "location": ("cle", "or"),
    "echoppe": ("echoppe", "orange"),
    "vente": ("vente", "orange"),
    "agence": ("agence", "orange"),
    "warp": ("warp", "bleu"),
    "lier": ("lier", "bleu"),
    "spawn": ("spawn", "bleu"),
    "message": ("message", "bleu"),
    "deposer": ("deposer", "vert"),
    "retirer": ("retirer", "rouge"),
    "epingle": ("epingle", "rose"),
    "epingle_off": ("epingle", "lavande"),
    "pays": ("pays", "bleu"),
    "capitale": ("capitale", "or"),
    "inviter_ville": ("inviter_ville", "bleu"),
    "quitter": ("quitter", "rouge"),
    "guerre": ("guerre", "rouge"),
    "effets": ("effets", "rose"),
    "taxes": ("taxes", "or"),
}


def tuile(palette):
    b, c, d, e, f = (hexa(x) for x in PALETTES[palette])
    cle = {"A": NOIR, "B": b, "C": c, "D": d, "E": e, "F": f}
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, ligne in enumerate(GABARIT):
        for x, ch in enumerate(ligne):
            if ch in cle:
                im.putpixel((x, y), cle[ch])
    return im, e, f


def icone(picto, palette):
    im, e, f = tuile(palette)
    motif = PICTOS[picto]
    pleins = {(4 + x, 4 + y) for y, l in enumerate(motif) for x, ch in enumerate(l) if ch == "#"}
    for (x, y) in pleins:
        if (x, y + 1) not in pleins and y + 1 <= 10:
            im.putpixel((x, y + 1), f)
    for (x, y) in pleins:
        im.putpixel((x, y), e)
    return im


def barre(n):
    """Jauge verticale (1 à 9) dans une tuile lavande, remplie d'or depuis le bas."""
    im, e, f = tuile("lavande")
    fond, plein, haut = hexa("45365f"), hexa("f0a800"), hexa("ffd94d")
    hauteur = round(7 * n / 9)  # rangées 4 à 10
    for y in range(4, 11):
        for x in range(6, 10):
            im.putpixel((x, y), fond)
    for i in range(hauteur):
        y = 10 - i
        for x in range(6, 10):
            im.putpixel((x, y), haut if i == hauteur - 1 else plein)
    return im


# ── Niveaux de ville : bâtiments qui grandissent ──────────────────────────
NIVEAUX = ["FFD54F", "FFC247", "FFB02E", "FF922B", "FF6B35", "F04A3A", "E63946"]
MUR, MUR_OMBRE, FENETRE, PORTE = hexa("beb0d9"), hexa("9b86c6"), hexa("ffd94d"), hexa("45365f")


class Toile:
    def __init__(self, w=16, h=16):
        self.im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.w, self.h = w, h

    def px(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.im.putpixel((x, y), c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, c)

    def contour(self, c=NOIR):
        src = self.im.copy()
        for y in range(self.h):
            for x in range(self.w):
                if src.getpixel((x, y))[3]:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < self.w and 0 <= yy < self.h and src.getpixel((xx, yy))[3]:
                        self.im.putpixel((x, y), c)
                        break


def ombrer(r, g, b, a=255, k=0.72):
    return (int(r * k), int(g * k), int(b * k), a)


def maison(t, x, w, h, toit, sol=14, fenetres=True):
    """Murs de w x h posés sur sol, toit à deux pans de la couleur du niveau."""
    t.rect(x, sol - h + 1, x + w - 1, sol, MUR)
    t.rect(x + w // 2, sol - h + 1, x + w - 1, sol, MUR_OMBRE)
    sommet = sol - h
    for i in range((w + 1) // 2):
        t.rect(x + i, sommet - i, x + w - 1 - i, sommet - i, toit if i % 2 == 0 else ombrer(*toit))
    if fenetres and w >= 4:
        t.px(x + 1, sol - h + 2, FENETRE)
        t.px(x + w - 2, sol - h + 2, FENETRE)
        t.rect(x + w // 2 - (0 if w % 2 else 1), sol - 1, x + w // 2, sol, PORTE)


def immeuble(t, x, w, h, toit, sol=14):
    """Immeuble haussmannien : toit mansardé (couleur du niveau), fenêtres en grille."""
    t.rect(x, sol - h + 1, x + w - 1, sol, MUR)
    t.rect(x + w - 1, sol - h + 1, x + w - 1, sol, MUR_OMBRE)
    t.rect(x, sol - h, x + w - 1, sol - h, toit)
    t.rect(x + 1, sol - h - 1, x + w - 2, sol - h - 1, ombrer(*toit))
    for y in range(sol - h + 2, sol - 1, 2):
        for xx in range(x + 1, x + w - 1, 2):
            t.px(xx, y, FENETRE)


def tour(t, x, w, h, toit, sol=14, fleche=3):
    t.rect(x, sol - h + 1, x + w - 1, sol, MUR)
    t.rect(x + w - 1, sol - h + 1, x + w - 1, sol, MUR_OMBRE)
    for y in range(sol - h + 3, sol - 1, 3):
        t.px(x + w // 2, y, FENETRE)
    for i in range(fleche):
        t.rect(x + min(i, w // 2), sol - h - i, x + w - 1 - min(i, w // 2), sol - h - i, toit)


def couronne(t, x, y):
    or_, vif = hexa("f0a800"), hexa("ffd94d")
    for dx in (0, 2, 4):
        t.px(x + dx, y, vif)
    t.rect(x, y + 1, x + 4, y + 2, or_)


def niveau(n):
    t = Toile()
    toit = hexa(NIVEAUX[n - 1])
    if n == 1:
        maison(t, 4, 8, 4, toit)
    elif n == 2:
        maison(t, 1, 7, 4, toit)
        maison(t, 8, 7, 5, toit)
    elif n == 3:
        maison(t, 1, 5, 4, toit)
        tour(t, 6, 4, 9, toit)
        maison(t, 10, 5, 5, toit)
    elif n == 4:
        maison(t, 1, 5, 5, toit)
        tour(t, 6, 3, 11, toit, fleche=3)
        immeuble(t, 9, 6, 7, toit)
    elif n == 5:
        immeuble(t, 1, 5, 8, toit)
        immeuble(t, 6, 4, 10, toit)
        immeuble(t, 10, 5, 7, toit)
    elif n == 6:
        immeuble(t, 1, 4, 8, toit)
        tour(t, 5, 3, 12, toit)
        immeuble(t, 8, 4, 10, toit)
        immeuble(t, 12, 3, 6, toit)
    else:
        immeuble(t, 1, 3, 7, toit)
        tour(t, 4, 3, 11, toit)
        immeuble(t, 7, 3, 12, toit)
        immeuble(t, 10, 4, 9, toit)
        couronne(t, 6, 0)
    t.contour()
    return t.im


# ── Niveaux de pays : drapeaux qui grandissent ────────────────────────────
NIVEAUX_PAYS = ["40E0D0", "1E90FF", "FF8C00", "B22222"]
MAT, MAT_OMBRE = hexa("beb0d9"), hexa("6b5a8a")


def drapeau(t, x, haut, larg, haut_drapeau, couleur, sol=14):
    """Mât de x (de haut à sol) et drapeau rectangulaire flottant à droite, couleur du niveau ombrée en bas."""
    t.rect(x, haut, x, sol, MAT)
    t.px(x, haut - 1, OR_VIF)
    for dy in range(haut_drapeau):
        c = couleur if dy < haut_drapeau - 1 else ombrer(*couleur)
        t.rect(x + 1, haut + dy, x + larg, haut + dy, c)
    t.px(x + larg, haut + haut_drapeau - 1, (0, 0, 0, 0))  # bout du drapeau découpé (flotte)


def chateau(t, x0, x1, h, sol=14):
    t.rect(x0, sol - h + 1, x1, sol, MUR)
    t.rect(x1 - 1, sol - h + 1, x1, sol, MUR_OMBRE)
    for x in range(x0, x1 + 1, 2):
        t.px(x, sol - h, MUR)
    t.rect((x0 + x1) // 2, sol - 1, (x0 + x1) // 2, sol, PORTE)


def niveau_pays(n):
    t = Toile()
    c = hexa(NIVEAUX_PAYS[n - 1])
    if n == 1:
        t.rect(4, 13, 11, 14, hexa("4fa45c"))
        drapeau(t, 6, 5, 5, 3, c)
    elif n == 2:
        t.rect(3, 13, 12, 14, hexa("4fa45c"))
        drapeau(t, 5, 3, 7, 5, c)
    elif n == 3:
        chateau(t, 2, 13, 5)
        drapeau(t, 7, 1, 6, 4, c, sol=9)
    else:
        chateau(t, 1, 14, 6)
        drapeau(t, 7, 3, 7, 4, c, sol=8)
        couronne(t, 5, 0)
    t.contour()
    return t.im


# ── Bandeaux « ligne d'horizon » ───────────────────────────────────────────
CIEL_HAUT, CIEL_BAS, ROSE = hexa("1e0a3c"), hexa("5b2d9e"), hexa("e060a0")
LOIN, PROCHE = hexa("4a3670"), hexa("140f1f")
OR_VIF, OR_CHAUD = hexa("ffd94d"), hexa("f0a800")


def melange(a, b, k):
    return tuple(int(a[i] + (b[i] - a[i]) * k) for i in range(3)) + (255,)


def bandeau(w, h, graine, theme):
    rnd = random.Random(graine)
    t = Toile(w, h)
    # ciel : dégradé tramé en damier (même trame que le fond générique), rose près de l'horizon
    for y in range(h):
        k = y / (h - 1)
        base = melange(CIEL_HAUT, CIEL_BAS, min(1, k * 1.2))
        if k > 0.4:
            base = melange(base, ROSE, (k - 0.4) * 0.75)
        alt = melange(base, CIEL_HAUT, 0.12)
        for x in range(w):
            t.px(x, y, base if (x + y) % 2 else alt)
    # étoiles et scintillements (or vif de la charte)
    for _ in range(w // 9):
        x, y = rnd.randrange(w), rnd.randrange(int(h * 0.45))
        t.px(x, y, OR_VIF if rnd.random() < 0.4 else hexa("e8e0f5"))
    for _ in range(2):
        x, y = rnd.randrange(6, w - 6), rnd.randrange(3, int(h * 0.35))
        for d in (-1, 1):
            t.px(x + d, y, hexa("ffd94d", 170))
            t.px(x, y + d, hexa("ffd94d", 170))
        t.px(x, y, hexa("ffffff"))
    if theme == "roles":
        # couronne dorée au-dessus de la ville (motif récurrent de la charte)
        cx, cy = w // 2 - 4, 2
        for dx in (0, 4, 8):
            t.rect(cx + dx, cy, cx + dx, cy + 1, OR_VIF)
        t.rect(cx, cy + 2, cx + 8, cy + 4, OR_CHAUD)
        t.px(cx + 2, cy + 3, hexa("e060a0"))
        t.px(cx + 6, cy + 3, hexa("e060a0"))
    sol = h - 1
    if theme == "pays":
        # collines, châteaux sur les sommets, drapeaux aux couleurs des niveaux de pays
        for couche, (couleur, amp, base) in enumerate(((LOIN, 6, h // 2), (hexa("2a2040"), 5, int(h * 0.7)))):
            phase = rnd.random() * 6.28
            import math
            hauteurs = [int(base - amp * math.sin(phase + x / (11 + couche * 4))) for x in range(w)]
            for x in range(w):
                t.rect(x, hauteurs[x], x, sol, couleur)
            if couche == 0:
                for k in range(3):
                    cx = rnd.randrange(10 + k * w // 3, (k + 1) * w // 3 - 8)
                    hy = hauteurs[cx]
                    t.rect(cx - 4, hy - 5, cx + 4, hy, PROCHE)
                    for xx in range(cx - 4, cx + 5, 2):
                        t.px(xx, hy - 6, PROCHE)
                    t.rect(cx, hy - 12, cx, hy - 6, PROCHE)
                    t.rect(cx + 1, hy - 12, cx + 4, hy - 10, hexa(NIVEAUX_PAYS[k % 4]))
                    t.px(cx - 2, hy - 3, OR_VIF)
                    t.px(cx + 2, hy - 3, OR_VIF)
        return t.im
    # plan lointain : silhouettes claires
    x = -rnd.randrange(4)
    while x < w:
        bw, bh = rnd.randrange(5, 11), rnd.randrange(h // 3, int(h * 0.62))
        t.rect(x, sol - bh, x + bw - 1, sol, LOIN)
        if rnd.random() < 0.3:
            t.rect(x + bw // 2, sol - bh - 3, x + bw // 2, sol - bh, LOIN)
        x += bw - rnd.randrange(0, 3)
    # plan proche : immeubles haussmanniens (toits mansardés, cheminées), fenêtres allumées
    x = -rnd.randrange(3)
    centre = w // 2
    dome_fait = theme not in ("ville", "fiche", "accueil", "reglages")
    toits = []
    while x < w:
        bw = rnd.randrange(6, 13)
        bh = rnd.randrange(h // 4, int(h * 0.5))
        if not dome_fait and x <= centre < x + bw + 8:
            # monument central : dôme façon Sénat
            dw = 15
            dx = centre - dw // 2
            t.rect(dx, sol - 12, dx + dw - 1, sol, PROCHE)
            for r in range(6):
                t.rect(dx + 2 + r // 2 + (1 if r > 3 else 0), sol - 13 - r, dx + dw - 3 - r // 2 - (1 if r > 3 else 0), sol - 13 - r, PROCHE)
            t.rect(centre, sol - 21, centre, sol - 18, PROCHE)
            t.px(centre, sol - 22, OR_VIF)
            for cx in range(dx + 2, dx + dw - 2, 3):
                t.rect(cx, sol - 9, cx, sol - 1, hexa("2a2040"))
            x = dx + dw
            dome_fait = True
            continue
        t.rect(x, sol - bh, x + bw - 1, sol, PROCHE)
        t.rect(x + 1, sol - bh - 1, x + bw - 2, sol - bh - 1, PROCHE)  # mansarde
        toits.append((x, bw, bh))
        if rnd.random() < 0.6:
            cx = x + rnd.randrange(1, bw - 1)
            t.rect(cx, sol - bh - 3, cx, sol - bh - 2, PROCHE)  # cheminée
        for yy in range(sol - bh + 2, sol - 1, 3):
            for xx in range(x + 1, x + bw - 1, 2):
                if rnd.random() < 0.4:
                    t.px(xx, yy, hexa("ffd94d", 230) if rnd.random() < 0.7 else OR_CHAUD)
        x += bw + rnd.randrange(0, 2)
    if theme == "warps":
        # épingles bleues (charte, infos / liens) plantées sur les toits
        for px_ in range(10, w - 6, rnd.randrange(22, 30)):
            y0 = rnd.randrange(4, h // 3)
            bleu, clair = hexa("2bc4e8"), hexa("bdeffa")
            t.rect(px_ - 1, y0, px_ + 1, y0 + 2, bleu)
            t.px(px_, y0 + 1, clair)
            t.rect(px_, y0 + 3, px_, y0 + 6, bleu)
    if theme == "echoppes":
        # étals du marché au premier plan : auvents rayés or / rose
        x = 3
        while x < w - 10:
            aw = rnd.randrange(12, 18)
            for k in range(aw):
                c = OR_CHAUD if (k // 2) % 2 == 0 else hexa("e060a0")
                t.rect(x + k, sol - 7, x + k, sol - 5, c)
            t.rect(x + 1, sol - 4, x + 1, sol, hexa("2a2040"))
            t.rect(x + aw - 2, sol - 4, x + aw - 2, sol, hexa("2a2040"))
            t.rect(x + 2, sol - 2, x + aw - 3, sol, hexa("5b2d9e"))
            x += aw + rnd.randrange(6, 14)
    if theme == "agence":
        # panneaux « à louer » (or) et « à vendre » (orange) accrochés aux façades
        for i, (bx, bw, bh) in enumerate(toits[::2]):
            if bw < 7 or bh < 8:
                continue
            c = OR_VIF if i % 2 == 0 else hexa("e08a3a")
            px0, py0 = bx + 1, sol - bh + 2
            t.rect(px0, py0, px0 + 4, py0 + 2, c)
            t.rect(px0 + 1, py0 + 1, px0 + 3, py0 + 1, hexa("45365f"))
            t.rect(px0 + 2, py0 + 3, px0 + 2, py0 + 4, hexa("2a2040"))
    if theme == "banque":
        # pièces d'or qui tombent sur la ville
        for _ in range(9):
            x0, y0 = rnd.randrange(4, w - 6), rnd.randrange(2, h // 2)
            t.rect(x0, y0, x0 + 2, y0 + 1, OR_CHAUD)
            t.px(x0 + 1, y0, OR_VIF)
    if theme in ("parcelles", "parcelle"):
        # parcelles découpées au sol : pointillés menthe (couleur des villes)
        x = 4
        while x < w - 14:
            pw = rnd.randrange(14, 22)
            for k in range(0, pw, 2):
                t.px(x + k, sol - 1, hexa("97fc79"))
                t.px(x + k, sol - 4, hexa("97fc79"))
            for k in range(sol - 4, sol, 2):
                t.px(x, k, hexa("97fc79"))
                t.px(x + pw, k, hexa("97fc79"))
            x += pw + rnd.randrange(4, 9)
    if theme == "commerce":
        # courbe de recettes dorée qui monte au-dessus de la ville
        y = h // 2
        for xx in range(4, w - 4):
            if xx % 6 == 0:
                y = max(4, min(h // 2 + 2, y + rnd.choice((-3, -2, -1, 1))))
            t.px(xx, y, OR_VIF)
            t.px(xx, y + 1, hexa("f0a800", 140))
    if theme == "habitants":
        # petites silhouettes devant les immeubles
        for xx in range(6, w - 4, rnd.randrange(9, 14)):
            c = hexa("b376e5")
            t.px(xx, sol - 4, c)
            t.rect(xx - 1, sol - 3, xx + 1, sol - 1, c)
    return t.im


# ── Cadre et en-tête du menu Métiers ───────────────────────────────────────
CADRE_CLAIR, CADRE_LAVANDE = hexa("e2cdff"), hexa("c49bff")
OR_CADRE, OR_CADRE_OMBRE = hexa("f2c85b"), hexa("a8822a")


def cadre(im, x0, y0, x1, y1):
    """Cadre de l'illustration du menu Métiers : noir, liseré clair, lavande (2 px), noir, coins dorés."""
    px = im.load()
    couches = [NOIR, CADRE_CLAIR, CADRE_LAVANDE, CADRE_LAVANDE, NOIR]
    for i, c in enumerate(couches):
        for x in range(x0 + i, x1 - i + 1):
            px[x, y0 + i] = c
            px[x, y1 - i] = c
        for y in range(y0 + i, y1 - i + 1):
            px[x0 + i, y] = c
            px[x1 - i, y] = c
    # coins dorés : triangles de 5 pixels dans les quatre angles intérieurs, ombre sur la diagonale
    ix0, iy0, ix1, iy1 = x0 + 5, y0 + 5, x1 - 5, y1 - 5
    for d in range(5):
        for k in range(5 - d):
            c = OR_CADRE_OMBRE if k == 4 - d else OR_CADRE
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
                # bandeau nettoyé : colonne de référence libre de même parité (garde le damier)
                px[x, y] = mp[8 + (x % 2), y]
            else:
                px[x, y] = mp[x, y]


def largeur_texte(txt):
    return sum(len(POLICE[c][0]) + 1 for c in txt) - 1


def texte(im, txt, cx, y, couleur=hexa("ffffff"), ombre=hexa("000000")):
    """Texte QuinqueFive centré sur cx, ombre d'un pixel dessous (comme l'onglet « METIERS »)."""
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


def onglet(im, txt, cx):
    """Onglet du titre encastré dans le bandeau, relevé pixel par pixel sur l'onglet « METIERS »."""
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
    texte(im, txt, cx, 8 if any(c in ACCENTS for c in txt) else 7)


# ── Fonds de menus ─────────────────────────────────────────────────────────
HAUT, BAS = 1, 139
BANDEAU = (25, 17, 150, 52)  # rangées 0 et 1, colonnes 1 à 7
GRILLE = [r * 9 + c for r in range(2, 5) for c in range(1, 8)]
BARRE_LISTE = [45, 47, 48, 49, 50, 51, 53]
BARRE_AGENCE = list(range(45, 54))
SELECTEUR = list(range(37, 44))


def etiquette(im, txt, rangee):
    """Libellé centré dans une rangée laissée vide (ex : « GESTION » entre deux rangées de boutons)."""
    texte(im, txt, 88, 18 + 18 * rangee + 6, ombre=hexa("0f0c13"))


def liste(theme, graine, titre, barre=BARRE_LISTE):
    return (theme, graine, titre, [0, 8] + GRILLE + barre, {})


FONDS = {
    # id: (thème du bandeau, graine, titre, cases, étiquettes {rangée: texte})
    "menu_ville": ("ville", 11, "MA VILLE", [0, 8] + list(range(19, 26)) + SELECTEUR + [45, 49, 53], {3: "GESTION"}),
    "menu_ville_accueil": ("accueil", 5, "VILLES", [0, 29, 31, 33], {2: "FONDE OU REJOINS UNE VILLE"}),
    "menu_ville_fiche": ("fiche", 23, "VILLE", [0, 8] + list(range(19, 26)) + [38, 40, 42] + [47, 49, 51], {3: "VITRINE"}),
    "menu_ville_commerce": ("commerce", 37, "COMMERCE", [0, 8, 19, 21, 22, 23, 25] + SELECTEUR + list(range(47, 52)) + [53],
                            {3: "SEPT DERNIERS JOURS"}),
    "menu_ville_banque": ("banque", 83, "BANQUE", [0, 8, 20, 22, 24] + SELECTEUR + [47, 49, 51], {3: "MONTANT"}),
    "menu_ville_reglages": ("reglages", 97, "RÉGLAGES", [0, 8, 20, 22, 24] + SELECTEUR + [45, 53], {3: "VILLE"}),
    "menu_ville_parcelle": ("parcelle", 101, "PARCELLE", [0, 8] + list(range(19, 26)) + SELECTEUR + [49, 53], {3: "GESTION"}),
    "menu_ville_annonce": ("agence", 113, "ANNONCE", [0, 8, 20, 22, 24] + SELECTEUR + [45, 46, 47, 49, 51, 52, 53], {3: "PRIX"}),
    "menu_villes": liste("annuaire", 41, "ANNUAIRE"),
    "menu_warps": liste("warps", 53, "WARPS"),
    "menu_echoppes": liste("echoppes", 67, "ÉCHOPPES"),
    "menu_habitants": liste("habitants", 71, "HABITANTS"),
    "menu_agence": liste("agence", 127, "AGENCE", BARRE_AGENCE),
    "menu_parcelles": liste("parcelles", 131, "PARCELLES"),
    "menu_roles": liste("roles", 139, "RÔLES"),
    "menu_droits": liste("roles", 149, "PERMISSIONS"),
    "menu_parametres": liste("ville", 157, "PARAMÈTRES"),
    "menu_pays": ("pays", 163, "MON PAYS", [0, 8] + list(range(19, 26)) + SELECTEUR + [45, 46, 47, 49, 51, 52, 53], {3: "GESTION"}),
    "menu_pays_accueil": ("pays", 167, "PAYS", [0, 29, 31, 33], {2: "FONDE OU REJOINS UN PAYS"}),
    "menu_pays_fiche": ("pays", 173, "PAYS", [0, 8] + list(range(19, 26)) + [38, 40, 42], {3: "DÉCOUVRIR"}),
    "menu_pays_annuaire": liste("pays", 179, "ANNUAIRE DES PAYS"),
    "menu_pays_villes": liste("pays", 181, "VILLES DU PAYS"),
}

# Accueil partagé « Villes et Pays » : deux grands panneaux illustrés côte à côte.
PANNEAU_VILLE = (7, 17, 78, 70)    # colonnes 0 à 3, rangées 0 à 2
PANNEAU_PAYS = (97, 17, 168, 70)   # colonnes 5 à 8, rangées 0 à 2
# Allégé le 2026-10-19 : les deux panneaux (Ma ville, Mon pays) et une seule rangée « Explorer »
# (ce qui n'est dans aucun des deux tableaux de bord), sans doublons.
CASES_TERRITOIRES = [38, 39, 40, 41, 42, 49]


def fond_territoires():
    im = generique_raccourci(GENERIQUE, HAUT, BAS)
    entete(im)
    for (x0, y0, x1, y1), theme, graine, titre in ((PANNEAU_VILLE, "ville", 191, "VILLE"), (PANNEAU_PAYS, "pays", 193, "PAYS")):
        im.alpha_composite(bandeau(x1 - x0 + 1, y1 - y0 + 1, graine, theme), (x0, y0))
        cadre(im, x0, y0, x1, y1)
        # plaque du titre en bas du panneau, dans la trame de l'onglet Métiers
        w = largeur_texte(titre) + 10
        cx = (x0 + x1) // 2
        px = im.load()
        for yy in range(y1 - 15, y1 - 5):
            for xx in range(cx - w // 2, cx + w // 2 + 1):
                bord = yy in (y1 - 15, y1 - 6) or xx in (cx - w // 2, cx + w // 2)
                px[xx, yy] = NOIR if bord else hexa("594674")
        for xx in range(cx - w // 2 + 1, cx + w // 2):
            px[xx, y1 - 14] = hexa("7f66a3")
        texte(im, titre, cx, y1 - 12)
    # séparation centrale : pointillés dorés de haut en bas
    px = im.load()
    for y in range(20, 70, 3):
        px[88, y] = OR_CADRE
    onglet(im, "VILLES ET PAYS", 88)
    tampon = tampon_case()
    for c in CASES_TERRITOIRES:
        poser(im, tampon, *objet(c))
    etiquette(im, "EXPLORER", 3)
    return im


def fond(nom):
    theme, graine, titre, cases, etiquettes = FONDS[nom]
    im = generique_raccourci(GENERIQUE, HAUT, BAS)
    entete(im)
    x0, y0, x1, y1 = BANDEAU
    im.alpha_composite(bandeau(x1 - x0 + 1, y1 - y0 + 1, graine, theme), (x0, y0))
    cadre(im, x0, y0, x1, y1)
    onglet(im, titre, 88)
    tampon = tampon_case()
    for c in cases:
        poser(im, tampon, *objet(c))
    for rangee, txt in etiquettes.items():
        etiquette(im, txt, rangee)
    return im


# ── Écriture du pack ───────────────────────────────────────────────────────
def main():
    ico = os.path.join(SORTIE, "textures", "icones")
    gui = os.path.join(SORTIE, "textures", "font", "gui")
    os.makedirs(ico, exist_ok=True)
    os.makedirs(gui, exist_ok=True)
    images = {}
    for nom, (picto, pal) in ICONES.items():
        images[nom] = icone(picto, pal)
    for n in range(1, 8):
        images["niveau_%d" % n] = niveau(n)
    for n in range(1, 10):
        images["barre_%d" % n] = barre(n)
    for n in range(1, 5):
        images["pays_niveau_%d" % n] = niveau_pays(n)
    for nom, im in images.items():
        im.save(os.path.join(ico, nom + ".png"))
    fonds = {nom: fond(nom) for nom in FONDS}
    fonds["menu_territoires"] = fond_territoires()
    for nom, im in fonds.items():
        im.save(os.path.join(gui, nom + ".png"))

    lignes = [
        "# Généré par plugins/fluxplugin/Flux/assets/ville/gen_ville.py : ne pas modifier à la main.",
        "# Menus Ville (FluxLands + Flux, Labs) : icônes de boutons, niveaux de ville, jauges, fonds.",
        "info:",
        "  namespace: influville",
        "items:",
    ]
    for nom in images:
        lignes += [
            "  %s:" % nom,
            "    display_name: ' '",
            "    resource:",
            "      material: PAPER",
            "      generate: true",
            "      textures:",
            "      - icones/%s.png" % nom,
        ]
    lignes.append("font_images:")
    for nom in fonds:
        lignes += [
            "  # Fond plein écran 176 x 221 (coffre de 6 rangées, inventaire masqué).",
            "  %s:" % nom,
            "    suggest_in_command: false",
            "    show_in_gui: false",
            "    path: font/gui/%s.png" % nom,
            "    scale_ratio: 221",
            "    y_position: 13",
        ]
    with open(os.path.join(SORTIE, "config.yml"), "w") as f:
        f.write("\n".join(lignes) + "\n")

    if APERCUS:
        os.makedirs(APERCUS, exist_ok=True)
        n = len(images)
        planche = Image.new("RGBA", (n * 20 + 4, 24), hexa("1e1828"))
        for i, im in enumerate(images.values()):
            planche.alpha_composite(im, (4 + i * 20, 4))
        planche.resize((planche.width * 6, planche.height * 6), Image.NEAREST).save(os.path.join(APERCUS, "icones.png"))
        for nom, im in fonds.items():
            im.resize((176 * 4, 221 * 4), Image.NEAREST).save(os.path.join(APERCUS, nom + ".png"))
    print(len(images), "icônes,", len(fonds), "fonds ->", SORTIE)


main()
