#!/usr/bin/env python3
"""Plinko (Flux) : fond du plateau et sprites de l'animation, thème casino (2026-10-17).

    <venv avec Pillow>/python3 gen_plinko.py <menu_casino_generique.png> <ItemsAdder/contents/influcasino>

Tout dans le namespace influcasino (comme les autres menus du casino), fichier plinko.yml à part :
- menu_plinko (fenêtre entière 176 x 221) : fond générique du casino (assets/casino/gen_theme.py), pyramide de
  10 rangées de clous dorés, 11 cases de gains (rouge vif aux bords, ivoire au centre), repère de départ doré.
- plinko_bille_y<Y> (bille 5 x 5 dont le haut est à Y), plinko_clou_y<Y> (clou allumé 3 x 3, un par rangée),
  plinko_case (contour lumineux 13 x 16 de la case d'arrivée).
La géométrie doit rester identique à celle de fr.influencraft.flux.Plinko (Java) : ROWS, SX, SY, Y0, CX, cases.
"""
import os
import sys

from PIL import Image

FOND, PACK = sys.argv[1], sys.argv[2]

ROWS, SX, SY, Y0, CX = 10, 14, 13, 48, 88           # clous : rangée k à y = Y0 + SY k, k + 3 clous espacés de SX
CASE_HAUT, CASE_H, CASE_L = 172, 16, 13              # cases : de y 172 à 187, 13 px de large, centrées tous les SX
MULTIPLICATEURS = ["10", "3", "1.5", "1.1", "0.6", "0.4", "0.6", "1.1", "1.5", "3", "10"]
COULEURS = {"10": "D41A28", "3": "E0602C", "1.5": "E0962C", "1.1": "FFC860", "0.6": "FFE9A8", "0.4": "FFF6D8"}
BILLE_Y = range(30, 182)                              # haut de la bille (centre - 2)


def rgb(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def plus_clair(c, f):
    return tuple(min(255, int(v + (255 - v) * f)) for v in c[:3]) + (255,)


def plus_sombre(c, f):
    return tuple(int(v * (1 - f)) for v in c[:3]) + (255,)


def clous():
    return [(CX + (i - (k + 2) / 2) * SX, Y0 + SY * k, k) for k in range(ROWS) for i in range(k + 3)]


def dessin(motif, couleurs):
    """Image depuis un motif de caractères (« . » = transparent)."""
    h, w = len(motif), len(motif[0])
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y, ligne in enumerate(motif):
        for x, ch in enumerate(ligne):
            if ch != ".":
                im.putpixel((x, y), couleurs[ch])
    return im


CLOU = dessin(["aba", "bcb", "aba"], {"a": rgb("6A3410"), "b": rgb("C07820"), "c": rgb("FFC860")})
CLOU_ALLUME = dessin(["aba", "bcb", "aba"], {"a": rgb("E0962C"), "b": rgb("FFF6D8"), "c": rgb("FFFFFF")})
BILLE = dessin([".ooo.", "ohbbo", "obbso", "obsso", ".ooo."],
               {"o": rgb("2A0306"), "h": rgb("FFFFFF"), "b": rgb("FFF6E8"), "s": rgb("E8D2B0")})

# Police 3 x 5 des multiplicateurs (le point fait 1 px de large)
CHIFFRES = {
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "3": ["###", "..#", ".##", "..#", "###"], "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "###", "..#", "###"], "6": ["###", "#..", "###", "#.#", "###"],
    ".": [".", ".", ".", ".", "#"],
}


def texte(im, txt, cx, y, couleur):
    largeur = sum(len(CHIFFRES[c][0]) for c in txt) + len(txt) - 1
    x = cx - largeur // 2
    for c in txt:
        g = CHIFFRES[c]
        for dy, ligne in enumerate(g):
            for dx, ch in enumerate(ligne):
                if ch == "#":
                    im.putpixel((x + dx, y + dy), couleur)
        x += len(g[0]) + 1


# ------------------------------------------------------------------ fond
fond = Image.open(FOND).convert("RGBA")
for px, py, k in clous():
    fond.alpha_composite(CLOU, (int(px) - 1, int(py) - 1))
# repère de départ : petite flèche dorée sous le bouton
for dy, w in enumerate((5, 3, 1)):
    for dx in range(w):
        fond.putpixel((CX - w // 2 + dx, 37 + dy), rgb("FFC860"))
for j, m in enumerate(MULTIPLICATEURS):
    c = rgb(COULEURS[m])
    cx = CX + (j - 5) * SX
    x0 = cx - CASE_L // 2
    for y in range(CASE_HAUT, CASE_HAUT + CASE_H):
        for x in range(x0, x0 + CASE_L):
            coul = c
            if y == CASE_HAUT:
                coul = plus_clair(c, 0.45)
            elif y == CASE_HAUT + CASE_H - 1 or x == x0 + CASE_L - 1:
                coul = plus_sombre(c, 0.35)
            elif x == x0:
                coul = plus_clair(c, 0.2)
            fond.putpixel((x, y), coul)
    texte(fond, m, cx, CASE_HAUT + 9, rgb("2A0306"))
os.makedirs(os.path.join(PACK, "textures", "font"), exist_ok=True)
fond.save(os.path.join(PACK, "textures", "font", "menu_plinko.png"))

# ------------------------------------------------------------------ sprites
os.makedirs(os.path.join(PACK, "textures", "plinko"), exist_ok=True)
BILLE.save(os.path.join(PACK, "textures", "plinko", "bille.png"))
CLOU_ALLUME.save(os.path.join(PACK, "textures", "plinko", "clou.png"))
case = Image.new("RGBA", (CASE_L, CASE_H), (0, 0, 0, 0))
for x in range(CASE_L):
    for y in range(CASE_H):
        if x in (0, CASE_L - 1) or y in (0, CASE_H - 1):
            case.putpixel((x, y), rgb("FFFFFF"))
        elif x in (1, CASE_L - 2) or y in (1, CASE_H - 2):
            case.putpixel((x, y), rgb("FFFFFF", 140))
case.save(os.path.join(PACK, "textures", "plinko", "case.png"))

L = ["info:", "  namespace: influcasino", "",
     "# Généré par plugins/fluxplugin/FluxCasino/assets/plinko/gen_plinko.py, ne pas éditer à la main.",
     "# Plinko : fond du plateau et sprites posés au pixel près dans le titre (y_position = 13 - haut).", "",
     "font_images:",
     "  menu_plinko:", "    suggest_in_command: false", "    show_in_gui: false", "    path: font/menu_plinko.png",
     "    scale_ratio: 221", "    y_position: 13"]


def glyphe(nom, chemin, h, haut):
    L.extend([f"  {nom}:", "    suggest_in_command: false", "    show_in_gui: false",
              f"    path: {chemin}", f"    scale_ratio: {h}", f"    y_position: {13 - haut}"])


for y in BILLE_Y:
    glyphe(f"plinko_bille_y{y}", "plinko/bille.png", 5, y)
for k in range(ROWS):
    glyphe(f"plinko_clou_y{Y0 + SY * k - 1}", "plinko/clou.png", 3, Y0 + SY * k - 1)
glyphe("plinko_case", "plinko/case.png", CASE_H, CASE_HAUT)
open(os.path.join(PACK, "plinko.yml"), "w", encoding="utf-8").write("\n".join(L) + "\n")
print(len(BILLE_Y) + ROWS + 1, "glyphes")
