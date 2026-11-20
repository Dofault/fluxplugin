#!/usr/bin/env python3
"""Heart of the Primordial Forest set ("Sylve"): the rich druid outfit, about a hundred cubes.

    <venv with Pillow and numpy>/python3 gen_sylve_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, sylve.bbmodel and one editable Blockbench file
per piece: druid_head, _chest, _legs, _feet). With --deploy it writes to Labs:
- plugins/FluxArmor/models/sylve.bbmodel: coat, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn crown casque_sylve and the 3D inventory models of the other pieces.

The four pieces:
- Crown of the Old Grove: bark band and moss cap held by two roots crossing over the head, a leaning branch forking in two on the
  right with its bud, an old flower on the left of the brow, a few leaves each turned its own way;
- Coat of the Plant Heart: forest cloth under a moss mantle, low collar with a root running along it, the emblem (a
  living seed, its shoot and two leaves, framed by two roots on old wood), layered bark guard with a branch on the
  left shoulder, roots over a cloth pad on the right one, braided root belt, two coat tails of different lengths;
- Leggings of the Deep Roots: two tabard panels of different lengths, the long one held by a twig, layered oak guard
  with a root and a leaf on the left leg, roots and a shoot on the right one;
- Ankle boots of the Root-Walker: thick sole, leather, bark toe and layered instep, roots holding the ankle, a leaf.

Palette, materials and light: druid.py. Grids, faces and emissive letters: setkit.py.
"""
from druid import FLOWER, MATERIALS, PALETTE, SEED, fabric, moss
from setkit import Cube, lit, mirror, run, setup, stamp

setup("sylve", PALETTE, MATERIALS)

# ---------------------------------------------------------------------------------------- head


def hat():
    return lit([
        # Bark band, thicker on the right of the brow.
        Cube("bandeau_avant", [-5, 29, 4], [5, 31, 5], "bark", north=None),
        Cube("bandeau_arriere", [-5, 29, -5], [5, 31, -4], "bark", south=None),
        Cube("bandeau_droit", [-5, 29, -4], [-4, 31, 4], "bark", east=None),
        Cube("bandeau_gauche", [4, 29, -4], [5, 31, 4], "bark", west=None),
        Cube("noeud_avant", [-5, 31, 4], [-2, 32, 5], "wood", north=None),
        Cube("calotte", [-4.5, 31, -4.5], [4.5, 33, 4.5], "moss"),  # above the top of the head
        # Two roots crossing over the head hold the crown.
        Cube("racine_dessus", [-1, 33, -5], [1, 34, 5], "root"),
        Cube("racine_travers", [-5, 33, -1], [5, 34, 1], "root"),
        # Leaning branch forking in two, its bud at the end of the long twig.
        Cube("branche", [-3, 31, 1], [-1, 36, 3], "wood", rot=(0, 0, 20), origin=[-2, 31, 2]),
        Cube("rameau_long", [-4.2, 35, 1.5], [-3.2, 38.5, 2.5], "wood", rot=(0, 0, 40), origin=[-3.7, 35.3, 2]),
        Cube("rameau_court", [-4.2, 35, 1.5], [-3.2, 37.5, 2.5], "wood", rot=(0, 0, -20), origin=[-3.7, 35.3, 2]),
        Cube("bourgeon", [-7, 37, 1.5], [-5, 39, 2.5], "sprout"),
        # Leaves, each turned its own way.
        Cube("feuille_rameau", [-3.5, 37, 1], [-1.5, 38, 3], "leaf", rot=(0, 0, -20)),
        Cube("feuille_gauche", [5, 30, -1], [7, 31, 2], "leaf", rot=(0, 0, -25), origin=[5, 30.5, 0.5]),
        Cube("feuille_arriere", [-6, 30, -3], [-5, 32, -1], "leaf"),
        Cube("pousse_avant", [1, 31, 4], [3, 32, 5], "sprout", north=None),
        # Old flower on the left of the brow: flat petals, a heart standing out.
        Cube("fleur", [3, 29, 5], [6, 32, 5.5], "linen", south=FLOWER, north=None, west=None, east=None, up=None, down=None),
        Cube("fleur_coeur", [4, 30, 5.5], [5, 31, 6.5], "seed", north=None),
        # Roots around the band, a knot at the back.
        Cube("racine_tempe", [-6, 26, 0], [-5, 30, 1], "root"),
        Cube("racine_arriere", [-3, 28, -6], [4, 30, -5], "root", south=None),
        Cube("noeud_arriere", [-1, 30, -6], [1, 32, -5], "wood", south=None),
    ])


# ---------------------------------------------------------------------------------------- chest


def torso():
    collar_back = stamp(moss(12, 4), 0, 0, "LLLLLLLLLLLO")
    wing = stamp(moss(5, 2), 0, 0, "LLLLO")
    tail = dict(rot=(10, 0, 0))
    return lit([
        # No top face: it is under the head.
        Cube("tunique", [-4.5, 12, -2.5], [4.5, 24, 2.5], "fabric", up=None),
        # Moss mantle over the shoulders, a linen edge at its end.
        Cube("mantelet_avant", [-4.5, 20, 2.5], [4.5, 24, 3.5], "moss", south=stamp(moss(9, 4), 0, 3, "LLLLLLLLO"), north=None),
        Cube("mantelet_dos", [-4.5, 19, -3.5], [4.5, 24, -2.5], "moss", north=stamp(moss(9, 5), 0, 4, "LLLLLLLLO"), south=None),
        # Low collar, a root running along part of it and ending in a leaf.
        Cube("col_dos", [-6, 22, -6], [6, 26, -5], "moss", north=collar_back, south=collar_back),
        Cube("col_droit", [-6, 24, -5], [-5, 26, 0], "moss", west=wing, east=wing, north=None),
        Cube("col_gauche", [5, 24, -5], [6, 26, 0], "moss", west=wing, east=wing, north=None),
        Cube("col_racine", [-6, 26, -6], [2, 27, -5], "root"),
        Cube("col_feuille", [2, 26, -6], [4, 27, -4], "leaf", rot=(0, 0, 20)),
        # Emblem, the Heart of the Forest: a living seed, its shoot and two leaves, two roots framing old wood.
        Cube("embleme_bois", [-2.5, 14.5, 2.5], [2.5, 18.5, 3.5], "wood", north=None),
        Cube("embleme_racine_droite", [-3.5, 14.5, 2.5], [-2.5, 20.5, 3.5], "root", rot=(0, 0, 10), north=None),
        Cube("embleme_racine_gauche", [2.5, 14.5, 2.5], [3.5, 20.5, 3.5], "root", rot=(0, 0, -10), north=None),
        Cube("embleme_graine", [-1, 15.5, 3.5], [1, 17.5, 4.5], "seed", south=SEED, north=None),
        Cube("embleme_pousse", [-0.5, 17.5, 3.5], [0.5, 21, 4.5], "sprout", north=None),
        Cube("embleme_feuille_droite", [-2.5, 19.5, 3.5], [-0.5, 20.5, 4.5], "leaf", rot=(0, 0, -30), origin=[-0.5, 20, 4], north=None),
        Cube("embleme_feuille_gauche", [0.5, 19.5, 3.5], [2.5, 20.5, 4.5], "leaf", rot=(0, 0, 30), origin=[0.5, 20, 4], north=None),
        # Braided root belt, wooden buckle, a seed hanging from it.
        Cube("ceinture_manteau", [-5, 12, -3], [5, 14, 3], "braid"),
        Cube("boucle_manteau", [-1, 12, 3], [1, 14, 4], "wood", north=None),
        Cube("graine_suspendue", [3, 10, 3], [4, 12, 4], "seed", north=None),
        # Coat tails of different lengths, leaning away from the legs.
        Cube("basque_droite", [-4.5, 6, -3.5], [-0.5, 12, -2.5], "moss", origin=[-2.5, 12, -3], **tail,
             north=stamp(moss(4, 6), 0, 5, "LDLD")),
        Cube("basque_gauche", [0.5, 8, -3.5], [4.5, 12, -2.5], "moss", origin=[2.5, 12, -3], **tail,
             north=stamp(moss(4, 4), 0, 3, "DLDL")),
    ])


def sleeve():
    return [
        Cube("manche", [-8.5, 14, -3], [-3.5, 24, 3], "fabric"),
        Cube("poignet", [-9, 14, -3.5], [-3, 16, 3.5], "linen"),
    ]


def right_arm():
    """Living roots: two roots crossing over a cloth pad, one leaf."""
    return sleeve() + [
        Cube("epaule_tissu", [-10, 22, -3.5], [-6, 24, 3.5], "moss"),
        Cube("epaule_racine", [-9, 24, -4], [-8, 25, 4], "root"),
        Cube("epaule_racine_cote", [-11, 21, -1], [-10, 25, 1], "root"),
        Cube("epaule_feuille", [-10, 25, 1], [-8, 26, 3], "leaf", rot=(0, 20, 0)),
    ]


def left_arm():
    """Bark of the guardian: dark base, a bark plate leaning outwards, a wooden rib, a branch and its leaves."""
    tilt = dict(rot=(0, 0, -10), origin=[6, 24, 0])
    return [mirror(c) for c in sleeve()] + [
        Cube("epaule_base", [6, 21, -3.5], [10, 24, 3.5], "root"),
        Cube("epaule_ecorce", [6, 24, -3], [11, 25, 3], "bark", **tilt),
        Cube("epaule_renfort", [7, 25, -2], [9, 26, 2], "wood", **tilt),
        Cube("epaule_branche", [9, 25, -1], [10, 28, 0], "wood", rot=(0, 0, -25), origin=[9.5, 25, -0.5]),
        Cube("epaule_feuille_haut", [10, 27, -1.5], [12, 28, 0.5], "leaf"),
        Cube("epaule_feuille_avant", [7, 22, 3.5], [9, 23, 4.5], "leaf", north=None),
        Cube("epaule_lame", [8.5, 18, -3], [9.5, 21, 3], "bark"),
    ]


# ---------------------------------------------------------------------------------------- legs


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "braid"),
        Cube("boucle", [-4, 10, 3], [-2, 12, 4], "wood", north=None),
        Cube("lien", [1, 10, 3], [2, 12, 4], "linen", north=None),
        Cube("perle", [-3.5, 8, 3.5], [-2.5, 10, 4.5], "wood"),
    ]


def right_leg():
    """Living roots: long panel held by a twig, linen wrap under the knee, two roots and a shoot on the outside."""
    return [
        Cube("pantalon", [-4.5, 5, -2.5], [0.5, 12, 2.5], "fabric", up=None),  # under the belt
        Cube("pan_long", [-4, 6, 2.5], [0, 10, 3.5], "panel"),
        Cube("brindille", [-4.5, 9, 3.5], [0.5, 10, 4.5], "wood"),
        Cube("bande_genou", [-5, 6, -3], [0, 7, 3], "linen"),
        Cube("bande_cheville", [-5, 5, -3], [1, 6, 3], "linen", south="L", north="L", west="L", east="L", up="L"),
        Cube("racine_cote", [-5.5, 6, -2], [-4.5, 11, -1], "root", rot=(15, 0, 0)),
        Cube("racine_dos", [-4.5, 8, -3.5], [0.5, 9, -2.5], "root"),
        Cube("pousse", [-5.5, 7, 0.5], [-4.5, 9, 1.5], "sprout"),
    ]


def left_leg():
    """Old oak guard: bark knee, dark base, bark plate, a wooden layer, a root along its front, a leaf on top."""
    wrap = right_leg()[4]
    return [
        mirror(right_leg()[0]),
        mirror(wrap),
        Cube("pan_court", [0.5, 8, 2.5], [3.5, 10, 3.5], "panel"),
        Cube("genou_ecorce", [0.5, 6, 2.5], [4.5, 8, 3.5], "bark"),
        Cube("garde_base", [4.5, 6, -2.5], [5.5, 11, 2.5], "root"),
        Cube("garde_ecorce", [5.5, 7, -2], [6.5, 11, 2], "bark"),
        Cube("garde_bois", [6.5, 8, -1], [7.5, 10, 1], "wood"),
        Cube("garde_racine", [4.5, 6, 2.5], [5.5, 11, 3.5], "root"),
        Cube("garde_feuille", [5.5, 11, -1], [7.5, 12, 1], "leaf"),
    ]


# ---------------------------------------------------------------------------------------- feet


def right_boot():
    return [
        Cube("semelle", [-5, 0, -3], [1, 1, 6], "root", south="R", north="R", west="R", east="R", up="B", down="R"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather"),
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 4.5], "leather"),
        # Bark toe, two layers of bark and wood on the instep.
        Cube("coque", [-4.5, 1, 4.5], [0.5, 3, 5.5], "bark"),
        Cube("cou_de_pied", [-3.5, 3, 2.5], [-0.5, 4, 4.5], "bark"),
        Cube("cou_de_pied_haut", [-3, 4, 2.5], [-1, 5, 3.5], "wood"),
        # Roots holding the ankle, a leaf on the outside, wooden heel.
        Cube("racine_cheville", [-5, 3, -3], [1, 4, 3], "root", south="B", north="B", west="B", east="B", up="B"),
        Cube("racine_oblique", [-6, 1, -1], [-5, 5, 0], "root", rot=(25, 0, 0)),
        Cube("feuille", [-6, 4, 0], [-5, 5, 2], "leaf"),
        Cube("talon", [-4, 1, -3.5], [0, 3, -2.5], "bark"),
    ]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "jambieres_jambe_gauche": left_leg,
          "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-3.0, 0.46), "plastron": (0, 0.5), "jambieres": (0, 0.85), "bottes": (0, 0.9)},
        {"druid_head": "tete", "druid_chest": "plastron", "druid_legs": "jambieres", "druid_feet": "bottes"})
