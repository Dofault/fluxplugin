#!/usr/bin/env python3
"""Ashen Arcanum set ("Arcanum"): a scholar pyromancer of an old order, dark cloth and old copper, about ten cubes a piece.

    <venv with Pillow and numpy>/python3 gen_arcanum_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, list of cubes, arcanum.bbmodel and one editable
Blockbench file per piece). With --deploy it writes to Labs:
- plugins/FluxArmor/models/arcanum.bbmodel: coat, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn cowl casque_arcanum and the 3D inventory models of the other pieces.

The four pieces:
- Cowl of the Broken Sun: a charcoal hood edged with copper, and behind it a copper ring standing like a halo, broken
  on its upper right where an ember burns;
- Coat of the Combustion Seal: charcoal cloth, the seal on the chest (a copper lozenge under a dark red square: an
  eight-pointed sun, an ember at its heart), an ember red half cape over the left shoulder and the left of the back,
  a copper band on the right arm, a wide leather sash;
- Embers' Wake: ash brown trousers, a ceremonial panel split in two down the front with an ember climbing along the
  split, an ember red drape on the right hip, a leather band under the left knee;
- Cinder Treads: charcoal sole with a glowing seam, reddish leather, a copper rim on the toe, a turned-down cuff.

Light comes from the top left. Emissive pixels: the ember of the ring, the heart of the seal, the bottom of the panel
split, the seam on the outside of the soles. Grids and faces: setkit.py.
"""
from setkit import Cube, lit, mirror, paint, run, setup, stamp

PALETTE = {"N": "#211C1B", "A": "#382925", "T": "#57352D", "R": "#873D2E", "I": "#B84C30",  # charcoal, ash, reddish, ember, glowing
           "K": "#A7653D", "P": "#C8874D", "H": "#E5A34D", "O": "#F0C77B"}  # old copper, bright copper, amber, pale gold

# ---------------------------------------------------------------------------------------- painters


def fabric(w, h):
    """Thick smoked cloth: charcoal, a few straight folds in ash brown starting at different heights."""
    def pixel(x, y):
        if w >= 4 and x % 4 == 1 and int(h * 0.3) + ((x // 4) % 2) * max(1, int(h * 0.25)) <= y < h - 1:
            return "A"
        if w >= 3 and x == 0 and y < h * 0.35:
            return "A"
        return "N"
    return paint(w, h, pixel)


def ash(w, h):
    """Ash brown cloth of the trousers."""
    return paint(w, h, lambda x, y: "N" if w >= 3 and x == w - 1 or h >= 3 and y == h - 1 else ("T" if (x, y) == (0, 0) and w >= 3 else "A"))


def ember_cloth(w, h):
    """Deep ember red cloth, almost earthen: reddish brown folds and shade, never bright."""
    def pixel(x, y):
        if w >= 3 and x == w - 1 or w >= 5 and x % 3 == 2 and y >= h * 0.4:
            return "T"
        return "R"
    return paint(w, h, pixel)


def leather(w, h):
    """Patinated leather, warmer than the cloth."""
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "A"
        if w >= 3 and h >= 2 and y == 0 and x <= 1:
            return "R"
        return "T"
    return paint(w, h, pixel)


def copper(w, h):
    """Old copper: bright along the lit part of the top edge, one amber pixel, reddish shade, no outline."""
    def pixel(x, y):
        if h >= 2 and y == h - 1 or w >= 3 and x == w - 1:
            return "T"
        if y == 0 and x < (w + 1) // 2:
            return "H" if x == 0 and w >= 3 else "P"
        return "K"
    return paint(w, h, pixel)


def sole(w, h):
    """Charcoal sole, a seam glowing along it: ember red at its ends, amber in the middle."""
    def pixel(x, y):
        if h == 1 and 1 <= x < w - 1:
            return "h" if abs(x - (w - 1) / 2) < 1 else ("i" if abs(x - (w - 1) / 2) < w / 4 else "R")
        return "N"
    return paint(w, h, pixel)


setup("arcanum", PALETTE, {
    "fabric": (fabric, "A", "N"), "ash": (ash, "T", "N"), "ember_cloth": (ember_cloth, "R", "T"),
    "leather": (leather, "T", "A"), "copper": (copper, "P", "T"), "sole": (sole, "A", "N")})

EMBER = ["hi", "ir"]

# ---------------------------------------------------------------------------------------- pieces


def hat():
    brim = stamp(fabric(9, 2), 0, 1, "PKKKKKKKT")
    ring = dict(north=None)
    return lit([
        # Hood: top, back and sides, its front edge standing over the brow with a copper trim.
        Cube("capuche_dessus", [-4.5, 31, -4.5], [4.5, 33, 4.5], "fabric"),
        Cube("capuche_dos", [-4.5, 25, -5], [4.5, 31, -4], "fabric", south=None),
        Cube("capuche_droite", [-5, 26, -4], [-4, 31, 2], "fabric", east=None),
        Cube("capuche_gauche", [4, 26, -4], [5, 31, 2], "fabric", west=None),
        Cube("capuche_bord", [-4.5, 31, 4.5], [4.5, 33, 5.5], "fabric", south=brim, north=None),
        # Broken sun: a copper ring standing behind the hood, resting on its back.
        Cube("anneau_bas", [-5, 26, -6], [5, 27, -5], "copper"),
        Cube("anneau_gauche", [5, 27, -6], [6, 33, -5], "copper"),
        Cube("anneau_droit", [-6, 27, -6], [-5, 32, -5], "copper"),
        Cube("anneau_oblique", [3.75, 32.6, -6], [4.75, 36.9, -5], "copper", rot=(0, 0, 35)),
        Cube("anneau_haut", [-2, 36, -6], [3, 37, -5], "copper"),
        # The ember burning in the break of the ring.
        Cube("braise", [-5.5, 32.6, -6], [-3.5, 34.6, -5], "copper", True, rot=(0, 0, 45), south=EMBER, north=EMBER,
             west=["i", "r"], east=["i", "r"], up=["hi"], down=["ir"]),
    ])


def torso():
    return lit([
        # No top face: it is under the head.
        Cube("manteau", [-4.5, 12, -2.5], [4.5, 24, 2.5], "fabric", up=None),
        # Combustion seal: a copper lozenge under a dark red square make an eight-pointed sun, an ember at its heart.
        Cube("sceau_losange", [-2, 16.5, 2.5], [2, 20.5, 3.5], "copper", rot=(0, 0, 45), north=None),
        Cube("sceau_carre", [-1.5, 17, 3.5], [1.5, 20, 4.5], "ember_cloth", south=["RRR", "RhR", "RRT"], north=None,
             west="T", east="T", up="R", down="T"),
        # Half cape on the left of the back, wide leather sash and its copper clasp.
        # It stops where the shoulder cape starts: their back faces never lie in the same plane.
        Cube("demi_cape", [-0.5, 13, -3.5], [3.5, 24, -2.5], "ember_cloth", north=stamp(ember_cloth(4, 11), 0, 10, "TKKP"), south=None),
        Cube("echarpe", [-5, 12, -3], [5, 14, 3], "leather"),  # below the sleeves
        Cube("fermoir", [-4, 12, 3], [-2, 14, 4], "copper", north=None),
    ])


def sleeve():
    return Cube("manche", [-8.5, 14, -3], [-3.5, 24, 3], "fabric")


def right_arm():
    """Bare sleeve with a copper band."""
    return [sleeve(), Cube("brassard", [-9, 19, -3.5], [-3, 21, 3.5], "copper")]


def left_arm():
    """The half cape falls over this shoulder."""
    hem6, hem7 = "TKKKKP", "PKKKKKT"
    return [
        mirror(sleeve()),
        Cube("cape_epaule", [3.5, 20, -3.5], [9.5, 25, 3.5], "ember_cloth", south=stamp(ember_cloth(6, 5), 0, 4, hem6),
             north=stamp(ember_cloth(6, 5), 0, 4, hem6), east=stamp(ember_cloth(7, 5), 0, 4, hem7), west=None),
    ]


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "leather", sides=["RTTTTTTTTA", "AAAAAAAAAN"]),
        Cube("boucle", [-1, 10, 3], [1, 12, 4], "copper", north=None),
    ]


def right_leg():
    """Half of the ceremonial panel (the ember climbs along the split), an ember red drape on the hip."""
    return lit([
        Cube("pantalon", [-4.5, 5, -2.5], [0.5, 12, 2.5], "ash", up=None),  # under the belt
        Cube("pan", [-2, 6, 2.5], [0, 10, 3.5], "fabric", south=["KA", "KT", "KR", "Ki"], west="K", east="N", down="N"),
        Cube("drape_hanche", [-5.5, 5, -3], [-4.5, 10, 3], "ember_cloth", west=stamp(ember_cloth(6, 5), 0, 4, "TKKKKP")),
    ])


def left_leg():
    """The other half of the panel, a leather band under the knee."""
    right = right_leg()
    return lit([
        mirror(right[0]),
        mirror(right[1]),
        Cube("bande_genou", [-0.5, 6, -3], [5, 8, 3], "leather", east=stamp(leather(6, 2), 2, 0, ["PK", "KT"])),
    ])


def right_boot():
    return lit([
        Cube("semelle", [-5, 0, -3], [1, 1, 6], "sole", south="N", north="N", east="N"),  # the seam shows on the outside only
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather"),
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 4.5], "leather"),
        Cube("bout_cuivre", [-4.5, 1, 4.5], [0.5, 2, 5.5], "copper"),
        Cube("revers", [-5, 5, -3], [1, 6, 3], "leather", south="A", north="A", west="A", east="A", up="K"),
    ])


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "jambieres_jambe_gauche": left_leg,
          "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-2.5, 0.5), "plastron": (0, 0.55), "jambieres": (0, 0.9), "bottes": (0, 0.9)},
        {"pyromancer_arcanum_head": "tete", "pyromancer_arcanum_chest": "plastron", "pyromancer_arcanum_legs": "jambieres",
         "pyromancer_arcanum_feet": "bottes"})
