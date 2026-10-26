#!/usr/bin/env python3
# OBSOLÈTE depuis le 2026-10-15 : les fonds Collection sont désormais dessinés par l'utilisateur
# (assets/menu_collection.png et menu_collection_categorie.png = ses images). Ne pas relancer : il les écraserait.
"""Fond du menu Collection (FluxMenu) à partir de l'image fournie (original.png, 176 x 221 :
la fenêtre entière d'un coffre de 6 rangées avec l'inventaire, posée telle quelle).

    <venv avec Pillow>/python3 gen_fond.py original.png <sortie menu_collection.png>

Étagères sous les rangées 3, 4 et 5 du coffre (y 88, 106, 124, colonnes 1 à 7), cadre jusqu'à
y 162 : la 1re rangée de l'inventaire (y 140 à 155) est dans le cadre et reçoit la barre de
progression.

Retouche (2026-10-15) : couronne, titre « COLLECTION » et étincelles effacés. Au-dessus des
faisceaux, le fond n'est qu'un dégradé vertical tramé : chaque pixel de la gravure prend le pixel
de fond le plus proche sur la même ligne, à une distance paire (même parité de trame).
"""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert("RGBA")
px = im.load()

# Couleurs du fond tramé de cette bande (relevées de part et d'autre de la gravure).
FOND = {px[x, y] for y in range(40, 68) for x in list(range(12, 40)) + list(range(140, 164))}
X0, X1, Y0, Y1 = 40, 140, 40, 68   # couronne (y 40 à 50, sous la ligne du cadre en 39), titre et étincelles (y 55 à 65)
for y in range(Y0, Y1):
    ligne = [px[x, y] for x in range(im.width)]
    for x in range(X0, X1):
        if ligne[x] in FOND:
            continue
        for d in range(2, 80, 2):
            for xs in (x - d, x + d):
                if 0 <= xs < im.width and ligne[xs] in FOND:
                    px[x, y] = ligne[xs]
                    break
            else:
                continue
            break

im.save(sys.argv[2])
print("fond :", im.size, im.getbbox())
