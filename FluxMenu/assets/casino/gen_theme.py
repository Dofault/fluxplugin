#!/usr/bin/env python3
"""Thème casino (menus du casino seulement) : fond générique dessiné par programme.

    <venv avec Pillow>/python3 gen_theme.py <sortie.png> [haut bas]

Fond 176 x 221 (fenêtre entière), cadre de y haut à y bas (par défaut 0 et 220 : plein écran). Dessiné à la
hauteur voulue (pas de compression d'un fond existant : les ampoules restent rondes et régulières).
Couleurs reprises de l'image de référence fournie le 2026-10-17 (bordeaux, or, ampoules crème) :
- cadre de 8 px aux coins arrondis (rayon 18) : liseré sombre, filet d'or, bande de 5 px d'ampoules sur rouge très
  sombre, filet d'or ; chaque pixel prend la couche de sa profondeur depuis le bord du rectangle arrondi ;
- ampoules (halo orangé) réparties à intervalle régulier sur la ligne médiane de la bande, virages compris ;
- intérieur bordeaux tramé en damier, léger dégradé, ombre intérieure le long du cadre, symboles de cartes
  (pique, cœur, carreau, trèfle) en quinconce, à peine plus clairs que le fond.
"""
import sys

from PIL import Image

W, H = 176, 221
SORTIE = sys.argv[1]
HAUT = int(sys.argv[2]) if len(sys.argv) > 2 else 0
BAS = int(sys.argv[3]) if len(sys.argv) > 3 else H - 1


def rgb(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def melange(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


NOIR = rgb("0F0000")
OR_FONCE, OR, OR_CLAIR, OR_REFLET = rgb("8A4A10"), rgb("E0962C"), rgb("FFC860"), rgb("FFE9A8")
BANDE = rgb("2A0306")
ROUGE_VIF = rgb("B7141F")
FOND_HAUT, FOND_BAS = rgb("4A0610"), rgb("3A030B")
OMBRE = rgb("1E0204")
MOTIF = rgb("5A0B18")
AMPOULE = {"c": rgb("FFF6D8"), "b": rgb("FFD27A"), "h": rgb("E8892A")}

CADRE = 8                   # épaisseur du cadre
PAS_AMPOULE = 9

SYMBOLES = {
    "pique": ["..#..", ".###.", "#####", "#####", "..#..", ".###."],
    "coeur": [".#.#.", "#####", "#####", ".###.", "..#..", "....."],
    "carreau": ["..#..", ".###.", "#####", ".###.", "..#..", "....."],
    "trefle": [".###.", ".###.", "#####", "#####", "..#..", ".###."],
}

import math

RAYON = 18
im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px = im.load()
x0, x1, y0, y1 = 0, W - 1, HAUT, BAS


def profondeur(x, y):
    """Distance au bord extérieur du rectangle arrondi (négative = dehors), et le côté éclairé (haut / gauche)."""
    cx = min(max(x, x0 + RAYON), x1 - RAYON)
    cy = min(max(y, y0 + RAYON), y1 - RAYON)
    if cx != x and cy != y:                       # dans un coin : distance au centre de l'arc
        d = RAYON - math.hypot(x - cx, y - cy) + 0.5
        eclaire = (x < cx) or (y < cy and x - cx < cy - y)
        return d, eclaire, True
    d = min(x - x0, x1 - x, y - y0, y1 - y)
    eclaire = d in (x - x0, y - y0)
    return d, eclaire, False


COUCHES = {0: (NOIR, NOIR), 1: (OR, OR_CLAIR), 2: (BANDE, BANDE), 3: (BANDE, BANDE), 4: (BANDE, BANDE),
           5: (BANDE, BANDE), 6: (BANDE, BANDE), 7: (OR_FONCE, OR)}

for y in range(y0, y1 + 1):
    t = (y - y0) / max(1, y1 - y0)
    base = melange(FOND_HAUT, FOND_BAS, t)
    for x in range(x0, x1 + 1):
        d, eclaire, coin = profondeur(x, y)
        if d < 0:
            continue
        couche = int(d)
        if couche in COUCHES:
            px[x, y] = COUCHES[couche][1 if eclaire else 0]
            continue
        c = base if (x + y) % 2 else melange(base, NOIR, 0.08)
        e = d - CADRE                             # ombre intérieure le long du cadre (6 px)
        if e < 6:
            c = melange(c, OMBRE, 0.55 * (1 - e / 6))
        px[x, y] = c

# symboles de cartes en quinconce, tous les 28 px
ordre = ["pique", "coeur", "trefle", "carreau"]
for j, y in enumerate(range(y0 + CADRE + 10, y1 - CADRE - 6, 28)):
    for i, x in enumerate(range(x0 + CADRE + 6 + (14 if j % 2 else 0), x1 - CADRE - 6, 28)):
        motif = SYMBOLES[ordre[(i + j) % 4]]
        for dy, ligne in enumerate(motif):
            for dx, ch in enumerate(ligne):
                if ch == "#":
                    px[x + dx, y + dy] = melange(px[x + dx, y + dy], MOTIF, 0.75)

HALO = rgb("5A1608")


def ampoule(cx, cy):
    """Ampoule 3 x 3 (cœur crème, bords dorés, coins orangés) et halo d'un pixel dans la bande sombre."""
    for dx in (-2, -1, 0, 1, 2):
        for dy in (-2, -1, 0, 1, 2):
            if max(abs(dx), abs(dy)) == 2 and abs(dx) + abs(dy) <= 3 and px[cx + dx, cy + dy] == BANDE:
                px[cx + dx, cy + dy] = HALO
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            role = "c" if dx == dy == 0 else ("b" if dx == 0 or dy == 0 else "h")
            px[cx + dx, cy + dy] = AMPOULE[role]


# ligne médiane de la bande (profondeur 4,5) : 4 côtés droits + 4 quarts de cercle, parcourue à pas régulier
r = RAYON - 4.5
m = 4.5
segments = []                                     # (longueur, fonction t -> point)
def droite(ax, ay, bx, by):
    segments.append((math.hypot(bx - ax, by - ay), lambda t, ax=ax, ay=ay, bx=bx, by=by: (ax + (bx - ax) * t, ay + (by - ay) * t)))
def arc(cx, cy, a0):
    segments.append((math.pi / 2 * r, lambda t, cx=cx, cy=cy, a0=a0: (cx + r * math.cos(a0 + t * math.pi / 2),
                                                                     cy + r * math.sin(a0 + t * math.pi / 2))))
L, R_, T, B = x0 + m, x1 - m, y0 + m, y1 - m
droite(x0 + RAYON, T, x1 - RAYON, T)
arc(x1 - RAYON, y0 + RAYON, -math.pi / 2)
droite(R_, y0 + RAYON, R_, y1 - RAYON)
arc(x1 - RAYON, y1 - RAYON, 0)
droite(x1 - RAYON, B, x0 + RAYON, B)
arc(x0 + RAYON, y1 - RAYON, math.pi / 2)
droite(L, y1 - RAYON, L, y0 + RAYON)
arc(x0 + RAYON, y0 + RAYON, math.pi)
total = sum(s[0] for s in segments)
n = round(total / PAS_AMPOULE)
deja = set()
for k in range(n):
    dist = k * total / n
    for longueur, f in segments:
        if dist <= longueur:
            x, y = f(dist / max(longueur, 1e-9))
            p = (round(x - 0.5), round(y - 0.5))
            if p not in deja:
                deja.add(p)
                ampoule(*p)
            break
        dist -= longueur

im.save(SORTIE)
print("fond casino", im.size, "cadre de", y0, "à", y1, "-", n, "ampoules")
