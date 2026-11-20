#!/usr/bin/env python3
"""Azure Sentinel set ("Sentinelle Azurée"): the warrior outfit of an old order of guardians, azure forged metal.

    <venv with Pillow and numpy>/python3 gen_sentinelle_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder. With --deploy it writes to Labs:
- plugins/FluxArmor/models/sentinelle.bbmodel: cuirass, greaves, sabatons (then /fluxarmor build, /iazip);
- contents/influoutils: the worn helm casque_sentinelle and the 3D inventory models of the other pieces.

The emblem of the order, a shield with a point at the bottom (a square plate and a cube turned by 45 degrees, one
glowing pixel in its heart), is on every piece: brow of the helm, chest plate, belt, painted on the sabatons and on
the cape. The four pieces:
- Helm of the Azure Oath: open faced (T opening: brow plate, nasal, cheek guards), heavy deep blue top with an azure
  crest from front to back, night blue neck guard;
- Cuirass of the Celestial Bastion: azure cloth chest wider than the deep blue waist plates, light blue chest plate,
  pauldrons in two layered lames, high dark silver collar, short azure cape with the shield painted on it;
- Greaves of the Royal Walker: azure cloth thighs, deep blue greaves, pointed silver knee cops, night blue belt;
- Sabatons of the Watcher: deep blue leather shafts, azure metal feet in lames, a shin plate, a silver cuff.

Light comes from the top left: lit top edge on the left half, shade on the right column and the bottom row, no
outline. Grids, faces and emissive letters: see setkit.py.
"""
import math

from setkit import Cube, lit, mirror, paint, run, setup, stamp

PALETTE = {"A": "#5A85EA", "P": "#3455A0", "N": "#24375F",  # azure, deep blue, night blue
           "C": "#83A6F5", "W": "#B1C7FF",  # light blue, pale blue
           "S": "#AAB8D2", "D": "#64738F", "I": "#D9E0EF"}  # cold silver, dark silver, cold ivory

# ---------------------------------------------------------------------------------------- painters


def metal(base, lit_, glint, shade):
    """Satin forged metal: a lit top edge on the left half with one glint, shade on the right and at the bottom."""
    def painter(w, h):
        def pixel(x, y):
            if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
                return shade
            if y == 0 and x < (w + 1) // 2:
                return glint if x == 1 and w >= 4 else lit_
            return base
        return paint(w, h, pixel)
    return painter


plate = metal("A", "C", "W", "P")    # azure plate
deep = metal("P", "A", "A", "N")     # deep blue plate
light = metal("C", "W", "W", "A")    # light blue chest plate
silver = metal("S", "S", "I", "D")   # cold silver
dsilver = metal("D", "S", "S", "N")  # dark silver


def cloth(w, h):
    """Thick azure cloth: lit strip on the left, wide straight folds starting at different heights, never noise."""
    def pixel(x, y):
        if w >= 3 and x == w - 1:
            return "P"
        if w >= 4 and x % 4 == 2 and int(h * (0.35 if (x // 4) % 2 else 0.6)) <= y < h - (x // 4) % 2:
            return "P"
        if w >= 3 and x == 0 and y < h * 0.4:
            return "C"
        return "A"
    return paint(w, h, pixel)


def leather(w, h):
    """Matt deep blue leather: one lighter corner where it is stretched, wide shade, no glint."""
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "N"
        if y == 0 and x <= 1 and w >= 3:
            return "A"
        return "P"
    return paint(w, h, pixel)


def night(w, h):
    return paint(w, h, lambda x, y: "P" if y == 0 and x < (w + 1) // 2 and h >= 2 else "N")


setup("sentinelle", PALETTE, {"plate": (plate, "C", "P"), "deep": (deep, "A", "N"), "light": (light, "W", "A"),
                              "silver": (silver, "S", "D"), "dsilver": (dsilver, "S", "N"), "cloth": (cloth, "A", "P"),
                              "leather": (leather, "P", "N"), "night": (night, "P", "N")})

# ---------------------------------------------------------------------------------------- emblem


def emblem(name, x, top, z, w=2, h=2):
    """The shield of the order on a surface facing +z at z: a silver plate w x h whose top edge is at `top`, and a
    cube turned by 45 degrees under it for the point. Its heart glows."""
    heart = {2: ["IS", "wD"], 3: ["ISD", "SwD"]}[w]
    s = w / math.sqrt(2)
    cy = top - h
    return [
        Cube(name, [x - w / 2, cy, z], [x + w / 2, top, z + 1], "silver", south=heart, north=None),
        # A hair thinner than the plate: its upper half stays behind it.
        Cube(name + "_pointe", [x - s / 2, cy - s / 2, z], [x + s / 2, cy + s / 2, z + 0.95], "silver", rot=(0, 0, 45),
             origin=[x, cy, z], south="D" if s < 1.5 else ["SD", "DN"], north=None),
    ]


# ---------------------------------------------------------------------------------------- pieces


def hat():
    """Open faced helm: the shell has no front, the face shows through a T between brow plate, nasal and cheeks."""
    cheek = Cube("joue_droite", [-5, 24, 0], [-2, 27, 5], "deep", east="N", north="N")
    return lit([
        Cube("coque", [-4.5, 25, -4.5], [4.5, 32, 4.5], "plate", south=None, down=None),
        # Heavy top: a deep blue block and the azure crest running over it from the brow to the nape.
        Cube("sommet", [-4, 32, -4], [4, 34, 4], "deep", down=None),
        Cube("crete", [-1, 34, -4.5], [1, 35, 4], "plate", up=["CA"] * 8, south=["CP"], north=["AP"]),
        Cube("frontal", [-4.5, 29, 4.5], [4.5, 32, 5.5], "silver", north=None),
        Cube("nasal", [-1, 26, 4.5], [1, 29, 5.5], "dsilver", north=None, up=None),
        cheek, mirror(cheek),
        Cube("nuque", [-4, 24, -5.5], [4, 27, -4.5], "night", south=None),
    ] + emblem("blason", 0, 32, 5.5))


def torso():
    # Cape: the shield of the order painted in its upper half, a dark silver hem.
    cape = stamp(stamp(cloth(8, 13), 2, 2, ["SSSS", "SCCS", "SCWS", "ASSA"]), 0, 12, "DDDDDDDD")
    return lit([
        # Chest wider than the waist: the bastion. No top face under the head, the collar and the pauldrons.
        Cube("torse", [-5, 16, -3], [5, 24, 3], "cloth", up=None, down="N"),
        # Waist in two deep blue lames.
        Cube("abdomen", [-4.5, 12, -2.5], [4.5, 16, 2.5], "deep", up=None,
             sides=["AAAAAPPPN", "PPPPPPPPN", "AAAAAPPPN", "NNNNNNNNN"]),
        Cube("pectoral", [-3, 16, 3], [3, 22, 4], "light", north=None),
        Cube("col", [-4.5, 22, -4.5], [4.5, 25, -3], "dsilver", south=None),
        Cube("cape", [-4, 10, -4.25], [4, 23, -3.25], "cloth", rot=(6, 0, 0), origin=[0, 23, -3.75],
             north=cape, south="P", up="P", down="D", west=["C"] * 5 + ["A"] * 7 + ["D"], east=["P"] * 12 + ["D"]),
    ] + emblem("blason", 0, 21, 4, w=3))


def right_arm():
    cuff5, cuff6 = ["DDDDN"], ["DSSSDN"]
    return lit([
        Cube("manche", [-8.5, 14, -2.4], [-3.5, 24, 2.4], "cloth", up=None, down="P",
             south=stamp(cloth(5, 10), 0, 9, cuff5), north=stamp(cloth(5, 10), 0, 9, cuff5),
             west=stamp(cloth(5, 10), 0, 9, cuff5), east=stamp(cloth(5, 10), 0, 9, cuff5)),
        # Pauldron in two layered lames: the deep blue top one, the azure one stepping out under it.
        Cube("epauliere", [-9.5, 22, -3.5], [-4.5, 25, 3.5], "deep", down="N"),
        Cube("lame", [-10, 20, -3], [-6, 22, 3], "plate", down="N"),
    ])


def left_arm():
    return [mirror(c) for c in right_arm()]


def legs_torso():
    return lit([Cube("ceinture", [-5, 10, -3], [5, 12, 3], "night")] + emblem("insigne", 0, 12, 3))


def right_leg():
    return lit([
        Cube("cuisse", [-4.5, 8, -2.5], [0.5, 12, 2.5], "cloth", up=None),  # under the belt
        Cube("greve", [-4.5, 5, -2.5], [0.5, 8, 2.5], "deep", up=None),
        # Pointed knee cop: the shield of the order again, as large as the knee.
        Cube("genouillere", [-3.5, 7, 2.5], [-0.5, 9, 3.5], "silver", north=None),
        Cube("genouillere_pointe", [-2 - 1.06, 7 - 1.06, 2.5], [-2 + 1.06, 7 + 1.06, 3.45], "silver",
             rot=(0, 0, 45), origin=[-2, 7, 2.5], south=["SD", "DN"], north=None),
    ])


# Top of the cuff: a silver ring around the leg.
RING = ["SSSSSS"] + ["SNNNND"] * 4 + ["DDDDDD"]


def right_boot():
    shin = ["ASSSP", "ASwSP", "PPSPN"]  # the shield painted on the shin plate
    return lit([
        Cube("semelle", [-5, 0, -3], [0.8, 1, 6], "night", up="N"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather", up=None),  # under the cuff
        # Metal foot in lames, lit one row out of two.
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 5.5], "plate", north=None, up=["CAAAP", "PPPPN", "CAAAP"]),
        Cube("plaque", [-4.5, 3, 2.5], [0.5, 6, 3.5], "plate", south=shin, north=None),
        # Silver cuff around the top of the shaft, its buckle on the outer side.
        Cube("revers", [-5, 4, -3], [0.8, 5, 3], "silver", west=["SSISSD"], down="D", up=RING),
    ])


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-2.5, 0.5), "plastron": (0, 0.55), "jambieres": (0, 0.9), "bottes": (0, 0.9)},
        {"sentinelle_casque": "tete", "sentinelle_plastron": "plastron", "sentinelle_jambieres": "jambieres",
         "sentinelle_bottes": "bottes"})
