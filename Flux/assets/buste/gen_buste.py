#!/usr/bin/env python3
"""The player's bust of the profile menu (2026-10-29): 4 player heads (head, torso, right arm, left arm) shaped and
placed by item models, re-skinned by the entity shader (InfluArmor's, file buste.fsh.glsl).

How it works:
- every part is a vanilla player head (special model minecraft:player_head: the real skin of the player);
- its item model (display « gui ») scales the head cube to the part's size (torso 8x8x4, arms 4x8x4 or 3x8x4 for
  slim skins), places it, and turns the whole bust by the view angle VIEW;
- each part also has its own quarter-turn ROTATIONS[part] (invisible: a cube stays a cube): the shader reads it
  back from the face normals and knows which part a pixel belongs to, then samples that part's area of the skin
  (torso, arms, jacket, sleeves) instead of the head's;
- in menus the normals come with y flipped (checked in game on 2026-10-29), hence F.
This script writes the item models (pack influmenu: items/buste_*.json, models/item/buste_*.json), the shader
constants (InfluArmor/assets/shaders/buste_constantes.glsl) and, with --preview <skin.png> <out.png>, a picture of
the bust computed with the same maths as the game.

    <venv with numpy, Pillow>/python3 gen_buste.py <plugins dir of the server> [--preview skin.png out.png]
"""
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SHADER_OUT = os.path.join(HERE, "..", "..", "..", "..", "InfluArmor", "assets", "shaders", "buste_constantes.glsl")

VIEW = (12.0, 22.0)   # pitch, yaw (degrees) of the whole bust; unusual on purpose: no other head uses it
K = 3.0               # skin pixel -> GUI pixel (bust 16 px wide and high -> 48 GUI px, about 3 slots)
Z = 60.0              # towards the camera: in front of the menu background drawn by the title
F = np.diag([1.0, -1.0, 1.0])
ROTATIONS = {  # part -> quarter-turn marker (columns: where local x, y, z go)
    "head": [[1, 0, 0], [0, 1, 0], [0, 0, 1]],
    "torso": [[-1, 0, 0], [0, 0, 1], [0, 1, 0]],
    "right_arm": [[0, 1, 0], [1, 0, 0], [0, 0, -1]],
    "left_arm": [[0, -1, 0], [0, 0, -1], [1, 0, 0]],
}
ORDER = ["head", "torso", "right_arm", "left_arm"]
# one item per slot: each part sits in its own slot around the bust's centre slot (dx right, dy down, in slots); the
# game draws the slots in order, so the hidden-side arm first, the head last (it covers the top of the torso)
SLOTS = {"left_arm": (-1, 0), "torso": (0, 0), "right_arm": (1, 0), "head": (0, 1)}
CUT = 8  # visible rows of the torso and arms (of 12)


def parts(slim):
    a = 3.0 if slim else 4.0
    return {  # physical size (x, y, z) and centre, skin pixels, origin at the top of the torso
        "head": ((8, 8, 8), (0, 4, 0)),
        "torso": ((8, CUT, 4), (0, -CUT / 2, 0)),
        "right_arm": ((a, CUT, 4), (-(4 + a / 2), -CUT / 2, 0)),
        "left_arm": ((a, CUT, 4), (4 + a / 2, -CUT / 2, 0)),
    }


def rx(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler_xyz(m):
    """Angles (a, b, c) with m = Rx(a) Ry(b) Rz(c) (Minecraft's display rotation, JOML rotationXYZ)."""
    b = math.asin(max(-1.0, min(1.0, m[0, 2])))
    if abs(math.cos(b)) > 1e-6:
        a = math.atan2(-m[1, 2], m[2, 2])
        c = math.atan2(-m[0, 1], m[0, 0])
    else:
        a = math.atan2(m[2, 1], m[1, 1])
        c = 0.0
    return [round(math.degrees(v), 4) for v in (a, b, c)]


def view():
    return rx(VIEW[0]) @ ry(VIEW[1])


def display(part, slim):
    """(rotation degrees, translation px, scale) of a part's item model, and the matrices behind them."""
    rp = np.array(ROTATIONS[part], dtype=float)
    dims, centre = parts(slim)[part]
    rd = view() @ rp
    s = np.zeros(3)
    for i in range(3):
        j = int(np.argmax(np.abs(rp[:, i])))
        s[i] = K * dims[j] / 8.0
    c_loc = np.array([0.0, -4.0, 0.0])  # head cube centre relative to the item's pivot (block centre), px
    t = view() @ (K * np.array(centre, dtype=float)) - rd @ (s * c_loc) + np.array([0.0, 0.0, Z])
    shown = t + np.array([-18.0 * SLOTS[part][0], 18.0 * SLOTS[part][1], 0.0])  # from its own slot's centre
    return euler_xyz(rd), [round(v, 4) for v in shown], [round(v, 4) for v in s], rd, s, t


def write_models(plugins):
    pack = os.path.join(plugins, "ItemsAdder", "contents", "influmenu")
    os.makedirs(os.path.join(pack, "items"), exist_ok=True)
    os.makedirs(os.path.join(pack, "models", "item"), exist_ok=True)
    names = []
    for part in ORDER:
        for slim in ((False, True) if part.endswith("arm") else (False,)):
            name = "buste_" + part + ("_slim" if slim else "")
            rot, tr, sc, *_ = display(part, slim)
            for v in tr:
                assert abs(v) <= 80, (name, tr)
            for v in sc:
                assert abs(v) <= 4, (name, sc)
            json.dump({"parent": "minecraft:item/template_skull",
                       "display": {"gui": {"rotation": rot, "translation": tr, "scale": sc}}},
                      open(os.path.join(pack, "models", "item", name + ".json"), "w"), indent=2)
            json.dump({"model": {"type": "minecraft:special", "base": "influmenu:item/" + name,
                                 "model": {"type": "minecraft:player_head"},
                                 "transformation": {"left_rotation": [1.0, 0.0, 0.0, -0.0], "right_rotation": [0.0, 0.0, 0.0, 1.0],
                                                    "scale": [1.0, 1.0, 1.0], "translation": [0.5, 0.0, 0.5]}},
                       "oversized_in_gui": True},
                      open(os.path.join(pack, "items", name + ".json"), "w"), indent=2)
            names.append(name)
    return names


def glsl_mat3(m):
    cols = [m[:, i] for i in range(3)]
    return "mat3(" + ", ".join("%.6f" % v for col in cols for v in col) + ")"


def write_shader():
    inv = view().T @ F
    lines = ["// Generated by plugins/fluxplugin/Flux/assets/buste/gen_buste.py: do not edit by hand.",
             "// Normal seen in a menu -> bust frame (undoes the view angle and the menus' y flip).",
             "const mat3 BUSTE_VUE_INV = %s;" % glsl_mat3(inv),
             "// Quarter-turn marker of each part: head, torso, right arm, left arm.",
             "const mat3 BUSTE_ROT[4] = mat3[4](%s);" % ", ".join(glsl_mat3(np.array(ROTATIONS[p], dtype=float)) for p in ORDER),
             "const float BUSTE_CUT = %.1f;" % CUT, ""]
    open(SHADER_OUT, "w").write("\n".join(lines))


# ---------------------------------------------------------------- the shader's logic, for the preview

# face of a box: normal, u axis, v axis (Minecraft's box UV layout, checked on the head in game)
FACES = {
    "top": ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
    "bottom": ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
    "right": ((-1, 0, 0), (0, 0, 1), (0, -1, 0)),
    "front": ((0, 0, 1), (1, 0, 0), (0, -1, 0)),
    "left": ((1, 0, 0), (0, 0, -1), (0, -1, 0)),
    "back": ((0, 0, -1), (-1, 0, 0), (0, -1, 0)),
}


def rect(face, u0, v0, w, h, d):
    return {"top": (u0 + d, v0, w, d), "bottom": (u0 + d + w, v0, w, d), "right": (u0, v0 + d, d, h),
            "front": (u0 + d, v0 + d, w, h), "left": (u0 + d + w, v0 + d, d, h), "back": (u0 + 2 * d + w, v0 + d, w, h)}[face]


def boxes(slim, hat):
    a = 3 if slim else 4
    if hat:
        return {"head": (32, 0, 8, 8, 8), "torso": (16, 32, 8, CUT, 4), "right_arm": (40, 32, a, CUT, 4), "left_arm": (48, 48, a, CUT, 4)}
    return {"head": (0, 0, 8, 8, 8), "torso": (16, 16, 8, CUT, 4), "right_arm": (40, 16, a, CUT, 4), "left_arm": (32, 48, a, CUT, 4)}


def shader_uv(face_loc, fu, fv, normal_seen, hat, slim):
    """What buste.fsh.glsl does for one pixel of a head face: skin pixel (u, v) to show, or None."""
    m = view().T @ F @ np.array(normal_seen)
    if max(abs(m)) < 0.98:
        return None
    n, au, av = (np.array(x, dtype=float) for x in FACES[face_loc])
    part = None
    for p in ORDER:
        if np.dot(np.array(ROTATIONS[p], dtype=float) @ n, m) > 0.98:
            part = p
    if part is None:
        return None
    r = np.array(ROTATIONS[part], dtype=float)
    p_phys = r @ (n + au * (2 * fu - 1) + av * (2 * fv - 1))
    nf = r @ n
    face_t = next(f for f, (fn, _, _) in FACES.items() if np.dot(np.array(fn), nf) > 0.5)
    _, au_t, av_t = (np.array(x, dtype=float) for x in FACES[face_t])
    x, y, w, h = rect(face_t, *boxes(slim, hat)[part])
    tu, tv = (np.dot(p_phys, au_t) + 1) / 2, (np.dot(p_phys, av_t) + 1) / 2
    return x + min(max(tu * w, 0.01), w - 0.01), y + min(max(tv * h, 0.01), h - 0.01)


def preview(skin_path, out_path, slim=False):
    from PIL import Image
    skin = Image.open(skin_path).convert("RGBA")
    size, scale = 64, 4
    n_px = size * scale
    xs = (np.arange(n_px) + 0.5) / scale - size / 2
    gx, gy = np.meshgrid(xs, -xs)
    img = Image.new("RGBA", (n_px, n_px), (40, 32, 55, 255))
    out = img.load()
    depth = np.full((n_px, n_px), -1e9)
    for part in ORDER:
        _, _, _, rd, s, t = display(part, slim)
        m = rd @ np.diag(s)
        minv = np.linalg.inv(m)
        dvec = minv @ np.array([0.0, 0.0, -1.0])
        pts = np.stack([gx - t[0], gy - t[1], np.full_like(gx, 200.0 - t[2])], axis=-1)
        o = pts @ minv.T
        for hat, inflate in ((False, 0.0), (True, 0.25)):
            lo = np.array([-4 - inflate, -8 - inflate, -4 - inflate])
            hi = np.array([4 + inflate, 0 + inflate, 4 + inflate])
            for face, (n, au, av) in FACES.items():
                n = np.array(n, dtype=float)
                if (rd @ n)[2] <= 0:
                    continue  # back face
                seen = F @ rd @ n  # normal as the game gives it in a menu
                axis = int(np.argmax(np.abs(n)))
                plane = hi[axis] if n[axis] > 0 else lo[axis]
                k = (plane - o[..., axis]) / dvec[axis]
                p = o + k[..., None] * dvec
                inside = np.all((p >= lo - 1e-6) & (p <= hi + 1e-6), axis=-1)
                rel = (p - (lo + hi) / 2) / ((hi - lo) / 2)
                fu = (rel @ np.array(au, dtype=float) + 1) / 2
                fv = (rel @ np.array(av, dtype=float) + 1) / 2
                zd = (p @ m.T)[..., 2] + t[2]
                for sy, sx in zip(*np.nonzero(inside)):
                    if zd[sy, sx] <= depth[sy, sx]:
                        continue
                    uv = shader_uv(face, fu[sy, sx], fv[sy, sx], seen, hat, slim)
                    if uv is None:
                        continue
                    c = skin.getpixel((int(uv[0]), int(uv[1])))
                    if c[3] < 26:
                        continue
                    depth[sy, sx] = zd[sy, sx]
                    light = 1.0 if n[1] > 0.5 else 0.85 if abs(n[2]) > 0.5 else 0.7
                    nf = rd @ n
                    light = 1.0 if nf[1] > 0.5 else 0.85 if abs(nf[2]) > 0.5 else 0.7
                    out[int(sx), int(sy)] = (int(c[0] * light), int(c[1] * light), int(c[2] * light), 255)
    img.save(out_path)


if __name__ == "__main__":
    if "--preview" in sys.argv:
        i = sys.argv.index("--preview")
        preview(sys.argv[i + 1], sys.argv[i + 2], slim="--slim" in sys.argv)
        print("preview ->", sys.argv[i + 2])
    else:
        print(write_models(sys.argv[1]))
        write_shader()
        print("shader constants ->", os.path.normpath(SHADER_OUT))
