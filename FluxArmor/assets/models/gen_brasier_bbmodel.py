#!/usr/bin/env python3
"""Brazier set ("Brasier"), the cosmetic outfit of the fire class: volcanic crust over a glowing core.

    <venv with Pillow and numpy>/python3 gen_brasier_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder. With --deploy it writes to Labs:
- plugins/FluxArmor/models/brasier.bbmodel: robe, skirt, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn hat casque_brasier and the 3D inventory models of the other pieces, from the same cubes.
No rotated cube.

The four pieces: a volcano hat (stepped stone cone, lava running down from its crater, red cloth on the nape), an
ember red robe under a smith's apron with a live coal set in it and basalt pauldrons, a flared skirt with a gilded
and charred hem, heavy forge boots with a cracked stone toe.

Grids, faces and emissive letters: see setkit.py.
"""
from setkit import Cube, lit, mirror, run, setup, stamp

PALETTE = {"D": "#292321", "V": "#39302B", "S": "#4D423B",  # charcoal, volcanic black, lit stone
           "X": "#56292A", "E": "#71352D", "R": "#A64C36",  # robe: fold, ember red, terracotta
           "U": "#C96B36", "M": "#D99A4D", "C": "#D4BDA0",  # burnt orange, amber, ash cream
           "Y": "#7C6038", "G": "#A9854F"}  # aged gold and its shade

# ---------------------------------------------------------------------------------------- painters


def stone(w, h):
    """Basalt block: lit top and left edges, shaded bottom and right edges, a calm field."""
    if h == 1:
        return ["S" * (w - 1) + "V"] if w > 1 else ["S"]
    rows = ["S" * (w - 1) + "V"]
    for y in range(1, h - 1):
        rows.append(("S" if y < h * 0.6 else "V") + "V" * (w - 2) + "D" if w > 2 else "V" * w)
    rows.append("V" + "D" * (w - 1))
    return rows


def robe(w, h):
    """Ember red cloth: terracotta where the light falls, straight folds starting at different heights."""
    rows = []
    for y in range(h):
        line = ["E"] * w
        if w >= 3:
            line[-1] = "X"
            if y < h * 0.45:
                line[0] = "R"
            for k, x in enumerate(range(2, w - 2, 3)):
                if y >= (h * 0.3 if k % 2 else h * 0.55):
                    line[x] = "X"
        rows.append("".join(line))
    return rows


def leather(w, h):
    rows = []
    for y in range(h):
        line = ["V"] * w
        if w >= 3:
            line[-1] = "D"
        rows.append("D" * w if h >= 3 and y == h - 1 else "".join(line))
    return rows


def gold(w, h):
    rows = [["G"] * w for _ in range(h)]
    for y in range(h):
        rows[y][-1] = "Y"
    rows[-1] = ["Y"] * w if h > 1 else rows[-1]
    rows[0][0] = "C"
    return ["".join(r) for r in rows]


setup("brasier", PALETTE, {"stone": (stone, "V", "D"), "robe": (robe, "E", "D"), "leather": (leather, "V", "D"),
                           "gold": (gold, "G", "Y")})

# ---------------------------------------------------------------------------------------- pieces


def hat():
    """Volcano: four stone steps, one straight lava run cooling down the middle of each side, red lining and nape cloth."""
    under = ["".join("X" if 2 <= x <= 11 and 2 <= z <= 11 else "E" for x in range(14)) for z in range(14)]
    crater = ["SSSV", "ScmD", "SmuD", "VDDD"]
    return lit([
        Cube("bord", [-7, 30, -7], [7, 31, 7], "stone", sides=["CGGGGGGGGGGGGY"], up=stone(14, 14), down=under),
        Cube("gradin_1", [-6, 31, -6], [6, 33, 6], "stone", sides=stamp(stone(12, 2), 6, 0, ["u", "E"]), up=stone(12, 12)),
        Cube("gradin_2", [-4, 33, -4], [4, 35, 4], "stone", sides=stamp(stone(8, 2), 4, 0, ["m", "u"]), up=stone(8, 8)),
        Cube("cratere", [-2, 35, -2], [2, 37, 2], "stone", sides=stamp(stone(4, 2), 2, 0, ["m", "m"]), up=crater),
        Cube("couvre_nuque", [-4, 25, -5], [4, 30, -4], "robe", north=stamp(robe(8, 5), 0, 4, "GGGGGGGY"),
             south="X", west=["E", "E", "E", "E", "G"], east=["X", "X", "X", "X", "Y"], down="Y"),
    ])


def torso():
    front = stamp(robe(9, 12), 3, 0, ["DDD", " D "])
    apron = ["GVVVVG", "SVVVVD", "SVVVVD", "SVuuVD", "SumcuD", "SummuD", "SVuuVD", "SVVVVD", "SVVVVD", "VDDDDD"]
    return lit([
        # No top face: it is under the head.
        Cube("robe", [-4.5, 12, -2.5], [4.5, 24, 2.5], "robe", south=front, up=None),
        # Smith's apron of dark leather with a live coal set in it, the heart of the set.
        Cube("tablier", [-3, 12, 2.5], [3, 22, 3.5], "leather", south=apron, north=None,
             west=["G"] + ["V"] * 8 + ["D"], east=["Y"] + ["D"] * 9),
    ])


def right_arm():
    hem5, hem6 = "GGGGY", "GGGGGY"
    crack = stamp(stone(6, 7), 2, 2, ["u", "m", "u"])
    return lit([
        Cube("manche", [-8.5, 15, -3], [-3.5, 24, 3], "robe", south=stamp(robe(5, 9), 0, 8, hem5),
             north=stamp(robe(5, 9), 0, 8, hem5), west=stamp(robe(6, 9), 0, 8, hem6), east=stamp(robe(6, 9), 0, 8, hem6)),
        Cube("epauliere", [-10, 22, -3.5], [-4, 25, 3.5], "stone", up=crack, west=stamp(stone(7, 3), 3, 0, ["u", "E"])),
    ])


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "leather", sides=["SVVVVVVVVD", "VDDDDDDDDD"]),
        Cube("boucle", [-2, 10, 3], [2, 12, 4], "gold", south=["CGGG", "GDDY"], north=None),
    ]


def right_leg():
    # The two inner columns of the flare are equal: both flares overlap between the legs.
    flare = ["REEXEE", "REEXEE", "GGGGGG", "DDUDDD"]
    return [
        Cube("jupe_haut", [-4.5, 9, -2.5], [0.5, 12, 2.5], "robe", sides=["REEXX", "REEEX", "REEEX"]),
        Cube("jupe_evasee", [-5, 5, -3], [1, 9, 3], "robe", south=flare, north=["EEXEEX", "EEXEEX", "GGGGGG", "DDDUDD"], west=flare, east=["EEXEEE", "EEXEEE", "YYYYYY", "DDDDDD"]),
    ]


def right_boot():
    shaft = ["SVVVD", "DDGDD", "VVVVD", "VVVVD"]
    return lit([
        Cube("semelle", [-5, 0, -3], [1, 1, 6], "leather", sides="D", up="V"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather", sides=shaft),
        # Stone toe cap split by a crack from front to back.
        Cube("coque", [-4.5, 1, 2.5], [0.5, 3, 5.5], "stone", up=["SSuSV", "SVmVD", "SVuDD"], south=["SSuSV", "VDDDD"],
             west=["SSV", "VDD"], east=["SSV", "VDD"]),
    ])


def left_arm():
    return [mirror(c) for c in right_arm()]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-5.0, 0.42), "plastron": (0, 0.6), "jambieres": (0, 0.95), "bottes": (0, 0.95)})
