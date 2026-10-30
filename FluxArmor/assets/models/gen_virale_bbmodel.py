#!/usr/bin/env python3
"""Armure virale pour InfluArmor : modèle Blockbench repris du set viral 3D de FluxItems (set_porte.png).

    <venv Pillow + numpy>/python3 gen_virale_bbmodel.py <dossier de sortie>

Écrit virale.bbmodel (ouvrable dans Blockbench, format « Generic Model ») et ses textures 2D de secours.
- Coque : un cube par membre (gonflé comme l'armure vanilla), habillé de la texture d'armure virale 2D
  (plaques, néons), UV du gabarit 64 x 32 ;
- Pièces en relief : celles de plugins/FluxItems/assets/modeles/gen_virale.py (parure_plastron, parure_jupe) :
  grand et petit V, V du dos, gorgerin sur le torse ; épaulières en escalier sur les bras (elles suivent le bras) ;
  ceinture et pans de jupe sur les jambières ; tuiles 16 x 16 plaque, clair, néon (lumineuse).
Repère InfluArmor : un pixel du skin par unité, pieds en y = 0, +x vers la gauche du joueur, +z vers l'avant.
Boîtes du joueur : torse x -4..4, y 12..24, z -2..2 ; bras droit x -8..-4 ; jambe droite x -4..0, y 0..12.
"""
import base64
import io
import json
import os
import shutil
import sys
import uuid

from PIL import Image

FLUX = "/home/influencraft/plugins/FluxItems/assets/modeles"
sys.path.insert(0, FLUX)
import gen_virale as gv  # noqa: E402

PACK_VIRAL = "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents/influoutils/resourcepack/assets/influoutils/textures/item/virale"
TUILES = {"c_plaque": 0, "c_clair": 1, "c_noir": 2, "c_neon": 3}
FACES = ("north", "south", "west", "east", "up", "down")


def png64(im):
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def textures():
    """0 : armure portée (calque humanoid), 1 : jambières (humanoid_leggings), 2 : tuiles, 3 : néon (lumineux)."""
    tuiles = Image.new("RGBA", (48, 16))
    for nom in ("c_plaque", "c_clair", "c_noir"):
        tuiles.paste(Image.open(os.path.join(PACK_VIRAL, nom + ".png")).convert("RGBA").crop((0, 0, 16, 16)), (16 * TUILES[nom], 0))
    neon = Image.open(os.path.join(PACK_VIRAL, "c_neon.png")).convert("RGBA").crop((0, 0, 16, 16))
    out = []
    for nom, im, uw, uh in (("virale_armure.png", Image.open(os.path.join(PACK_VIRAL, "armure.png")).convert("RGBA"), 64, 32),
                            ("virale_jambieres.png", Image.open(os.path.join(PACK_VIRAL, "jambieres.png")).convert("RGBA"), 64, 32),
                            ("virale_tuiles.png", tuiles, 48, 16), ("virale_neon.png", neon, 16, 16)):
        out.append({"name": nom, "uuid": str(uuid.uuid4()), "width": im.width, "height": im.height,
                    "uv_width": uw, "uv_height": uh, "source": png64(im)})
    return out


def cube(nom, de, a, faces, rotation=None, origine=None):
    return {"name": nom, "type": "cube", "uuid": str(uuid.uuid4()), "from": [round(v, 3) for v in de], "to": [round(v, 3) for v in a],
            "origin": origine or [round((de[i] + a[i]) / 2, 3) for i in range(3)], "rotation": rotation or [0, 0, 0], "faces": faces}


def faces_gabarit(tex, U, V, w, h, d, haut=0.0, bas=1.0):
    """UV d'une coque sur la zone du gabarit (U, V, w x h x d) ; haut / bas : tranche verticale utilisée (0 à 1)."""
    v0, v1 = V + d + h * haut, V + d + h * bas
    r = {"south": [U + d, v0, U + d + w, v1], "north": [U + 2 * d + w, v0, U + 2 * d + 2 * w, v1],
         "west": [U, v0, U + d, v1], "east": [U + d + w, v0, U + 2 * d + w, v1],
         "up": [U + d, V, U + d + w, V + d], "down": [U + d + w, V, U + d + 2 * w, V + d]}
    return {f: {"uv": r[f], "texture": tex} for f in FACES}


def faces_tuile(nom):
    if nom == "c_neon":
        return {f: {"uv": [0, 0, 16, 16], "texture": 3} for f in FACES}
    t = TUILES[nom]
    return {f: {"uv": [16 * t, 0, 16 * t + 16, 16], "texture": 2} for f in FACES}


def depuis_parure(e, miroir):
    """Élément de parure (torse centré en 8, 8, 8, avant vers +Z) -> cube Blockbench (torse centré en 0, 18, 0)."""
    (x1, y1, z1), (x2, y2, z2) = e["from"], e["to"]
    sx = -1 if miroir else 1
    xa, xb = sorted((sx * (x1 - 8), sx * (x2 - 8)))
    de, a = [xa, y1 + 10, z1 - 8], [xb, y2 + 10, z2 - 8]
    tex = e["faces"]["north"]["texture"][1:]
    rot, org = None, None
    r = e.get("rotation")
    if r:
        ox, oy, oz = r["origin"]
        org = [sx * (ox - 8), oy + 10, oz - 8]
        ang = r["angle"] * (sx if r["axis"] == "z" else 1)
        rot = [ang if r["axis"] == "x" else 0, ang if r["axis"] == "y" else 0, ang if r["axis"] == "z" else 0]
    return cube(tex, de, a, faces_tuile(tex), rot, org)


def coins(c):
    """Huit coins d'un cube Blockbench après sa rotation autour de son origine (x, puis y, puis z)."""
    import math
    ax, ay, az = (math.radians(v) for v in c["rotation"])
    ox, oy, oz = c["origin"]
    out = []
    for i in range(8):
        x = (c["to"] if i & 1 else c["from"])[0] - ox
        y = (c["to"] if i & 2 else c["from"])[1] - oy
        z = (c["to"] if i & 4 else c["from"])[2] - oz
        y, z = y * math.cos(ax) - z * math.sin(ax), y * math.sin(ax) + z * math.cos(ax)
        x, z = x * math.cos(ay) + z * math.sin(ay), -x * math.sin(ay) + z * math.cos(ay)
        x, y = x * math.cos(az) - y * math.sin(az), x * math.sin(az) + y * math.cos(az)
        out.append((x + ox, y + oy, z + oz))
    return out


def rogner_x(c, xmin, xmax):
    """Raccourcit un cube le long de x (bout fautif seulement) jusqu'à ce que ses coins tournés restent dans [xmin, xmax] :
    une pièce du torse qui déborde sur le bras (ou l'inverse) se croise avec lui dès que le bras bouge."""
    for _ in range(200):
        xs = [p[0] for p in coins(c)]
        if min(xs) >= xmin - 1e-6 and max(xs) <= xmax + 1e-6:
            return c
        # Bout à raccourcir : celui dont les coins sortent le plus.
        bout = {}
        for k in (0, 1):
            c2 = json.loads(json.dumps(c))
            if k: c2["to"][0] = round(c2["to"][0] - 0.1, 3)
            else: c2["from"][0] = round(c2["from"][0] + 0.1, 3)
            x2 = [p[0] for p in coins(c2)]
            bout[k] = max(0, xmin - min(x2)) + max(0, max(x2) - xmax)
        if bout[1] <= bout[0]: c["to"][0] = round(c["to"][0] - 0.1, 3)
        else: c["from"][0] = round(c["from"][0] + 0.1, 3)
        if c["to"][0] - c["from"][0] < 0.5:
            break
    return c


def modele():
    g = {k: [] for k in ("plastron_torse", "plastron_bras", "bottes_jambe", "jambieres_torse", "jambieres_jambe")}
    # Coques texturées (armure virale 2D), gonflées d'un pixel (un demi pour les jambières).
    g["plastron_torse"].append(cube("coque", [-5, 11, -3], [5, 25, 3], faces_gabarit(0, 16, 16, 8, 12, 4)))
    # Coque du bras un dixième de pixel en retrait de celle du torse : là où elles se croisent (épaule), des faces
    # dans le même plan scintilleraient, chaque membre étant dessiné à part.
    g["plastron_bras"].append(cube("coque", [-9, 11, -2.9], [-3, 24.9, 2.9], faces_gabarit(0, 40, 16, 4, 12, 4)))
    g["bottes_jambe"].append(cube("coque", [-5, -1, -3], [1, 5.5, 3], faces_gabarit(0, 0, 16, 4, 12, 4, 0.54, 1.0)))
    g["jambieres_torse"].append(cube("coque", [-4.5, 11.5, -2.5], [4.5, 16.5, 2.5], faces_gabarit(1, 16, 16, 8, 12, 4, 0.6, 1.0)))
    g["jambieres_jambe"].append(cube("coque", [-4.5, -0.5, -2.5], [0.5, 12.5, 2.5], faces_gabarit(1, 0, 16, 4, 12, 4)))
    # Pièces en relief du set viral : torse (V, gorgerin), bras droit (épaulières), jambières (ceinture, jupe).
    for e in gv.parure_plastron()["elements"]:
        cx = (e["from"][0] + e["to"][0]) / 2
        # Le torse reste entre ses épaules (x -4,8..4,8), les épaulières restent sur le bras (x <= -3,6).
        if abs(cx - 8) < 5:
            g["plastron_torse"].append(rogner_x(depuis_parure(e, False), -4.8, 4.8))
        elif cx > 8:  # côté +X de la parure : épaulière ; le bras droit du joueur est en -X, d'où le miroir
            g["plastron_bras"].append(rogner_x(depuis_parure(e, True), -13, -3.6))
    # Les couches d'une même barre (plaque, clair, néon : même origine) gardent la même longueur.
    for nom in ("plastron_torse", "plastron_bras"):
        barres = {}
        for c in g[nom]:
            barres.setdefault((tuple(c["origin"]), tuple(c["rotation"])), []).append(c)
        for cs in barres.values():
            if len(cs) > 1:
                de, a = max(c["from"][0] for c in cs), min(c["to"][0] for c in cs)
                for c in cs:
                    c["from"][0], c["to"][0] = max(c["from"][0], de), min(c["to"][0], a)
    for e in gv.parure_jupe()["elements"]:
        g["jambieres_torse"].append(depuis_parure(e, False))
    # Bottes : revers néon en couronne.
    g["bottes_jambe"].append(cube("revers", [-5.2, 5.2, -3.2], [1.2, 5.8, 3.2], faces_tuile("c_neon")))
    return g


def main():
    dest = sys.argv[1]
    os.makedirs(dest, exist_ok=True)
    groupes = modele()
    elements, outliner = [], []
    for nom, cubes in groupes.items():
        elements += cubes
        outliner.append({"name": nom, "uuid": str(uuid.uuid4()), "origin": [0, 0, 0], "children": [c["uuid"] for c in cubes]})
    bb = {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False}, "name": "virale",
          "resolution": {"width": 64, "height": 32}, "elements": elements, "outliner": outliner, "textures": textures()}
    json.dump(bb, open(os.path.join(dest, "virale.bbmodel"), "w"), indent=1)
    shutil.copy(os.path.join(PACK_VIRAL, "armure.png"), os.path.join(dest, "virale_humanoid.png"))
    shutil.copy(os.path.join(PACK_VIRAL, "jambieres.png"), os.path.join(dest, "virale_jambieres.png"))
    print("Modèle écrit dans", dest, ":", ", ".join("%s %d" % (k, len(v)) for k, v in groupes.items()))


if __name__ == "__main__":
    main()
