#!/usr/bin/env python3
"""Guardian of the Old Forest set ("Saule"): four druid accessories, not a full outfit, about nine cubes each.

    <venv with Pillow and numpy>/python3 gen_saule_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, list of cubes, saule.bbmodel and one editable
Blockbench file per piece). With --deploy it writes to Labs:
- plugins/FluxArmor/models/saule.bbmodel: mantle, belt, shoes (then /fluxarmor build, /iazip);
- contents/influoutils: the worn crown casque_saule and the 3D inventory models of the other pieces.

The four pieces leave the body showing between them:
- Crown of the Old Willow: wide bark ring under a lighter wooden cap, two thick antlers rising at the back (the left
  one longer and leaning out), a branch reaching forwards over the brow, two leaves, an old amber seed set in front;
- Mantle of the Brambles: short cape over the shoulders only, each shoulder two thick leaves folded on one another,
  woven linen on the back and round the neck, two lapels opening over the chest, a carved wooden clasp, one leaf
  hanging on the back;
- Belt of the Roots: braided roots under a lighter layer, a carved wooden buckle turned on its point with a moss
  heart, a linen pouch with its flap and toggle on the left, a thick root hanging on the right, a leaf, a knot behind;
- Walkers of the Grove: thick wooden sole, forest green plant leather, a paler fibre cuff round the ankle, a wooden
  toggle on the foot.

Light comes from the top left. Nothing glows. Grids and faces: setkit.py.
"""
from setkit import Cube, paint, run, setup, stamp

PALETTE = {"D": "#263F32", "F": "#365C43", "M": "#56734A", "S": "#82916B", "P": "#A5A982",  # pine, forest, moss, sage, pale leaf
           "R": "#3E3027", "B": "#594333", "W": "#795A40",  # wood shade, bark, earth brown
           "L": "#B7A783", "I": "#D1C7A3", "A": "#B58A4C"}  # linen, plant ivory, old amber

# ---------------------------------------------------------------------------------------- painters


def bark(w, h):
    """Old wood lying flat: short uneven fibres along its length, lit above, dark below."""
    def pixel(x, y):
        if h >= 2 and y == h - 1:
            return "R" if (x + 1) % 4 == 0 else "B"
        if y % 2 == 0 and (x + 3 * y) % 5 <= 2:
            return "W"
        return "B"
    return paint(w, h, pixel)


def antler(w, h):
    """Standing wood: a lit streak on the left broken twice, shade on the right."""
    def pixel(x, y):
        if w >= 2 and x == w - 1:
            return "R"
        if x == 0 and y % 4 != 3:
            return "W"
        return "B"
    return paint(w, h, pixel)


def carved(w, h):
    """Carved lighter wood of the cap, clasp, buckle and toggles."""
    def pixel(x, y):
        if (x, y) == (0, 0) and w * h >= 4:
            return "A"
        if h >= 2 and y == h - 1 or w >= 3 and x == w - 1:
            return "B"
        return "W"
    return paint(w, h, pixel)


def leaf(w, h):
    """Thick leaf: one vein along its length, a side vein, a darker edge away from the light."""
    def pixel(x, y):
        along, across, size = (x, y, h) if w >= h else (y, x, w)
        if size >= 2 and across == size // 2:
            return "S"
        if size >= 3 and across == size // 2 - 1 and along == 1:
            return "S"
        if x == w - 1 and w >= 3 or y == h - 1 and h >= 3:
            return "D"
        return "F"
    return paint(w, h, pixel)


def fold(w, h):
    """Upper fold of a shoulder leaf, lit."""
    def pixel(x, y):
        if (x, y) == (0, 0):
            return "P"
        if y == 0 and x < w // 2:
            return "S"
        return "F" if h >= 2 and y == h - 1 else "M"
    return paint(w, h, pixel)


def linen(w, h):
    """Woven plant fibre: matt, a few short strokes down the weave, greener in the shade."""
    def pixel(x, y):
        if (x, y) == (0, 0) and w * h >= 4:
            return "I"
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "S"
        if x % 4 == 1 and 1 <= y % 5 <= 2:
            return "I"
        return "L"
    return paint(w, h, pixel)


def roots(w, h):
    """Braided roots: lit strands above, creases where they cross."""
    def pixel(x, y):
        if y == 0:
            return "B" if x % 3 == 2 else "W"
        if h >= 2 and y == h - 1:
            return "R" if x % 3 == 2 else "B"
        return "B"
    return paint(w, h, pixel)


def hide(w, h):
    """Plant leather of the shoes: forest green, lit above, a few fibre marks."""
    def pixel(x, y):
        if y == 0 and x < (w + 1) // 2:
            return "M"
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "D"
        return "D" if (2 * x + y) % 7 == 3 else "F"
    return paint(w, h, pixel)


def fibre(w, h):
    """Thick plant fibres wound round the ankle, paler than the shoe."""
    return paint(w, h, lambda x, y: "P" if y == 0 and x % 2 == 0 else ("M" if h >= 2 and y == h - 1 else "S"))


def seed(w, h):
    return paint(w, h, lambda x, y: "I" if (x, y) == (0, 0) else ("W" if x == w - 1 and y == h - 1 and w * h >= 4 else "A"))


setup("saule", PALETTE, {
    "bark": (bark, "W", "R"), "antler": (antler, "W", "R"), "carved": (carved, "W", "B"), "leaf": (leaf, "M", "D"),
    "fold": (fold, "S", "F"), "linen": (linen, "I", "S"), "roots": (roots, "W", "R"), "hide": (hide, "M", "D"),
    "fibre": (fibre, "P", "M"), "seed": (seed, "I", "W"), "moss": (lambda w, h: paint(w, h, lambda x, y: "S" if (x, y) == (0, 0) else "M"), "S", "F")})

# ---------------------------------------------------------------------------------------- pieces


def hat():
    return [
        # Wide ring round the head, its inside never seen, a lighter cap above the top of the head.
        Cube("bande_principale", [-5, 29, -5], [5, 31, 5], "bark", down=None),
        Cube("bande_superieure", [-4.5, 31, -4.5], [4.5, 33, 4.5], "carved", sides=stamp(carved(9, 2), 0, 0, "WWBWWWBWW")),
        # Antlers rising at the back: the left one longer and leaning out, the right one shorter and straighter.
        Cube("ramure_gauche", [3, 33, -4], [5, 37, -2], "antler", rot=(0, 0, -15), origin=[4, 33, -3]),
        Cube("ramure_droite", [-5, 33, -4], [-3, 36, -2], "antler", rot=(0, 0, 5), origin=[-4, 33, -3]),
        # Branch reaching forwards over the brow.
        Cube("branche_centrale", [-1, 33, 1], [1, 36, 3], "antler", rot=(25, 0, 0), origin=[0, 33, 2]),
        # Two leaves on the front, each turned its own way.
        Cube("feuille_principale", [-5, 31, 5], [-2, 33, 6], "leaf", rot=(0, 0, 25), north=None),
        Cube("feuille_secondaire", [2, 31, 5], [4, 33, 6], "leaf", rot=(0, 0, -30), north=None),
        # The old seed of the order, set in the wood of the ring.
        Cube("noyau", [-1, 29, 5], [1, 31, 6], "seed", north=None),
    ]


def torso():
    return [
        # Woven linen over the top of the back and round the neck.
        # Both stop where the shoulder leaves start: no face of theirs lies in the plane of a leaf.
        Cube("structure_dorsale", [-3.5, 20, -4.5], [3.5, 24, -2], "linen", south=None),
        Cube("col", [-3.5, 22, 2], [3.5, 24, 3.5], "linen", north=None),
        # Lapels opening over the chest, the middle of the torso left bare.
        Cube("pan_droit", [-4.5, 16, 2], [-1.5, 22, 3], "linen", rot=(0, 0, 8), origin=[-3, 22, 2.5], north=None,
             south=stamp(linen(3, 6), 0, 5, "SLS")),
        Cube("pan_gauche", [1.5, 16, 2], [4.5, 22, 3], "linen", rot=(0, 0, -8), origin=[3, 22, 2.5], north=None,
             south=stamp(linen(3, 6), 0, 5, "LSL")),
        # Carved clasp: a small shoot.
        Cube("attache", [-1, 21, 3.5], [1, 23, 4.5], "carved", south=["WM", "BW"], north=None),
        # One leaf hanging on the back, on its point.
        Cube("feuille_dorsale", [-1.5, 17.5, -5.5], [1.5, 20.5, -4.5], "leaf", rot=(0, 0, 45), south=None),
    ]


def right_arm():
    """Shoulder: two thick leaves folded on one another."""
    return [
        Cube("epaule", [-9.5, 21, -3.5], [-3.5, 24, 3.5], "leaf"),
        Cube("epaule_repli", [-9, 24, -2.5], [-4, 25, 2.5], "fold"),
    ]


def legs_torso():
    return [
        # Braided roots round the waist under a lighter layer.
        Cube("bande_principale", [-5, 10, -3], [5, 12, 3], "roots"),
        Cube("couche_superieure", [-4.5, 12, -2.5], [4.5, 13, 2.5], "carved", south="W", north="W", west="W", east="B", down=None),
        # Carved buckle on its point, a moss heart.
        Cube("boucle", [-1.5, 9.5, 3], [1.5, 12.5, 4], "carved", rot=(0, 0, 45), south=["AWW", "WWB", "WBB"], north=None),
        Cube("boucle_noyau", [-0.5, 10.5, 4], [0.5, 11.5, 5], "moss", rot=(0, 0, 45), north=None),
        # Linen pouch on the left, its flap and wooden toggle.
        Cube("poche", [5, 8, -1.5], [7, 11, 1.5], "linen", west=None),
        Cube("poche_rabat", [5, 11, -2], [8, 12, 2], "fibre"),
        Cube("poche_attache", [7, 9, -0.5], [8, 10, 0.5], "carved"),
        # Thick root hanging on the right, a leaf under the belt, a knot behind.
        Cube("racine_pendante", [-6, 6, -1], [-5, 10, 1], "antler"),
        Cube("feuille", [2, 8, 3], [4, 10, 4], "leaf", rot=(0, 0, -20), origin=[3, 10, 3.5], north=None),
        Cube("noeud_arriere", [-1, 10, -4], [1, 12, -3], "carved", south=None),
    ]


def right_boot():
    return [
        Cube("semelle", [-5, 0, -3], [1, 1, 5], "bark", south="B", north="B", west="B", east="B", up="W", down="R"),
        Cube("chaussure", [-4.5, 1, -2.5], [0.5, 4, 4.5], "hide"),
        Cube("revers", [-5, 4, -3], [1, 6, 1], "fibre"),
        Cube("attache", [-3, 4, 2], [-1, 5, 4], "carved"),
    ]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "jambieres_torse": legs_torso,
          "jambieres_jambe_droite": lambda: [], "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-3.0, 0.5), "plastron": (0, 0.6), "jambieres": (0, 0.9), "bottes": (0, 0.9)},
        {"druid_couronne_vieux_saule": "tete", "druid_mantelet_ronces": "plastron", "druid_ceinture_racines": "jambieres",
         "druid_marcheurs_sylve": "bottes"})
