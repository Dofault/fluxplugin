#!/usr/bin/env python3
"""Drill Mole boss ("Taupe foreuse"): generates the Blockbench model from scratch.

Outputs (next to this script unless --out is given):
  taupe_foreuse.bbmodel   generic (free) Blockbench model, texture embedded
  taupe_foreuse.png       128x128 painted texture
  preview_*.png           software renders used to check the model

Conventions: 1 unit = 1 pixel (16 = 1 block), Y up, the drill points to -Z
(north). Bone rotations are euler degrees in ZYX order (Blockbench internal).
Element rotations stay single axis and in {-45, -22.5, 0, 22.5, 45}, so the
model is safe for item-display based engines (BetterModel).
"""
import argparse
import base64
import io
import json
import math
import os
import random
import uuid

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = 128
TILE = 32
RNG = random.Random(26)


def uid():
    return str(uuid.UUID(int=RNG.getrandbits(128), version=4))


# --------------------------------------------------------------------------
# Texture: one 32x32 painted tile per material
# --------------------------------------------------------------------------
MATERIALS = [
    "fur", "fur_light", "belly", "skin",
    "steel", "steel_dark", "plate", "brass",
    "drill", "drill_hot", "claw", "lens",
    "rubber", "crystal", "hot", "dirt",
]
EMISSIVE = {"lens", "crystal", "hot", "drill_hot"}


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def paint_tile(mat, r):
    t = np.zeros((TILE, TILE, 3))
    noise = lambda amp: (np.array([[r.uniform(-amp, amp) for _ in range(TILE)] for _ in range(TILE)]))[..., None]
    if mat in ("fur", "fur_light", "belly"):
        base = {"fur": "#4a3a33", "fur_light": "#67524a", "belly": "#8b6f5e"}[mat]
        t[:] = hexc(base)
        t += noise(6)
        # hair strokes: short vertical streaks, dark and light
        for _ in range(70):
            x, y = r.randrange(TILE), r.randrange(TILE)
            ln = r.randint(2, 5)
            c = r.choice([-18, -12, 14, 20])
            for k in range(ln):
                t[(y + k) % TILE, x] += c
    elif mat == "skin":
        t[:] = hexc("#e39a9a")
        t += noise(7)
        for _ in range(14):  # blotches
            x, y = r.randrange(TILE), r.randrange(TILE)
            t[y:y + 2, x:x + 2] = hexc("#c97f84")
        for y in range(3, TILE, 6):  # wrinkles
            t[y, :] -= 10
    elif mat in ("steel", "steel_dark"):
        t[:] = hexc("#9aa1ab" if mat == "steel" else "#4b5059")
        t += noise(5)
        for y in range(TILE):  # brushed
            t[y, :] += r.uniform(-6, 6)
        t[0, :] += 25
        t[-1, :] -= 25
    elif mat == "plate":
        t[:] = hexc("#7d848f")
        t += noise(5)
        t[0, :] = hexc("#bfc5ce")
        t[:, 0] = hexc("#aab0b9")
        t[-1, :] = hexc("#3e434b")
        t[:, -1] = hexc("#3e434b")
        for (x, y) in [(2, 2), (TILE - 4, 2), (2, TILE - 4), (TILE - 4, TILE - 4),
                       (TILE // 2 - 1, 2), (TILE // 2 - 1, TILE - 4), (2, TILE // 2 - 1), (TILE - 4, TILE // 2 - 1)]:
            t[y:y + 2, x:x + 2] = hexc("#c4923a")
            t[y, x] = hexc("#f0c870")
        for _ in range(10):  # scratches
            x, y = r.randrange(3, TILE - 6), r.randrange(3, TILE - 3)
            t[y, x:x + r.randint(2, 5)] += 30
    elif mat == "brass":
        t[:] = hexc("#c4923a")
        t += noise(10)
        t[0, :] = hexc("#e6b85c")
        t[-1, :] = hexc("#8f6624")
        for _ in range(12):
            x, y = r.randrange(TILE), r.randrange(TILE)
            t[y, x] = hexc("#8f6624")
    elif mat in ("drill", "drill_hot"):
        a, b, hl = (("#bcc3cc", "#6b727c", "#eef2f6") if mat == "drill"
                    else ("#ff8a2a", "#c4461c", "#ffd94d"))
        for y in range(TILE):
            for x in range(TILE):
                band = ((x + y) // 4) % 2
                t[y, x] = hexc(a if band == 0 else b)
                if (x + y) % 8 == 0:
                    t[y, x] = hexc(hl)
        t += noise(4)
    elif mat == "claw":
        for y in range(TILE):
            t[y, :] = hexc("#efe4c8") * (1 - y / TILE) + hexc("#bfae86") * (y / TILE)
        t += noise(5)
    elif mat == "lens":
        cy = cx = TILE / 2
        for y in range(TILE):
            for x in range(TILE):
                d = math.hypot(x - cx + 0.5, y - cy + 0.5) / (TILE / 2)
                t[y, x] = hexc("#ffe28a") * (1 - d) + hexc("#ff9a1a") * d
        t[4:7, 5:9] = hexc("#ffffff")
    elif mat == "rubber":
        t[:] = hexc("#2a2a2e")
        t += noise(3)
        for y in range(0, TILE, 3):
            t[y, :] = hexc("#3c3c43")
    elif mat == "crystal":
        for y in range(TILE):
            for x in range(TILE):
                f = ((x // 5) + (y // 7)) % 3
                t[y, x] = hexc(["#B376E5", "#5B2D9E", "#E060A0"][f])
        t += noise(6)
        for _ in range(8):
            x, y = r.randrange(TILE), r.randrange(TILE)
            t[y, x] = hexc("#FFD94D")
    elif mat == "hot":
        cy = cx = TILE / 2
        for y in range(TILE):
            for x in range(TILE):
                d = min(1, math.hypot(x - cx, y - cy) / (TILE / 2))
                t[y, x] = hexc("#ffd94d") * (1 - d) + hexc("#ff4a14") * d
    elif mat == "dirt":
        t[:] = hexc("#6b4a2b")
        t += noise(12)
        for _ in range(40):
            x, y = r.randrange(TILE - 1), r.randrange(TILE - 1)
            t[y:y + 2, x:x + 2] = hexc(r.choice(["#4f3520", "#8a8a80", "#7d5a36"]))
    return np.clip(t, 0, 255).astype(np.uint8)


def build_texture():
    img = np.zeros((TEX, TEX, 4), dtype=np.uint8)
    r = random.Random(11)
    for i, m in enumerate(MATERIALS):
        tx, ty = (i % 4) * TILE, (i // 4) * TILE
        img[ty:ty + TILE, tx:tx + TILE, :3] = paint_tile(m, r)
        img[ty:ty + TILE, tx:tx + TILE, 3] = 255
    return img


def tile_origin(mat):
    i = MATERIALS.index(mat)
    return (i % 4) * TILE, (i // 4) * TILE


# --------------------------------------------------------------------------
# Model building
# --------------------------------------------------------------------------
class Bone:
    def __init__(self, name, pivot, parent=None, rot=None):
        self.name, self.pivot, self.parent = name, list(pivot), parent
        self.rot = [float(v) for v in (rot or [0, 0, 0])]
        self.uuid = uid()
        self.children = []  # Bone or element uuid
        if parent:
            parent.children.append(self)


ELEMENTS = []   # bbmodel element dicts
ELEM_BONE = {}  # element uuid -> Bone
ELEM_MATS = {}  # element uuid -> {face: material}
BONES = {}

FACE_DIMS = {"north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1), "up": (0, 2), "down": (0, 2)}


def cube(bone, name, frm, to, mat, rot=None, origin=None, faces=None):
    frm, to = [float(v) for v in frm], [float(v) for v in to]
    size = [to[i] - frm[i] for i in range(3)]
    assert all(s > 0 for s in size), (name, frm, to)
    if origin is None:
        origin = [(frm[i] + to[i]) / 2 for i in range(3)]
    mats = {f: (faces or {}).get(f, mat) for f in FACE_DIMS}
    face_json = {}
    for f, (a, b) in FACE_DIMS.items():
        w, h = size[a], size[b]
        uw, vh = min(w, TILE), min(h, TILE)
        tx, ty = tile_origin(mats[f])
        ox = RNG.randint(0, int(TILE - uw)) if uw < TILE else 0
        oy = RNG.randint(0, int(TILE - vh)) if vh < TILE else 0
        face_json[f] = {"uv": [tx + ox, ty + oy, tx + ox + uw, ty + oy + vh], "texture": 0}
    e = {
        "name": name, "box_uv": False, "rescale": False, "locked": False,
        "render_order": "default", "allow_mirror_modeling": True,
        "from": frm, "to": to, "autouv": 0, "color": MATERIALS.index(mat) % 8,
        "origin": [float(v) for v in origin], "rotation": [float(v) for v in (rot or [0, 0, 0])],
        "faces": face_json, "type": "cube", "uuid": uid(),
    }
    assert sum(1 for v in e["rotation"] if v) <= 1 and all(v in (-45, -22.5, 0, 22.5, 45) for v in e["rotation"]), name
    ELEMENTS.append(e)
    ELEM_BONE[e["uuid"]] = bone
    ELEM_MATS[e["uuid"]] = mats
    bone.children.append(e["uuid"])
    return e


def locator(bone, name, pos):
    e = {"name": name, "position": [float(v) for v in pos], "rotation": [0, 0, 0],
         "ignore_inherited_scale": False, "uuid": uid(), "type": "locator"}
    ELEMENTS.append(e)
    ELEM_BONE[e["uuid"]] = bone
    bone.children.append(e["uuid"])


def mirror_x(frm, to):
    return [-to[0], frm[1], frm[2]], [-frm[0], to[1], to[2]]


def cube_pair(bone_l, bone_r, name, frm, to, mat, rot=None, origin=None, faces=None):
    """Left (+X) element and its mirror on the right (-X)."""
    cube(bone_l, name + "_l", frm, to, mat, rot, origin, faces)
    f2, t2 = mirror_x(frm, to)
    rot2 = None if rot is None else [rot[0], -rot[1], -rot[2]]
    o2 = None if origin is None else [-origin[0], origin[1], origin[2]]
    if faces:
        faces = {({"east": "west", "west": "east"}).get(k, k): v for k, v in faces.items()}
    cube(bone_r, name + "_r", f2, t2, mat, rot2, o2, faces)


def build_model():
    root = Bone("root", [0, 0, 0])
    body = Bone("body", [0, 12, 0], root)
    head = Bone("head", [0, 15, -19], body)
    drill = Bone("drill", [0, 14.5, -37], head)
    arm_l = Bone("arm_l", [13, 13, -13], body)
    paw_l = Bone("paw_l", [18, 8, -18], arm_l, rot=[0, -30, 0])
    arm_r = Bone("arm_r", [-13, 13, -13], body)
    paw_r = Bone("paw_r", [-18, 8, -18], arm_r, rot=[0, 30, 0])
    leg_l = Bone("leg_l", [13, 9, 14], body)
    leg_r = Bone("leg_r", [-13, 9, 14], body)
    tail = Bone("tail", [0, 13, 24], body)
    EY = 32.0
    engine = Bone("engine", [0, EY, 5], body)
    exh_l = Bone("exhaust_l", [4.5, EY + 5.5, 9.5], engine)
    exh_r = Bone("exhaust_r", [-4.5, EY + 5.5, 9.5], engine)
    for b in (root, body, head, drill, arm_l, paw_l, arm_r, paw_r, leg_l, leg_r, tail, engine, exh_l, exh_r):
        BONES[b.name] = b

    # ---- torso: teardrop profile, each slice a rounded section of three boxes
    #       (z0,  z1, half width, bottom, top)
    slices = [(-20, -16, 11, 8, 25), (-16, -10, 14, 6, 29), (-10, -2, 16, 5, 31), (-2, 8, 17, 5, 32),
              (8, 14, 16, 5, 31), (14, 19, 14, 6, 28), (19, 22, 11, 8, 24), (22, 24, 7, 11, 19)]
    for i, (z0, z1, hw, bot, top) in enumerate(slices):
        top_mat = "fur_light" if 1 <= i <= 5 else "fur"
        cube(body, f"torso_{i}_tall", [-hw + 3, bot, z0], [hw - 3, top, z1], "fur",
             faces={"down": "belly", "up": top_mat})
        cube(body, f"torso_{i}_mid", [-hw + 1.5, bot + 1.5, z0], [hw - 1.5, top - 1.5, z1], "fur",
             faces={"down": "belly", "up": top_mat})
        cube(body, f"torso_{i}_wide", [-hw, bot + 3, z0], [hw, top - 3, z1], "fur", faces={"down": "belly"})
    for i, (x, z, s) in enumerate([(-8, -8, 2), (5, -6, 3), (-3, 3, 2), (9, 6, 2), (-10, 10, 3), (2, 15, 2), (-6, 16, 2)]):
        top = max(t for (z0, z1, hw, b, t) in slices if z0 <= z < z1)
        cube(body, f"tuft_{i}", [x, top, z], [x + s, top + 1, z + 2], "fur_light")
    for i, (x, y, z) in enumerate([(16.5, 9, 10), (-17.5, 10, -4), (14.5, 7, -13), (-15.5, 22, 12), (-9, 30.5, -7)]):
        cube(body, f"dirt_clod_{i}", [x, y, z], [x + 1.5, y + 2, z + 3], "dirt")

    # ---- armour and harness
    cube(body, "plate_shoulder", [-12.5, 31, -9.5], [12.5, 32.3, -2.5], "plate")
    cube(body, "plate_ridge", [-4, 29, -15], [4, 30.3, -10.5], "plate")
    cube_pair(body, body, "plate_flank", [17, 11, -1], [17.8, 25, 7], "plate")
    cube_pair(body, body, "harness_side", [16, 9, 8.5], [16.4, 27, 10.5], "rubber")
    cube(body, "harness_top", [-13.4, 31, 8.5], [13.4, 31.4, 10.5], "rubber")
    cube(body, "harness_belly", [-13.4, 4.6, 8.5], [13.4, 5, 10.5], "rubber")

    # ---- engine on the back: block, fins, brass tanks, violet power crystal
    cube(engine, "engine_block", [-7, EY, -1], [7, EY + 5.5, 11], "steel_dark")
    cube(engine, "engine_head", [-6, EY + 5.5, 0], [6, EY + 7.5, 10], "steel")
    for k in range(4):
        cube(engine, f"engine_fin_{k}", [-6.5, EY + 7.5, 1 + k * 2.5], [6.5, EY + 8.5, 2 + k * 2.5], "steel_dark")
    cube(engine, "crystal_frame", [-4, EY + 0.5, -1.8], [4, EY + 5, -1], "brass")
    cube(engine, "crystal", [-2.5, EY + 1.5, -2.4], [2.5, EY + 4, -1.4], "crystal")
    cube_pair(engine, engine, "tank", [7, EY + 0.5, 0], [10, EY + 3.5, 10], "brass")
    cube_pair(engine, engine, "tank_cap", [7.5, EY + 1, -0.7], [9.5, EY + 3, 0], "steel")
    for b, x in ((exh_l, 4.5), (exh_r, -4.5)):
        side = "l" if x > 0 else "r"
        cube(b, f"exhaust_pipe_{side}", [x - 1, EY + 5.5, 8.5], [x + 1, EY + 13.5, 10.5], "steel_dark")
        cube(b, f"exhaust_rim_{side}", [x - 1.5, EY + 13.5, 8], [x + 1.5, EY + 14.5, 11], "steel", faces={"up": "hot"})
        locator(b, f"exhaust_{side}_top", [x, EY + 15, 9.5])

    # ---- head: distinct pointed head below the shoulder line, pink snout, goggles
    cube(head, "skull", [-9, 9, -28], [9, 23, -18], "fur", faces={"down": "belly"})
    cube(head, "skull_round", [-10, 11, -27], [10, 21, -19], "fur", faces={"down": "belly"})
    cube(head, "brow", [-7, 23, -26], [7, 24.5, -19], "fur_light")
    cube(head, "snout", [-6, 10, -35], [6, 19, -28], "skin")
    cube(head, "snout_top", [-4.5, 19, -34], [4.5, 20.5, -28], "skin")
    cube(head, "chin", [-5, 8.5, -33], [5, 10, -27], "skin")
    cube_pair(head, head, "tooth", [0.4, 6, -33.5], [2.4, 10, -31.5], "claw")
    for i, a in enumerate((-22.5, 0, 22.5)):
        y = 12 + i * 2.5
        cube_pair(head, head, f"whisker_{i}", [6, y, -31], [13, y + 0.3, -30.7], "claw",
                  rot=[0, 0, a], origin=[6, y, -30.85])
    cube(head, "goggle_strap", [-10.3, 20, -26], [10.3, 21.5, -20], "rubber")
    cube_pair(head, head, "goggle_frame", [2.5, 21, -27.5], [8.5, 26, -22.5], "brass")
    cube_pair(head, head, "goggle_lens", [3.5, 22, -28], [7.5, 25, -27.4], "lens")
    cube(head, "hose", [-1.2, 20.5, -36], [1.2, 22.5, -26], "rubber")
    cube(head, "hose_ring", [-1.5, 20.2, -31], [1.5, 22.8, -30], "brass")

    # ---- drill collar (fixed) and spinning drill
    cube(head, "collar", [-7.5, 7, -37], [7.5, 22, -35], "plate")
    cube(head, "collar_gear", [-7, 7.5, -35], [7, 21.5, -34.5], "brass")
    for i, (x, y) in enumerate([(5.5, 7.5), (-7, 7.5), (5.5, 20), (-7, 20)]):
        cube(head, f"collar_bolt_{i}", [x, y, -37.6], [x + 1.5, y + 1.5, -37], "brass")

    cx, cy = 0.0, 14.5
    sizes = [12, 10.5, 9, 7.5, 6, 4.5, 3]
    seg_len = 4.0
    twist = [0, 22.5, 45, -22.5, 0, 22.5, 45]
    z_base = -37
    for i, s in enumerate(sizes):
        z1 = z_base - seg_len * i
        z0 = z1 - seg_len
        mat = "drill_hot" if i >= 5 else "drill"
        cube(drill, f"drill_seg_{i}", [cx - s / 2, cy - s / 2, z0], [cx + s / 2, cy + s / 2, z1], mat,
             rot=[0, 0, twist[i]], origin=[cx, cy, (z0 + z1) / 2])
        # helical flutes: a blade pair whose angle advances 22.5 deg per segment
        phi = 45 + 22.5 * i
        base = min((0, 90), key=lambda b0: abs(((phi - b0 + 90) % 180) - 90))
        r = ((phi - base + 90) % 180) - 90
        h = s / 2
        for sign in (1, -1):
            lo_, hi_ = (h - 0.5, h + 1.5) if sign > 0 else (-h - 1.5, -h + 0.5)
            if base == 90:
                f, t = [cx - 0.6, cy + lo_, z0], [cx + 0.6, cy + hi_, z1]
            else:
                f, t = [cx + lo_, cy - 0.6, z0], [cx + hi_, cy + 0.6, z1]
            if i < 6:
                cube(drill, f"drill_flute_{i}_{'a' if sign > 0 else 'b'}", f, t, "steel" if i < 5 else "drill_hot",
                     rot=[0, 0, r], origin=[cx, cy, (z0 + z1) / 2])
    tip_z = z_base - seg_len * len(sizes)
    cube(drill, "drill_point", [cx - 0.75, cy - 0.75, tip_z - 2.5], [cx + 0.75, cy + 0.75, tip_z], "drill_hot")
    locator(drill, "drill_tip", [cx, cy, tip_z - 2.5])

    # ---- front digging arms: wide shovel paws turned outward, steel-capped claws
    cube_pair(arm_l, arm_r, "upper_arm", [12, 6, -17], [18, 15, -9], "fur", faces={"down": "belly"})
    cube_pair(paw_l, paw_r, "wrist_cuff", [15, 8, -21], [20, 15, -16], "fur")
    cube_pair(paw_l, paw_r, "palm", [15, 0.5, -24], [27, 13, -20], "skin")
    cube_pair(paw_l, paw_r, "palm_edge", [16, 13, -23.5], [26, 14.5, -20.5], "skin")
    cube_pair(paw_l, paw_r, "bracer", [16, 6, -20], [26, 12, -19.2], "plate")
    for k, cxk in enumerate((16.5, 19.3, 22.1, 24.9)):
        o = [cxk, 3.25, -24]
        cube_pair(paw_l, paw_r, f"claw_{k}", [cxk - 1.1, 2, -28.5], [cxk + 1.1, 4.5, -24], "claw", rot=[-22.5, 0, 0], origin=o)
        cube_pair(paw_l, paw_r, f"claw_tip_{k}", [cxk - 0.8, 2.25, -32.5], [cxk + 0.8, 4, -28.5], "steel", rot=[-22.5, 0, 0], origin=o)
    locator(paw_l, "paw_l_tip", [20.5, 0.5, -32])
    locator(paw_r, "paw_r_tip", [-20.5, 0.5, -32])

    # ---- hind legs
    cube_pair(leg_l, leg_r, "thigh", [11, 3, 10], [17, 12, 18], "fur", faces={"down": "belly"})
    cube_pair(leg_l, leg_r, "foot", [11.5, 0, 8], [16.5, 3, 17], "skin")
    for k, cxk in enumerate((12.5, 14, 15.5)):
        cube_pair(leg_l, leg_r, f"toe_{k}", [cxk - 0.5, 0, 5.5], [cxk + 0.5, 1.5, 8], "claw")

    # ---- tail
    cube(tail, "tail", [-1.5, 11.5, 24], [1.5, 14.5, 32], "skin", rot=[-22.5, 0, 0], origin=[0, 13, 24])
    cube(tail, "tail_tip", [-1, 12, 31.5], [1, 14, 35], "skin", rot=[-22.5, 0, 0], origin=[0, 13, 24])

    locator(root, "ground_front", [0, 0, -40])
    return root


# --------------------------------------------------------------------------
# Animations
# --------------------------------------------------------------------------
ANIMS = []  # (name, length, loop, {bone: {channel: [(t, (x,y,z))]}}, markers)


class Anim:
    def __init__(self, name, length, loop):
        self.name, self.length, self.loop = name, length, loop
        self.tracks = {}
        self.markers = []
        ANIMS.append(self)

    def key(self, bone, channel, keys):
        tr = self.tracks.setdefault(bone, {}).setdefault(channel, {})
        for t, v in keys:
            tr[round(t, 4)] = tuple(float(x) for x in v)
        return self

    def spin(self, bone, segments):
        """segments: list of (t0, t1, deg0, deg1); split so no step exceeds 90 deg."""
        for t0, t1, d0, d1 in segments:
            n = max(1, math.ceil(abs(d1 - d0) / 90))
            self.key(bone, "rotation", [(t0 + (t1 - t0) * k / n, (0, 0, d0 + (d1 - d0) * k / n)) for k in range(n + 1)])
        return self

    def mark(self, t):
        self.markers.append(t)
        return self


def build_anims():
    # idle: breathing, slow drill, sniffing head, puffing exhausts
    a = Anim("idle", 2.0, "loop")
    a.key("body", "position", [(0, (0, 0, 0)), (1, (0, 0.4, 0)), (2, (0, 0, 0))])
    a.key("body", "scale", [(0, (1, 1, 1)), (1, (1.02, 1.03, 1)), (2, (1, 1, 1))])
    a.key("head", "rotation", [(0, (0, 0, 0)), (0.5, (3, 2, 0)), (1, (0, 0, 0)), (1.5, (3, -2, 0)), (2, (0, 0, 0))])
    a.spin("drill", [(0, 2, 0, 360)])
    a.key("tail", "rotation", [(0, (0, -10, 0)), (1, (0, 10, 0)), (2, (0, -10, 0))])
    a.key("exhaust_l", "scale", [(0, (1, 1, 1)), (0.5, (1.15, 1.1, 1.15)), (1, (1, 1, 1)), (2, (1, 1, 1))])
    a.key("exhaust_r", "scale", [(0, (1, 1, 1)), (1, (1, 1, 1)), (1.5, (1.15, 1.1, 1.15)), (2, (1, 1, 1))])

    # walk: swimming dig stroke of the paws, body roll and bounce
    a = Anim("walk", 1.0, "loop")
    a.key("arm_l", "rotation", [(0, (20, 0, 0)), (0.5, (-20, 0, 0)), (1, (20, 0, 0))])
    a.key("arm_r", "rotation", [(0, (-20, 0, 0)), (0.5, (20, 0, 0)), (1, (-20, 0, 0))])
    a.key("paw_l", "rotation", [(0, (-10, 0, 0)), (0.5, (15, 0, 0)), (1, (-10, 0, 0))])
    a.key("paw_r", "rotation", [(0, (15, 0, 0)), (0.5, (-10, 0, 0)), (1, (15, 0, 0))])
    a.key("leg_l", "rotation", [(0, (-18, 0, 0)), (0.5, (18, 0, 0)), (1, (-18, 0, 0))])
    a.key("leg_r", "rotation", [(0, (18, 0, 0)), (0.5, (-18, 0, 0)), (1, (18, 0, 0))])
    a.key("body", "rotation", [(0, (0, 0, -3)), (0.5, (0, 0, 3)), (1, (0, 0, -3))])
    a.key("body", "position", [(0, (0, 0, 0)), (0.25, (0, 0.8, 0)), (0.5, (0, 0, 0)), (0.75, (0, 0.8, 0)), (1, (0, 0, 0))])
    a.key("head", "rotation", [(0, (0, 4, 0)), (0.5, (0, -4, 0)), (1, (0, 4, 0))])
    a.spin("drill", [(0, 1, 0, 360)])
    a.key("tail", "rotation", [(0, (0, 15, 0)), (0.5, (0, -15, 0)), (1, (0, 15, 0))])

    # attack_drill: rear back, drill revs up, lunge (impact 0.8 s = tick 16), recover
    a = Anim("attack_drill", 1.6, "once")
    a.key("body", "position", [(0, (0, 0, 0)), (0.6, (0, 1, 6)), (0.8, (0, 0, -14)), (1.1, (0, 0, -12)), (1.6, (0, 0, 0))])
    a.key("body", "rotation", [(0, (0, 0, 0)), (0.6, (8, 0, 0)), (0.8, (-6, 0, 0)), (1.1, (-4, 0, 0)), (1.6, (0, 0, 0))])
    a.key("head", "rotation", [(0, (0, 0, 0)), (0.6, (10, 0, 0)), (0.8, (-8, 0, 0)), (1.6, (0, 0, 0))])
    a.key("arm_l", "rotation", [(0, (0, 0, 0)), (0.6, (-25, 0, 10)), (0.8, (30, 0, 0)), (1.6, (0, 0, 0))])
    a.key("arm_r", "rotation", [(0, (0, 0, 0)), (0.6, (-25, 0, -10)), (0.8, (30, 0, 0)), (1.6, (0, 0, 0))])
    a.spin("drill", [(0, 0.6, 0, 360), (0.6, 1.1, 360, 1800), (1.1, 1.6, 1800, 2160)])
    a.key("exhaust_l", "scale", [(0, (1, 1, 1)), (0.6, (1.3, 1.2, 1.3)), (0.8, (1, 1, 1)), (1.6, (1, 1, 1))])
    a.key("exhaust_r", "scale", [(0, (1, 1, 1)), (0.6, (1.3, 1.2, 1.3)), (0.8, (1, 1, 1)), (1.6, (1, 1, 1))])
    a.mark(0.6).mark(0.8)

    # attack_claw: left swipe (impact 0.5 s = t10), right swipe (impact 0.9 s = t18)
    a = Anim("attack_claw", 1.3, "once")
    a.key("arm_l", "rotation", [(0, (0, 0, 0)), (0.35, (-20, -10, 40)), (0.5, (25, 30, -5)), (0.7, (0, 0, 0)), (1.3, (0, 0, 0))])
    a.key("arm_r", "rotation", [(0, (0, 0, 0)), (0.4, (0, 0, 0)), (0.75, (-20, 10, -40)), (0.9, (25, -30, 5)), (1.1, (0, 0, 0)), (1.3, (0, 0, 0))])
    a.key("body", "rotation", [(0, (0, 0, 0)), (0.35, (0, 10, 4)), (0.5, (0, -12, -3)), (0.75, (0, -10, -4)), (0.9, (0, 12, 3)), (1.3, (0, 0, 0))])
    a.key("head", "rotation", [(0, (0, 0, 0)), (0.5, (0, -8, 0)), (0.9, (0, 8, 0)), (1.3, (0, 0, 0))])
    a.spin("drill", [(0, 1.3, 0, 360)])
    a.mark(0.5).mark(0.9)

    # burrow: nose-dives into the ground; ends hidden (hold)
    a = Anim("burrow", 1.5, "hold")
    a.key("body", "rotation", [(0, (0, 0, 0)), (0.3, (6, 0, 0)), (0.7, (-28, 0, 0)), (1.5, (-35, 0, 0))])
    a.key("root", "position", [(0, (0, 0, 0)), (0.5, (0, 0, 0)), (1.5, (0, -46, -10))])
    a.key("arm_l", "rotation", [(0, (0, 0, 0)), (0.5, (-30, 0, 0)), (0.75, (25, 0, 0)), (1.0, (-30, 0, 0)), (1.25, (25, 0, 0)), (1.5, (0, 0, 0))])
    a.key("arm_r", "rotation", [(0, (0, 0, 0)), (0.5, (25, 0, 0)), (0.75, (-30, 0, 0)), (1.0, (25, 0, 0)), (1.25, (-30, 0, 0)), (1.5, (0, 0, 0))])
    a.key("tail", "rotation", [(0, (0, 0, 0)), (1.0, (-30, 0, 0)), (1.5, (-30, 0, 0))])
    a.spin("drill", [(0, 0.5, 0, 360), (0.5, 1.5, 360, 2520)])
    a.mark(0.5)

    # emerge: bursts out nose first (surface broken at 0.5 s = t10), lands at 1.1 s = t22
    a = Anim("emerge", 1.5, "once")
    a.key("root", "position", [(0, (0, -46, 0)), (0.5, (0, -18, -4)), (0.85, (0, 6, -6)), (1.1, (0, 0, -6)), (1.5, (0, 0, 0))])
    a.key("body", "rotation", [(0, (35, 0, 0)), (0.5, (40, 0, 0)), (0.85, (15, 0, 0)), (1.1, (-6, 0, 0)), (1.5, (0, 0, 0))])
    a.key("arm_l", "rotation", [(0, (-40, 0, 20)), (0.85, (-40, 0, 20)), (1.1, (15, 0, 0)), (1.5, (0, 0, 0))])
    a.key("arm_r", "rotation", [(0, (-40, 0, -20)), (0.85, (-40, 0, -20)), (1.1, (15, 0, 0)), (1.5, (0, 0, 0))])
    a.spin("drill", [(0, 1.0, 0, 2160), (1.0, 1.5, 2160, 2340)])
    a.mark(0.5).mark(1.1)

    # rage (phase 2): rears up on its hind legs, flails, drill screams, exhausts pulse
    a = Anim("rage", 2.0, "once")
    a.key("body", "rotation", [(0, (0, 0, 0)), (0.5, (35, 0, 0)), (1.5, (35, 0, 0)), (1.8, (-5, 0, 0)), (2.0, (0, 0, 0))])
    a.key("body", "position", [(0, (0, 0, 0)), (0.5, (0, 4, 4)), (1.5, (0, 4, 4)), (1.8, (0, 0, 0))])
    a.key("arm_l", "rotation", [(0, (0, 0, 0)), (0.5, (-60, 0, 30)), (0.75, (-30, 0, 50)), (1.0, (-60, 0, 30)), (1.25, (-30, 0, 50)), (1.5, (-60, 0, 30)), (1.8, (10, 0, 0)), (2.0, (0, 0, 0))])
    a.key("arm_r", "rotation", [(0, (0, 0, 0)), (0.5, (-60, 0, -30)), (0.75, (-30, 0, -50)), (1.0, (-60, 0, -30)), (1.25, (-30, 0, -50)), (1.5, (-60, 0, -30)), (1.8, (10, 0, 0)), (2.0, (0, 0, 0))])
    a.key("head", "rotation", [(0, (0, 0, 0)), (0.5, (15, 0, 0)), (0.75, (15, 10, 0)), (1.0, (15, -10, 0)), (1.25, (15, 10, 0)), (1.5, (15, 0, 0)), (2.0, (0, 0, 0))])
    a.spin("drill", [(0, 0.5, 0, 720), (0.5, 1.5, 720, 4320), (1.5, 2.0, 4320, 4680)])
    for b in ("exhaust_l", "exhaust_r"):
        a.key(b, "scale", [(0, (1, 1, 1)), (0.6, (1.4, 1.3, 1.4)), (0.8, (1, 1, 1)), (1.0, (1.4, 1.3, 1.4)), (1.2, (1, 1, 1)), (1.4, (1.4, 1.3, 1.4)), (1.6, (1, 1, 1))])
    a.key("engine", "position", [(0, (0, 0, 0)), (0.6, (0, 0.5, 0)), (0.7, (0, -0.3, 0)), (0.8, (0, 0.5, 0)), (0.9, (0, -0.3, 0)), (1.0, (0, 0, 0))])
    a.mark(0.5).mark(1.8)

    # death: drill winds down, engine coughs, rolls onto its side (hold)
    a = Anim("death", 2.2, "hold")
    a.spin("drill", [(0, 0.4, 0, 270), (0.4, 0.9, 270, 450), (0.9, 1.4, 450, 500)])
    a.key("body", "rotation", [(0, (0, 0, 0)), (0.3, (6, 0, -5)), (1.2, (-4, 0, 80)), (1.4, (-4, 0, 74)), (1.6, (-4, 0, 78))])
    a.key("body", "position", [(0, (0, 0, 0)), (1.2, (-4, 3, 0)), (1.6, (-4, 3, 0))])
    a.key("head", "rotation", [(0, (0, 0, 0)), (0.3, (15, 0, 0)), (1.2, (-12, 0, 0))])
    a.key("arm_l", "rotation", [(0, (0, 0, 0)), (1.2, (-30, 0, 30)), (1.6, (-20, 0, 20)), (1.8, (-26, 0, 26)), (2.2, (-22, 0, 22))])
    a.key("arm_r", "rotation", [(0, (0, 0, 0)), (1.2, (30, 0, -10)), (2.2, (30, 0, -10))])
    a.key("leg_l", "rotation", [(0, (0, 0, 0)), (1.4, (-20, 0, 0)), (1.6, (5, 0, 0)), (1.8, (-15, 0, 0)), (2.2, (0, 0, 0))])
    a.key("tail", "rotation", [(0, (0, 0, 0)), (1.2, (20, 0, 0))])
    for b in ("exhaust_l", "exhaust_r"):
        a.key(b, "scale", [(0, (1, 1, 1)), (0.3, (1.5, 1.4, 1.5)), (0.5, (1, 1, 1)), (0.7, (1.3, 1.2, 1.3)), (0.9, (1, 1, 1))])
    a.mark(1.2)


def anim_json(a):
    animators = {}
    for bone, chans in a.tracks.items():
        b = BONES[bone]
        kfs = []
        for ch, keys in chans.items():
            for t in sorted(keys):
                x, y, z = keys[t]
                kfs.append({"channel": ch, "data_points": [{"x": x, "y": y, "z": z}],
                            "uuid": uid(), "time": t, "color": -1, "interpolation": "linear"})
        animators[b.uuid] = {"name": bone, "type": "bone", "keyframes": kfs}
    return {"uuid": uid(), "name": a.name, "loop": a.loop, "override": False, "length": a.length,
            "snapping": 20, "selected": False, "anim_time_update": "", "blend_weight": "",
            "start_delay": "", "loop_delay": "", "animators": animators,
            "markers": [{"color": 0, "time": t} for t in a.markers]}


def outliner(b):
    return {"name": b.name, "origin": b.pivot, "rotation": b.rot, "color": 0, "uuid": b.uuid,
            "export": True, "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True,
            "autouv": 0, "children": [outliner(c) if isinstance(c, Bone) else c for c in b.children]}


def write_bbmodel(root, tex_img, path):
    buf = io.BytesIO()
    Image.fromarray(tex_img, "RGBA").save(buf, "PNG")
    src = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    model = {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": "taupe_foreuse", "model_identifier": "", "visible_box": [4, 3.5, 1.5],
        "variable_placeholders": "", "variable_placeholder_buttons": [], "timeline_setups": [],
        "unhandled_root_fields": {},
        "resolution": {"width": TEX, "height": TEX},
        "elements": ELEMENTS,
        "outliner": [outliner(root)],
        "textures": [{
            "path": "", "name": "taupe_foreuse.png", "folder": "", "namespace": "", "id": "0",
            "group": "", "width": TEX, "height": TEX, "uv_width": TEX, "uv_height": TEX,
            "particle": False, "use_as_default": False, "layers_enabled": False, "sync_to_project": "",
            "render_mode": "default", "render_sides": "auto", "pbr_channel": "color",
            "frame_time": 1, "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
            "visible": True, "internal": True, "saved": False, "uuid": uid(), "relative_path": "",
            "source": src}],
        "animations": [anim_json(a) for a in ANIMS],
    }
    with open(path, "w") as f:
        json.dump(model, f, indent=1)


# --------------------------------------------------------------------------
# Software renderer (orthographic, z-buffered, textured) for previews
# --------------------------------------------------------------------------
def rot_matrix(rx, ry, rz):
    rx, ry, rz = (math.radians(v) for v in (rx, ry, rz))
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Z @ Y @ X


def affine(R=np.eye(3), t=(0, 0, 0)):
    M = np.eye(4)
    M[:3, :3] = R
    M[:3, 3] = t
    return M


def T(v):
    return affine(t=v)


def sample(track, t):
    ts = sorted(track)
    if t <= ts[0]:
        return np.array(track[ts[0]])
    if t >= ts[-1]:
        return np.array(track[ts[-1]])
    for a, b in zip(ts, ts[1:]):
        if a <= t <= b:
            k = (t - a) / (b - a)
            return np.array(track[a]) * (1 - k) + np.array(track[b]) * k


def bone_matrices(root, anim=None, t=0.0):
    mats = {}

    def walk(b, parent):
        tr = (anim.tracks.get(b.name, {}) if anim else {})
        rot = np.array(b.rot) + (sample(tr["rotation"], t) if "rotation" in tr else np.zeros(3))
        pos = sample(tr["position"], t) if "position" in tr else np.zeros(3)
        scl = sample(tr["scale"], t) if "scale" in tr else np.ones(3)
        p = np.array(b.pivot, float)
        M = parent @ T(p + pos) @ affine(rot_matrix(*rot) @ np.diag(scl)) @ T(-p)
        mats[b.name] = M
        for c in b.children:
            if isinstance(c, Bone):
                walk(c, M)

    walk(root, np.eye(4))
    return mats


FACE_CORNERS = {  # (top-left, top-right, bottom-left) as (x?, y?, z?) picks: 0 = from, 1 = to
    "north": ((1, 1, 0), (0, 1, 0), (1, 0, 0)),
    "south": ((0, 1, 1), (1, 1, 1), (0, 0, 1)),
    "east": ((1, 1, 1), (1, 1, 0), (1, 0, 1)),
    "west": ((0, 1, 0), (0, 1, 1), (0, 0, 0)),
    "up": ((0, 1, 0), (1, 1, 0), (0, 1, 1)),
    "down": ((0, 0, 1), (1, 0, 1), (0, 0, 0)),
}


def render(root, tex, yaw, pitch, anim=None, t=0.0, size=(520, 400), ss=2, bounds=None, ground=True):
    W, H = size[0] * ss, size[1] * ss
    mats = bone_matrices(root, anim, t)
    V = rot_matrix(pitch, 0, 0) @ rot_matrix(0, yaw, 0)
    light = np.array([-0.4, 0.8, -0.45])
    light /= np.linalg.norm(light)
    faces = []
    for e in ELEMENTS:
        if e["type"] != "cube":
            continue
        B = mats[ELEM_BONE[e["uuid"]].name]
        o = np.array(e["origin"])
        M = B @ T(o) @ affine(rot_matrix(*e["rotation"])) @ T(-o)
        lo, hi = np.array(e["from"]), np.array(e["to"])
        for f, picks in FACE_CORNERS.items():
            pts = []
            for pk in picks:
                p = np.array([hi[i] if pk[i] else lo[i] for i in range(3)])
                pts.append((M @ np.append(p, 1))[:3])
            A, Bp, C = pts
            n = np.cross(C - A, Bp - A)
            if np.linalg.norm(n) < 1e-9:
                continue
            n /= np.linalg.norm(n)
            faces.append((A, Bp, C, n, e["faces"][f]["uv"], ELEM_MATS[e["uuid"]][f]))
    proj = lambda p: V @ p
    allp = np.array([proj(p) for fc in faces for p in fc[:3]])
    if bounds is None:
        bounds = (allp[:, 0].min(), allp[:, 0].max(), allp[:, 1].min(), allp[:, 1].max())
    x0, x1, y0, y1 = bounds
    sc = min(W * 0.9 / (x1 - x0), H * 0.9 / (y1 - y0))
    cxs, cys = (x0 + x1) / 2, (y0 + y1) / 2
    to_screen = lambda v: np.array([W / 2 - (v[0] - cxs) * sc, H / 2 - (v[1] - cys) * sc, v[2]])

    img = np.zeros((H, W, 3))
    yy = np.linspace(0, 1, H)[:, None]
    img[:] = (np.array([30, 24, 44]) * (1 - yy) + np.array([14, 12, 20]) * yy)[:, None, :] if False else 0
    img[:] = np.array([30, 24, 44]) * (1 - yy[..., None]) + np.array([12, 10, 18]) * yy[..., None]
    zb = np.full((H, W), np.inf)

    if ground:  # floor grid at y = 0, one cell per block
        for gx in range(-48, 49, 16):
            for a, b in (((gx, 0, -80), (gx, 0, 48)), ((-48, 0, gx * 80 / 48 if False else gx), (48, 0, gx))):
                pass
        G = 4
        for gz in range(-80, 49, 16):
            for gx in range(-48, 48, 16):
                A = to_screen(proj(np.array([gx, 0, gz])))
                Bp = to_screen(proj(np.array([gx + 16, 0, gz])))
                C = to_screen(proj(np.array([gx, 0, gz + 16])))
                shade = 0.22 if ((gx // 16 + gz // 16) % 2) else 0.17
                _raster(img, zb, A, Bp, C, None, None, np.array([150, 140, 170]) * shade, depth_bias=1e3)
    texf = tex[..., :3].astype(float)
    for A, Bp, C, n, uv, mat in faces:
        nv = V @ n
        if nv[2] >= 0:
            continue
        sA, sB, sC = to_screen(proj(A)), to_screen(proj(Bp)), to_screen(proj(C))
        if mat in EMISSIVE:
            shade = 1.15
        else:
            shade = 0.5 + 0.6 * max(0.0, float(np.dot(n, light)))
        _raster(img, zb, sA, sB, sC, texf, uv, shade)
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    return out.resize(size, Image.LANCZOS), bounds


def _raster(img, zb, A, B, C, tex, uv, shade, depth_bias=0.0):
    H, W = zb.shape
    D = B + C - A
    xs = [A[0], B[0], C[0], D[0]]
    ys = [A[1], B[1], C[1], D[1]]
    bx0, bx1 = max(0, int(min(xs))), min(W - 1, int(max(xs)) + 1)
    by0, by1 = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
    if bx0 > bx1 or by0 > by1:
        return
    e1, e2 = B - A, C - A
    det = e1[0] * e2[1] - e1[1] * e2[0]
    if abs(det) < 1e-6:
        return
    px, py = np.meshgrid(np.arange(bx0, bx1 + 1) + 0.5, np.arange(by0, by1 + 1) + 0.5)
    dx, dy = px - A[0], py - A[1]
    s = (dx * e2[1] - dy * e2[0]) / det
    t = (e1[0] * dy - e1[1] * dx) / det
    m = (s >= 0) & (s <= 1) & (t >= 0) & (t <= 1)
    if not m.any():
        return
    z = A[2] + s * e1[2] + t * e2[2] + depth_bias
    sub = zb[by0:by1 + 1, bx0:bx1 + 1]
    m &= z < sub
    if not m.any():
        return
    if tex is None:
        col = np.broadcast_to(shade, (m.sum(), 3))
    else:
        u = uv[0] + s[m] * (uv[2] - uv[0])
        v = uv[1] + t[m] * (uv[3] - uv[1])
        ui = np.clip(np.floor(np.minimum(u, max(uv[0], uv[2]) - 1e-3)), 0, TEX - 1).astype(int)
        vi = np.clip(np.floor(np.minimum(v, max(uv[1], uv[3]) - 1e-3)), 0, TEX - 1).astype(int)
        col = tex[vi, ui] * shade
    sub[m] = z[m]
    img[by0:by1 + 1, bx0:bx1 + 1][m] = col


def sheet(tiles, cols, label_h=0):
    w, h = tiles[0].size
    rows = math.ceil(len(tiles) / cols)
    out = Image.new("RGB", (w * cols, h * rows), (12, 10, 18))
    for i, im in enumerate(tiles):
        out.paste(im, ((i % cols) * w, (i // cols) * h))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=HERE)
    ap.add_argument("--no-preview", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    tex = build_texture()
    root = build_model()
    build_anims()
    Image.fromarray(tex, "RGBA").save(os.path.join(args.out, "taupe_foreuse.png"))
    write_bbmodel(root, tex, os.path.join(args.out, "taupe_foreuse.bbmodel"))
    n_cubes = sum(1 for e in ELEMENTS if e["type"] == "cube")
    print(f"{n_cubes} cubes, {len(BONES)} bones, {len(ANIMS)} animations")
    if args.no_preview:
        return

    views = [(-35, -22), (35, -22), (90, -8), (180 - 35, -25), (0, -10), (0, -89)]
    tiles = [render(root, tex, y, p)[0] for y, p in views]
    sheet(tiles, 3).save(os.path.join(args.out, "preview_vues.png"))

    by = {a.name: a for a in ANIMS}
    _, bnd = render(root, tex, -40, -18, by["rage"], 1.0, bounds=None)
    poses = [("idle", 0.0), ("walk", 0.25), ("attack_drill", 0.6), ("attack_drill", 0.8),
             ("attack_claw", 0.35), ("attack_claw", 0.5), ("rage", 1.0), ("burrow", 1.0),
             ("emerge", 0.85), ("death", 2.2)]
    big = (bnd[0] - 30, bnd[1] + 30, bnd[2] - 50, bnd[3] + 10)
    tiles = [render(root, tex, -40, -18, by[n], t, bounds=big)[0] for n, t in poses]
    sheet(tiles, 5).save(os.path.join(args.out, "preview_animations.png"))
    print("previews written")


if __name__ == "__main__":
    main()
