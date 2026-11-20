#!/usr/bin/env python3
"""Legacy of the Frost set ("Givre"): the plain cryomancer outfit, about ten cubes per piece.

    <venv with Pillow and numpy>/python3 gen_givre_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, givre.bbmodel and one editable Blockbench file
per piece: cryomancer_head, _chest, _legs, _feet). With --deploy it writes to Labs:
- plugins/FluxArmor/models/givre.bbmodel: coat, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn diadem casque_givre and the 3D inventory models of the other pieces.

The four pieces:
- Diadem of the Permafrost: one dark silver ring around a night blue cap, a low silver plate on top, a cut crystal on its mount at the brow, a
  leaning shard on each side (the left one shorter);
- Coat of the Glacial Zenith: slate cloth, night blue collar edged with silver, the emblem (glowing crystal in a silver
  lozenge), dark silver pauldron set with ice on the left, leather pad under a silver band on the right, two coat tails
  of different lengths;
- Veil of the Eternal Snows: night blue trousers, two tabard panels of different lengths, a dark silver knee set with
  ice on the left leg, a silver clasp on the right one;
- Boots of the White Silence: thick sole, leather, one angular silver plate over the foot, a small crystal.

Palette, materials and light: cryo.py. Grids, faces and emissive letters: setkit.py.
"""
from cryo import CORE, MATERIALS, MOUNT, PALETTE, dark, fabric, steel
from setkit import Cube, lit, mirror, run, setup, stamp

setup("givre", PALETTE, MATERIALS)


def hat():
    ring = stamp(steel(10, 2), 7, 1, "S")
    return lit([
        # One ring around the brow; its inside is never seen.
        Cube("bandeau", [-5, 29, -5], [5, 31, 5], "steel", sides=ring, down=None),
        Cube("calotte", [-4.5, 31, -4.5], [4.5, 33, 4.5], "dark"),
        Cube("couronne", [-2, 33, -2], [2, 34, 2], "silver"),
        # Crystal on its mount: a body and its point.
        Cube("monture", [-2, 29, 5], [2, 31, 6], "silver", north=None),
        Cube("cristal", [-1, 31, 5], [1, 35, 6], "crystal", south=["wy", "yG", "GG", "GE"], north=None),
        Cube("cristal_pointe", [-0.707, 34.293, 5], [0.707, 35.707, 6], "crystal", rot=(0, 0, 45), south=["w"], north=None),
        # A shard on each side, leaning outwards, the left one shorter.
        Cube("eclat_droit", [-6, 29, -1], [-5, 33, 1], "crystal", rot=(0, 0, 12), origin=[-5.5, 29, 0]),
        Cube("eclat_gauche", [5, 29, -1], [6, 32, 1], "crystal", rot=(0, 0, -12), origin=[5.5, 29, 0]),
        Cube("plaque_arriere", [-2, 29, -6], [2, 32, -5], "silver", south=None),
    ])


def torso():
    collar_back = stamp(dark(12, 5), 0, 0, "PLLLLLLLLLLS")
    wing = stamp(dark(6, 3), 0, 0, "LLLLLS")
    tail = dict(rot=(10, 0, 0))
    # Coat front: closing seam, a lighter lapel line under the collar.
    front = stamp(stamp(fabric(9, 12), 4, 0, ["N"] * 12), 2, 0, ["E   E", " E E "])
    return lit([
        # No top face: it is under the head.
        Cube("manteau", [-4.5, 12, -2.5], [4.5, 24, 2.5], "fabric", south=front, up=None),
        Cube("col_dos", [-6, 22, -6], [6, 27, -5], "dark", north=collar_back, south=collar_back, up="L"),
        Cube("col_droit", [-6, 24, -5], [-5, 27, 1], "dark", west=wing, east=wing, up="L", north=None),
        Cube("col_gauche", [5, 24, -5], [6, 27, 1], "dark", west=wing, east=wing, up="L", north=None),
        # Emblem: glowing crystal in a silver lozenge.
        Cube("embleme", [-2, 16, 2.5], [2, 20, 3.5], "silver", rot=(0, 0, 45), south=MOUNT, north=None),
        Cube("embleme_cristal", [-1, 17, 3.5], [1, 19, 4.5], "crystal", rot=(0, 0, 45), south=CORE, north=None),
        Cube("ceinture_manteau", [-5, 12, -3], [5, 14, 3], "leather"),
        Cube("boucle_manteau", [-1, 12, 3], [1, 14, 4], "silver", south=["PL", "LD"], north=None),
        # Coat tails of different lengths, leaning away from the legs, a lighter edge at the end.
        Cube("basque_droite", [-4.5, 6, -3.5], [-0.5, 12, -2.5], "fabric", origin=[-2.5, 12, -3], **tail,
             north=stamp(fabric(4, 6), 0, 5, "FFWF")),
        Cube("basque_gauche", [0.5, 8, -3.5], [4.5, 12, -2.5], "fabric", origin=[2.5, 12, -3], **tail,
             north=stamp(fabric(4, 4), 0, 3, "FFFF")),
    ])


def sleeve():
    """Cloth sleeve, its leather cuff painted on."""
    cuff5, cuff6 = ["RCCCN"], ["RCCCCN"]
    return Cube("manche", [-8.5, 14, -3], [-3.5, 24, 3], "fabric", south=stamp(fabric(5, 10), 0, 9, cuff5),
                north=stamp(fabric(5, 10), 0, 9, cuff5), west=stamp(fabric(6, 10), 0, 9, cuff6), east=stamp(fabric(6, 10), 0, 9, cuff6))


def right_arm():
    return [
        sleeve(),
        Cube("epaule_cuir", [-10, 22, -3.5], [-6, 24, 3.5], "hide"),
        Cube("renfort_epaule", [-10, 24, -1], [-6, 25, 1], "silver"),
    ]


def left_arm():
    return [
        mirror(sleeve()),
        Cube("epaule_argent", [6, 22, -3.5], [10, 25, 3.5], "steel"),
        Cube("epaule_glace", [7, 25, -1], [9, 26, 1], "crystal"),
    ]


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "leather"),
        Cube("boucle", [-3, 10, 3], [-1, 12, 4], "silver", south=["PL", "LD"], north=None),
    ]


def right_leg():
    return [
        Cube("pantalon", [-4.5, 5, -2.5], [0.5, 12, 2.5], "dark", up=None),  # under the belt
        Cube("pan_long", [-4, 5, 2.5], [0, 10, 3.5], "panel", west="S", east="A"),
        Cube("fermoir", [-5.5, 6, -1], [-4.5, 8, 1], "silver"),
    ]


def left_leg():
    return [
        mirror(right_leg()[0]),
        Cube("pan_court", [0.5, 7, 2.5], [3.5, 10, 3.5], "panel", west="S", east="A"),
        Cube("genouillere", [0.5, 5, 2.5], [4.5, 7, 3.5], "steel", south=["SLGD", "DDNN"]),
    ]


def right_boot():
    return [
        Cube("semelle", [-5, 0, -3], [1, 1, 5.5], "leather", south="N", north="N", west="N", east="N", up="C", down="N"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather"),
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 5], "leather"),
        # One silver plate over the foot, a crystal against the shaft.
        Cube("plaque", [-3.5, 3, 2.5], [-0.5, 4, 5], "silver"),
        Cube("cristal", [-2.5, 4, 2.5], [-1.5, 5, 3.5], "crystal"),
    ]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "jambieres_jambe_gauche": left_leg,
          "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-2.5, 0.5), "plastron": (0, 0.55), "jambieres": (0, 0.9), "bottes": (0, 0.9)},
        {"cryomancer_head": "tete", "cryomancer_chest": "plastron", "cryomancer_legs": "jambieres", "cryomancer_feet": "bottes"})
