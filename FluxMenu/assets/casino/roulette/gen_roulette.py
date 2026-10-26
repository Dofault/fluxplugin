#!/usr/bin/env python3
"""Roulette (FluxMenu) : roue et icône dans le pack influcasino.

    <venv avec Pillow>/python3 gen_roulette.py roue_v2.png <ItemsAdder/contents>

- roue_v2.png : roue redessinée par l'utilisateur le 2026-10-17 (124 x 124 : 25 cases, le 0 vert en haut puis
  les 24 numéros dans le sens horaire, rouge = impair, noir = pair, moyeu doré). Copiée telle quelle dans
  textures/roulette/roue.png ; glyphe roulette_roue (haut à y 18 de la fenêtre, centre 87,5 / 79,5).
  L'ancienne roue (24 cases, rouge / crème, assets roue_complete.png, roue_sans_numeros.png, numeroter.py) est
  abandonnée : ne pas la recopier dans le pack.
- objet roulette : icône 16 x 16 du bouton du menu Casino.
La roue reste immobile : c'est la bille qui tourne (gen_roulette_jeu.py, à lancer ensuite).
"""
import math
import os
import sys

from PIL import Image

SOURCE, CONTENTS = sys.argv[1], sys.argv[2]
Y0, TAILLE = 18, 124
PACK = os.path.join(CONTENTS, "influcasino")

os.makedirs(os.path.join(PACK, "textures", "roulette"), exist_ok=True)
roue = Image.open(SOURCE).convert("RGBA")
assert roue.size == (TAILLE, TAILLE), roue.size
roue.save(os.path.join(PACK, "textures", "roulette", "roue.png"))

# icône 16 x 16 : roue réduite (cases rouge / noir, 0 vert en haut, moyeu doré)
ic = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
COUL = {"bord": (0x2A, 0x03, 0x06, 255), "jante": (0x8A, 0x4A, 0x10, 255), "rouge": (0xC0, 0x14, 0x24, 255),
        "noir": (0x14, 0x0A, 0x0C, 255), "vert": (0x09, 0x85, 0x1C, 255), "fond": (0x5A, 0x0B, 0x18, 255),
        "moyeu": (0xE0, 0x96, 0x2C, 255)}
for y in range(16):
    for x in range(16):
        dx, dy = x - 7.5, y - 7.5
        d = math.hypot(dx, dy)
        a = (math.degrees(math.atan2(dx, -dy)) + 360) % 360
        if d > 7.9:
            continue
        if d > 7:
            c = COUL["bord"]
        elif d > 6.1:
            c = COUL["jante"]
        elif d > 3.6:
            secteur = int(((a + 15) % 360) // 30)
            c = COUL["vert"] if secteur == 0 else (COUL["rouge"] if secteur % 2 else COUL["noir"])
        elif d > 2.2:
            c = COUL["fond"]
        else:
            c = COUL["moyeu"]
        ic.putpixel((x, y), c)
ic.save(os.path.join(PACK, "textures", "roulette", "icone.png"))

open(os.path.join(PACK, "roulette.yml"), "w", encoding="utf-8").write(f"""info:
  namespace: influcasino

# Généré par plugins/FluxMenu/assets/casino/roulette/gen_roulette.py, ne pas éditer à la main.
# roulette_roue : la roue (124 x 124, haut à y 18), affichée immobile.

items:
  roulette:
    display_name: ' '
    resource:
      material: PAPER
      generate: true
      textures:
      - roulette/icone.png

font_images:
  roulette_roue:
    suggest_in_command: false
    show_in_gui: false
    path: roulette/roue.png
    scale_ratio: {TAILLE}
    y_position: {13 - Y0}
""")
print("roue", roue.size, "et icône")
