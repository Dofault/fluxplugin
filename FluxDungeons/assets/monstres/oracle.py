#!/usr/bin/env python3
"""Oracle : mage ancestral, guide et donneur de quêtes, modèle Blockbench (.bbmodel) STATIQUE.

Refonte complète du 2026-11-06 : personnage de fantasy original, pas un skin de joueur. Proportions du brief (en blocs)
: 3,2 de haut chapeau compris, tête 0,72 x 0,68 x 0,62, chapeau 1,35 de large, épaules 0,95, torse 0,78, bas de robe
1,05. Visage construit en volumes (face avancée, joues, sourcils inclinés séparés, yeux encastrés, nez en relief,
bouche à deux lèvres, moustache), barbe en cinq volumes, grand chapeau à rebord relevé et cône courbé vers la gauche,
robe en trois pans évasés. Palette officielle d'Oracle uniquement. Le cristal lévite au-dessus de la paume droite et la
pierre du chapeau est légèrement lumineuse (texture émissive, os glow_).

Usage : oracle.py [dossier de sortie] [--apercu <planche.png>]
Face avant au nord (-z), côté droit du personnage en +x, origine au centre des pieds, sol en y = 0.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import LIGHT, apercu, attach, bake, export, ground, hx, tone  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "oracle"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000000a7c1")
HEIGHT = 3.2          # blocs, chapeau compris


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette officielle (sombre → clair)
V = pal("#392743", "#493054", "#5D4072", "#705287", "#806397", "#9580AA", "#AD99BF", "#C3B2D2")
BEARD = pal("#80788D", "#AAA2B4", "#D2C9DC", "#B6A0D0")
SKIN = pal("#A98876", "#C6A68F", "#D7BCA6")
CHEEK = hx("#C79E8E")                       # beige rosé très désaturé
GOLD = hx("#B39A6B")
AMETHYST = pal("#6E4A9A", "#9466C5", "#B18ADB", "#C3B2D2")


# --------------------------------------------------------------------------- peintres
def lit(face, sides=-1):
    return LIGHT[face] + (sides if face in ("east", "west") else 0)


def hat(w, h, r, face):
    """Tissu ancien mat : un ton par face, dessous du rebord dans l'ombre, quelques amas lavande sur le dessus."""
    b = 3 + lit(face)
    px = [[tone(V, b)] * w for _ in range(h)]
    if face == "up":
        for _ in range(max(1, w * h // 30)):
            x0, y0 = r.randrange(max(1, w - 2)), r.randrange(h)
            for x in range(x0, min(w, x0 + 3)):
                px[y0][x] = tone(V, 5)
    elif face not in ("down",):
        for _ in range(max(1, w * h // 22)):
            x0, y0 = r.randrange(max(1, w - 2)), r.randrange(h)
            for x in range(x0, min(w, x0 + 3)):
                px[y0][x] = tone(V, b - 1)
        if h >= 3:
            px[h - 1] = [tone(V, b - 1)] * w
    return px


def tunic(base=3):
    def f(w, h, r, face):
        """Tunique et manches : face éclairée, dessous et bas de face plus sombres (sous les bras)."""
        b = base + lit(face)
        px = [[tone(V, b)] * w for _ in range(h)]
        if face not in ("up", "down"):
            for _ in range(max(1, w * h // 20)):
                x0, y0 = r.randrange(w), r.randrange(max(1, h - 2))
                for y in range(y0, min(h, y0 + 3)):
                    px[y][x0] = tone(V, b - 1)
            for y in range(max(0, h - 2), h):
                px[y] = [tone(V, b - 1)] * w
        return px
    return f


def robe(w, h, r, face):
    """Robe : plis verticaux larges (deux tons par bandes de 4), côtés plus sombres, quelques motifs anguleux discrets
    (lignes et chevrons dorés) dans le bas du devant."""
    b = 2 + lit(face)
    if face in ("up", "down"):
        return [[tone(V, b)] * w for _ in range(h)]
    px = [[tone(V, b + (1 if (x // 4) % 2 == 0 else 0)) for x in range(w)] for y in range(h)]
    if face == "north" and h >= 6 and w >= 6:
        y = h - 4
        for x in range(1, w - 1):
            if x % 5 in (1, 2, 3):
                px[y][x] = tone(V, 5)
        for cx in range(3, w - 2, 5):
            px[y - 1][cx] = GOLD
    return px


def band(w, h, r, face):
    """Bande lavande : petits losanges et points d'or espacés."""
    b = 6 + min(0, LIGHT[face])
    px = [[tone(V, b)] * w for _ in range(h)]
    if face == "north":
        for y in range(1, h, 3):
            for x in range(w):
                px[y][x] = GOLD if (x + y) % 3 == 0 else tone(V, 5)
    return px


def trim(w, h, r, face):
    """Bordures (poignets, bas de robe, bande du chapeau) : lavande, liseré d'or en haut."""
    b = 5 + lit(face)
    px = [[tone(V, b)] * w for _ in range(h)]
    if face not in ("up", "down"):
        px[0] = [GOLD if x % 4 == 1 else tone(V, b + 1) for x in range(w)]
    return px


def belt(w, h, r, face):
    b = 1 + lit(face)
    px = [[tone(V, b)] * w for _ in range(h)]
    if face == "north":
        for x in range(1, w, 3):
            px[h // 2][x] = GOLD
    return px


def shoe(w, h, r, face):
    b = 1 + lit(face)
    px = [[tone(V, b)] * w for _ in range(h)]
    if face not in ("up", "down"):
        px[0] = [tone(V, 5)] * w
    return px


def beard(w, h, r, face):
    """Barbe : mèches verticales de 2 pixels en trois nuances, ombre en bas, quelques reflets lavande."""
    b = 1 + (1 if face == "up" else -1 if face in ("east", "west", "down") else 0)
    px = [[tone(BEARD[:3], b + (1 if (x // 2) % 3 == 0 else -1 if (x // 2) % 3 == 2 else 0)) for x in range(w)]
          for y in range(h)]
    if face not in ("up", "down") and h >= 2:
        px[h - 1] = [tone(BEARD[:3], b - 1)] * w
        for _ in range(max(1, w * h // 20)):
            x, y = r.randrange(w), r.randrange(h - 1)
            px[y][x] = BEARD[3]
    return px


def skin(w, h, r, face):
    b = 1 + lit(face)
    return [[tone(SKIN, b)] * w for _ in range(h)]


def cheek(w, h, r, face):
    px = skin(w, h, r, face)
    if face == "north":
        for y in range(h // 2, h):
            for x in range(w):
                if (x + y) % 2 == 0:
                    px[y][x] = CHEEK
    return px


def nose(w, h, r, face):
    """Nez : face avant claire, un côté ombré, dessous dans l'ombre."""
    b = {"north": 2, "up": 2, "west": 1, "east": 0, "south": 1, "down": 0}[face]
    return [[tone(SKIN, b)] * w for _ in range(h)]


def skull(w, h, r, face):
    """Crâne : cheveux gris lavande en mèches sur les côtés et l'arrière (sous le chapeau), peau en bas des côtés."""
    if face == "north":
        return [[tone(SKIN, 1)] * w for _ in range(h)]
    if face == "up":
        return [[BEARD[1]] * w for _ in range(h)]
    hair = h if face == "south" else h * 2 // 3
    shade = hx("#968EA2")                         # entre l'ombre et la base de la barbe : mèches douces
    return [[(shade if (x // 2) % 3 == 2 else BEARD[1]) if y < hair else tone(SKIN, 0) for x in range(w)]
            for y in range(h)]


def brow(w, h, r, face):
    b = 0 + (1 if face == "up" else 0)
    return [[tone(pal("#6E6276", "#80788D"), b)] * w for _ in range(h)]


def eye(w, h, r, face):
    """Œil (densité 2) : paupière d'ombre en haut, blanc lavande désaturé, iris violet, reflet lavande clair."""
    if face != "north":
        return [[SKIN[0]] * w for _ in range(h)]
    px = [[hx("#D2C9DC")] * w for _ in range(h)]
    px[0] = [SKIN[0]] * w                               # paupière : petite zone d'ombre
    c = w // 2
    for y in range(1, h):
        for x in (c - 1, c, c + 1):
            if 0 <= x < w:
                px[y][x] = V[3]
        px[y][c] = V[1]
    px[1][c + 1 if c + 1 < w else c] = V[7]             # petit reflet
    return px


def mouth(w, h, r, face):
    """Bouche (densité 2) : lèvre supérieure plus sombre aux coins relevés, lèvre inférieure plus claire au centre."""
    if face != "north":
        return [[SKIN[0]] * w for _ in range(h)]
    px = [[SKIN[1]] * w for _ in range(h)]
    upper, corner, lower = hx("#8A6660"), hx("#9C766B"), hx("#BE9483")
    for x in range(1, w - 1):
        px[min(1, h - 1)][x] = upper
    px[0][0] = px[0][w - 1] = corner
    if h >= 3:
        for x in range(2, w - 2):
            px[2][x] = lower
    return px


def crystal(w, h, r, face):
    b = {"up": 2, "north": 2, "east": 1, "south": 1, "west": 0, "down": 0}[face]
    px = [[tone(AMETHYST, b - (1 if (x + y) % 3 == 2 else 0)) for x in range(w)] for y in range(h)]
    if face in ("north", "up") and w >= 2:
        px[0][0] = AMETHYST[3]
    return px


MATS = {
    "hat": hat, "tunic": tunic(3), "yoke": tunic(4), "sleeve": tunic(3), "robe": robe, "band": band, "trim": trim,
    "belt": belt, "shoe": shoe, "beard": beard, "skin": skin, "cheek": cheek, "nose": nose, "skull": skull,
    "brow": brow, "eye": (eye, 2), "mouth": (mouth, 2), "gem": crystal,
}
GLOW_MATS = {"crystal": crystal}


# --------------------------------------------------------------------------- Oracle (unités de dessin ; mis à 3,2 blocs)
NECK = (0, 29.5, 0)
HEAD_R = rot(-6, -10, 2)          # tête un peu inclinée vers l'avant et tournée vers le joueur
HAT_R = rot(-2, 0, 4)             # chapeau penché vers la gauche du personnage
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, 17, 0), "Root"),
    "Head": (NECK, "Body"),
    "Hat": ((0, 41, 0), "Head"),
    "glow_hat_gem": ((0, 42.4, -5.6), "Hat"),
    "Right_Arm": ((7.4, 27, 0), "Body"),
    "Right_Forearm": ((9, 21.5, -2), "Right_Arm"),
    "glow_crystal": ((9.2, 24.5, -10.6), "Right_Forearm"),
    "Left_Arm": ((-7.4, 27, 0), "Body"),
    "Left_Forearm": ((-9.2, 19.5, 0.3), "Left_Arm"),
    "Right_Leg": ((3, 3, 0), "Root"),
    "Left_Leg": ((-3, 3, 0), "Root"),
}


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, c, size, r, mat))

    on_head = attach(NECK, HEAD_R)
    on_hat = attach((0, 40.5, 0), HAT_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    def ht(g, name, c, size, r, mat):
        c1, M1 = on_hat(c, r)                       # le chapeau penche, puis suit la tête
        c2, M2 = on_head(c1, M1)
        add(g, name, c2, size, M2, mat)

    # ---------------------------------------------------------------- pieds, légèrement écartés
    for s, side in ((1, "Right"), (-1, "Left")):
        add(f"{side}_Leg", "chaussure", (3.0 * s, 1.2, -1.4), (4.4, 2.4, 6.8), (0, -8 * s, 0), "shoe")

    # ---------------------------------------------------------------- robe en trois pans évasés, ceinture, torse
    add("Body", "robe_bas", (0, 4.3, 0), (16.8, 2.6, 10.2), (0, 0, 0), "trim")
    add("Body", "robe_centre", (0, 10.6, -0.2), (9, 12.6, 8.4), (-2, 0, 0), "robe")
    add("Body", "robe_droite", (5.6, 9.8, 0.3), (4.6, 11.4, 8.8), (0, 0, 4), "robe")
    add("Body", "robe_gauche", (-5.6, 9.8, 0.3), (4.6, 11.4, 8.8), (0, 0, -4), "robe")
    add("Body", "ceinture", (0, 17.4, 0), (11.4, 1.8, 7.8), (0, 0, 0), "belt")
    add("Body", "torse", (0, 22.8, 0), (12.5, 9.6, 7.4), (0, 0, 0), "tunic")
    add("Body", "epaules", (0, 27.2, 0), (15.2, 3.2, 8), (0, 0, 0), "yoke")
    add("Body", "bande", (0, 22.6, -3.9), (3, 10.4, 0.5), (0, 0, 0), "band")
    add("Body", "pierre_sternum", (0, 25.2, -4.25), (1.8, 1.8, 0.6), (0, 0, 45), "gem")

    # ---------------------------------------------------------------- visage construit en volumes (tourné avec la tête)
    hd("Head", "crane", (0, 35.4, 0.4), (11.5, 10, 9.4), (0, 0, 0), "skull")
    hd("Head", "face", (0, 34.6, -4.65), (9.4, 7.8, 1.0), (0, 0, 0), "skin")
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd("Head", f"joue_{side}", (3.6 * s, 32.4, -5.0), (2.6, 2.6, 1.2), (0, -8 * s, 0), "cheek")
        # sourcils séparés, inclinés : l'intérieur relevé (air interrogatif et bienveillant)
        hd("Head", f"sourcil_{side}", (2.5 * s, 37.4, -5.45), (3.2, 0.9, 0.9), (0, 0, -10 * s), "brow")
        hd("Head", f"oeil_{side}", (2.5 * s, 35.8, -5.2), (2.4, 1.4, 0.3), (0, 0, 0), "eye")
    hd("Head", "nez", (0, 34.3, -5.6), (1.6, 2.6, 1.4), (0, 0, 0), "nose")
    hd("Head", "moustache", (0, 32.85, -5.5), (4.8, 0.9, 0.7), (0, 0, 0), "beard")
    hd("Head", "bouche", (0, 32.0, -5.25), (3.4, 1.4, 0.4), (0, 0, 0), "mouth")
    # barbe : joues et mâchoire, menton, trois mèches décalées qui s'effilent sur le torse
    hd("Head", "barbe_joues", (0, 30.4, -3.4), (10.4, 2.4, 3.6), (0, 0, 0), "beard")
    hd("Head", "barbe_menton", (0, 28.2, -4.0), (6.8, 3.4, 2.8), (8, 0, 0), "beard")
    hd("Head", "meche_droite", (1.6, 25.2, -4.3), (2.8, 4.2, 2.0), (4, 0, -6), "beard")
    hd("Head", "meche_gauche", (-1.5, 25.6, -4.2), (2.8, 3.6, 2.0), (4, 0, 7), "beard")
    hd("Head", "meche_pointe", (0.2, 22.3, -4.4), (2.2, 2.6, 1.6), (6, 0, 10), "beard")

    # ---------------------------------------------------------------- chapeau : rebord relevé, cône courbé vers la gauche
    ht("Hat", "rebord", (0, 40.9, 0), (21.6, 1.4, 19), (0, 0, 0), "hat")
    ht("Hat", "rebord_droit", (10.4, 41.7, 0), (3.2, 1.2, 14), (0, 0, 16), "hat")
    ht("Hat", "rebord_gauche", (-10.4, 41.7, 0), (3.2, 1.2, 14), (0, 0, -16), "hat")
    ht("Hat", "bande", (0, 42.4, 0.2), (12.4, 1.8, 11), (0, 0, 0), "trim")
    ht("glow_hat_gem", "pierre", (0, 42.4, -5.75), (1.8, 1.8, 0.6), (0, 0, 45), "crystal")
    ht("Hat", "cone_bas", (0, 45.3, 0.5), (11, 4.4, 10), (0, 0, 3), "hat")
    ht("Hat", "cone_milieu", (-0.8, 49.0, 1.4), (8, 4.2, 7.4), (5, 0, 9), "hat")
    ht("Hat", "cone_haut", (-2.3, 52.4, 2.4), (5.4, 3.8, 5), (9, 0, 17), "hat")
    ht("Hat", "pointe", (-4.4, 54.8, 3.4), (3.2, 2.8, 3.2), (14, 0, 34), "hat")

    # ---------------------------------------------------------------- bras droit relevé, avant-bras vers l'avant
    add("Right_Arm", "manche_haut", (8.6, 24.2, -0.5), (4.8, 7.5, 5.4), (-18, 0, 6), "sleeve")
    add("Right_Forearm", "manche", (9.2, 20.4, -5.2), (5.6, 5.6, 7), (-72, 0, 0), "sleeve")
    add("Right_Forearm", "poignet", (9.2, 20.7, -9.0), (6.4, 6.4, 1.4), (-72, 0, 0), "trim")
    add("Right_Forearm", "main", (9.2, 21.0, -10.6), (3.4, 2.6, 3.4), (0, 0, 0), "skin")
    # cristal qui lévite au-dessus de la paume
    add("glow_crystal", "cristal", (9.2, 25.0, -10.8), (2.2, 3.8, 2.2), (12, 25, 15), "crystal")
    add("glow_crystal", "cristal_2", (10.4, 24.0, -10.3), (1.3, 2.4, 1.3), (-8, 0, -24), "crystal")

    # ---------------------------------------------------------------- bras gauche détendu, manche évasée
    add("Left_Arm", "manche_haut", (-8.6, 23.4, 0.2), (4.8, 8, 5.4), (0, 0, -5), "sleeve")
    add("Left_Forearm", "manche", (-9.4, 16.6, 0.4), (5.8, 6.4, 6.2), (0, 0, -8), "sleeve")
    add("Left_Forearm", "poignet", (-9.8, 13.1, 0.4), (6.4, 1.4, 6.8), (0, 0, -8), "trim")
    add("Left_Forearm", "main", (-10.0, 11.4, 0.4), (3.4, 3.2, 3.4), (0, 0, -6), "skin")
    return P


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    _, _, dims = ground(parts(), GROUPS, center=False)
    P, groups, dims = ground(parts(), GROUPS, scale=HEIGHT / dims[1], center=False)   # origine au centre des pieds
    uv, atlases, sizes = bake(P, MATS, GLOW_MATS)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; textures {sizes[0]} et "
          f"{sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(AMETHYST), views=((32, 8), (0, 2), (90, 2), (205, 10)), size=400)
