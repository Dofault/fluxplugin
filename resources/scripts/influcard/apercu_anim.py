#!/usr/bin/env python3
"""Aperçu d'une animation de familier, image par image (sans dépendance), pour vérifier un mouvement avant de
le livrer : sens des rotations, pieds qui restent au sol, amplitude des foulées.

Usage : apercu_anim.py <dossier de sortie> <id> [animation] [images] [vue]
  animation : walk par défaut ; images : 8 ; vue : profil (défaut) ou 3/4.
Rend le modèle tel qu'il est livré (familiers_design + familiers_sol.poser), une bande PNG <id>_<animation>.png
avec une ligne au niveau du sol (y = 0) ; cadrage fixe sur toute l'animation.

Conventions Blockbench (repris par BetterModel) : rotation d'animation appliquée en (-x, -y, z), position en
(-x, y, z), autour de l'origine de l'os, os enfants transformés par leurs parents.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apercu import BG, apply, rot_matrix, render  # noqa: E402
from atelier import Atelier  # noqa: E402
from familiers_design import DESIGNS  # noqa: E402
from familiers_sol import poser  # noqa: E402
from png_util import write  # noqa: E402

SIZE = 200
GROUND = (240, 90, 90, 255)


def mul(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def sample(keyframes, channel, t):
    """Valeur interpolée (linéaire) d'un canal à l'instant t, None si le canal n'a pas d'image clé."""
    pts = sorted(((k["time"], [float(k["data_points"][0][c]) for c in "xyz"]) for k in keyframes
                  if k["channel"] == channel), key=lambda p: p[0])
    if not pts:
        return None
    if t <= pts[0][0]:
        return pts[0][1]
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        if t0 <= t <= t1:
            f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return [a + (b - a) * f for a, b in zip(v0, v1)]
    return pts[-1][1]


def posed_elements(model, animation, t):
    """Éléments du modèle à l'instant t : chaque cube reçoit sa matrice et son pivot finaux (_M, _q)."""
    anim = next(a for a in model["animations"] if a["name"] == animation)
    frames = {an["name"]: an["keyframes"] for an in anim["animators"].values()}
    by_uuid = {e["uuid"]: e for e in model["elements"]}
    out = []

    def walk(group, B, c):
        o = group["origin"]
        kf = frames.get(group["name"], [])
        r = sample(kf, "rotation", t) or [0, 0, 0]
        p = sample(kf, "position", t) or [0, 0, 0]
        R = rot_matrix(-r[0], -r[1], r[2])
        # point local x -> B * (o + pos + R (x - o)) + c
        Bn = mul(B, R)
        shift = apply(B, [o[i] + [-p[0], p[1], p[2]][i] for i in range(3)])
        cn = [shift[i] + c[i] - apply(Bn, o)[i] for i in range(3)]
        for child in group.get("children", []):
            if isinstance(child, dict):
                walk(child, Bn, cn)
            elif child in by_uuid:
                e = dict(by_uuid[child])
                M = rot_matrix(*(e.get("rotation") or [0, 0, 0]))
                eo = e["origin"]
                # x -> Bn (eo + M (x - eo)) + cn = (Bn M)(x - eo) + Bn eo + cn
                e["_M"] = mul(Bn, M)
                e["_q"] = [apply(Bn, eo)[i] + cn[i] for i in range(3)]
                out.append(e)

    identity = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for g in model["outliner"]:
        if isinstance(g, dict):
            walk(g, identity, [0, 0, 0])
    return out


def strip(pid, animation="walk", count=8, view="profil"):
    a = Atelier("familier_" + pid)
    DESIGNS[pid](a)
    model = poser(a.export(), pid)
    length = next(x["length"] for x in model["animations"] if x["name"] == animation)
    yaw, pitch = (90, 0) if view == "profil" else (35, 15)
    frames = [posed_elements(model, animation, length * i / count) for i in range(count)]
    # cadrage commun à toutes les images, sol compris
    lows = []
    for els in frames:
        for e in els:
            for cx in (e["from"][0], e["to"][0]):
                for cy in (e["from"][1], e["to"][1]):
                    for cz in (e["from"][2], e["to"][2]):
                        p = apply(e["_M"], [cx - e["origin"][0], cy - e["origin"][1], cz - e["origin"][2]])
                        lows.append([p[i] + e["_q"][i] for i in range(3)])
    images = [render(els, a.px, yaw, pitch, size=SIZE, frame_points=lows) for els in frames]
    lowest = min(p[1] for p in lows)
    return images, lowest


if __name__ == "__main__":
    out, pid = sys.argv[1], sys.argv[2]
    animation = sys.argv[3] if len(sys.argv) > 3 else "walk"
    count = int(sys.argv[4]) if len(sys.argv) > 4 else 8
    view = sys.argv[5] if len(sys.argv) > 5 else "profil"
    os.makedirs(out, exist_ok=True)
    images, lowest = strip(pid, animation, count, view)
    rows = [sum((img[y] for img in images), []) for y in range(SIZE)]
    path = os.path.join(out, f"{pid}_{animation}.png")
    write(path, SIZE * len(images), SIZE, rows)
    print(f"{path} : point le plus bas y = {lowest:.2f} (0 = sol)")
