#!/usr/bin/env python3
"""Pixel art de la Loutre des Marées pour l'hologramme du portail Pêcheur (lobby RPG), namespace influhologrammes.

    python3 gen_donjon_pecheur.py [dossier ItemsAdder/contents] [--apercu image.png]   (Labs par défaut ; Pillow requis)
    puis /iazip, puis scripts/typewriter/gen_lobby_rpg.py (qui lit les caractères dans le cache d'ItemsAdder)

  - donjon_pecheur_loutre : la loutre du boss (palette de docs/technique/boss/loutre-marees.md) qui sort de l'eau,
    un poisson doré dans les pattes, contour marine épais et eau au dégradé du logo « DONJON PÊCHEUR » ; l'eau
    finit en pointe des deux côtés. 148 x 61 (jamais plus de 256 px de côté) ;
  - donjon_pecheur_logo : le logo sources/donjon_pecheur_logo.png (1024 x 416) réduit à 208 x 84 : à 256 de large, la
    limite des glyphes, le logo s'affichait décalé sur le côté en jeu (2026-11-14).
Les deux images sont affichées l'une sur l'autre (deux hologrammes, la loutre au-dessus) : l'eau de la loutre a la
largeur du mot « DONJON » (85 % du logo), d'où les deux hauteurs ci-dessous.
Écrit son propre fichier de config (donjon_pecheur.yml) : ceux des autres scripts restent à eux.
"""
import math
import random
from pathlib import Path

from pixlib import Canvas, art_height, args, hexc, publish

OUT, preview = args()
LOGO_SIZE = (208, 84)  # under 256 wide (pixlib)
LOGO_HEIGHT = 48     # displayed height of the logo in font pixels (scale_ratio): 4.4 blocks wide at the hologram's scale 1.5
DONJON = 868 / 1024  # width of the word DONJON in the logo (x 78 to 945 of the source), the otter's water spans it

W, H = 150, 76
LINE = hexc('15133A')          # logo outline navy
FUR = [hexc('142F42'), hexc('1F4A60'), hexc('2F6E82'), hexc('579EAA')]  # dark -> light
PALE = [hexc('526F78'), hexc('7E9A9F'), hexc('9BB6B5'), hexc('C9DCDA')]
MARK = [hexc('287E83'), hexc('77D9CB')]
EYEW = hexc('E8F2F0'); IRIS = hexc('174F65'); PUPIL = hexc('101B25'); GLINT = hexc('FFFFFF')
NOSE = hexc('18272F'); NOSE_HI = hexc('4A6470'); MOUTH = hexc('263B43')
GOLD = [hexc('B86E00'), hexc('F0A800'), hexc('FFD94D'), hexc('FFF1A8')]
WATER = [hexc('1E4FA0'), hexc('2A7BC8'), hexc('35C8EE'), hexc('8FE6FA')]
FOAM = hexc('E6FAFF')
cv = Canvas(W, H, LINE)
cv.ox = 25  # otter drawn in a 100 px frame, centred
put, ellipse, shape, img = cv.put, cv.ellipse, cv.shape, cv.img

# ---- body (behind the head) ----
body = ellipse(50, 63, 24, 21)
shape(body, FUR)
belly = ellipse(50, 63, 14, 16)
shape(belly, PALE, outline=False)
# turquoise wave marks on the shoulders (like the boss's painted waves)
for ox, flip in ((33, 1), (67, -1)):
    for i, (dx, dy) in enumerate([(0,0),(1,-1),(2,-2),(3,-1),(4,0),(5,-1),(6,-2)]):
        put(ox + flip*dx - (3*flip), 52 + dy, MARK[1] if i % 3 != 2 else MARK[0])

# ---- ears ----
for ex in (29, 71):
    e = ellipse(ex, 21, 4, 3.6)
    shape(e, FUR)
    for p in ellipse(ex, 21.6, 1.8, 1.6): put(*p, FUR[0])

# ---- head ----
head = ellipse(50, 31, 26, 15.5)
shape(head, FUR)
# light stripe on the top of the head (light back line of the model)
for x in range(44, 57):
    put(x, 16, FUR[3])
# little crest on the forehead (turquoise)
for (x, y) in ((49,18),(50,18),(50,17),(51,18)): put(x, y, MARK[1])

# ---- muzzle: two puffy cheeks + chin ----
chin = ellipse(50, 43, 9, 4.5)
shape(chin, PALE)
mask = ellipse(50, 37, 19, 8.5) & head
shape(mask, PALE[:3], outline=False)
cheeks = ellipse(44, 38, 8.5, 6) | ellipse(56, 38, 8.5, 6)
shape(cheeks, PALE)
# whisker dots
for (x, y) in ((40,37),(43,39),(39,40),(60,37),(57,39),(61,40)): put(x, y, PALE[0])
# whiskers sticking out of the face
for side in (-1, 1):
    for k, slope in enumerate((-0.25, 0.05, 0.3)):
        for i in range(12):
            x = 50 + side * (17 + i); y = round(37 + k*2 + slope * i)
            x = 50 + side * (17 + i)
            put(x, y, GLINT if i < 8 else PALE[3])

# ---- nose ----
nose = [(47,32),(48,32),(49,32),(50,32),(51,32),(52,32),(53,32),
        (47,33),(48,33),(49,33),(50,33),(51,33),(52,33),(53,33),
        (48,34),(49,34),(50,34),(51,34),(52,34),(49,35),(50,35),(51,35),(50,36)]
for p in nose: put(*p, NOSE)
for p in ((48,32),(49,32)): put(*p, NOSE_HI)
for x in range(46, 55): put(x, 31, LINE)
put(46, 32, LINE); put(54, 32, LINE)
# mouth: "w" line + two fangs (angry boss)
for (x, y) in ((50,37),(50,38),(49,39),(48,40),(47,40),(46,39),(51,39),(52,40),(53,40),(54,39)): put(x, y, MOUTH)
for (x, y) in ((47,41),(47,42),(53,41),(53,42)): put(x, y, EYEW)
for (x, y) in ((46,41),(48,41),(46,42),(48,42),(47,43),(52,41),(54,41),(52,42),(54,42),(53,43)): put(x, y, LINE)

# ---- angry eyes ----
def eye(cx, flip):
    # squinted white: 7 wide, top edge cut by the brow
    rows = {0: range(-2, 3), 1: range(-3, 4), 2: range(-3, 4), 3: range(-2, 3)}
    for dy, rng in rows.items():
        for dx in rng:
            # slant: inner corner lower (V shape)
            if dy == 0 and dx * flip > 0: continue
            put(cx + dx, 25 + dy, EYEW)
    for dy in range(1, 4):
        for dx in (0, flip*-1):
            put(cx + dx - flip, 25 + dy, IRIS)  # iris looks toward the center
    put(cx - flip, 26, PUPIL); put(cx - flip, 27, PUPIL)
    put(cx - 2*flip, 26, GLINT)
    # outline around the eye
    for dx in range(-4, 5): put(cx + dx, 29, LINE)
    for dy in range(25, 29): put(cx - 4, dy, LINE); put(cx + 4, dy, LINE)
    # thick slanted brow (V): high at the outside, low at the inside
    for i in range(10):
        x = cx + flip * (5 - i)
        y = 21 + (i * 4) // 9
        put(x, y, LINE); put(x, y + 1, LINE)
        if i < 9: put(x, y + 2, FUR[0])
eye(40, -1)
eye(60, 1)

# ---- golden fish held in the paws ----
fish = {(x, y) for x in range(37, 64) for y in range(48, 63)
        if abs(y + .5 - 55.5) <= 5.2 * math.sin(math.pi * (x + .5 - 37) / 27) ** .7}
tail = {(x, y) for x in range(61, 70) for y in range(48, 64)
        if abs(y + .5 - 55.5) <= (x - 60) * .9 and not (x > 66 and abs(y + .5 - 55.5) < (x - 65))}
fin = {(x, y) for x in range(47, 56) for y in range(47, 51) if y >= 50 - (x - 47) // 2 and y <= 50}
shape(tail | fin, GOLD)
shape(fish, GOLD)
put(41, 54, PUPIL); put(41, 53, GLINT); put(38, 56, LINE); put(39, 56, LINE)  # eye, mouth
for y in range(51, 60): put(45, y, GOLD[0]) if (45, y) in fish and (45,y-1) in fish else None  # gill
for x in range(49, 59, 3): put(x, 56, GOLD[1]); put(x+1, 55, GOLD[3])  # scales sparkle
# paws gripping the fish
for px in (36, 57):
    paw = ellipse(px, 56, 4.5, 4)
    shape(paw, FUR)
    for k in range(3): put(px - 2 + 2*k, 59, LINE)  # finger lines

cv.ox = 0
OXW = 25  # keep the wave phase under the otter as before
# ---- water (logo blue, sparkles, wavy foam) ----
top = lambda x: 64 + round(2 * math.sin((x - OXW) / 5.5))
# tapered ends: the sheet of water thins out to a point on both sides
def half(x):
    u = min(1.0, abs((x + .5 - 75) / 73))
    return 6.2 * (1 - u ** 3) ** .55
water = {(x, y) for x in range(W) for y in range(H - 1)
         if y >= max(top(x), 69 - half(x)) and y <= 69 + half(x) and half(x) > .6}
for (x, y) in water:
    for dx, dy in ((1,0),(-1,0),(0,1),(0,-1)):
        if (x+dx, y+dy) not in water and 0 <= y+dy < H: put(x+dx, y+dy, LINE)
for (x, y) in water:
    d = y - top(x)
    c = WATER[3] if d == 0 else WATER[2] if d < 3 else WATER[1] if d < 7 else WATER[0]
    put(x, y, c)
# foam crests
for x in range(W):
    if (x, top(x)) in water and math.sin((x - OXW) / 5.5) > 0.6: put(x, top(x), FOAM)
# sparkles like the logo letters
random.seed(7)
for _ in range(26):
    x = random.randrange(4, 146); y = random.randrange(67, 74)
    if (x, y) in water and (x+1, y+1) in water:
        put(x, y, WATER[3])
        if random.random() < .4: put(x+1, y, WATER[3]); put(x, y+1, WATER[3]); put(x+1, y+1, WATER[3])

# spray at both tips
for side in (-1, 1):
    for (dx, dy, c) in ((4, -3, FOAM), (6, -5, WATER[3]), (2, -6, WATER[2]), (7, -1, WATER[3])):
        put((1 if side < 0 else W - 2) - side * (12 - dx), 66 + dy, c)
art = cv.cropped()
height = art_height(art, LOGO_SIZE, LOGO_HEIGHT, DONJON)
publish(OUT, "donjon_pecheur", "loutre", art, height, Path(__file__).resolve().parent / "sources" / "donjon_pecheur_logo.png",
        LOGO_SIZE, LOGO_HEIGHT, "Loutre des Marées au-dessus du logo du portail Pêcheur (lobby RPG, page Typewriter lobby_rpg).",
        "Logo DONJON PÊCHEUR, sous la loutre (remplace le nom en texte du portail).", "gen_donjon_pecheur.py", preview)
