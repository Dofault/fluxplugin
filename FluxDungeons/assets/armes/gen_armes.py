#!/usr/bin/env python3
"""Class weapons of the RPG universe in 3D: Brise-Aube, Sylve-Celeste, Sceptre du Neant, Reliquaire de l'Aube.

    <venv with Pillow and numpy>/python3 gen_armes.py <ItemsAdder/contents/fluxdungeons> [previews dir] [sheet.md]

One source of truth for everything: the cube lists below produce
- resourcepack/assets/fluxdungeons/models/item/<weapon>.json : vanilla item models (cubes, named and grouped
  for Blockbench, which opens them as they are), with every display context computed (see DISPLAYS);
- resourcepack/assets/fluxdungeons/items/<weapon>.json : item definitions, target of the item_model component
  (key `look.model: fluxdungeons:<weapon>` of plugins/FluxDungeons/rpg/equipment.yml);
- resourcepack/assets/fluxdungeons/textures/item/armes/<weapon>/<material>.png : one 32 x 32 swatch per material.
  They are placeholders: a texture artist replaces a PNG without touching any model (same name, same size,
  tileable, 2 texels per model unit);
- previews (front, side, inventory icon, held in third and first person) and the fabrication sheet (markdown),
  generated from the same cubes, so the documentation never drifts from the models.

Minecraft limits checked here (the script stops on any violation):
- cube corners (`from`, `to`) between -16 and 32 on every axis;
- one rotation axis per cube, angle in {-45, -22.5, 0, 22.5, 45};
- display: translation within +-80, scale within 0..4.
Run again after any change, then /iazip on Labs.
"""
import json
import math
import os
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw

NS = "fluxdungeons"
T = 32  # swatch size: 2 texels per model unit, 16 units per swatch
FACES = ("north", "east", "south", "west", "up", "down")
ANGLES = (-45.0, -22.5, 0.0, 22.5, 45.0)


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# ------------------------------------------------------------------------------------------- weapon builder

class Weapon:
    """A list of named cubes grouped by part. Model space: Y up, the weapon axis along Y at x = z = 8,
    the flat of the weapon in the XY plane (seen from the front, +Z), thickness along Z."""

    def __init__(self, wid, name, cls, art, materials):
        self.id, self.name, self.cls, self.art = wid, name, cls, art
        self.materials = materials  # key -> (hex, label, emissive)
        self.cubes = []
        self.part = None

    def group(self, part):
        self.part = part

    def box(self, name, f, t, mat, rot=None, note=""):
        """rot = (axis, angle, origin) or (axis, angle) to turn around the cube centre."""
        assert mat in self.materials, mat
        f, t = [float(v) for v in f], [float(v) for v in t]
        if rot is not None and len(rot) == 2:
            rot = (rot[0], rot[1], [(a + b) / 2 for a, b in zip(f, t)])
        self.cubes.append({"name": name, "part": self.part, "from": f, "to": t, "mat": mat, "rot": rot, "note": note})

    def c(self, name, centre, size, mat, rot=None, note=""):
        """Cube given by its centre and size; rot = (axis, angle) turns it around its centre."""
        f = [c - s / 2 for c, s in zip(centre, size)]
        t = [c + s / 2 for c, s in zip(centre, size)]
        self.box(name, f, t, mat, None if rot is None else (rot[0], rot[1], list(centre)), note)

    def mirror(self, name, f, t, mat, rot=None, note=""):
        """Cube and its mirror image across x = 8 (names suffixed _g / _d: left / right seen from the front)."""
        self.box(name + "_g", f, t, mat, rot, note)
        f2, t2 = [16 - t[0], f[1], f[2]], [16 - f[0], t[1], t[2]]
        r2 = None
        if rot is not None:
            if len(rot) == 2:
                rot = (rot[0], rot[1], [(a + b) / 2 for a, b in zip(f, t)])
            ax, ang, o = rot
            r2 = (ax, ang if ax == "x" else -ang, [16 - o[0], o[1], o[2]])
        self.box(name + "_d", f2, t2, mat, r2, note)

    def seg(self, name, start, angle, length, width, depth, mat, local_x=None, local_z=None, overlap=0.35, note=""):
        """Segment leaving `start` (x, y) with `angle` (Z rotation: positive leans +Y towards -X).
        local_x / local_z: optional (min, max) inside the segment, relative to its axis (overlays).
        Returns the end point."""
        a = math.radians(angle)
        d = (-math.sin(a), math.cos(a))
        cx, cy = start[0] + d[0] * length / 2, start[1] + d[1] * length / 2
        lx = local_x or (-width / 2, width / 2)
        lz = local_z or (-depth / 2, depth / 2)
        h = length / 2 + overlap
        self.box(name, [cx + lx[0], cy - h, 8 + lz[0]], [cx + lx[1], cy + h, 8 + lz[1]], mat,
                 None if angle == 0 else ("z", angle, [cx, cy, 8]), note)
        return (start[0] + d[0] * length, start[1] + d[1] * length)


# ------------------------------------------------------------------------------------------------ weapons

def brise_aube():
    w = Weapon("brise_aube", "Brise-Aube", "Guerrier",
               "Épée à deux mains massive : lame large d'argent à arête renforcée, deux tranchants clairs en biseau, "
               "noyau d'énergie bleue sur toute la longueur, pointe en losange, épaulements en crocs, garde d'acier "
               "et d'or à quillons relevés, longue poignée de cuir à trois bagues (la bague d'or sépare les deux mains), "
               "pommeau d'argent serti.",
               {"argent": ("#C9D4DF", "Argent métallique", False), "argent_clair": ("#EEF4FA", "Argent clair", False),
                "acier": ("#596879", "Acier sombre", False), "or": ("#D6A63C", "Or ancien", False),
                "noyau": ("#39BFFF", "Bleu électrique", True), "noyau_profond": ("#1767A5", "Bleu profond", False),
                "cuir": ("#2B303B", "Cuir sombre (ajout : poignée)", False)})
    w.group("pommeau")
    w.box("pommeau_pointe", [7, -16, 7], [9, -15, 9], "acier")
    w.box("pommeau_corps", [6, -15, 6.5], [10, -12.5, 9.5], "argent")
    w.c("pommeau_chanfrein", [8, -13.75, 8], [2.6, 2.6, 3.2], "argent_clair", ("y", 45), "dépasse en pointe sur les quatre arêtes")
    w.box("pommeau_gemme", [7.3, -14.45, 6.4], [8.7, -13.05, 9.6], "noyau", note="superposé : dépasse de 0,1 devant et derrière")
    w.box("pommeau_bague", [6.5, -12.5, 6.75], [9.5, -12, 9.25], "or")
    w.group("poignee")
    w.box("poignee", [7, -12, 7], [9, -2, 9], "cuir")
    w.box("bague_basse", [6.8, -10.2, 6.8], [9.2, -9.7, 9.2], "acier")
    w.box("bague_mains", [6.7, -7.3, 6.7], [9.3, -6.7, 9.3], "or", note="sépare les deux mains")
    w.box("bague_haute", [6.8, -4.3, 6.8], [9.2, -3.8, 9.2], "acier")
    w.group("garde")
    w.box("garde_coeur", [5.5, -2, 6.6], [10.5, 1, 9.4], "acier")
    w.box("garde_plaque", [6.3, -1.6, 6.5], [9.7, 0.6, 9.5], "or", note="superposé : 0,1 devant et derrière")
    w.box("garde_gemme", [7.3, -1.2, 6.4], [8.7, 0.2, 9.6], "noyau", note="superposé sur la plaque")
    w.mirror("quillon", [0.5, -1.3, 7], [5.6, 0.3, 9], "argent")
    w.mirror("quillon_filet", [0.5, 0.3, 7.2], [5.6, 0.7, 8.8], "or", note="liseré posé sur le quillon")
    w.mirror("quillon_bout", [-0.3, -1.4, 6.9], [1.5, 1.8, 9.1], "or", ("z", 22.5), "relevé vers l'extérieur")
    w.mirror("quillon_pointe", [-0.85, 1.75, 7.2], [0.55, 3.15, 8.8], "argent_clair", ("z", 45), "losange au bout du quillon")
    w.group("talon")
    w.box("talon", [5.5, 1, 7.05], [10.5, 4, 8.95], "acier")
    w.box("talon_plaque", [6.2, 1.3, 6.85], [9.8, 3.7, 9.15], "or", note="superposé : 0,2 devant et derrière")
    w.c("talon_gemme", [8, 2.5, 8], [1.4, 1.4, 2.5], "noyau", ("z", 45), "losange d'énergie sur la plaque")
    w.group("lame")
    w.box("arete", [6.5, 4, 7], [9.5, 24, 9], "argent", note="structure renforcée, épaisseur 2")
    w.mirror("tranchant", [4.5, 4, 7.35], [6.5, 24.5, 8.65], "argent_clair", note="plus fin que l'arête : marche de 0,35")
    w.box("renfort", [6.3, 4, 6.85], [9.7, 6.5, 9.15], "argent_clair", note="superposé sur l'arête")
    w.box("rainure", [7.1, 5, 6.9], [8.9, 22, 9.1], "noyau_profond", note="superposé : 0,1 devant et derrière")
    w.box("noyau", [7.5, 5.5, 6.8], [8.5, 21.5, 9.2], "noyau", note="superposé sur la rainure")
    w.mirror("croc", [3.4, 4.2, 7.3], [5, 5.8, 8.7], "argent_clair", ("z", 45), "épaulement en crocs à la base de la lame")
    w.mirror("entaille", [4.1, 13.4, 7.25], [5.3, 14.6, 8.75], "acier", ("z", 45), "brisure du tranchant, à moitié enfoncée")
    w.group("pointe")
    w.c("pointe", [8, 24.5, 8], [4.95, 4.95, 1.3], "argent_clair", ("z", 45), "losange : largeur 7 = la lame")
    w.c("pointe_arete", [8, 24.5, 8], [3.54, 3.54, 2], "argent", ("z", 45), "prolonge l'arête jusqu'à y 27")
    w.c("pointe_noyau", [8, 23.2, 8], [1.4, 1.4, 2.4], "noyau", ("z", 45), "fin du noyau, dépasse de 0,2")
    return w


def sylve_celeste():
    w = Weapon("sylve_celeste", "Sylve-Céleste", "Archer",
               "Arc long elfique : deux branches de bois en courbe progressive (0°, 22,5°, 45°) qui finissent en "
               "crochets dorés relevés, dos de bois clair, nervures d'émeraude lumineuses sur les branches, poignée "
               "de bois sombre bandée de vert, cristal émeraude en losange serti d'or au repose-flèche, corde tendue "
               "entre les deux encoches.",
               {"bois_sombre": ("#493526", "Bois sombre", False), "bois_moyen": ("#765037", "Bois moyen", False),
                "bois_clair": ("#B78B58", "Bois clair", False), "emeraude": ("#16B879", "Vert émeraude", True),
                "vert_profond": ("#08754F", "Vert profond", False), "or": ("#C5A65A", "Or végétal", False),
                "corde": ("#D8D2BC", "Corde", False)})
    w.group("poignee")
    w.box("poignee", [6.9, 5, 6.9], [9.3, 11, 9.1], "bois_sombre")
    for i, (y0, y1) in enumerate(((5.6, 6.6), (7.5, 8.5), (9.4, 10.4))):
        w.box("bande_%d" % (i + 1), [6.8, y0, 6.8], [9.4, y1, 9.2], "vert_profond", note="superposé : 0,1 autour")
    w.box("poignee_bague_haute", [6.6, 10.8, 6.6], [9.6, 11.6, 9.4], "or")
    w.box("poignee_bague_basse", [6.6, 4.4, 6.6], [9.6, 5.2, 9.4], "or")
    w.c("sertissure", [9.75, 8, 8], [2.6, 2.6, 1.0], "or", ("z", 45), "derrière le cristal")
    w.c("cristal", [9.95, 8, 8], [2.0, 2.0, 1.6], "emeraude", ("z", 45), "dépasse de la sertissure devant et derrière")
    # Limbs: upper chain, then the lower one mirrored across y = 8 (angles negated).
    chain = [(0.0, 6.0, 1.9, 1.8), (22.5, 6.0, 1.7, 1.6), (22.5, 5.0, 1.5, 1.4), (45.0, 2.5, 1.3, 1.2)]
    for side, sign in (("haute", 1), ("basse", -1)):
        w.group("branche_" + side)
        p = (8.1, 11.0 if sign > 0 else 5.0)
        joints = []
        for i, (ang, ln, wd, dp) in enumerate(chain):
            start = p
            p = _seg_any(w, "branche_%s_%d" % (side, i + 1), start, ang, ln, sign, "bois_moyen",
                         local_x=(-wd / 2, wd / 2), local_z=(-dp / 2, dp / 2))
            if i < 3:
                # Back of the bow (belly, +X side): light wood strip, 0.1 proud.
                _seg_any(w, "dos_%s_%d" % (side, i + 1), start, ang, ln, sign, "bois_clair",
                         local_x=(wd / 2 - 0.45, wd / 2 + 0.1), local_z=(-dp / 2 + 0.2, dp / 2 - 0.2), overlap=0.0,
                         note="superposé sur le dos de la branche")
            if i in (1, 2):
                _seg_any(w, "nervure_%s_%d" % (side, i + 1), start, ang, ln - 1.2, sign, "emeraude",
                         local_x=(-0.3, 0.3), local_z=(-dp / 2 - 0.1, dp / 2 + 0.1), overlap=0.0, shift=0.6,
                         note="dépasse de 0,1 devant et derrière")
            joints.append((start, ang))
        # Gold ring on the joint between segments 2 and 3, gold hook at the tip, then the nock.
        (j, ang) = joints[2]
        _seg_any(w, "bague_%s" % side, j, ang, 0.8, sign, "or", local_x=(-1.05, 1.05), local_z=(-0.95, 0.95),
                 overlap=0.0, shift=-0.4)
        _seg_any(w, "crochet_%s" % side, p, -22.5, 2.4, sign, "or", local_x=(-0.5, 0.5), local_z=(-0.5, 0.5),
                 overlap=0.2, note="crochet relevé vers l'avant")
        w.c("encoche_%s" % side, [p[0], p[1], 8], [1.0, 1.0, 1.0], "or", ("z", 45))
        w.c("perle_%s" % side, [p[0] + 0.15, p[1] + sign * 0.15, 8], [0.6, 0.6, 1.2], "emeraude", ("z", 45),
            "goutte d'émeraude dans l'encoche")
    tip = _limb_end(chain)
    w.group("corde")
    w.box("corde", [tip[0] - 0.15, 16 - tip[1], 7.85], [tip[0] + 0.15, tip[1], 8.15], "corde",
          note="de l'encoche basse à l'encoche haute")
    return w


def _limb_end(chain):
    p = (8.1, 11.0)
    for ang, ln, _, _ in chain:
        a = math.radians(ang)
        p = (p[0] - math.sin(a) * ln, p[1] + math.cos(a) * ln)
    return p


def _seg_any(w, name, start, ang, ln, sign, mat, local_x, local_z, overlap=0.35, shift=0.0, note=""):
    """Segment going up (sign 1) or its mirror image across a horizontal line going down (sign -1).
    shift moves the segment along its own axis."""
    a = math.radians(ang)
    d = (-math.sin(a), math.cos(a) * sign)
    s0 = (start[0] + d[0] * shift, start[1] + d[1] * shift)
    cx, cy = s0[0] + d[0] * ln / 2, s0[1] + d[1] * ln / 2
    h = ln / 2 + overlap
    w.box(name, [cx + local_x[0], cy - h, 8 + local_z[0]], [cx + local_x[1], cy + h, 8 + local_z[1]], mat,
          None if ang == 0 else ("z", ang * sign, [cx, cy, 8]), note)
    return (start[0] + d[0] * ln, start[1] + d[1] * ln)


def sceptre_neant():
    w = Weapon("sceptre_neant", "Sceptre du Néant", "Mage",
               "Bâton arcanique : long manche noir veiné de runes violettes, renforts en losange, tête asymétrique "
               "(une longue griffe recourbée à gauche, une griffe courte à droite, une griffe arrière), cristal "
               "d'améthyste allongé à cœur bleu, six éclats en suspension montant en spirale autour du cristal, "
               "trois arcs d'orbite qui suggèrent leur rotation, pointe inférieure de métal.",
               {"noir": ("#171521", "Noir profond", False), "violet_sombre": ("#35214F", "Violet sombre", False),
                "violet_moyen": ("#7446B5", "Violet moyen", False), "violet_lumineux": ("#B47AFF", "Violet lumineux", True),
                "amethyste": ("#D0A0FF", "Améthyste", True), "energie": ("#5A9BFF", "Énergie secondaire", True),
                "metal": ("#8C86A6", "Métal (ajout : pointe)", False)})
    w.group("pointe")
    w.box("pointe_fin", [7.7, -16, 7.7], [8.3, -15.2, 8.3], "metal")
    w.c("pointe_milieu", [8, -14.6, 8], [1.2, 1.2, 1.2], "metal", ("y", 45))
    w.box("pointe_base", [7.1, -14.1, 7.1], [8.9, -12.7, 8.9], "metal")
    w.box("pointe_collier", [6.9, -12.8, 6.9], [9.1, -12.1, 9.1], "violet_sombre")
    w.group("manche")
    w.box("manche", [7.2, -12.2, 7.2], [8.8, 17, 8.8], "noir")
    w.c("renfort_bas", [8, -6, 8], [2.2, 0.8, 2.2], "violet_sombre", ("y", 45))
    w.box("prise", [7, -1, 7], [9, 5, 9], "violet_sombre", note="zone de prise, 0,2 autour du manche")
    w.c("bague_prise_basse", [8, -1.1, 8], [2.4, 0.6, 2.4], "violet_moyen", ("y", 45))
    w.c("bague_prise_haute", [8, 5.1, 8], [2.4, 0.6, 2.4], "violet_moyen", ("y", 45))
    w.c("renfort_haut", [8, 10, 8], [2.2, 0.8, 2.2], "violet_sombre", ("y", 45))
    for i, (y, side) in enumerate(((12, 1), (13.8, -1), (15.4, 1))):
        x0 = 8.75 if side > 0 else 7.0
        w.box("rune_%d" % (i + 1), [x0, y, 7.8], [x0 + 0.25, y + 1.2, 8.2], "violet_lumineux",
              note="dépasse de 0,2 du manche, alternée gauche et droite")
    w.group("tete")
    w.c("collier", [8, 17.5, 8], [3, 2, 3], "violet_sombre", ("y", 45))
    w.box("coupe", [6, 18.5, 6.8], [10, 19.3, 9.2], "violet_moyen")
    end = w.seg("griffe_g1", (6.6, 18.9), 22.5, 4.0, 1.0, 1.2, "violet_sombre")
    end = w.seg("griffe_g2", end, 0.0, 4.0, 0.9, 1.1, "violet_sombre")
    end = w.seg("griffe_g3", end, -22.5, 3.5, 0.8, 1.0, "violet_sombre", note="se recourbe au-dessus du cristal")
    w.c("griffe_g_pointe", [end[0] + 0.15, end[1] + 0.3, 8], [0.9, 0.9, 1.0], "violet_lumineux", ("z", 45))
    end = w.seg("griffe_d1", (9.4, 18.9), -22.5, 3.0, 0.9, 1.1, "violet_sombre", note="griffe courte : asymétrie")
    end = w.seg("griffe_d2", end, 0.0, 2.5, 0.8, 1.0, "violet_sombre")
    w.c("griffe_d_pointe", [end[0], end[1] + 0.4, 8], [0.8, 0.8, 0.9], "energie", ("z", 45))
    w.c("griffe_arriere", [8, 20.9, 9.9], [0.9, 4.2, 0.9], "violet_sombre", ("x", 22.5), "penchée vers l'arrière (+Z)")
    w.c("griffe_arriere_pointe", [8, 23.0, 10.9], [0.7, 0.7, 0.7], "violet_lumineux", ("x", 45))
    w.group("cristal")
    w.c("cristal_bas", [8, 21.6, 8], [2.0, 2.0, 2.2], "violet_lumineux", ("z", 45))
    w.c("cristal", [8, 24.4, 8], [3.2, 3.2, 2.8], "amethyste", ("z", 45), "élément principal")
    w.c("cristal_haut", [8, 27.4, 8], [2.0, 2.0, 2.2], "violet_lumineux", ("z", 45))
    w.c("cristal_coeur", [8, 24.4, 8], [0.9, 0.9, 3.0], "energie", ("z", 45), "dépasse de 0,1 devant et derrière")
    w.group("eclats")
    for i, (ctr, size, rot, mat) in enumerate((
            ((3.4, 21.0, 8.0), (1.0, 2.0, 1.0), ("z", 22.5), "amethyste"),
            ((12.6, 23.0, 6.8), (1.1, 1.8, 0.9), ("z", -45), "violet_lumineux"),
            ((11.4, 27.6, 10.4), (0.9, 1.6, 0.9), ("x", 22.5), "amethyste"),
            ((4.8, 28.6, 5.8), (0.8, 1.4, 0.8), ("x", -45), "energie"),
            ((9.4, 30.9), (0.7, 1.0, 0.7), ("z", 45), "violet_lumineux"),
            ((13.2, 19.4, 9.8), (0.6, 0.6, 0.6), ("y", 45), "energie"))):
        if len(ctr) == 2:
            ctr = (ctr[0], ctr[1], 8.2)
        w.c("eclat_%d" % (i + 1), ctr, size, mat, rot, "en suspension, aucun contact")
    w.c("orbite_1", [3.4, 24.6, 7.0], [0.3, 2.6, 0.3], "violet_moyen", None, "trace d'orbite, à gauche, en avant")
    w.c("orbite_2", [11.3, 27.9, 9.0], [0.3, 2.6, 0.3], "violet_moyen", ("z", 45), "trace d'orbite, en haut à droite, en arrière")
    w.c("orbite_3", [11.0, 20.4, 8.0], [0.3, 2.4, 0.3], "violet_moyen", ("z", -45), "trace d'orbite, en bas à droite")
    return w


def reliquaire_aube():
    w = Weapon("reliquaire_aube", "Reliquaire de l'Aube", "Soigneur",
               "Bâton sacré symétrique : manche d'ivoire cerclé d'or, prise bandée de turquoise, calice doré qui "
               "porte un halo octogonal d'or clair ; au centre, un cristal turquoise en losange étagé (or, turquoise "
               "sombre, turquoise, éclat céleste) tenu par quatre rayons ; deux ailes de quatre plumes déployées "
               "derrière le halo, bouts de plume blancs lumineux ; étoile au sommet, pointe dorée en bas.",
               {"ivoire": ("#F2EAD6", "Ivoire", False), "blanc": ("#FFFFFF", "Blanc lumineux", True),
                "or": ("#D9B45B", "Or", False), "or_clair": ("#F1D58B", "Or clair", False),
                "turquoise": ("#39D8D0", "Turquoise", True), "turquoise_sombre": ("#198F91", "Turquoise sombre", False),
                "celeste": ("#8CDDF0", "Bleu céleste", True)})
    w.group("pointe")
    w.box("pointe_fin", [7.6, -16, 7.6], [8.4, -15, 8.4], "or_clair")
    w.c("pointe_milieu", [8, -14.25, 8], [1.4, 1.5, 1.4], "or", ("y", 45))
    w.box("pointe_base", [6.9, -13.5, 6.9], [9.1, -12.3, 9.1], "or")
    w.group("manche")
    w.box("manche", [7.2, -12.4, 7.2], [8.8, 16.5, 8.8], "ivoire")
    for i, y in enumerate((-8, -0.6, 5.6, 11.5)):
        w.box("cercle_%d" % (i + 1), [6.9, y, 6.9], [9.1, y + 0.7, 9.1], "or")
    w.box("prise", [7.0, 0.1, 7.0], [9.0, 5.6, 9.0], "turquoise_sombre", note="zone de prise, entre deux cercles")
    w.c("perle_prise", [8, 2.85, 8], [0.9, 0.9, 2.2], "celeste", ("z", 45), "dépasse de 0,1 devant et derrière")
    w.group("calice")
    w.c("socle", [8, 16.9, 8], [3.0, 1.2, 3.0], "or", ("y", 45))
    w.box("calice", [5.9, 17.5, 6.8], [10.1, 18.5, 9.2], "or_clair")
    w.box("col", [7.2, 18.5, 7.4], [8.8, 19.7, 8.6], "or")
    w.group("halo")
    cx, cy, ap, side = 8.0, 25.0, 5.0, 4.34
    w.c("halo_haut", [cx, cy + ap, 8], [side, 1.0, 1.4], "or_clair")
    w.c("halo_bas", [cx, cy - ap, 8], [side, 1.0, 1.4], "or_clair")
    w.mirror("halo_cote", [cx - ap - 0.5, cy - side / 2, 7.3], [cx - ap + 0.5, cy + side / 2, 8.7], "or_clair")
    k = ap / math.sqrt(2)
    w.mirror("halo_diag_haut", [cx - k - side / 2, cy + k - 0.5, 7.3], [cx - k + side / 2, cy + k + 0.5, 8.7], "or_clair",
             ("z", 45), "segment d'octogone")
    w.mirror("halo_diag_bas", [cx - k - side / 2, cy - k - 0.5, 7.3], [cx - k + side / 2, cy - k + 0.5, 8.7], "or_clair",
             ("z", -45), "segment d'octogone")
    w.box("rayon_haut", [7.7, 27.0, 7.6], [8.3, 29.6, 8.4], "or")
    w.box("rayon_bas", [7.7, 20.4, 7.6], [8.3, 23.0, 8.4], "or")
    w.mirror("rayon", [3.4, 24.7, 7.6], [6.0, 25.3, 8.4], "or")
    w.group("cristal")
    w.c("ecrin", [cx, cy, 8], [3.6, 3.6, 0.8], "or", ("z", 45), "fond de la sertissure")
    w.c("cristal_bord", [cx, cy, 8], [3.0, 3.0, 1.4], "turquoise_sombre", ("z", 45), "superposé, en retrait")
    w.c("cristal", [cx, cy, 8], [2.6, 2.6, 2.0], "turquoise", ("z", 45), "élément central")
    w.c("cristal_eclat", [cx, cy + 0.3, 8], [1.2, 1.2, 2.2], "celeste", ("z", 45), "dépasse de 0,1 devant et derrière")
    w.group("couronne")
    w.box("etoile_tige", [7.6, 30.5, 7.6], [8.4, 32, 8.4], "blanc")
    w.c("etoile", [8, 31.0, 8], [1.1, 1.1, 1.0], "or_clair", ("z", 45))
    w.group("ailes")
    for i, (ang, ln, x0, y0) in enumerate(((45, 6.5, 12.3, 20.6), (22.5, 6.0, 12.0, 19.6), (0, 5.0, 11.6, 18.6),
                                            (-22.5, 3.6, 11.2, 17.6))):
        a = math.radians(ang)
        dx, dy = math.cos(a), math.sin(a)
        mx, my = x0 + dx * ln / 2, y0 + dy * ln / 2
        # Built on the right side then mirrored: feathers long along X, turned by the angle.
        f = [16 - (mx + ln / 2), my - 0.65, 7.6]
        t = [16 - (mx - ln / 2), my + 0.65, 8.4]
        w.mirror("plume_%d" % (i + 1), f, t, "ivoire", None if ang == 0 else ("z", -ang),
                 "derrière le halo (épaisseur 0,8)")
        ex, ey = x0 + dx * (ln - 0.4), y0 + dy * (ln - 0.4)
        w.mirror("plume_%d_bout" % (i + 1), [16 - ex - 0.6, ey - 0.6, 7.5], [16 - ex + 0.6, ey + 0.6, 8.5], "blanc", ("z", 45))
    w.mirror("aile_os", [16 - 15.0, 19.3, 8.4], [16 - 10.8, 20.1, 8.9], "or", ("z", -22.5), "devant les plumes")
    return w


WEAPONS = [brise_aube, sylve_celeste, sceptre_neant, reliquaire_aube]

# ------------------------------------------------------------------------------------------- displays
# Vanilla references (assets/minecraft/models/item/handheld.json and bow.json) and the point of their sprite held
# by the fist. Each weapon reuses the vanilla rotation, turned in the plane of the weapon by `turn` degrees, and a
# translation computed so that the weapon's grip lands exactly where the vanilla grip lands: the fist closes on
# the grip at any scale. In third person, in the weapon plane, 90 = forward, less = raised, more = lowered.

VANILLA = {
    "handheld": {"tp": ([0, -90, 55], [0, 4, 0.5], 0.85), "fp": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68), "grip": (3.5, 3.5, 8)},
    "bow": {"tp": ([-80, 260, -40], [-1, -2, 2.5], 0.9), "fp": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68), "grip": (7.5, 8, 8)},
}

# weapon: base, weapon axis in the sprite of the base (degrees), grip, third person (turn, scale),
# first person (turn, scale[, shift of the fist on screen: x right, y up, in display units])
DISPLAYS = {
    "brise_aube": ("handheld", 45, (8, -7, 8), (-32, 0.70), (0, 0.42)),
    "sylve_celeste": ("bow", 135, (8.1, 8, 8), (25, 0.55), (-45, 0.32, (-3, 2.5, 0))),
    "sceptre_neant": ("handheld", 45, (8, 2, 8), (-47, 0.66), (10, 0.36)),
    "reliquaire_aube": ("handheld", 45, (8, 2.85, 8), (-47, 0.66), (10, 0.36)),
}
# Spin of the weapon around its own axis (degrees) before it is held: the bow was held back to front in game, its
# back (grip, crystal, +X) towards the player and the string away (2026-11-13). 180 = back forward, string inward.
SPIN = {"sylve_celeste": 180}


def rx(d):
    a = math.radians(d)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def ry(d):
    a = math.radians(d)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def rz(d):
    a = math.radians(d)
    return np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]])


def rxyz(r):
    return rx(r[0]) @ ry(r[1]) @ rz(r[2])


C8 = np.array([8.0, 8.0, 8.0])


def euler_xyz(m):
    """Angles (x, y, z) in degrees such that rxyz(angles) == m (the order of a model display rotation)."""
    b = math.asin(max(-1.0, min(1.0, m[0, 2])))
    a = math.atan2(-m[1, 2], m[2, 2])
    c = math.atan2(-m[0, 1], m[0, 0])
    return [math.degrees(a), math.degrees(b), math.degrees(c)]


def held(base, axis_angle, grip, turn, scale, ctx, shift=(0, 0, 0), spin=0):
    """Display entry: vanilla rotation + in-plane turn (+ spin around the weapon's own axis), translation that puts
    the grip on the vanilla grip."""
    rot, tr, s = VANILLA[base][ctx]
    g_v = np.array(VANILLA[base]["grip"], float)
    hand = np.array(tr, float) + rxyz(rot) @ (s * (g_v - C8))
    # Our model is drawn along +Y (90 degrees); the vanilla sprite axis is at axis_angle.
    z = rot[2] + (axis_angle - 90) + turn
    new = [rot[0], rot[1], z]
    if spin:
        new = euler_xyz(rxyz(new) @ ry(spin))
    t = hand + np.array(shift, float) - rxyz(new) @ (scale * (np.array(grip, float) - C8))
    return {"rotation": [round(v, 2) for v in new], "translation": [round(v, 2) for v in t], "scale": [scale] * 3}


def mirror_hand(d):
    r, t = d["rotation"], d["translation"]
    return {"rotation": [r[0], -r[1], -r[2]], "translation": list(t), "scale": d["scale"]}


def bounds(cubes, A=np.eye(3)):
    pts = []
    for c in cubes:
        for p in corners(c):
            pts.append(A @ p)
    pts = np.array(pts)
    return pts.min(0), pts.max(0)


def corners(c):
    (x1, y1, z1), (x2, y2, z2) = c["from"], c["to"]
    out = []
    for x in (x1, x2):
        for y in (y1, y2):
            for z in (z1, z2):
                p = np.array([x, y, z], float)
                if c["rot"]:
                    ax, ang, o = c["rot"]
                    o = np.array(o, float)
                    p = {"x": rx, "y": ry, "z": rz}[ax](ang) @ (p - o) + o
                out.append(p)
    return out


def fit(cubes, rot, room):
    """Rotation, translation and scale so that the rotated model fills `room` units around the centre."""
    A = rxyz(rot)
    lo, hi = bounds(cubes, A)
    s = min(room / (hi - lo)[:2].max(), 4.0)
    mid = (lo + hi) / 2 - A @ C8
    return {"rotation": rot, "translation": [round(v, 2) for v in (-s * mid)], "scale": [round(s, 3)] * 3}


def displays(w):
    base, axis_angle, grip, tp, fp = DISPLAYS[w.id]
    d = {
        "thirdperson_righthand": held(base, axis_angle, grip, tp[0], tp[1], "tp", spin=SPIN.get(w.id, 0)),
        "firstperson_righthand": held(base, axis_angle, grip, fp[0], fp[1], "fp", *fp[2:], spin=SPIN.get(w.id, 0)),
    }
    d["thirdperson_lefthand"] = mirror_hand(d["thirdperson_righthand"])
    d["firstperson_lefthand"] = mirror_hand(d["firstperson_righthand"])
    # Inventory and menus: diagonal like a vanilla tool (top towards the upper right), filling the slot.
    d["gui"] = fit(w.cubes, [0, 0, -45], 16.0)
    d["fixed"] = fit(w.cubes, [0, 180, 45], 15.0)
    d["on_shelf"] = fit(w.cubes, [0, 180, 45], 15.0)
    g = fit(w.cubes, [0, 0, 0], 11.0)
    d["ground"] = g
    d["head"] = {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [0.6, 0.6, 0.6]}
    return d


# ------------------------------------------------------------------------------------------- validation

def check(w, disp):
    errs = []
    for c in w.cubes:
        for v in c["from"] + c["to"]:
            if not -16 <= v <= 32:
                errs.append("%s: corner %.3f out of -16..32" % (c["name"], v))
        if any(a >= b for a, b in zip(c["from"], c["to"])):
            errs.append("%s: empty cube" % c["name"])
        if c["rot"] and c["rot"][1] not in ANGLES:
            errs.append("%s: angle %s" % (c["name"], c["rot"][1]))
    for k, d in disp.items():
        if any(abs(v) > 80 for v in d["translation"]):
            errs.append("display %s: translation %s" % (k, d["translation"]))
        if any(not 0 < v <= 4 for v in d["scale"]):
            errs.append("display %s: scale %s" % (k, d["scale"]))
    names = [c["name"] for c in w.cubes]
    if len(set(names)) != len(names):
        errs.append("duplicate cube names")
    if errs:
        sys.exit(w.id + ":\n  " + "\n  ".join(errs))


# ------------------------------------------------------------------------------------------- textures

def swatch(hexa, emissive, seed):
    """Placeholder swatch: base colour, light grain, soft diagonal sheen (metal) or inner glow (emissive)."""
    r = np.random.default_rng(seed)
    base = np.array(rgb(hexa), float)
    y, x = np.mgrid[0:T, 0:T]
    v = 1 + 0.05 * (r.random((T, T)) - 0.5)
    if emissive:
        d = np.hypot(x - T / 2 + 0.5, y - T / 2 + 0.5) / (T / 2)
        v = v * (1.12 - 0.18 * d)
    else:
        v = v * (1.04 - 0.08 * ((x + y) % 16) / 15)
    px = np.clip(base * v[..., None], 0, 255)
    return Image.fromarray(np.dstack([px, np.full((T, T), 255)]).astype(np.uint8), "RGBA")


# ------------------------------------------------------------------------------------------- model json

def uv(face, f, t):
    """Each face takes a window of its own size in the swatch (1 unit = 1 uv unit), capped at the swatch."""
    sx, sy, sz = (b - a for a, b in zip(f, t))
    wdt, hgt = {"north": (sx, sy), "south": (sx, sy), "east": (sz, sy), "west": (sz, sy),
                "up": (sx, sz), "down": (sx, sz)}[face]
    return [0, 0, round(min(wdt, 16), 3), round(min(hgt, 16), 3)]


def model(w):
    elements, groups = [], {}
    for i, c in enumerate(w.cubes):
        e = {"name": c["name"], "from": [round(v, 3) for v in c["from"]], "to": [round(v, 3) for v in c["to"]]}
        if c["rot"]:
            ax, ang, o = c["rot"]
            e["rotation"] = {"angle": ang, "axis": ax, "origin": [round(v, 3) for v in o]}
        e["faces"] = {fc: {"uv": uv(fc, c["from"], c["to"]), "texture": "#" + c["mat"]} for fc in FACES}
        if w.materials[c["mat"]][2]:
            e["light_emission"] = 15
        elements.append(e)
        groups.setdefault(c["part"], []).append(i)
    tex = {m: "%s:item/armes/%s/%s" % (NS, w.id, m) for m in w.materials}
    tex["particle"] = tex[next(iter(w.materials))]
    return {
        "credit": "Influencraft, generated by plugins/fluxplugin/FluxDungeons/assets/armes/gen_armes.py",
        "gui_light": "front",
        "textures": tex,
        "elements": elements,
        "display": displays(w),
        "groups": [{"name": p, "origin": [8, 8, 8], "color": 0, "children": ids} for p, ids in groups.items()],
    }


# ------------------------------------------------------------------------------------------- previews

SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
NORMAL = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}


def face_pts(face, f, t):
    (x1, y1, z1), (x2, y2, z2) = f, t
    return {"north": [(x2, y2, z1), (x1, y2, z1), (x1, y1, z1), (x2, y1, z1)],
            "south": [(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)],
            "west": [(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)],
            "east": [(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)],
            "up": [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)],
            "down": [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)]}[face]


def quads(cubes, colours, M=np.eye(3), b=np.zeros(3)):
    """World quads (4 points, colour, emissive) of cubes posed by world = M . p + b."""
    out = []
    for c in cubes:
        R, o = np.eye(3), np.zeros(3)
        if c["rot"]:
            R = {"x": rx, "y": ry, "z": rz}[c["rot"][0]](c["rot"][1])
            o = np.array(c["rot"][2], float)
        col, lum = colours[c["mat"]]
        for fc in FACES:
            n = M @ R @ np.array(NORMAL[fc], float)
            pts = [M @ (R @ (np.array(p, float) - o) + o) + b for p in face_pts(fc, c["from"], c["to"])]
            out.append((pts, n, col, lum, fc))
    return out


def draw(qs, view, size, scale, centre=(0, 0), persp=None, bg=(139, 139, 139)):
    """Painter-free z-buffer rasteriser. view: 3x3 applied to world points; persp = focal (camera at origin
    looking -Z) or None for orthographic (larger z = closer)."""
    img = np.zeros((size, size, 3))
    img[:] = bg
    zb = np.full((size, size), -1e9)
    L = np.array([0.3, 0.8, 0.5])
    L /= np.linalg.norm(L)
    for pts, n, col, lum, fc in qs:
        n = view @ n
        P = [view @ p for p in pts]
        if persp:
            if any(p[2] > -0.5 for p in P):
                continue
            facing = np.dot(n, -P[0]) > 0
            proj = [((p[0] / -p[2]) * persp, (p[1] / -p[2]) * persp, 1 / -p[2]) for p in P]
        else:
            facing = n[2] > 1e-6
            proj = [(p[0], p[1], p[2]) for p in P]
        if not facing:
            continue
        k = 1.0 if lum else 0.45 + 0.55 * max(0.0, float(np.dot(n / np.linalg.norm(n), L)))
        c = np.array(col, float) * k
        S = np.array([[(x - centre[0]) * scale + size / 2, -(y - centre[1]) * scale + size / 2, z] for x, y, z in proj])
        for tri in ((0, 1, 2), (0, 2, 3)):
            Q = S[list(tri)]
            xmin, xmax = int(max(0, Q[:, 0].min())), int(min(size - 1, Q[:, 0].max() + 1))
            ymin, ymax = int(max(0, Q[:, 1].min())), int(min(size - 1, Q[:, 1].max() + 1))
            if xmin > xmax or ymin > ymax:
                continue
            xs, ys = np.meshgrid(np.arange(xmin, xmax + 1) + 0.5, np.arange(ymin, ymax + 1) + 0.5)
            (x0, y0), (x1, y1), (x2, y2) = Q[0, :2], Q[1, :2], Q[2, :2]
            den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if abs(den) < 1e-9:
                continue
            a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
            bb = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
            cc = 1 - a - bb
            inside = (a >= -1e-6) & (bb >= -1e-6) & (cc >= -1e-6)
            z = a * Q[0, 2] + bb * Q[1, 2] + cc * Q[2, 2]
            sub = zb[ymin:ymax + 1, xmin:xmax + 1]
            ok = inside & (z > sub)
            sub[ok] = z[ok]
            img[ymin:ymax + 1, xmin:xmax + 1][ok] = c
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")


def colours_of(w):
    return {m: (rgb(h), lum) for m, (h, _, lum) in w.materials.items()}


PLAYER = {"tete": ("#C8956D", [-4, -8, -4], [4, 0, 4]), "corps": ("#3D7A7A", [-4, 0, -2], [4, 12, 2]),
          "bras_g": ("#3D7A7A", [4, 0, -2], [8, 12, 2]), "jambe_d": ("#3B4FA0", [-4, 12, -2], [0, 24, 2]),
          "jambe_g": ("#3B4FA0", [0, 12, -2], [4, 24, 2])}


def third_person(w, disp, arm_pitch=-18.0):
    """Player and weapon in entity model space (pixels, Y down, front -Z), following the game chain:
    arm pivot and rotation, ItemInHandLayer (X -90, Y 180, translate 1, 2, -10 px), display transform, -8."""
    cubes = []
    for k, (col, f, t) in PLAYER.items():
        cubes.append(({"name": k, "from": f, "to": t, "mat": k, "rot": None}, rgb(col)))
    qs = []
    for c, col in cubes:
        qs += quads([c], {c["mat"]: (col, False)})
    pivot = np.array([-5.0, 2.0, 0.0])
    Ra = rx(arm_pitch)
    arm = {"name": "bras_d", "from": [-3, -2, -2], "to": [1, 10, 2], "mat": "bras", "rot": None}
    qs += quads([arm], {"bras": (rgb("#3D7A7A"), False)}, Ra, pivot)
    d = disp["thirdperson_righthand"]
    s = d["scale"][0]
    L = rx(-90) @ ry(180)
    M = Ra @ L @ rxyz(d["rotation"]) * s
    b = pivot + Ra @ L @ (np.array([1.0, 2.0, -10.0]) + np.array(d["translation"])) - M @ C8
    qs += quads(w.cubes, colours_of(w), M, b)
    # Entity space -> picture space: Y up, the player facing the viewer.
    flip = np.diag([1.0, -1.0, -1.0])
    return [([flip @ p for p in pts], flip @ n, col, lum, fc) for pts, n, col, lum, fc in qs]


def first_person(w, disp):
    """Camera space (pixels, looking -Z). The item frame is seen mirrored along Z: checked against the vanilla
    sword, whose blade then leaves the lower right corner towards the centre as in game."""
    d = disp["firstperson_righthand"]
    s = d["scale"][0]
    flip = np.diag([1.0, 1.0, -1.0])
    M = flip @ rxyz(d["rotation"]) * s
    b = np.array([0.56, -0.52, -0.72]) * 16 + flip @ np.array(d["translation"]) - M @ C8
    return quads(w.cubes, colours_of(w), M, b)


def previews(w, disp, folder):
    os.makedirs(folder, exist_ok=True)
    col = colours_of(w)
    qs = quads(w.cubes, col, b=-np.array([8.0, 8.0, 8.0]))
    lo, hi = bounds(w.cubes)
    cy = (lo[1] + hi[1]) / 2 - 8
    H = 560
    sc = H / 52.0
    sheet = Image.new("RGB", (H * 4, H), (139, 139, 139))
    for i, v in enumerate([np.eye(3), ry(90), rx(12) @ ry(-35), rx(12) @ ry(215)]):
        sheet.paste(draw(qs, v, H, sc, centre=(0, cy)), (H * i, 0))
    sheet.save(os.path.join(folder, w.id + "_vues.png"))
    # Inventory icon at slot scale (16 px) and enlarged.
    g = disp["gui"]
    A = rxyz(g["rotation"]) * g["scale"][0]
    gq = quads(w.cubes, col, A, np.array(g["translation"], float) - A @ C8)
    icon = draw(gq, np.eye(3), 288, 288 / 16.0)
    icon.save(os.path.join(folder, w.id + "_icone.png"))
    small = draw(gq, np.eye(3), 64, 64 / 16.0).resize((16, 16), Image.LANCZOS)
    small.resize((128, 128), Image.NEAREST).save(os.path.join(folder, w.id + "_icone_16px.png"))
    # Third person: front three-quarter and side.
    tq = third_person(w, disp)
    tp = Image.new("RGB", (H * 2, H), (139, 139, 139))
    tp.paste(draw(tq, rx(8) @ ry(-35), H, H / 64.0, centre=(0, -10)), (0, 0))
    tp.paste(draw(tq, ry(-90), H, H / 64.0, centre=(0, -10)), (H, 0))
    tp.save(os.path.join(folder, w.id + "_troisieme_personne.png"))
    # First person: camera at origin, 70 degrees vertical FOV, 16:9.
    fq = first_person(w, disp)
    W, Hh = 640, 360
    f = (Hh / 2) / math.tan(math.radians(35))
    canvas = draw(fq, np.eye(3), W, 1.0, persp=f, bg=(120, 160, 210))
    canvas = canvas.crop(((W - W) // 2, (W - Hh) // 2, W, (W + Hh) // 2))
    ImageDraw.Draw(canvas).line([(W // 2 - 6, Hh // 2), (W // 2 + 6, Hh // 2)], fill=(255, 255, 255))
    ImageDraw.Draw(canvas).line([(W // 2, Hh // 2 - 6), (W // 2, Hh // 2 + 6)], fill=(255, 255, 255))
    canvas.save(os.path.join(folder, w.id + "_premiere_personne.png"))


# ------------------------------------------------------------------------------------------- sheet

def fmt(v):
    s = ("%.3f" % v).rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def sheet(weapons):
    out = ["# Armes de classe RPG : fiches de fabrication", "",
           "> Généré par `gen_armes.py` : ne pas modifier à la main, relancer le script.", "",
           "Repère commun : unités de modèle Minecraft (16 = un bloc), **Y vers le haut**, axe de l'arme selon Y en "
           "x = 8, z = 8, plat de l'arme dans le plan XY vu de face (+Z, face `south`), épaisseur selon Z. "
           "« de » et « à » sont les coins `from` / `to` du cube **avant** rotation ; une rotation tourne autour "
           "de son origine, sur un seul axe, par pas de 22,5° (positif = sens direct vu depuis le bout positif de "
           "l'axe : autour de Z, le haut penche vers -X). Suffixes `_g` / `_d` : gauche / droite vus de face.", ""]
    for w in weapons:
        disp = displays(w)
        lo, hi = bounds(w.cubes)
        size = hi - lo
        base, axis_angle, grip, tp, fp = DISPLAYS[w.id]
        out += ["## %s (%s) : `%s:%s`" % (w.name, w.cls, NS, w.id), "", w.art, "",
                "| Dimension | Valeur |", "|---|---|",
                "| Longueur (Y) | %s (de %s à %s) |" % (fmt(size[1]), fmt(lo[1]), fmt(hi[1])),
                "| Largeur max (X) | %s (de %s à %s) |" % (fmt(size[0]), fmt(lo[0]), fmt(hi[0])),
                "| Épaisseur max (Z) | %s |" % fmt(size[2]),
                "| Prise (point tenu par le poing) | (%s) |" % ", ".join(fmt(v) for v in grip),
                "| Centre visuel (milieu de l'encombrement) | (%s) |" % ", ".join(fmt(v) for v in (lo + hi) / 2),
                "| Longueur en main, 3e personne | %s px de joueur (joueur = 32) |" % fmt(size[1] * tp[1]),
                "| Nombre de cubes | **%d** |" % len(w.cubes), "",
                "Palette :", "", "| Matériau (texture) | Couleur | Lumineux |", "|---|---|---|"]
        for m, (h, label, lum) in w.materials.items():
            out.append("| `%s` | %s `%s` | %s |" % (m, label, h, "oui" if lum else ""))
        counts = {}
        for c in w.cubes:
            counts[c["part"]] = counts.get(c["part"], 0) + 1
        out += ["", "Hiérarchie (groupes, dans l'ordre de construction) : " +
                ", ".join("`%s` (%d)" % (p, n) for p, n in counts.items()), "",
                "| # | Groupe | Cube | De (x, y, z) | À (x, y, z) | Taille | Rotation | Matériau | Note |",
                "|---|---|---|---|---|---|---|---|---|"]
        for i, c in enumerate(w.cubes, 1):
            sz = [b - a for a, b in zip(c["from"], c["to"])]
            r = "" if not c["rot"] else "%s %s° autour de (%s)" % (c["rot"][0].upper(), fmt(c["rot"][1]),
                                                                    ", ".join(fmt(v) for v in c["rot"][2]))
            out.append("| %d | %s | `%s` | %s | %s | %s | %s | `%s` | %s |" % (
                i, c["part"], c["name"], " ".join(fmt(v) for v in c["from"]), " ".join(fmt(v) for v in c["to"]),
                " × ".join(fmt(v) for v in sz), r, c["mat"], c["note"]))
        out += ["", "Affichages (`display` du modèle) :", "", "| Contexte | Rotation | Translation | Échelle |", "|---|---|---|---|"]
        for k in ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand",
                  "gui", "fixed", "on_shelf", "ground", "head"):
            d = disp[k]
            out.append("| `%s` | %s | %s | %s |" % (k, " ".join(fmt(v) for v in d["rotation"]),
                                                   " ".join(fmt(v) for v in d["translation"]), fmt(d["scale"][0])))
        out.append("")
    return "\n".join(out)


# ------------------------------------------------------------------------------------------- main

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(data, fh, indent=1, ensure_ascii=False)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dest = sys.argv[1]
    pack = os.path.join(dest, "resourcepack/assets", NS)
    weapons = [f() for f in WEAPONS]
    for w in weapons:
        disp = displays(w)
        check(w, disp)
        texdir = os.path.join(pack, "textures/item/armes", w.id)
        os.makedirs(texdir, exist_ok=True)
        for i, (m, (h, _, lum)) in enumerate(w.materials.items()):
            swatch(h, lum, zlib.crc32((w.id + m).encode())).save(os.path.join(texdir, m + ".png"))
        write_json(os.path.join(pack, "models/item", w.id + ".json"), model(w))
        write_json(os.path.join(pack, "items", w.id + ".json"),
                   {"model": {"type": "minecraft:model", "model": "%s:item/%s" % (NS, w.id)}})
        if len(sys.argv) > 2:
            previews(w, disp, sys.argv[2])
        print("%-16s %3d cubes" % (w.id, len(w.cubes)))
    if len(sys.argv) > 3:
        with open(sys.argv[3], "w") as fh:
            fh.write(sheet(weapons))


if __name__ == "__main__":
    main()
