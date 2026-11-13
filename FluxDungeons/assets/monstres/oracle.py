#!/usr/bin/env python3
"""The Oracle: an old human mage, wise and calm, STATIC Blockbench model (.bbmodel), 2026-11-14.

Same collection as the Oversized Evoker (evocateur_demesure.py): same robe family (long robe, darker open coat,
lighter tabard with gold edges, belt, wide cuffs, soft shoulders), same painters, same pixel size. Everything that
makes a person is his own: a human head (warm skin, grey blue eyes, thin grey brows, straight nose), a long silver
beard in three volumes with a moustache, silver hair showing under a real wizard's hat (wide brim, cone in four
volumes, tip bent backwards and to one side), a low collar. Warmer, lighter violet than the Evoker's. No glow.

Few cubes: strands of beard and hair, folds, the hat's mark are painted, not built.
This script replaces the voxel version of 2026-11-10.

Usage: oracle.py [output folder] [--apercu <sheet.png>] [--cubes <table.md>]
Front faces north (-z), his right is +x, ground at y = 0, origin between the feet.
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bbkit import apercu, attach, bake, export, ground, hx, matrix, tone  # noqa: E402
from golem_fonderie import app, euler, rot  # noqa: E402
import evocateur_demesure as E  # noqa: E402  (shared painters: cloth, metal, mount)

IDENT = "oracle"
NS = uuid.UUID("5b2d9e00-0000-4000-8000-00000000a7c1")
SCALE = 1.4           # 56 drawing units tall with the hat -> about 4.9 blocks (body alone: 4.0)


def pal(*c):
    return [hx(x) for x in c]


# --------------------------------------------------------------------------- palettes (one per material)
ROBE = pal("#1F182E", "#2A203C", "#3B2B52", "#554071", "#674F83", "#79618F")     # contact .. glint, main = index 3
HAT = pal("#161B29", "#20283C", "#29334E", "#354361", "#465775", "#64738E")      # night blue, main = index 2
SKIN = pal("#705140", "#916B56", "#B68C70", "#CBA68A", "#DFC0A3")                # deep shadow, shadow, main, light, glint
BEARD = pal("#50545D", "#6C7077", "#85878A", "#ADB0B4", "#D0D1D0")               # shadow, secondary, main, light, silver
HAIR = pal("#565B63", "#777D84", "#A7ADB2", "#C4C8CA")                           # dark, main, light, glint
EYE_WHITE, EYE_SHADE, IRIS, IRIS_DARK, PUPIL, EYE_LINE = (hx(c) for c in ("#E5E0D3", "#C4BDAF", "#7186A5", "#465A78",
                                                                           "#252B35", "#51483F"))
BROW = pal("#493F39", "#62564C")
GOLD = E.GOLD
MARK = hx("#B8C8FF")
SIDES = E.SIDES


def robe(level=3, **kw):
    return E.cloth(level, shades=ROBE, **kw)


# --------------------------------------------------------------------------- painters
def face(w, h, r, f):
    """Human face, warm and matt: lit forehead and nose bridge, sides one tone darker, one soft line on the
    forehead, a small shadow under each eye, lit cheekbones, shadow beside the nose. The lower rows are under the
    beard."""
    if f == "up":
        return [[SKIN[3]] * w for _ in range(h)]
    if f == "down":
        return [[SKIN[0]] * w for _ in range(h)]
    if f != "north" or w < 9 or h < 10:
        px = [[SKIN[1]] * w for _ in range(h)]
        px[h - 1] = [SKIN[0]] * w
        return px
    px = [[SKIN[2]] * w for _ in range(h)]
    for y in range(h):
        px[y][0] = px[y][w - 1] = SKIN[1]
    for x in (3, 4, 5):
        px[1][x] = SKIN[3]
        px[2][x] = SKIN[1]                                   # one line across the forehead
        px[3][x] = SKIN[3]
    px[3][4] = px[4][4] = SKIN[4]                            # bridge of the nose
    for x in (1, 2, 6, 7):
        px[6][x] = SKIN[1]                                   # under the eyes
    px[7][1] = px[7][7] = SKIN[3]                            # cheekbones
    px[6][3] = px[6][5] = px[7][3] = px[7][5] = SKIN[1]      # beside the nose
    px[8] = [SKIN[1]] * w
    px[9] = [SKIN[0]] * w
    return px


def flesh(w, h, r, f):
    if f == "down":
        return [[SKIN[0]] * w for _ in range(h)]
    return [[SKIN[3 if f == "up" else 2 if f == "north" else 1]] * w for _ in range(h)]


def nose(w, h, r, f):
    if f == "down":
        return [[SKIN[0]] * w for _ in range(h)]
    px = [[SKIN[3 if f in ("north", "up") else 1]] * w for _ in range(h)]
    if f == "north":
        px[h - 1] = [SKIN[2]] * w
    return px


def eye(inner_last):
    def f(w, h, r, face_):
        """Calm eye, density 2 (6 x 3): a thin lid line, the white, a blue grey iris looking straight ahead with a
        darker side and the pupil in its middle. `inner_last`: the nose side is the last column."""
        if face_ != "north" or w < 6 or h < 3:
            return [[EYE_LINE] * w for _ in range(h)]
        rows = [[SKIN[1], EYE_LINE, EYE_LINE, EYE_LINE, EYE_LINE, SKIN[1]],
                [EYE_SHADE, EYE_WHITE, IRIS, PUPIL, IRIS_DARK, EYE_WHITE],
                [SKIN[1], EYE_SHADE, IRIS_DARK, IRIS_DARK, EYE_SHADE, SKIN[1]]]
        return [row if inner_last else row[::-1] for row in rows]
    return f


def brow(w, h, r, f):
    return [[BROW[1 if f == "up" else 0]] * w for _ in range(h)]


def strands(shades, base, tip=False, part=False, mouth=False):
    """Hair in locks: vertical clusters. Every third column a shadow line from the second row down, the column
    before it lit on its upper half; `part`: a darker line down the middle (the beard's parting); `tip`: the last
    two rows lighter; `mouth`: a dark notch in the middle of the top row (the mouth, under the moustache)."""
    def f(w, h, r, face_):
        if face_ == "up":
            return [[tone(shades, base + 1)] * w for _ in range(h)]
        if face_ == "down":
            return [[shades[0]] * w for _ in range(h)]
        b = base if face_ == "north" else base - 1
        px = [[tone(shades, b)] * w for _ in range(h)]
        if h >= 3:
            for x in range(1, w, 3):
                for y in range(1, h):
                    px[y][x] = tone(shades, b - 1)
                if x >= 1 and face_ == "north":
                    for y in range(0, max(1, h // 2)):
                        px[y][x - 1] = tone(shades, b + 1)
            if part and face_ == "north" and w >= 3:
                for y in range(max(1, h // 3), h):
                    px[y][w // 2] = shades[0]
            if tip:
                for y in (h - 2, h - 1):
                    px[y] = [tone(shades, b + 1) if c != shades[0] else c for c in px[y]]
            else:
                px[h - 1] = [tone(shades, b - 1)] * w
        if mouth and face_ == "north":
            for x in range(w // 2 - 1, w // 2 + 2):
                px[0][x] = SKIN[0]
        return px
    return f


def brim(w, h, r, f):
    """Hat brim: lit cloth on top, dark underneath, one gold line around its edge."""
    if f == "up":
        return [[HAT[3]] * w for _ in range(h)]
    if f == "down":
        return [[HAT[0]] * w for _ in range(h)]
    px = [[GOLD[1]] * w for _ in range(h)]
    if h >= 2:
        px[h - 1] = [GOLD[0]] * w
    return px


def hat_base(w, h, r, f):
    """Bottom of the cone: night blue cloth, its only mark on the front: a pale blue lozenge between two gold pixels."""
    px = E.cloth(2, shades=HAT)(w, h, r, f)
    if f == "north" and w >= 7 and h >= 3:
        c, y = w // 2, h // 2
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1), (0, 0)):
            if 0 <= y + dy < h:
                px[y + dy][c + dx] = MARK
        px[y][c - 3] = px[y][c + 3] = GOLD[1]
    return px


def cuff(w, h, r, f):
    if f == "down":
        return [[ROBE[2] if x in (0, w - 1) or y in (0, h - 1) else ROBE[0] for x in range(w)] for y in range(h)]
    px = robe(4)(w, h, r, f)
    if f in SIDES:
        px[h - 1] = [GOLD[1]] * w
    return px


def shoulder(w, h, r, f):
    px = robe(3)(w, h, r, f)
    if f in SIDES:
        px[h - 1] = [GOLD[1]] * w
    return px


def collar(w, h, r, f):
    """Low mage collar: dark cloth, one gold line along its top."""
    px = robe(2)(w, h, r, f if f in ("up", "down") else "north")
    if f in SIDES:
        px[0] = [GOLD[1]] * w
    return px


def belt(w, h, r, f):
    px = [[ROBE[2 if f == "up" else 1]] * w for _ in range(h)]
    if f in SIDES:
        px[h - 1] = [ROBE[0]] * w
    return px


def boot(w, h, r, f):
    px = [[ROBE[0]] * w for _ in range(h)]
    if f == "up":
        px = [[ROBE[1]] * w for _ in range(h)]
    return px


def tabard_up(px, w, h):
    """Chest: one vertical gold line, a small lozenge on the chest, a smaller mark near the belt."""
    cx = w // 2
    for y in range(1, h - 1):
        px[y][cx] = GOLD[1]
    px[4][cx] = px[1][1]
    E.diamond(px, cx, 4, 1, GOLD[2])
    px[9][cx] = GOLD[2]


MATS = {
    "robe": robe(3, folds=((0.3, 0.5), (0.75, 0.35))), "robe_hem": robe(3, hem=True),
    "skirt_back": robe(3, folds=((0.2, 0.3), (0.5, 0.15), (0.8, 0.4))),
    "coat_side": robe(2, folds=((0.3, 0.3), (0.7, 0.5))), "lapel": robe(2),
    "tabard_up": robe(4, edges=("first", "last"), art=tabard_up), "tabard": robe(4, edges=("first", "last")),
    "tabard_tip": robe(4, hem=True), "belt": belt, "mount": E.mount, "mantle": robe(3), "shoulder": shoulder,
    "collar": collar, "sleeve": robe(3, folds=((0.6, 0.3),)), "sleeve_low": robe(3, folds=((0.35, 0.2),)), "cuff": cuff,
    "hand": flesh, "finger": flesh, "neck": flesh, "face": face, "nose": nose, "brow": brow,
    "eye_right": (eye(True), 2), "eye_left": (eye(False), 2),
    "beard_top": strands(BEARD, 2, mouth=True), "beard_mid": strands(BEARD, 2, part=True),
    "beard_tip": strands(BEARD, 2, part=True, tip=True), "moustache": strands(BEARD, 3),
    "hair": strands(HAIR, 2),
    "brim": brim, "hat_base": hat_base, "hat": E.cloth(2, shades=HAT, folds=((0.35, 0.2),)),
    "hat_tip": E.cloth(2, shades=HAT), "trouser": robe(1), "boot": boot,
}

# --------------------------------------------------------------------------- skeleton and pose (drawing units)
HIPS = (0, 21, 0)
NECK = (0, 35.5, 0)
HEAD_R = rot(-2, 4, 0)              # level, turned a touch to his left: he listens
SHOULDER = {1: (7.4, 33, 0), -1: (-7.4, 33, 0)}
# arm: (shoulder rotation, extra elbow bend, finger curl, finger fan)
ARMS = {
    1: (rot(12, 0, 8), rot(46, 0, 0), 20, 5),       # right: forearm lifted, open hand, as if explaining
    -1: (rot(-4, 0, -9), rot(14, 0, -3), 15, 6),    # left: hanging, relaxed
}


def parts():
    P, G = [], {"Root": ((0, 0, 0), None), "Body": (HIPS, "Root"), "Skirt": (HIPS, "Body"), "Torso": (HIPS, "Body"),
                "Head": (NECK, "Torso")}

    def add(g, name, c, size, r, mat):
        P.append((g, name, list(c), tuple(size), r, mat))

    def panel(g, name, top, size, r, mat, tabs=(), tab_mat=None, axis=0):
        M = rot(*r)

        def at(off):
            q = app(M, off)
            return [top[i] + q[i] for i in range(3)]
        add(g, name, at((0, -size[1] / 2, 0)), size, M, mat)
        for k, (d, w, h) in enumerate(tabs):
            off, sz = [0, -size[1] - h / 2, 0], list(size)
            off[axis], sz[axis], sz[1] = d, w, h
            add(g, f"{name}_tab_{k + 1}", at(off), sz, M, tab_mat or mat)

    # ---------------------------------------------------------------- legs
    for s, side, yaw, z in ((1, "right", -6, -0.6), (-1, "left", 8, -1.2)):
        G[f"Leg_{side}"] = ((2.5 * s, 14, 0.3), "Root")
        add(f"Leg_{side}", "leg", (2.5 * s, 8, 0.3), (4, 12, 4), (0, 0, 0), "trouser")
        add(f"Leg_{side}", "boot", (2.7 * s, 1.25, z), (4.6, 2.5, 7.2), (0, yaw, 0), "boot")

    # ---------------------------------------------------------------- robe: the Evoker's cut, fewer panels
    add("Skirt", "skirt_core", (0, 12.2, 0.4), (10.6, 18, 7.4), (0, 0, 0), "robe")
    panel("Skirt", "skirt_front", (0, 21, -3.6), (10, 17, 1.4), (4, 0, 0), "robe_hem")
    G["Skirt_back"] = ((0, 21, 3.8), "Skirt")
    panel("Skirt_back", "skirt_back", (0, 21, 3.8), (11, 17.5, 1.5), (-8, 0, 0), "skirt_back",
          tabs=((0, 3.4, 1.6),), tab_mat="robe_hem")
    G["Coat_right"] = ((5.7, 21.5, 0.2), "Skirt")
    panel("Coat_right", "coat_right", (5.7, 21.5, 0.2), (1.6, 17, 9), (0, 0, 7), "coat_side")
    G["Coat_left"] = ((-5.7, 21.5, 0.2), "Skirt")
    panel("Coat_left", "coat_left", (-5.7, 21.5, 0.2), (1.6, 17, 9), (0, 0, -7), "coat_side")
    G["Tabard"] = ((0, 20.5, -4.6), "Skirt")
    panel("Tabard", "tabard_low", (0, 20.5, -4.6), (7, 15, 0.9), (7, 0, 0), "tabard", tabs=((0, 5, 1.2),), tab_mat="tabard_tip")
    panel("Tabard", "tabard_point", (0, 20.5, -4.6), (3, 17.4, 0.88), (7, 0, 0), "tabard_tip")

    # ---------------------------------------------------------------- torso
    add("Torso", "torso", (0, 28, 0), (11, 14.5, 7), (0, 0, 0), "robe")
    add("Torso", "belt", (0, 20.9, 0), (12.2, 2.4, 8.4), (0, 0, 0), "belt")
    add("Torso", "buckle", (0, 20.9, -4.4), (2.6, 2.6, 0.9), (0, 0, 45), "mount")
    add("Torso", "tabard_up", (0, 27.5, -3.85), (7, 12, 0.8), (0, 0, 0), "tabard_up")
    add("Torso", "lapel_right", (4.55, 27.6, -3.8), (2.6, 12, 0.9), (0, 0, -4), "lapel")
    add("Torso", "lapel_left", (-4.55, 27.6, -3.8), (2.6, 12, 0.9), (0, 0, 4), "lapel")
    add("Torso", "neck", (0, 35.6, 0.2), (5, 1.8, 5), (0, 0, 0), "neck")
    add("Torso", "mantle", (0, 34.0, 0.3), (16, 2.6, 9), (0, 0, 0), "mantle")
    for s, side in ((1, "right"), (-1, "left")):                 # soft shoulders: one tilted layer, one gold line
        add("Torso", f"shoulder_{side}", (8.6 * s, 34.3, 0.3), (6.2, 2.2, 9.6), (0, 0, -20 * s), "shoulder")
        add("Torso", f"collar_{side}", (4.3 * s, 36.3, 1.0), (1, 2.6, 6), (0, 0, -8 * s), "collar")
    add("Torso", "collar_back", (0, 36.6, 4.0), (9, 3.2, 1), (-8, 0, 0), "collar")

    # ---------------------------------------------------------------- head: human
    on_head = attach(NECK, HEAD_R)

    def hd(g, name, c, size, r, mat):
        cc, M = on_head(c, r)
        add(g, name, cc, size, M, mat)

    hd("Head", "head", (0, 40.75, -0.3), (9, 9.5, 9), (0, 0, 0), "face")
    hd("Head", "nose", (0, 40.3, -5.2), (1.6, 3.0, 1.4), (-8, 0, 0), "nose")
    # thin brows, outer ends a little lower: thoughtful, not angry
    hd("Head", "brow_right", (2.5, 42.6, -4.95), (2.8, 0.7, 0.7), (0, 0, -7), "brow")
    hd("Head", "brow_left", (-2.5, 42.6, -4.95), (2.8, 0.7, 0.7), (0, 0, 7), "brow")
    hd("Head", "eye_right", (2.5, 41.2, -4.85), (3, 1.5, 0.2), (0, 0, 0), "eye_right")
    hd("Head", "eye_left", (-2.5, 41.2, -4.85), (3, 1.5, 0.2), (0, 0, 0), "eye_left")
    # hair: two side volumes and the back, down to the collar
    hd("Head", "hair_back", (0, 40.6, 4.7), (9.8, 8.2, 1.2), (0, 0, 0), "hair")
    for s, side in ((1, "right"), (-1, "left")):
        hd("Head", f"hair_{side}", (4.9 * s, 41.0, 1.2), (1.1, 7.4, 6.2), (0, 0, 0), "hair")
    # beard: three volumes narrowing downwards, each a little further forward, and the moustache over them
    G["Beard"] = (on_head((0, 37.5, -4.0), (0, 0, 0))[0], "Head")
    hd("Beard", "beard_top", (0, 37.6, -3.5), (9.6, 3.6, 3.4), (0, 0, 0), "beard_top")
    hd("Beard", "beard_mid", (0, 34.6, -4.2), (6.6, 3.4, 2.6), (6, 0, 0), "beard_mid")
    hd("Beard", "beard_tip", (0, 31.9, -4.6), (3.6, 3.0, 1.9), (12, 0, 0), "beard_tip")
    hd("Beard", "moustache_right", (1.6, 38.9, -5.5), (2.8, 1.0, 0.9), (0, 0, -12), "moustache")
    hd("Beard", "moustache_left", (-1.6, 38.9, -5.5), (2.8, 1.0, 0.9), (0, 0, 12), "moustache")
    # the hat: brim, then a cone in four volumes, each tilted a little more: the tip bends back and to his right
    G["Hat"] = (on_head((0, 44.6, -0.2), (0, 0, 0))[0], "Head")
    hd("Hat", "brim", (0, 44.7, -0.2), (13.6, 1.2, 13.6), (-4, 0, 3), "brim")
    hd("Hat", "hat_base", (0, 46.3, -0.1), (9.8, 2.4, 9.8), (-4, 0, 3), "hat_base")
    hd("Hat", "hat_mid", (0.2, 49.0, 0.4), (7.0, 3.6, 7.0), (-9, 0, 5), "hat")
    hd("Hat", "hat_top", (0.6, 51.9, 1.4), (4.4, 3.4, 4.4), (-22, 0, 10), "hat")
    hd("Hat", "hat_tip", (1.3, 54.0, 3.0), (2.2, 2.8, 2.2), (-46, 0, 16), "hat_tip")

    # ---------------------------------------------------------------- arms: the Evoker's, relaxed
    for s, side in ((1, "right"), (-1, "left")):
        R_sh, R_el, curl, fan = ARMS[s]
        sh, el, wr = SHOULDER[s], (8.0 * s, 24.8, 0), (8.0 * s, 15.6, 0)
        upper = attach(sh, R_sh)
        bend = attach(el, R_el)

        def lower(c, r, upper=upper, bend=bend):
            return upper(*bend(c, r))
        ag, fg, hg = f"Arm_{side}", f"Forearm_{side}", f"Hand_{side}"
        G[ag] = (sh, "Torso")
        G[fg] = (upper(el, (0, 0, 0))[0], ag)
        G[hg] = (lower(wr, (0, 0, 0))[0], fg)
        for g, name, c, size, mat, place in (
            (ag, "sleeve_upper", (8.0 * s, 29.0, 0), (4.2, 9, 4.6), "sleeve", upper),
            (fg, "sleeve_lower", (8.0 * s, 21.8, 0), (5, 6.4, 5.4), "sleeve_low", lower),
            (fg, "cuff", (8.0 * s, 17.3, 0), (6.6, 4, 7), "cuff", lower),
            (hg, "hand", (8.0 * s, 14.1, 0), (3.0, 3.2, 3.4), "hand", lower),
        ):
            cc, M = place(c, (0, 0, 0))
            add(g, name, cc, size, M, mat)
        fb, tb = f"Fingers_{side}", f"Thumb_{side}"
        G[fb] = (lower((8.0 * s, 12.6, 0.6), (0, 0, 0))[0], hg)
        for k, dx in enumerate((-1.0, 0, 1.0)):
            knuckle = (8.0 * s + dx, 12.6, 0.6)
            c, M = attach(knuckle, rot(curl, 0, fan * dx))((knuckle[0], knuckle[1] - 1.0, knuckle[2]), (0, 0, 0))
            cc, M = lower(c, M)
            add(fb, f"finger_{k + 1}", cc, (0.9, 2.2, 0.9), M, "finger")
        knuckle = (8.0 * s - 1.2 * s, 13.4, -1.6)
        G[tb] = (lower(knuckle, (0, 0, 0))[0], hg)
        c, M = attach(knuckle, rot(curl + 30, 0, -20 * s))((knuckle[0], knuckle[1] - 0.8, knuckle[2]), (0, 0, 0))
        cc, M = lower(c, M)
        add(tb, "thumb", cc, (0.9, 1.8, 0.9), M, "finger")

    order, done = [], set()
    while len(order) < len(G):
        for k, (pv, par) in G.items():
            if k not in done and (par is None or par in done):
                order.append(k)
                done.add(k)
    return P, {k: G[k] for k in order}


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--apercu", "--cubes"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    out = args[0] if args else HERE
    P, groups, dims = ground(*parts(), scale=SCALE, center=False)
    uv, atlases, sizes = bake(P, MATS, {})
    data = export(IDENT, NS, P, groups, uv, atlases, sizes)
    data["textures"] = data["textures"][:1]          # nothing glows
    print(f"{len(P)} cubes ; {dims[0]:.2f} x {dims[1]:.2f} x {dims[2]:.2f} blocs (l x h x p) ; texture {sizes[0]}")
    path = os.path.join(out, IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print("écrit", path)
    if "--apercu" in opts:
        apercu(data, atlases, sizes, opts["--apercu"], set(), views=((0, 4), (35, 12), (90, 3), (180, 6), (-40, 10)), size=440)
    if "--cubes" in opts:
        E.IDENT = IDENT
        with open(opts["--cubes"], "w", encoding="utf-8") as f:
            f.write(E.cube_table(P, groups, dims))
        print("écrit", opts["--cubes"])
