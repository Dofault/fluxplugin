#!/usr/bin/env python3
"""Loutre des Marées : boss côtier, modèle Blockbench (.bbmodel) STATIQUE (2026-11-10).

Vraie loutre de mer, pas un ours ni un castor : corps long et fuselé, tronc fin, dos doucement voûté, cou peu marqué,
petite tête ronde, museau court et clair, petites oreilles rondes, pattes courtes et fines, grands pieds arrière
palmés, queue longue, épaisse à la base et effilée. Pose : elle sort de l'eau et observe devant elle, sur ses quatre
pattes, tête un peu relevée et tournée, queue qui retombe et s'incurve vers la droite.

Le fantastique reste discret : pelage bleu océan, marques turquoise en forme de vagues peintes sur les flancs, les
épaules et le dessus de la queue, trois toutes petites crêtes sur le dos. Pelage, ventre, palmures, griffes et marques
sont peints (1 texel par unité, comme un bloc 16 x 16), jamais construits en petits cubes.

Pose cuite dans les cubes, os rangés aux articulations pour une animation future ; pas d'animation ni de lumière.
SCALE règle la taille globale d'un coup (les tailles de chaque partie suivent le brief en unités de dessin).

Usage : loutre_marees.py [dossier de sortie] [--apercu <planche.png>]
Face avant au nord (-z), côté droit du monstre en +x, sol en y = 0, centré en x et z sur sa boîte englobante.
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, corners, export, ground, hx, tone  # noqa: E402
from golem_fonderie import rot  # noqa: E402

IDENT = "loutre_marees"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000010a7e0")
SCALE = 0.78          # 16 unités = 1 bloc après réduction ; seul réglage de taille globale


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palette du brief (sombre → clair)
FUR = pal("#142F42", "#1D4356", "#285B70", "#39788A", "#579EAA")
BELLY = pal("#526F78", "#75949A", "#9BB6B5")
MARK = pal("#287E83", "#43B7B0", "#77D9CB")
IRIS, PUPIL, GLINT = hx("#174F65"), hx("#101B25"), hx("#8BCBCB")
CLAW, CLAW_SHADE = hx("#263B43"), hx("#18272F")

SIDE = {"up": 0, "north": 0, "east": 0, "west": 0, "south": -1, "down": -1}   # lumière du haut, un peu de face

# Vague turquoise : petit motif pixelisé (crête de vague qui retombe), lu en (dx, dy, teinte).
WAVE = ((0, 2, 0), (1, 1, 1), (2, 0, 1), (3, 0, 2), (4, 1, 1), (5, 2, 1), (6, 2, 0), (7, 1, 0))
DOTS = ((0, 0, 1), (2, 1, 0))


def stamp(px, x0, y0, motif):
    h, w = len(px), len(px[0])
    for dx, dy, t in motif:
        x, y = x0 + dx, y0 + dy
        if 0 <= x < w and 0 <= y < h:
            px[y][x] = MARK[t]


# --------------------------------------------------------------------------- peintres
def fur(base=2, up=1, marks=0, belly_edge=False):
    def f(w, h, r, face):
        """Pelage : aplat d'un ton, quelques pixels plus sombres, dessus plus clair (reflet du dos), bas des côtés
        ombré ; `belly_edge` fond le bas des côtés dans la couleur du ventre (pas de séparation nette) ; `marks`
        pose au plus ce nombre de vagues turquoise par côté."""
        b = base + SIDE[face] + (up if face == "up" else 0)
        px = [[tone(FUR, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 30)):
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = tone(FUR, b - 1)
            if y + 1 < h and r.random() < 0.4:
                px[y + 1][x] = tone(FUR, b - 1)
        if face == "up":
            for _ in range(max(1, w * h // 40)):
                px[r.randrange(h)][r.randrange(w)] = tone(FUR, b + 1)
        if face in ("east", "west", "north", "south") and h >= 5:
            if belly_edge:
                px[h - 1] = [BELLY[0]] * w
                px[h - 2] = [BELLY[0] if r.random() < 0.45 else tone(FUR, b - 1) for x in range(w)]
                px[h - 3] = [tone(FUR, b - 1) if r.random() < 0.3 else px[h - 3][x] for x in range(w)]
            else:
                px[h - 1] = [tone(FUR, b - 1)] * w
            for x in range(w):                       # arête du haut accrochée par la lumière
                if r.random() < 0.3:
                    px[0][x] = tone(FUR, b + 1)
        if marks and face in ("east", "west") and w >= 10 and h >= 7:
            for k in range(marks):
                x0 = int((k + 0.5) * w / marks) - 4 + r.randrange(-1, 2)
                y0 = 1 + r.randrange(max(1, h // 2 - 2))
                stamp(px, x0, y0, WAVE)
                stamp(px, x0 + 2, y0 + 4, DOTS)
        return px
    return f


def back(w, h, r, face):
    """Ligne du dos : bleu ardoise plus clair que les flancs, une ondulation turquoise très fine au milieu."""
    px = fur(3, up=0)(w, h, r, face)
    if face == "up" and w >= 5 and h >= 10:
        c = w // 2
        for y in range(2, h - 2):
            if y % 6 in (0, 1):
                px[y][c + (1 if y % 12 < 6 else -1)] = MARK[0]
    return px


def belly(base=1, up=1):
    def f(w, h, r, face):
        """Ventre, poitrail, museau : gris bleuté désaturé, plus clair que le corps sans jamais tirer sur le blanc."""
        b = base + SIDE[face] + (up if face == "up" else 0)
        px = [[tone(BELLY, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 16)):
            x, y = r.randrange(w), r.randrange(h)
            px[y][x] = tone(BELLY, b - 1 if r.random() < 0.7 else b + 1)
        if face not in ("up", "down") and h >= 4:
            px[h - 1] = [tone(BELLY, b - 1)] * w
        return px
    return f


def cheek(w, h, r, face):
    """Joue : le haut en pelage, le bas qui s'éclaircit vers le museau (tache claire de la loutre de mer)."""
    px = fur(3, up=0)(w, h, r, face)
    if face in ("north", "east", "west", "down"):
        for y in range(h):
            for x in range(w):
                if face == "down" or y >= h * 0.55 or (y >= h * 0.35 and (x + y) % 2 == 0):
                    px[y][x] = BELLY[1] if face != "down" else BELLY[0]
    return px


def paw(webbed=False, toes=4):
    def f(w, h, r, face):
        """Pied : bleu nuit, orteils un ton plus clair, petites griffes sombres au bout ; pied palmé : la palmure
        reste plus sombre entre les orteils (dessus)."""
        b = 1 + SIDE[face]
        px = [[tone(FUR, b)] * w for _ in range(h)]
        cols = [round((i + 0.5) * w / toes - 0.5) for i in range(toes)]
        if face == "north":
            for c in cols:
                px[h - 1][c] = CLAW
                if h >= 2:
                    px[h - 2][c] = tone(FUR, 2)
        elif face == "up":
            for y in range(h):
                for x in range(w):
                    if x in cols:
                        px[y][x] = tone(FUR, 2)
                    elif webbed and y < h - 2:
                        px[y][x] = tone(FUR, 0) if (y % 3) else MARK[0] if r.random() < 0.15 else tone(FUR, 0)
            for c in cols:
                px[0][c] = CLAW
        elif face not in ("down", "south"):
            px[h - 1] = [tone(FUR, 0)] * w
        return px
    return f


def tail(base=2, marks=True):
    def f(w, h, r, face):
        """Queue : même pelage, quelques bandes plus sombres en travers (pas d'écailles), une vague turquoise sur le
        dessus des segments assez longs."""
        b = base + SIDE[face] + (1 if face == "up" else 0)
        px = [[tone(FUR, b)] * w for _ in range(h)]
        for _ in range(max(1, w * h // 28)):
            px[r.randrange(h)][r.randrange(w)] = tone(FUR, b - 1)
        if face in ("east", "west", "up", "down"):
            # bandes en travers de la queue (axe de la queue = horizontale des côtés, verticale du dessus)
            if face == "up":
                for y in range(1, h, 5):
                    for x in range(w):
                        if r.random() < 0.6:
                            px[y][x] = tone(FUR, b - 1)
                if marks and h >= 8 and w >= 7:
                    c = w // 2 - 1
                    for k, (dx, dy) in enumerate(((0, 0), (1, 1), (1, 2), (2, 3), (1, 4), (0, 5))):
                        if dy + 2 < h:
                            px[dy + 2][c + dx] = MARK[1 if k in (2, 3) else 0]
            else:
                for x in range(2, w, 5):
                    for y in range(h):
                        if r.random() < 0.6:
                            px[y][x] = tone(FUR, b - 1)
                if h >= 3:
                    px[h - 1] = [tone(FUR, b - 1)] * w
        return px
    return f


def crest(w, h, r, face):
    """Petite crête du dos : turquoise sombre, arête claire, pas une épine."""
    px = [[MARK[0]] * w for _ in range(h)]
    if face in ("east", "west", "north", "south"):
        px[0] = [MARK[1]] * w
    if face == "up":
        px = [[MARK[1]] * w for _ in range(h)]
    return px


def eye_painter(inner_right):
    def f(w, h, r, face):
        """Œil furieux (densité 2) : paupière du haut en biais qui descend vers le nez (sous le sourcil froncé), iris
        bleu pétrole qui fixe droit devant, pupille fendue, un pixel de reflet ; peu de blanc, seulement sur les côtés
        de l'iris. `inner_right` : le coin intérieur (côté nez) est à droite de la texture."""
        if face != "north":
            return [[FUR[0]] * w for _ in range(h)]
        white = BELLY[1]
        px = [[white] * w for _ in range(h)]
        for y in range(h):
            px[y][0] = px[y][w - 1] = FUR[0]
        px[h - 1] = [FUR[0]] * w                          # paupière du bas, sombre
        for x in range(w):
            t = x / (w - 1) if inner_right else 1 - x / (w - 1)
            lid = 1 + round(3.2 * t)                      # 1 rang côté tempe, 4 côté nez : regard plissé
            for y in range(min(lid, h - 2)):
                px[y][x] = FUR[0]
            if lid < h - 1:
                px[lid][x] = FUR[1]                       # ombre de la paupière sur l'œil
        cx = w // 2 - 1
        for y in range(2, h - 1):
            for x in range(cx, cx + 3):
                if px[y][x] in (white, FUR[1]):
                    px[y][x] = IRIS
        for y in range(3, h - 1):
            if px[y][cx + 1] == IRIS:
                px[y][cx + 1] = PUPIL                     # pupille fendue verticale
        gx = cx + 2 if inner_right else cx
        if px[3][gx] == IRIS:
            px[3][gx] = GLINT
        px[h - 2][cx + (0 if inner_right else 2)] = MARK[0]
        return px
    return f


def brow(w, h, r, face):
    """Sourcil froncé : bourrelet de pelage presque noir, arête du dessus un ton plus claire."""
    px = [[FUR[0]] * w for _ in range(h)]
    if face == "up":
        px = [[FUR[1]] * w for _ in range(h)]
    elif face == "north":
        px[0] = [FUR[1]] * w
    return px


def fang(w, h, r, face):
    """Croc : gris bleu très clair (le plus clair de la palette, jamais blanc pur), pointe un ton plus sombre."""
    px = [[BELLY[2]] * w for _ in range(h)]
    px[h - 1] = [BELLY[1]] * w
    return px


def toe(w, h, r, face):
    """Doigt (densité 2) : pelage sombre, dessus un ton plus clair, griffe gris bleu sombre au bout."""
    b = {"up": 2, "down": 0}.get(face, 1)
    px = [[FUR[b]] * w for _ in range(h)]
    if face == "north":
        px[h - 1] = [CLAW] * w
        if h >= 3:
            px[h - 2][w // 2] = CLAW
    elif face == "up":
        px[0] = [CLAW] * w                                # la griffe se voit aussi du dessus
        if h >= 3:
            px[1][w // 2] = CLAW_SHADE
    elif face == "down":
        px = [[CLAW_SHADE] * w for _ in range(h)]         # coussinet
    elif face in ("east", "west"):
        px[h - 1] = [FUR[0]] * w
        px[h - 1][0] = CLAW                               # bout de griffe vu de profil (u = 0 côté avant)
    return px


def palm(w, h, r, face):
    """Paume : dessus en pelage avec les jointures des doigts plus claires vers l'avant, coussinets sombres dessous."""
    b = {"up": 2, "down": 0}.get(face, 1)
    px = [[FUR[b]] * w for _ in range(h)]
    if face == "up":
        for _ in range(max(1, w * h // 10)):
            px[r.randrange(h)][r.randrange(w)] = FUR[1]
        for x in range(1, w - 1, 3):
            px[0][x] = FUR[3]
    elif face == "down":
        px = [[CLAW_SHADE] * w for _ in range(h)]
        for x in range(1, w - 1, 3):
            for y in range(1, max(2, h - 1)):
                px[y][x] = CLAW
    elif face != "south":
        px[h - 1] = [FUR[0]] * w
    return px


def web(w, h, r, face):
    """Palmure : peau fine bleu nuit entre les doigts, un reflet turquoise de loin en loin."""
    px = [[FUR[0]] * w for _ in range(h)]
    if face == "up":
        for _ in range(max(1, w * h // 18)):
            px[r.randrange(h)][r.randrange(w)] = MARK[0]
    return px


def nose(w, h, r, face):
    """Truffe : gris bleu sombre, narines plus sombres, arête du haut un ton plus clair."""
    px = [[CLAW] * w for _ in range(h)]
    if face == "north":
        for x in (1, w - 2):
            px[h - 2][x] = CLAW_SHADE
        px[0] = [tone(FUR, 1)] * w
    elif face in ("down", "south"):
        px = [[CLAW_SHADE] * w for _ in range(h)]
    return px


def ear(w, h, r, face):
    px = [[FUR[2 if face == "up" else 1]] * w for _ in range(h)]
    if face == "north" and w >= 3 and h >= 3:
        for y in range(1, h):
            for x in range(1, w - 1):
                px[y][x] = FUR[0]
    return px


def whisker(w, h, r, face):
    return [[BELLY[2] if x < w - 2 else BELLY[1] for x in range(w)] for _ in range(h)]


MATS = {
    "fur": fur(2), "fur_dark": fur(1), "flank": fur(2, marks=1, belly_edge=True), "shoulder": fur(2, marks=1),
    "hips": fur(2, belly_edge=True), "back": back, "head": fur(2, up=1), "neck": fur(2),
    "cheek": cheek, "muzzle": belly(1, up=1), "belly": belly(1, up=0), "chest": belly(0, up=1),
    "toe": (toe, 2), "palm": palm, "web": web, "brow": brow, "tail": tail(2), "tail_end": tail(1, marks=False),
    "crest": crest, "eye_r": (eye_painter(True), 2), "eye_l": (eye_painter(False), 2), "fang": fang, "nose": (nose, 2), "ear": ear, "whisker": whisker,
}


# --------------------------------------------------------------------------- squelette (unités de dessin)
# Os pensés pour l'animation (cf. loutre_marees_anim.py) : le corps se plie au milieu (Chest / Hips) pour onduler, les
# pattes sont des enfants du corps (elles le suivent quand il se cabre, plonge ou nage) et se plient au coude ou au
# genou, la queue a un os par segment, les moustaches ont le leur. Body est le centre de gravité (pivot au milieu).
NECK = (0, 30, -22.5)
SPINE = (0, 26, 6)                   # articulation du milieu du dos : pivot de Body, Chest et Hips
HEAD_R = rot(14, -11, -3)            # tête un peu relevée, tournée vers sa gauche : elle observe quelque chose
TAIL_ROOT = (1.4, 21.5, 31)
BODY_DROP = 6                      # corps dessiné plus haut que sa place : voir la fin de parts()
TAIL_R = rot(0, 32, 0)             # la queue s'incurve vers la droite : visible de trois quarts
TIP_R = rot(0, 34, 3)
SHOULDERS = {"right": (10.5, 12, -13), "left": (-10.5, 12, -13)}
HIPS = {"right": (11.5, 12, 22), "left": (-10.5, 12, 22)}
# Pattes : (épaule ou hanche, inclinaison de toute la patte) ; légèrement écartées, jamais deux pareilles.
LEGS = {
    "Leg_front_right": (SHOULDERS["right"], rot(6, 0, 10)),
    "Leg_front_left": (SHOULDERS["left"], rot(-4, 0, -9)),      # patte gauche un peu avancée
    "Leg_back_right": (HIPS["right"], rot(-3, 0, 6)),
    "Leg_back_left": (HIPS["left"], rot(2, 0, -7)),
}

# Queue : (os, nom, taille, inclinaison vers le bas en degrés, matériau) de la base au bout ; chaque segment part du
# dessus du précédent (recouvrement de 3 unités) : le dessus forme une pente continue. Base 14 de large, bout 7.
TAIL_SEGMENTS = (
    ("Tail_1", "queue_base", (14, 11, 12), 30, "tail"),
    ("Tail_2", "queue_milieu", (11.5, 9, 11), 20, "tail"),
    ("Tail_3", "queue_fin", (9, 7, 10), 9, "tail"),
    ("Tail_4", "queue_bout", (7, 5, 7), 3, "tail_end"),
)
TAIL_START = (1.4, 21.0, 33)    # centre du premier segment, sous l'arrière des hanches
TIP_FROM = "Tail_3"             # à partir de ce segment, la queue s'incurve encore (TIP_R)


def tail_chain(overlap=3.0):
    out, prev = [], None
    for g, name, size, ax, mat in TAIL_SEGMENTS:
        h, d = size[1], size[2]
        a = math.radians(ax)
        if prev is None:
            cy, cz = TAIL_START[1], TAIL_START[2]
        else:
            py, pz, ph, pa, pd = prev
            # arrière du dessus du segment précédent
            ry, rz = py + ph / 2 * math.cos(pa) - pd / 2 * math.sin(pa), pz + ph / 2 * math.sin(pa) + pd / 2 * math.cos(pa)
            fz = rz - overlap
            fy = ry + (rz - fz) * math.tan(pa) + 0.2
            oy, oz = h / 2 * math.cos(a) + d / 2 * math.sin(a), h / 2 * math.sin(a) - d / 2 * math.cos(a)
            cy, cz = fy - oy, fz - oz
        out.append((g, name, (TAIL_START[0], cy, cz), size, (ax, 0, 0), mat))
        prev = (cy, cz, h, a, d)
    return out


def local(c, M, v):
    """Point v du repère d'une pièce (centre c, matrice M) dans le repère du modèle."""
    from golem_fonderie import app
    q = app(M, v)
    return [c[i] + q[i] for i in range(3)]


def parts():
    """Pièces et os (nom : (pivot, parent)), en unités de dessin, le modèle posé au sol."""
    P = []
    G = {"Root": ((0, 0, 0), None), "Body": (SPINE, "Root"), "Chest": (SPINE, "Body"), "Hips": (SPINE, "Body"),
         "Head": (NECK, "Chest")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, c, size, r, mat))

    on_head = attach(NECK, HEAD_R)
    on_tail = attach(TAIL_ROOT, TAIL_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    # ---------------------------------------------------------------- corps : épaules trapues, tronc long et souple
    # Épaules larges et hautes (bosse du garrot : le côté boss), puis un tronc plus étroit en deux segments qui
    # s'arquent un peu au milieu et s'incurvent vers la droite, hanches fines en pente vers la queue. Ventre et ligne
    # du dos coupés en deux à l'articulation du milieu (SPINE) pour que le corps puisse se plier.
    add("Chest", "epaules", (0, 26.0, -13.5), (25, 20, 14), (-8, 0, 0), "shoulder")
    add("Chest", "garrot", (0, 35.6, -11.5), (18, 3.5, 11), (-6, 0, 0), "back")
    add("Chest", "cou", (0, 30.0, -22.0), (17, 16, 9), (18, 0, 0), "neck")
    add("Chest", "poitrail", (0, 21.0, -19.8), (11, 8, 4), (-16, 0, 0), "chest")
    add("Chest", "tronc_avant", (0, 26.0, 0), (21, 17, 15), (2, 0, 0), "flank")
    add("Chest", "ventre_avant", (0.1, 16.5, -4.5), (16, 4, 22), (0, 0, 0), "belly")
    add("Chest", "dos_avant", (0.2, 33.3, -4.0), (13, 2.5, 18.5), (0, 0, 0), "back")
    add("Hips", "tronc_arriere", (0.5, 26.5, 13.5), (19.5, 16.5, 15), (-3, 3, 0), "flank")
    add("Hips", "hanches", (1.1, 24.5, 25.5), (19, 15.5, 12), (10, 6, 0), "hips")
    add("Hips", "ventre_arriere", (0.6, 16.5, 16.5), (16, 4, 22), (0, 3, 0), "belly")
    add("Hips", "dos_arriere", (0.7, 33.3, 14.0), (13, 2.5, 18.5), (0, 2, 0), "back")
    # trois toutes petites crêtes qui suivent la courbe du dos (vers l'arrière), pas une rangée d'épines
    for i, (z, hgt) in enumerate(((-2, 1.8), (6.5, 2.3), (15, 1.6))):
        add("Chest" if z < SPINE[2] else "Hips", f"crete_{i + 1}", (0.15 * z / 5, 34.4 + hgt / 2, z),
            (1.0, hgt, 3.5), (28, 0, 0), "crest")

    # ---------------------------------------------------------------- tête : petite, ronde, museau court
    hd("Head", "crane", (0, 36.0, -31.5), (18, 14, 16), (0, 0, 0), "head")
    hd("Head", "front", (0, 43.8, -31.0), (13, 1.8, 12), (0, 0, 0), "head")          # arrondit le haut du crâne
    hd("Head", "joue_droite", (8.6, 32.5, -35.0), (5, 9, 10), (0, 10, 0), "cheek")
    hd("Head", "joue_gauche", (-8.6, 32.5, -35.0), (5, 9, 10), (0, -10, 0), "cheek")
    hd("Head", "museau", (0, 31.2, -41.5), (12, 7, 6), (-4, 0, 0), "muzzle")       # large à la base
    hd("Head", "museau_bout", (0, 31.4, -45.0), (8, 5.5, 3.5), (-4, 0, 0), "muzzle")    # plus étroit vers le nez
    hd("Head", "truffe", (0, 33.6, -46.9), (5, 2.8, 1.6), (-6, 0, 0), "nose")
    G["Jaw"] = (on_head((0, 29.5, -37.0), (0, 0, 0))[0], "Head")      # charnière : arrière haut de la mâchoire
    jc, jm = attach((0, 29.5, -37.0), rot(-10, 0, 0))((0, 27.6, -40.5), (4, 0, 0))   # gueule entrouverte
    hd("Jaw", "machoire", jc, (9, 2.6, 7), jm, "belly")
    # yeux plissés sous des sourcils froncés en V : bout intérieur qui plonge vers le nez, bout extérieur relevé.
    # Colonne 0 de la face avant = côté +x : le coin intérieur de l'œil droit est à droite de sa texture.
    hd("Head", "oeil_droit", (5.0, 38.6, -39.7), (4.4, 4.0, 0.8), (0, 0, 0), "eye_r")
    hd("Head", "oeil_gauche", (-5.0, 38.6, -39.7), (4.4, 4.0, 0.8), (0, 0, 0), "eye_l")
    hd("Head", "sourcil_droit", (4.8, 41.0, -40.1), (6.2, 1.7, 2.4), (-14, 0, 27), "brow")
    hd("Head", "sourcil_gauche", (-4.8, 41.0, -40.1), (6.2, 1.7, 2.4), (-14, 0, -27), "brow")
    # deux petits crocs sous la lèvre, visibles gueule entrouverte
    hd("Head", "croc_droit", (2.6, 27.4, -46.0), (1.0, 2.2, 1.0), (0, 0, 0), "fang")
    hd("Head", "croc_gauche", (-2.6, 27.4, -46.0), (1.0, 2.2, 1.0), (0, 0, 0), "fang")
    hd("Head", "oreille_droite", (8.0, 42.6, -28.5), (3.4, 2.8, 2.4), (0, -20, -18), "ear")
    hd("Head", "oreille_gauche", (-8.0, 42.6, -28.5), (3.4, 2.8, 2.4), (0, 20, 18), "ear")
    for s, side, bone in ((1, "droite", "Whiskers_right"), (-1, "gauche", "Whiskers_left")):
        G[bone] = (on_head((5.0 * s, 31.5, -44.0), (0, 0, 0))[0], "Head")    # racine des moustaches, sur le museau
        hd(bone, f"moustache_{side}_haut", (10.2 * s, 32.0, -43.5), (8, 0.5, 0.5), (0, 16 * s, 8 * s), "whisker")
        hd(bone, f"moustache_{side}_bas", (9.8 * s, 30.2, -43.0), (7, 0.5, 0.5), (0, 10 * s, -6 * s), "whisker")

    # ---------------------------------------------------------------- queue : un os par segment, pivot à l'avant
    chain = tail_chain()
    tip = next(q for q in chain if q[0] == TIP_FROM)
    on_tip = attach((tip[2][0], tip[2][1], tip[2][2] - tip[3][2] / 2), TIP_R)
    parent = "Hips"
    for g, name, c, size, r, mat in chain:
        bent = [k for k, q in enumerate(chain) if q[0] == TIP_FROM][0] <= [k for k, q in enumerate(chain) if q[0] == g][0]
        if bent:
            c, r = on_tip(c, r)
        cc, M = on_tail(c, r)
        add(g, name, cc, size, M, mat)
        G[g] = (local(cc, M, (0, 0, -size[2] / 2 + 1.0)), parent)     # avant du segment, 1 unité à l'intérieur
        parent = g

    # ---------------------------------------------------------------- pattes : courtes, doigts séparés et palmés
    def paw(prefix, c, size, yaw, toes, toe_size, fan, web_len):
        """Main ou pied : paume, doigts en éventail qui partent de son bord avant, palmure basse tendue entre eux ;
        le tout tourné de `yaw` autour du centre de la paume (dessiné dans le repère de la patte)."""
        bottom, front = c[1] - size[1] / 2, c[2] - size[2] / 2
        tw, th, tl = toe_size
        span = size[0] - tw
        out = [(f"{prefix}_paume", c, size, (0, 0, 0), "palm")]
        for k in range(toes):
            t = k / (toes - 1) - 0.5                       # -0,5 (côté -x) à 0,5 (côté +x)
            base = (c[0] + t * span, bottom + th / 2, front + 0.8)
            ang = -t * fan * 2                             # les doigts s'écartent vers l'extérieur de la main
            curl = -(0.6 if abs(t) < 0.3 else 0.0)        # doigts du milieu un peu plus longs
            cc, M = attach(base, rot(0, ang, 0))((base[0], base[1], base[2] - (tl - curl) / 2), (0, 0, 0))
            out.append((f"{prefix}_doigt_{k + 1}", cc, (tw, th, tl - curl), M, "toe"))
        out.append((f"{prefix}_palmure", (c[0], bottom + 0.35, front - web_len / 2 + 0.6),
                    (span + tw * 0.6, 0.7, web_len), (0, 0, 0), "web"))
        foot = attach(c, rot(0, yaw, 0))
        placed = []
        for n, cc, sz, r, m in out:
            pc, M = foot(cc, r)
            placed.append((n, pc, sz, M, m))
        return placed

    legs = {}            # patte : (os du haut, os du bas)
    for s, side in ((1, "right"), (-1, "left")):
        # avant : bras musclé (épaules trapues), avant-bras fin, petite main à quatre doigts tournée vers l'intérieur
        fg, fl = f"Leg_front_{side}", f"Paw_front_{side}"
        front_leg = [
            (fg, "bras", (11.8 * s, 9.6, -13), (7, 7, 8), (-14, 0, 0), "fur"),
            (fl, "avant_bras", (12.2 * s, 4.6, -14.6), (5, 5.5, 5), (14, 0, 0), "fur_dark"),
        ] + [(fl, *q) for q in paw("main", (12.4 * s, 1.1, -16.6), (6.4, 2.2, 4.5), 8 * s, 4, (1.5, 1.8, 3.8), 13, 2.4)]
        # arrière : cuisse, jarret, grand pied à cinq longs doigts bien palmés, tourné vers l'extérieur
        bg, bl = f"Leg_back_{side}", f"Foot_back_{side}"
        back_leg = [
            (bg, "cuisse", (12 * s, 10.0, 21.5), (8.5, 9, 11), (20, 0, 0), "fur"),
            (bl, "jarret", (12.6 * s, 4.6, 23.0), (5.4, 6, 5.4), (-24, 0, 0), "fur_dark"),
        ] + [(bl, *q) for q in paw("pied", (13 * s, 1.1, 21.0), (8, 2.2, 7), -12 * s, 5, (1.6, 2.0, 5.6), 11, 4.2)]
        for up, low, pieces, parent in ((fg, fl, front_leg, "Chest"), (bg, bl, back_leg, "Hips")):
            place = attach(*LEGS[up])
            for g, name, c, size, r, mat in pieces:
                cc, M = place(c, r)
                add(g, name, cc, size, M, mat)
                if name in ("avant_bras", "jarret"):                 # coude ou genou : haut de ce volume
                    G[low] = (local(cc, M, (0, size[1] / 2 - 0.5, 0)), up)
            G[up] = (LEGS[up][0], parent)
            legs[up] = (up, low)

    # corps, tête et queue dessinés BODY_DROP unités plus haut : on les descend d'un coup (loutre basse sur pattes)
    leg_bones = {b for pair in legs.values() for b in pair}
    P = [(g, n, c if g in leg_bones else [c[0], c[1] - BODY_DROP, c[2]], size, r, m) for g, n, c, size, r, m in P]
    G = {k: ((pv[0], pv[1] - (0 if k in leg_bones or k == "Root" else BODY_DROP), pv[2]), par)
         for k, (pv, par) in G.items()}

    # chaque patte (ses deux os) posée exactement au sol (y = 0) après inclinaison
    shift = {}
    for up, pair in legs.items():
        low = min(q[1] for g, n, c, size, r, m in P if g in pair for q in corners(c, size, r))
        for b in pair:
            shift[b] = low
    P = [(g, n, [c[0], c[1] - shift.get(g, 0), c[2]], size, r, m) for g, n, c, size, r, m in P]
    G = {k: ((pv[0], pv[1] - shift.get(k, 0), pv[2]), par) for k, (pv, par) in G.items()}
    # ordre d'écriture : chaque parent avant ses enfants
    order, done = [], set()
    while len(order) < len(G):
        for k, (pv, par) in G.items():
            if k not in done and (par is None or par in done):
                order.append(k)
                done.add(k)
    return P, {k: G[k] for k in order}


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = anims = None
    show = "--mesures" in args
    if show:
        args.remove("--mesures")
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    if "--anims" in args:
        i = args.index("--anims")
        anims = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(*parts(), scale=SCALE)
    uv, atlases, sizes = bake(P, MATS, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # aucune pièce lumineuse : pas de texture émissive
    import loutre_marees_anim
    data["animations"] = loutre_marees_anim.build(lambda name: str(uuid.uuid5(NS, f"{IDENT}/{name}")))
    loutre_marees_anim.ground_lock(data)
    print(f"{len(P)} volumes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; texture {sizes[0]}")
    # Variantes jouées en jeu : BetterModel ne joue seul que idle et walk, FluxDungeons joue death à la mort.
    # loutre_marees (phase terrestre) : toutes les animations + death = death_land ; loutre_marees_eau (phase
    # aquatique) : idle = walk = swim, death = death_water, plus attack_wave et ultimate_breach.
    def alias(anims, pairs, keep=None):
        out = [a for a in anims if keep is None or a["name"] in keep]
        for new, old in pairs:
            src = next(a for a in anims if a["name"] == old)
            copy = json.loads(json.dumps(src))
            copy["name"] = new
            copy["uuid"] = str(uuid.uuid5(NS, f"{IDENT}/alias/{new}/{old}"))
            out = [a for a in out if a["name"] != new] + [copy]
        return out
    variants = {
        IDENT: alias(data["animations"], [("death", "death_land")]),
        # dans l'eau elle reste à l'endroit (idle et walk = swim) : sur le dos, on la croyait à l'envers en nageant
        IDENT + "_eau": alias(data["animations"], [("idle", "swim"), ("walk", "swim"), ("death", "death_water")],
                              keep={"attack_wave", "attack_spin", "attack_spit", "ultimate_breach"}),
    }
    full = data["animations"]
    for name, played in variants.items():
        v = dict(data, animations=played, name=name, model_identifier=name)
        path = os.path.join(out, name + ".bbmodel")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, ensure_ascii=False)
        print("écrit", path, "(" + ", ".join(a["name"] for a in played) + ")")
    data["animations"] = full
    if prev:
        apercu(data, atlases, sizes, prev, set(), views=((35, 12), (0, 6), (90, 3), (215, 14)), size=420)
    if anims:
        from colosse_abime import strips
        strips(data, atlases, sizes, anims, count=10)
    if show:
        for name, rows in loutre_marees_anim.mesures(data).items():
            print(f"--- {name}")
            for r in rows:
                print(f"  tick {r['tick']:3d}  bas {r['low']:6.2f}  haut {r['high']:5.2f}  tête {r['head_low']:6.2f}/{r['head_high']:5.2f}"
                      f"  museau {r['nose']}  queue {r['tail_tip']}")
