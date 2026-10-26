#!/usr/bin/env python3
"""Fonds des menus des familiers (Flux : menus/familiers.yml et menus/familier.yml).

    <venv avec Pillow>/python3 gen_fond.py <menu_generique.png> <dossier de sortie>

Même style que les menus Collection : fond générique (dégradé, damier) raccourci au coffre (cadre de y 8 à
y 139, le libellé « Inventaire » du client tombe juste au-dessus du bord bas ; inventaire masqué et vide), et
la case arrondie de l'utilisateur (assets/case_arrondie.png) sous chaque case utile.
- menu_familiers.png : 5 emplacements actifs (cases 2 à 6), une rangée vide, la grille de 7 x 3 (rangées 2 à 4,
  colonnes 1 à 7).
- menu_familier.png : portrait (case 4) et les 10 niveaux (rangées 2 et 3, colonnes 2 à 6).
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from fond_lib import generique_raccourci, objet, poser, tampon_case  # noqa: E402

GENERIQUE, SORTIE = sys.argv[1], sys.argv[2]
HAUT, BAS = 8, 139
tampon = tampon_case()

FONDS = {
    "menu_familiers": list(range(2, 7)) + [r * 9 + c for r in range(2, 5) for c in range(1, 8)],
    "menu_familier": [4] + [r * 9 + c for r in (2, 3) for c in range(2, 7)],
}
for nom, cases in FONDS.items():
    im = generique_raccourci(GENERIQUE, HAUT, BAS)
    for case in cases:
        poser(im, tampon, *objet(case))
    im.save(os.path.join(SORTIE, nom + ".png"))
    print(nom, im.getbbox())
