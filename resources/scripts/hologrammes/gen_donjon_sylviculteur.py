#!/usr/bin/env python3
"""Pixel art de l'Araignée Sylvestre pour l'hologramme du portail Bûcheron (lobby RPG), namespace influhologrammes.

    python3 gen_donjon_sylviculteur.py [dossier ItemsAdder/contents] [--apercu image.png]   (Labs par défaut ; Pillow requis)
    puis /iazip, puis scripts/typewriter/gen_lobby_rpg.py (qui lit les caractères dans le cache d'ItemsAdder)

  - donjon_sylviculteur_araignee : l'araignée du boss vue de face (docs/technique/boss/araignee-sylvestre.md) : souche
    pour céphalothorax (cernes sur le dessus), six yeux ambre, abdomen en pomme de pin, pattes en branches moussues,
    champignons sur le dos ; posée sur une bande d'herbe et de terre aux couleurs du logo, effilée des deux côtés ;
  - donjon_sylviculteur_logo : le logo sources/donjon_bucheron_logo.png (1024 x 268, « DONJON » de l'ancien logo et
    « BÛCHERON » dessiné par gen_logo_bucheron.py, 2026-11-16) réduit à 208 x 54.
Les deux images sont affichées l'une sur l'autre (deux hologrammes, l'araignée au-dessus) : la bande d'herbe a la
largeur du mot « DONJON » (54 % du logo). Même taille de logo affichée que le portail Pêcheur (4,4 blocs de large).
"""
import math
import random
from pathlib import Path

from pixlib import Canvas, art_height, args, hexc, publish

OUT, preview = args()
LOGO_SIZE = (208, 54)  # under 256 wide (pixlib)
LOGO_HEIGHT = 31       # 4.4 blocks wide at the hologram's scale 1.5, as the Pêcheur logo
DONJON = 557 / 1024    # width of the word DONJON in the logo (x 233 to 790 of the source)

LINE = hexc('2B040C')  # outline of the logo
WOOD = [hexc('3E2412'), hexc('5C3A1E'), hexc('7E5530'), hexc('A27548')]
WOOD_BACK = [hexc('2E1A0D'), hexc('4A2E18'), hexc('664226'), hexc('85603B')]
RING = [hexc('8A6440'), hexc('B08A5C'), hexc('CDA877'), hexc('E3C796')]
CONE = [hexc('4A2A14'), hexc('6E4424'), hexc('95633A'), hexc('B88352')]
MOSS = [hexc('3D6B2A'), hexc('5E9440'), hexc('8CBF5C')]
EYE = [hexc('B86E00'), hexc('F0A800'), hexc('FFD94D'), hexc('FFF1A8')]
SOCKET = hexc('1A0A05')
CAP = [hexc('8C7458'), hexc('B39A78'), hexc('D9C6A5'), hexc('EFE4CC')]
STEM = [hexc('BDB4A0'), hexc('E6E0D0')]
GRASS = [hexc('2C8904'), hexc('4F9810'), hexc('79C10F'), hexc('93CE13')]
DIRT = [hexc('50210A'), hexc('663208'), hexc('763E09'), hexc('864511')]

W, H = 100, 64
cv = Canvas(W, H, LINE)
put, ellipse, shape, seg = cv.put, cv.ellipse, cv.shape, cv.segment
rnd = random.Random(11)


def mirror(mask):
    return {(W - 1 - x, y) for (x, y) in mask}


def bark(mask, ramp, density=.18):
    """Vertical streaks of the bark, a darker pixel now and then."""
    for (x, y) in mask:
        if (x * 7) % 5 == 0 and rnd.random() < .6 and (x, y - 1) in mask:
            put(x, y, ramp[1])
        elif rnd.random() < density * .3:
            put(x, y, ramp[0])


def moss(mask, top_rows=2, chance=.55):
    tops = {}
    for (x, y) in mask:
        tops[x] = min(tops.get(x, y), y)
    for x, y0 in tops.items():
        for k in range(top_rows):
            if rnd.random() < chance - k * .2:
                put(x, y0 + k, MOSS[2] if k == 0 else MOSS[1])


def leg(attach, knee, foot, ramp, front):
    """Branch leg: thick upper part, knot at the knee (moss on top), thinner lower part ending in a root tip."""
    mid = ((knee[0] * 2 + foot[0]) / 3, (knee[1] * 2 + foot[1]) / 3)
    upper = seg(attach, knee, 3.2 if front else 2.8)
    lower = seg(knee, mid, 2.6 if front else 2.3) | seg(mid, foot, 1.6)
    both = upper | lower
    shape(both, ramp)
    bark(both, ramp)
    knot = ellipse(knee[0] + .5, knee[1] + .5, 3.6 if front else 3.1, 3.2 if front else 2.8)
    shape(knot, ramp)
    moss(knot, 2, .8)
    return both | knot


# legs of the right side (mirrored for the left): attach on the body, knee high and out, foot in the grass
BACK_LEGS = [((60, 24), (76, 6), (85, 55)), ((62, 29), (90, 15), (97, 55))]
FRONT_LEGS = [((62, 32), (81, 24), (90, 56)), ((62, 41), (73, 36), (76, 56))]


def side_legs(legs, ramp, front):
    for attach, knee, foot in legs:
        for flip in (False, True):
            f = (lambda p: (W - p[0], p[1])) if flip else (lambda p: p)
            leg(f(attach), f(knee), f(foot), ramp, front)


side_legs(BACK_LEGS, WOOD_BACK, False)

# ---- abdomen: a pine cone of bark plates, mushrooms and a stick on it ----
stick = seg((53, 9), (58, 1), 1.1)
shape(stick, WOOD_BACK)
abdomen = ellipse(50, 17, 18, 11.5)
shape(abdomen, CONE)
for (x, y) in abdomen:
    row = (y - 7) // 4
    if (y - 7) % 4 == 0:
        put(x, y, CONE[0])                         # bottom edge of a row of plates
    elif (x + row * 4) % 8 == 0:
        put(x, y, CONE[1])                         # side edge of a plate
    elif (y - 7) % 4 == 1 and (x + row * 4) % 8 in (2, 3, 4):
        put(x, y, CONE[3])                         # lit top edge of a plate
moss(abdomen, 1, .35)
for cx, top in ((40, 3), (61, 5)):
    stem = {(x, y) for x in range(cx - 1, cx + 1) for y in range(top + 2, top + 6)}
    shape(stem, STEM, light=False)
    cap = ellipse(cx, top + 2, 4, 2.4)
    shape(cap, CAP)

side_legs(FRONT_LEGS, WOOD, True)  # behind the stump: they come out of its sides

# ---- cephalothorax: a stump, the cut with its rings on top ----
stump = {(x, y) for x in range(33, 67) for y in range(22, 48)
         if not ((x in (33, 66)) and (y in (22, 47))) and not (y >= 45 and (x < 35 or x > 64))}
shape(stump, WOOD)
bark(stump, WOOD)
cut = ellipse(50, 25, 16.5, 3.6)
for (x, y) in cut:
    d = math.hypot((x + .5 - 50) / 16.5, (y + .5 - 25) / 3.6)
    put(x, y, RING[3] if d < .2 else RING[1] if int(d * 5) % 2 else RING[2])
for x in range(34, 66):                            # dark lip under the cut
    if (x, 29) in stump:
        put(x, 29, WOOD[0])
moss({(x, y) for (x, y) in stump if y >= 30}, 2, .45)

# ---- six amber eyes: two big ones in the middle, two small above, two small outside; angry brows ----
def eye(cx, cy, rx, ry):
    sock = ellipse(cx, cy, rx + 1.2, ry + 1.1)
    for p in sock:
        put(*p, SOCKET)
    iris = ellipse(cx, cy, rx, ry)
    for (x, y) in iris:
        put(x, y, EYE[2] if y < cy - .5 else EYE[1])
    put(int(cx - rx * .5), int(cy - ry * .4), EYE[3])


for flip in (1, -1):
    eye(50 + flip * 6.5, 39, 3.3, 2.8)
    eye(50 + flip * 3, 33, 1.6, 1.4)
    eye(50 + flip * 13, 36, 1.9, 1.6)
    for i in range(8):                             # brow, low towards the middle
        x = 50 + flip * (3 + i)
        y = 36 - i // 3
        put(x, y, SOCKET)
        put(x, y - 1, SOCKET)

# ---- fangs under the face ----
for flip in (1, -1):
    fang = seg((50 + flip * 4, 45), (50 + flip * 3, 51), 1.4)
    shape(fang, RING)
    put(50 + flip * 3, 51, SOCKET)


# ---- grass and earth under the feet, like the logo's letters, thinning out on both sides ----
def half(x):
    u = min(1.0, abs((x + .5 - 50) / 49))
    return 4.6 * (1 - u ** 3) ** .55


top = lambda x: 54 - (1 if (x * 3) % 7 == 0 else 0)
ground = {(x, y) for x in range(W) for y in range(H - 1)
          if half(x) > .6 and max(top(x), 58 - half(x)) <= y <= 58 + half(x)}
for (x, y) in ground:
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if (x + dx, y + dy) not in ground:
            put(x + dx, y + dy, LINE)
for (x, y) in ground:
    d = y - min(yy for (xx, yy) in ground if xx == x)
    c = GRASS[3] if d == 0 else GRASS[2] if d == 1 else GRASS[1] if d == 2 else DIRT[2] if d < 6 else DIRT[1]
    if d >= 3 and rnd.random() < .18:
        c = DIRT[3] if rnd.random() < .5 else DIRT[0]
    if d in (1, 2) and rnd.random() < .2:
        c = GRASS[0]
    put(x, y, c)

art = cv.cropped()
publish(OUT, "donjon_sylviculteur", "araignee", art, art_height(art, LOGO_SIZE, LOGO_HEIGHT, DONJON),
        Path(__file__).resolve().parent / "sources" / "donjon_bucheron_logo.png", LOGO_SIZE, LOGO_HEIGHT,
        "Araignée Sylvestre au-dessus du logo du portail Bûcheron (lobby RPG, page Typewriter lobby_rpg).",
        "Logo DONJON BÛCHERON, sous l'araignée (remplace le nom en texte du portail).",
        "gen_donjon_sylviculteur.py", preview)
