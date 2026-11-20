#!/usr/bin/env python3
"""Images de l'hologramme « MONDE RPG » (au-dessus du portail RPG de Resaac) pour ItemsAdder, namespace influhologrammes.

    python3 gen_monde_rpg.py [dossier ItemsAdder/contents]      (Labs par défaut ; Pillow requis)   puis /iazip

  - monde_rpg_icone : pixel art dessiné ici (épée plantée dans un bloc d'herbe), mêmes couleurs que le logo ;
  - monde_rpg_titre : le logo sources/monde_rpg_logo.png réduit à 256 x 45 (le client ne dessine aucune image de
    police de plus de 256 px de côté : à respecter pour toute image de ce dossier).
Les deux glyphes ont la même taille de pixel (un pixel du logo = ~11,4 px de l'image source = 2 px de police) et le même
bas : l'icône (23 px de haut, x2) mesure 46, le logo 32. L'hologramme lui-même est posé par
scripts/typewriter/gen_resaac.py.
"""
import random
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
CONTENTS = Path(sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents")
OUT = CONTENTS / "influhologrammes"
TEX = OUT / "textures" / "font" / "hologrammes"

O = (43, 4, 12)                                                        # outline of the logo
T1 = [(20, 180, 133), (17, 155, 133), (25, 201, 149), (23, 171, 147)]  # teal top
T2 = [(86, 131, 83), (69, 107, 82), (97, 145, 94), (85, 126, 99)]      # moss green
T3 = [(15, 82, 77), (22, 97, 91), (27, 110, 104), (23, 100, 94)]       # dark teal
DT = [(95, 38, 37), (114, 52, 50), (77, 27, 26)]                       # dirt
B, M, K = (220, 240, 233), (150, 188, 177), (96, 132, 124)             # blade
G, G2 = (25, 201, 149), (15, 82, 77)                                   # guard
H, H2 = (95, 38, 37), (130, 62, 58)                                    # grip
S, S2 = (255, 217, 77), (240, 168, 0)                                  # gold (charte)


def icon():
    random.seed(11)
    cw, ch, p = 14, 19, 1
    g = {}

    def put(x, y, c):
        g[(x + p, y + p + 1)] = c

    put(6, 0, S); put(7, 0, S2); put(6, 1, S2); put(7, 1, S2)          # pommel
    for y in (2, 3, 4):
        put(6, y, H2 if y % 2 else H); put(7, y, H if y % 2 else H2)
    for x in range(2, 12):
        put(x, 5, G2 if x in (2, 11) else G)
    for x in (5, 8):
        put(x, 6, G2)
    put(6, 6, B); put(7, 6, M)
    for y in range(7, 11):
        put(5, y, K); put(6, y, B); put(7, y, M); put(8, y, K)
    for y in range(11, ch):                                            # grass block, textured like the letters
        r = y - 11
        for x in range(cw):
            pal = T1 if r < 2 else T2 if r < 4 else random.choice([T2, T3]) if r < 5 else T3 if r < 6 else DT
            put(x, y, random.choice(pal))
    for x in (5, 6, 7, 8):
        put(x, 11, O)                                                  # slit where the blade goes in
    put(6, 12, T3[0]); put(7, 12, T3[0])
    for x, y, c in [(1, 0, S), (0, 1, S), (1, 1, S2), (2, 1, S), (1, 2, S),
                    (12, 7, S), (12, 8, S2), (11, 8, S), (13, 8, S), (12, 9, S)]:
        put(x, y, c)                                                   # sparkles
    w, h = cw + 2 * p, ch + 2 * p + 2
    for (x, y) in list(g):
        if g[(x, y)] == O:
            continue
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in g and 0 <= q[0] < w and 0 <= q[1] < h:
                g[q] = O
    for (x, y) in sorted(g, key=lambda q: -q[1]):                      # depth under, like the logo
        if y >= ch + 1 and (x, y + 1) not in g and y + 1 < h:
            g[(x, y + 1)] = O
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for (x, y), c in g.items():
        im.putpixel((x, y), c + (255,))
    return im


CONFIG = """# Généré par scripts/hologrammes/gen_monde_rpg.py : ne pas modifier à la main.
# Images des hologrammes posés par Typewriter (scripts/typewriter/gen_resaac.py).
info:
  namespace: influhologrammes
font_images:
  # Pixel art de l'hologramme MONDE RPG (épée dans un bloc d'herbe), 16 x 23 affiché x2.
  monde_rpg_icone:
    suggest_in_command: false
    show_in_gui: false
    path: font/hologrammes/monde_rpg_icone.png
    scale_ratio: 46
    y_position: 44
  # Logo MONDE RPG, même taille de pixel que l'icône, même bas.
  monde_rpg_titre:
    suggest_in_command: false
    show_in_gui: false
    path: font/hologrammes/monde_rpg_titre.png
    scale_ratio: 32
    y_position: 30
"""

TEX.mkdir(parents=True, exist_ok=True)
icon().save(TEX / "monde_rpg_icone.png")
# The client puts font glyphs in 256 x 256 atlases: a wider picture is never drawn. 1024 x 180 -> 256 x 45 (exact /4).
logo = Image.open(HERE / "sources" / "monde_rpg_logo.png").convert("RGBA")
logo.resize((logo.width // 4, logo.height // 4), Image.BOX).save(TEX / "monde_rpg_titre.png")
(OUT / "config.yml").write_text(CONFIG)
print(f"écrit : {OUT}")
