#!/usr/bin/env python3
"""Colosse d'Abîme : boss du donjon de la roche (thème Rochiste), modèle Blockbench (.bbmodel) STATIQUE (2026-11-06).

Tortue colossale de roches souterraines : carapace en dôme aplati de basalte et de deepslate, cinq pointes
d'obsidienne, une géode d'améthyste qui s'ouvre au sommet (le cœur), tête avancée aux yeux violets, quatre pattes
massives écartées, queue terminée par une masse de blackstone. Une quarantaine de grands volumes, inclinés pour donner
du caractère : la simplicité géométrique est voulue (brief : « 25 cubes bien choisis plutôt que 300 »).

Textures pixel art à 1 texel par unité, palette restreinte par matériau (deepslate dominante). Les cristaux et les yeux
sont sur une seconde texture « emissive » (pleine luminosité, gérée nativement par BetterModel) et dans des os
`glow_`. Groupes rangés par partie (pivots aux articulations) pour une animation future, sans animation ici.

Usage : colosse_abime.py [dossier de sortie] [--apercu <planche.png>]
Face avant au nord (-z), côté droit du monstre en +x, sol en y = 0.
"""
import base64
import json
import math
import os
import random
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
import paths  # noqa: F401,E402  (scripts/influcard on the path)
sys.path.insert(0, HERE)
from png_util import encode, write  # noqa: E402
from golem_fonderie import app, euler, rot  # noqa: E402

IDENT = "colosse_abime"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000c0105")
MAX_W, MAX_H, MAX_L = 5 * 16, 4 * 16, 5 * 16      # limites du brief, en unités (1 bloc = 16)


def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


# --------------------------------------------------------------------------- palettes (sombre → clair), une par matériau
# Chaque matériau a sa famille de tons, son motif et sa luminosité : carapace froide en strates, tête en pierre taillée
# gris chaud et taupe, pattes en roche brute gris brun, articulations en deepslate très sombre, obsidienne presque
# noire, améthyste seul accent coloré.
PAL = {
    "basalt": [hx(c) for c in ("#232327", "#2E2E33", "#3A3A40", "#46464C", "#53535A", "#62626A")],
    "slate": [hx(c) for c in ("#25272C", "#30333A", "#3C3F46", "#484C54", "#555962", "#656A73")],
    "deep": [hx(c) for c in ("#17171A", "#1E1E22", "#26262B", "#2F2F35", "#393940", "#45454D")],
    "head": [hx(c) for c in ("#3A3735", "#484442", "#56514E", "#645E5A", "#736C67", "#827A74")],
    "cheek": [hx(c) for c in ("#3F3835", "#4D4541", "#5C524C", "#6A5F58", "#796C64", "#877A70")],
    "snout": [hx(c) for c in ("#42382F", "#51463C", "#605348", "#6E6054", "#7C6D60", "#8A7A6C")],
    "rough": [hx(c) for c in ("#38332F", "#45403B", "#534C46", "#615851", "#6E655D", "#7D7369")],
    "granite": [hx(c) for c in ("#3D3230", "#4C3E3A", "#5B4A44", "#69564E", "#776259", "#856E64")],
    "tiles": [hx(c) for c in ("#2A2A2E", "#35353A", "#404046", "#4C4C52", "#58585E", "#64646A")],
    "blackstone": [hx(c) for c in ("#131012", "#1A1619", "#221D21", "#2B252A", "#352E34", "#403840")],
    "obsidian": [hx(c) for c in ("#07050B", "#0D0913", "#140E1D", "#1C1429", "#2A1F3D", "#3D2E56")],
    "cavity": [hx(c) for c in ("#0B0A0D", "#121015", "#19161D", "#211D26", "#2A2530", "#332D3A")],
    "fang": [hx(c) for c in ("#5E5B58", "#6E6A66", "#7E7974", "#8E8882", "#9D9790", "#ACA59E")],
    "geode": [hx(c) for c in ("#26153F", "#331D55", "#40266B", "#4D2F80", "#5B3A94", "#6A47A8")],
}
GLOW = {
    "amethyst": [hx(c) for c in ("#3A1D66", "#55308F", "#7347B8", "#9468D8", "#B992EE", "#E6D9FF")],
    "eye": [hx(c) for c in ("#6B35C8", "#9B62F2", "#C9A3FF", "#F3EAFF")],
}
# lumière venue d'en haut : décalage de ton par face
LIGHT = {"up": 1, "north": 0, "south": 0, "east": 0, "west": 0, "down": -2}


def tone(pal, i):
    return pal[max(0, min(len(pal) - 1, i))]


def strata(name, base=2, shift=0):
    """Carapace : strates horizontales de 2 à 3 lignes, chaque strate coupée en tronçons de tons voisins, arête haute
    éclairée, bas de face dans l'ombre. Dessus : plaques claires ; dessous : ombre profonde."""
    def f(w, h, r, face):
        pal = PAL[name]
        b = base + shift + LIGHT[face]
        if face in ("up", "down"):
            px = [[tone(pal, b)] * w for _ in range(h)]
            for _ in range(max(1, w * h // 14)):
                pw, ph = r.randint(2, 5), r.randint(2, 3)
                x0, y0 = r.randrange(max(1, w - pw + 1)), r.randrange(max(1, h - ph + 1))
                t = tone(pal, b + r.choice((-1, 1)))
                for y in range(y0, min(h, y0 + ph)):
                    for x in range(x0, min(w, x0 + pw)):
                        px[y][x] = t
            return px
        px = [[tone(pal, b)] * w for _ in range(h)]
        y = 0
        while y < h:
            band = r.randint(2, 3)
            level = b + r.choice((-1, 0, 0, 1))
            x = 0
            while x < w:
                n = r.randint(3, 8)
                t = tone(pal, level + (r.choice((-1, 1)) if r.random() < 0.3 else 0))
                for yy in range(y, min(h, y + band)):
                    for xx in range(x, min(w, x + n)):
                        px[yy][xx] = t
                x += n
            # joint sombre et irrégulier sous chaque strate
            jy = min(h - 1, y + band - 1)
            for xx in range(w):
                if r.random() < 0.55:
                    px[jy][xx] = tone(pal, level - 1)
            y += band
        if h >= 3:
            px[0] = [tone(pal, b + 2) if r.random() < 0.7 else tone(pal, b + 1) for _ in range(w)]
            px[h - 1] = [tone(pal, b - 2)] * w
        return px
    return f


def carved(name, shift=0):
    """Pierre taillée (tête, joues, museau, mâchoire, arcades) : grandes plages de 3 à 5 pixels qui suivent le volume,
    deux lignes hautes éclairées, bas de face dans l'ombre, côtés un ton plus sombres."""
    def f(w, h, r, face):
        pal = PAL[name]
        b = 2 + shift + LIGHT[face] + (-1 if face in ("east", "west") else 0)
        px = [[tone(pal, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 10)):
            pw, ph = r.randint(3, 5), r.randint(2, 3)
            x0, y0 = r.randrange(max(1, w - pw + 1)), r.randrange(max(1, h - ph + 1))
            t = tone(pal, b + r.choice((-1, 1, 0)))
            for y in range(y0, min(h, y0 + ph)):
                for x in range(x0, min(w, x0 + pw)):
                    px[y][x] = t
        if face not in ("up", "down") and h >= 3:
            for x in range(w):
                px[0][x] = tone(pal, b + 2)
                if h >= 5 and r.random() < 0.6:
                    px[1][x] = tone(pal, b + 1)
                px[h - 1][x] = tone(pal, b - 2)
        return px
    return f


def rough(name, shift=0):
    """Roche brute (pattes) : blocs irréguliers de 2 à 5 pixels cernés d'un ton sombre, quelques éclats clairs sur le
    dessus, pied de la jambe assombri (ombre au sol)."""
    def f(w, h, r, face):
        pal = PAL[name]
        b = 2 + shift + LIGHT[face]
        px = [[tone(pal, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 8)):
            pw, ph = r.randint(2, 5), r.randint(2, 4)
            x0, y0 = r.randrange(max(1, w - pw + 1)), r.randrange(max(1, h - ph + 1))
            t = tone(pal, b + r.choice((-1, 0, 0, 1)))
            for y in range(y0, min(h, y0 + ph)):
                for x in range(x0, min(w, x0 + pw)):
                    edge = y in (y0, y0 + ph - 1) and x == x0
                    px[y][x] = tone(pal, b - 1) if edge else t
        if face not in ("up", "down"):
            for y in range(max(0, h - 2), h):
                for x in range(w):
                    px[y][x] = tone(pal, b - 2 if y == h - 1 else b - 1)
        return px
    return f


def paint_tiles(w, h, r, face):
    """Plastron : petites dalles de deepslate taillée aux joints sombres, sous le corps donc plutôt dans l'ombre."""
    pal = PAL["tiles"]
    b = 2 + LIGHT[face]
    px = [[tone(pal, b)] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if y % 4 == 3 or (x + (4 if (y // 4) % 2 else 0)) % 8 == 7:
                px[y][x] = tone(pal, b - 2)
            elif (x // 4 + y // 4) % 3 == 0:
                px[y][x] = tone(pal, b + 1)
    return px


def paint_blackstone(w, h, r, face):
    """Blackstone : très sombre, fissures sinueuses un ton plus clair."""
    pal = PAL["blackstone"]
    b = 2 + LIGHT[face]
    px = [[tone(pal, b)] * w for _ in range(h)]
    for _ in range(max(1, w * h // 14)):
        x0, y0 = r.randrange(w), r.randrange(h)
        for y in range(y0, min(h, y0 + 2)):
            for x in range(x0, min(w, x0 + 2)):
                px[y][x] = tone(pal, b - 1)
    for _ in range(max(1, (w + h) // 6)):
        x, y = r.randrange(w), r.randrange(h)
        for _ in range(r.randint(2, 4)):
            if 0 <= y < h and 0 <= x < w:
                px[y][x] = tone(pal, b + 2)
            y += 1
            x += r.choice((-1, 0, 1))
    return px


def paint_obsidian(w, h, r, face):
    """Obsidienne : presque noire, plages noir-violet, reflets seulement sur l'arête haute et une arête verticale."""
    pal = PAL["obsidian"]
    b = 1 + max(0, LIGHT[face])
    px = [[tone(pal, b)] * w for _ in range(h)]
    for _ in range(max(1, w * h // 6)):
        x0, y0 = r.randrange(w), r.randrange(h)
        t = tone(pal, b + r.choice((-1, 1)))
        for y in range(y0, min(h, y0 + 2)):
            for x in range(x0, min(w, x0 + 2)):
                px[y][x] = t
    if face not in ("down",):
        for x in range(w):
            if r.random() < 0.5:
                px[0][x] = tone(pal, 4)
        if face in ("north", "east"):
            for y in range(h):
                if y % 3 != 2:
                    px[y][w - 1] = tone(pal, 4 if y < h // 2 else 3)
            px[0][w - 1] = tone(pal, 5)
    return px


def paint_cavity(w, h, r, face):
    pal = PAL["cavity"]
    return [[tone(pal, 1 + (1 if (x + y) % 4 == 0 else 0)) for x in range(w)] for y in range(h)]


def paint_geode(w, h, r, face):
    """Lit de géode : violet sombre non lumineux, éclats plus clairs (amethyst budding)."""
    pal = PAL["geode"]
    b = 2 + LIGHT[face]
    px = [[tone(pal, b)] * w for _ in range(h)]
    for _ in range(max(1, w * h // 6)):
        x0, y0 = r.randrange(w), r.randrange(h)
        t = tone(pal, b + r.choice((-1, 1, 2)))
        for y in range(y0, min(h, y0 + 2)):
            for x in range(x0, min(w, x0 + 2)):
                px[y][x] = t
    return px


def paint_amethyst(w, h, r, face):
    """Cristal : chaque face sa luminosité (dessus clair, faces au soleil moyennes, faces à l'ombre profondes),
    facettes diagonales, arête lavande, reflet presque blanc en haut des faces éclairées."""
    pal = GLOW["amethyst"]
    b = {"up": 4, "north": 3, "east": 3, "south": 2, "west": 2, "down": 1}[face]
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            facet = ((x + y) // 2) % 3
            row.append(tone(pal, b + (1 if facet == 0 else -1 if facet == 2 else 0)))
        px.append(row)
    if face in ("north", "east") and w >= 2:
        for y in range(h):
            px[y][w - 1] = tone(pal, b + 1)
    if face in ("up", "north", "east") and h >= 2:
        px[0][0] = tone(pal, 5)
        if w >= 2:
            px[0][1] = tone(pal, 4)
        px[1][0] = tone(pal, 4)
    return px


def paint_eye(w, h, r, face):
    """Œil : cœur presque blanc, iris violet vif, bord bas plus sombre (paupière qui pèse) ; plus lumineux que le cœur."""
    rim, base, light, core = GLOW["eye"]
    px = [[base] * w for _ in range(h)]
    for x in range(w):
        px[h - 1][x] = rim
    if face == "north":
        c = w // 2
        for x in range(max(0, c - 1), min(w, c + 1)):
            px[0][x] = light
        px[0][c] = core
    return px


MATS = {
    "shell": strata("basalt", 2), "shell_light": strata("basalt", 2, 1), "dome": strata("slate", 2),
    "dome_light": strata("slate", 2, 1), "deep": strata("deep", 2),
    "head": carved("head"), "head_top": carved("head", 1), "brow": carved("head", -1), "cheek": carved("cheek"),
    "snout": carved("snout"), "snout_side": carved("snout", -1), "jaw": carved("head", -1),
    "leg": rough("rough", -1), "foot": rough("granite", -1), "tiles": paint_tiles, "blackstone": paint_blackstone,
    "obsidian": paint_obsidian, "cavity": paint_cavity, "fang": carved("fang"), "geode": paint_geode,
}
GLOW_MATS = {"amethyst": paint_amethyst, "eye": paint_eye}


# --------------------------------------------------------------------------- le Colosse
# Chaque pièce : (groupe, nom, centre, taille, rotation, matériau). Rotation : (x, y, z) en degrés ou matrice.
# Rappel (Blockbench) : x négatif abaisse l'avant (-z) ; z positif lève le côté +x.
HEAD_PIVOT = (0, 17, -25)
HEAD_TILT = -9          # la tête penche vers le joueur
GROUPS = {   # groupe : (pivot, parent)
    "Root": ((0, 0, 0), None),
    "Body": ((0, 14, 0), "Root"),
    "Shell": ((0, 24, 2), "Body"),
    "glow_heart": ((6, 40, -4), "Shell"),
    # plaques de la carapace qui s'entrouvrent pendant l'ultime (pivot au bord intérieur de chaque plaque)
    "Plate_top": ((-5, 38, 6), "Shell"),
    "Plate_shoulder_right": ((16, 30, -10), "Shell"),
    "Plate_shoulder_left": ((-17, 29, -8), "Shell"),
    "Plate_flank_right": ((25, 28, 6), "Shell"),
    "Plate_flank_left": ((-25, 28.5, 5), "Shell"),
    "Plate_rear": ((0, 27, 25), "Shell"),
    "Head": (HEAD_PIVOT, "Body"),
    "Jaw": ((0, 13, -31), "Head"),
    "glow_eyes": ((0, 20, -40), "Head"),
    "Leg_front_right": ((22, 16, -13), "Root"),
    "Leg_front_left": ((-22, 16, -13), "Root"),
    "Leg_back_right": ((21, 15, 16), "Root"),
    "Leg_back_left": ((-21, 15, 16), "Root"),
    "Tail": ((0, 16, 26), "Body"),
    "Tail_tip": ((1, 17.5, 33.5), "Tail"),
}


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, c, size, r, mat))

    T = rot(HEAD_TILT, 0, 0)

    def face(g, name, c, size, r, mat):
        """Pièce du visage dessinée tête droite, puis basculée avec toute la tête autour de son pivot."""
        d = app(T, [c[i] - HEAD_PIVOT[i] for i in range(3)])
        M = [[sum(T[i][k] * rot(*r)[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
        add(g, name, [d[i] + HEAD_PIVOT[i] for i in range(3)], size, M, mat)

    # ---------------------------------------------------------------- corps : bas, massif, ventre visible
    add("Body", "corps", (0, 15, 1), (42, 12, 40), (0, 0, 0), "deep")
    add("Body", "plastron", (0, 9.5, -1), (36, 3, 34), (-3, 0, 0), "tiles")

    # ---------------------------------------------------------------- carapace : dôme aplati, plaques de tons différents
    add("Shell", "carapace", (0, 26, 6), (56, 12, 42), (-4, 0, 0), "shell")
    add("Shell", "dome", (-2, 34.5, 8), (42, 8, 31), (-5, 3, 2), "dome")
    add("Plate_top", "sommet", (-5, 40, 13), (24, 4, 18), (-7, 8, -3), "dome_light")
    add("Plate_flank_right", "flanc_droit", (28, 24, 6), (8, 10, 38), (-4, 0, -24), "shell")
    add("Plate_flank_left", "flanc_gauche", (-28, 24.5, 5), (8, 11, 37), (-3, 0, 22), "dome")
    add("Shell", "rebord_avant", (0, 24.5, -15.5), (40, 5, 6), (-16, 0, 0), "dome")
    add("Plate_rear", "rebord_arriere", (0, 25, 27.5), (42, 7, 7), (18, 0, 0), "blackstone")
    add("Plate_shoulder_right", "epaule_droite", (22, 30.5, -10), (15, 5, 14), (-8, -12, -14), "shell_light")
    add("Plate_shoulder_left", "epaule_gauche", (-23, 29.5, -8), (14, 5, 15), (-6, 10, 18), "dome_light")
    # pointes d'obsidienne
    add("Shell", "pointe_1", (-15, 41, -3), (6, 12, 6), (-14, 20, 22), "obsidian")
    add("Shell", "pointe_2", (17, 38, 11), (5, 10, 5), (12, 30, -26), "obsidian")
    add("Plate_top", "pointe_3", (-6, 45, 17), (5, 8, 5), (22, 0, 10), "obsidian")
    add("Plate_flank_left", "pointe_4", (-27, 32, 18), (5, 8, 5), (14, 0, 42), "obsidian")
    add("Plate_shoulder_right", "pointe_5", (23, 33, -6), (4, 7, 4), (-18, 0, -38), "obsidian")
    # cœur : fissure bordée de blackstone, lit de géode, cristaux
    add("Shell", "fissure_a", (1, 39.2, -5), (6, 3, 15), (0, 28, 4), "blackstone")
    add("Shell", "fissure_b", (11.5, 39, -2.5), (5, 3, 13), (0, 28, -5), "blackstone")
    add("Shell", "geode", (6, 38.6, -4), (9, 2, 12), (0, 28, 0), "geode")
    add("glow_heart", "cristal_principal", (6, 43, -4.5), (5, 11, 5), (14, 30, -24), "amethyst")
    add("glow_heart", "cristal_2", (2.5, 41.5, -1), (3, 6, 3), (-12, 20, 22), "amethyst")
    add("glow_heart", "cristal_3", (9.5, 41, -8.5), (3, 5, 3), (20, 40, -8), "amethyst")

    # ---------------------------------------------------------------- tête : architecture faciale (16 volumes)
    add("Head", "cou", (0, 17, -21), (15, 10, 12), (10, 0, 0), "deep")
    face("Head", "crane", (0, 19, -32), (18, 10, 14), (0, 0, 0), "head")
    face("Head", "crane_sommet", (0, 24.5, -31), (16, 3, 13), (0, 0, 0), "head_top")
    for s, side in ((1, "droit"), (-1, "gauche")):
        # arcade séparée, plus basse vers le centre : regard qui fronce
        face("Head", f"arcade_{side}", (6.2 * s, 22.7, -39.3), (7, 2.4, 4.2), (0, -8 * s, 14 * s), "brow")
        # cavité sombre, l'œil y est encastré
        face("Head", f"orbite_{side}", (6.2 * s, 20.2, -39.4), (6.2, 3, 1.6), (0, 0, 8 * s), "cavity")
        face("glow_eyes", f"oeil_{side}", (6.2 * s, 20.1, -40.0), (3.6, 1.5, 0.6), (0, 0, 10 * s), "eye")
        # joue : déborde sur le côté, pierre plus taupe
        face("Head", f"joue_{side}", (9 * s, 16.4, -36.2), (4.5, 6, 8), (0, -6 * s, 0), "cheek")
        # côté du museau, un ton plus sombre
        face("Head", f"museau_{side}", (4.9 * s, 16.3, -40.7), (3.6, 4, 4), (0, -5 * s, 0), "snout_side")
        # croc minéral discret, à l'avant de la mâchoire
        face("Jaw", f"croc_{side}", (4.2 * s, 14.5, -42.4), (1.4, 1.8, 1.4), (0, 0, 0), "fang")
    face("Head", "museau", (0, 17.3, -41.3), (9, 5, 5), (0, 0, 0), "snout")
    # bouche : fente très sombre en retrait entre le museau et la mâchoire
    face("Head", "bouche", (0, 14.3, -40), (13.5, 1.1, 6.5), (0, 0, 0), "cavity")
    face("Jaw", "machoire", (0, 12.1, -37), (15, 3.6, 12), (-3, 0, 0), "jaw")

    # ---------------------------------------------------------------- pattes : roche brute, pieds de granit
    for s, side in ((1, "right"), (-1, "left")):
        add(f"Leg_front_{side}", "jambe", (22 * s, 10, -13), (13, 13, 13), (0, -10 * s, 10 * s), "leg")
        add(f"Leg_front_{side}", "pied", (25 * s, 2, -15), (16, 4, 17), (0, -14 * s, 0), "foot")
        add(f"Leg_back_{side}", "jambe", (21 * s, 9, 16), (12, 11, 12), (0, 8 * s, 8 * s), "leg")
        add(f"Leg_back_{side}", "pied", (23 * s, 2, 18), (14, 4, 14), (0, 12 * s, 0), "foot")

    # ---------------------------------------------------------------- queue : épaisse, relevée, masse de blackstone
    add("Tail", "queue", (0, 15, 27.5), (11, 8, 9), (-16, 6, 0), "deep")
    # extrémité réduite (retour du 2026-11-06 : ne doit pas ressembler à un marteau) : transition puis bloc de blackstone
    add("Tail", "queue_milieu", (0.8, 17.2, 33.2), (8.5, 7, 8), (-22, 10, 0), "shell")
    add("Tail_tip", "transition", (1.6, 18.6, 37), (6.5, 5.5, 5), (-20, 16, 8), "deep")
    add("Tail_tip", "masse", (2.2, 19.6, 40), (7, 6.5, 6.5), (-14, 22, 14), "blackstone")
    return P


def matrix(r):
    return r if isinstance(r[0], (list, tuple)) and len(r) == 3 and isinstance(r[0][0], (int, float)) else rot(*r)


# --------------------------------------------------------------------------- géométrie
def corners(c, size, r):
    M = matrix(r)
    out = []
    for sx in (-0.5, 0.5):
        for sy in (-0.5, 0.5):
            for sz in (-0.5, 0.5):
                p = app(M, [sx * size[0], sy * size[1], sz * size[2]])
                out.append([p[i] + c[i] for i in range(3)])
    return out


def solve():
    """Pose les pieds au sol, puis réduit le modèle s'il dépasse les limites du brief (5 x 4 x 5 blocs)."""
    P = parts()
    pts = [p for g, n, c, size, r, m in P for p in corners(c, size, r)]
    y0 = min(p[1] for p in pts)
    span = [max(p[i] for p in pts) - min(p[i] for p in pts) for i in range(3)]
    k = min(1.0, MAX_W / span[0], MAX_H / (max(p[1] for p in pts) - y0), MAX_L / span[2])
    cx = (max(p[0] for p in pts) + min(p[0] for p in pts)) / 2
    cz = (max(p[2] for p in pts) + min(p[2] for p in pts)) / 2
    out = []
    for g, n, c, size, r, m in P:
        out.append((g, n, [(c[0] - cx) * k, (c[1] - y0) * k, (c[2] - cz) * k], [v * k for v in size], r, m, size))
    groups = {name: ([(pv[0] - cx) * k, (pv[1] - y0) * k, (pv[2] - cz) * k], parent)
              for name, (pv, parent) in GROUPS.items()}
    dims = [span[0] * k / 16, (max(p[1] for p in pts) - y0) * k / 16, span[2] * k / 16]
    return out, groups, k, dims


def uid(name):
    return str(uuid.uuid5(NS, f"{IDENT}/{name}"))


def bake(P):
    """Deux atlas (roche, lumineux) à 1 texel par unité de dessin, étagères triées par hauteur."""
    reqs = []
    for i, (g, n, c, size, r, m, raw) in enumerate(P):
        dx, dy, dz = raw
        for d, (fw, fh) in {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
                            "up": (dx, dz), "down": (dx, dz)}.items():
            reqs.append((i, d, fw, fh, m, 1 if m in GLOW_MATS else 0))
    uv = {}
    atlases, sizes = {}, {}
    for t in (0, 1):
        mine = sorted((q for q in reqs if q[5] == t), key=lambda q: (-math.ceil(q[3]), -math.ceil(q[2])))
        for size in (32, 64, 128, 256, 512):
            x = y = shelf = 0
            place, ok = [], True
            for q in mine:
                w, h = max(1, math.ceil(q[2] - 1e-6)), max(1, math.ceil(q[3] - 1e-6))
                if x + w > size:
                    x, y, shelf = 0, y + shelf, 0
                if y + h > size:
                    ok = False
                    break
                place.append((x, y, w, h))
                x, shelf = x + w, max(shelf, h)
            if ok:
                break
        atlas = [[(0, 0, 0, 0)] * size for _ in range(size)]
        rnd = random.Random(4417 + t)
        for (i, d, fw, fh, m, _), (x, y, w, h) in zip(mine, place):
            img = (GLOW_MATS if t else MATS)[m](w, h, random.Random(rnd.random()), d)
            for j in range(h):
                for k in range(w):
                    atlas[y + j][x + k] = tuple(img[j][k]) + (255,)
            uv[(i, d)] = {"uv": [x, y, round(x + fw, 3), round(y + fh, 3)], "texture": t}
        atlases[t], sizes[t] = atlas, size
    return uv, atlases, sizes


def export(P, groups, uv, atlases, sizes):
    elements = []
    for i, (g, n, c, size, r, m, raw) in enumerate(P):
        e = {"name": n, "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
             "allow_mirror_modeling": True, "from": [round(c[j] - size[j] / 2, 4) for j in range(3)],
             "to": [round(c[j] + size[j] / 2, 4) for j in range(3)], "autouv": 0, "color": 0,
             "origin": [round(v, 4) for v in c], "type": "cube", "uuid": uid(f"el/{g}/{n}/{i}"),
             "faces": {d: uv[(i, d)] for d in ("north", "east", "south", "west", "up", "down")}}
        rr = euler(matrix(r))
        if any(abs(v) > 1e-3 for v in rr):
            e["rotation"] = rr
        elements.append(e)

    def group(name, origin):
        return {"name": name, "origin": [round(v, 4) for v in origin], "color": 0, "uuid": uid("group/" + name),
                "export": True, "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True,
                "autouv": 0, "rotation": [0, 0, 0], "children": []}
    index = {name: group(name, pv) for name, (pv, parent) in groups.items()}
    for (g, *_), e in zip(P, elements):
        index[g]["children"].append(e["uuid"])
    roots = []
    for name, (pv, parent) in groups.items():
        (index[parent]["children"] if parent else roots).append(index[name])

    def tex(i, name, px, w, mode):
        return {"path": "", "name": name, "folder": "", "namespace": "", "id": str(i), "width": w, "height": w,
                "uv_width": w, "uv_height": w, "particle": i == 0, "use_as_default": False, "layers_enabled": False,
                "sync_to_project": "", "render_mode": mode, "render_sides": "auto", "frame_time": 1,
                "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False, "visible": True,
                "internal": True, "saved": False, "uuid": uid("texture/" + name),
                "source": "data:image/png;base64," + base64.b64encode(encode(w, w, px)).decode()}
    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": IDENT, "model_identifier": IDENT, "visible_box": [5, 4, 0], "variable_placeholders": "",
            "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
            "resolution": {"width": sizes[0], "height": sizes[0]}, "elements": elements,
            "outliner": [{"name": IDENT, "origin": [0, 0, 0], "color": 0, "uuid": uid("group"), "export": True,
                          "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True, "autouv": 0,
                          "rotation": [0, 0, 0], "children": roots}],
            "textures": [tex(0, IDENT + ".png", atlases[0], sizes[0], "default"),
                         tex(1, IDENT + "_lumiere.png", atlases[1], sizes[1], "emissive")],
            "animations": []}


def apercu(data, atlases, sizes, path, size=360):
    """Planche sur fond neutre : trois-quarts avant (vue principale), face, profil, trois-quarts arrière, et la
    silhouette en noir (le brief demande qu'elle se lise même toute noire). Les pièces lumineuses ne sont pas
    assombries par l'orientation des faces."""
    import apercu as A
    W0 = sizes[0]
    combo = [atlases[0][y] + (atlases[1][y] if y < sizes[1] else [(0, 0, 0, 0)] * sizes[1]) for y in range(W0)]
    els = []
    for e in data["elements"]:
        e = json.loads(json.dumps(e))
        for f in e["faces"].values():
            if f["texture"] == 1:
                f["uv"] = [f["uv"][0] + W0, f["uv"][1], f["uv"][2] + W0, f["uv"][3]]
                f["texture"] = 0
        els.append(e)
    bg = (58, 58, 62, 255)
    hot = {c for pal in GLOW.values() for c in pal}
    imgs = []
    for yaw, pitch in ((32, 16), (0, 8), (90, 4), (212, 18)):
        img = A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=30)
        saved = dict(A.SHADE)
        A.SHADE.update(dict.fromkeys(A.SHADE, 1.0))
        try:
            lit = A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=30)
        finally:
            A.SHADE.update(saved)
        for y in range(size):
            for x in range(size):
                if tuple(lit[y][x][:3]) in hot:
                    img[y][x] = lit[y][x]
        imgs.append(img)
    sil = [[(20, 20, 22, 255) if px != bg else (205, 205, 210, 255) for px in row] for row in imgs[0]]
    imgs.append(sil)
    write(path, size * len(imgs), size, [sum((im[y] for im in imgs), []) for y in range(size)])


def strips(data, atlases, sizes, folder, count=8):
    """Une bande PNG par animation (profil au-dessus, trois-quarts au-dessous), cadrage fixe, et le point le plus bas
    de chaque image (0 = sol)."""
    import apercu as A
    from apercu_anim import posed_elements
    os.makedirs(folder, exist_ok=True)
    W0 = sizes[0]
    combo = [atlases[0][y] + (atlases[1][y] if y < sizes[1] else [(0, 0, 0, 0)] * sizes[1]) for y in range(W0)]
    flat = json.loads(json.dumps(data))
    for e in flat["elements"]:
        for f in e["faces"].values():
            if f["texture"] == 1:
                f["uv"] = [f["uv"][0] + W0, f["uv"][1], f["uv"][2] + W0, f["uv"][3]]
                f["texture"] = 0
    size = 180
    for a in flat["animations"]:
        n = count if a["loop"] == "loop" else count + 2
        times = [a["length"] * i / (n if a["loop"] == "loop" else n - 1) for i in range(n)]
        frames = [posed_elements(flat, a["name"], t) for t in times]
        pts, lows = [], []
        for fe in frames:
            low = 1e9
            for e in fe:
                for cx in (e["from"][0], e["to"][0]):
                    for cy in (e["from"][1], e["to"][1]):
                        for cz in (e["from"][2], e["to"][2]):
                            q = app(e["_M"], [cx - e["origin"][0], cy - e["origin"][1], cz - e["origin"][2]])
                            q = [q[i] + e["_q"][i] for i in range(3)]
                            pts.append(q)
                            low = min(low, q[1])
            lows.append(round(low, 1))
        rows = []
        for yaw, pitch in ((90, 0), (32, 14)):
            imgs = [A.render(fe, combo, yaw, pitch, size=size, frame_points=pts) for fe in frames]
            rows += [sum((im[y] for im in imgs), []) for y in range(size)]
        write(os.path.join(folder, f"anim_{a['name']}.png"), size * len(frames), 2 * size, rows)
        print(f"{a['name']} ({a['length']} s) : point le plus bas par image {lows}")


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = anims = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    if "--anims" in args:
        i = args.index("--anims")
        anims = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, k, dims = solve()
    uv, atlases, sizes = bake(P)
    data = export(P, groups, uv, atlases, sizes)
    import colosse_abime_anim
    data["animations"] = colosse_abime_anim.build(uid, k)
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x L), échelle {k:.3f} ; "
          f"textures {sizes[0]} et {sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev)
    if anims:
        strips(data, atlases, sizes, anims)
