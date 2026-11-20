#!/usr/bin/env python3
"""Pyromancer of the Ashes set ("Pyromancien"): ritual outfit of a fire mage, cloth and leather held by forged metal.

    <venv with Pillow and numpy>/python3 gen_pyromancien_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, pyromancien.bbmodel and one editable Blockbench
file per piece: pyromancer_head, _chest, _legs, _feet). With --deploy it writes to Labs:
- plugins/FluxArmor/models/pyromancien.bbmodel: coat, legs, boots (then /fluxarmor build, /iazip);
- contents/influoutils: the worn diadem casque_pyromancien and the 3D inventory models of the other pieces.

The four pieces:
- Diadem of the Old Kiln: blackened band, leather cap, a small hearth on top (stone turned 45 degrees between four
  bronze posts, a live ember in it), a bronze lozenge with an ember on the brow, a tall fin on the right, a low plate
  on the left;
- Coat of the Brazier: burnt black cloth, folded hood and a high leather collar edged with bronze, the emblem (a bronze
  lozenge holding the ember, over a forged cradle), stepped forged pauldron on the left, leather one on the right, two coat
  tails of different lengths;
- Veil of the Ashes: charcoal trousers, two tabard panels of different lengths with a copper border and a scorched
  end, a metal knee and a leather thigh guard on the left leg, a strap and its clasp on the right one;
- Boots of the Burning Lands: thick sole, leather, blackened toe and instep, a volcanic stone with one ember crack.

Light comes from the top left. Emissive pixels: the two embers of the diadem, the heart of the emblem, the cracks of
the boots. Grids, faces and emissive letters: see setkit.py.
"""
from setkit import Cube, lit, mirror, paint, run, setup, stamp

PALETTE = {"N": "#211D20", "B": "#302426", "A": "#49332D", "T": "#674437",  # charcoal, burnt black, ash brown, earth
           "K": "#81513A", "P": "#A66B43", "Z": "#BD8956", "O": "#D4A66A",  # dark copper, patina, bronze, old gold
           "R": "#A83F2F", "I": "#C65A35", "H": "#E18B43", "L": "#E5B66D",  # ember, glowing red, warm orange, bright gold
           "C": "#D4BDA0", "S": "#96806C"}  # light ash, warm stone

# ---------------------------------------------------------------------------------------- painters


def fabric(w, h):
    """Matt burnt cloth: straight folds starting at different heights, a warmer strip where the light falls."""
    def pixel(x, y):
        fold = w >= 4 and x % 4 == 2
        start = int(h * 0.3) + ((x // 4) % 2) * max(1, int(h * 0.25))
        if fold and start <= y < h - (x // 4) % 2:
            return "N"
        if w >= 3 and x == w - 1:
            return "N"
        if w >= 3 and x == 0 and y < h * 0.4:
            return "A"
        return "B"
    return paint(w, h, pixel)


def dark(w, h):
    """Charcoal cloth of the trousers, darker than the coat."""
    return paint(w, h, lambda x, y: "B" if w >= 3 and (x == 0 and y < h * 0.5 or x == 1 and y == 0) else "N")


def panel(w, h):
    """Ash brown tabard cloth: dark copper border on the lit side, shade on the other, a scorched end."""
    def pixel(x, y):
        if y == h - 1:
            return "N"
        if w >= 3 and x == 0:
            return "K"
        if w >= 3 and x == w - 1:
            return "B"
        return "A"
    return paint(w, h, pixel)


def leather(w, h):
    """Warm worn leather: one lit corner, shade below and on the right."""
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "A"
        if w >= 3 and h >= 2 and y == 0 and x <= 1:
            return "K"
        return "T"
    return paint(w, h, pixel)


def strap(w, h):
    """Dark belt leather."""
    return paint(w, h, lambda x, y: "T" if w >= 3 and y == 0 and x <= w // 3 else ("N" if h >= 2 and y == h - 1 else "A"))


def iron(w, h):
    """Forged black metal: a dense dark field, a short copper glint on the top edge, never an outline."""
    def pixel(x, y):
        if y == 0 and w >= 3 and h >= 2 and x < (w + 1) // 2:
            return "P" if x == 1 else "K"
        if x + y < max(1, min(w, h) // 2 + 1):
            return "B"
        return "N"
    return paint(w, h, pixel)


def copper(w, h):
    def pixel(x, y):
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "A"
        if y == 0:
            return "Z" if x == 0 and w >= 3 else "P"
        return "K"
    return paint(w, h, pixel)


def bronze(w, h):
    def pixel(x, y):
        if x == 0 and y == 0:
            return "O"
        if w >= 2 and x == w - 1 or h >= 2 and y == h - 1:
            return "K" if x == w - 1 and y == h - 1 else "P"
        return "Z"
    return paint(w, h, pixel)


def stone(w, h):
    """Volcanic stone: almost black, a few flat variations, one lit grain."""
    return paint(w, h, lambda x, y: "S" if (x, y) == (0, 0) and w * h >= 4 else ("B" if (3 * x + 5 * y) % 7 == 0 else "N"))


setup("pyromancien", PALETTE, {
    "fabric": (fabric, "A", "N"), "dark": (dark, "B", "N"), "panel": (panel, "A", "N"), "leather": (leather, "T", "A"),
    "strap": (strap, "A", "N"), "iron": (iron, "B", "N"), "copper": (copper, "P", "A"), "bronze": (bronze, "Z", "K"),
    "stone": (stone, "B", "N")})

EMBER = ["lh", "hi"]  # bright gold where the light falls, cooling towards the bottom right
# Bronze lozenge: a frame around a dark copper bed, the ember sits on it.
LOZENGE = ["OZZP", "ZKKP", "ZKKP", "PPPK"]

# ---------------------------------------------------------------------------------------- head


def hat():
    band = stamp(iron(10, 2), 8, 1, "Z")
    side = stamp(iron(8, 2), 6, 1, "Z")
    fin = dict(rot=(-12, 0, 0), origin=[-5.5, 28, -0.5])
    posts = [Cube("montant", [x, 34, z], [x + 1, 36, z + 1], "copper") for x in (-3, 2) for z in (-3, 2)]
    return lit([
        # Band around the brow, leather cap over the skull.
        Cube("bandeau_avant", [-5, 29, 4], [5, 31, 5], "iron", south=band, north=None),
        Cube("bandeau_arriere", [-5, 29, -5], [5, 31, -4], "iron", north=band, south=None),
        Cube("bandeau_droit", [-5, 29, -4], [-4, 31, 4], "iron", west=side, east=None),
        Cube("bandeau_gauche", [4, 29, -4], [5, 31, 4], "iron", east=side, west=None),
        Cube("calotte", [-4.5, 31, -4.5], [4.5, 33, 4.5], "strap"),
        # The hearth: a volcanic stone turned 45 degrees between four posts, the ember on it.
        Cube("plaque_foyer", [-3, 33, -3], [3, 34, 3], "copper"),
        *posts,
        Cube("foyer", [-1.5, 34, -1.5], [1.5, 36, 1.5], "stone", rot=(0, 45, 0)),
        Cube("braise", [-1, 36, -1], [1, 37, 1], "copper", True, rot=(0, 45, 0), up=EMBER, sides=["hi"], down=None),
        # Brow ornament: bronze lozenge, its ember standing out of it.
        Cube("losange_frontal", [-2, 29.5, 5], [2, 33.5, 6], "bronze", rot=(0, 0, 45), south=LOZENGE, north=None),
        Cube("gemme_frontale", [-1, 30.5, 6], [1, 32.5, 7], "copper", True, rot=(0, 0, 45), south=EMBER, north=None,
             west=["i", "r"], east=["i", "r"], up=["hi"], down=["ir"]),
        # Right side: a tall forged fin leaning back, a short blade behind it.
        Cube("aileron", [-6, 28, -2], [-5, 34, 1], "iron", **fin),
        Cube("aileron_pointe", [-6, 34, -2], [-5, 35, 0], "copper", **fin),
        Cube("lame_arriere", [-6, 29, -4], [-5, 32, -2], "iron"),
        # Left side: a low plate with a bronze boss.
        Cube("plaque_gauche", [5, 28, -3], [6, 32, 1], "iron"),
        Cube("bossage", [6, 29, -2], [7, 31, 0], "bronze"),
        # Back: leather nape band and its clasp.
        Cube("nuque", [-4.5, 27, -5], [4.5, 29, -4], "strap", south=None),
        Cube("fermoir_nuque", [-1, 27, -6], [1, 29, -5], "copper", south=None),
    ])


# ---------------------------------------------------------------------------------------- chest


def torso():
    # Coat front: closing seam in the middle, lapels opening under the collar.
    front = stamp(stamp(fabric(9, 12), 4, 0, ["N"] * 12), 2, 0, ["A   A", " A A "])
    collar_back = stamp(strap(12, 5), 0, 0, "OZZZZZZZZZZP")
    wing = stamp(strap(4, 3), 0, 0, "ZZZP")
    step = stamp(strap(3, 2), 0, 0, "OZP")
    belt = strap(10, 2)
    tail = dict(rot=(10, 0, 0))
    return lit([
        # No top face: it is under the head.
        Cube("manteau", [-4.5, 12, -2.5], [4.5, 24, 2.5], "fabric", south=front, up=None),
        Cube("capuche", [-4.5, 21, -5], [4.5, 24, -2.5], "fabric"),
        # High collar standing behind the head and on the shoulders, lower towards the front.
        Cube("col_dos", [-6, 22, -6], [6, 27, -5], "strap", north=collar_back, south=collar_back, up="Z"),
        Cube("col_droit", [-6, 24, -5], [-5, 27, -1], "strap", west=wing, east=wing, up="Z", north=None),
        Cube("col_gauche", [5, 24, -5], [6, 27, -1], "strap", west=wing, east=wing, up="Z", north=None),
        Cube("col_droit_bas", [-6, 24, -1], [-5, 26, 2], "strap", west=step, east=step, up="Z"),
        Cube("col_gauche_bas", [5, 24, -1], [6, 26, 2], "strap", west=step, east=step, up="Z"),
        # Emblem: the ember held in a bronze lozenge, a spark above, a forged cradle below.
        Cube("embleme", [-2, 16.5, 2.5], [2, 20.5, 3.5], "bronze", rot=(0, 0, 45), south=LOZENGE, north=None),
        Cube("embleme_coeur", [-1, 17.5, 3.5], [1, 19.5, 4.5], "copper", True, rot=(0, 0, 45), south=EMBER, north=None,
             west=["i", "r"], east=["i", "r"], up=["hi"], down=["ir"]),
        Cube("embleme_etincelle", [-1, 21.3, 2.5], [1, 23.3, 3.5], "copper", rot=(0, 0, 45), south=["ZP", "PK"], north=None),
        Cube("berceau_droit", [-2.5, 14.1, 2.5], [-1.5, 18.1, 3.5], "copper", rot=(0, 0, 35), north=None),
        Cube("berceau_gauche", [1.5, 14.1, 2.5], [2.5, 18.1, 3.5], "copper", rot=(0, 0, -35), north=None),
        # Belt of the coat, its buckle and a copper tag.
        Cube("ceinture_manteau", [-5, 12, -3], [5, 14, 3], "strap", south=belt, north=belt),
        Cube("boucle_manteau", [-1, 12, 3], [1, 14, 4], "bronze", south=["OZ", "ZK"], north=None),
        Cube("pendeloque", [3, 11, 3], [4, 14, 4], "copper", north=None),
        # Coat tails of different lengths, leaning away from the legs, and a panel on the right hip.
        Cube("basque_droite", [-4.5, 6, -3.5], [-0.5, 12, -2.5], "fabric", origin=[-2.5, 12, -3], **tail,
             north=stamp(fabric(4, 6), 0, 5, "AAAA")),
        Cube("basque_gauche", [0.5, 8, -3.5], [4.5, 12, -2.5], "fabric", origin=[2.5, 12, -3], **tail,
             north=stamp(fabric(4, 4), 0, 3, "AAAA")),
        Cube("pan_hanche", [-5.5, 8, -2.5], [-4.5, 12, 2.5], "fabric", west=stamp(fabric(5, 4), 0, 3, "AAAAA")),
    ])


def sleeve():
    cuff6, cuff7 = stamp(strap(6, 2), 2, 0, "Z"), stamp(strap(7, 2), 3, 0, "Z")
    return [
        Cube("manche", [-8.5, 14, -3], [-3.5, 24, 3], "fabric"),
        Cube("poignet", [-9, 14, -3.5], [-3, 16, 3.5], "strap", south=cuff6, north=cuff6, west=cuff7, east=cuff7),
    ]


def right_arm():
    """Leather pauldron: a pad with a stitched edge, a hanging flap, a copper ridge."""
    pad = stamp(leather(7, 3), 1, 1, "K K K")
    return sleeve() + [
        Cube("epaule_cuir", [-10, 22, -3.5], [-6, 25, 3.5], "leather", west=pad),
        Cube("lame_cuir", [-9.5, 19, -3], [-8.5, 22, 3], "strap"),
        Cube("renfort_epaule", [-10, 25, -1], [-6, 26, 1], "copper"),
    ]


def left_arm():
    """Forged pauldron: a block, a narrower step with a copper top over it, a hanging lame."""
    return [mirror(c) for c in sleeve()] + [
        Cube("epaule_fer", [6, 21, -3.5], [10, 24, 3.5], "iron"),
        Cube("epaule_fer_haut", [6, 24, -2.5], [9, 25, 2.5], "iron", up=copper(3, 5)),
        Cube("lame_fer", [8.5, 18, -3], [9.5, 21, 3], "iron", east=stamp(iron(6, 3), 2, 1, "ZZ")),
    ]


# ---------------------------------------------------------------------------------------- legs


def legs_torso():
    return [
        Cube("ceinture", [-5, 10, -3], [5, 12, 3], "strap"),
        Cube("boucle", [-4, 10, 3], [-2, 12, 4], "bronze", south=["OZ", "ZK"], north=None),
    ]


def right_leg():
    """Cloth leg: long tabard panel with an uneven end, a strap under the knee and its clasp."""
    long_panel = stamp(panel(4, 5), 1, 4, "R")
    return [
        Cube("pantalon", [-4.5, 5, -2.5], [0.5, 12, 2.5], "dark", up=None),  # under the belt
        Cube("pan_long", [-4, 5, 2.5], [0, 10, 3.5], "panel", south=long_panel, west="K", east="K"),
        Cube("pan_pointe", [-4, 4, 2.5], [-2, 5, 3.5], "panel", south=["KN"], north=["NK"], west="K", east="N"),
        Cube("sangle", [-5, 6, -3], [0, 7, 3], "strap"),
        Cube("fermoir", [-6, 5, -1], [-5, 8, 1], "copper"),
    ]


def left_leg():
    """Guarded leg: short panel, forged knee, leather thigh guard under a metal plate."""
    trousers = mirror(right_leg()[0])
    return [
        trousers,
        Cube("pan_court", [0.5, 7, 2.5], [3.5, 10, 3.5], "panel", west="K", east="K"),
        Cube("genouillere", [0.5, 5, 2.5], [4.5, 7, 3.5], "iron", south=stamp(iron(4, 2), 2, 1, "Z")),
        Cube("cuissard", [4.5, 7, -2.5], [5.5, 11, 2.5], "leather"),
        Cube("plaque_cuisse", [5.5, 8, -1.5], [6.5, 10, 1.5], "iron", east=["KPB", "BZN"]),
    ]


# ---------------------------------------------------------------------------------------- feet


def right_boot():
    sole_side = stamp(paint(9, 1, lambda x, y: "N"), 2, 0, "Z   Z")
    return lit([
        Cube("semelle", [-5, 0, -3], [1, 1, 6], "stone", south="N", north="N", west=sole_side, east="N", up="B", down="N"),
        Cube("tige", [-4.5, 1, -2.5], [0.5, 5, 2.5], "leather", sides=stamp(leather(5, 4), 1, 1, ["K", " ", "K"])),
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 4.5], "leather"),
        # Blackened toe and instep, a volcanic stone set in front of the instep.
        Cube("coque", [-4.5, 1, 4.5], [0.5, 3, 5.5], "iron"),
        Cube("cou_de_pied", [-3.5, 3, 2.5], [-0.5, 4, 4.5], "iron"),
        Cube("pierre", [-3, 3, 4.5], [-1, 4, 5.5], "stone", up=["Br"], south=["SN"]),
        # Ankle strap and its bronze buckle on the outside, heel counter.
        Cube("sangle_cheville", [-5, 3, -3], [1, 4, 3], "strap", sides="A", up="A"),
        Cube("boucle_cheville", [-6, 2, -1], [-5, 5, 1], "bronze"),
        Cube("talon", [-4, 1, -3.5], [0, 3, -2.5], "iron"),
    ])


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": legs_torso, "jambieres_jambe_droite": right_leg, "jambieres_jambe_gauche": left_leg,
          "bottes_jambe_droite": right_boot}

if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-2.5, 0.5), "plastron": (0, 0.55), "jambieres": (0, 0.9), "bottes": (0, 0.9)},
        {"pyromancer_head": "tete", "pyromancer_chest": "plastron", "pyromancer_legs": "jambieres", "pyromancer_feet": "bottes"})
