#!/usr/bin/env python3
# OBSOLÈTE depuis le 2026-10-15 : les fonds Collection sont désormais dessinés par l'utilisateur
# (assets/menu_collection.png et menu_collection_categorie.png = ses images). Ne pas relancer : il les écraserait.
"""Fond du menu d'une catégorie de collections (FluxMenu, menus/collection_<catégorie>.yml).

    <venv avec Pillow>/python3 gen_fond_categorie.py <menu_generique.png> <sortie menu_collection_categorie.png>

Part du fond générique (plugins/FluxMenu/assets/menu_generique.png : panneau plein écran,
dégradé clair en haut vers sombre en bas, trame en damier), raccourci pour que le cadre aille de
y 8 (au-dessus de la rangée 0 du coffre) à y 182 (sous la 2e rangée de l'inventaire) :
- bord haut et ornements (rangées 0 à 9) descendus en 8, bord bas et ornements (211 à 220)
  remontés en 173 ;
- entre les deux, le dégradé entier compressé : chaque rangée reprend la rangée d'origine
  proportionnelle, de même parité (le damier reste aligné) ;
- un carré de case (creusé : bord sombre en haut à gauche, clair en bas à droite) sous l'icône de
  la catégorie (case 4), sous les 28 collections (rangées 1 à 4, colonnes 1 à 7) et sous la
  collection sélectionnée (1re rangée de l'inventaire, case du milieu). Rien sous les boutons
  (rangée 5) ni sous la barre de progression (dessinée par FluxMenu, 2e rangée de l'inventaire).
"""
import sys
from PIL import Image

src = Image.open(sys.argv[1]).convert("RGBA")
sp = src.load()
W, H = src.size

HAUT, BAS = 8, 182                   # premier et dernier pixel du cadre dans la nouvelle image
BLOC_HAUT = range(0, 10)             # bord haut et ornements des coins
BLOC_BAS = range(211, 221)           # bord bas et ornements des coins
im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px = im.load()

def copier(y_src, y_dst):
    for x in range(W):
        px[x, y_dst] = sp[x, y_src]

for y in BLOC_HAUT:
    copier(y, HAUT + y)
debut, fin = HAUT + len(BLOC_HAUT), BAS - len(BLOC_BAS) + 1        # rangées 18 à 172
src_debut, src_fin = BLOC_HAUT.stop, BLOC_BAS.start - 1              # rangées 10 à 210
for y in range(debut, fin):
    s = src_debut + round((y - debut) * (src_fin - src_debut) / (fin - 1 - debut))
    if (s - y) % 2:                                                   # même parité : damier aligné
        s = s - 1 if s > src_debut else s + 1
    copier(s, y)
for i, y in enumerate(BLOC_BAS):
    copier(y, fin + i)

# --- Carrés des cases -----------------------------------------------------------------------
SOMBRE, CLAIR = (31, 24, 40, 255), (89, 70, 116, 255)
FOND_A, FOND_B = (44, 35, 57, 255), (47, 37, 60, 255)
def carre(x, y):
    """Case dont l'objet est dessiné en (x, y) : carré 18 x 18 de (x - 1, y - 1) à (x + 16, y + 16)."""
    for dy in range(18):
        for dx in range(18):
            X, Y = x - 1 + dx, y - 1 + dy
            if dy == 0 or dx == 0:
                c = SOMBRE
            elif dy == 17 or dx == 17:
                c = CLAIR
            else:
                c = FOND_A if (X + Y) % 2 else FOND_B
            px[X, Y] = c

carre(8 + 18 * 4, 18)                                   # icône de la catégorie (case 4)
for r in range(1, 5):
    for c in range(1, 8):
        carre(8 + 18 * c, 18 + 18 * r)                  # collections
carre(8 + 18 * 4, 140)                                  # collection sélectionnée (case 58)

im.save(sys.argv[2])
print("fond :", im.size, im.getbbox())
