#!/usr/bin/env python3
"""Oracle: voxel wizard, guide and quest giver, STATIC Blockbench model (.bbmodel).

Built on 2026-11-10 from the voxel specification "Cahier des charges, PNJ L'Oracle (modèle 3D voxel)": every block of
its §13.3 inventory is laid on a voxel grid in the listed order (a later block repaints what it covers), then the
voxels are merged into a few axis-aligned boxes per bone, each face textured one texel per voxel. No rotation
anywhere: slopes (hat tip, raised forearm) are stair steps, as the specification requires.

Specification axes: X negative = viewer's left = the character's RIGHT hand (staff side), Y up, Z negative = front.
Blockbench axes (all our models): front faces north (-z), the character's right is +x. So x = -X, y = Y, z = Z.

Usage: oracle.py [output folder] [--apercu <sheet.png>]
"""
import json
import os
import random
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, bake, export, ground, hx  # noqa: E402

IDENT = "oracle"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000000a7c1")
HEIGHT = 3.2          # blocks in game for the 32 voxels (1 voxel = 0.1 block)
Z_SHIFT = -2.5        # puts the middle of the robe (Z -1..5) on the origin

# --------------------------------------------------------------------------- palette (§2)
VP, VM, VF, CH = hx("#5B2D8E"), hx("#4E2780"), hx("#2E1A47"), hx("#24162F")
OR = hx("#E0A82E")
GB, GC, GM = hx("#E6E6EA"), hx("#C9C9CF"), hx("#A7A7AF")
PE, PO, YE = hx("#F5C9A0"), hx("#EBA27E"), hx("#2A1A16")
BF, BC, VC = hx("#5A3A2E"), hx("#6E4A3A"), hx("#C77DFF")
VM_SHARE, VM_SEED = 0.20, 1717      # §10: about 20 % of the purple voxels in the darker variation, fixed seed

# --------------------------------------------------------------------------- inventory (§13.3), specification coordinates
# (id, bone, min corner (X, Y, Z), size (L, H, P), colour or None = face rule only, down faces in VF)
BLOCKS = [
    ("P01", "Right_Leg", (-3, 0, -2), (3, 2, 6), CH, False),
    ("P02", "Left_Leg", (1, 0, -2), (3, 2, 6), CH, False),
    ("P03", "Body", (-5, 2, -1), (11, 5, 7), VP, False),
    ("P04", "Body", (-5, 2, -1), (11, 1, 7), OR, False),
    ("P05", "Body", (-4, 7, 0), (9, 6, 6), VP, False),
    ("P06", "Body", (-5, 13, 0), (11, 2, 6), VP, False),
    ("P06d", "Body", (-5, 13, 0), (1, 1, 6), None, True),      # §5.5 under the shoulders, X -5
    ("P06e", "Body", (5, 13, 0), (1, 1, 6), None, True),       # §5.5 under the shoulders, X +5
    ("P07", "Body", (-3, 3, -1), (1, 4, 1), VF, False),
    ("P08", "Body", (3, 3, -1), (1, 4, 1), VF, False),
    ("P09", "Body", (-2, 3, 5), (1, 10, 1), VF, False),
    ("P10", "Body", (2, 3, 5), (1, 10, 1), VF, False),
    ("P11", "Body", (0, 3, -1), (1, 4, 1), OR, False),
    ("P12", "Body", (0, 7, 0), (1, 6, 1), OR, False),
    ("P13", "Body", (-4, 8, 0), (9, 1, 6), BF, False),
    ("P14", "Body", (-1, 7, -1), (3, 3, 1), OR, False),
    ("P15", "Body", (0, 8, -1), (1, 1, 1), BF, False),
    ("P16", "Head", (-3, 15, 0), (7, 6, 6), PE, False),
    ("P17", "Head", (-3, 15, 0), (1, 6, 6), GC, False),
    ("P18", "Head", (3, 15, 0), (1, 6, 6), GC, False),
    ("P19", "Head", (-3, 15, 5), (7, 6, 1), GM, False),
    ("P20", "Head", (-3, 14, 0), (1, 1, 3), GC, False),
    ("P21", "Head", (3, 14, 0), (1, 1, 3), GC, False),
    ("P22", "Head", (-2, 20, 0), (1, 1, 1), GC, False),
    ("P23", "Head", (2, 20, 0), (1, 1, 1), GC, False),
    ("P24", "Head", (-2, 18, 0), (1, 2, 1), YE, False),
    ("P25", "Head", (2, 18, 0), (1, 2, 1), YE, False),
    ("P26", "Head", (-2, 17, 0), (1, 1, 1), PO, False),
    ("P27", "Head", (2, 17, 0), (1, 1, 1), PO, False),
    ("F16", "Head", (-2, 16, 0), (5, 1, 1), GB, False),        # §6.2 face row Y16
    ("F15", "Head", (-2, 15, 0), (5, 1, 1), GC, False),        # §6.2 face row Y15, ends repainted below
    ("F15a", "Head", (-2, 15, 0), (1, 1, 1), GB, False),
    ("F15b", "Head", (2, 15, 0), (1, 1, 1), GB, False),
    ("P28", "Head", (0, 17, -1), (1, 1, 1), PO, False),
    ("P29", "Beard", (-2, 16, -1), (5, 1, 1), GB, False),
    ("B1", "Beard", (-2, 15, -1), (5, 1, 1), GC, False),
    ("B2", "Beard", (-2, 14, -1), (5, 1, 1), GC, False),
    ("B3", "Beard", (-2, 13, -1), (5, 1, 1), GC, False),
    ("B1a", "Beard", (-2, 13, -1), (1, 3, 1), GB, False),      # GB ends of B1 to B3
    ("B1b", "Beard", (2, 13, -1), (1, 3, 1), GB, False),
    ("B4", "Beard", (-1, 12, -1), (3, 1, 1), GC, False),
    ("B4c", "Beard", (0, 12, -1), (1, 1, 1), GM, False),
    ("B5", "Beard", (0, 11, -1), (1, 1, 1), GM, False),
    ("P30", "Right_Arm", (-7, 12, 0), (2, 3, 4), VP, False),
    ("P31", "Right_Arm", (-7, 11, -1), (2, 1, 5), VP, True),
    ("P32", "Right_Arm", (-7, 11, -2), (2, 1, 1), OR, True),
    ("P33", "Right_Arm", (-7, 9, -2), (2, 2, 2), PE, False),
    ("P34", "Right_Arm", (-8, 9, -2), (1, 2, 1), PE, False),
    ("P35", "Left_Arm", (6, 10, 0), (2, 5, 4), VP, False),
    ("P36", "Left_Arm", (6, 9, -1), (2, 1, 5), OR, True),
    ("P37", "Left_Arm", (6, 7, 0), (2, 2, 2), PE, False),
    ("S1", "Staff", (-8, 0, -1), (1, 16, 1), BF, False),
    ("S2", "Staff", (-8, 16, -1), (1, 1, 1), BF, False),
    ("S3", "Staff", (-9, 16, -1), (1, 1, 1), BF, False),
    ("S4", "Staff", (-7, 16, -1), (1, 1, 1), BF, False),
    ("S5", "Staff", (-8, 16, -2), (1, 1, 1), BF, False),
    ("S6", "Staff", (-8, 16, 0), (1, 1, 1), BF, False),
    ("S7", "glow_orb", (-8, 17, -1), (1, 1, 1), VC, False),
    ("S8", "Staff", (-9, 17, -1), (1, 1, 1), BC, False),
    ("S9", "Staff", (-7, 17, -1), (1, 1, 1), BC, False),
    ("S10", "Staff", (-8, 17, 0), (1, 1, 1), BC, False),
    ("S11", "Staff", (-9, 18, -1), (1, 1, 1), BF, False),
    ("S12", "Staff", (-7, 18, -1), (1, 1, 1), BF, False),
    ("S13", "Staff", (-8, 18, -1), (1, 1, 1), BF, False),
    ("S14", "Staff", (-8, 19, -1), (1, 1, 1), BF, False),
    ("P38", "Hat", (-7, 21, -4), (15, 1, 13), VP, True),
    ("P39", "Hat", (-3, 22, -1), (7, 3, 7), VP, False),
    ("P40", "Hat", (-3, 22, -1), (7, 2, 7), OR, False),
    ("P41", "Hat", (-1, 22, -2), (3, 3, 1), OR, False),
    ("P42", "Hat", (0, 23, -2), (1, 1, 1), VF, False),
    ("P43", "Hat", (-2, 25, 0), (5, 2, 5), VP, False),
    ("P44", "Hat", (-1, 27, 1), (4, 2, 4), VP, False),
    ("P45", "Hat", (0, 29, 2), (3, 1, 3), VP, False),
    ("P46", "Hat", (1, 30, 3), (2, 1, 2), VP, False),
    ("P47a", "Hat", (2, 31, 4), (2, 1, 1), VP, False),
    ("P47b", "Hat", (4, 30, 4), (1, 2, 1), VP, False),
]
REMOVED = [(-7, 21, -4), (7, 21, -4), (-7, 21, 8), (7, 21, 8)]     # rounded brim corners (§9.1)
NOT_VARIED = {"Staff", "glow_orb", "Right_Leg", "Left_Leg"}

# bone pivots, specification coordinates (X, Y, Z)
BONES = {
    "Root": ((0, 0, 2), None),
    "Body": ((0, 2, 2), "Root"),
    "Head": ((0, 15, 2.5), "Body"),
    "Beard": ((0, 15, -1), "Head"),
    "Hat": ((0, 21, 2.5), "Head"),
    "Right_Arm": ((-6, 14, 2), "Body"),
    "Left_Arm": ((6, 14, 2), "Body"),
    "Staff": ((-7.5, 10, -0.5), "Right_Arm"),
    "glow_orb": ((-7.5, 17.5, -0.5), "Staff"),
    "Right_Leg": ((-2, 2, 1), "Root"),
    "Left_Leg": ((2, 2, 1), "Root"),
}
LABEL = {"Body": "robe", "Head": "tete", "Beard": "barbe", "Hat": "chapeau", "Right_Arm": "bras_droit",
         "Left_Arm": "bras_gauche", "Staff": "baton", "glow_orb": "orbe", "Right_Leg": "pied_droit",
         "Left_Leg": "pied_gauche"}


# --------------------------------------------------------------------------- voxels
def voxels():
    """{(x, y, z) Blockbench voxel index: [colour, bone, down face colour or None]}."""
    grid = {}
    for _, bone, (X, Y, Z), (L, H, D), col, down_vf in BLOCKS:
        for i in range(X, X + L):
            for j in range(Y, Y + H):
                for k in range(Z, Z + D):
                    key = (-i, j, k)
                    if col is None:                                 # face rule only (under the shoulders)
                        grid[key][2] = VF
                        continue
                    grid[key] = [col, bone, VF if down_vf else None]
    for X, Y, Z in REMOVED:
        grid.pop((-X, Y, Z), None)
    rnd = random.Random(VM_SEED)
    for key in sorted(grid):
        v = grid[key]
        if v[0] == VP and v[1] not in NOT_VARIED and rnd.random() < VM_SHARE:
            v[0] = VM
    return grid


def boxes(grid):
    """Greedy merge of the voxels of each bone into boxes: [(bone, min index, size)]."""
    used, out = set(), []
    for key in sorted(grid, key=lambda k: (k[1], k[2], k[0])):
        if key in used:
            continue
        bone = grid[key][1]

        def free(p):
            return p in grid and p not in used and grid[p][1] == bone
        x, y, z = key
        sx = 1
        while free((x + sx, y, z)):
            sx += 1
        sz = 1
        while all(free((x + i, y, z + sz)) for i in range(sx)):
            sz += 1
        sy = 1
        while all(free((x + i, y + sy, z + k)) for i in range(sx) for k in range(sz)):
            sy += 1
        for i in range(sx):
            for j in range(sy):
                for k in range(sz):
                    used.add((x + i, y + j, z + k))
        out.append((bone, (x, y, z), (sx, sy, sz)))
    return out


def face_painter(grid, lo, size):
    """Texture of each face of a box, one texel per voxel, in Blockbench's face orientation."""
    (x0, y0, z0), (sx, sy, sz) = lo, size
    x1, y1, z1 = x0 + sx - 1, y0 + sy - 1, z0 + sz - 1

    def paint(w, h, r, face):
        def at(u, v):
            if face == "north":
                p = (x1 - u, y1 - v, z0)
            elif face == "south":
                p = (x0 + u, y1 - v, z1)
            elif face == "east":
                p = (x1, y1 - v, z1 - u)
            elif face == "west":
                p = (x0, y1 - v, z0 + u)
            elif face == "up":
                p = (x0 + u, y1, z0 + v)
            else:
                p = (x0 + u, y0, z1 - v)
            col, _, down = grid[p]
            return down if face == "down" and down else col
        return [[at(u, v) for u in range(w)] for v in range(h)]
    return paint


def parts(grid):
    """bbkit pieces: (bone, name, centre, size, rotation, material), in voxel units."""
    P, mats, glow, count = [], {}, {}, {}
    for bone, (x, y, z), (sx, sy, sz) in boxes(grid):
        count[bone] = count.get(bone, 0) + 1
        mat = f"m{len(P)}"
        (glow if bone.startswith("glow_") else mats)[mat] = face_painter(grid, (x, y, z), (sx, sy, sz))
        centre = (x - 0.5 + sx / 2, y + sy / 2, z + Z_SHIFT + sz / 2)
        P.append((bone, f"{LABEL[bone]}_{count[bone]}", centre, (sx, sy, sz), (0, 0, 0), mat))
    return P, mats, glow


def bones():
    return {name: ((-X, Y, Z + Z_SHIFT), parent) for name, ((X, Y, Z), parent) in BONES.items()}


if __name__ == "__main__":
    args = sys.argv[1:]
    prev = None
    if "--apercu" in args:
        i = args.index("--apercu")
        prev = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else HERE
    grid = voxels()
    P0, mats, glow = parts(grid)
    _, _, raw = ground(P0, bones(), center=False)
    P, groups, dims = ground(P0, bones(), scale=HEIGHT / raw[1], center=False)   # origin between the feet
    uv, atlases, sizes = bake(P, mats, glow)
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    print(f"{len(grid)} voxels in {len(P)} boxes ; {raw[0] * 16:.0f} x {raw[1] * 16:.0f} x {raw[2] * 16:.0f} voxels ; "
          f"{dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocks ; textures {sizes[0]}, {sizes[1]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("written", path)
    if prev:
        # face, right profile (seen from the character's right), back, left profile, 3/4 as in §13.1
        apercu(data, atlases, sizes, prev, {VC}, views=((0, 4), (90, 4), (180, 4), (-90, 4), (-30, 10)), size=400)
