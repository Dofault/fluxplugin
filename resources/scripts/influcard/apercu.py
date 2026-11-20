#!/usr/bin/env python3
"""Aperçu 3D d'un ou plusieurs familiers (sans dépendance) : une planche PNG par familier, 4 vues
(trois quarts avant gauche, profil, trois quarts arrière, dessus), rendu orthographique par lancer de rayons
avec les rotations des cubes. Sert à contrôler un modèle avant de le livrer, pas à l'illustration des cartes.

Usage : apercu.py <dossier de sortie> <id> [<id>...]   (id = fonction de familiers_design.py, ou « tous »)
        apercu.py <dossier de sortie> --planche <nom> <id> [<id>...]   (vue 3/4 + profil de chacun, une image)
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from atelier import Atelier  # noqa: E402
from familiers_design import DESIGNS  # noqa: E402
from png_util import write  # noqa: E402

SIZE = 220
VIEWS = [("3/4 avant", 35, 20), ("profil", 90, 8), ("3/4 arrière", 215, 20), ("dessus", 20, 70)]
BG = (46, 42, 56)
SHADE = {"up": 1.0, "down": 0.55, "north": 0.85, "south": 0.7, "east": 0.78, "west": 0.92}


def rot_matrix(rx, ry, rz):
    """Blockbench (three.js, ordre ZYX) : R = Rz * Ry * Rx."""
    x, y, z = (math.radians(v) for v in (rx, ry, rz))
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    Rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    Ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    Rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]

    def mul(A, B):
        return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return mul(Rz, mul(Ry, Rx))


def apply(M, v):
    return [sum(M[i][k] * v[k] for k in range(3)) for i in range(3)]


def transpose(M):
    return [[M[j][i] for j in range(3)] for i in range(3)]


def render(elements, px, yaw, pitch, size=SIZE, bg=BG + (255,), margin=16, frame_points=None):
    """Image size x size ; bg None = fond transparent (pixels None), margin = bord laissé autour.
    Un élément peut porter sa matrice et son pivot finaux (_M, _q : modèle animé, cf. apercu_anim) ;
    frame_points : points (monde) qui fixent le cadrage (même cadre pour toutes les images d'une animation),
    avec une ligne rouge au niveau du sol (y = 0) en vue de profil."""
    # caméra orthographique : direction de vue d (de l'œil vers la scène)
    yw, pt = math.radians(yaw), math.radians(pitch)
    # yaw 0 : œil au nord (face au familier, qui regarde -z), vers le sud
    d = [-math.sin(yw) * math.cos(pt), -math.sin(pt), math.cos(yw) * math.cos(pt)]
    right = [-math.cos(yw), 0, -math.sin(yw)]
    up = [d[1] * right[2] - d[2] * right[1], d[2] * right[0] - d[0] * right[2], d[0] * right[1] - d[1] * right[0]]
    if up[1] < 0:
        up = [-c for c in up]
    cubes = []
    for e in elements:
        if e["faces"]["north"].get("texture") is None:
            continue
        r = e.get("rotation") or [0, 0, 0]
        M = e.get("_M") or rot_matrix(*r)
        o = e["origin"]
        q = e.get("_q", o)
        corners = []
        for cx in (e["from"][0], e["to"][0]):
            for cy in (e["from"][1], e["to"][1]):
                for cz in (e["from"][2], e["to"][2]):
                    p = apply(M, [cx - o[0], cy - o[1], cz - o[2]])
                    p = [p[i] + q[i] for i in range(3)]
                    corners.append((sum(p[i] * right[i] for i in range(3)), sum(p[i] * up[i] for i in range(3))))
        cubes.append((e, M, transpose(M), (o, q), apply(transpose(M), d), corners))
    # cadrage : projection des coins (ou des points imposés)
    pts = [c for cube in cubes for c in cube[5]]
    if frame_points:
        pts = [(sum(p[i] * right[i] for i in range(3)), sum(p[i] * up[i] for i in range(3)))
               for p in frame_points + [[0, 0, 0]]]
    minu, maxu = min(p[0] for p in pts), max(p[0] for p in pts)
    minv, maxv = min(p[1] for p in pts), max(p[1] for p in pts)
    scale = (size - margin) / max(maxu - minu, maxv - minv, 1)
    cu, cv = (minu + maxu) / 2, (minv + maxv) / 2
    out = [[bg] * size for _ in range(size)]
    far = 200.0
    # rectangle d'écran de chaque cube (pixels) : seuls les cubes qui couvrent le pixel sont testés
    boxes = []
    for cube in cubes:
        us, vs = [c[0] for c in cube[5]], [c[1] for c in cube[5]]
        boxes.append((cube, (min(us) - cu) * scale + size / 2 - 1, (max(us) - cu) * scale + size / 2 + 1,
                       (cv - max(vs)) * scale + size / 2 - 1, (cv - min(vs)) * scale + size / 2 + 1))
    for py in range(size):
        row = [b for b in boxes if b[3] <= py <= b[4]]
        for qx in range(size):
            u = cu + (qx + 0.5 - size / 2) / scale
            v = cv - (py + 0.5 - size / 2) / scale
            origin = [right[i] * u + up[i] * v - d[i] * far for i in range(3)]
            best, col = None, None
            for (e, M, Mt, (o, q), rd, _), x_lo, x_hi, _, _ in row:
                if not x_lo <= qx <= x_hi:
                    continue
                ro = apply(Mt, [origin[i] - q[i] for i in range(3)])
                ro = [ro[i] + o[i] for i in range(3)]
                t0, t1, face = -1e9, 1e9, None
                ok = True
                for ax, (neg, pos) in enumerate((("west", "east"), ("down", "up"), ("north", "south"))):
                    lo, hi = e["from"][ax], e["to"][ax]
                    if abs(rd[ax]) < 1e-9:
                        if ro[ax] < lo or ro[ax] > hi:
                            ok = False
                            break
                        continue
                    ta, tb = (lo - ro[ax]) / rd[ax], (hi - ro[ax]) / rd[ax]
                    fa = neg if rd[ax] > 0 else pos
                    if ta > tb:
                        ta, tb = tb, ta
                    if ta > t0:
                        t0, face = ta, fa
                    t1 = min(t1, tb)
                    if t0 > t1:
                        ok = False
                        break
                if not ok or face is None or (best is not None and t0 >= best):
                    continue
                hit = [ro[i] + rd[i] * t0 for i in range(3)]
                (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]

                def fr(a, lo, hi):
                    return 0.5 if hi - lo < 1e-6 else min(0.999, max(0.0, (a - lo) / (hi - lo)))
                x, y, z = hit
                if face == "north":
                    s, t = 1 - fr(x, x0, x1), 1 - fr(y, y0, y1)
                elif face == "south":
                    s, t = fr(x, x0, x1), 1 - fr(y, y0, y1)
                elif face == "east":
                    s, t = 1 - fr(z, z0, z1), 1 - fr(y, y0, y1)
                elif face == "west":
                    s, t = fr(z, z0, z1), 1 - fr(y, y0, y1)
                elif face == "up":
                    s, t = fr(x, x0, x1), fr(z, z0, z1)
                else:
                    s, t = fr(x, x0, x1), 1 - fr(z, z0, z1)
                u0, v0, u1, v1 = e["faces"][face]["uv"]
                tu = int(u0 + min(0.999, s) * (u1 - u0))
                tv = int(v0 + min(0.999, t) * (v1 - v0))
                c = px[tv][tu]
                if c[3] == 0:
                    continue
                k = SHADE[face]
                best, col = t0, tuple(min(255, int(c[i] * k)) for i in range(3)) + (255,)
            if col:
                out[py][qx] = col
            elif frame_points and pitch == 0 and abs(v) < 0.5 / scale:
                out[py][qx] = (240, 90, 90, 255)
    return out


def views(ident, n=len(VIEWS)):
    a = Atelier("familier_" + ident)
    DESIGNS[ident](a)
    data = a.export()
    return [render(data["elements"], a.px, yaw, pitch) for _, yaw, pitch in VIEWS[:n]]


def sheet(ident, path):
    vs = views(ident)
    write(path, SIZE * len(vs), SIZE, [sum((v[y] for v in vs), []) for y in range(SIZE)])


def planche(ids, path, cols=3):
    """Plusieurs familiers sur une image : vue 3/4 avant + profil pour chacun."""
    cells = [views(i, 2) for i in ids]
    blank = [[BG + (255,)] * SIZE for _ in range(SIZE)]
    rows = []
    for k in range(0, len(cells), cols):
        line = cells[k:k + cols] + [[blank, blank]] * (cols - len(cells[k:k + cols]))
        for y in range(SIZE):
            rows.append(sum((v[y] for c in line for v in c), []))
    write(path, SIZE * 2 * cols, len(rows), rows)


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    ids = sys.argv[2:]
    if ids and ids[0] == "--planche":  # apercu.py <sortie> --planche <nom> <id>... : une seule image
        planche(ids[2:], os.path.join(out, ids[1] + ".png"))
        sys.exit()
    if ids == ["tous"]:
        ids = [i for i in DESIGNS if i != "flammeche"]  # flammeche : modèle d'origine, pas de design
    for i in ids:
        sheet(i, os.path.join(out, i + ".png"))
        print("ok", i)
