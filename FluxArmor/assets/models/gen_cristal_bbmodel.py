#!/usr/bin/env python3
"""Crystal set ("Cristal"): the light cryomancer outfit, five cubes per piece, built from the owner's brief.

    <venv with Pillow and numpy>/python3 gen_cristal_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder. With --deploy it writes to Labs:
- plugins/FluxArmor/models/cristal.bbmodel: robe, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn crown casque_cristal and the 3D inventory models of the other pieces.

The four pieces (brief: prompt/armure.md family, ten pale ice colours only, soft vertical gradients, no outline):
- Crown of Frost: a ring around the brow, a tall crystal in front, a leaning one on each side, a small one behind;
- Crystal Robe: a sleeveless body lighter at the top, a flared hem, a pauldron tilted on each shoulder, a chest crystal;
- Frost Leggings: two legs under a belt, a crystal on each knee;
- Crystal Boots: a boot and its sole per foot, a tilted crystal on the instep.

The brief's boxes are kept, moved to the player's frame and grown by half a pixel where they would lie in the skin's
outer layer (body, legs, boots, ring). The violet heart of the two main crystals is the only emissive pixel.
Grids, faces and emissive letters: setkit.py.
"""
from setkit import Cube, lit, mirror, paint, run, setup, stamp

PALETTE = {"W": "#FFFFFF", "F": "#F0F9FF", "I": "#E0F7FA", "B": "#B2EBF2", "C": "#80DEEA",  # snow to soft cyan
           "M": "#4DD0E1", "D": "#26C6DA", "K": "#A5F3FC", "V": "#C4B5FD", "S": "#94A3B8"}  # cyans, crystal, violet, silver

# ---------------------------------------------------------------------------------------- painters


def ramp(w, h, letters, edge=None):
    """Vertical gradient through letters, top to bottom; edge = letter of a lighter top row."""
    def pixel(x, y):
        if edge and y == 0 and h >= 2:
            return edge
        return letters[min(len(letters) - 1, y * len(letters) // h)]
    return paint(w, h, pixel)


def ice(w, h):
    return ramp(w, h, "IB", "W")


def robe(w, h):
    """Cloth: very soft gradient, a column of the next tone here and there instead of folds."""
    letters = "IIBBC"
    def pixel(x, y):
        step = min(len(letters) - 1, y * len(letters) // h)
        if w >= 5 and x % 5 == 1 and 0 < y < h - 1:
            step = min(len(letters) - 1, (y + 1) * len(letters) // h)
        return letters[step]
    return paint(w, h, pixel)


def hem(w, h):
    return ramp(w, h, "BBC")


def crystal(w, h):
    return ramp(w, h, "WKC") if h >= 3 else ramp(w, h, "WK")


def pale(w, h):
    """Side crystals of the crown: crystal blue all over, a white tip."""
    return ramp(w, h, "KKC", "F")


def boot(w, h):
    return ramp(w, h, "BCM")


setup("cristal", PALETTE, {
    "ice": (ice, "W", "B"), "robe": (robe, "I", "C"), "hem": (hem, "B", "C"), "crystal": (crystal, "W", "C"),
    "pale": (pale, "F", "C"), "pauldron": (lambda w, h: ramp(w, h, "FIB", "W"), "W", "B"),
    "chest": (lambda w, h: ramp(w, h, "KCM"), "K", "M"), "leg": (lambda w, h: ramp(w, h, "IBC"), "I", "C"),
    "belt": (lambda w, h: ramp(w, h, "B", "I"), "I", "B"), "boot": (boot, "B", "M"),
    "sole": (lambda w, h: ["D" * w] * h, "M", "D")})

# ---------------------------------------------------------------------------------------- pieces


def hat():
    heart = stamp(crystal(3, 4), 1, 1, "v")
    return lit([
        Cube("base_cercle", [-5, 29.5, -5], [5, 31, 5], "ice"),
        Cube("cristal_central", [-1.5, 30.5, 4.6], [1.5, 34, 5.8], "crystal", south=heart),
        Cube("cristal_droit", [-4.3, 30.3, 4.5], [-2.5, 33.1, 5.5], "pale", rot=(0, 15, 0), origin=[-3.4, 31.7, 5]),
        Cube("cristal_gauche", [2.5, 30.3, 4.5], [4.3, 33.1, 5.5], "pale", rot=(0, -15, 0), origin=[3.4, 31.7, 5]),
        Cube("petit_cristal_arriere", [-0.75, 30.2, -5.6], [0.75, 32.2, -4.8], "ice"),
    ])


def torso():
    gem = stamp(ramp(3, 3, "KCM"), 1, 1, "v")
    return lit([
        # No top face: it is under the head.
        Cube("corps_principal", [-4.5, 14, -2.5], [4.5, 24, 2.5], "robe", up=None),
        Cube("bas_robe", [-5, 12.5, -3], [5, 15, 3], "hem"),
        Cube("cristal_poitrine", [-1.5, 19, 2.5], [1.5, 22, 3.1], "chest", south=gem, north=None),
    ])


def right_arm():
    # The pauldron caps the top of the arm, tilted 8 degrees outwards.
    return [Cube("epauliere", [-9, 22, -3], [-3, 25, 3], "pauldron", rot=(0, 0, 8), origin=[-6, 23, 0])]


def left_arm():
    return [mirror(c) for c in right_arm()]


def legs_torso():
    return [Cube("bande_ceinture", [-5, 10.5, -3], [5, 12, 3], "belt")]


def right_leg():
    return [
        Cube("jambe", [-4.5, 4, -2.5], [0.5, 12, 2.5], "leg", up=None),  # under the belt, above the boot
        Cube("cristal_genou", [-3.1, 6.5, 2.5], [-0.9, 9, 3.3], "crystal", north=None),
    ]


def right_boot():
    return [
        Cube("semelle", [-4.75, 0, -2.75], [0.75, 0.8, 3.75], "sole"),
        Cube("botte", [-4.5, 0.8, -2.5], [0.5, 4, 3.5], "boot"),
        Cube("cristal_avant", [-2.9, 1.5, 3.5], [-1.1, 3.5, 4.2], "crystal", rot=(10, 0, 0), origin=[-2, 2.5, 3.5]),
    ]


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-2.5, 0.55), "plastron": (0, 0.6), "jambieres": (0, 0.95), "bottes": (0, 0.95)})
