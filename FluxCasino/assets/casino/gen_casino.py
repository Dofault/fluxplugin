#!/usr/bin/env python3
"""Textures du casino : jeton (objet 16 x 16 et glyphe 9 x 9 pour le texte) et bouton « − ».

    <venv avec Pillow>/python3 gen_casino.py <ItemsAdder/contents>

- contents/influcasino/ : objet influcasino:jeton (16 x 16) et glyphe %img_jeton% (9 x 9, même réglage que
  %img_bitblock%). Jeton de casino vu de dessus : liseré sombre, couronne à rayures rouge / crème, cœur doré.
- contents/influitems/textures/remove.png : bouton « − » (retirer, diminuer), même gabarit que add.png (+ vert)
  dans la palette rouge de deny.png. L'objet influitems:remove est ajouté à influitems/config.yml s'il manque.
"""
import math
import os
import sys

from PIL import Image

CONTENTS = sys.argv[1]


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


# couleurs du thème casino (2026-10-17) : rayures rouge / crème, cœur doré
CONTOUR, VIOLET, VIOLET_OMBRE, BLANC, BLANC_OMBRE = rgb("1A0505"), rgb("C8141F"), rgb("8A0A14"), rgb("FFF6E8"), rgb("E8D2B0")
OR_FONCE, OR, OR_CLAIR, OR_REFLET = rgb("B0681E"), rgb("F0A800"), rgb("FFD94D"), rgb("FFF3B0")


def jeton(n):
    """Jeton n x n : cercle, couronne à 8 rayures alternées, cœur doré éclairé en haut à gauche, étoile au centre."""
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    c = (n - 1) / 2
    r = n / 2
    for y in range(n):
        for x in range(n):
            dx, dy = x - c, y - c
            d = math.hypot(dx, dy)
            if d > r - 0.15:
                continue
            if d > r - 1.15:
                col = CONTOUR
            elif d > r * 0.58:
                angle = (math.degrees(math.atan2(dy, dx)) + 360 + 22.5) % 360
                blanc = int(angle // 45) % 2 == 0
                bas = dy > 0.5
                col = (BLANC_OMBRE if bas else BLANC) if blanc else (VIOLET_OMBRE if bas else VIOLET)
            elif d > r * 0.58 - 1:
                col = OR_FONCE
            else:
                col = OR_CLAIR if dx + dy < -0.5 else OR
            im.putpixel((x, y), col)
    # étoile (16 px) ou point (9 px) au centre
    ci = int(c)
    if n >= 16:
        # losange gravé au centre (cœur de 6 px), reflet sur sa face haute gauche
        for x, y in ((7, 5), (8, 5), (6, 6), (9, 6), (6, 9), (9, 9), (7, 10), (8, 10), (5, 7), (5, 8), (10, 7), (10, 8)):
            im.putpixel((x, y), OR_FONCE)
        for x, y in ((7, 6), (8, 6), (6, 7), (7, 7), (6, 8)):
            im.putpixel((x, y), OR_REFLET)
    else:
        im.putpixel((ci, ci), OR_FONCE)
    return im


# ------------------------------------------------------------------ influcasino
base = os.path.join(CONTENTS, "influcasino")
os.makedirs(os.path.join(base, "textures", "item"), exist_ok=True)
os.makedirs(os.path.join(base, "textures", "font"), exist_ok=True)
jeton(16).save(os.path.join(base, "textures", "item", "jeton.png"))
jeton(9).save(os.path.join(base, "textures", "font", "jeton.png"))


def icone_plinko():
    """Icône du jeu Plinko (16 x 16) : pyramide de clous, bille blanche, cases colorées en bas."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    clou = rgb("C9B8E6")
    for k, y in enumerate((3, 6, 9)):
        for i in range(k + 2):
            x = 8 - (k + 1) * 2 + i * 4
            im.putpixel((x, y), clou)
    # bille en train de tomber, entre deux clous
    for x, y in ((9, 4), (10, 4), (9, 5), (10, 5)):
        im.putpixel((x, y), BLANC)
    im.putpixel((10, 5), BLANC_OMBRE)
    for x, y in ((8, 4), (11, 4), (8, 5), (11, 5), (9, 3), (10, 3), (9, 6), (10, 6)):
        if im.getpixel((x, y))[3] == 0:
            im.putpixel((x, y), CONTOUR)
    # cases de gains : rouge, orange, jaune, orange, rouge
    cases = ["C8256A", "F0A800", "FFD94D", "F0A800", "C8256A"]
    for j, h in enumerate(cases):
        for x in range(1 + j * 3, 3 + j * 3):
            for y in range(12, 15):
                im.putpixel((x, y), rgb(h))
    for x in range(0, 16):
        im.putpixel((x, 15), CONTOUR)
    return im


icone_plinko().save(os.path.join(base, "textures", "item", "plinko.png"))
open(os.path.join(base, "config.yml"), "w", encoding="utf-8").write("""info:
  namespace: influcasino

# Généré par plugins/fluxplugin/FluxCasino/assets/casino/gen_casino.py, ne pas éditer à la main.
# Jeton de casino : objet (menus, 16 x 16) et glyphe %img_jeton% (texte, 9 x 9, réglé comme %img_bitblock%).

items:
  jeton:
    display_name: ' '
    resource:
      material: PAPER
      generate: true
      textures:
      - item/jeton.png
  plinko:
    display_name: ' '
    resource:
      material: PAPER
      generate: true
      textures:
      - item/plinko.png

font_images:
  jeton:
    show_in_gui: true
    suggest_in_command: true
    path: font/jeton.png
    scale_ratio: 9
    y_position: 8
""")

# ------------------------------------------------------------------ boutons du casino (influcasino)
# Mêmes boutons que influitems (même gabarit, même gravure), seules les couleurs changent. Les boutons sources
# ont tous 6 couleurs aux mêmes rôles : contour, reflet, corps, bords, gravure, ombre de gravure.
ROLES_SOURCES = {
    "violet": {"15111b": "O", "ebe6f3": "H", "9b86c6": "C", "beb0d9": "S", "45365f": "G", "b1a0d2": "GS"},
    "rouge": {"15111b": "O", "f5dcdd": "H", "cf5058": "C", "df8d92": "S", "8a2c38": "G", "d9767c": "GS"},
    "vert": {"15111b": "O", "dbecde": "H", "4fa45c": "C", "8cc395": "S", "2f6b3b": "G", "75b87f": "GS"},
}
PALETTES_CASINO = {
    "or": {"O": "1A0505", "H": "FFF6D8", "C": "E0962C", "S": "FFC860", "G": "5A1608", "GS": "B0681E"},
    "vert": {"O": "1A0505", "H": "FFE9A8", "C": "1E7A3A", "S": "E0962C", "G": "FFC860", "GS": "0F4A22"},
    "rouge": {"O": "1A0505", "H": "FFE9A8", "C": "A8101C", "S": "E0962C", "G": "FFC860", "GS": "6A0810"},
    "bordeaux": {"O": "1A0505", "H": "FFE9A8", "C": "5A0B18", "S": "B0681E", "G": "FFC860", "GS": "2A0306"},
}
BOUTONS = {  # objet influcasino : (bouton source influitems, palette)
    "btn_retour": ("back", "or"), "btn_precedent": ("prev", "or"), "btn_suivant": ("next", "or"),
    "plus": ("add", "vert"), "moins": ("remove", "rouge"), "cadenas": ("lock", "bordeaux"),
}


def recolorer(source, palette):
    im = Image.open(source).convert("RGBA")
    roles = {}
    for table in ROLES_SOURCES.values():
        roles.update(table)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    for y in range(im.height):
        for x in range(im.width):
            p = im.getpixel((x, y))
            if p[3] == 0:
                continue
            role = roles.get("%02x%02x%02x" % p[:3])
            if role is None:
                raise SystemExit(f"{source} : couleur hors gabarit %02x%02x%02x" % p[:3])
            out.putpixel((x, y), rgb(PALETTES_CASINO[palette][role]))
    return out


# ------------------------------------------------------------------ influitems:remove (« − » rouge)
items = os.path.join(CONTENTS, "influitems")
add = Image.open(os.path.join(items, "textures", "add.png")).convert("RGBA")
# palette verte de add.png -> rouge de deny.png (même gabarit, mêmes rôles de couleur)
VERT_ROUGE = {"15111b": "15111b", "dbecde": "f5dcdd", "4fa45c": "cf5058", "8cc395": "df8d92",
              "2f6b3b": "8a2c38", "75b87f": "d9767c"}
moins = Image.new("RGBA", add.size, (0, 0, 0, 0))
for y in range(16):
    for x in range(16):
        p = add.getpixel((x, y))
        if p[3] == 0:
            continue
        k = "%02x%02x%02x" % p[:3]
        moins.putpixel((x, y), rgb(VERT_ROUGE.get(k, k)))
# symbole : on efface la croix (rangées 4 à 10, colonnes 4 à 11 = corps) puis barre horizontale + ombre
for y in range(4, 11):
    for x in range(4, 12):
        moins.putpixel((x, y), rgb("cf5058"))
for x in range(5, 11):
    moins.putpixel((x, 6), rgb("8a2c38"))
    moins.putpixel((x, 7), rgb("8a2c38"))
    moins.putpixel((x, 8), rgb("d9767c"))
moins.save(os.path.join(items, "textures", "remove.png"))
cfg = os.path.join(items, "config.yml")
texte = open(cfg, encoding="utf-8").read()
if "\n  remove:\n" not in texte:
    i = texte.index("\n  deny:\n")
    texte = texte[:i] + """
  remove:
    display_name: ' '
    resource:
      material: PAPER
      generate: true
      textures:
      - remove.png
""" + texte[i:]
    open(cfg, "w", encoding="utf-8").write(texte)
os.makedirs(os.path.join(base, "textures", "bouton"), exist_ok=True)
lignes = []
for nom, (source, palette) in BOUTONS.items():
    recolorer(os.path.join(items, "textures", source + ".png"), palette).save(
        os.path.join(base, "textures", "bouton", nom + ".png"))
    lignes += [f"  {nom}:", "    display_name: ' '", "    resource:", "      material: PAPER", "      generate: true",
               "      textures:", f"      - bouton/{nom}.png"]
cfg_casino = os.path.join(base, "config.yml")
texte = open(cfg_casino, encoding="utf-8").read()
texte = texte.replace("\nfont_images:", "\n" + "\n".join(lignes) + "\n\nfont_images:", 1)
open(cfg_casino, "w", encoding="utf-8").write(texte)
print("jeton 16 + 9, remove, boutons :", ", ".join(BOUTONS))
