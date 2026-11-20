#!/usr/bin/env python3
"""Chargement d'un .bbmodel pour les rendus d'apercu.py (portraits, cartes) : tous les modèles, pas seulement ceux
de l'atelier des familiers.

- les rotations des os (groupes de l'outliner, à toute profondeur) sont appliquées en plus de celle de chaque cube
  (champs `_M` / `_q` lus par apercu.render) ; l'atelier n'en pose pas, les boss de FluxDungeons si ;
- plusieurs textures (face["texture"] = numéro) : réunies dans une seule image, les UV de chaque face ramenés
  dans cette image ;
- cubes cachés (visibility false) et os de boîte de collision (`hitbox`) ignorés.
"""
import base64
import json
import os
import tempfile

from apercu import apply, rot_matrix
from png_util import read


def _mul(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _texture(entry):
    source = entry["source"].split(",", 1)[1]
    fd, tmp = tempfile.mkstemp(suffix=".png")
    with os.fdopen(fd, "wb") as f:
        f.write(base64.b64decode(source))
    try:
        w, h, px = read(tmp)
    finally:
        os.remove(tmp)
    return w, h, px


def load(path):
    """(éléments prêts pour apercu.render, texture réunie) d'un .bbmodel."""
    data = json.load(open(path, encoding="utf-8"))
    res = data.get("resolution") or {"width": 16, "height": 16}
    # textures empilées verticalement ; échelle UV -> pixels propre à chacune
    atlas, offsets = [], []
    width = 0
    for t in data["textures"]:
        w, h, px = _texture(t)
        uw = t.get("uv_width") or res["width"]
        uh = t.get("uv_height") or res["height"]
        offsets.append((len(atlas), w / uw, h / uh))
        atlas.extend([list(row) for row in px])
        width = max(width, w)
    for row in atlas:
        row.extend([(0, 0, 0, 0)] * (width - len(row)))

    groups = {g["uuid"]: g for g in data.get("groups", [])}
    parent_chain = {}

    def walk(node, chain):
        if isinstance(node, str):
            parent_chain[node] = chain
            return
        g = groups.get(node.get("uuid"), node)
        if g.get("visibility") is False or str(g.get("name", "")).lower() == "hitbox":
            chain = chain + [None]  # tout ce qui est dessous est caché
        else:
            chain = chain + [g]
        for child in node.get("children", []):
            walk(child, chain)

    for node in data.get("outliner", []):
        walk(node, [])

    out = []
    for e in data["elements"]:
        if e.get("visibility") is False or e.get("type", "cube") != "cube":
            continue
        chain = parent_chain.get(e.get("uuid"), [])
        if None in chain:
            continue
        e = json.loads(json.dumps(e))
        M = rot_matrix(*(e.get("rotation") or [0, 0, 0]))
        o = e.get("origin") or [0, 0, 0]
        q = list(o)
        for g in reversed(chain):  # de l'os le plus proche au plus lointain
            r = g.get("rotation") or [0, 0, 0]
            if any(r):
                R = rot_matrix(*r)
                go = g.get("origin") or [0, 0, 0]
                M = _mul(R, M)
                q = [v + go[i] for i, v in enumerate(apply(R, [q[k] - go[k] for k in range(3)]))]
        e["origin"] = o
        e["_M"], e["_q"] = M, q
        for face in e["faces"].values():
            idx = face.get("texture")
            if idx is None:
                continue
            oy, sx, sy = offsets[idx if idx < len(offsets) else 0]
            u0, v0, u1, v1 = face["uv"]
            face["uv"] = [u0 * sx, v0 * sy + oy, u1 * sx, v1 * sy + oy]
            face["texture"] = 0
        out.append(e)
    return out, atlas
