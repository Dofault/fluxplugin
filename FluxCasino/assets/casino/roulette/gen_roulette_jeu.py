#!/usr/bin/env python3
"""Roulette jouable (Flux) : fond, bille, jetons de pari (2026-10-17, roue redessinée de 25 cases).

    <venv avec Pillow>/python3 gen_roulette_jeu.py <menu_casino_generique.png> <ItemsAdder/contents/influcasino>

À lancer après gen_roulette.py : la roue (textures/roulette/roue.png, 124 x 124, haut gauche en (26, 18)) est
relue dans le pack. La roue reste immobile ; c'est la bille qui tourne sur la piste extérieure, ralentit, tombe
dans sa case sans rebondir et s'y pose, puis ne bouge plus (aucune animation de case : la bille dans son trou
suffit). Fichier roulette_jeu.yml à part (roulette.yml reste celui de gen_roulette.py). Tout dans le namespace
influcasino :
- menu_roulette (fenêtre 176 x 221) : fond générique, table de pari sous la roue (cases des boutons, gains).
- roulette_bille_y<Y> / roulette_traine_y<Y> : la bille (5 x 5, haut à Y) et son ombre de vitesse (même forme, translucide).
- objets pari_rouge, pari_noir (jetons de pari) et pari_n0 à pari_n24 (jeton du numéro choisi, couleur de sa case).

La géométrie (centre, rayons, ordre des numéros) doit rester identique à fr.influencraft.flux.Roulette (Java).
"""
import math
import os
import sys

from PIL import Image, ImageDraw

FOND, PACK = sys.argv[1], sys.argv[2]

# ------------------------------------------------------------------ géométrie de la roue
X0, Y0, TAILLE = 26, 18, 124                 # roue.png : haut gauche et taille
CX, CY = 87.5, 79.5                          # centre (coordonnées continues de la fenêtre)
CASES_NB = 25                                # le 0 vert en haut, puis 24 numéros dans le sens horaire
PAS = 360 / CASES_NB                         # degrés par case (14,4)
# numéros de la roue de l'utilisateur, relevés case par case sur l'image (0 = vert en haut), sens horaire
NUMEROS = [0, 17, 8, 21, 2, 13, 10, 19, 4, 15, 6, 23, 12, 1, 20, 9, 14, 3, 22, 7, 16, 11, 18, 5, 24]
assert sorted(NUMEROS) == list(range(CASES_NB))
BILLE_Y = range(19, 137)                      # haut de la bille (centre - 2,5) : piste de rayon 55,5 et chute dans la case


def rgb(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


OR_FONCE, OR, OR_VIF = rgb("B0681E"), rgb("E0962C"), rgb("FFD94D")
CONTOUR = rgb("1A0505")
# couleur de chaque numéro, comme la roue : impair rouge, pair noir, 0 vert (le blanc de l'ancienne roue est
# devenu noir)
COULEURS = {"rouge": (rgb("C01424"), rgb("8A0A14"), rgb("FFF6D8")),       # corps, anneau, texte
            "noir": (rgb("1C1214"), rgb("5A4A4C"), rgb("FFF6D8")),
            "vert": (rgb("09851C"), rgb("04520F"), rgb("FFF6D8"))}


def couleur_de(n):
    return "vert" if n == 0 else "rouge" if n % 2 else "noir"


# ------------------------------------------------------------------ petite police 3 x 5
POLICE = {
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "###"], "3": ["###", "..#", ".##", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"], "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"], "9": ["###", "#.#", "###", "..#", "###"],
    ".": [".", ".", ".", ".", "#"], "x": ["...", "#.#", ".#.", "#.#", "..."],
}


def texte(im, txt, x, y, couleur, ech=1, ombre=None):
    for col, dec in ((ombre, 1), (couleur, 0)) if ombre else ((couleur, 0),):
        px = x
        for c in txt:
            g = POLICE[c]
            for dy, ligne in enumerate(g):
                for dx, ch in enumerate(ligne):
                    if ch == "#":
                        for ex in range(ech):
                            for ey in range(ech):
                                xx, yy = px + dx * ech + ex + dec, y + dy * ech + ey + dec
                                if 0 <= xx < im.width and 0 <= yy < im.height:
                                    im.putpixel((xx, yy), col)
            px += (len(g[0]) + 1) * ech
    return px


def largeur_texte(txt, ech=1):
    return sum((len(POLICE[c][0]) + 1) * ech for c in txt) - ech


# ------------------------------------------------------------------ fond : table de pari sous la roue
fond = Image.open(FOND).convert("RGBA")
d = ImageDraw.Draw(fond)
PX0, PY0, PX1, PY1 = 12, 147, 163, 205
d.rectangle([PX0, PY0, PX1, PY1], fill=OR_FONCE)
d.rectangle([PX0 + 1, PY0 + 1, PX1 - 1, PY1 - 1], fill=rgb("1A0308"))
d.line([(PX0 + 1, PY0 + 1), (PX1 - 1, PY0 + 1)], fill=OR)
# cases des boutons (18 x 18 par case de coffre, le bouton fait 16 x 16 au centre) : rangée du haut = mise (y 157),
# rangée du dessous = paris (y 175)
CASES = [(3, 157), (4, 157), (5, 157), (1, 175), (2, 175), (4, 175), (5, 175), (6, 175)]
for col, y in CASES:
    x = 8 + 18 * col
    d.rectangle([x - 1, y - 1, x + 16, y + 16], fill=OR_FONCE)
    d.rectangle([x, y, x + 15, y + 15], fill=rgb("2A0306"))
# gains, sous chaque pari
for col, mult in ((1, "x2"), (2, "x2"), (5, "x24")):
    w = largeur_texte(mult)
    texte(fond, mult, 8 + 18 * col + 8 - w // 2, 195, OR_VIF)
os.makedirs(os.path.join(PACK, "textures", "font"), exist_ok=True)
fond.save(os.path.join(PACK, "textures", "font", "menu_roulette.png"))

# ------------------------------------------------------------------ la roue (relue dans le pack)
roue = Image.open(os.path.join(PACK, "textures", "roulette", "roue.png")).convert("RGBA")
assert roue.size == (TAILLE, TAILLE), roue.size
sp = os.path.join(PACK, "textures", "roulette")


# ------------------------------------------------------------------ bille et ombre de vitesse
def bille(alpha):
    motif = [".ooo.", "ohbbo", "obbso", "obsso", ".ooo."]
    coul = {"o": rgb("2A0306"), "h": rgb("FFFFFF"), "b": rgb("FFF6E8"), "s": rgb("E8D2B0")}
    im = Image.new("RGBA", (5, 5), (0, 0, 0, 0))
    for y, ligne in enumerate(motif):
        for x, ch in enumerate(ligne):
            if ch != ".":
                c = coul[ch]
                im.putpixel((x, y), c[:3] + (alpha,))
    return im


bille(255).save(os.path.join(sp, "bille.png"))
bille(120).save(os.path.join(sp, "traine.png"))


# ------------------------------------------------------------------ jetons de pari (et icône du numéro tombé)
def disque(taille, rayon, corps, bord, anneau=None):
    im = Image.new("RGBA", (taille, taille), (0, 0, 0, 0))
    c = (taille - 1) / 2
    for y in range(taille):
        for x in range(taille):
            dd = math.hypot(x - c, y - c)
            if dd > rayon:
                continue
            if dd > rayon - 1:
                im.putpixel((x, y), CONTOUR)
            elif anneau and dd > rayon - 2.6:
                im.putpixel((x, y), anneau)
            elif dd > rayon - 3.6 and not anneau:
                im.putpixel((x, y), bord)
            else:
                im.putpixel((x, y), corps)
    return im


def jeton_numero(n):
    """Jeton 16 x 16 du numéro n : couleur de sa case, nombre centré."""
    corps, bord, txt = COULEURS[couleur_de(n)]
    im = disque(16, 7.9, corps, bord)
    t = str(n)
    w = largeur_texte(t)
    texte(im, t, (16 - w) // 2, 5, txt)
    return im


def jeton_couleur(nom):
    """Jeton de pari « rouge » ou « noir » : couleur de la case, étoile au centre."""
    corps, bord, txt = COULEURS[nom]
    im = disque(16, 7.9, corps, bord)
    for (x, y) in ((7, 4), (8, 4), (7, 5), (8, 5), (4, 6), (5, 6), (6, 6), (7, 6), (8, 6), (9, 6), (10, 6), (11, 6),
                   (5, 7), (6, 7), (7, 7), (8, 7), (9, 7), (10, 7), (6, 8), (7, 8), (8, 8), (9, 8),
                   (6, 9), (7, 9), (8, 9), (9, 9), (5, 10), (6, 10), (9, 10), (10, 10), (5, 11), (10, 11)):
        im.putpixel((x, y), txt)
    return im


os.makedirs(os.path.join(PACK, "textures", "item"), exist_ok=True)
for n in range(CASES_NB):
    jeton_numero(n).save(os.path.join(PACK, "textures", "item", f"pari_n{n}.png"))
jeton_couleur("rouge").save(os.path.join(PACK, "textures", "item", "pari_rouge.png"))
jeton_couleur("noir").save(os.path.join(PACK, "textures", "item", "pari_noir.png"))

# ------------------------------------------------------------------ config ItemsAdder
L = ["info:", "  namespace: influcasino", "",
     "# Généré par plugins/fluxplugin/FluxCasino/assets/casino/roulette/gen_roulette_jeu.py, ne pas éditer à la main.",
     "# Roulette jouable : fond, bille et ombre de vitesse, jetons de pari.",
     "# Glyphes posés au pixel près dans le titre (y_position = 13 - haut).", "",
     "items:"]
for nom in ["pari_rouge", "pari_noir"] + [f"pari_n{n}" for n in range(CASES_NB)]:
    L += [f"  {nom}:", "    display_name: ' '", "    resource:", "      material: PAPER", "      generate: true",
          "      textures:", f"      - item/{nom}.png"]
L += ["", "font_images:", "  menu_roulette:", "    suggest_in_command: false", "    show_in_gui: false",
      "    path: font/menu_roulette.png", "    scale_ratio: 221", "    y_position: 13"]


def glyphe(nom, chemin, h, haut):
    L.extend([f"  {nom}:", "    suggest_in_command: false", "    show_in_gui: false",
              f"    path: {chemin}", f"    scale_ratio: {h}", f"    y_position: {13 - haut}"])


for y in BILLE_Y:
    glyphe(f"roulette_bille_y{y}", "roulette/bille.png", 5, y)
    glyphe(f"roulette_traine_y{y}", "roulette/traine.png", 5, y)
open(os.path.join(PACK, "roulette_jeu.yml"), "w", encoding="utf-8").write("\n".join(L) + "\n")

print("glyphes :", len(BILLE_Y) * 2)
