#!/usr/bin/env python3
"""Fond du menu Casino (Flux : menus/casino.yml), thème casino.

    <venv avec Pillow>/python3 gen_fond.py <ItemsAdder/contents/influcasino>

Fond du casino (gen_theme.py) dessiné de y 0 à y 139 (l'arrondi des coins finit au-dessus de la case 0 ; cadre
sous la dernière rangée utile du coffre), et la case arrondie de l'utilisateur (assets/case_arrondie.png, même forme) recolorée en bordeaux
sous le solde (4), les 3 jeux (20, 22, 24) et la revente (39, 41). Écrit textures/font/menu_casino.png,
textures/font/menu_casino_generique.png (fond générique plein écran, pour les prochains menus) et fonds.yml
(glyphes menu_casino et menu_casino_generique, namespace influcasino).
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image

ICI = os.path.dirname(os.path.abspath(__file__))
# fond_lib: shared background helpers of the suite, in Flux/assets
sys.path.insert(0, os.path.join(ICI, "..", "..", "..", "Flux", "assets"))
from fond_lib import objet, poser, tampon_case  # noqa: E402

PACK = sys.argv[1]
# case arrondie : couleurs violettes d'origine -> bordeaux du casino (même rôle, même forme)
RECOLORE = {"15111b": "1A0505", "1c1624": "230707", "231c2e": "2A0306", "47385c": "5A0B18", "54426d": "64101C",
            "594674": "6E1020", "312740": "3A030B", "3a2e4c": "2E0309"}


def hexa(c):
    return "%02x%02x%02x" % c[:3]


tampon = {k: tuple(int(RECOLORE[hexa(c)][i:i + 2], 16) for i in (0, 2, 4)) + (255,)
          for k, c in tampon_case().items()}
os.makedirs(os.path.join(PACK, "textures", "font"), exist_ok=True)
# fond générique plein écran (validé le 2026-10-17), disponible pour les prochains menus du casino
subprocess.run([sys.executable, os.path.join(ICI, "gen_theme.py"), os.path.join(PACK, "textures", "font",
                "menu_casino_generique.png")], check=True, stdout=subprocess.DEVNULL)
with tempfile.TemporaryDirectory() as tmp:
    base = os.path.join(tmp, "base.png")
    subprocess.run([sys.executable, os.path.join(ICI, "gen_theme.py"), base, "0", "139"], check=True,
                   stdout=subprocess.DEVNULL)
    im = Image.open(base).convert("RGBA")
for case in (4, 20, 22, 24, 39, 41):
    poser(im, tampon, *objet(case))
os.makedirs(os.path.join(PACK, "textures", "font"), exist_ok=True)
im.save(os.path.join(PACK, "textures", "font", "menu_casino.png"))
open(os.path.join(PACK, "fonds.yml"), "w", encoding="utf-8").write("""info:
  namespace: influcasino

# Généré par plugins/fluxplugin/FluxCasino/assets/casino/gen_fond.py, ne pas éditer à la main.
font_images:
  # fond générique plein écran du casino (cadre de y 0 à 220), pour les prochains menus
  menu_casino_generique:
    suggest_in_command: false
    show_in_gui: false
    path: font/menu_casino_generique.png
    scale_ratio: 221
    y_position: 13
  menu_casino:
    suggest_in_command: false
    show_in_gui: false
    path: font/menu_casino.png
    scale_ratio: 221
    y_position: 13
""")
print("fond casino", im.getbbox())
