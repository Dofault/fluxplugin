#!/usr/bin/env python3
"""Legs and feet cosmetics of FluxCosmetics, the collection "NEXUS, Legacy of the Wanderer" (2026-11-21): 17 pieces
worn in the legs slot (knee pads, trousers, skirt, belts, holster, a frog on the thigh...) and 20 in the feet slot
(shoes and boots, skates, flippers, webbed feet, rockets...), drawn in 3D on the legs and feet by FluxArmor.

    <venv with Pillow and numpy>/python3 gen_jambes.py [--deploy] [preview folder [piece...]]

Without --deploy nothing leaves the preview folder. With --deploy it writes to Labs, for each piece <id>: the FluxArmor
set acc_<id> (plugins/FluxArmor/sets and models: one leggings or boots piece, fluxarmor:acc_<id>_leggings|boots) and
its inventory icon acc_<id> in contents/fluxcosmetics; then /fluxarmor reload, /fluxarmor build, /iazip.

Frame of FluxArmor's setkit: feet at y = 0, +x towards the player's left, +z forwards. The right leg is the box x -3.9
to 0.1 (centre CX), y 0 to 12, z -2 to 2; a group of the right leg is mirrored on the left one unless the model gives a
left group of its own (an empty one: nothing on that side, the holster on the right thigh only). Waist pieces go in
jambieres_torse (they follow the body). Lower-case letters glow (setkit shells), as does a cube built with glow=True.
"""
import math
import os
import sys

from coiffes_aetheria import Part, flat, grad, metal, mirror, soft
from coiffes_kit import k, paint, stamp, v

NS = "fluxcosmetics"
ASSETS = v.LABS + "/ItemsAdder/contents/" + NS
SETS = v.LABS + "/FluxArmor/sets"
CX = -1.9  # middle of the right leg

# The 36 colours of NEXUS, nothing else is ever painted.
PALETTE = {
    "K": "#171D27", "D": "#303C4B", "S": "#526779", "A": "#8FA5B5", "W": "#CBD8DF",  # metal
    "N": "#202D46", "B": "#405674", "Z": "#6686A3",  # blue cloth
    "C": "#55C9D1", "Q": "#A1F2E8",  # energy
    "F": "#254D3B", "M": "#477D4E", "J": "#77AD69", "L": "#B4D88C",  # green
    "E": "#493126", "U": "#704A32", "V": "#A87348", "H": "#D2AE78",  # leather, wood
    "R": "#A83C30", "X": "#E15B35", "O": "#F49A42",  # magma, TNT
    "G": "#C99432", "Y": "#E8B94D", "P": "#FFE39A",  # gold
    "I": "#E5DDC5", "T": "#F4F0DF", "1": "#77766E", "2": "#AAA99D",  # light cloth, worn cloth
    "3": "#49365F", "4": "#76579B", "5": "#B59BD6", "6": "#EF8ACB",  # magic
    "7": "#B66C32", "8": "#E49A4E", "9": "#6F2929", "0": "#B94345",  # orange, red cloth
}

RIGHT, LEFT, WAIST = "jambieres_jambe_droite", "jambieres_jambe_gauche", "jambieres_torse"
FOOT, FOOT_LEFT = "bottes_jambe_droite", "bottes_jambe_gauche"


def setup(name, materials, atlas=64):
    k.ATLAS = atlas
    k.setup(name, PALETTE, materials)


def C(name, low, high, material, **kw):
    return Part(name, low, high, material, kw.pop("glow", False), kw.pop("rot", (0, 0, 0)), kw.pop("origin", None), **kw)


def tube(name, y0, y1, material, out=0.5, x=CX, **grids):
    """A sleeve round the right leg (or round x), out pixels away from it; open at both ends unless told."""
    grids.setdefault("up", None)
    grids.setdefault("down", None)
    return C(name, [x - 2 - out, y0, -2 - out], [x + 2 + out, y1, 2 + out], material, **grids)


def waist(name, y0, y1, material, out=0.5, **grids):
    grids.setdefault("up", None)
    grids.setdefault("down", None)
    return C(name, [-4 - out, y0, -2 - out], [4 + out, y1, 2 + out], material, **grids)


def shoe(sole, upper, top=3.4, out=0.5, toe=4.2, sole_h=1.0, base=0.0, **grids):
    """Sole, upper round the foot (open on top: the leg goes in), toe box. grids: sole_*, upper_*, toe_* faces."""
    pick = lambda prefix: {f[len(prefix):]: g for f, g in grids.items() if f.startswith(prefix)}
    up = pick("upper_")
    up.setdefault("up", None)
    up.setdefault("down", None)
    return [
        C("semelle", [CX - 2.6 - out + 0.5, base, -2.7 - out + 0.5], [CX + 2.6 + out - 0.5, base + sole_h, toe + 0.1], sole,
          **pick("sole_")),
        C("tige", [CX - 2 - out, base + sole_h, -2 - out], [CX + 2 + out, top, 2 + out], upper, **up),
        C("bout", [CX - 1.9 - out, base + sole_h, 2 + out], [CX + 1.9 + out, base + sole_h + 1.5, toe], upper, north=None,
          **pick("toe_")),
    ]


def seg_yz(name, p0, p1, w, material, x0, x1, **kw):
    """A bar from p0 to p1 (y, z) between x0 and x1: its top face is at p1."""
    dy, dz = p1[0] - p0[0], p1[1] - p0[1]
    length = math.hypot(dy, dz)
    cy, cz = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
    return C(name, [x0, cy - length / 2, cz - w / 2], [x1, cy + length / 2, cz + w / 2], material,
             rot=(round(math.degrees(math.atan2(dz, dy)), 2), 0, 0), origin=[(x0 + x1) / 2, cy, cz], **kw)


def cut(w, h, inside, colour):
    return paint(w, h, lambda x, y: colour(x, y) if inside(x, y) else ".")


def camo(seed):
    def painter(w, h):
        def pixel(x, y):
            n = ((x // 2) * 7 + (y // 2) * 13 + seed + (x + y) // 3 * 5) % 11
            return "F" if n < 3 else "U" if n < 5 else "H" if n == 5 else "M"
        return paint(w, h, pixel)
    return painter


def rows(w, *letters):
    return [c * w for c in letters]


# ======================================================================================== legs


def knee(steel, plate_grid, extra=()):
    return [
        C("coque", [CX - 2.3, 5, 2.2], [CX + 2.3, 9, 3.3], "steel", north=None, south=plate_grid),
        C("bord_haut", [CX - 2.5, 9, 2.0], [CX + 2.5, 9.7, 3.4], "steel", up="W", north=None),
        tube("sangle", 6.4, 7.2, "strap", out=0.3),
    ] + list(extra)


def knee_pads():
    setup("knee_pads", {"steel": (metal("S", "A", "D", "W"), "W", "D"), "strap": (soft("U", "V", "E"), "V", "E")})
    return {RIGHT: knee("steel", ["AAAAD", "WSSSW", "SWSWD", "DSWSK"])}


def knee_pads_glow():
    setup("knee_pads_glow", {"steel": (metal("S", "A", "D", "W"), "W", "D"), "strap": (soft("D", "S", "K"), "S", "K")})
    crystal = C("cristal", [CX - 1.2, 6, 3.3], [CX + 1.2, 8.4, 3.8], "steel", north=None, south=["qc", "cq"],
                west=["c", "q"], east=["q", "c"], up="q", down="c")
    return {RIGHT: k.lit(knee("steel", ["ADDDD", "DSSSD", "DSSSD", "DDDDK"], [crystal]))}


def shin_guards():
    setup("shin_guards", {"steel": (metal("S", "A", "D", "W"), "W", "D"), "strap": (soft("U", "V", "E"), "V", "E")})
    return {RIGHT: [
        C("plaque", [CX - 2.2, 1.5, 2.2], [CX + 2.2, 6.5, 3.2], "steel", north=None, south=["AWAD", "ASSD", "AWSD", "ASSD", "DSSK"]),
        C("evasement", [CX - 2.6, 6.5, 2.0], [CX + 2.6, 7.6, 3.4], "steel", north=None, up="W"),
        C("pointe", [CX - 1.3, 0.6, 2.2], [CX + 1.3, 1.5, 3.1], "steel", north=None, up=None),
        C("nervure", [CX - 0.5, 2, 3.2], [CX + 0.5, 6, 3.7], "steel", north=None, south=["W", "A", "W", "A"]),
        tube("sangle_haut", 5, 5.6, "strap", out=0.3), tube("sangle_bas", 2.4, 3, "strap", out=0.3),
    ]}


def legs_futuristic():
    setup("legs_futuristic", {"shell": (metal("D", "S", "K", "A"), "A", "K"), "steel": (metal("S", "A", "D", "W"), "W", "D")})
    side = ["SAS", "ScS", "ScS", "DDD"]
    return {RIGHT: k.lit([
        tube("coque", 2, 9, "shell", out=0.5),
        C("panneau", [CX - 1.8, 3, 2.5], [CX + 1.8, 8, 3.1], "steel", north=None, south=["cZZc", "cBBc", "cBBc", "cBBc", "qBBq"],
          up="A"),
        C("module", [CX - 3.3, 4, -1.5], [CX - 2.5, 8, 1.5], "steel", east=None, west=side, south=["A", "c", "c", "D"],
          north=["A", "S", "S", "D"]),
        tube("anneau_haut", 9, 10, "steel", out=0.7, up="A"),
        tube("anneau_bas", 1.2, 2, "steel", out=0.6, down="K"),
    ])}


def trousers(name, painter, top, bottom, waist_grid=None, out=0.45, hem=None, variant_left=None, extra=()):
    """Trousers: a sleeve per leg (thigh and shin), the waist band; variant_left(cubes) paints the left leg apart."""
    setup(name, {"cloth": (painter, top, bottom), "band": (painter, top, bottom),
                 "leather": (soft("U", "V", "E"), "V", "E"), "hem": (flat(hem or bottom), hem or bottom, hem or bottom)})
    leg = [tube("cuisse", 6, 12.4, "cloth", out=out), tube("tibia", 0.8, 6, "cloth", out=out)]
    if hem:
        leg.append(tube("ourlet", 0.4, 1.2, "hem", out=out + 0.15))
    model = {RIGHT: leg + list(extra), WAIST: [waist("taille", 11.4, 13.4, "band", out=0.5, **({"south": waist_grid} if waist_grid else {}))]}
    if variant_left:
        model[LEFT] = [mirror(c) for c in variant_left()]
    return model


def pants_torn():
    cloth = lambda w, h: paint(w, h, lambda x, y: "N" if x == w - 1 or y == h - 1 else "Z" if y == 0 and x < 3 else "B")

    def legs(thigh_tear, shin_tear):
        thigh = stamp(cloth(5, 6), 0, 1, thigh_tear) if thigh_tear else cloth(5, 6)
        shin = stamp(cloth(5, 5), 0, 1, shin_tear) if shin_tear else cloth(5, 5)
        return [tube("cuisse", 6, 12, "cloth", out=0.45, south=thigh), tube("tibia", 1, 6, "cloth", out=0.45, south=shin,
                                                                           down=None)]
    m = trousers("pants_torn", cloth, "Z", "N", waist_grid=["BHBBBBBHB", "NNNNNNNNN"])
    m[RIGHT] = legs(None, [" 2   ", " 1   ", "   2 ", "   1 "])
    m[LEFT] = [mirror(c) for c in legs(["2    ", " 12  ", "  12 ", "   1 "], None)]  # diagonal tear on the left thigh
    for c in m[RIGHT] + m[LEFT]:
        c.grids["down"] = None
    m[RIGHT].append(C("frange", [CX - 2.45, 0.4, 2.45], [CX + 2.45, 1, 2.5], "cloth", south=["1.2.1"], north=None, west=None,
                      east=None, up=None, down=None))
    return m


def pants_camo():
    pocket = C("poche", [CX - 3.3, 6, -1.5], [CX - 2.4, 9.6, 1.5], "leather", east=None, west=["VVV", "UHU", "UUU", "EEE"])
    return trousers("pants_camo", camo(3), "M", "F", waist_grid=None, extra=[pocket])


def pants_striped():
    stripes = lambda w, h: paint(w, h, lambda x, y: "N" if x % 2 == 0 else "I")
    return trousers("pants_striped", stripes, "N", "I", hem="I")


def pants_pyjama():
    def stars(w, h):
        marks = {(1, 1): "5", (3, 3): "6", (4, 0): "5", (0, 4): "6", (2, 5): "5"}
        return paint(w, h, lambda x, y: marks.get((x % 6, y % 7), "3" if x == w - 1 else "4"))
    return trousers("pants_pyjama", stars, "3", "3", out=0.8, hem="5")


def shorts():
    setup("shorts", {"cloth": (soft("U", "V", "E"), "V", "E"), "cuff": (soft("V", "H", "U"), "H", "U")})
    return {RIGHT: [tube("jambe", 7.6, 12.4, "cloth", out=0.6, south=["VUUUE", "UUUUE", "UHUUE", "UUHUE", "EEEEE"]),
                    tube("revers", 7, 7.8, "cuff", out=0.8, down="U")],
            WAIST: [waist("taille", 11.4, 13.4, "cloth", out=0.6, south=["HUUUHUUUH", "EEEEEEEEE"])]}


def skirt():
    setup("skirt", {"cloth": (lambda w, h: paint(w, h, lambda x, y: "C" if y == h - 1 else "N" if x % 3 == 2 else "B"), "Z", "N")})

    def hem(w, h, longer_at):
        return cut(w, h, lambda x, y: y < h - 1 or (x + longer_at) % 3, lambda x, y: "C" if y >= h - 1 else "N" if x % 3 == 2 else "B")
    front_r = C("pan_avant_droit", [-4.8, 6, 2.7], [0, 11.6, 3.1], "cloth", rot=(-8, 0, 0), origin=[0, 11.6, 2.9],
                south=hem(5, 6, 0), north=hem(5, 6, 0), west=None, east=None, up=None, down=None)
    front_l = C("pan_avant_gauche", [0, 7.6, 2.7], [4.8, 11.6, 3.1], "cloth", rot=(-8, 0, 0), origin=[0, 11.6, 2.9],
                south=hem(5, 4, 1), north=hem(5, 4, 1), west=None, east=None, up=None, down=None)
    back = C("pan_arriere", [-4.8, 4.6, -3.1], [4.8, 11.6, -2.7], "cloth", rot=(8, 0, 0), origin=[0, 11.6, -2.9],
             north=hem(10, 7, 2), south=hem(10, 7, 2), west=None, east=None, up=None, down=None)
    side = C("pan_droit", [-5.1, 6.6, -2.7], [-4.7, 11.6, 2.7], "cloth", rot=(0, 0, -8), origin=[-4.9, 11.6, 0],
             west=hem(5, 5, 1), east=hem(5, 5, 1), north=None, south=None, up=None, down=None)
    return {WAIST: [waist("ceinture", 11, 13, "cloth", out=0.7, south=rows(9, "Z", "N"), north=rows(9, "B", "N")),
                    front_r, front_l, back, side, mirror(side)], RIGHT: [], LEFT: []}


def belt_parts():
    return [waist("sangle", 11.2, 12.8, "leather", out=0.6, south=["VUUUUUUUV", "EEEEEEEEE"])]


def belt():
    setup("belt", {"leather": (soft("U", "V", "E"), "V", "E"), "gold": (metal("Y", "P", "G", "P"), "P", "G")})
    return {WAIST: belt_parts() + [C("boucle", [-1.5, 10.8, 2.6], [1.5, 13.2, 3.2], "gold", north=None, south=["PYG", "YEG"])],
            RIGHT: [], LEFT: []}


def belt_pouches():
    setup("belt_pouches", {"leather": (soft("U", "V", "E"), "V", "E"), "gold": (metal("Y", "P", "G", "P"), "P", "G")})
    side = C("poche_droite", [-6.1, 9.2, -1.5], [-4.6, 11.6, 1.5], "leather", east=None, west=["VVV", "UHE"], up="V")
    return {WAIST: belt_parts() + [
        C("poche_avant", [-2, 8.2, 2.6], [2, 11.4, 4.4], "leather", north=None, south=["VVVV", "UHHU", "UUUE"], up="V"),
        side, mirror(side)], RIGHT: [], LEFT: []}


def holster():
    setup("holster", {"leather": (soft("U", "V", "E"), "V", "E"), "strap": (soft("E", "U", "E"), "U", "E")})
    return {RIGHT: [
        C("etui", [CX - 3.7, 5, -1.0], [CX - 2.5, 10, 1.6], "leather", rot=(0, 0, 10), origin=[CX - 3.1, 10, 0], east=None,
          west=["VUE", "HUE", "UHE", "UHE", "EEE"], up="E"),
        C("rabat", [CX - 3.9, 9.4, -1.2], [CX - 2.5, 11, 1.8], "leather", rot=(0, 0, 10), origin=[CX - 3.1, 10, 0], east=None,
          west=["VVV", "UHU"]),
        tube("sangle_haut", 9.6, 10.2, "strap", out=0.3), tube("sangle_bas", 6, 6.6, "strap", out=0.3),
    ], LEFT: []}


def pants_chain():
    setup("pants_chain", {"chain": (lambda w, h: paint(w, h, lambda x, y: "W" if (x + y) % 2 == 0 else "S"), "A", "D"),
                          "steel": (metal("S", "A", "D", "W"), "W", "D")})
    import coiffes_aetheria as a
    front = a.seg("chaine_avant", (-3.4, 12), (-4.8, 8.8), 0.8, "chain", z=2.9, d=0.4)
    back = a.seg("chaine_arriere", (-4.8, 8.8), (-3.4, 12), 0.8, "chain", z=-2.9, d=0.4)
    return {WAIST: [
        C("passant_avant", [-4, 11.6, 2.5], [-2.8, 12.8, 3.3], "steel"),
        C("passant_arriere", [-4, 11.6, -3.3], [-2.8, 12.8, -2.5], "steel"),
        front, back,
        C("chaine_cote", [-5.1, 8.5, -2.9], [-4.7, 9.1, 2.9], "chain", west=["WSWSWS"], east=["SWSWSW"]),
    ], RIGHT: [], LEFT: []}


def leg_frog():
    setup("leg_frog", {"skin": (soft("M", "J", "F"), "L", "F"), "belly": (flat("L"), "L", "J")})
    return {LEFT: [
        C("corps", [4.4, 6, -1.5], [7, 9, 1.5], "skin", west=None, east=["JMM", "MMF", "MFF"]),
        C("tete", [4.4, 8.6, 0.5], [7.4, 11, 3.5], "skin", west=None, south=["LML", "MLM"],
          up=["MMM", "MJM", "KMK"], east=["JMK", "MMM"]),
        C("patte_arriere", [5.6, 5.4, -2.2], [7.6, 6.6, 0.4], "skin", up=["JM", "MF", "FF"]),
        C("patte_avant", [4.4, 9.4, 3.4], [6.2, 10.4, 4.4], "skin", south=["LJ"]),
    ], RIGHT: []}


def belt_tnt():
    setup("belt_tnt", {"leather": (soft("U", "V", "E"), "V", "E"), "tnt": (flat("R"), "I", "9"), "fuse": (flat("E"), "U", "E"),
                       "steel": (metal("S", "A", "D", "W"), "W", "D")}, atlas=128)
    band = ["RR9RRR9RRR9R"] * 4 + ["TTTTTTTTTTTT", "KKK.K..K.KKK", ".K..KK.K..K.", ".K..K.KK..K.", ".K..K..K..K.",
                                   "TTTTTTTTTTTT"] + ["RR9RRR9RRR9R"] * 6
    band = [line.replace(".", "T") for line in band]
    tnt = C("tnt", [4.7, 7.6, -1.5], [7.7, 11.6, 1.5], "tnt", south=band, north=band, east=band, west=None,
            up=["IIIIIIIIIIII"] * 12, down=["999999999999"] * 12)
    tnt.density = 4
    import coiffes_aetheria as a
    return {WAIST: belt_parts() + [
        tnt,
        a.seg("meche", (6.2, 11.6), (6.8, 13), 0.5, "fuse", z=0),
        C("attache", [4.6, 11.2, -0.5], [5.4, 12.8, 0.5], "steel"),
    ], RIGHT: [], LEFT: []}


def katana_sheath():
    setup("katana_sheath", {"lacquer": (lambda w, h: paint(w, h, lambda x, y: "C" if y % 5 == 2 and x == 0 else "N" if x else "B"), "Z", "K"),
                            "gold": (metal("Y", "P", "G", "P"), "P", "G"), "steel": (metal("S", "A", "D", "W"), "W", "D"),
                            "leather": (soft("U", "V", "E"), "V", "E")})
    pts = [(12.6, 2.8), (9.2, 0.6), (6.0, -1.9), (3.3, -4.7)]  # worn on the left hip, mouth forwards, a slight curve
    out = belt_parts()
    for i in range(3):
        out.append(seg_yz("fourreau_%d" % i, pts[i], pts[i + 1], 1.3, "lacquer", 4.7, 5.8))
    out += [seg_yz("embouchure", (13.4, 3.4), pts[0], 1.7, "gold", 4.5, 6.0),
            seg_yz("bout", pts[3], (2.6, -5.4), 1.6, "steel", 4.5, 6.0)]
    return {WAIST: out, RIGHT: [], LEFT: []}


# ======================================================================================== feet


def sneakers():
    setup("sneakers", {"sole": (flat("I"), "T", "2"), "cloth": (soft("B", "Z", "N"), "Z", "N")})
    return {FOOT: shoe("sole", "cloth", top=3.6, upper_south=["BIBIB", "ZBZBZ", "BBBBN"], upper_west=["BBBBB", "TTTTT", "BBBBN"],
                       toe_up=["IBIBI", "BBBBB"], sole_west=["2222222"]) + [
        C("languette", [CX - 1, 3.2, 2.2], [CX + 1, 4.4, 2.8], "cloth", south=["ZZ"])]}


def sneakers_high():
    setup("sneakers_high", {"sole": (flat("I"), "T", "2"), "cloth": (soft("N", "Z", "K"), "Z", "K")})
    return {FOOT: shoe("sole", "cloth", top=5.4, upper_south=["NININ", "ZNZNZ", "NININ", "ZNZNZ"],
                       upper_west=["NNNNN", "ZZZNN", "ZZZZN", "NNNNK"]) + [
        tube("col", 5, 6, "cloth", out=0.7, up="Z"),
        C("languette", [CX - 1, 5.2, 2.3], [CX + 1, 6.6, 2.9], "cloth", south=["ZZ"])]}


def hiking_shoes():
    setup("hiking_shoes", {"sole": (lambda w, h: paint(w, h, lambda x, y: "K" if x % 2 == 0 else "D"), "D", "K"),
                           "leather": (soft("U", "V", "E"), "V", "E"), "cap": (flat("E"), "U", "E"), "collar": (flat("H"), "H", "V")})
    return {FOOT: shoe("sole", "leather", top=4.2, sole_h=1.3, upper_west=["UVVVU", "UVVVU", "UUUUE"]) + [
        C("renfort", [CX - 2.5, 1.2, 3.4], [CX + 2.5, 2.8, 4.4], "cap", north=None),
        tube("col", 4, 5, "collar", out=0.6, up="H")]}


def cowboy_boots():
    setup("cowboy_boots", {"leather": (soft("U", "V", "E"), "V", "E"), "heel": (flat("E"), "U", "E"), "band": (flat("H"), "H", "V")})
    star = ["UUGUU", "UGYGU", "UUGUU", "UUUUU"]
    cubes = shoe("heel", "leather", top=3.4, toe=5.0, sole_h=0.8)
    cubes[2] = C("bout", [CX - 1.5, 0.8, 2.5], [CX + 1.5, 2.2, 5.2], "leather", north=None)
    return {FOOT: cubes + [
        C("talon", [CX - 2.2, 0, -2.8], [CX + 2.2, 1.4, -1.0], "heel"),
        tube("tige", 3.4, 7.6, "leather", out=0.55, west=star, east=[l[::-1] for l in star]),
        tube("revers", 7.6, 8.4, "band", out=0.7, up="H")]}


def military_boots():
    setup("military_boots", {"sole": (lambda w, h: paint(w, h, lambda x, y: "K" if (x + y) % 2 else "D"), "D", "K"),
                             "leather": (soft("D", "S", "K"), "S", "K")})
    laces = ["DSDSD", "SDSDS", "DSDSD"]
    return {FOOT: shoe("sole", "leather", top=4.2, sole_h=1.4, upper_south=laces) + [
        tube("tige", 4.2, 6.6, "leather", out=0.55, south=["SDSDS", "DSDSD"]),
        tube("col", 6.6, 7.4, "leather", out=0.75, up="S"),
        C("coquille", [CX - 2.5, 1.4, 3.6], [CX + 2.5, 2.8, 4.5], "leather", north=None, south=["SSSSD"])]}


def snow_boots():
    setup("snow_boots", {"sole": (flat("2"), "2", "1"), "cloth": (soft("B", "Z", "N"), "Z", "N"),
                         "fur": (lambda w, h: paint(w, h, lambda x, y: "2" if (x * 3 + y * 5) % 7 == 0 else "I" if y else "T"), "T", "2")})
    return {FOOT: shoe("sole", "cloth", top=5, out=0.9, toe=4.8, sole_h=1.4) + [
        tube("fourrure", 5, 6.8, "fur", out=1.2, up="T")]}


def slippers():
    setup("slippers", {"plush": (soft("4", "5", "3"), "5", "3"), "sole": (flat("3"), "3", "3")})
    ear = C("oreille_droite", [CX - 2.2, 3.4, 3.4], [CX - 1.0, 4.6, 4.2], "plush")
    return {FOOT: [
        C("semelle", [CX - 2.6, 0, -2.2], [CX + 2.6, 0.6, 4.8], "sole"),
        C("corps", [CX - 2.5, 0.6, -2.2], [CX + 2.5, 2.6, 2.4], "plush", up=None),
        C("tete", [CX - 2.6, 0.6, 2.4], [CX + 2.6, 3.4, 4.8], "plush", south=["45554", "43434", "44644"]),
        ear, C("oreille_gauche", [CX + 1.0, 3.4, 3.4], [CX + 2.2, 4.6, 4.2], "plush"),
    ]}


def slides():
    setup("slides", {"sole": (lambda w, h: paint(w, h, lambda x, y: "D" if y == h - 1 else "2"), "2", "D"),
                     "strap": (soft("U", "V", "E"), "V", "E")})
    return {FOOT: [
        C("semelle", [CX - 2.6, 0, -2.6], [CX + 2.6, 1, 4.4], "sole", down="K"),
        C("bride", [CX - 2.7, 0.8, 0.2], [CX + 2.7, 2.4, 3.0], "strap", down=None, up=["VUUUV", "UVUVU", "UUVUU"],
          south=["UUVUU", "EEEEE"]),
    ]}


def flippers():
    setup("flippers", {"rubber": (soft("M", "J", "F"), "J", "F"), "blade": (flat("M"), "J", "F")})

    def blade(w, h):
        return cut(w, h, lambda x, y: x >= max(0, 1 - y // 4) and x < w - max(0, 1 - y // 4),
                   lambda x, y: "L" if x in (2, 4) else "F" if y == h - 1 else "J" if x in (1, 5) else "M")
    b = blade(6, 8)  # pushed outwards: the two blades never overlap
    return {FOOT: [
        C("chausson", [CX - 2.5, 0, -2.6], [CX + 2.5, 3, 2.6], "rubber", up=None),
        C("palme", [CX - 4.2, 0, 2.6], [CX + 1.8, 0.6, 10.6], "blade", up=b, down=b, south=["FFFFFF"], north=None,
          west=["FMMMMMMM"], east=["MMMMMMMF"]),
    ]}


def ice_skates():
    setup("ice_skates", {"boot": (soft("B", "Z", "N"), "Z", "N"), "sole": (flat("D"), "S", "K"), "blade": (metal("A", "W", "S", "W"), "W", "S")})
    return {FOOT: shoe("sole", "boot", top=4.6, sole_h=0.6, base=1.2, toe=3.8, upper_south=["BIBIB", "ZBZBZ", "BBBBN"]) + [
        tube("col", 4.6, 5.6, "boot", out=0.7, up="Z"),
        C("support", [CX - 1, 0.6, -2], [CX + 1, 1.2, 3.4], "sole"),
        C("lame", [CX - 0.5, 0, -3.2], [CX + 0.5, 0.6, 5], "blade", west=["WWWWWWWA"], east=["AWWWWWWW"], up="W", down="S"),
    ]}


def roller_skates():
    setup("roller_skates", {"boot": (soft("B", "Z", "N"), "Z", "N"), "frame": (metal("S", "A", "D", "W"), "A", "D"),
                            "wheel": (lambda w, h: paint(w, h, lambda x, y: "A" if (x, y) == (0, 0) else "D" if x + y < 2 else "K"), "D", "K")})
    out = shoe("frame", "boot", top=5.2, sole_h=0.6, base=2, toe=3.8, upper_west=["BBBBB", "CCCCC", "BBBBN"])
    for name, x0, z0 in (("roue_ar_ext", CX - 2.5, -2.4), ("roue_ar_int", CX + 1.5, -2.4), ("roue_av_ext", CX - 2.5, 1.8),
                         ("roue_av_int", CX + 1.5, 1.8)):
        out.append(C(name, [x0, 0, z0], [x0 + 1, 2, z0 + 2], "wheel", west=["AD", "DK"], east=["DA", "KD"]))
    return {FOOT: out + [tube("col", 5.2, 6, "boot", out=0.7, up="Z")]}


def golden_shoes():
    setup("golden_shoes", {"gold": (metal("Y", "P", "G", "P"), "P", "G"), "sole": (flat("E"), "U", "E")})
    return {FOOT: [
        C("semelle", [CX - 2.6, 0.4, -2.6], [CX + 2.6, 1, 4.2], "sole"),
        C("talon", [CX - 2.2, 0, -2.6], [CX + 2.2, 0.6, -1.2], "sole"),
        C("dessus", [CX - 2.5, 1, -2.5], [CX + 2.5, 2.8, 2.5], "gold", up=None, west=["PYYYG", "YYYGG"]),
        C("pointe", [CX - 1.6, 1, 2.5], [CX + 1.6, 2.2, 5.0], "gold", north=None, rot=(-14, 0, 0), origin=[CX, 1, 2.5],
          up=["PYY", "YYG"]),
    ]}


def light_shoes():
    setup("light_shoes", {"sole": (flat("D"), "S", "K"), "shell": (soft("D", "B", "K"), "B", "K")})
    band = ["DDDDD", "ccccc", "DDDDK"]
    return {FOOT: k.lit(shoe("sole", "shell", top=4.2, sole_h=1.2, toe=4.4, upper_west=band, upper_east=band,
                             sole_west=["qqqqqqq"], sole_east=["qqqqqqq"]) + [
        C("module", [CX - 1.5, 1.4, -3.2], [CX + 1.5, 3, -2.4], "shell", south=None, north=["cqc", "SAS"])])}


def magma_boots():
    def rock(w, h):
        def pixel(x, y):
            n = (x * 3 + y * 5) % 11
            return "o" if n == 0 else "x" if n == 1 and y % 2 else "9" if (x + y) % 3 else "R"
        return paint(w, h, pixel)
    setup("magma_boots", {"rock": (rock, "X", "K"), "sole": (lambda w, h: paint(w, h, lambda x, y: "o" if x % 4 == 1 else "K"), "K", "K")})
    plate = C("plaque_droite", [CX - 3.5, 1.5, -1.6], [CX - 3.0, 4.5, 1.6], "rock", east=None)
    return {FOOT: k.lit(shoe("sole", "rock", top=4.6, out=0.8, toe=4.8, sole_h=1.4) + [
        tube("tige", 4.6, 6.8, "rock", out=0.8, up="X"), plate, mirror(plate)])}


def webbed(name, base, light, shade, tip, top):
    """Webbed feet: an ankle block and a flat foot forwards, pushed to the outside of the leg so that the two feet
    never overlap (their flat tops would flicker). Frog: three toes apart with round pads; duck: one web, three lines."""
    setup(name, {"skin": (soft(base, light, shade), top, shade)})
    frog = name == "frog_feet"

    def foot(w, h):
        def inside(x, y):
            return y < 5 or x % 2 == 0 if frog else y < 6 or y < 7 and x % 2 == 0
        def colour(x, y):
            if frog and y == h - 1:
                return tip
            if not frog and x % 2 == 0 and y >= 2:
                return shade
            return light if y < 2 else base
        return cut(w, h, inside, colour)
    f = foot(5, 8)
    return {FOOT: [
        C("cheville", [CX - 2.5, 0.8, -2.6], [CX + 2.5, 3.2, 2.6], "skin", up=None),
        C("patte", [CX - 3.4, 0, -2.6], [CX + 1.6, 0.8, 5.4], "skin", up=f, down=f, south=[f[-1]], north=[shade * 5],
          west=[shade * 8], east=None),
    ]}


def frog_feet():
    return webbed("frog_feet", "M", "J", "F", "L", "L")


def duck_feet():
    return webbed("duck_feet", "7", "8", "E", "Y", "Y")


def clogs():
    setup("clogs", {"wood": (lambda w, h: paint(w, h, lambda x, y: "E" if y % 3 == 2 else "V" if y == 0 else "U"), "V", "E")})
    return {FOOT: [
        C("sabot", [CX - 2.7, 0, -2.7], [CX + 2.7, 3.2, 4.4], "wood", up=None),
        C("bout", [CX - 2.2, 0, 4.4], [CX + 2.2, 2.4, 5.2], "wood", north=None, south=["VUVU", "UEUE"]),
        C("talon_haut", [CX - 2.7, 3.2, -2.7], [CX + 2.7, 4.2, -1.4], "wood"),
        C("ouverture", [CX - 2.2, 3.0, -1.4], [CX + 2.2, 3.2, 3.6], "wood", up=["EEEE"] * 5, down=None, north=None, south=None,
          west=None, east=None),
    ]}


def futuristic_boots():
    setup("futuristic_boots", {"shell": (metal("D", "S", "K", "A"), "A", "K"), "plate": (metal("S", "A", "D", "W"), "W", "D")})
    module = C("module_droit", [CX - 3.4, 3, -1.6], [CX - 2.7, 6.6, 1.6], "plate", east=None, west=["ASA", "ScS", "ScS", "DDD"])
    return {FOOT: k.lit(shoe("shell", "shell", top=4.4, out=0.7, toe=4.6, sole_h=1.4) + [
        tube("tige", 4.4, 7, "shell", out=0.7, up="A", south=["DcccD", "DSSSD", "DDDDK"]),
        C("plaque", [CX - 2.4, 1.8, 4.4], [CX + 2.4, 3.6, 5.0], "plate", north=None, south=["AcqcD", "SSSSD"]),
        module, mirror(module)])}


def particle_boots():
    setup("particle_boots", {"cloth": (soft("4", "5", "3"), "5", "3"), "sole": (flat("K"), "D", "K"), "rune": (flat("5"), "6", "5")})
    return {FOOT: shoe("sole", "cloth", top=4, sole_h=1.0) + [
        tube("tige", 4, 6, "cloth", out=0.55, up="5"),
        C("rune", [CX - 1, 3.2, 3.0], [CX + 1, 5.4, 3.3], "rune", glow=True, south=["65", "56"], north=None, west=None, east=None,
          up=None, down=None),
    ]}


def rocket_boots():
    setup("rocket_boots", {"sole": (flat("2"), "I", "1"), "shoe": (soft("D", "S", "K"), "S", "K"),
                           "body": (metal("S", "A", "D", "W"), "W", "D"), "nose": (flat("R"), "O", "9"),
                           "flame": (lambda w, h: paint(w, h, lambda x, y: "o" if y == 0 else "x"), "o", "x")})
    tilt = dict(rot=(-12, 0, 0), origin=[CX - 3.4, 1, -1.2])
    rocket = [
        C("fusee", [CX - 4.2, 1.6, -2.0], [CX - 2.7, 4.8, -0.5], "body", east=None, west=["WA", "CS", "SD"], **tilt),
        C("ogive", [CX - 4.0, 4.8, -1.8], [CX - 2.9, 5.8, -0.7], "nose", down=None, **tilt),
        C("tuyere", [CX - 4.2, 0.9, -2.0], [CX - 2.7, 1.6, -0.5], "nose", **tilt),
        C("flamme", [CX - 3.9, 0.1, -1.7], [CX - 3.0, 0.9, -0.8], "flame", up=None, **tilt),
        C("aileron", [CX - 4.6, 1.0, -1.5], [CX - 4.2, 2.6, -0.9], "nose", **tilt),
    ]
    return {FOOT: k.lit(shoe("sole", "shoe", top=3.6) + rocket)}


# ---------------------------------------------------------------------------------------- catalogue

# id: (builder, French name, colour of the name, slot)
PIECES = {
    "knee_pads": (knee_pads, "Genouillères", "#8FA5B5", "legs"),
    "shin_guards": (shin_guards, "Protège-jambes", "#8FA5B5", "legs"),
    "legs_futuristic": (legs_futuristic, "Jambières futuristes", "#55C9D1", "legs"),
    "pants_torn": (pants_torn, "Pantalon déchiré", "#6686A3", "legs"),
    "pants_camo": (pants_camo, "Pantalon camouflage", "#77AD69", "legs"),
    "pants_striped": (pants_striped, "Pantalon rayé", "#E5DDC5", "legs"),
    "pants_pyjama": (pants_pyjama, "Pantalon de pyjama", "#B59BD6", "legs"),
    "shorts": (shorts, "Short", "#A87348", "legs"),
    "skirt": (skirt, "Jupe", "#6686A3", "legs"),
    "belt": (belt, "Ceinture", "#E8B94D", "legs"),
    "belt_pouches": (belt_pouches, "Ceinture avec pochettes", "#A87348", "legs"),
    "holster": (holster, "Holster", "#A87348", "legs"),
    "pants_chain": (pants_chain, "Chaîne de pantalon", "#CBD8DF", "legs"),
    "leg_frog": (leg_frog, "Grenouille accrochée à la jambe", "#77AD69", "legs"),
    "belt_tnt": (belt_tnt, "Mini TNT à la ceinture", "#E15B35", "legs"),
    "katana_sheath": (katana_sheath, "Fourreau de katana", "#55C9D1", "legs"),
    "knee_pads_glow": (knee_pads_glow, "Genouillères lumineuses", "#A1F2E8", "legs"),
    "sneakers": (sneakers, "Baskets classiques", "#6686A3", "feet"),
    "sneakers_high": (sneakers_high, "Baskets montantes", "#6686A3", "feet"),
    "hiking_shoes": (hiking_shoes, "Chaussures de randonnée", "#A87348", "feet"),
    "cowboy_boots": (cowboy_boots, "Bottes de cowboy", "#D2AE78", "feet"),
    "military_boots": (military_boots, "Bottes militaires", "#8FA5B5", "feet"),
    "snow_boots": (snow_boots, "Bottes de neige", "#F4F0DF", "feet"),
    "slippers": (slippers, "Pantoufles", "#B59BD6", "feet"),
    "slides": (slides, "Claquettes", "#AAA99D", "feet"),
    "flippers": (flippers, "Palmes", "#77AD69", "feet"),
    "ice_skates": (ice_skates, "Patins à glace", "#CBD8DF", "feet"),
    "roller_skates": (roller_skates, "Rollers", "#55C9D1", "feet"),
    "golden_shoes": (golden_shoes, "Chaussures dorées", "#FFE39A", "feet"),
    "light_shoes": (light_shoes, "Chaussures lumineuses", "#A1F2E8", "feet"),
    "magma_boots": (magma_boots, "Bottes de magma", "#F49A42", "feet"),
    "frog_feet": (frog_feet, "Pieds de grenouille", "#B4D88C", "feet"),
    "duck_feet": (duck_feet, "Pieds de canard", "#E49A4E", "feet"),
    "clogs": (clogs, "Sabots", "#A87348", "feet"),
    "futuristic_boots": (futuristic_boots, "Bottes futuristes", "#55C9D1", "feet"),
    "particle_boots": (particle_boots, "Bottes enchantées", "#B59BD6", "feet"),
    "rocket_boots": (rocket_boots, "Mini fusées aux pieds", "#F49A42", "feet"),
}
LIMBS = ("plastron_torse", "plastron_bras_droit", "jambieres_torse", "jambieres_jambe_droite", "bottes_jambe_droite")


def full(model):
    out = dict(model)
    for g in LIMBS:
        out.setdefault(g, [])
    return out


def previews(name, model, folder):
    os.makedirs(folder, exist_ok=True)
    m = full(model)
    still, walk = k.scene(m, []), k.scene(m, [], 30)
    k.strip([(still, v.rot_y(0), False), (still, v.rot_x(12) @ v.rot_y(-35), False), (still, v.rot_y(-90), False),
             (still, v.rot_y(90), False), (still, v.rot_x(12) @ v.rot_y(145), False), (walk, v.rot_x(15) @ v.rot_y(-55), False)],
            os.path.join(folder, name + "_vues.png"), width=380, size=560, scale=22, centre=(0, 8))


def icon(name, model, slot):
    _, _, legs, feet = k.pieces(full(model), [])
    cubes = legs if slot == "legs" else feet
    corners = [c.rotated((x, y, z)) for c in cubes for x in (c.low[0], c.high[0]) for y in (c.low[1], c.high[1])
               for z in (c.low[2], c.high[2])]
    low, high = [min(p[i] for p in corners) for i in range(3)], [max(p[i] for p in corners) for i in range(3)]
    size = round(min(1.6, 15 / max(1.1 * (high[0] - low[0]), high[1] - low[1] + 1, 1.1 * (high[2] - low[2]))), 2)
    m = v.item_model(v.item_elements(cubes, 1.0, (low[1] + high[1]) / 2),
                     {"rotation": [15, 155, 0], "translation": [0, 0, 0], "scale": [size, size, size]})
    texture = "%s:item/accessoires/%s" % (NS, name)
    m["credit"] = "Influencraft, generated by plugins/fluxplugin/FluxCosmetics/assets/gen_jambes.py"
    m["textures"] = {"main": texture, "glow": texture + "_neon", "particle": texture}
    return m


def deploy(name, model, title, colour, slot):
    item = icon(name, model, slot)  # before the atlas is saved: mirrored cubes paint new rectangles
    v.write_json(os.path.join(v.FLUXARMOR_MODELS, "acc_" + name + ".bbmodel"), k.bbmodel("acc_" + name, model))
    os.makedirs(os.path.join(ASSETS, "textures/item/accessoires"), exist_ok=True)
    k.MAIN.image.save(os.path.join(ASSETS, "textures/item/accessoires", name + ".png"))
    k.GLOW.image.save(os.path.join(ASSETS, "textures/item/accessoires", name + "_neon.png"))
    v.write_json(os.path.join(ASSETS, "models/item", "acc_" + name + ".json"), item)
    v.write_json(os.path.join(ASSETS, "items", "acc_" + name + ".json"),
                 {"model": {"type": "minecraft:model", "model": "%s:item/acc_%s" % (NS, name)}})
    piece = "leggings" if slot == "legs" else "boots"
    with open(os.path.join(SETS, "acc_" + name + ".yml"), "w", encoding="utf-8") as fh:
        fh.write("# Cosmétique « %s » (collection NEXUS) : une seule pièce, dessinée en 3D sur les %s.\n"
                 "# Écrit par plugins/fluxplugin/FluxCosmetics/assets/gen_jambes.py --deploy, ne pas modifier à la main.\n"
                 "# Porté comme cosmétique (FluxCosmetics, clé %s) : aucun bonus, pas de texture 2D de secours.\n"
                 "model: models/acc_%s.bbmodel\n"
                 "pieces:\n"
                 "  %s:\n"
                 "    material: iron_%s\n"
                 "    model: %s:acc_%s\n"
                 "    name: '&%s%s'\n" % (title, "jambes" if slot == "legs" else "pieds", name, name, piece, piece, NS, name,
                                         colour, title.replace("'", "''")))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--deploy"]
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    only = args[1:]
    for name, (build, title, colour, slot) in PIECES.items():
        if only and name not in only:
            continue
        model = build()
        cubes = [c for part in model.values() for c in part]
        print("  %-18s %2d cubes %3d faces" % (name, len(cubes), sum(len(c.faces()) for c in cubes)))
        if args:
            previews(name, model, args[0])
        if "--deploy" in sys.argv:
            deploy(name, model, title, colour, slot)
    if "--deploy" in sys.argv:
        print("Deployed to Labs: /fluxarmor reload, /fluxarmor build, then /iazip.")
