#!/usr/bin/env python3
"""Rapace-Liane : boss du donjon Herboris, modèle Blockbench (.bbmodel) STATIQUE (2026-11-06).

Créature végétale à l'anatomie de rapace : corps de lianes torsadées, racines enroulées, plaques d'écorce,
feuilles épaisses à la place des plumes, bec de bois sombre et yeux ambre à pupille verticale. Bipède, ailes
à demi ouvertes (pose menaçante), queue de grandes feuilles en contrepoids. Aucune animation : les groupes ne
servent qu'à ranger les pièces, mais leurs pivots sont posés aux articulations (bassin, cuisses, tarses, pieds,
torse, cou, tête, épaules, avant-ailes, mains, queue) pour qu'ils deviennent les os le jour de l'animation.

Construction : tout est décrit en coordonnées monde (1 unité = 1/16 de bloc, sol y = 0, face avant au nord -z,
côté droit du monstre en +x). Une liane est une suite de cubes orientés le long d'une courbe ; une liane
torsadée est faite de 2 ou 3 brins qui s'enroulent autour de cette courbe. Textures : 1 texel par unité
(densité vanilla), sauf les yeux (2 texels par unité pour lire la pupille).

Usage : rapace_liane.py [dossier de sortie] [--apercu <planche.png>]
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
from golem_fonderie import euler, mul, app, rot  # noqa: E402

IDENT = "rapace_liane"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-0000000a11a5")
HEIGHT = 4.5    # hauteur visée en blocs (crête comprise)
I3 = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]


def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


# --------------------------------------------------------------------------- vecteurs
def add(*vs):
    return [sum(v[i] for v in vs) for i in range(3)]


def sub(a, b):
    return [a[i] - b[i] for i in range(3)]


def sc(a, k):
    return [a[i] * k for i in range(3)]


def dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def norm(a):
    n = math.sqrt(dot(a, a)) or 1.0
    return sc(a, 1 / n)


def lerp(a, b, t):
    return [a[i] + (b[i] - a[i]) * t for i in range(3)]


def dist(a, b):
    return math.sqrt(dot(sub(a, b), sub(a, b)))


def cols(x, y, z):
    return [[x[0], y[0], z[0]], [x[1], y[1], z[1]], [x[2], y[2], z[2]]]


def col(M, j):
    return [M[0][j], M[1][j], M[2][j]]


def frame(ydir, xhint=None, roll=0.0):
    """Repère direct dont l'axe local y suit ydir ; x au plus près de xhint ; roll autour de y (degrés)."""
    y = norm(ydir)
    if xhint is None:
        xhint = [1, 0, 0] if abs(y[0]) < 0.9 else [0, 0, 1]
    x = sub(xhint, sc(y, dot(xhint, y)))
    if dot(x, x) < 1e-6:
        x = [0, 0, 1] if abs(y[2]) < 0.9 else [1, 0, 0]
        x = sub(x, sc(y, dot(x, y)))
    x = norm(x)
    z = cross(x, y)
    if roll:
        c, s = math.cos(math.radians(roll)), math.sin(math.radians(roll))
        x, z = add(sc(x, c), sc(z, s)), add(sc(z, c), sc(x, -s))
    return cols(x, y, z)


def catmull(pts, n):
    """Courbe lisse passant par pts, n échantillons par tronçon."""
    pts = [list(p) for p in pts]
    if len(pts) == 2:
        return [lerp(pts[0], pts[1], j / n) for j in range(n + 1)]
    P = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
        for j in range(n):
            t = j / n
            out.append([0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t * t
                               + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t ** 3) for k in range(3)])
    out.append(pts[-1])
    return out


def interp(table, h):
    for (h0, v0), (h1, v1) in zip(table, table[1:]):
        if h <= h1:
            return v0 + (v1 - v0) * max(0.0, (h - h0) / (h1 - h0))
    return table[-1][1]


# --------------------------------------------------------------------------- scène
class Scene:
    def __init__(self):
        self.nodes, self.cubes = {}, []

    def node(self, name, pivot, parent=None):
        self.nodes[name] = {"pivot": list(pivot), "parent": parent}

    def box(self, node, name, c, size, mat, M=None, faces=None):
        self.cubes.append({"node": node, "name": name, "c": list(c), "size": [max(0.2, v) for v in size],
                           "M": M or I3, "mat": mat, "faces": faces or {}})

    def seg(self, node, name, a, b, w, mat, d=None, xhint=None, roll=0.0, over=0.5):
        self.box(node, name, lerp(a, b, 0.5), [w, dist(a, b) + over, d or w], mat, frame(sub(b, a), xhint, roll))

    def rope(self, node, name, pts, w0, w1, mat, n=3, xhint=None, over=0.6, flat=1.0):
        """Liane simple le long d'une courbe lisse, diamètre de w0 à w1 (flat < 1 : aplatie)."""
        path = catmull(pts, n) if n > 1 else pts
        m = len(path) - 1
        for i in range(m):
            w = w0 + (w1 - w0) * (i + 0.5) / m
            self.seg(node, f"{name}_{i}", path[i], path[i + 1], w, mat, w * flat, xhint, 0, over)

    def twist(self, node, name, pts, w0, w1, mats, spread=(1.5, 1.0), turns=0.8, n=4, xhint=None, phase=0.0):
        """Liane torsadée : un brin par matériau de mats, enroulés autour de la courbe comme un cordage."""
        path = catmull(pts, n)
        m = len(path)
        k_n = len(mats)
        for k, mat in enumerate(mats):
            offs = []
            for i, p in enumerate(path):
                t = i / (m - 1)
                F = frame(sub(path[min(i + 1, m - 1)], path[max(i - 1, 0)]), xhint)
                th = 2 * math.pi * (k / k_n + turns * t) + phase
                r = spread[0] + (spread[1] - spread[0]) * t
                offs.append(add(p, sc(col(F, 0), r * math.cos(th)), sc(col(F, 2), r * math.sin(th))))
            self.rope(node, f"{name}_{k}", offs, w0, w1, mat, n=1, xhint=xhint, over=0.7)

    def spiral(self, node, name, a, b, r, turns, w, mat, xhint=None, phase=0.0, steps=6, flat=0.8):
        """Racine fine enroulée autour du segment a → b (rayon r)."""
        F = frame(sub(b, a), xhint)
        x, y, z = col(F, 0), col(F, 1), col(F, 2)
        L = dist(a, b)
        N = max(3, int(turns * steps))
        pts = []
        for i in range(N + 1):
            t = i / N
            th = phase + 2 * math.pi * turns * t
            pts.append(add(a, sc(y, L * t), sc(x, r * math.cos(th)), sc(z, r * math.sin(th))))
        self.rope(node, name, pts, w, w * 0.8, mat, n=1, over=0.5, flat=flat)

    def leaf(self, node, name, base, dirv, length, width, mat, xhint, curl=8.0, thick=1.0):
        """Feuille épaisse en trois pièces qui s'affinent jusqu'à la pointe, légèrement incurvée."""
        F = frame(dirv, xhint)
        p = list(base)
        for i, (lf, wf, tf) in enumerate(((0.48, 1.0, 1.0), (0.32, 0.66, 0.85), (0.2, 0.3, 0.7))):
            Fi = mul(F, rot(curl * (i + 0.5), 0, 0))
            L = length * lf
            y = col(Fi, 1)
            self.box(node, f"{name}_{i}", add(p, sc(y, L / 2)), [width * wf, L + 0.4, thick * tf], mat, Fi)
            p = add(p, sc(y, L))

    def plate(self, node, name, center, M, size, mat, tilt=(0, 0, 0)):
        self.box(node, name, center, size, mat, mul(M, rot(*tilt)))


# --------------------------------------------------------------------------- matériaux
# Palette du brief (valeurs de référence, variations douces autour) : lianes vert forêt #315B32, lianes
# secondaires olive #526B35, racines #59402B, écorce #493124, feuilles #3F7737 / #668C42, nervures #8BA95A,
# bec #30251E, serres #251E19, iris #D69A32, pupille #17130F. Chaque rampe va de l'ombre à la lumière par petits
# écarts : aucune teinte isolée ne tranche sur sa matière.
LIANE = [hx(c) for c in ("#284B2A", "#315B32", "#376338", "#3E6B3D", "#41633A")]
LIANE_OLIVE = [hx(c) for c in ("#46592E", "#526B35", "#58723A", "#5F793F", "#4F6634")]
LIANE_SOMBRE = [hx(c) for c in ("#1F3A23", "#25432A", "#2A4A2E", "#305332", "#30502F")]
FIBRE = [hx(c) for c in ("#466F3E", "#4E7A44", "#548148", "#5B884C", "#527D45")]
RACINE = [hx(c) for c in ("#4A3524", "#59402B", "#61472F", "#694E35", "#523C29")]
ECORCE = [hx(c) for c in ("#38261B", "#493124", "#51372A", "#5A3F30", "#553027")]
BEC = {"bec_base": [hx(c) for c in ("#3D2F25", "#47372B", "#504032")],
       "bec": [hx(c) for c in ("#2A201A", "#30251E", "#372B23")],
       "serre": [hx(c) for c in ("#1F1915", "#251E19", "#2B231D")]}
FEUILLES = {"feuille_profonde": [hx(c) for c in ("#356B30", "#3F7737", "#46803D", "#80A256")],
            "feuille_mousse": [hx(c) for c in ("#5A7F3B", "#668C42", "#6E9447", "#8BA95A")],
            "feuille_foret": [hx(c) for c in ("#2E5A2C", "#356632", "#3C6F37", "#759A50")],
            "feuille_olive": [hx(c) for c in ("#4C6432", "#577038", "#5F793D", "#83A055")]}
CONTOUR = [hx(c) for c in ("#1C2719", "#222F1F", "#283624")]
AMBRE = [hx(c) for c in ("#A87428", "#BE872D", "#D69A32", "#DEA840", "#EDCB86")]
PUPILLE = hx("#17130F")


def bands(w, h, r, tones, run=(2, 5)):
    """Colonnes de fibres : chaque colonne change de ton par tronçons (jamais un pixel seul)."""
    px = [[tones[0]] * w for _ in range(h)]
    for x in range(w):
        y = 0
        while y < h:
            n = r.randint(*run)
            t = r.choice(tones)
            for yy in range(y, min(h, y + n)):
                px[yy][x] = t
            y += n
    return px


def paint_fibres(pal):
    """Stries longitudinales, sillons sombres entre les fibres, nœuds discrets (2 x 2, ton voisin)."""
    shade, base, mid, light, knot = pal

    def f(w, h, r, face):
        if face in ("up", "down"):                     # bout : grain de coupe, cerne plus sombre
            return [[shade if x in (0, w - 1) or y in (0, h - 1) else mid for x in range(w)] for y in range(h)]
        px = bands(w, h, r, (base, base, mid, mid, light))
        if w >= 3:
            for x in range(1 if w > 3 else 0, w, 3):   # sillons réguliers, légèrement interrompus
                for y in range(h):
                    if r.random() < 0.85:
                        px[y][x] = shade
        if h >= 6 and w >= 3 and r.random() < 0.5:
            x0, y0 = r.randrange(w - 1), r.randrange(h - 1)
            for yy in (y0, y0 + 1):
                for xx in (x0, x0 + 1):
                    px[yy][xx] = knot
        return px
    return f


def paint_ecorce(w, h, r, face):
    """Plaques superposées : chaque plaque a un ton, une arête haute claire et une ombre dessous ; fines fissures."""
    shade, base, mid, light, red = ECORCE
    if face in ("up", "down"):
        return [[mid if (x // 3 + y // 3) % 2 else base for x in range(w)] for y in range(h)]
    px = [[base] * w for _ in range(h)]
    y = 0
    while y < h:
        n = r.randint(3, 5)
        t = r.choice((base, base, mid, red))
        for yy in range(y, min(h, y + n)):
            px[yy] = [t] * w
        px[y] = [light] * w
        if y + n - 1 < h and n >= 3:
            px[y + n - 1] = [shade] * w
        y += n
    for _ in range(max(1, w // 4)):                    # fissures verticales de 2 à 3 texels
        x, y0 = r.randrange(w), r.randrange(h)
        for yy in range(y0, min(h, y0 + r.randint(2, 3))):
            px[yy][x] = shade
    return px


def paint_feuille(pal):
    """Grandes faces : nervure centrale, nervures secondaires en chevrons vers la pointe, bords un ton plus bas."""
    shade, base, light, vein = pal
    vein = tuple(int(b + (v - b) * 0.55) for b, v in zip(base, vein))

    def f(w, h, r, face):
        if face not in ("north", "south"):
            return [[shade] * w for _ in range(h)]
        px = [[base] * w for _ in range(h)]
        if w >= 4:
            for y in range(h):
                px[y][0] = px[y][w - 1] = shade
        c = w // 2
        if w >= 4:
            for y in range(h - 2, 0, -3):
                for d in range(1, c):
                    yy = y - d
                    if yy < 0:
                        break
                    for xx in (c - d, c + d):
                        if 0 < xx < w - 1:
                            px[yy][xx] = light if px[yy][xx] == base else shade
        for y in range(h):
            px[y][c] = vein
        return px
    return f


def paint_bec(pal):
    """Bois très dense : stries longitudinales serrées, arête haute un ton plus claire, rare fissure."""
    shade, base, light = pal

    def f(w, h, r, face):
        px = [[shade if x % 2 else base for x in range(w)] for _ in range(h)]
        if face not in ("up", "down"):
            px[0] = [light] * w
            if w * h >= 12 and r.random() < 0.5:
                x, y = r.randrange(w), r.randrange(max(1, h - 2))
                for k in range(min(3, h - y)):
                    px[y + k][x] = shade
        return px
    return f


def paint_contour(w, h, r, face):
    shade, base, light = CONTOUR
    return [[shade if y % 2 else base for _ in range(w)] for y in range(h)]


def paint_oeil(front_right):
    """Face extérieure de l'œil : iris ambre en dégradé, pupille verticale fine décalée vers l'avant, minuscule
    reflet ; les autres faces sont la cavité de fibres sombres."""
    def f(w, h, r, face):
        if face not in ("east", "west"):
            return paint_contour(w, h, r, face)
        dark, deep, mid, light, glint = AMBRE
        px = []
        for y in range(h):
            row = []
            for x in range(w):
                d = max(abs((x + 0.5) / w - 0.5), abs((y + 0.5) / h - 0.5) * 0.9) * 2
                row.append(dark if d > 0.8 else deep if d > 0.55 else mid if d > 0.25 else light)
            px.append(row)
        pc = max(1, min(w - 2, w // 2 + (1 if front_right else -1)))
        for y in range(1, h - 1):
            px[y][pc] = PUPILLE
        gx = pc + (-1 if front_right else 1)
        px[1][gx] = glint
        return px
    return f


MATS = {
    "liane": (paint_fibres(LIANE), 1),
    "liane_olive": (paint_fibres(LIANE_OLIVE), 1),
    "liane_sombre": (paint_fibres(LIANE_SOMBRE), 1),
    "fibre": (paint_fibres(FIBRE), 1),
    "racine": (paint_fibres(RACINE), 1),
    "ecorce": (paint_ecorce, 1),
    "contour": (paint_contour, 1),
    "oeil_d": (paint_oeil(True), 2),
    "oeil_g": (paint_oeil(False), 2),
}
MATS.update({k: (paint_bec(v), 1) for k, v in BEC.items()})
MATS.update({k: (paint_feuille(v), 1) for k, v in FEUILLES.items()})
LEAF_MATS = list(FEUILLES)


# --------------------------------------------------------------------------- le Rapace-Liane
# Os de l'animation (hiérarchie BetterModel) : nom → parent. Les pivots sont posés dans design() aux articulations.
BONES = {
    "Root": None, "Body": "Root", "Chest": "Body", "Back": "Chest", "Neck": "Chest", "Head": "Neck",
    "Eyes": "Head", "Beak": "Head", "Beak_Lower": "Head", "Tail": "Body",
    "Right_Wing": "Chest", "Right_Wing_Forearm": "Right_Wing", "Right_Wing_Hand": "Right_Wing_Forearm",
    "Left_Wing": "Chest", "Left_Wing_Forearm": "Left_Wing", "Left_Wing_Hand": "Left_Wing_Forearm",
    "Right_Leg": "Body", "Right_Tarsus": "Right_Leg", "Right_Foot": "Right_Tarsus", "Right_Claws": "Right_Foot",
    "Left_Leg": "Body", "Left_Tarsus": "Left_Leg", "Left_Foot": "Left_Tarsus", "Left_Claws": "Left_Foot",
}


def design(S):
    """Corps simplifié (2026-11-06 : une liane = une pièce épaisse, l'écorce en grandes plaques, des feuilles larges),
    tête détaillée. Chaque pièce est rangée dans l'os qui la porte ; les points d'articulation de repos sont gardés
    dans S.pts pour le calcul des animations."""
    rnd = random.Random(2611)
    B, L = S.box, S.leaf
    LV = ["feuille_profonde", "feuille_mousse"]
    S.pts = {}

    def R(node, name, pts, w0, w1, mat, n=1, **kw):
        S.rope(node, name, pts, w0, w1, mat, n=n, **kw)

    # ---------------------------------------------------------------- repère du torse : penché de 15° vers l'avant
    O = [0, 26, 2.5]
    X = [1, 0, 0]
    U = norm([0, 18, -5])
    BK = cross(X, U)                                    # local +z = dos, -z = poitrine
    MT = cols(X, U, BK)

    def T(x, h, z):
        return add(O, sc(X, x), sc(U, h), sc(BK, z))

    top = T(0, 18.6, 0.5)
    hb = add(top, [0, 11, -4.2])
    S.node("Root", [0, 0, 0])
    S.node("Body", [0, 24.5, 3], "Root")
    S.node("Chest", O, "Body")
    S.node("Back", T(0, 15.5, 7.6), "Chest")
    S.node("Neck", top, "Chest")
    S.node("Tail", [0, 24, 7.5], "Body")
    S.pts.update(U=U, hip=[0, 24.5, 3])

    for i, (h0, h1, hw, df, db) in enumerate(((0, 6.5, 7, 5, 5), (6.5, 13, 8.8, 7, 6), (13, 19.5, 10.4, 7.4, 6.8))):
        B("Chest", f"masse_{i}", T(0, (h0 + h1) / 2, (db - df) / 2), [2 * hw - 1, h1 - h0 + 0.4, df + db - 1],
          "liane_sombre", MT)
    for s in (1, -1):
        R("Chest", f"plastron_x_{s}", [T(-9.5 * s, 17.5, -6.4), T(0, 9.5, -7.6 - (0.6 if s > 0 else 0)),
                                       T(5.5 * s, 1.5, -5.4)], 4.2, 3.6, "liane")
        S.plate("Chest", f"pectoral_{s}", T(5.8 * s, 14.6, -8.0), MT, [7.6, 6, 1.4], "ecorce", (-6, -12 * s, -6 * s))
        R("Chest", f"epaule_{s}", [T(6.5 * s, 17, -6.0), T(10.2 * s, 20, -0.5), T(8.5 * s, 17.5, 5.6)], 4.0, 3.6,
          "liane")
        S.plate("Chest", f"ecorce_epaule_{s}", T(10.8 * s, 20.6, 0.3), MT, [6.4, 1.6, 9], "ecorce", (4, 0, -20 * s))
    knot = T(0, 15.5, 7.6)
    B("Back", "noeud_dos", knot, [7, 6, 3], "ecorce", MT)
    R("Back", "dos_x1", [T(-6, 8, 6.4), knot, T(6.5, 18.5, 6.8)], 3.8, 3.4, "liane")
    R("Back", "dos_x2", [T(6, 8, 6.4), add(knot, sc(BK, 0.7)), T(-6.5, 18.5, 6.8)], 3.6, 3.2, "liane")
    S.plate("Back", "ecorce_reins", T(0, 4.5, 5.6), MT, [8, 5, 1.3], "ecorce", (6, 0, 0))

    # ---------------------------------------------------------------- bassin et pattes d'oiseau
    B("Body", "bassin", [0, 25, 3], [12, 6, 9], "liane_sombre")
    for s, side in ((1, "Right"), (-1, "Left")):
        leg, ta, ft, cl = f"{side}_Leg", f"{side}_Tarsus", f"{side}_Foot", f"{side}_Claws"
        H, K, A, F = [6 * s, 24.5, 3], [7.3 * s, 21, -2.5], [7.0 * s, 10.5, 5.6], [6.8 * s, 2.2, -1.2]
        S.node(leg, H, "Body")
        S.node(ta, A, leg)
        S.node(ft, F, ta)
        S.pts.update({f"{side}_H": H, f"{side}_A": A, f"{side}_F": F})
        S.seg(leg, "hanche", H, K, 6.4, "liane_sombre", 6.6, over=1.6)
        S.seg(leg, "cuisse_haut", K, lerp(K, A, 0.55), 7.4, "liane", 7.0, over=1.2)
        S.seg(leg, "cuisse_bas", lerp(K, A, 0.5), A, 5.0, "liane", 4.8, over=0.8)
        S.plate(leg, "ecorce_cuisse", add(lerp(K, A, 0.38), [3.7 * s, 0, 0]), frame(sub(A, K), [0, 0, 1]),
                [5.4, 7.5, 1.2], "ecorce")
        B(ta, "talon", A, [5, 4.6, 5], "liane_sombre", frame(sub(F, A)))
        S.seg(ta, "tarse", A, F, 3.0, "liane", 2.8)
        B(ta, "cheville", add(F, [0, 1.6, 0.2]), [3.6, 2, 3.6], "liane_sombre", frame(sub(F, A)))
        B(ft, "plante", [F[0], 1.25, F[2]], [5.0, 2.5, 5.0], "liane_sombre")
        claw_base = []
        for j, yaw in enumerate((-34, 0, 30)):
            yv = math.radians(yaw * s)
            dv = [math.sin(yv), 0, -math.cos(yv)]
            p0 = [F[0], 1.3, F[2]]
            p1 = add(p0, sc(dv, 4.6))
            p2 = add(p1, sc(dv, 3.6), [0, 0.1, 0])
            S.seg(ft, f"doigt_{j}_a", p0, p1, 2.4, "liane", 2.2)
            S.seg(ft, f"doigt_{j}_b", p1, p2, 2.0, "liane", 1.8)
            c1 = add(p2, sc(dv, 1.5), [0, 0.2, 0])
            c2 = add(c1, sc(dv, 0.9), [0, -1.4, 0])
            S.seg(cl, f"serre_{j}_a", p2, c1, 1.5, "serre", 1.5)
            S.seg(cl, f"serre_{j}_b", c1, c2, 1.0, "serre", 1.0, over=0.3)
            claw_base.append(p2)
        S.node(cl, [sum(p[0] for p in claw_base) / 3, 1.4, sum(p[2] for p in claw_base) / 3], ft)
        h1 = [F[0], 1.0, F[2] + 4.4]
        S.seg(ft, "doigt_arriere", [F[0], 1.4, F[2] + 1.6], h1, 2.1, "liane", 2.0)
        S.seg(ft, "serre_arriere", h1, [F[0], 0.0, F[2] + 6.2], 1.2, "serre", 1.2, over=0.3)

    # ---------------------------------------------------------------- queue
    R("Tail", "queue", [[0, 25, 6.5], [0, 22.5, 10.5], [0, 20.5, 14.5]], 4.2, 3.0, "liane")
    for j, xs in enumerate((-0.45, 0, 0.45)):
        L("Tail", f"feuille_queue_{j}", [xs * 2.6, 20.6 + abs(xs), 14.0 - abs(xs)], [xs, -0.55, 1],
          12.5 - abs(xs) * 4, 4.4, LV[j % 2], [1, 0, 0], curl=6, thick=1.2)

    # ---------------------------------------------------------------- ailes semi-repliées : épaule, avant-bras, main
    for s, side in ((1, "Right"), (-1, "Left")):
        sh = T(8.5 * s, 17.2, 5.2)
        el = add(sh, [4.0 * s, 2.6, -5.5])
        wr = add(el, [3.0 * s, -8.5, 10])
        tip = add(wr, [0.8 * s, -12, 11])
        bones = [f"{side}_Wing", f"{side}_Wing_Forearm", f"{side}_Wing_Hand"]
        S.node(bones[0], sh, "Chest")
        S.node(bones[1], el, bones[0])
        S.node(bones[2], wr, bones[1])
        trail = norm([0.16 * s, -1, 0.3])
        S.pts.update({f"{side}_sh": sh, f"{side}_el": el, f"{side}_wr": wr, f"{side}_tip": tip,
                      f"{side}_trail": trail})
        S.seg("Chest", f"racine_aile_{s}", knot, sh, 4.2, "liane", 4.0, over=1.0)
        B(bones[0], "epaule_aile", sh, [4.8, 4.8, 4.8], "liane_sombre", frame(sub(el, sh), [1, 0, 0]))
        S.seg(bones[0], "humerus", sh, el, 4.4, "liane", 4.2, over=1.0)
        S.seg(bones[1], "avant_bras", el, wr, 3.8, "liane", 3.6, over=1.0)
        S.seg(bones[2], "main", wr, tip, 3.0, "liane", 2.6, over=0.6)
        B(bones[1], "poignet", el, [5, 5, 5], "liane_sombre", frame(sub(wr, sh), [1, 0, 0]))
        B(bones[2], "main_noeud", wr, [3.8, 3.8, 3.8], "liane_sombre", frame(sub(tip, el), [1, 0, 0]))
        ax = norm(sub(wr, el))
        up = norm(sub([0.6 * s, 1, -0.2], sc(ax, dot([0.6 * s, 1, -0.2], ax))))
        S.plate(bones[1], "ecorce_aile", add(lerp(el, wr, 0.45), sc(up, 2.2)), frame(ax, cross(up, ax)),
                [4.6, 11, 1.4], "ecorce")
        out_dir = norm(sub(tip, el))
        spans = [(sh, el), (el, wr), (wr, tip)]

        def along(t):
            i = min(2, int(t))
            a, b = spans[i]
            ax = norm(sub(b, a))
            n = norm(cross(ax, trail))
            if n[0] * s < 0:
                n = sc(n, -1)
            return lerp(a, b, t - i), ax, n, bones[i]

        rows = (("base", 4, 0.5, 0.32, 1.4, 1.6, 6.5, 6.0, 1.6),
                ("milieu", 5, 0.95, 0.24, 3.8, 0.6, 10.0, 5.6, 1.3),
                ("bout", 5, 1.95, 0.22, 1.0, -0.4, 14.0, 4.6, 1.2))
        for row, count, t0, dt, tr, off, length, width, thick in rows:
            for j in range(count):
                p, ax, n, bone = along(t0 + j * dt)
                if row == "bout":
                    d = norm(lerp(trail, out_dir, 0.45 + 0.1 * j))
                else:
                    d = norm(add(sc(trail, 0.8), sc(out_dir, 0.5 + 0.04 * j)))
                L(bone, f"feuille_{row}_{j}", add(p, sc(trail, tr), sc(n, off + 0.3 * (j % 2))), d,
                  length + 0.5 * j * (row == "bout") + rnd.random() * 0.8, width, LV[(j + len(row)) % 2], ax,
                  curl=(9 if row == "bout" else 6) * s, thick=thick)

    # ---------------------------------------------------------------- cou
    neck = [top, add(top, [0, 4, -2.2]), add(top, [0, 8, -2.6]), hb]
    R("Neck", "cou_coeur", neck, 6.4, 4.2, "liane_sombre")
    for s in (1, -1):
        R("Neck", f"cou_liane_{s}", [add(p, [2.2 * s * (1 - 0.25 * i), 0, 0.3]) for i, p in enumerate(neck)],
          3.0, 2.4, "liane")
    for j, a in enumerate((-150, -90, -30)):
        ring = [math.cos(math.radians(a)), 0, math.sin(math.radians(a))]
        L("Neck", f"collerette_{j}", add(top, sc(ring, 4.0), [0, 1.2, 0]), add(ring, [0, -0.9, 0]), 5.5, 3.6,
          LV[j % 2], [-ring[2], 0, ring[0]], curl=8, thick=1.1)

    # ---------------------------------------------------------------- tête : crâne compact, bec de bois, yeux ambre
    C = add(hb, [0, 2.6, -2.0])
    BH = norm([0, 0.22, 1])
    UH = cross(BH, X)
    MH = cols(X, UH, BH)                                # local +z = nuque, -z = bec ; regard un peu vers le bas

    def Hd(x, y, z):
        return add(C, sc(X, x), sc(UH, y), sc(BH, z))
    S.node("Head", hb, "Neck")
    S.node("Eyes", Hd(0, 0.9, -2), "Head")
    S.node("Beak", Hd(0, 1, -5), "Head")
    S.node("Beak_Lower", Hd(0, -1.3, -4.6), "Head")
    S.pts["head_axis"] = X
    B("Head", "crane", Hd(0, 0, 0.6), [7, 6.4, 7.6], "liane_sombre", MH)
    B("Head", "nuque", Hd(0, 0.2, 4.7), [5.6, 5, 1.6], "liane", MH)
    B("Head", "sommet", Hd(0, 3.4, 1.0), [5.6, 1.2, 6.2], "liane", MH)
    B("Head", "gorge", Hd(0, -3.5, -0.2), [5, 1.2, 5.4], "liane_sombre", MH)
    B("Head", "face", Hd(0, 0.3, -3.8), [5.8, 5.4, 1.8], "liane_sombre", MH)
    for s in (1, -1):
        S.rope("Head", f"joue_{s}", [Hd(3.6 * s, -2.2, 3.2), Hd(3.9 * s, -0.8, -0.6), Hd(3.1 * s, -1.9, -4.2)], 1.4,
               1.2, "liane_olive", n=2)
        S.rope("Head", f"tempe_{s}", [Hd(3.4 * s, 2.6, 4), Hd(3.7 * s, 2.0, 0.8)], 1.2, 1.1, "racine", n=1)
    B("Beak", "jonction_bec", Hd(0, 0.9, -4.75), [5.0, 4.4, 0.8], "liane_olive", MH)
    for name, c, size, rx, mat, bone in (("bec_base", (0, 1.0, -6.1), (4.8, 4.0, 2.8), 0, "bec_base", "Beak"),
                                         ("bec_milieu", (0, 0.9, -8.4), (4.0, 3.6, 2.4), -8, "bec_base", "Beak"),
                                         ("arete_bec", (0, 2.95, -7.0), (1.8, 0.8, 5.0), -4, "bec", "Beak"),
                                         ("mandibule", (0, -1.6, -6.9), (3.6, 1.4, 4.4), -4, "bec_base", "Beak_Lower"),
                                         ("mandibule_pointe", (0, -1.9, -9.4), (2.4, 1.1, 1.6), -4, "bec",
                                          "Beak_Lower")):
        S.plate(bone, name, Hd(*c), MH, list(size), mat, (rx, 0, 0))
    # crochet : arc continu du dessus du bec jusqu'à la pointe presque noire, qui revient vers l'intérieur
    arc = [(-1.3 + 2.6 * math.cos(math.radians(a)), -9.4 - 2.6 * math.sin(math.radians(a))) for a in range(0, 201, 40)]
    for i, (w, d, mat) in enumerate(((3.6, 3.2, "bec_base"), (3.1, 2.8, "bec"), (2.5, 2.3, "bec"), (1.8, 1.7, "serre"),
                                     (1.1, 1.1, "serre"))):
        (y0, z0), (y1, z1) = arc[i], arc[i + 1]
        S.seg("Beak", f"bec_crochet_{i}", Hd(0, y0, z0), Hd(0, y1, z1), w, mat, d, xhint=X, over=0.9 if i < 4 else 0.4)
    for s in (1, -1):
        S.plate("Eyes", f"oeil_{s}", Hd(3.5 * s, 0.85, -2.0), MH, [0.6, 2.4, 3.4], "oeil_d" if s > 0 else "oeil_g",
                (-10, 30 * s, 0))
        S.plate("Eyes", f"sourcil_{s}", Hd(3.3 * s, 2.45, -2.3), MH, [2.6, 1.2, 4.8], "contour", (-18, 26 * s, 0))
        S.plate("Eyes", f"arcade_{s}", Hd(2.3 * s, 2.9, -3.9), MH, [2.2, 1.1, 2.0], "liane_sombre", (-24, 0, -12 * s))
        S.plate("Eyes", f"paupiere_{s}", Hd(3.65 * s, -0.55, -1.8), MH, [1.1, 0.6, 3.6], "contour", (-8, 30 * s, 0))
        S.plate("Eyes", f"coin_arriere_{s}", Hd(3.75 * s, 0.9, -0.1), MH, [1.0, 2.6, 0.6], "contour", (0, 30 * s, 0))
        S.plate("Eyes", f"coin_avant_{s}", Hd(3.2 * s, 0.7, -3.6), MH, [0.9, 1.9, 0.5], "contour", (0, 30 * s, 0))
        S.seg("Eyes", f"fibre_sourcil_{s}", Hd(3.95 * s, 2.85, -2.8), Hd(3.75 * s, 3.5, 1.6), 0.6, "racine")
        S.seg("Eyes", f"fibre_sourcil_b_{s}", Hd(3.85 * s, 2.5, -0.4), Hd(3.55 * s, 2.9, 3.2), 0.5, "liane_olive")
    # couronne végétale
    S.rope("Head", "crete_1", [Hd(0.5, 3.6, 2.5), Hd(0.9, 5.4, 5.5), Hd(1.3, 6.6, 9.0), Hd(1.6, 6.9, 11.8)], 2.6, 1.0,
           "liane", n=2)
    S.rope("Head", "crete_2", [Hd(-1.5, 3.4, 3), Hd(-2.1, 4.9, 6.4), Hd(-2.7, 5.4, 9.8)], 2.1, 0.9, "liane_olive", n=2)
    S.rope("Head", "crete_3", [Hd(2.2, 3.0, 3.6), Hd(3.0, 4.6, 6.5), Hd(3.6, 5.1, 8.6)], 1.7, 0.8, "racine", n=2)
    S.rope("Head", "crete_4", [Hd(-0.4, 2.6, 4.8), Hd(-0.6, 3.9, 8), Hd(-0.3, 3.6, 10.6)], 1.9, 0.8, "liane_sombre",
           n=2)
    for s in (1, -1):
        L("Head", f"feuille_crete_{s}", Hd(2.9 * s, 2.8, 3.2), add(sc(X, 0.7 * s), sc(UH, 0.45), sc(BH, 1)), 4.4,
          1.9, LEAF_MATS[1 if s > 0 else 3], [0, 1, 0], curl=6, thick=0.8)
    for n, p in BONES.items():
        assert n in S.nodes and S.nodes[n]["parent"] == p, n


# --------------------------------------------------------------------------- géométrie finale
def cube_corners(c):
    hs = [v / 2 for v in c["size"]]
    pts = []
    for sx in (-1, 1):
        for sy in (-1, 1):
            for sz in (-1, 1):
                p = app(c["M"], [sx * hs[0], sy * hs[1], sz * hs[2]])
                pts.append(add(c["c"], p))
    return pts


def solve():
    """Pose les griffes au sol (y = 0) et met le modèle à la hauteur visée."""
    S = Scene()
    design(S)
    y0 = min(p[1] for c in S.cubes for p in cube_corners(c))
    top = max(p[1] for c in S.cubes for p in cube_corners(c)) - y0
    k = HEIGHT * 16 / top
    for c in S.cubes:
        c["c"] = [c["c"][0] * k, (c["c"][1] - y0) * k, c["c"][2] * k]
        c["size"] = [v * k for v in c["size"]]
    for n in S.nodes.values():
        n["pivot"] = [n["pivot"][0] * k, (n["pivot"][1] - y0) * k, n["pivot"][2] * k]
    for key, v in S.pts.items():
        if key not in ("U", "head_axis") and not key.endswith("_trail"):
            S.pts[key] = [v[0] * k, (v[1] - y0) * k, v[2] * k]
    return S, k


# --------------------------------------------------------------------------- export
def uid(name):
    return str(uuid.uuid5(NS, f"{IDENT}/{name}"))


def bake_textures(S, k):
    """Atlas par étagères (texels à la taille de dessin, avant mise à l'échelle) ; taille minimale qui tient."""
    reqs = []
    for c in S.cubes:
        dx, dy, dz = (v / k for v in c["size"])
        dims = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
                "up": (dx, dz), "down": (dx, dz)}
        for d, (fw, fh) in dims.items():
            mat = c["faces"].get(d, c["mat"])
            dens = MATS[mat][1]
            reqs.append((c, d, fw * dens, fh * dens, mat))
    reqs.sort(key=lambda q: (-math.ceil(q[3] - 1e-6), -math.ceil(q[2] - 1e-6)))
    for size in (256, 512, 1024):
        x = y = shelf = 0
        places = []
        ok = True
        for q in reqs:
            w, h = max(1, math.ceil(q[2] - 1e-6)), max(1, math.ceil(q[3] - 1e-6))
            if x + w > size:
                x, y, shelf = 0, y + shelf, 0
            if y + h > size:
                ok = False
                break
            places.append((x, y, w, h))
            x, shelf = x + w, max(shelf, h)
        if ok:
            break
    else:
        raise SystemExit("atlas trop petit")
    atlas = [[(0, 0, 0, 0)] * size for _ in range(size)]
    rnd = random.Random(1306)
    for (c, d, fw, fh, mat), (x, y, w, h) in zip(reqs, places):
        img = MATS[mat][0](w, h, random.Random(rnd.random()), d)
        for j in range(h):
            for i in range(w):
                atlas[y + j][x + i] = tuple(img[j][i]) + (255,)
        c.setdefault("uv", {})[d] = [x, y, round(x + max(fw, 0.01), 3), round(y + max(fh, 0.01), 3)]
    print(f"texture : {size}x{size}, {y + shelf}/{size} lignes utilisées")
    return atlas, size


def export(S, atlas, tex):
    elements = []
    for i, c in enumerate(S.cubes):
        hs = [v / 2 for v in c["size"]]
        e = {"name": c["name"], "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
             "allow_mirror_modeling": True, "from": [round(c["c"][j] - hs[j], 4) for j in range(3)],
             "to": [round(c["c"][j] + hs[j], 4) for j in range(3)], "autouv": 0, "color": 0,
             "origin": [round(v, 4) for v in c["c"]],
             "faces": {d: {"uv": uv, "texture": 0} for d, uv in c["uv"].items()}, "type": "cube",
             "uuid": uid(f"el/{c['node']}/{c['name']}/{i}")}
        r = euler(c["M"])
        if any(abs(v) > 1e-3 for v in r):
            e["rotation"] = r
        elements.append(e)

    def group(name, origin):
        return {"name": name, "origin": [round(v, 4) for v in origin], "color": 0, "uuid": uid("group/" + name),
                "export": True, "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True,
                "autouv": 0, "rotation": [0, 0, 0], "children": []}
    index = {n: group(n, v["pivot"]) for n, v in S.nodes.items()}
    for c, e in zip(S.cubes, elements):
        index[c["node"]]["children"].append(e["uuid"])
    used = {c["node"] for c in S.cubes}
    for n in list(used):
        while S.nodes[n]["parent"]:
            n = S.nodes[n]["parent"]
            used.add(n)
    roots = []
    for n, v in S.nodes.items():
        if n in used:
            (index[v["parent"]]["children"] if v["parent"] else roots).append(index[n])
    texture = {"path": "", "name": IDENT + ".png", "folder": "", "namespace": "", "id": "0", "width": tex,
               "height": tex, "uv_width": tex, "uv_height": tex, "particle": True, "use_as_default": False,
               "layers_enabled": False, "sync_to_project": "", "render_mode": "default", "render_sides": "auto",
               "frame_time": 1, "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
               "visible": True, "internal": True, "saved": False, "uuid": uid("texture"),
               "source": "data:image/png;base64," + base64.b64encode(encode(tex, tex, atlas)).decode()}
    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": IDENT, "model_identifier": IDENT, "visible_box": [4, 5, 0], "variable_placeholders": "",
            "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
            "resolution": {"width": tex, "height": tex}, "elements": elements,
            "outliner": [{"name": IDENT, "origin": [0, 0, 0], "color": 0, "uuid": uid("group"), "export": True,
                          "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True, "autouv": 0,
                          "rotation": [0, 0, 0], "children": roots}],
            "textures": [texture], "animations": []}


def apercu(data, atlas, path, size=420):
    """Planche de 4 vues sur fond neutre : trois-quarts avant, face, profil, trois-quarts arrière."""
    import apercu as A
    bg = (58, 58, 62, 255)
    views = [(28, 12), (0, 6), (90, 4), (208, 16)]
    imgs = [A.render(data["elements"], atlas, yaw, pitch, size=size, bg=bg, margin=30) for yaw, pitch in views]
    write(path, size * len(imgs), size, [sum((im[y] for im in imgs), []) for y in range(size)])


def strips(data, atlas, folder, count=8, only=None):
    """Une bande PNG par animation (profil au-dessus, trois-quarts au-dessous), cadrage fixe, ligne de sol."""
    import apercu as A
    from apercu_anim import posed_elements
    os.makedirs(folder, exist_ok=True)
    size = 190
    for a in data["animations"]:
        if only and a["name"] not in only:
            continue
        n = count if a["loop"] == "loop" else count + 2
        times = [a["length"] * i / (n if a["loop"] == "loop" else n - 1) for i in range(n)]
        frames = [posed_elements(data, a["name"], t) for t in times]
        pts = []
        lows = []
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
        for yaw, pitch in ((90, 0), (30, 12)):
            imgs = [A.render(fe, atlas, yaw, pitch, size=size, frame_points=pts) for fe in frames]
            rows += [sum((im[y] for im in imgs), []) for y in range(size)]
        write(os.path.join(folder, f"anim_{a['name']}.png"), size * len(frames), 2 * size, rows)
        print(f"{a['name']} : point le plus bas par image {lows}")


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
    S, k = solve()
    atlas, tex = bake_textures(S, k)
    data = export(S, atlas, tex)
    import rapace_liane_anim
    data["animations"] = rapace_liane_anim.build(S, uid)
    w = max(p[0] for c in S.cubes for p in cube_corners(c)) - min(p[0] for c in S.cubes for p in cube_corners(c))
    print(f"hauteur : {HEIGHT:.2f} blocs, envergure : {w / 16:.2f} blocs, {len(data['elements'])} cubes, "
          f"échelle {k:.3f}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    write(os.path.join(out, IDENT + ".png"), tex, tex, atlas)
    print("écrit", path)
    if prev:
        apercu(data, atlas, prev)
    if anims:
        strips(data, atlas, anims)
