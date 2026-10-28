"""Rewards menu background: the owner's hand-retouched image (menu_recompenses_sans_titre.png) with the menu title
« RÉCOMPENSES » on its first row (back 9, menu icon 17), as in every menu. The retouched image itself is never
regenerated.

Usage: python ajouter_titre.py <png out>  (Pillow)
"""
import os
import sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from fond_lib import title  # noqa: E402

im = Image.open(os.path.join(HERE, "menu_recompenses_sans_titre.png")).convert("RGBA")
title(im, "Récompenses", 36)
im.save(sys.argv[1])
