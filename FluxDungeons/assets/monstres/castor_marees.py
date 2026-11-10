#!/usr/bin/env python3
"""Castor des Marées : boss côtier, modèle Blockbench (.bbmodel) STATIQUE (2026-11-09).

Castor gigantesque qui vit depuis toujours entre les rochers et l'écume : quadrupède bas, large et lourd, dos voûté qui
monte vers des hanches plus grosses que les épaules, tête basse et avancée (il fixe une menace), deux grandes incisives
ivoire, queue large et plate qui descend vers le sol. Les touches marines (bernacles, coquillages, deux algues, une
pierre incrustée à la base de la queue) restent secondaires : le pelage brun domine.

Volumes pensés un par un, inclinés et décalés pour casser l'effet de briques empilées ; le pelage, les écailles de la
queue et les griffes sont peints (1 texel par unité, comme un bloc 16 x 16), jamais construits en petits cubes.
Pose cuite dans les cubes, os rangés aux articulations pour une animation future ; pas d'animation ni de lumière.

47 volumes, 4,3 x 3,1 x 7,1 blocs (largeur x hauteur x longueur, queue comprise ; 5,4 blocs de la truffe aux
hanches). Proportions de chaque partie tirées du brief (corps 46 de large, tête 28 + joues, queue 24 de large) ;
SCALE réduit le tout d'un coup si la salle du boss l'impose (0,9 : 3,8 x 2,8 x 6,4).

Usage : castor_marees.py [dossier de sortie] [--apercu <planche.png>]
Face avant au nord (-z), côté droit du monstre en +x, sol en y = 0, centré en x et z sur sa boîte englobante.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx, tone  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "castor_marees"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000ca570")
SCALE = 1.0           # unités de dessin = unités du modèle (16 = 1 bloc) ; seul réglage de taille globale


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette du brief (sombre → clair)
FUR = pal("#35251F", "#493126", "#68432E", "#815638", "#9A704D")
BELLY = pal("#554239", "#766052", "#957B65")
TAIL = pal("#302722", "#49372E", "#624B3C")
ALGAE = pal("#344B3B", "#50684A")
SHELL, CORAL, STONE = hx("#B29A79"), hx("#9B6857"), hx("#66665E")
SHELL_SHADE, STONE_SHADE, STONE_LIT = hx("#9A8466"), hx("#56564F"), hx("#76766D")
IRIS, PUPIL, TOOTH, CLAW = hx("#A66C2C"), hx("#17130F"), hx("#D6C59A"), hx("#392B24")
TOOTH_SHADE = hx("#BBA97F")

SIDE = {"up": 0, "north": 0, "east": 0, "west": 0, "south": -1, "down": -1}   # lumière du haut, un peu de face


# --------------------------------------------------------------------------- peintres
def fur(base=2, up=0):
    def f(w, h, r, face):
        """Pelage : aplat d'un ton, petites mèches de 1 ou 2 pixels (jamais de longues lignes), plus clair sur le
        dessus, ombre sous les volumes (bas des côtés, dessous)."""
        b = base + SIDE[face] + (up if face == "up" else 0)
        px = [[tone(FUR, b)] * w for _ in range(h)]
        if face == "up":
            for _ in range(max(1, w * h // 24)):
                x, y = r.randrange(w), r.randrange(h)
                px[y][x] = tone(FUR, b + 1)
                if x + 1 < w and r.random() < 0.4:
                    px[y][x + 1] = tone(FUR, b + 1)
            for _ in range(w * h // 22):
                px[r.randrange(h)][r.randrange(w)] = tone(FUR, b - 1)
        elif face == "down":
            for _ in range(w * h // 16):
                px[r.randrange(h)][r.randrange(w)] = tone(FUR, b - 1)
        else:
            for _ in range(max(1, w * h // 26)):
                x, y = r.randrange(w), r.randrange(max(1, h - 1))
                c = tone(FUR, b + 1) if (y < h // 2 and r.random() < 0.25) else tone(FUR, b - 1)
                px[y][x] = c
                if y + 1 < h and r.random() < 0.45:
                    px[y + 1][x] = c
            if h >= 4:
                px[h - 1] = [tone(FUR, b - 1)] * w
            if h >= 9:
                px[h - 2] = [tone(FUR, b - 1) if (x + r.randrange(2)) % 2 else px[h - 2][x] for x in range(w)]
            if h >= 6 and face != "south":
                for x in range(w):
                    if r.random() < 0.2:
                        px[0][x] = tone(FUR, b + 1)
        return px
    return f


def belly(base=1, up=1):
    def f(w, h, r, face):
        """Museau, ventre, poitrail : taupe, un peu plus clair que le dos sans jamais tirer sur le beige."""
        b = base + SIDE[face] + (up if face == "up" else 0)
        px = [[tone(BELLY, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 14)):
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = tone(BELLY, b - 1 if r.random() < 0.7 else b + 1)
        if face not in ("up", "down") and h >= 4:
            px[h - 1] = [tone(BELLY, b - 1)] * w
        return px
    return f


def paw(webbed=False, claws=3):
    def f(w, h, r, face):
        """Pied : pelage sombre, quelques griffes courtes en bout (1 pixel de large, courbées vers le bas) ; pied
        arrière palmé : orteils séparés par la palmure plus sombre sur le dessus."""
        b = 1 + SIDE[face]
        px = [[tone(FUR, b)] * w for _ in range(h)]
        cols = [round((i + 0.5) * w / claws - 0.5) for i in range(claws)]
        if face == "north":
            for c in cols:
                for y in range(max(0, h - 3), h):
                    px[y][c] = CLAW
                if h >= 4:
                    px[h - 4][c] = tone(FUR, 2)          # bout de l'orteil, éclairé au-dessus de la griffe
        elif face == "up":
            for y in range(h):
                for x in range(w):
                    if webbed and x not in cols and y < h - 3:
                        px[y][x] = tone(FUR, 0)          # palmure
                    elif x in cols:
                        px[y][x] = tone(FUR, 2)          # orteil
            for c in cols:
                px[0][c] = CLAW                          # la griffe dépasse sur le dessus, à l'avant
        elif face not in ("down", "south"):
            px[h - 1] = [tone(FUR, 0)] * w
        return px
    return f


def tail(w, h, r, face):
    """Queue : plus sombre que le pelage, petites écailles en quinconce (cellules de 3 x 2, une sur six effacée),
    ombre en bas des tranches."""
    b = {"up": 1, "north": 1, "east": 1, "west": 1, "south": 1, "down": 0}[face]
    px = [[TAIL[b]] * w for _ in range(h)]
    if face in ("up", "down"):
        for y in range(0, h, 2):
            off = (y // 2) % 2 * 2 + (1 if (y // 2) % 3 == 2 else 0)
            for x in range(off, w, 3):
                if r.random() < 0.17:
                    continue
                px[y][x] = TAIL[max(0, b - 1)]
                if face == "up" and x + 1 < w and y + 1 < h and r.random() < 0.5:
                    px[y + 1][x + 1] = TAIL[2]
    else:
        for x in range(0, w, 3):
            if r.random() < 0.7:
                px[0][x] = TAIL[2]
        if h >= 3:
            px[h - 1] = [TAIL[0]] * w
    return px


def shell(w, h, r, face):
    """Bernacles et coquillages : aplat clair, stries tous les 2 ou 3 pixels, un point de corail discret."""
    px = [[SHELL if face != "down" else SHELL_SHADE] * w for _ in range(h)]
    for x in range(1, w, 3 if face == "up" else 2):
        for y in range(h):
            if (x + y) % 3:
                px[y][x] = SHELL_SHADE
    if w * h >= 12 and r.random() < 0.7:
        px[r.randrange(h)][r.randrange(w)] = CORAL
    return px


def stone(w, h, r, face):
    """Pierre marine incrustée : gris vert, quelques bernacles et un peu de corail sur le dessus."""
    px = [[STONE_LIT if face == "up" else STONE_SHADE if face in ("down", "south") else STONE] * w for _ in range(h)]
    for _ in range(max(1, w * h // 6)):
        px[r.randrange(h)][r.randrange(w)] = STONE_SHADE if face == "up" else STONE
    if face in ("up", "north", "east", "west"):
        for _ in range(max(1, w * h // 10)):
            px[r.randrange(h)][r.randrange(w)] = SHELL
        px[r.randrange(h)][r.randrange(w)] = CORAL
    return px


def algae(w, h, r, face):
    """Algue : brins verticaux de deux verts sombres, sans dégradé."""
    shift = r.randrange(3)
    return [[ALGAE[1] if (x + shift) % 3 == 0 and face != "down" else ALGAE[0] for x in range(w)] for y in range(h)]


def tooth(w, h, r, face):
    """Incisive : ivoire jaunâtre (jamais blanc pur), côtés et dessous ombrés, collet plus sombre sous la lèvre."""
    if face != "north":
        return [[TOOTH_SHADE if face in ("east", "west", "down", "south") else TOOTH] * w for _ in range(h)]
    px = [[TOOTH] * w for _ in range(h)]
    px[0] = [TOOTH_SHADE] * w
    return px


def eye(w, h, r, face):
    """Œil (densité 2) : petit, iris ambre foncé, pupille noire décalée vers l'intérieur, paupière basse sombre ;
    pas de reflet (regard menaçant, pas de dessin animé)."""
    if face != "north":
        return [[FUR[0]] * w for _ in range(h)]
    px = [[IRIS] * w for _ in range(h)]
    px[0] = [FUR[0]] * w
    px[h - 1] = [FUR[1]] * w
    for y in range(h):
        px[y][0] = px[y][w - 1] = FUR[0]
    c = w // 2
    for y in range(2, h - 1):
        for x in (c - 1, c):
            px[y][x] = PUPIL
    return px


def eye_left(w, h, r, face):
    """Œil gauche : pupille décalée de l'autre côté (les deux regardent devant, vers le centre)."""
    return [row[::-1] for row in eye(w, h, r, face)] if face == "north" else eye(w, h, r, face)


def brow(w, h, r, face):
    """Paupière supérieure épaisse : le pelage le plus sombre, arête du dessus un ton au-dessus."""
    b = 1 if face == "up" else 0
    px = [[FUR[b]] * w for _ in range(h)]
    if face == "north":
        px[0] = [FUR[1]] * w
    return px


def nose(w, h, r, face):
    """Truffe : brun très sombre, deux narines noires sur l'avant, arête plus claire en haut."""
    px = [[FUR[0]] * w for _ in range(h)]
    if face == "north":
        px[0] = [FUR[1]] * w
        for x in (1, w - 2):
            if h >= 3:
                px[h // 2 + (1 if h >= 4 else 0)][x] = PUPIL
    elif face == "up":
        px = [[FUR[1]] * w for _ in range(h)]
    return px


def ear(w, h, r, face):
    px = [[FUR[1 + (1 if face == "up" else 0)]] * w for _ in range(h)]
    if face == "north" and w >= 3 and h >= 3:
        for y in range(1, h):
            for x in range(1, w - 1):
                px[y][x] = FUR[0]
    return px


def whisker(w, h, r, face):
    """Moustache : taupe clair, la pointe un ton plus sombre."""
    return [[BELLY[2] if x < w - 2 else BELLY[1] for x in range(w)] for _ in range(h)]


MATS = {
    "fur": fur(2), "fur_dark": fur(1), "back": fur(2, up=0), "head": fur(2), "cheek": fur(2),
    "muzzle": belly(1, up=1), "belly": belly(1, up=0), "paw": paw(), "foot": paw(webbed=True, claws=4),
    "tail": tail, "shell": shell, "stone": stone, "algae": algae, "tooth": tooth, "eye": (eye, 2),
    "eye_l": (eye_left, 2), "brow": brow, "nose": (nose, 2), "ear": ear, "whisker": whisker,
}


# --------------------------------------------------------------------------- squelette (unités de dessin)
NECK = (0, 30, -24)
HEAD_BACK = 3                     # recul de toute la tête vers le corps (cou plus court)
HEAD_R = rot(-13, 7, 2.5)          # tête basse (il fixe la menace devant lui), un peu tournée et penchée
TAIL_ROOT, TAIL_MID = (0, 19, 23), (0, 8, 38)
TAIL_R, TIP_R = rot(0, 20, 0), rot(0, 18, 4)     # la queue s'incurve vers la droite : visible de trois quarts
# Pivots des os enfants : l'articulation dessinée droite, déplacée avec la tête baissée ou la queue incurvée.
JAW_HINGE = attach(NECK, HEAD_R)((0, 22.5, -41 + HEAD_BACK), (0, 0, 0))[0]     # arrière haut de la mâchoire
TIP_PIVOT = attach(TAIL_ROOT, TAIL_R)(TAIL_MID, (0, 0, 0))[0]                   # avant de la pale
GROUPS = {
    "Root": ((0, 0, 0), None),
    "Body": ((0, 24, 0), "Root"),
    "Head": (NECK, "Body"),
    "Jaw": (JAW_HINGE, "Head"),
    "Tail": (TAIL_ROOT, "Body"),
    "Tail_tip": (TIP_PIVOT, "Tail"),
    "Leg_front_right": ((17, 22, -14), "Root"),
    "Leg_front_left": ((-17, 22, -14), "Root"),
    "Leg_back_right": ((21, 24, 13), "Root"),
    "Leg_back_left": ((-21, 24, 13), "Root"),
}
# Pattes : (épaule ou hanche, inclinaison de toute la patte) ; écartées vers l'extérieur, jamais deux pareilles.
LEGS = {
    "Leg_front_right": (GROUPS["Leg_front_right"][0], rot(7, 0, 8)),
    "Leg_front_left": (GROUPS["Leg_front_left"][0], rot(4, 0, -6)),
    "Leg_back_right": (GROUPS["Leg_back_right"][0], rot(0, 0, 6)),
    "Leg_back_left": (GROUPS["Leg_back_left"][0], rot(-3, 0, -7)),
}


# Queue : (os, nom, taille, inclinaison vers le bas en degrés, roulis) de la base au bout ; chaque segment part du
# dessus du précédent (recouvrement de 3 unités), si bien que le dessus de la queue forme une seule pente continue
# au lieu d'un escalier. Changer une taille ou un angle ici suffit : les positions sont recalculées.
TAIL_SEGMENTS = (
    ("Tail", "queue_base", (16, 9, 11), 40, 0),
    ("Tail", "queue_milieu", (21, 6.5, 12), 26, 2),
    ("Tail_tip", "queue_pale", (24, 5.5, 15), 8, 3),
    ("Tail_tip", "queue_bout", (19, 4.5, 6), 12, 3),
)
TAIL_START = (0, 13, 26)    # centre du premier segment, sous la croupe


def tail_chain(overlap=3.0):
    import math
    out, prev = [], None
    for g, name, size, ax, roll in TAIL_SEGMENTS:
        h, d = size[1], size[2]
        a = math.radians(ax)

        def top(cy, cz, t, h=h, a=a):           # point du dessus, à la distance t du centre le long du segment
            return cy + h / 2 * math.cos(a) - t * math.sin(a), cz + h / 2 * math.sin(a) + t * math.cos(a)
        if prev is None:
            cy, cz = TAIL_START[1], TAIL_START[2]
        else:
            py, pz, ph, pa = prev
            ry, rz = py + ph / 2 * math.cos(pa) - (prev_d / 2) * math.sin(pa), pz + ph / 2 * math.sin(pa) + (prev_d / 2) * math.cos(pa)
            fz = rz - overlap                   # l'avant du segment rentre sous l'arrière du précédent
            fy = ry + (rz - fz) * math.tan(pa) + 0.2
            oy, oz = top(0, 0, -d / 2)
            cy, cz = fy - oy, fz - oz
        out.append((g, name, (0.3 * len(out), cy, cz), size, (ax, 0, roll)))
        prev, prev_d = (cy, cz, h, a), d
    return out


def parts():
    P = []

    def add(g, name, c, size, r, mat):
        P.append((g, name, c, size, r, mat))

    on_head = attach(NECK, HEAD_R)
    on_tail = attach(TAIL_ROOT, TAIL_R)
    on_tip = attach(TAIL_MID, TIP_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head((c[0], c[1], c[2] + HEAD_BACK), r)
        add(g, name, cc, size, M, mat)

    def tl(g, name, c, size, r, mat):
        if g == "Tail_tip":
            c, r = on_tip(c, r)                     # le bout s'incurve encore, puis suit la base
        cc, M = on_tail(c, r)
        add(g, name, cc, size, M, mat)

    # ---------------------------------------------------------------- corps : épaules < tronc < hanches, dos voûté
    add("Body", "epaules", (0, 27.5, -14), (40, 22, 18), (-8, 0, 1), "fur")
    add("Body", "tronc", (0, 27.5, 1), (36, 27, 34), (-3, 0, 0), "fur")
    add("Body", "flanc_droit", (19, 23, 2), (8, 17, 32), (-2, 0, 9), "fur")            # ventre plus large que le dos :
    add("Body", "flanc_gauche", (-19, 23.5, 2), (8, 17, 32), (-2, 0, -10), "fur")      # coupe du tronc en tonneau
    add("Body", "hanches", (0, 26, 14), (46, 24, 20), (3, 0, -1.5), "fur")
    add("Body", "croupe", (0, 36.5, 18.5), (42, 10, 16), (22, 0, -1.5), "fur")       # arrondit l'arrière en pente
    add("Body", "dos", (0.5, 41.8, 5), (24, 4.5, 32), (-7, 0, 2.5), "back")            # bosse du dos, plus claire
    add("Body", "cou", (0, 31, -24), (30, 18, 10), (-10, 0, 0), "fur")

    # ---------------------------------------------------------------- touches marines (secondaires)
    add("Body", "bernacles_epaule", (17, 37.4, -12), (8, 4, 6.5), (4, 28, 14), "shell")
    add("Body", "coquillage_dos", (-6.5, 46.0, 8), (5.5, 3, 5.5), (0, 45, -3), "shell")
    add("Body", "algue_epaule", (-19.5, 34.5, -11.5), (5, 1.6, 13), (6, -8, 48), "algae")
    add("Body", "algue_dos", (6.5, 45.5, -3), (2.4, 7, 2.4), (-14, 20, -18), "algae")

    # ---------------------------------------------------------------- tête (dessinée droite, puis baissée)
    hd("Head", "crane", (0, 34, -37), (28, 21, 24), (0, 0, 0), "head")
    hd("Head", "joue_droite", (13, 28.5, -42), (8, 13, 15), (0, 10, -3), "cheek")
    hd("Head", "joue_gauche", (-13, 28, -42), (8, 13, 15), (0, -8, 3), "cheek")
    # museau : deux babines qui se rejoignent devant (lèvre de rongeur), chanfrein incliné, truffe
    hd("Head", "babine_droite", (5.2, 27.0, -52.5), (10.5, 11, 9), (5, 12, 0), "muzzle")
    hd("Head", "babine_gauche", (-5.2, 26.8, -52.5), (10.5, 11, 9), (5, -11, 0), "muzzle")
    hd("Head", "chanfrein", (0, 33.5, -52.5), (15, 5, 12), (-16, 0, 0), "head")
    hd("Head", "truffe", (0, 31.6, -58.6), (8, 4.5, 3), (-16, 0, 0), "nose")
    hd("Head", "incisive_droite", (2.1, 19.6, -56.4), (3.6, 7.5, 2.2), (10, 0, 2), "tooth")
    hd("Head", "incisive_gauche", (-2.1, 19.6, -56.4), (3.6, 7.5, 2.2), (10, 0, -2.5), "tooth")
    hd("Head", "oeil_droit", (8.5, 37.5, -49.4), (4.4, 3, 1.0), (0, 0, 0), "eye")
    hd("Head", "oeil_gauche", (-8.5, 37.5, -49.4), (4.4, 3, 1.0), (0, 0, 0), "eye_l")
    hd("Head", "sourcil_droit", (8.6, 40.0, -50.0), (7.5, 2.4, 3.2), (-8, 0, 14), "brow")
    hd("Head", "sourcil_gauche", (-8.6, 40.2, -50.0), (7.5, 2.4, 3.2), (-8, 0, -11), "brow")
    hd("Head", "oreille_droite", (11.5, 44.8, -32), (5, 4.5, 3.5), (-10, 20, -18), "ear")
    hd("Head", "oreille_gauche", (-11.5, 44.6, -32.5), (5, 4.5, 3.5), (-6, -14, 22), "ear")
    for s, side in ((1, "droite"), (-1, "gauche")):
        hd("Head", f"moustache_{side}_haut", (13.5 * s, 27.5, -54), (9, 0.6, 0.6), (0, -18 * s, 9 * s), "whisker")
        hd("Head", f"moustache_{side}_bas", (13 * s, 25.0, -53), (8, 0.6, 0.6), (0, -11 * s, -6 * s), "whisker")
    hd("Jaw", "machoire", (0, 20, -48), (14, 5, 13), (-6, 0, 0), "belly")

    # ---------------------------------------------------------------- queue : épaisse à la base, large et plate au bout
    for g, name, c, size, r in tail_chain():
        tl(g, name, c, size, r, "tail")
    tl("Tail", "pierre_queue", (3.5, 18.4, 27.5), (6, 4, 5), (22, 18, 6), "stone")

    # ---------------------------------------------------------------- pattes (dessinées droites, puis inclinées)
    for s, side in ((1, "right"), (-1, "left")):
        fg, bg = f"Leg_front_{side}", f"Leg_back_{side}"
        # avant : plus courtes et plus fines, bras penché vers l'arrière, main large posée en avant
        leg = attach(*LEGS[fg])
        for name, c, size, r, mat in (
                ("bras", (18 * s, 15, -13), (11, 15, 13), (-10, 0, 0), "fur"),
                ("main", (18.8 * s, 4, -17), (12.5, 7, 13), (0, -9 * s, 0), "paw")):
            cc, M = leg(c, r)
            add(fg, name, cc, size, M, mat)
        # arrière : grosse cuisse qui prolonge les hanches, jarret replié, grand pied palmé
        leg = attach(*LEGS[bg])
        for name, c, size, r, mat in (
                ("cuisse", (22 * s, 21, 12.5), (15, 24, 24), (-18, 0, 7 * s), "fur"),
                ("jarret", (22.8 * s, 8, 19), (10, 11, 10), (22, 0, 0), "fur_dark"),
                ("pied", (22.8 * s, 2.2, 12), (14.5, 4.4, 22), (0, -8 * s, 0), "foot")):
            cc, M = leg(c, r)
            add(bg, name, cc, size, M, mat)

    # chaque patte posée exactement au sol (y = 0) après inclinaison
    low = {}
    for g, n, c, size, r, m in P:
        if g.startswith("Leg_"):
            low[g] = min([low.get(g, 1e9)] + [q[1] for q in corners(c, size, r)])
    return [(g, n, [c[0], c[1] - low.get(g, 0), c[2]], size, r, m) for g, n, c, size, r, m in P]


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(parts(), GROUPS, scale=SCALE)
    uv, atlases, sizes = bake(P, MATS, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # aucune pièce lumineuse : pas de texture émissive
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; texture {sizes[0]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if prev:
        apercu(data, atlases, sizes, prev, set(), views=((35, 12), (0, 6), (90, 3), (215, 14)), size=420)
