#!/usr/bin/env python3
"""Throne of the Thousand Winters set ("Hivers"): the rich cryomancer outfit, about ninety cubes in layers.

    <venv with Pillow and numpy>/python3 gen_hivers_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, hivers.bbmodel and one editable Blockbench file
per piece: cryomancer_head, _chest, _legs, _feet). With --deploy it writes to Labs:
- plugins/FluxArmor/models/hivers.bbmodel: coat, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn diadem casque_hivers and the 3D inventory models of the other pieces.

The four pieces:
- Diadem of the Permafrost: dark silver band over a night blue cap, a stepped silver tiara holding a long cut crystal
  between two claws, two shards on the right, a silver wing and one shard on the left;
- Coat of the Eternal Zenith: slate cloth, a lighter stole down the front carrying the emblem (silver lozenge, glowing
  crystal, three shards), a tall night blue collar edged with silver, a short cape ending in a point on the back,
  layered silver pauldron on the left, leather one on the right, a chain and its crystal on the chest;
- Veil of the Eternal Snows: night blue trousers, two tabard panels of different lengths ending in a point, a panel on
  the back, layered guard and silver knee on the left leg, strap and clasp on the right one;
- Boots of the White Silence: thick sole, leather, silver toe, instep and heel, a rimed cuff, one crystal on each
  instep (the right one larger).

Palette, materials and light: cryo.py. Grids, faces and emissive letters: setkit.py.
"""
from cryo import CORE, MATERIALS, MOUNT, PALETTE, crystal, dark, fabric, hide, leather, panel, silver, steel
from setkit import Cube, lit, mirror, run, setup, stamp

setup("hivers", PALETTE, MATERIALS)

# ---------------------------------------------------------------------------------------- head


def hat():
    band, side = stamp(steel(10, 2), 7, 1, "S"), stamp(steel(8, 2), 5, 1, "S")
    lean = dict(rot=(0, 0, 12), origin=[-5.5, 30, 0])
    return lit([
        # Band around the brow, cloth cap over the skull.
        Cube("bandeau_avant", [-5, 29, 4], [5, 31, 5], "steel", south=band, north=None),
        Cube("bandeau_arriere", [-5, 29, -5], [5, 31, -4], "steel", north=band, south=None),
        Cube("bandeau_droit", [-5, 29, -4], [-4, 31, 4], "steel", west=side, east=None),
        Cube("bandeau_gauche", [4, 29, -4], [5, 31, 4], "steel", east=side, west=None),
        Cube("calotte", [-4.5, 31, -4.5], [4.5, 33, 4.5], "dark"),
        # Stepped tiara.
        Cube("tiare_bas", [-3, 31, 4], [3, 34, 5], "silver"),
        Cube("tiare_haut", [-2, 34, 4], [2, 36, 5], "silver"),
        # Main crystal: a long body pointed at both ends, held by two claws.
        Cube("cristal", [-1, 32, 5], [1, 37, 6], "crystal", south=["wy", "yG", "GG", "GE", "GE"], north=None),
        Cube("cristal_pointe", [-0.707, 36.293, 5], [0.707, 37.707, 6], "crystal", rot=(0, 0, 45), south=["w"], north=None),
        Cube("cristal_base", [-0.707, 31.293, 5], [0.707, 32.707, 6], "crystal", rot=(0, 0, 45), south=["E"], north=None),
        Cube("griffe_droite", [-2, 31, 5], [-1, 34, 6], "silver", north=None),
        Cube("griffe_gauche", [1, 31, 5], [2, 34, 6], "silver", north=None),
        # Right side: two shards leaning outwards. Left side: a silver wing and one shard.
        Cube("eclat_droit", [-6, 30, -1], [-5, 34, 1], "crystal", **lean),
        Cube("eclat_droit_court", [-6, 30, 1], [-5, 33, 3], "crystal", **lean),
        Cube("aile_gauche", [5, 29, -2], [6, 32, 2], "silver"),
        Cube("eclat_gauche", [5, 32, -1], [6, 33, 1], "crystal"),
        # Cheek tabs and back plate.
        Cube("patte_droite", [-5, 27, 0], [-4, 29, 3], "steel"),
        Cube("patte_gauche", [4, 27, 0], [5, 29, 3], "steel"),
        Cube("plaque_arriere", [-2, 31, -5], [2, 34, -4], "steel", south=None),
        Cube("bossage_arriere", [-1, 32, -6], [1, 33, -5], "silver", south=None),
    ])


# ---------------------------------------------------------------------------------------- chest


def torso():
    collar_back = stamp(dark(12, 6), 0, 0, "PLLLLLLLLLLS")
    wing = stamp(dark(4, 4), 0, 0, "LLLS")
    step = stamp(dark(3, 2), 0, 0, "PLS")
    stole = stamp(stamp(paint_stole(), 0, 0, ["S"] * 10), 5, 0, ["A"] * 10)
    return lit([
        # No top face: it is under the head.
        Cube("manteau", [-4.5, 12, -2.5], [4.5, 24, 2.5], "fabric", up=None),
        # Lighter stole over the coat, a silver border on its lit edge.
        Cube("etole", [-3, 13, 2.5], [3, 23, 3.5], "panel", south=stole, north=None, west="S", east="A", up="F"),
        # Tall collar behind the head and on the shoulders, lower towards the front.
        Cube("col_dos", [-6, 22, -6], [6, 28, -5], "dark", north=collar_back, south=collar_back, up="L"),
        Cube("col_droit", [-6, 24, -5], [-5, 28, -1], "dark", west=wing, east=wing, up="L", north=None),
        Cube("col_gauche", [5, 24, -5], [6, 28, -1], "dark", west=wing, east=wing, up="L", north=None),
        Cube("col_droit_bas", [-6, 24, -1], [-5, 26, 2], "dark", west=step, east=step, up="L"),
        Cube("col_gauche_bas", [5, 24, -1], [6, 26, 2], "dark", west=step, east=step, up="L"),
        # Short cape on the back, ending in a point.
        Cube("cape", [-5, 17, -3.5], [5, 22, -2.5], "dark", north=stamp(dark(10, 5), 0, 4, "SDDDDDDDDD")),
        Cube("cape_milieu", [-3, 15, -3.5], [3, 17, -2.5], "dark", north=stamp(dark(6, 2), 0, 1, "SDDDDD")),
        Cube("cape_pointe", [-1, 13, -3.5], [1, 15, -2.5], "dark", north=["NN", "SW"]),
        # Emblem: glowing crystal in a silver lozenge, one shard above, two below.
        Cube("embleme", [-2, 16, 3.5], [2, 20, 4.5], "silver", rot=(0, 0, 45), south=MOUNT, north=None),
        Cube("embleme_cristal", [-1, 17, 4.5], [1, 19, 5.5], "crystal", rot=(0, 0, 45), south=CORE, north=None),
        Cube("embleme_eclat_haut", [-0.707, 20.793, 3.5], [0.707, 22.207, 4.5], "crystal", rot=(0, 0, 45), south=["Y"], north=None),
        Cube("embleme_eclat_droit", [-2.5, 13.6, 3.5], [-1.5, 16.6, 4.5], "crystal", rot=(0, 0, 35), north=None),
        Cube("embleme_eclat_gauche", [1.5, 13.6, 3.5], [2.5, 16.6, 4.5], "crystal", rot=(0, 0, -35), north=None),
        # Chain hanging from a brooch on the right of the chest, a crystal at its end.
        Cube("broche", [-5, 22, 2.5], [-3, 23, 3.5], "silver", north=None),
        Cube("chaine", [-4.5, 19, 2.5], [-3.5, 22, 3.5], "steel", south=["S", "D", "S"], north=None),
        Cube("chaine_cristal", [-4.5, 17, 2.5], [-3.5, 19, 3.5], "crystal", north=None),
        # Belt and its buckle, a long panel on the right hip.
        Cube("ceinture_manteau", [-5, 12, -3], [5, 14, 3], "leather"),
        Cube("boucle_manteau", [-1, 12, 3], [1, 14, 4], "silver", south=["PL", "LD"], north=None),
        Cube("pan_hanche", [-5.5, 5, -2.5], [-4.5, 12, 2.5], "fabric", west=stamp(fabric(5, 7), 0, 5, ["SSSSS", "FFWFF"])),
    ])


def paint_stole():
    return panel(6, 10)


def sleeve():
    cuff6, cuff7 = stamp(leather(6, 2), 2, 0, "L"), stamp(leather(7, 2), 3, 0, "L")
    return [
        Cube("manche", [-8.5, 14, -3], [-3.5, 24, 3], "fabric"),
        Cube("poignet", [-9, 14, -3.5], [-3, 16, 3.5], "leather", south=cuff6, north=cuff6, west=cuff7, east=cuff7),
    ]


def right_arm():
    """Leather pauldron: a pad, a hanging flap, a silver band and a small crystal on the outside."""
    return sleeve() + [
        Cube("epaule_cuir", [-10, 22, -3.5], [-6, 25, 3.5], "hide"),
        Cube("lame_cuir", [-9.5, 19, -3], [-8.5, 22, 3], "leather"),
        Cube("renfort_epaule", [-10, 25, -1], [-6, 26, 1], "silver"),
        Cube("cristal_epaule", [-11, 22, -1], [-10, 24, 1], "crystal"),
    ]


def left_arm():
    """Layered silver pauldron: dark base, a silver plate leaning outwards, ice set in it, a hanging lame."""
    tilt = dict(rot=(0, 0, -10), origin=[6, 24, 0])
    return [mirror(c) for c in sleeve()] + [
        Cube("epaule_base", [6, 21, -3.5], [10, 24, 3.5], "steel"),
        Cube("epaule_plaque", [6, 24, -3], [10, 25, 3], "silver", **tilt),
        Cube("epaule_glace", [7, 25, -1], [9, 26, 1], "crystal", **tilt),
        Cube("epaule_lame", [8.5, 18, -3], [9.5, 21, 3], "silver"),
    ]


# ---------------------------------------------------------------------------------------- legs


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "leather"),
        Cube("boucle", [-3, 10, 3], [-1, 12, 4], "silver", rot=(0, 0, 45), south=["PL", "LD"], north=None),
        Cube("passant_1", [1, 10, 3], [2, 12, 4], "hide", north=None),
        Cube("passant_2", [3, 10, 3], [4, 12, 4], "hide", north=None),
    ]


def right_leg():
    """Cloth leg: long tabard panel and its point, a panel on the back, a strap under the knee and its clasp."""
    return [
        Cube("pantalon", [-4.5, 5, -2.5], [0.5, 12, 2.5], "dark", up=None),  # under the belt
        Cube("pan_long", [-4, 5, 2.5], [0, 10, 3.5], "panel", west="S", east="A"),
        Cube("pan_long_pointe", [-3, 4, 2.5], [-1, 5, 3.5], "panel", south=["FW"], west="S", east="A"),
        Cube("pan_arriere", [-4, 6, -3.5], [0, 10, -2.5], "panel", west="S", east="A"),
        Cube("sangle", [-5, 6, -3], [0, 7, 3], "leather"),
        Cube("fermoir", [-6, 5, -1], [-5, 8, 1], "silver", west=["LP", "SG", "DD"]),
    ]


def left_leg():
    """Guarded leg: short panel and its point, silver knee, leather guard under two plates, a crystal on a link."""
    return [
        mirror(right_leg()[0]),
        Cube("pan_court", [0.5, 7, 2.5], [3.5, 10, 3.5], "panel", west="S", east="A"),
        Cube("pan_court_pointe", [1.5, 6, 2.5], [2.5, 7, 3.5], "panel", south=["W"], west="S", east="A"),
        Cube("genouillere", [0.5, 4, 2.5], [4.5, 6, 3.5], "silver"),
        Cube("cuissard", [4.5, 6, -2.5], [5.5, 11, 2.5], "hide"),
        Cube("cuissard_plaque", [5.5, 7, -2], [6.5, 10, 2], "silver"),
        Cube("cuissard_haut", [5.5, 10, -1], [6.5, 11, 1], "steel"),
        Cube("cuissard_glace", [6.5, 8, -1], [7.5, 9, 1], "crystal"),
        Cube("maillon", [3.5, 9, 2.5], [4.5, 10, 3.5], "steel", north=None),
        Cube("pendentif", [3.5, 7, 2.5], [4.5, 9, 3.5], "crystal", north=None),
    ]


# ---------------------------------------------------------------------------------------- feet


def boot(gem):
    """Right boot; gem = width of the crystal on its instep."""
    return [
        Cube("semelle", [-5, 0, -3], [1, 1, 6], "leather", south="N", north="N", west="N", east="N", up="C", down="N"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather"),
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 4.5], "leather"),
        Cube("coque", [-4.5, 1, 4.5], [0.5, 3, 5.5], "steel"),
        Cube("cou_de_pied", [-3.5, 3, 2.5], [-0.5, 4, 4.5], "silver"),
        Cube("cristal", [-2 - gem / 2, 4, 2.5], [-2 + gem / 2, 5, 3.5], "crystal"),
        # Ankle strap and its buckle on the outside, heel counter, rimed cuff.
        Cube("sangle_cheville", [-5, 3, -3], [1, 4, 3], "hide", south="R", north="R", west="R", east="R", up="R"),
        Cube("boucle_cheville", [-6, 2, -1], [-5, 4, 1], "silver"),
        Cube("talon", [-4, 1, -3.5], [0, 3, -2.5], "steel"),
        Cube("revers", [-5, 5, -3], [1, 6, 3], "frost", south="F", north="F", west="F", east="F"),
    ]


def right_boot():
    return boot(2)


def left_boot():
    return [mirror(c) for c in boot(1)]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "jambieres_jambe_gauche": left_leg,
          "bottes_jambe_droite": right_boot, "bottes_jambe_gauche": left_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-3.0, 0.48), "plastron": (0, 0.52), "jambieres": (0, 0.85), "bottes": (0, 0.9)},
        {"cryomancer_head": "tete", "cryomancer_chest": "plastron", "cryomancer_legs": "jambieres", "cryomancer_feet": "bottes"})
