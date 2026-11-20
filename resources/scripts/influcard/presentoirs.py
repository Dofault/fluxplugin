#!/usr/bin/env python3
"""Présentoirs de boosters (meubles ItemsAdder, un par booster) pour les magasins.

Inspirés des boîtes de présentation des cartes à collectionner : bac en carton ouvert aux couleurs du booster,
deux rangées de paquets debout (vrais visuels des boosters, fluxpet:item/booster_<id>), une troisième dont
on ne voit que le haut, fronton imprimé à l'arrière (visuel du booster recadré) et bandeau sur l'avant.
Modèle Minecraft classique (format Java, 1 bloc), pas BetterModel : c'est un objet fixe.

Usage : presentoirs.py <dossier ItemsAdder/contents/fluxpet> [aperçu.png]
Écrit textures/item/presentoir/*.png, models/item/presentoir_<id>.json et presentoirs.yml (fichier à part :
gen_familiers.py réécrit config.yml, pas celui-ci). Puis /iazip. Objets : fluxpet:presentoir_<id>.
"""
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from png_util import read, write  # noqa: E402

IA = sys.argv[1]
BOOSTERS_YML = "/home/influencraft/serveurs/04_labs/plugins/InfluPet/content/boosters.yml"
NS = "fluxpet"
DISABLED = ("chimiste",)
# boosters qui ont un présentoir : les 4 paliers et un par carrière (metier_*), soit 15 (2026-10-09)
TIERS = ("ordinaire", "tendance", "virale", "phenomene")


def has_display(bid):
    return bid in TIERS or bid.startswith("metier_")
# couleur du bac pour les boosters dont le nom est en code couleur simple (&f...)
FALLBACK = {"ordinaire": "#C8CCD8"}


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c)


def mix(a, b, t):
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))


def carton(color):
    """16x16 : carton teinté, bord haut plus clair, rainures verticales légères."""
    base = shade(color, 0.62)
    px = []
    for y in range(16):
        row = []
        for x in range(16):
            c = base
            if y < 2:
                c = shade(color, 0.85)
            elif x % 5 == 0:
                c = shade(base, 0.9)
            if x in (0, 15) or y == 15:
                c = shade(base, 0.75)
            row.append(c + (255,))
        px.append(row)
    return px


def bandeau(color):
    """32x16 (format conseillé par ItemsAdder) : bandeau sur les 8 lignes du haut (sombre, liserés à la
    couleur du booster, trois étoiles dorées), le reste sombre."""
    dark, gold = (30, 26, 40), (255, 217, 77)
    px = [[(dark + (255,)) for _ in range(32)] for _ in range(16)]
    for x in range(32):
        px[0][x] = color + (255,)
        px[7][x] = shade(color, 0.7) + (255,)
    for cx in (8, 16, 24):
        for dx, dy in ((0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)):
            px[4 + dy][cx + dx] = gold + (255,)
    return px


def face(tex, uv, rotation=0):
    f = {"uv": uv, "texture": tex}
    if rotation:
        f["rotation"] = rotation
    return f


def box(frm, to, faces, rot=None):
    e = {"from": frm, "to": to, "faces": faces}
    if rot:
        e["rotation"] = rot
    return e


def all_faces(tex, uv=(0, 0, 16, 16)):
    return {d: face(tex, list(uv)) for d in ("north", "south", "east", "west", "up", "down")}


# paquet : face avant = visuel du booster (x 5..26 sur 32 -> u 2,5..13,5), côtés et dos = bord argenté
PACK_FRONT = [2.5, 0, 13.5, 16]
PACK_EDGE = [2.5, 0, 3.5, 16]


def pack(x0, y0, z0, w, h, lean):
    faces = {d: face("#booster", PACK_EDGE) for d in ("south", "east", "west", "up", "down")}
    faces["north"] = face("#booster", PACK_FRONT)
    return box([x0, y0, z0], [x0 + w, y0 + h, z0 + 0.5], faces,
               {"angle": lean, "axis": "x", "origin": [x0 + w / 2, y0, z0 + 0.25]})


def model(bid):
    els = []
    c = "#carton"
    # bac : fond, devant bas avec bandeau, côtés en escalier (bas devant, hauts derrière), dos
    # pièces jointives qui ne se recouvrent jamais : deux faces dans le même plan clignotent en jeu
    # (devant sur toute la largeur, côtés derrière lui, fond et dos entre les côtés)
    els.append(box([2, 0, 3], [14, 1, 13], all_faces(c)))
    front = all_faces(c)
    front["north"] = face("#bandeau", [0, 0, 16, 8])
    els.append(box([1, 0, 2], [15, 2.5, 3], front))
    for x0 in (1, 14):
        els.append(box([x0, 0, 3], [x0 + 1, 2.5, 14], all_faces(c)))
        els.append(box([x0, 2.5, 7], [x0 + 1, 6, 14], all_faces(c)))
        els.append(box([x0, 6, 10.5], [x0 + 1, 9, 14], all_faces(c)))
    els.append(box([2, 0, 13], [14, 9, 14], all_faces(c)))
    # fronton imprimé : visuel du booster recadré sur sa bande centrale
    header = all_faces(c)
    header["north"] = face("#booster", [2.5, 5, 13.5, 10.5])
    els.append(box([1, 9, 13.2], [15, 12, 13.8], header))
    # paquets en gradins, penchés vers l'arrière (face tournée vers le joueur) : chaque rangée dépasse de
    # celle de devant ; marches en carton sous les rangées du fond
    els.append(box([2, 1, 6.5], [14, 2.5, 13], all_faces(c)))
    els.append(box([2, 2.5, 9.5], [14, 4, 13], all_faces(c)))
    for y0, z0 in ((1, 3.6), (2.5, 6.6), (4, 9.6)):
        for i in range(3):
            els.append(pack(2.05 + i * 4, y0, z0, 3.8, 6, 22.5))
    return {"textures": {"booster": f"{NS}:item/booster_{bid}", "carton": f"{NS}:item/presentoir/carton_{bid}",
                         "bandeau": f"{NS}:item/presentoir/bandeau_{bid}", "particle": f"{NS}:item/presentoir/carton_{bid}"},
            "elements": els,
            "display": {"gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]},
                        "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
                        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                                                  "scale": [0.375, 0.375, 0.375]},
                        "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]}}}


def boosters():
    data = yaml.safe_load(open(BOOSTERS_YML, encoding="utf-8"))["boosters"]
    out = {}
    for bid, b in data.items():
        if any(x in bid for x in DISABLED) or not has_display(bid):
            continue
        m = re.match(r"&#([0-9A-Fa-f]{6})", b["name"])
        color = hexrgb(m.group(1)) if m else hexrgb(FALLBACK.get(bid, "#C8CCD8"))
        name = re.sub(r"&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "", b["name"])
        out[bid] = (color, name, m.group(1) if m else FALLBACK.get(bid, "#C8CCD8").lstrip("#"))
    return out


def furniture(name, hexcolor):
    return {
        "display_name": f"<#{hexcolor}>Présentoir {name.replace('Booster ', '')}",
        "lore": ["<#EADBC8>Une boîte de boosters à exposer", "<#EADBC8>dans ton magasin."],
        "resource": {"material": "PAPER", "generate": False, "model_path": None},
        "behaviours": {"furniture": {
            "entity": "item_display", "opposite_direction": True,
            "display_transformation": {"transform": "FIXED", "translation": {"x": 0, "y": 0.5, "z": 0},
                                       "scale": {"x": 1.0, "y": 1.0, "z": 1.0}},
            "solid": True, "gravity": False, "fixed_rotation": False,
            "placeable_on": {"floor": True, "walls": False, "ceiling": False},
            "hitbox": {"width": 1, "length": 1, "height": 1},
            "sound": {"place": {"name": "block.wood.place"}, "break": {"name": "block.wood.break"}}}},
    }


def main():
    items = {}
    os.makedirs(f"{IA}/textures/item/presentoir", exist_ok=True)
    os.makedirs(f"{IA}/models/item", exist_ok=True)
    for bid, (color, name, hexcolor) in boosters().items():
        write(f"{IA}/textures/item/presentoir/carton_{bid}.png", 16, 16, carton(color))
        write(f"{IA}/textures/item/presentoir/bandeau_{bid}.png", 32, 16, bandeau(color))
        with open(f"{IA}/models/item/presentoir_{bid}.json", "w", encoding="utf-8") as f:
            json.dump(model(bid), f, ensure_ascii=False)
        item = furniture(name, hexcolor)
        item["resource"]["model_path"] = f"item/presentoir_{bid}"
        items[f"presentoir_{bid}"] = item
    with open(f"{IA}/presentoirs.yml", "w", encoding="utf-8") as f:
        f.write("# Présentoirs de boosters (meubles), générés par scripts/influcard/presentoirs.py : modifier le\n"
                "# script, pas ce fichier. /iagive <joueur> fluxpet:presentoir_<booster>\n\n")
        yaml.safe_dump({"info": {"namespace": NS}, "items": items}, f, allow_unicode=True, sort_keys=False)
    print(f"{len(items)} présentoirs -> {IA}")


# ------------------------------------------------------------------ aperçu (sans le jeu)

def preview(bids, path):
    from apercu import render
    tiles = []
    for bid in bids:
        _, _, booster = read(f"{IA}/textures/item/booster_{bid}.png")
        _, _, cart = read(f"{IA}/textures/item/presentoir/carton_{bid}.png")
        _, _, band = read(f"{IA}/textures/item/presentoir/bandeau_{bid}.png")
        atlas = [[(0, 0, 0, 0)] * 64 for _ in range(64)]
        for y in range(32):
            for x in range(32):
                atlas[y][x] = tuple(booster[y][x])
        for y in range(16):
            for x in range(16):
                atlas[y][32 + x] = tuple(cart[y][x])
        for y in range(16):
            for x in range(32):
                atlas[32 + y][x] = tuple(band[y][x])
        offs = {"#booster": (0, 0, 32, 32), "#carton": (32, 0, 16, 16), "#bandeau": (0, 32, 32, 16)}
        els = []
        for e in model(bid)["elements"]:
            r = e.get("rotation")
            faces = {}
            for d, fc in e["faces"].items():
                ox, oy, w, h = offs[fc["texture"]]
                u0, v0, u1, v1 = fc["uv"]
                faces[d] = {"uv": [ox + u0 * w / 16, oy + v0 * h / 16, ox + u1 * w / 16, oy + v1 * h / 16], "texture": 0}
            els.append({"from": e["from"], "to": e["to"], "faces": faces,
                        "rotation": [r["angle"], 0, 0] if r else [0, 0, 0],
                        "origin": r["origin"] if r else [(a + b) / 2 for a, b in zip(e["from"], e["to"])]})
        tiles.append([render(els, atlas, yaw, pitch, size=240) for yaw, pitch in ((20, 20), (0, 8))])
    rows = []
    for pair in tiles:
        rows += [pair[0][y] + pair[1][y] for y in range(240)]
    write(path, 480, len(rows), rows)


if __name__ == "__main__":
    main()
    if len(sys.argv) > 2:
        preview(list(boosters()), sys.argv[2])
