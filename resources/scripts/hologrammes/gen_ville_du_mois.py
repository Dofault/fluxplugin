#!/usr/bin/env python3
"""Images de l'hologramme « VILLE DU MOIS » pour ItemsAdder, namespace influhologrammes (même principe que
gen_monde_rpg.py : un logo et une icône en pixel art, même taille de pixel, même bas).

    python3 gen_ville_du_mois.py [dossier ItemsAdder/contents] [--apercu image.png]   (Labs par défaut ; Pillow requis)
    puis /iazip

  - ville_du_mois_icone : une étoile dorée dans le style du logo (variante 7 de variantes_ville_du_mois.py) ;
  - ville_du_mois_titre : le logo sources/ville_du_mois_logo.png réduit à 254 x 37 (jamais plus de 256 px de côté).
Écrit son propre fichier de config (ville_du_mois.yml) : celui de gen_monde_rpg.py reste à lui.
"""
import random
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
args = [a for a in sys.argv[1:]]
preview = None
if "--apercu" in args:
    i = args.index("--apercu")
    preview = args[i + 1]
    del args[i:i + 2]
CONTENTS = Path(args[0] if args else "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents")
OUT = CONTENTS / "influhologrammes"
TEX = OUT / "textures" / "font" / "hologrammes"

# the icon is the star of variantes_ville_du_mois.py (variant 7), plain: same style as the logo (gold face in a
# gradient, brown thickness below, dark outline)
from variantes_ville_du_mois import Icon  # noqa: E402
import math  # noqa: E402


RPG_ICON_HEIGHT = 23        # pixels of the MONDE RPG icon: the star has as many
TITLE_HEIGHT = 26           # displayed height of the VILLE DU MOIS logo (its scale_ratio)
ICON_RATIO = round(TITLE_HEIGHT * 1.3)   # the star is shown 130 % as tall as the logo


def icon():
    """The star, its size searched so the finished picture (outline and thickness included) is 23 pixels high."""
    best = None
    for tenth in range(60, 140):
        r = tenth / 10
        i = Icon()
        pts = []
        for k in range(10):
            a = math.radians(-90 + k * 36)
            rad = r if k % 2 == 0 else r * 0.42
            pts.append((r + 0.5 + rad * math.cos(a), r + 1.5 + rad * math.sin(a)))
        i.poly(pts)
        im = i.render()
        if im.height == RPG_ICON_HEIGHT:
            best = im
        elif im.height > RPG_ICON_HEIGHT:
            break
    assert best is not None, "no star size gives 23 pixels"
    return best


CONFIG = """# Généré par scripts/hologrammes/gen_ville_du_mois.py : ne pas modifier à la main.
info:
  namespace: influhologrammes
font_images:
  # Étoile de l'hologramme VILLE DU MOIS : 23 px de haut comme l'icône MONDE RPG, affichée à 130 % de la hauteur du logo, centrée sur lui.
  ville_du_mois_icone:
    suggest_in_command: false
    show_in_gui: false
    path: font/hologrammes/ville_du_mois_icone.png
    scale_ratio: {ih}
    y_position: {iy}
  # Logo VILLE DU MOIS, même taille de pixel que l'icône, même bas.
  ville_du_mois_titre:
    suggest_in_command: false
    show_in_gui: false
    path: font/hologrammes/ville_du_mois_titre.png
    scale_ratio: 26
    y_position: 24
"""

ic = icon()
logo = Image.open(HERE / "sources" / "ville_du_mois_logo.png").convert("RGBA")
title = logo.resize((logo.width // 4, logo.height // 4), Image.BOX)
if preview:
    k = 6
    gap = 4
    big_i = ic.resize((ic.width * 2 * k // 2, ic.height * 2 * k // 2), Image.NEAREST)
    big_t = title.resize((title.width * 2 * k // 4, title.height * 2 * k // 4), Image.NEAREST)
    canvas = Image.new("RGBA", (big_i.width + gap * k + big_t.width + 40, max(big_i.height, big_t.height) + 40), (30, 26, 40, 255))
    canvas.alpha_composite(big_i, (20, canvas.height - 20 - big_i.height))
    canvas.alpha_composite(big_t, (20 + big_i.width + gap * k, canvas.height - 20 - big_t.height))
    canvas.save(preview)
    print("aperçu :", preview)
else:
    TEX.mkdir(parents=True, exist_ok=True)
    ic.save(TEX / "ville_du_mois_icone.png")
    title.save(TEX / "ville_du_mois_titre.png")
    # centred on the logo: as much above its top as below its bottom
    (OUT / "ville_du_mois.yml").write_text(CONFIG.replace("{ih}", str(ICON_RATIO)).replace("{iy}", str(24 + (ICON_RATIO - TITLE_HEIGHT) // 2)))
    print(f"écrit : {OUT}")
