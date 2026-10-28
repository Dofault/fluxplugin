"""Fond du menu Récompenses : descend la bande du temps de jeu d'une rangée (2026-10-28), sur l'image retouchée
par l'utilisateur. Bande connexion : rangée 6 (y 138-155), bande temps de jeu : rangée 8 (y 174-191), rangée 7
libre entre les deux, bas du cadre 18 px plus bas.

Usage : python decale_temps.py <menu_recompenses.png> <sortie>  (Pillow requis)
"""
import sys
from PIL import Image

src = Image.open(sys.argv[1]).convert("RGBA")
W = src.width
out = Image.new("RGBA", (W, 221), (0, 0, 0, 0))


def ligne(y_src, y_dst):
    out.paste(src.crop((0, y_src, W, y_src + 1)), (0, y_dst))


for y in range(0, 155):          # haut et bande connexion (jusqu'aux cases)
    ligne(y, y)
ligne(173, 155)                  # bas de la bande connexion
fond = list(range(174, 182))     # panneau sous les bandes (sans les ornements des coins du bas)
for k, y in enumerate(range(156, 174)):
    ligne(fond[k % len(fond)], y)
ligne(138, 174)                  # haut de la bande temps de jeu
for y in range(157, 173):        # cases du temps de jeu
    ligne(y, y + 18)
ligne(173, 191)
for y in range(174, 192):        # marge du bas et bord
    ligne(y, y + 18)
out.save(sys.argv[2])
