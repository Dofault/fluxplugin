#!/usr/bin/env python3
"""Pixel art de l'Ossacorne pour l'hologramme du portail Défenseur (lobby RPG), namespace influhologrammes.

    python3 gen_donjon_defenseur.py [dossier ItemsAdder/contents] [--apercu image.png]   (Labs par défaut ; Pillow requis)
    puis /iazip, puis scripts/typewriter/gen_lobby_rpg.py (qui lit les caractères dans le cache d'ItemsAdder)

  - donjon_defenseur_ossacorne : le boss vu de face (docs/technique/boss/ossacorne.md, palettes os et cornes de son
    cahier des charges) : crâne massif aux orbites sombres et arcades froncées, deux cornes de bélier enroulées,
    plaques osseuses pointues de la colonne en éventail derrière, côtes ouvertes, pattes avant à trois griffes ;
    posé sur une bande de pierre aux gris du logo, effilée des deux côtés ;
  - donjon_defenseur_logo : le logo sources/donjon_defenseur_logo.png (1024 x 319) réduit à 208 x 65.
Les deux images sont affichées l'une sur l'autre (deux hologrammes, l'Ossacorne au-dessus) : la bande de pierre a la
largeur du mot « DONJON » (65 % du logo). Même taille de logo affichée que les autres portails (4,4 blocs de large).
"""
import math
import random
from pathlib import Path

from pixlib import Canvas, art_height, args, hexc, publish

OUT, preview = args()
LOGO_SIZE = (208, 65)  # under 256 wide (pixlib)
LOGO_HEIGHT = 37       # 4.4 blocks wide at the hologram's scale 1.5, as the other logos
DONJON = 664 / 1024    # width of the word DONJON in the logo (x 180 to 843 of the source)

LINE = hexc('191919')  # outline of the logo
BONE = [hexc('8A7A68'), hexc('B8A68A'), hexc('DCCBA8'), hexc('FFF1D9')]
BONE_BACK = [hexc('6E5E52'), hexc('948268'), hexc('B8A68A'), hexc('D8C7A6')]
CAVITY = hexc('382D2B')
DEEP = hexc('1E1716')
HORN = [hexc('3A2416'), hexc('5A3A22'), hexc('7A5232'), hexc('9A6E45')]
GLOW = hexc('FF5C5C')   # a red spark deep in the orbits (red of the charter)
STONE = [hexc('3C3B3B'), hexc('5D5B5B'), hexc('777777'), hexc('919191'), hexc('A8A8A8'), hexc('C5C5C5')]

W, H = 100, 66
cv = Canvas(W, H, LINE)
put, ellipse, shape, seg = cv.put, cv.ellipse, cv.shape, cv.segment
rnd = random.Random(5)


def mirror_point(p):
    return (W - p[0], p[1])


def bone(points, radii, ramp=BONE):
    """A bone along points (one radius per segment), knobs at the joints."""
    mask = set()
    for (a, b), r in zip(zip(points, points[1:]), radii):
        mask |= seg(a, b, r)
    shape(mask, ramp)
    return mask


def spots(mask, chance=.08):
    """Old bone: a darker speck now and then."""
    for (x, y) in mask:
        if rnd.random() < chance and (x - 1, y - 1) in mask and (x + 1, y + 1) in mask:
            put(x, y, BONE[1])


# ---- dorsal plates in a fan behind the skull (back ones darker) ----
PLATES = [(-26, 12, 4.2), (-17, 6, 4.6), (-8, 1, 5.0), (0, -1, 5.4), (8, 1, 5.0), (17, 6, 4.6), (26, 12, 4.2)]
for dx, tip_y, half_w in sorted(PLATES, key=lambda p: -abs(p[0])):
    base_x, base_y = 50 + dx * .55, 24
    tip_x = 50 + dx
    plate = set()
    for y in range(int(tip_y), base_y + 1):
        t = (y - tip_y) / (base_y - tip_y)
        cx = tip_x + (base_x - tip_x) * t
        w = half_w * t ** .7
        for x in range(int(cx - w), int(cx + w) + 1):
            if abs(x + .5 - cx) <= w:
                plate.add((x, y))
    shape(plate, BONE_BACK if abs(dx) > 10 else BONE)
    for y in range(base_y - 3, base_y + 1):        # dark root
        for (x, yy) in plate:
            if yy == y:
                put(x, y, CAVITY)

# ---- ribs flaring out on both sides, shoulder blades ----
for flip in (False, True):
    f = mirror_point if flip else (lambda p: p)
    for k in range(3):
        y0 = 30 + k * 5
        pts = [f((44, y0)), f((34 - k, y0 - 1)), f((26 - k * 2, y0 + 3)), f((22 - k * 2, y0 + 9 - k))]
        spots(bone(pts, [1.6, 1.5, 1.2], BONE_BACK), .05)
    blade = ellipse(*f((31, 30)), 7, 5) if not flip else ellipse(W - 31, 30, 7, 5)
    shape(blade, BONE)
    spots(blade)

# ---- front legs: humerus down and out, elbow, forearm, three curved claws ----
for flip in (False, True):
    f = mirror_point if flip else (lambda p: p)
    leg = bone([f((31, 32)), f((22, 42)), f((25, 55))], [3.6, 3.0])
    spots(leg)
    elbow = ellipse(*f((22.5, 42.5)), 4, 3.6)
    shape(elbow, BONE)
    foot = ellipse(*f((25, 56)), 5, 2.6)
    shape(foot, BONE)
    for dx in (-4, 0, 4):                          # claws, curving down
        claw = seg(f((25 + dx, 57)), f((25 + dx * 1.4, 60)), 1.0)
        shape(claw, BONE_BACK, light=False)

# ---- horns: ram curls, base on top of the skull, out, back down, tip forward ----
def horn(flip):
    cx, cy = (W - 26, 21) if flip else (26, 21)
    mask, order = set(), {}
    steps = 40
    for i in range(steps):
        t = i / (steps - 1)
        a = math.radians(-35 - 300 * t)            # from the skull, over the top, round the outside, under
        r = 10 - 4.5 * t
        x = cx + (r * math.cos(a)) * (-1 if flip else 1) * -1
        y = cy + r * math.sin(a)
        for p in seg((x, y), (x, y), 3.6 - 1.9 * t):
            mask.add(p)
            order.setdefault(p, t)
    shape(mask, HORN)
    for p, t in order.items():                     # growth rings
        if int(t * 14) % 2 and (p[0], p[1] - 1) in mask and (p[0], p[1] + 1) in mask:
            put(*p, HORN[1] if rnd.random() < .85 else HORN[0])


# ---- skull: big rounded cranium, frowning brow ridges, deep orbits, short wide muzzle, open jaw and fangs ----
cranium = ellipse(50, 26, 14.5, 11)
muzzle = {(x, y) for x in range(40, 61) for y in range(30, 42) if not (y > 39 and (x < 42 or x > 58))}
jaw = {(x, y) for x in range(41, 60) for y in range(43, 48) if not (y == 47 and (x < 43 or x > 57))}
shape(jaw, BONE_BACK)
for x in range(42, 59):                            # the open mouth, dark
    put(x, 42, DEEP)
shape(cranium | muzzle, BONE)
spots(cranium | muzzle, .05)
horn(False)
horn(True)
for flip in (1, -1):
    # orbit: slanted (angry), deep, a red spark at the bottom
    orbit = {(x, y) for x in range(-5, 5) for y in range(-3, 4) if abs(x + .5) + abs(y + .5) * 1.25 <= 5.2}
    ox, oy = 50 + flip * 6.5, 28
    for (x, y) in orbit:
        if not (y < -1 and x * flip < -1):         # cut by the frown on the inner top corner
            put(int(ox + x * flip), oy + y, CAVITY if abs(x) == 4 or y in (-3, 3) else DEEP)
    put(int(ox - flip), oy + 1, GLOW)
    for i in range(9):                             # brow ridge, low towards the middle
        x = 50 + flip * (2 + i)
        y = 22 + (8 - i) // 3
        put(x, y, BONE[3])
        put(x, y + 1, BONE[0])
# nose: two narrow nostrils
for flip in (1, -1):
    for y in range(33, 37):
        put(50 + flip * 2 - (1 if flip < 0 else 0), y, DEEP)
# teeth: big upper fangs, smaller ones between; lower fangs going up
for x in range(42, 59):
    if x % 2 == 0:
        put(x, 41, BONE[3])
for flip in (1, -1):
    fx = 50 + flip * 6 - (1 if flip < 0 else 0)
    for k, y in enumerate(range(41, 45)):
        put(fx, y, BONE[3])
        if k < 2:
            put(fx - flip, y, BONE[2])
    put(fx, 45, LINE)
    lx = 50 + flip * 3 - (1 if flip < 0 else 0)
    for y in range(42, 44):
        put(lx, y, BONE[3])

# ---- stone under the feet, the grays of the logo, thinning out on both sides, a few bones lying on it ----
def half(x):
    u = min(1.0, abs((x + .5 - 50) / 49))
    return 4.6 * (1 - u ** 3) ** .55


ground = {(x, y) for x in range(W) for y in range(H - 1) if half(x) > .6 and 60 - half(x) <= y <= 60 + half(x)
          and y >= 58 - (1 if (x * 5) % 9 == 0 else 0)}
for (x, y) in ground:
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if (x + dx, y + dy) not in ground:
            put(x + dx, y + dy, LINE)
for (x, y) in ground:
    d = y - min(yy for (xx, yy) in ground if xx == x)
    c = STONE[5] if d == 0 else STONE[4] if d == 1 else STONE[3] if d < 4 else STONE[2]
    if d >= 1 and rnd.random() < .25:
        c = STONE[rnd.choice((1, 2, 3, 4))]
    put(x, y, c)
for bx in (10, 84):                                # bones lying on the stone
    shape(seg((bx, 57), (bx + 6, 56), .9), BONE_BACK, light=False)

art = cv.cropped()
publish(OUT, "donjon_defenseur", "ossacorne", art, art_height(art, LOGO_SIZE, LOGO_HEIGHT, DONJON),
        Path(__file__).resolve().parent / "sources" / "donjon_defenseur_logo.png", LOGO_SIZE, LOGO_HEIGHT,
        "Ossacorne au-dessus du logo du portail Défenseur (lobby RPG, page Typewriter lobby_rpg).",
        "Logo DONJON DÉFENSEUR, sous l'Ossacorne (remplace le nom en texte du portail).",
        "gen_donjon_defenseur.py", preview)
