#!/usr/bin/env python3
# OBSOLÈTE depuis le 2026-10-17 : la roue a été redessinée par l'utilisateur (25 cases, rouge / noir, numéros dessinés
# dans l'image : roue_v2.png). Ce script numérotait l'ancienne roue de 24 cases : ne plus le lancer.
"""Numéros des cases de la roulette, ajoutés sur l'image fournie (roue_sans_numeros.png, 176 x 221).

    <venv avec Pillow>/python3 numeroter.py roue_sans_numeros.png <sortie.png>

Roue de 24 cases de 15° (centre 87,5 / 79,5), dans le sens horaire depuis le 0 vert en haut : 11 cases rouge /
crème, l'orange en bas, 11 cases rouge / crème. Numéros 1 à 23 mélangés comme sur une vraie roue : rouge =
impair, crème = pair, 12 sur l'orange, jamais deux nombres consécutifs côte à côte. Tous les nombres
sont à l'endroit, centrés sur leur case (choix du 2026-10-17 : d'abord orientés vers l'extérieur comme sur une vraie
roue, mais ceux du bas se lisaient à l'envers).
Seuls les numéros sont ajoutés, le reste de l'image n'est pas touché (le 0 existant est gardé).
"""
import math
import sys

from PIL import Image

SOURCE, SORTIE = sys.argv[1], sys.argv[2]
CX, CY, RAYON = 87.5, 79.5, 45.5          # centre de la roue, rayon du milieu des cases
PAS = 15
ORDRE = [17, 8, 21, 2, 13, 10, 19, 4, 15, 6, 23, 12, 1, 20, 9, 14, 3, 22, 7, 16, 11, 18, 5]  # cases 1 à 23
assert sorted(ORDRE) == list(range(1, 24))
ROUGES = {(0xC0, 0x14, 0x24), (0xC0, 0x10, 0x25)}   # deux nuances de rouge dans les cases
COULEUR_TEXTE = {  # couleur de la case -> couleur du numéro
    (0xC0, 0x14, 0x24): (0xFF, 0xF6, 0xD8, 255),   # rouge : crème
    (0xFA, 0xEB, 0xC0): (0x8A, 0x0A, 0x14, 255),   # crème : rouge sombre
    (0xDF, 0x8E, 0x23): (0x2A, 0x03, 0x06, 255),   # orange : brun très sombre
}
CHIFFRES = {
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "###"], "3": ["###", "..#", ".##", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"], "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"], "9": ["###", "#.#", "###", "..#", "###"],
}

def glyphe(n):
    """Nombre en matrice de 0 / 1 (chiffres 3 x 5 espacés de 1)."""
    t = str(n)
    w = 4 * len(t) - 1
    return w, [[1 if any(0 <= x - k * 4 < 3 and CHIFFRES[c][y][x - k * 4] == "#" for k, c in enumerate(t)) else 0
                for x in range(w)] for y in range(5)]


im = Image.open(SOURCE).convert("RGBA")
px = im.load()
for i, n in enumerate(ORDRE, 1):
    angle = i * PAS                        # degrés, sens horaire depuis le haut
    rad = math.radians(angle - 90)
    mx, my = CX + RAYON * math.cos(rad), CY + RAYON * math.sin(rad)
    fond = px[int(mx), int(my)][:3]
    fonds = ROUGES if fond in ROUGES else {fond}
    couleur = COULEUR_TEXTE.get((0xC0, 0x14, 0x24) if fond in ROUGES else fond)
    if couleur is None:
        raise SystemExit(f"case {i} ({n}) : couleur inattendue %02x%02x%02x" % fond)
    w, g = glyphe(n)
    th = 0.0                               # tous à l'endroit (demande du 2026-10-17)
    for y in range(int(my) - 8, int(my) + 9):
        for x in range(int(mx) - 8, int(mx) + 9):
            # pixel de l'image -> repère du nombre (rotation inverse), chiffre allumé = pixel peint (case seulement)
            dx, dy = x + 0.5 - mx, y + 0.5 - my
            u = dx * math.cos(th) + dy * math.sin(th) + w / 2
            v = -dx * math.sin(th) + dy * math.cos(th) + 2.5
            iu, iv = math.floor(u), math.floor(v)
            if 0 <= iu < w and 0 <= iv < 5 and g[iv][iu] and px[x, y][:3] in fonds:
                px[x, y] = couleur
im.save(SORTIE)
print("23 numéros posés")
