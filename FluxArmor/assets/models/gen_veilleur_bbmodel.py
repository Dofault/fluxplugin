#!/usr/bin/env python3
"""Order of the Watchers set ("Ordre des Veilleurs"): the outfit of the old order that guards the borders of the known
world. Thick cloth, aged bronze, leather: it suits a warrior as well as a mage.

    <venv with Pillow and numpy>/python3 gen_veilleur_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder. With --deploy it writes to Labs:
- plugins/FluxArmor/models/veilleur.bbmodel: tunic, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn hood casque_veilleur and the 3D inventory models of the other pieces.

The signature of the set is a bronze lozenge set with a turquoise stone (a cube turned by 45 degrees). It is used
twice and nowhere else: large on the chest, where four leather straps hold it, small as the belt buckle. The stone of
the chest is the only glowing thing of the set. The four pieces:
- Hood of the Long Watch: slate hood open on the face, a petrol green rim, its fall at the nape;
- Tunic of the Watcher: slate gambeson, a raised chest panel crossed by two leather straps (they cross again in the
  back), the lozenge where they meet, petrol green collar rising behind the neck, pauldrons in two levels leaning
  outwards (cloth edged with ivory, a bronze plate on it), petrol sleeves, wide bronze bracers between two leather straps;
- Greaves of the Borders: thick leather belt and its lozenge buckle, one petrol green band hanging in front and one
  behind, slate trousers, bronze plates on the outer thighs, leather knee pads;
- Boots of the Last Outpost: leather boots on a dark sole, bronze toe cap, petrol green cuff.

Light comes from the top left: lit top edge on the left, shade on the right column and the bottom row, straight folds
that follow the cloth, never noise. Grids, faces and emissive letters: see setkit.py.
"""
import math

from setkit import Cube, lit, mirror, paint, run, setup, stamp

PALETTE = {"A": "#343D54", "N": "#252B3F", "B": "#414C67",  # slate blue cloth, its night blue shade, its light
           "P": "#526D6B", "Q": "#405B59", "R": "#62807C",  # petrol green cloth, greyed green shade, its light
           "M": "#98764D", "L": "#B79A70", "D": "#6E5437",  # aged bronze, light bronze, its shade
           "K": "#55403D", "H": "#79564B", "J": "#3A2B2B",  # dark leather, medium leather, deepest shade and soles
           "I": "#C5C5B3", "E": "#DDD2B9",                  # greyed ivory and soft beige: edges
           "S": "#65AFAE", "T": "#A1D5CD", "W": "#D5EEE7", "U": "#4A8A8C"}  # stone: base, light, glint, shade

# ---------------------------------------------------------------------------------------- painters


def cloth(base, light, shade):
    """Thick matt cloth: a lit strip at the top left, shade at the right and under, straight folds starting at
    different heights."""
    def painter(w, h):
        def pixel(x, y):
            if h >= 3 and y == h - 1 or w >= 4 and x == w - 1:
                return shade
            if w >= 6 and x % 4 == 2 and int(h * (0.4 if (x // 4) % 2 else 0.62)) <= y:
                return shade
            if y == 0 and h >= 2 and x < (w + 1) // 2 or w >= 3 and x == 0 and y < h * 0.4:
                return light
            return base
        return paint(w, h, pixel)
    return painter


slate = cloth("A", "B", "N")
petrol = cloth("P", "R", "Q")


def bronze(w, h):
    """Old kept bronze: lit top edge on the left half, shade on the right column and the bottom row, no white glint."""
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "D"
        if y == 0 and x < (w + 1) // 2:
            return "L"
        return "M"
    return paint(w, h, pixel)


def leather(w, h):
    """Warm dark leather: medium brown where it is stretched at the top left, darker ends."""
    def pixel(x, y):
        if h >= 2 and y == h - 1 or w >= 4 and x == w - 1:
            return "J"
        if y == 0 and x < (w + 1) // 2:
            return "H"
        return "K"
    return paint(w, h, pixel)


def sole(w, h):
    return ["J" * w] * h


setup("veilleur", PALETTE, {"slate": (slate, "B", "N"), "petrol": (petrol, "R", "Q"), "bronze": (bronze, "L", "D"),
                            "leather": (leather, "H", "J"), "sole": (sole, "J", "J")})

# ---------------------------------------------------------------------------------------- signature


def lozenge(name, x, y, z, grid, depth):
    """The bronze lozenge on a surface facing +z at z, centred on (x, y): a cube turned by 45 degrees. The first row
    of the grid becomes its upper left edge, the lit one."""
    s = len(grid) / 1.13  # the diagonal of the chest one is 5 pixels, 4 for the buckle
    return Cube(name, [x - s / 2, y - s / 2, z], [x + s / 2, y + s / 2, z + depth], "bronze", rot=(0, 0, 45),
                origin=[x, y, z], south=grid, north=None)


# Frame one pixel wide, the stone in three levels: pale heart, a glint on the upper corner, shade under.
CHEST_LOZENGE = ["LLLM", "MtwM", "MusD", "MDDD"]
BUCKLE_LOZENGE = ["LLM", "MSD", "MDD"]

# ---------------------------------------------------------------------------------------- pieces


def hat():
    """Hood: the shell has no front, the rim closes the gap around the face."""
    strip = Cube("bord_droit", [-5.25, 25, 4], [-4, 31, 5.5], "petrol", south=["P"] * 5 + ["Q"], up=None,
                 west=["RP"] + ["PQ"] * 4 + ["QQ"], east=["QQ"] * 6)
    return [
        Cube("capuche", [-5, 25, -5], [5, 33, 5], "slate", south=None, down=None),
        Cube("bord_haut", [-5.25, 31, 4], [5.25, 33.25, 5.5], "petrol", south=["RRRRRRPPPQ", "PPPPPPPPQQ"], down="Q"),
        strip, mirror(strip),
        # The fall of the hood at the nape: what makes it a hood in profile.
        Cube("tombant", [-3.5, 27, -6], [3.5, 32, -5], "slate", south=None),
    ]


def straps(grid, top, rows):
    """Two leather straps crossing over a grid, from its upper corners, two pixels wide, lit on their upper pixel."""
    w = len(grid[0])
    for i in range(rows):
        if i + 1 < w:
            grid = stamp(grid, i, top + i, "HK")
            grid = stamp(grid, w - 2 - i, top + i, "HK")
    return grid


def torso():
    # Chest panel: the straps come from its four corners and meet under the lozenge.
    panel = ["HKBBBHK",
             "BHKAHKN",
             "AAHKKAN",
             "AAAKKAN",
             "AAAKKAN",
             "AAHKKAN",
             "AHKAHKN",
             "HKNNNHK"]
    back = straps(slate(9, 12), 2, 9)
    return lit([
        # No top face: it is under the head.
        Cube("tunique", [-4.5, 12, -2.5], [4.5, 24, 2.5], "slate", north=back, up=None),
        Cube("plastron", [-3.5, 14, 2.5], [3.5, 22, 3.5], "slate", south=panel, north=None, up="B"),
        lozenge("losange", 0, 18.5, 3.5, CHEST_LOZENGE, 0.7),
        # Thick collar: a ring around the neck, higher behind it.
        Cube("col", [-4.5, 22, -3], [4.5, 24, 3], "petrol", south=["RRRRRPPPQ", "PPPPPPQQQ"], west=None, east=None),
        Cube("col_dos", [-4, 22, -5.5], [4, 26, -3.5], "petrol", north=["EIIIIIII", "RRRRPPPQ", "PPPPPPPQ", "QQQQQQQQ"],
             west=["IE", "PR", "PP", "QQ"], east=["II", "PQ", "QQ", "QQ"], up="P"),
    ])


def right_arm():
    lean, pivot = (0, 0, 12), [-4, 23.5, 0]  # the outer edge of the pauldron drops by a good pixel

    def hemmed(w):
        return stamp(slate(w, 3), 0, 2, "E" + "I" * (w - 1))

    def strapped(w):
        return stamp(stamp(bronze(w, 5), 0, 0, "H" + "K" * (w - 2) + "J"), 0, 4, "K" * (w - 1) + "J")
    return [
        Cube("manche", [-8.5, 14, -2.4], [-3.5, 24, 2.4], "petrol", up=None),
        # Wide bracer on the forearm, held by two leather straps.
        Cube("brassard", [-9, 13.5, -3], [-3.9, 18.5, 3], "bronze", south=strapped(5), north=strapped(5),
             west=strapped(6), east=strapped(6), up="K"),
        # Pauldron in two levels: cloth edged with ivory, the bronze plate set back on it.
        Cube("epauliere_tissu", [-10, 20.5, -3.5], [-4, 23.5, 3.5], "slate", rot=lean, origin=pivot,
             south=hemmed(6), north=hemmed(6), west=hemmed(7), east=hemmed(7), down="N"),
        Cube("epauliere_metal", [-9.5, 23.5, -3], [-4.5, 25.5, 3], "bronze", rot=lean, origin=pivot, down=None,
             up=["LLLMD"] + ["LMMMD"] * 4 + ["DDDDD"]),
    ]


def left_arm():
    return [mirror(c) for c in right_arm()]


def legs_torso():
    belt10 = ["HHHHHKKKKJ", "KMKKKKKKMJ", "JJJJJJJJJJ"]  # one bronze rivet near each hip
    return [
        Cube("ceinture", [-5, 10.5, -3], [5, 13.5, 3], "leather", south=belt10, north=belt10),
        lozenge("boucle", 0, 12, 3, BUCKLE_LOZENGE, 0.8),
        # One band in front, one behind, leaning away from the legs.
        Cube("bande_avant", [-1.5, 5.5, 2.75], [1.5, 10.5, 3.25], "petrol", rot=(-7, 0, 0), origin=[0, 10.5, 3],
             south=["RPQ", "RPQ", "PPQ", "PPQ", "EII"], north="Q", up=None),
        Cube("bande_arriere", [-2, 5.5, -3.25], [2, 10.5, -2.75], "petrol", rot=(7, 0, 0), origin=[0, 10.5, -3],
             north=["RPPQ", "RPPQ", "PPPQ", "PPQQ", "EIII"], south="Q", up=None),
    ]


def right_leg():
    return [
        Cube("jambe", [-4.5, 4, -2.5], [0.5, 12, 2.5], "slate", up=None, down=None),  # under the belt, in the boot
        # Bronze plate around the outer thigh, on each side of the hanging band.
        Cube("cuissot", [-5, 7.5, -3], [-2, 10.5, 3], "bronze", east=None),
        Cube("genou", [-4, 5, 2.5], [-1, 7, 3.5], "leather", south=["HHK", "KJJ"], north=None),
    ]


def right_boot():
    return [
        Cube("semelle", [-5, 0, -3], [0.5, 1, 5], "sole"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 4, 2.5], "leather", up=None),  # under the cuff
        # Leather foot, its tip capped with bronze.
        Cube("bout", [-4.5, 1, 2.5], [0.5, 3, 4.5], "leather", north=None, south=["LMMMD", "MMDDD"],
             up="K", west=["KM", "JD"], east=["MK", "DJ"]),
        Cube("revers", [-5.5, 3, -3], [0.5, 5, 3], "petrol"),
    ]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-2.5, 0.5), "plastron": (0, 0.55), "jambieres": (0, 0.9), "bottes": (0, 0.9)},
        {"veilleur_casque": "tete", "veilleur_plastron": "plastron", "veilleur_jambieres": "jambieres",
         "veilleur_bottes": "bottes"})
