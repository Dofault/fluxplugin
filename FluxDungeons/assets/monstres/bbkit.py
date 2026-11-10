#!/usr/bin/env python3
"""Outils communs des modèles générés par script (personnages et monstres) : pièces, mise au sol, atlas de textures
(une texture normale et une texture émissive), export Blockbench, aperçu sur fond neutre.

Une pièce est un tuple (groupe, nom, centre, taille, rotation, matériau) ; la rotation est (x, y, z) en degrés
(convention Blockbench) ou une matrice 3 x 3 (pièce tournée avec son parent, cf. `attach`). Les peintres de matériaux
reçoivent (largeur, hauteur, aléa, face) et rendent une grille de couleurs RGB.
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
from golem_fonderie import app, euler, mul, rot  # noqa: E402


def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


def tone(pal, i):
    return pal[max(0, min(len(pal) - 1, i))]


LIGHT = {"up": 1, "north": 0, "south": 0, "east": 0, "west": 0, "down": -2}


def matrix(r):
    return r if isinstance(r[0], (list, tuple)) else rot(*r)


def attach(pivot, R):
    """Fonction qui place une pièce dessinée droite dans le repère d'un parent tourné de R autour de pivot."""
    def place(c, r):
        d = app(R, [c[i] - pivot[i] for i in range(3)])
        return [d[i] + pivot[i] for i in range(3)], mul(R, matrix(r))
    return place


def corners(c, size, r):
    M = matrix(r)
    out = []
    for sx in (-0.5, 0.5):
        for sy in (-0.5, 0.5):
            for sz in (-0.5, 0.5):
                p = app(M, [sx * size[0], sy * size[1], sz * size[2]])
                out.append([p[i] + c[i] for i in range(3)])
    return out


def ground(parts, groups, scale=1.0, center=True):
    """Pose le modèle au sol (y = 0) ; center=True le centre aussi en x et z sur sa boîte englobante (un monstre),
    center=False garde l'origine dessinée (un personnage : origine entre ses pieds). Renvoie pièces (avec taille de
    dessin), groupes, dimensions en blocs."""
    pts = [p for g, n, c, size, r, m in parts for p in corners(c, size, r)]
    y0 = min(p[1] for p in pts)
    cx = (max(p[0] for p in pts) + min(p[0] for p in pts)) / 2 if center else 0.0
    cz = (max(p[2] for p in pts) + min(p[2] for p in pts)) / 2 if center else 0.0
    k = scale
    out = [(g, n, [(c[0] - cx) * k, (c[1] - y0) * k, (c[2] - cz) * k], [v * k for v in size], r, m, size)
           for g, n, c, size, r, m in parts]
    grp = {name: ([(pv[0] - cx) * k, (pv[1] - y0) * k, (pv[2] - cz) * k], parent) for name, (pv, parent) in groups.items()}
    dims = [(max(p[i] for p in pts) - min(p[i] for p in pts)) * k / 16 for i in range(3)]
    return out, grp, dims


def bake(P, mats, glow_mats, seed=4417):
    """Deux atlas (normal, émissif) à 1 texel par unité de dessin ; un matériau peut demander une densité plus fine
    en se déclarant (peintre, densité)."""
    def painter(m):
        p = (glow_mats if m in glow_mats else mats)[m]
        return p if isinstance(p, tuple) else (p, 1)
    reqs = []
    for i, (g, n, c, size, r, m, raw) in enumerate(P):
        dx, dy, dz = raw
        dens = painter(m)[1]
        for d, (fw, fh) in {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
                            "up": (dx, dz), "down": (dx, dz)}.items():
            reqs.append((i, d, fw * dens, fh * dens, m, 1 if m in glow_mats else 0))
    uv, atlases, sizes = {}, {}, {}
    for t in (0, 1):
        mine = sorted((q for q in reqs if q[5] == t), key=lambda q: (-math.ceil(q[3]), -math.ceil(q[2])))
        for size in (16, 32, 64, 128, 256, 512):
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
        rnd = random.Random(seed + t)
        for (i, d, fw, fh, m, _), (x, y, w, h) in zip(mine, place):
            img = painter(m)[0](w, h, random.Random(rnd.random()), d)
            for j in range(h):
                for k in range(w):
                    atlas[y + j][x + k] = tuple(img[j][k]) + (255,)
            uv[(i, d)] = {"uv": [x, y, round(x + fw, 3), round(y + fh, 3)], "texture": t}
        atlases[t], sizes[t] = atlas, size
    return uv, atlases, sizes


def export(ident, ns, P, groups, uv, atlases, sizes, animations=()):
    def uid(name):
        return str(uuid.uuid5(ns, f"{ident}/{name}"))
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
            "name": ident, "model_identifier": ident, "visible_box": [2, 3, 0], "variable_placeholders": "",
            "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
            "resolution": {"width": sizes[0], "height": sizes[0]}, "elements": elements,
            "outliner": [{"name": ident, "origin": [0, 0, 0], "color": 0, "uuid": uid("group"), "export": True,
                          "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True, "autouv": 0,
                          "rotation": [0, 0, 0], "children": roots}],
            "textures": [tex(0, ident + ".png", atlases[0], sizes[0], "default"),
                         tex(1, ident + "_lumiere.png", atlases[1], sizes[1], "emissive")],
            "animations": list(animations)}


def flat_elements(data, sizes):
    """Éléments avec la texture émissive placée à droite de la normale (une seule image pour l'aperçu)."""
    W0 = sizes[0]
    els = []
    for e in data["elements"]:
        e = json.loads(json.dumps(e))
        for f in e["faces"].values():
            if f["texture"] == 1:
                f["uv"] = [f["uv"][0] + W0, f["uv"][1], f["uv"][2] + W0, f["uv"][3]]
                f["texture"] = 0
        els.append(e)
    return els


def combined(atlases, sizes):
    W0 = sizes[0]
    return [atlases[0][y] + (atlases[1][y] if y < sizes[1] else [(0, 0, 0, 0)] * sizes[1]) for y in range(W0)]


def apercu(data, atlases, sizes, path, glow_colors, views=((30, 10), (0, 4), (90, 2), (210, 12)), size=360,
           silhouette=True):
    """Planche sur fond neutre, vues demandées puis la silhouette en noir ; les pixels émissifs ne sont pas assombris."""
    import paths  # noqa: F401,E402  (scripts/influcard on the path)
    import apercu as A
    els, combo = flat_elements(data, sizes), combined(atlases, sizes)
    bg = (58, 58, 62, 255)
    imgs = []
    for yaw, pitch in views:
        img = A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=24)
        saved = dict(A.SHADE)
        A.SHADE.update(dict.fromkeys(A.SHADE, 1.0))
        try:
            lit = A.render(els, combo, yaw, pitch, size=size, bg=bg, margin=24)
        finally:
            A.SHADE.update(saved)
        for y in range(size):
            for x in range(size):
                if tuple(lit[y][x][:3]) in glow_colors:
                    img[y][x] = lit[y][x]
        imgs.append(img)
    if silhouette:
        imgs.append([[(20, 20, 22, 255) if px != bg else (205, 205, 210, 255) for px in row] for row in imgs[0]])
    write(path, size * len(imgs), size, [sum((im[y] for im in imgs), []) for y in range(size)])
