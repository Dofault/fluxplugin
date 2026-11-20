#!/usr/bin/env python3
"""Legacy of the Great North set ("Grand Nord"): the Christmas outfit, the keeper of the winter feasts.

    <venv with Pillow and numpy>/python3 gen_grandnord_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder. With --deploy it writes to Labs:
- plugins/FluxArmor/models/grandnord.bbmodel: coat, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn hat casque_grandnord and the 3D inventory models of the other pieces.

The four pieces:
- Solstice Hat: thick fur band, a two-step cardinal body set back, a short tip falling to the left with its pompon,
  a gold medallion with the pole star on the band;
- Coat of the Great Winter Father: velvet coat, fur collar, two fir green stoles down the front, leather belt and
  its gold buckle (eight-pointed star), two gold buttons, cloth pauldrons edged with gold, fur cuffs;
- Greaves of the Snow Traveller: thick trousers, fir green waistband, leather knee pads, fur turn-up;
- Steps of the Winter Father: old leather boots on a dark sole, fur cuff, a gold pole star on the outer side, the
  one of the right boot set with a frost crystal (the only glowing pixel of the set).

The fur turn-up of the legs and the cuff of the boots sit at the same height, the cuff slightly larger: worn together
they read as one band. Grids, faces and emissive letters: setkit.py.
"""
from setkit import Cube, lit, mirror, run, setup, stamp

PALETTE = {"R": "#8E2933", "V": "#A83B43", "X": "#641F2A",  # cardinal red, velvet red, shaded red
           "I": "#E8D9B9", "C": "#F3E9D5", "B": "#CDBB98",  # fur: warm ivory, cream, beige shade
           "S": "#294A3A", "T": "#1D342B",  # fir green and its shade
           "G": "#B28A4F", "L": "#D0AE70", "Y": "#7A5C36",  # aged gold, bright gold, brown shade
           "K": "#604334", "H": "#7A5846", "D": "#44302A", "N": "#2E211C",  # leather, lit, shaded, sole
           "F": "#A9C8D0", "W": "#D3E4E8", "Z": "#7FA3B0"}  # frost, its light and its shade

# ---------------------------------------------------------------------------------------- painters


def noise(x, y, w, h):
    """Stable value 0..99 per pixel, different for each face size: irregular without repeating."""
    return ((x * 73856093) ^ (y * 19349663) ^ (w * 83492791) ^ (h * 2971)) % 100


def velvet(w, h):
    """Thick cloth: broken velvet light at the top, shade under, one short fold every few pixels."""
    rows = []
    for y in range(h):
        line = []
        for x in range(w):
            c = "R"
            if h >= 3 and y == h - 1:
                c = "X"
            elif y < max(1, h * 0.3) and noise(x, y, w, h) < 62:
                c = "V"
            elif w >= 4 and x == w - 1:
                c = "X"
            elif w >= 6 and x % 4 == 2 and y >= h * (0.45 if (x // 4) % 2 else 0.65):
                c = "X"
            line.append(c)
        rows.append("".join(line))
    return rows


def fur(w, h):
    """Dense fur: ivory, small cream groups towards the top, beige under. The same on the four pieces."""
    rows = []
    for y in range(h):
        line = []
        for x in range(w):
            n = noise(x, y, w, h)
            c = "I"
            if h >= 2 and y == h - 1 and n < 65:
                c = "B"
            elif y < h - 1 and (n < 28 or noise(x + 1, y, w, h) < 12):
                c = "C"
            elif n > 93:
                c = "B"
            line.append(c)
        rows.append("".join(line))
    return rows


def leather(w, h):
    """Matt old leather: lighter top, darker under and at the right end, a rare worn pixel."""
    rows = []
    for y in range(h):
        line = []
        for x in range(w):
            n = noise(x, y, w, h)
            c = "K"
            if h >= 2 and y == h - 1:
                c = "D"
            elif y == 0 and h >= 3 and n < 60:
                c = "H"
            elif w >= 4 and x == w - 1:
                c = "D"
            elif n > 94:
                c = "H"
            line.append(c)
        rows.append("".join(line))
    return rows


def gold(w, h):
    """Aged gold: light at the top left, brown shade at the bottom right."""
    rows = [["G"] * w for _ in range(h)]
    if w > 1:
        for y in range(h):
            rows[y][-1] = "Y"
    if h > 1:
        rows[-1] = ["Y"] * w
    rows[0][0] = "L"
    return ["".join(r) for r in rows]


def fir(w, h):
    rows = []
    for y in range(h):
        line = ["S"] * w
        if w >= 3:
            line[-1] = "T"
        rows.append("T" * w if h >= 3 and y == h - 1 else "".join(line))
    return rows


def sole(w, h):
    return ["N" * w] * h


setup("grandnord", PALETTE, {"velvet": (velvet, "V", "X"), "fur": (fur, "C", "B"), "leather": (leather, "H", "D"),
                             "gold": (gold, "L", "Y"), "fir": (fir, "S", "T"), "sole": (sole, "N", "N")})

STAR = ["GLG", "LCL", "GLY"]  # pole star: bright cross, cream heart
FROST_STAR = ["GLG", "LfL", "GLY"]  # the same set with a frost crystal

# ---------------------------------------------------------------------------------------- pieces


def hat():
    """Fur band, two steps set back, a short tip falling to the player's left and its pompon."""
    turn, pivot = (0, 0, -28), [1, 39, -2]
    return lit([
        Cube("revers", [-5, 29, -5], [5, 32, 5], "fur"),
        Cube("corps", [-4.5, 32, -4.5], [4.5, 36, 4.5], "velvet"),
        Cube("corps_haut", [-3.5, 36, -4], [3.5, 39, 3], "velvet"),
        Cube("pointe", [1, 38, -3], [7, 40, -1], "velvet", rot=turn, origin=pivot, sides=["VVVVRR", "RRXRRX"], up="V", west=None),
        Cube("pompon", [6.5, 37.5, -3.5], [9.5, 40.5, -0.5], "fur", rot=turn, origin=pivot),
        Cube("medaillon", [1, 29, 5], [4, 32, 6], "gold", south=STAR, north=None),
    ])


def torso():
    front = stamp(velvet(9, 12), 4, 3, ["X"] * 9)
    return [
        # No top face: it is under the head.
        Cube("manteau", [-4.5, 12, -2.5], [4.5, 24, 2.5], "velvet", south=front, up=None),
        Cube("col", [-4, 22, -3], [4, 24, 3], "fur"),
        Cube("etole_droite", [-3.5, 12, 2.5], [-2.5, 22, 2.9], "fir", south=["S"] * 9 + ["T"], north=None),
        Cube("etole_gauche", [2.5, 12, 2.5], [3.5, 22, 2.9], "fir", south=["S"] * 9 + ["T"], north=None),
        Cube("ceinture", [-5, 14, -3], [5, 16, 3], "leather"),
        Cube("boucle", [-1.5, 13.5, 3], [1.5, 16.5, 4], "gold", south=STAR, north=None),
        Cube("bouton_haut", [-1, 19.5, 2.5], [1, 21.5, 3], "gold", south=["LG", "GY"], north=None),
        Cube("bouton_bas", [-1, 17, 2.5], [1, 19, 3], "gold", south=["LG", "GY"], north=None),
    ]


def right_arm():
    def edged(w):
        return stamp(velvet(w, 4), 0, 3, "G" * (w - 1) + "Y")
    return [
        Cube("manche", [-8.5, 14, -2.5], [-3.5, 24, 2.5], "velvet", up=None),
        Cube("poignet", [-9, 12, -3], [-3, 14, 3], "fur"),
        # Cloth pauldron: wider than the shoulder, a gold edge under it, nothing metallic.
        Cube("epauliere", [-10, 21, -3.5], [-4, 25, 3.5], "velvet", south=edged(6), north=edged(6), west=edged(7), east=edged(7)),
    ]


def legs_torso():
    return [Cube("taille", [-5, 10, -3], [5, 12, 3], "fir", sides=["SSSSSSSSST", "GGGGGGGGGY"])]


def right_leg():
    return [
        Cube("jambe", [-4.5, 4, -2.5], [0, 11.9, 2.5], "velvet"),
        Cube("genou", [-4, 7, 2.5], [-0.5, 10, 3.5], "leather", south=["HHHK", "KKKD", "DDDD"], north=None),
        Cube("revers", [-5, 4, -3], [0, 6, 3], "fur"),
    ]


def boot(crystal):
    """One right boot; the left one is its mirror, without the crystal."""
    star = FROST_STAR if crystal else STAR
    return lit([
        Cube("semelle", [-5, 0, -3], [0, 1, 5], "sole"),
        Cube("tige", [-4.5, 1, -2.5], [0, 5, 2.5], "leather"),
        Cube("bout", [-4.5, 1, 2.5], [0, 3, 4.5], "leather", north=None),
        # Slightly larger than the turn-up of the legs, at the same height.
        Cube("revers", [-5.25, 4, -3.25], [0, 6, 3.25], "fur"),
        Cube("etoile", [-5.2, 1, -1.5], [-4.5, 4, 1.5], "gold", west=star, east=None),
    ])


def right_boot():
    return boot(True)


def left_boot():
    return [mirror(c) for c in boot(False)]


def left_arm():
    return [mirror(c) for c in right_arm()]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg,
          "bottes_jambe_droite": right_boot, "bottes_jambe_gauche": left_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-5.0, 0.42), "plastron": (0, 0.6), "jambieres": (0, 0.95), "bottes": (0, 0.95)})
