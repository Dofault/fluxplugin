#!/usr/bin/env python3
"""Golem de Fonderie : monstre du donjon du Forgeron, modèle Blockbench (.bbmodel) STATIQUE, fidèle au concept
artistique (refonte du 2026-11-06). Pas d'animation ni de squelette à cette étape : la posture du concept
(jambes en A, genoux fléchis, pieds tournés vers l'extérieur, buste penché, épaules inégales, bras du marteau
avancé et écarté, autre bras pendant) est cuite dans les cubes, rangés dans un seul groupe.

La posture est décrite plus bas comme une hiérarchie de pivots (bassin, jambes, torse, tête, bras, marteau) :
le jour où le monstre sera animé pour MythicMobs, ces pivots deviendront les os sans tout redessiner.

Textures : 1 texel par unité (comme le vanilla), palette limitée par matériau (métal anthracite chaud, métal
oxydé, métal en fusion), dégradés doux et taches groupées plutôt que du bruit. Le métal en fusion est sur une
seconde texture « emissive » (pleine luminosité, gérée nativement par BetterModel).

Usage : golem_fonderie.py [dossier de sortie] [--apercu <image.png>]
Face avant vers le nord (-z), côté droit du monstre en +x (bras du marteau).
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
from png_util import encode, write  # noqa: E402

IDENT = "golem_fonderie"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000000f0d1")
TEX = 256      # atlas du corps
GTEX = 64      # atlas du métal en fusion (texture émissive)
HEIGHT = 4     # hauteur visée en blocs : le modèle dessiné est agrandi pour l'atteindre (texels agrandis d'autant)


def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


# --------------------------------------------------------------------------- palette (imposée par le brief)
M0, M1, M2, M3, M4 = (hx(c) for c in ("#29272A", "#39363A", "#4B474A", "#625B59", "#746B66"))   # métal
R0, R1, R2 = (hx(c) for c in ("#493126", "#69432F", "#80563B"))                                  # oxydé
G0, G1, G2, G3, G4 = (hx(c) for c in ("#A83D13", "#D65A16", "#F28A20", "#FFB43B", "#FFD36B"))   # fusion

RAMPS = {   # (ombre, base, lumière) par matériau
    "metal": (M1, M2, M3),
    "dark": (M0, M1, M2),
    "rust": (R0, R1, R2),
    "rust_dark": (M0, R0, R1),
}


# --------------------------------------------------------------------------- peintres
def patches(px, w, h, r, color, n, maxw=3, maxh=2):
    """Taches d'usure groupées (jamais un pixel isolé)."""
    for _ in range(n):
        pw, ph = r.randint(2, maxw), r.randint(1, maxh)
        if pw > w or ph > h:
            continue
        x0, y0 = r.randrange(0, w - pw + 1), r.randrange(0, h - ph + 1)
        for y in range(y0, y0 + ph):
            for x in range(x0, x0 + pw):
                px[y][x] = color


def paint_solid(w, h, r, ramp, face):
    shade, base, light = ramp
    if face == "up":
        px = [[light] * w for _ in range(h)]
        patches(px, w, h, r, base, w * h // 24)
        return px
    if face == "down":
        return [[shade] * w for _ in range(h)]
    px = [[base] * w for _ in range(h)]
    patches(px, w, h, r, shade, w * h // 30)
    patches(px, w, h, r, light, w * h // 40, maxh=1)
    if h >= 3:
        px[0] = [light] * w             # arête haute éclairée
        px[h - 1] = [shade] * w         # bas dans l'ombre
    if h >= 6 and w >= 4:               # ombre progressive vers le bas
        for x in range(w):
            if r.random() < 0.6:
                px[h - 2][x] = shade
    return px


def paint_glow(w, h, r, face):
    """Du bord sombre au cœur clair ; le jaune très clair seulement au centre des grandes surfaces."""
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            dx = abs((x + 0.5) / w - 0.5) * 2 if w > 1 else 0
            dy = abs((y + 0.5) / h - 0.5) * 2 if h > 1 else 0
            d = max(dx, dy)
            c = G0 if d > 0.85 else G1 if d > 0.6 else G2 if d > 0.3 else G3
            if d <= 0.15 and w * h >= 8:
                c = G4
            row.append(c)
        px.append(row)
    return px


def paint_slit(w, h, r, face):
    """Fond des fentes du fourneau : orange vif au centre, assombri aux extrémités, jaune au cœur."""
    px = []
    for y in range(h):
        d = abs((y + 0.5) / h - 0.5) * 2
        row = []
        for x in range(w):
            dx = abs((x + 0.5) / w - 0.5) * 2
            c = G1 if d > 0.8 else G2 if d > 0.45 else G3
            if d < 0.15 and dx < 0.25:
                c = G4
            row.append(c)
        px.append(row)
    return px


def paint_gap(w, h, r, face):
    """Chaleur visible entre deux plaques : orange foncé à intermédiaire, pas de jaune."""
    return [[G2 if (x + y) % 3 == 0 and w > 2 else G1 for x in range(w)] for y in range(h)]


def paint_eye(w, h, r, face):
    return [[G3 if 0 < x < w - 1 or w <= 2 else G2 for x in range(w)] for _ in range(h)]


def paint_face(w, h, r, face):
    """Face du casque : masse sombre, à peine plus claire sous le sourcil."""
    px = [[M0] * w for _ in range(h)]
    px[0] = [M1] * w
    return px


def paint_warm(hot_left):
    """Côté intérieur du fourneau : le brun sombre s'échauffe vers la matière en fusion."""
    def f(w, h, r, face):
        ramp = [R0, R0, R1, G0]
        row = [ramp[min(3, int((i + 0.5) * 4 / w))] for i in range(w)]
        if hot_left:
            row = row[::-1]
        return [list(row) for _ in range(h)]
    return f


MATS = {
    "metal": lambda w, h, r, f: paint_solid(w, h, r, RAMPS["metal"], f),
    "dark": lambda w, h, r, f: paint_solid(w, h, r, RAMPS["dark"], f),
    "rust": lambda w, h, r, f: paint_solid(w, h, r, RAMPS["rust"], f),
    "rust_dark": lambda w, h, r, f: paint_solid(w, h, r, RAMPS["rust_dark"], f),
    "face": paint_face,
    "warm_e": paint_warm(True),
    "warm_w": paint_warm(False),
}
GLOW_MATS = {"glow": paint_glow, "slit": paint_slit, "gap": paint_gap, "eye": paint_eye}


# --------------------------------------------------------------------------- maths
def mul(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def app(M, v):
    return [sum(M[i][k] * v[k] for k in range(3)) for i in range(3)]


def rot(rx, ry, rz):
    """Blockbench (three.js, ordre ZYX) : R = Rz * Ry * Rx, angles en degrés."""
    x, y, z = (math.radians(v) for v in (rx, ry, rz))
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    Rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    Ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    Rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return mul(Rz, mul(Ry, Rx))


def euler(M):
    """Inverse de rot() : angles (x, y, z) en degrés."""
    sy = max(-1.0, min(1.0, -M[2][0]))
    y = math.asin(sy)
    if abs(sy) < 0.9999:
        x, z = math.atan2(M[2][1], M[2][2]), math.atan2(M[1][0], M[0][0])
    else:
        x, z = math.atan2(-M[1][2], M[1][1]), 0.0
    return [round(math.degrees(a), 4) for a in (x, y, z)]


# --------------------------------------------------------------------------- scène
class Golem:
    def __init__(self):
        self.nodes, self.cubes = {}, []

    def node(self, name, pivot, rx=0, ry=0, rz=0, parent=None, world=None, offset=(0, 0, 0)):
        """Pivot de posture. world=(x, y, z) impose l'orientation finale (pied à plat quelle que soit la jambe)."""
        self.nodes[name] = {"pivot": list(pivot), "rot": (rx, ry, rz), "parent": parent, "world": world,
                            "offset": list(offset)}

    def cube(self, node, name, frm, to, mat, faces=None, erot=None):
        self.cubes.append({"node": node, "name": name, "from": list(frm), "to": list(to), "mat": mat,
                           "faces": faces or {}, "erot": erot})

    def mirror(self, node, name, frm, to, mat, faces=None, erot=None):
        sw = {"east": "west", "west": "east", "warm_e": "warm_w", "warm_w": "warm_e"}
        f = {sw.get(k, k): sw.get(v, v) for k, v in (faces or {}).items()}
        if erot:
            erot = (erot[0], -erot[1], -erot[2])
        self.cube(node, name, [-to[0], frm[1], frm[2]], [-frm[0], to[1], to[2]], sw.get(mat, mat), f, erot)

    def world(self, name):
        """(M, P) : un point local x du nœud va en M (x - pivot) + P."""
        n = self.nodes[name]
        if n["parent"] is None:
            Mp = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
            P = [n["pivot"][i] + n["offset"][i] for i in range(3)]
        else:
            Mp, Ppar = self.world(n["parent"])
            pp = self.nodes[n["parent"]]["pivot"]
            d = app(Mp, [n["pivot"][k] - pp[k] for k in range(3)])
            P = [d[i] + Ppar[i] + n["offset"][i] for i in range(3)]
        M = rot(*n["world"]) if n["world"] else mul(Mp, rot(*n["rot"]))
        return M, P

    def bake(self):
        """Éléments avec la posture appliquée : rotation et pivot propres à chaque cube."""
        out = []
        for c in self.cubes:
            M, P = self.world(c["node"])
            pn = self.nodes[c["node"]]["pivot"]
            oe = [(a + b) / 2 for a, b in zip(c["from"], c["to"])]
            Mw = mul(M, rot(*c["erot"])) if c["erot"] else M
            d = app(M, [oe[k] - pn[k] for k in range(3)])
            q = [d[i] + P[i] for i in range(3)]
            out.append({"c": c, "M": Mw, "origin": q, "pivot": list(P),
                        "from": [c["from"][i] - oe[i] + q[i] for i in range(3)],
                        "to": [c["to"][i] - oe[i] + q[i] for i in range(3)]})
        return out


def corners(b):
    pts = []
    for x in (b["from"][0], b["to"][0]):
        for y in (b["from"][1], b["to"][1]):
            for z in (b["from"][2], b["to"][2]):
                p = app(b["M"], [x - b["origin"][0], y - b["origin"][1], z - b["origin"][2]])
                pts.append([p[i] + b["origin"][i] for i in range(3)])
    return pts


# --------------------------------------------------------------------------- le golem
def design(g, foot_offsets=None):
    N, C, Mi = g.node, g.cube, g.mirror
    fo = foot_offsets or {}

    # ---------------------------------------------------------------- bassin : compact, penché vers la jambe d'appui
    N("bassin", (0, 20, 0), rz=-3)
    C("bassin", "hanches", (-5.5, 17.5, -3.5), (5.5, 21, 3.5), "metal")
    C("bassin", "ceinture", (-6, 19.8, -4), (6, 21.8, 4), "rust_dark")
    C("bassin", "boucle", (-1.5, 19.4, -4.6), (1.5, 22.2, -4), "metal")
    C("bassin", "protection", (-2.25, 15.5, -4.3), (2.25, 19.8, -3.7), "rust")   # courte, ne couvre pas les cuisses
    C("bassin", "protection_dos", (-2.5, 16.5, 3.5), (2.5, 20, 4.1), "rust_dark")

    # ---------------------------------------------------------------- jambes en A, genoux fléchis
    # droite : plus légère et plus avancée ; gauche : jambe d'appui, plus écartée
    legs = {"d": dict(s=1, hip=(10, 0, 15), knee=(-20, 0, -11), toe=-14),
            "g": dict(s=-1, hip=(4, 0, -19), knee=(-10, 0, 14), toe=12)}
    for k, L in legs.items():
        put = C if L["s"] > 0 else Mi
        s = L["s"]
        cuisse, tibia, pied = f"cuisse_{k}", f"tibia_{k}", f"pied_{k}"
        N(cuisse, (3.75 * s, 18.5, 0), *L["hip"], parent="bassin")
        put(cuisse, "cuisse", (1.5, 11.5, -2.5), (6, 18.5, 2.5), "metal")
        put(cuisse, "plaque_cuisse", (1.25, 13, -3), (6.25, 17.5, -2.5), "metal")
        put(cuisse, "plaque_cuisse_ext", (6, 12.5, -2), (6.6, 17, 1.5), "rust")
        N(tibia, (3.75 * s, 11.5, 0), *L["knee"], parent=cuisse)
        put(tibia, "chaleur_genou", (2, 10.6, -2.1), (5.5, 12.2, 2.1), "gap")
        put(tibia, "genouillere", (1.25, 9.5, -3.4), (6.25, 12.75, -1.5), "metal")
        put(tibia, "tibia", (2, 5, -2.25), (5.5, 11, 2.25), "dark")
        put(tibia, "plaque_tibia_a", (1.75, 6, -2.8), (3.45, 9.6, -2.25), "rust")
        put(tibia, "plaque_tibia_b", (4.15, 6, -2.8), (5.75, 9.6, -2.25), "rust")
        put(tibia, "faille_tibia", (3.45, 6.3, -2.45), (4.15, 9.3, -2.25), "gap")
        N(pied, (3.75 * s, 5, 0), parent=tibia, world=(0, L["toe"], 0), offset=(0, fo.get(k, 0), 0))
        put(pied, "cheville", (1.5, 3.5, -2.5), (6, 6.5, 2.5), "dark")
        put(pied, "botte", (0.25, 1.5, -4), (7.25, 4.5, 3.5), "metal")
        put(pied, "avant_pied", (0.5, 1.5, -6), (7, 3.5, -4), "metal")
        put(pied, "talon", (1.5, 1.5, 3.5), (6, 3.25, 4.75), "dark")
        put(pied, "semelle", (0, 0, -6.5), (7.5, 1.5, 4.75), "rust")

    # ---------------------------------------------------------------- torse : penché en avant, épaules inégales
    N("torse", (0, 21, 0), rx=-9, ry=4, rz=5, parent="bassin")
    C("torse", "taille", (-4.5, 20.5, -3.25), (4.5, 24, 3.25), "dark")
    C("torse", "torse_bas", (-6, 23.5, -4.25), (6, 30.5, 4.25), "metal")
    C("torse", "torse_haut", (-7.5, 30, -4.75), (7.5, 35.5, 4.75), "metal")
    C("torse", "plastron_d", (0.2, 31.3, -5.35), (5.2, 34.7, -4.75), "metal")
    C("torse", "plastron_g", (-5.2, 31.3, -5.35), (-0.2, 34.7, -4.75), "metal")
    C("torse", "flanc_d", (6, 24.5, -3.5), (6.7, 31, 3.5), "rust")
    C("torse", "dos", (-5.5, 24.5, 4.25), (5.5, 34, 5), "dark")
    C("torse", "col", (-4, 34.5, -3.5), (4, 36, 3), "dark")
    # fourneau encastré : cadre anthracite, encadrement brun, trois fentes étroites et profondes
    C("torse", "four_haut", (-4, 29.8, -5.6), (4, 31, -4.25), "dark")
    C("torse", "four_bas", (-4, 24, -5.6), (4, 25.2, -4.25), "dark")
    C("torse", "four_cote_d", (3, 25.2, -5.6), (4, 29.8, -4.25), "dark", {"west": "warm_w"})
    C("torse", "four_cote_g", (-4, 25.2, -5.6), (-3, 29.8, -4.25), "dark", {"east": "warm_e"})
    for i, (a, b) in enumerate(((-3, -2.5), (-1.5, -0.5), (0.5, 1.5), (2.5, 3))):
        C("torse", f"four_montant_{i}", (a, 25.2, -5.3), (b, 29.8, -4.25), "rust_dark",
          {"east": "warm_e", "west": "warm_w"})
    C("torse", "braise", (-2.5, 25.2, -4.5), (2.5, 29.8, -4.25), "slit")
    # dos : deux petits conduits
    C("torse", "conduit_d", (1.5, 32, 4.5), (4, 37.5, 6.5), "dark")
    C("torse", "conduit_d_bague", (1.25, 36, 4.25), (4.25, 37, 6.75), "rust_dark")
    C("torse", "conduit_g", (-4, 31, 4.5), (-1.75, 35.5, 6.25), "rust_dark")

    # épaulières : plaques superposées, épaisseurs et pentes différentes des deux côtés
    N("epaule_d", (8.5, 34, 0), rz=-16, parent="torse")
    C("epaule_d", "plaque_a", (5.5, 33.5, -4.75), (13.5, 36, 4.75), "metal")
    C("epaule_d", "plaque_b", (8, 31.2, -5), (14.5, 33.8, 5), "dark")
    C("epaule_d", "plaque_c", (10.5, 29.4, -4.5), (15, 31.4, 4.5), "rust")
    N("epaule_g", (-8.5, 34, 0), rz=22, parent="torse")
    C("epaule_g", "plaque_a", (-13, 33.5, -4.5), (-5.5, 35.5, 4.5), "metal")
    C("epaule_g", "plaque_b", (-14, 30.4, -5.25), (-8, 33.7, 5.25), "metal")
    C("epaule_g", "bloc", (-11, 35.3, -3), (-7.5, 36.8, 3), "dark")

    # ---------------------------------------------------------------- tête : petit casque sombre, rentré dans les épaules
    N("tete", (0, 35, -0.5), rx=7, ry=-10, parent="torse")
    C("tete", "casque", (-3.5, 34.5, -4), (3.5, 40.75, 2.5), "metal", {"north": "face"})
    C("tete", "dessus", (-3.75, 40.5, -4.25), (3.75, 42, 2.75), "metal")
    C("tete", "sourcil", (-3.5, 38.2, -4.8), (3.5, 39.6, -4), "dark")
    C("tete", "machoire", (-3, 34.5, -4.8), (3, 36.3, -3.5), "metal")
    C("tete", "joue_d", (3.5, 35, -4.2), (4.1, 39.5, 0.5), "rust_dark")
    C("tete", "joue_g", (-4.1, 35.5, -4.2), (-3.5, 39, 0), "rust_dark")
    C("tete", "oeil_d", (0.6, 37.2, -4.15), (2.6, 37.9, -4), "eye")
    C("tete", "oeil_g", (-2.6, 37.2, -4.15), (-0.6, 37.9, -4), "eye")

    # ---------------------------------------------------------------- bras droit : avancé, écarté, tient le marteau
    N("bras_d", (10.5, 31.5, 0), rx=8, rz=18, parent="torse")
    C("bras_d", "epaule", (8.5, 28.5, -2.25), (12.5, 32, 2.25), "dark")
    C("bras_d", "bras", (8.75, 24, -2), (12.25, 30, 2), "metal")
    C("bras_d", "plaque_bras", (12.25, 24.5, -2.3), (12.9, 29, 2.3), "rust")
    N("avant_bras_d", (10.5, 24, 0), rx=22, rz=-6, parent="bras_d")
    C("avant_bras_d", "coude", (8.5, 22.5, -2.25), (12.5, 25, 2.25), "dark")
    C("avant_bras_d", "chaleur", (8.6, 18.8, -2.6), (12.4, 19.8, 2.6), "gap")
    C("avant_bras_d", "plaque_haut", (8, 19.6, -3), (13, 23, 3), "metal")
    C("avant_bras_d", "plaque_bas", (8, 15.5, -3), (13, 19, 3), "metal")
    C("avant_bras_d", "protection", (13, 16, -2.5), (13.6, 22.5, 2.5), "rust")
    C("avant_bras_d", "poing", (8.25, 11.5, -2.75), (12.75, 15.5, 2.75), "metal")
    # marteau : manche court et épais à travers le poing, tête massive en blocs superposés
    # porté bas : manche vers le sol, un peu en avant et en dehors, tête près du pied droit
    N("marteau", (10.5, 13.5, 0), parent="avant_bras_d", world=(0, 24, -50))
    C("marteau", "manche", (7.75, 12.6, -0.9), (18.5, 14.4, 0.9), "rust_dark")
    C("marteau", "pommeau", (7, 12.25, -1.25), (8, 14.75, 1.25), "metal")
    C("marteau", "tete", (17.5, 10, -5.5), (25, 17, 5.5), "metal")
    C("marteau", "bout_avant", (17.25, 9.75, -6.5), (25.25, 17.25, -4.25), "metal")
    C("marteau", "bout_arriere", (17.75, 10.25, 4.25), (24.75, 16.75, 6.25), "rust")
    C("marteau", "bande", (17.4, 9.9, -0.6), (25.1, 17.1, 0.6), "glow")
    C("marteau", "event", (25, 12, -3.4), (25.2, 15, -1.6), "gap")
    C("marteau", "frette", (16.75, 12.25, -1.25), (17.5, 14.75, 1.25), "metal")

    # ---------------------------------------------------------------- bras gauche : pend, légèrement fléchi
    N("bras_g", (-10.5, 31.5, 0), rx=10, rz=-12, parent="torse")
    Mi("bras_g", "epaule", (8.5, 28.5, -2.25), (12.5, 32, 2.25), "dark")
    Mi("bras_g", "bras", (8.75, 24, -2), (12.25, 30, 2), "metal")
    N("avant_bras_g", (-10.5, 24, 0), rx=24, rz=4, parent="bras_g")
    Mi("avant_bras_g", "coude", (8.5, 22.5, -2.25), (12.5, 25, 2.25), "dark")
    Mi("avant_bras_g", "chaleur", (8.6, 19.8, -2.6), (12.4, 20.8, 2.6), "gap")
    Mi("avant_bras_g", "plaque_haut", (8, 20.6, -3.25), (13.25, 23, 3.25), "metal")
    Mi("avant_bras_g", "plaque_bas", (8, 15.5, -3.25), (13.25, 20, 3.25), "metal")
    Mi("avant_bras_g", "protection", (13.25, 15.5, -3.5), (14.1, 22.5, 3.5), "rust")
    Mi("avant_bras_g", "poing", (8, 11, -3), (13, 15.5, 3), "metal")


def solve():
    """Pose les deux pieds à plat sur le sol : chaque pied est recalé à la hauteur du plus bas, puis tout le
    modèle est posé à y = 0."""
    g = Golem()
    design(g)
    low = {}
    for b in g.bake():
        n = b["c"]["node"]
        if n.startswith("pied_"):
            low[n[-1]] = min(low.get(n[-1], 1e9), min(p[1] for p in corners(b)))
    target = min(low.values())
    offs = {k: target - v for k, v in low.items()}
    g = Golem()
    design(g, offs)
    baked = g.bake()
    y0 = min(p[1] for b in baked if b["c"]["node"].startswith("pied_") for p in corners(b))   # sol = semelles
    top = max(p[1] for b in baked for p in corners(b)) - y0
    k = HEIGHT * 16 / top
    for b in baked:
        for key in ("from", "to", "origin", "pivot"):
            b[key][1] -= y0
            b[key] = [v * k for v in b[key]]
    return g, baked, offs


# --------------------------------------------------------------------------- export
def uid(name):
    return str(uuid.uuid5(NS, f"{IDENT}/{name}"))


def bake_textures(baked):
    """Atlas par étagères ; texture 0 = corps, texture 1 = métal en fusion (émissive)."""
    size = {0: TEX, 1: GTEX}
    atlases = {t: [[(0, 0, 0, 0)] * size[t] for _ in range(size[t])] for t in size}
    cursor = {0: [0, 0, 0], 1: [0, 0, 0]}
    reqs = []
    for b in baked:
        c = b["c"]
        dx, dy, dz = (c["to"][i] - c["from"][i] for i in range(3))
        dims = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
                "up": (dx, dz), "down": (dx, dz)}
        b["faces"] = {}
        for d, (fw, fh) in dims.items():
            mat = c["faces"].get(d, c["mat"])
            reqs.append((b, d, fw, fh, mat, 1 if mat in GLOW_MATS else 0))
    reqs.sort(key=lambda q: (q[5], -math.ceil(q[3] - 1e-6), -math.ceil(q[2] - 1e-6)))
    rnd = random.Random(1306)
    for b, d, fw, fh, mat, t in reqs:
        w, h = max(1, math.ceil(fw - 1e-6)), max(1, math.ceil(fh - 1e-6))
        x, y, shelf = cursor[t]
        if x + w > size[t]:
            x, y, shelf = 0, y + shelf, 0
        if y + h > size[t]:
            raise SystemExit(f"texture {t} trop petite")
        img = (GLOW_MATS if t else MATS)[mat](w, h, random.Random(rnd.random()), d)
        for j in range(h):
            for i in range(w):
                atlases[t][y + j][x + i] = tuple(img[j][i]) + (255,)
        b["faces"][d] = {"uv": [x, y, round(x + max(fw, 0.01), 3), round(y + max(fh, 0.01), 3)], "texture": t}
        cursor[t] = [x + w, y, max(shelf, h)]
    for t in (0, 1):
        print(f"texture {t} : {cursor[t][1] + cursor[t][2]}/{size[t]} lignes")
    return atlases[0], atlases[1]


def groups(g, baked, elements):
    """Os de l'animation : un groupe par pièce, pivot à l'articulation, sans rotation (la posture est déjà dans
    les cubes), emboîtés comme la posture (bassin > jambes, torse > tête, épaules, bras > avant-bras > marteau).
    Les pièces incandescentes de chaque os vont dans un sous-groupe « glow_<os> » : pleine luminosité dans
    BetterModel, et c'est lui qu'on enfonce dans le corps pour éteindre le fourneau et les yeux à la mort."""
    def group(name, origin):
        return {"name": name, "origin": [round(v, 4) for v in origin], "color": 0, "uuid": uid("group/" + name),
                "export": True, "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True,
                "autouv": 0, "rotation": [0, 0, 0], "children": []}
    index, pivots = {}, {}
    for b, e in zip(baked, elements):
        n = b["c"]["node"]
        pivots[n] = b["pivot"]
        if n not in index:
            index[n] = group(n, b["pivot"])
        target = index[n]
        if b["c"]["mat"] in GLOW_MATS:
            gname = "glow_" + n
            if gname not in index:
                index[gname] = group(gname, b["pivot"])
                index[n]["children"].append(index[gname])
            target = index[gname]
        target["children"].append(e["uuid"])
    roots = []
    for n, node in g.nodes.items():
        if n not in index:
            continue
        (index[node["parent"]]["children"] if node["parent"] else roots).append(index[n])
    return roots


PULSE_TICKS = 36   # fourneau : 2 images interpolées de 1,8 s chacune, soit une pulsation de 3,6 s (durée de l'idle)


def pulse(glow):
    """Texture animée du métal en fusion : l'image normale puis une image à peine plus sombre (chaque teinte
    descend d'un quart vers la suivante de la rampe), interpolées par le client : pulsation douce, sans clignoter."""
    ramp = [G0, G1, G2, G3, G4]
    down = {c: ramp[max(0, i - 1)] for i, c in enumerate(ramp)}
    dim = []
    for row in glow:
        out = []
        for c in row:
            if c[3] and tuple(c[:3]) in down:
                d = down[tuple(c[:3])]
                out.append(tuple(int(a + (b - a) * 0.25) for a, b in zip(c[:3], d)) + (255,))
            else:
                out.append(c)
        dim.append(out)
    return glow + dim


def export(g, baked, body, glow, anims):
    elements = []
    for i, b in enumerate(baked):
        e = {"name": b["c"]["name"], "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
             "allow_mirror_modeling": True, "from": [round(v, 4) for v in b["from"]],
             "to": [round(v, 4) for v in b["to"]], "autouv": 0, "color": 0,
             "origin": [round(v, 4) for v in b["origin"]], "faces": b["faces"], "type": "cube",
             "uuid": uid(f"el/{b['c']['node']}/{b['c']['name']}/{i}")}
        r = euler(b["M"])
        if any(abs(v) > 1e-3 for v in r):
            e["rotation"] = r
        elements.append(e)

    def tex(i, name, px, w, mode, frames=1, frame_time=1):
        return {"path": "", "name": name, "folder": "", "namespace": "", "id": str(i), "width": w,
                "height": w * frames,
                "uv_width": w, "uv_height": w, "particle": i == 0, "use_as_default": False, "layers_enabled": False,
                "sync_to_project": "", "render_mode": mode, "render_sides": "auto", "frame_time": frame_time,
                "frame_order_type": "loop", "frame_order": "", "frame_interpolate": frames > 1, "visible": True,
                "internal": True, "saved": False, "uuid": uid("texture/" + name),
                "source": "data:image/png;base64," + base64.b64encode(encode(w, len(px), px)).decode()}

    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": IDENT, "model_identifier": IDENT, "visible_box": [2, 3, 0], "variable_placeholders": "",
            "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
            "resolution": {"width": TEX, "height": TEX}, "elements": elements,
            "outliner": [{"name": IDENT, "origin": [0, 0, 0], "color": 0, "uuid": uid("group"), "export": True,
                          "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True, "autouv": 0,
                          "rotation": [0, 0, 0], "children": groups(g, baked, elements)}],
            "textures": [tex(0, IDENT + ".png", body, TEX, "default"),
                         tex(1, IDENT + "_fusion.png", pulse(glow), GTEX, "emissive", 2, PULSE_TICKS)],
            "animations": anims}


def apercu(data, body, glow, path, size=760, yaw=28, pitch=14):
    """Vue trois-quarts avant légèrement plongeante, fond neutre. Le métal en fusion n'est pas assombri par
    l'orientation des faces (il émet sa propre lumière)."""
    import apercu as A
    combo = [body[y] + (glow[y] if y < GTEX else [(0, 0, 0, 0)] * GTEX) for y in range(TEX)]
    els = []
    for e in data["elements"]:
        e = json.loads(json.dumps(e))
        for f in e["faces"].values():
            if f["texture"] == 1:
                f["uv"] = [f["uv"][0] + TEX, f["uv"][1], f["uv"][2] + TEX, f["uv"][3]]
                f["texture"] = 0
        els.append(e)
    bg = (52, 50, 54, 255)
    img = A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=60)
    saved = dict(A.SHADE)
    A.SHADE.update(dict.fromkeys(A.SHADE, 1.0))
    try:
        lit = A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=60)
    finally:
        A.SHADE.update(saved)
    hot = {G0, G1, G2, G3, G4}
    for y in range(size):
        for x in range(size):
            if tuple(lit[y][x][:3]) in hot:
                img[y][x] = lit[y][x]
    write(path, size, size, img)


# --------------------------------------------------------------------------- animations
# Rotation d'animation appliquée par Blockbench/BetterModel en (-x, -y, z) autour du pivot de l'os, dans les axes
# du modèle (les os n'ont pas de rotation propre) : x négatif lève un bras vers l'avant, x positif penche le torse
# vers l'avant ; position en (-x, y, z). Les réglages ci-dessous sont calés pour que rien ne passe sous le sol.
ATK_ARM = -50        # bras du marteau à l'impact
ATK_HAMMER = 28       # marteau à l'impact (tête au ras du sol)


def animations(data):
    known = set()

    def walk_groups(n):
        for c in n:
            if isinstance(c, dict):
                known.add(c["name"])
                walk_groups(c["children"])
    walk_groups(data["outliner"])
    n = [0]

    def anim(name, length, loop, tracks):
        animators = {}
        for bone, channels in tracks.items():
            assert bone in known, bone
            frames = []
            for channel, pts in channels.items():
                for p in pts:
                    t, (x, y, z) = p[0], p[1]
                    n[0] += 1
                    frames.append({"channel": channel, "data_points": [{"x": f"{x:.3f}", "y": f"{y:.3f}",
                                                                        "z": f"{z:.3f}"}],
                                   "uuid": uid(f"kf/{name}/{n[0]}"), "time": round(t, 4), "color": -1,
                                   "interpolation": p[2] if len(p) > 2 else "catmullrom"})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        return {"uuid": uid("anim/" + name), "name": name, "loop": loop, "override": False, "length": length,
                "snapping": 20, "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "",
                "loop_delay": "", "animators": animators}

    def wave(length, steps, f):
        return [(round(length * i / steps, 4), f(i / steps)) for i in range(steps + 1)]

    S = lambda p, ph=0.0: math.sin(2 * math.pi * (p + ph))  # noqa: E731
    Z = (0, 0, 0)
    out = []

    # ------------------------------------------------------------------ idle : machine au repos, 3,6 s
    L = 3.6
    out.append(anim("idle", L, "loop", {
        # respiration mécanique : le torse monte et se redresse à peine
        "torse": {"position": wave(L, 12, lambda p: (0, 0.35 * (1 - math.cos(2 * math.pi * p)) / 2, 0)),
                  "rotation": wave(L, 12, lambda p: (-0.8 * (1 - math.cos(2 * math.pi * p)) / 2, 0, 0))},
        # épaules décalées entre elles
        "epaule_d": {"rotation": wave(L, 12, lambda p: (0, 0, 1.4 * S(p, 0.08)))},
        "epaule_g": {"rotation": wave(L, 12, lambda p: (0, 0, -1.1 * S(p, 0.3)))},
        # petite inclinaison de tête vers le milieu du cycle, puis retour
        "tete": {"rotation": [(0, Z), (1.1, Z), (1.6, (2.5, -4, 3)), (2.4, (2.5, -4, 3)), (3.0, Z), (L, Z)]},
        # les bras pèsent : oscillations lentes, en retard sur le torse
        "bras_d": {"rotation": wave(L, 12, lambda p: (0.8 * S(p, 0.15), 0, 0.6 * S(p, 0.15)))},
        "bras_g": {"rotation": wave(L, 12, lambda p: (-1.2 * S(p, 0.22), 0, -0.8 * S(p, 0.22)))},
        "avant_bras_g": {"rotation": wave(L, 12, lambda p: (-1.0 * S(p, 0.3), 0, 0))},
        "marteau": {"rotation": wave(L, 12, lambda p: (0.8 * S(p, 0.3), 0, 0))},
    }))

    # ------------------------------------------------------------------ walk : pas courts et lourds, 1,8 s
    L = 1.8
    STEP, LIFT = 13, 20
    t = {}
    for side, ph in (("d", 0.0), ("g", 0.5)):
        leg = lambda p, ph=ph: -STEP * S(p, ph)  # noqa: E731
        # le genou ne plie que pendant que la jambe revient vers l'avant (pied levé), puis le pied se repose
        knee = lambda p, ph=ph: LIFT * max(0.0, math.cos(2 * math.pi * (p + ph))) ** 1.5  # noqa: E731
        t[f"cuisse_{side}"] = {"rotation": wave(L, 16, lambda p, l=leg: (l(p), 0, 0))}
        t[f"tibia_{side}"] = {"rotation": wave(L, 16, lambda p, k=knee: (k(p), 0, 0))}
        t[f"pied_{side}"] = {"rotation": wave(L, 16, lambda p, l=leg, k=knee: (-(l(p) + k(p)), 0, 0))}
    # le torse encaisse chaque appui (petite chute), roule vers la jambe qui avance, tourne à peine
    t["torse"] = {"position": wave(L, 16, lambda p: (0, -0.7 * abs(S(p, 0.25)) ** 0.5 + 0.35, 0)),
                  "rotation": wave(L, 16, lambda p: (2, 2.5 * S(p), -3 * S(p)))}
    t["tete"] = {"rotation": wave(L, 16, lambda p: (-1.5 + 1.2 * abs(S(p, 0.25)), -2 * S(p), 2.5 * S(p)))}
    t["epaule_d"] = {"rotation": wave(L, 16, lambda p: (0, 0, 1.5 * S(p, 0.1)))}
    t["epaule_g"] = {"rotation": wave(L, 16, lambda p: (0, 0, 1.5 * S(p, 0.1)))}
    # bras opposés aux jambes ; celui du marteau plus stable, le marteau traîne derrière le mouvement
    t["bras_d"] = {"rotation": wave(L, 16, lambda p: (4 * S(p, 0.5), 0, 0))}
    t["marteau"] = {"rotation": wave(L, 16, lambda p: (3 * S(p, 0.4), 0, 0))}
    t["bras_g"] = {"rotation": wave(L, 16, lambda p: (-10 * S(p, 0.5), 0, 0))}
    t["avant_bras_g"] = {"rotation": wave(L, 16, lambda p: (-6 * S(p, 0.38) - 4, 0, 0))}
    out.append(anim("walk", L, "loop", t))

    # ------------------------------------------------------------------ attack : préparation, frappe, impact, 1,4 s
    # 0 → 0,5 s préparation lente ; 0,55 s sommet ; 0,8 s impact (frappe en 0,25 s) ; retour jusqu'à 1,4 s
    A, H = ATK_ARM, ATK_HAMMER
    lin = "linear"
    out.append(anim("attack", 1.4, "once", {
        "torse": {"rotation": [(0, Z), (0.5, (-9, 18, 4)), (0.55, (-10, 19, 4), lin), (0.8, (20, -8, -2), lin),
                               (0.88, (24, -9, -2)), (1.05, (19, -7, -1)), (1.4, Z)],
                  "position": [(0, Z), (0.5, (0, 0.4, 0.5)), (0.55, (0, 0.4, 0.5), lin), (0.8, (0, -1.2, -0.6), lin),
                               (0.88, (0, -1.6, -0.8)), (1.05, (0, -1.0, -0.5)), (1.4, Z)]},
        "tete": {"rotation": [(0, Z), (0.5, (-8, -6, 0)), (0.8, (8, 4, 0)), (0.88, (12, 4, 0)), (1.4, Z)]},
        "epaule_d": {"rotation": [(0, Z), (0.5, (0, 0, 7)), (0.55, (0, 0, 7), lin), (0.8, (0, 0, -6), lin),
                                  (0.9, (0, 0, -8)), (1.4, Z)]},
        "epaule_g": {"rotation": [(0, Z), (0.5, (0, 0, 3)), (0.8, (0, 0, 5)), (0.9, (0, 0, 7)), (1.4, Z)]},
        "bras_d": {"rotation": [(0, Z), (0.25, (-55, 0, 6)), (0.5, (-150, 0, 12)), (0.55, (-154, 0, 12), lin),
                                (0.8, (A, 0, -4), lin), (0.9, (A + 4, 0, -4)), (1.05, (A + 2, 0, -3)), (1.4, Z)]},
        "avant_bras_d": {"rotation": [(0, Z), (0.5, (-25, 0, 0)), (0.55, (-25, 0, 0), lin), (0.8, (-4, 0, 0), lin),
                                      (1.05, (-4, 0, 0)), (1.4, Z)]},
        "marteau": {"rotation": [(0, Z), (0.5, (12, 0, 0)), (0.55, (18, 0, 0), lin), (0.8, (H, 0, 0), lin),
                                 (1.05, (H, 0, 0)), (1.4, Z)]},
        "bras_g": {"rotation": [(0, Z), (0.5, (-22, 0, -14)), (0.8, (14, 0, -8)), (0.95, (12, 0, -8)), (1.4, Z)]},
        "avant_bras_g": {"rotation": [(0, Z), (0.5, (-30, 0, 0)), (0.8, (-10, 0, 0)), (1.4, Z)]},
        # appui : le poids recule sur la jambe gauche à la préparation, revient sur la droite à l'impact
        "cuisse_g": {"rotation": [(0, Z), (0.5, (5, 0, 0)), (0.8, (2, 0, 0)), (1.4, Z)]},
        "tibia_g": {"rotation": [(0, Z), (0.5, (8, 0, 0)), (0.8, (3, 0, 0)), (1.4, Z)]},
        "pied_g": {"rotation": [(0, Z), (0.5, (-13, 0, 0)), (0.8, (-5, 0, 0)), (1.4, Z)]},
        "cuisse_d": {"rotation": [(0, Z), (0.5, (2, 0, 0)), (0.8, (-7, 0, 0)), (1.05, (-7, 0, 0)), (1.4, Z)]},
        "tibia_d": {"rotation": [(0, Z), (0.5, (2, 0, 0)), (0.8, (10, 0, 0)), (1.05, (10, 0, 0)), (1.4, Z)]},
        "pied_d": {"rotation": [(0, Z), (0.5, (-4, 0, 0)), (0.8, (-3, 0, 0)), (1.05, (-3, 0, 0)), (1.4, Z)]},
    }))

    # ------------------------------------------------------------------ hurt : secousse contenue, 0,4 s
    out.append(anim("hurt", 0.4, "once", {
        "torse": {"rotation": [(0, Z), (0.07, (-7, 2, -2)), (0.17, (-4, 1, -1)), (0.4, Z)],
                  "position": [(0, Z), (0.07, (0, 0.2, 0.7)), (0.4, Z)]},
        "tete": {"rotation": [(0, Z), (0.08, (-6, 3, 4)), (0.22, (-2, 1, 1)), (0.4, Z)]},
        "epaule_d": {"rotation": [(0, Z), (0.07, (0, 0, 4)), (0.4, Z)]},
        "epaule_g": {"rotation": [(0, Z), (0.07, (0, 0, -3)), (0.4, Z)]},
        "bras_g": {"rotation": [(0, Z), (0.1, (-12, 0, -6)), (0.4, Z)]},
        "bras_d": {"rotation": [(0, Z), (0.1, (4, 0, 3)), (0.4, Z)]},
        "marteau": {"rotation": [(0, Z), (0.14, (-5, 0, 0)), (0.4, Z)]},
    }))

    return out


def lowest(data, animation, t, prefix=None):
    """Point le plus bas (y) du modèle animé à l'instant t (seulement les os dont le nom commence par prefix)."""
    from apercu_anim import posed_elements
    low = 1e9
    names = {}

    def scan(n, owner):
        for c in n:
            if isinstance(c, dict):
                scan(c["children"], c["name"] if owner is None or not c["name"].startswith("glow_") else owner)
            else:
                names[c] = owner
    scan(data["outliner"][0]["children"], None)
    for e in posed_elements(data, animation, t):
        if prefix and not names.get(e["uuid"], "").startswith(prefix):
            continue
        for cx in (e["from"][0], e["to"][0]):
            for cy in (e["from"][1], e["to"][1]):
                for cz in (e["from"][2], e["to"][2]):
                    p = app(e["_M"], [cx - e["origin"][0], cy - e["origin"][1], cz - e["origin"][2]])
                    low = min(low, p[1] + e["_q"][1])
    return low


def strips(data, body, glow, folder, count=8):
    """Une bande PNG par animation : profil et trois-quarts, cadrage fixe, ligne de sol."""
    import apercu as A
    from apercu_anim import posed_elements
    combo = [body[y] + (glow[y] if y < GTEX else [(0, 0, 0, 0)] * GTEX) for y in range(TEX)]
    flat = json.loads(json.dumps(data))
    for e in flat["elements"]:
        for f in e["faces"].values():
            if f["texture"] == 1:
                f["uv"] = [f["uv"][0] + TEX, f["uv"][1], f["uv"][2] + TEX, f["uv"][3]]
                f["texture"] = 0
    os.makedirs(folder, exist_ok=True)
    s = 200
    for a in flat["animations"]:
        frames = [posed_elements(flat, a["name"], a["length"] * i / count) for i in range(count + 1)]
        pts = []
        for fe in frames:
            for e in fe:
                for cx in (e["from"][0], e["to"][0]):
                    for cy in (e["from"][1], e["to"][1]):
                        for cz in (e["from"][2], e["to"][2]):
                            p = app(e["_M"], [cx - e["origin"][0], cy - e["origin"][1], cz - e["origin"][2]])
                            pts.append([p[i] + e["_q"][i] for i in range(3)])
        imgs = [A.render(fe, combo, yaw, 0 if yaw == 90 else 12, size=s, frame_points=pts)
                for fe in frames for yaw in (90, 28)]
        write(os.path.join(folder, f"anim_{a['name']}.png"), s * len(imgs), s,
              [sum((v[y] for v in imgs), []) for y in range(s)])
        print(f"{a['name']} : point le plus bas y = {min(p[1] for p in pts):.2f}")


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    for flag in ("--apercu", "--anims"):
        if flag in args:
            i = args.index(flag)
            if flag == "--apercu":
                prev = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    g, baked, offs = solve()
    print("ajustement des pieds :", {k: round(v, 2) for k, v in offs.items()})
    body, glow = bake_textures(baked)
    data = export(g, baked, body, glow, [])
    data["animations"] = animations(data)
    top = max(p[1] for b in baked for p in corners(b))
    print(f"hauteur : {top / 16:.2f} blocs, {len(data['elements'])} cubes")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    write(os.path.join(out, IDENT + ".png"), TEX, TEX, body)
    write(os.path.join(out, IDENT + "_fusion.png"), GTEX, GTEX, glow)
    print("écrit", path)
    if prev:
        apercu(data, body, glow, prev)
    if "--anims" in sys.argv:
        strips(data, body, glow, sys.argv[sys.argv.index("--anims") + 1])
