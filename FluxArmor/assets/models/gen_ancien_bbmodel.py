#!/usr/bin/env python3
"""Legacy of the Old Grove set ("Ancien"): the plain druid outfit, about ten cubes per piece.

    <venv with Pillow and numpy>/python3 gen_ancien_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, ancien.bbmodel and one editable Blockbench file
per piece: druid_head, _chest, _legs, _feet). With --deploy it writes to Labs:
- plugins/FluxArmor/models/ancien.bbmodel: coat, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn crown casque_ancien and the 3D inventory models of the other pieces.

The four pieces:
- Crown of the Old Grove: one bark ring over a moss cap, a leaning branch with its bud, a twig and its leaf on each
  side (the left one shorter), an old flower on the left of the brow;
- Coat of the Forest Awakening: forest cloth, low moss collar edged with linen, the emblem (a living seed, its shoot
  and two leaves), bark pauldron with a leaf on the left, root pad with a leaf on the right, braided root belt, one
  coat tail;
- Leggings of the Deep Roots: two tabard panels of different lengths, bark guard under a root and a leaf on the left
  leg, a root tie and a shoot on the right one;
- Ankle boots of the Root-Walker: thick sole, leather, a bark plate over the foot, a root around the ankle, a leaf.

Palette, materials and light: druid.py. Grids, faces and emissive letters: setkit.py.
"""
from druid import FLOWER, MATERIALS, PALETTE, SEED, moss
from setkit import Cube, lit, mirror, run, setup, stamp

setup("ancien", PALETTE, MATERIALS)


def hat():
    return lit([
        # One bark ring around the brow, its inside never seen, over a moss cap.
        Cube("bandeau", [-5, 29, -5], [5, 31, 5], "bark", down=None),
        Cube("calotte", [-4.5, 31, -4.5], [4.5, 33, 4.5], "moss"),  # above the top of the head
        # Leaning branch and its bud.
        Cube("branche", [-3, 31, 1], [-1, 36, 3], "wood", rot=(0, 0, 20), origin=[-2, 31, 2]),
        Cube("bourgeon", [-5, 35, 1.5], [-3, 37, 2.5], "sprout"),
        # A twig and its leaf on each side, the left one shorter.
        Cube("rameau_droit", [-6, 30, -1], [-5, 34, 0], "wood", rot=(0, 0, 20), origin=[-5.5, 30, -0.5]),
        Cube("feuille_droite", [-8, 33, -1.5], [-6, 34, 0.5], "leaf"),
        Cube("rameau_gauche", [5, 30, -1], [6, 32, 0], "wood", rot=(0, 0, -20), origin=[5.5, 30, -0.5]),
        Cube("feuille_gauche", [6, 31, -1.5], [8, 32, 0.5], "leaf"),
        # Old flower on the left of the brow.
        Cube("fleur", [2, 29, 5], [5, 32, 5.5], "linen", south=FLOWER, north=None, west=None, east=None, up=None, down=None),
        Cube("fleur_coeur", [3, 30, 5.5], [4, 31, 6.5], "seed", north=None),
    ])


def torso():
    collar_back = stamp(moss(12, 4), 0, 0, "LLLLLLLLLLLO")
    wing = stamp(moss(5, 2), 0, 0, "LLLLO")
    return lit([
        # No top face: it is under the head.
        Cube("tunique", [-4.5, 12, -2.5], [4.5, 24, 2.5], "fabric", up=None),
        Cube("col_dos", [-6, 22, -6], [6, 26, -5], "moss", north=collar_back, south=collar_back),
        Cube("col_droit", [-6, 24, -5], [-5, 26, 0], "moss", west=wing, east=wing, north=None),
        Cube("col_gauche", [5, 24, -5], [6, 26, 0], "moss", west=wing, east=wing, north=None),
        # Emblem: a living seed, its shoot and two open leaves.
        Cube("embleme_graine", [-1, 15.5, 2.5], [1, 17.5, 3.5], "seed", south=SEED, north=None),
        Cube("embleme_pousse", [-0.5, 17.5, 2.5], [0.5, 21, 3.5], "sprout", north=None),
        Cube("embleme_feuille_droite", [-2.5, 19.5, 2.5], [-0.5, 20.5, 3.5], "leaf", rot=(0, 0, -30), origin=[-0.5, 20, 3], north=None),
        Cube("embleme_feuille_gauche", [0.5, 19.5, 2.5], [2.5, 20.5, 3.5], "leaf", rot=(0, 0, 30), origin=[0.5, 20, 3], north=None),
        # Braided root belt, its wooden buckle painted on, one coat tail leaning away from the legs.
        Cube("ceinture_manteau", [-5, 12, -3], [5, 14, 3], "braid", south=stamp(MATERIALS["braid"][0](10, 2), 4, 0, ["CW", "WB"])),
        Cube("basque", [-4.5, 6, -3.5], [-0.5, 12, -2.5], "moss", rot=(10, 0, 0), origin=[-2.5, 12, -3],
             north=stamp(moss(4, 6), 0, 5, "LDLD")),
    ])


def sleeve():
    return Cube("manche", [-8.5, 14, -3], [-3.5, 24, 3], "fabric")


def right_arm():
    return [
        sleeve(),
        Cube("epaule_racines", [-10, 22, -3.5], [-6, 24, 3.5], "root"),
        Cube("epaule_feuille", [-9, 24, 0], [-7, 25, 2], "leaf"),
    ]


def left_arm():
    return [
        mirror(sleeve()),
        Cube("epaule_ecorce", [6, 22, -3.5], [10, 25, 3.5], "bark"),
        Cube("epaule_feuille", [8, 25, -1], [10, 26, 1], "leaf"),
    ]


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "braid"),
        Cube("boucle", [-4, 10, 3], [-2, 12, 4], "wood", north=None),
    ]


def right_leg():
    return [
        Cube("pantalon", [-4.5, 5, -2.5], [0.5, 12, 2.5], "fabric", up=None),  # under the belt
        Cube("pan_long", [-4, 5, 2.5], [0, 10, 3.5], "panel"),
        Cube("lien_racine", [-5, 7, -3], [0, 8, 3], "root"),
        Cube("pousse", [-5.5, 8, 0], [-4.5, 10, 1], "sprout"),
    ]


def left_leg():
    return [
        mirror(right_leg()[0]),
        Cube("pan_court", [0.5, 7, 2.5], [3.5, 10, 3.5], "panel"),
        Cube("garde_ecorce", [4.5, 6, -2.5], [5.5, 11, 2.5], "bark"),
        Cube("garde_racine", [5.5, 9, -2.5], [6.5, 10, 2.5], "root"),
        Cube("garde_feuille", [5.5, 10, 0], [6.5, 12, 1], "leaf"),
    ]


def right_boot():
    return [
        Cube("semelle", [-5, 0, -3], [1, 1, 5.5], "root", south="R", north="R", west="R", east="R", up="B", down="R"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather"),
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 5], "leather"),
        Cube("plaque_ecorce", [-3.5, 3, 2.5], [-0.5, 4, 5], "bark"),
        Cube("racine_cheville", [-5, 4, -3], [1, 5, 3], "root", south="B", north="B", west="B", east="B", up="B"),
        Cube("feuille", [-6, 4, -1], [-5, 5, 1], "leaf"),
    ]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "jambieres_jambe_gauche": left_leg,
          "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-3.0, 0.48), "plastron": (0, 0.55), "jambieres": (0, 0.9), "bottes": (0, 0.9)},
        {"druid_head": "tete", "druid_chest": "plastron", "druid_legs": "jambieres", "druid_feet": "bottes"})
