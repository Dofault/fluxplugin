#!/usr/bin/env python3
"""Génère le pack ItemsAdder « influmetier » du menu Carrières de FluxMenu.

    python3 gen_influmetier.py <contents/influmetier> <dossier assets/metiers> <dossier de png.py>

- metier_<id> : illustration 64 x 64 du métier (assets/metiers/<id>.png), posée dans le cadre du
  fond menu_metier (intérieur en (38, 39) : y_position = 13 - 39 = -26).
- nom_<id>    : nom du métier écrit en police QuinqueFive (5 x 5, accents ajoutés au-dessus),
  en or avec une ombre, centré sur la rangée du haut (haut de l'image à y = 22).
Écrit aussi assets/metiers/noms.json (largeur de chaque mot) pour gen_metiers.py.
"""
import json
import os
import shutil
import sys

DEST, SRC = sys.argv[1], sys.argv[2]
sys.path.insert(0, sys.argv[3])
import png  # noqa: E402

METIERS = {"mineur": "MINEUR", "rochiste": "ROCHISTE", "deblayeur": "DÉBLAYEUR", "cultivateur": "CULTIVATEUR",
           "herboriste": "HERBORISTE", "sylviculteur": "SYLVICULTEUR", "fondeur": "FONDEUR", "chimiste": "CHIMISTE",
           "enchanteur": "ENCHANTEUR", "pecheur": "PÊCHEUR", "defenseur": "DÉFENSEUR", "batisseur": "BÂTISSEUR"}
NOM_Y = 22           # haut du mot dans la fenêtre (rangée du haut 0-8 : y 18 à 34)
CHIFFRE_Y = 197      # chiffres du niveau (191 d'origine + 6 px, demandé le 2026-10-15)
PHOTO_Y = 39         # haut de l'illustration (intérieur du cadre, une rangée sous le bandeau)
OR, OMBRE = (0xFF, 0xD9, 0x4D), (0x15, 0x11, 0x1B)

# Lettres de la police QuinqueFive (5 x 5, GGBotNet, licence SIL OFL, assets/metiers/police/),
# extraites dans assets/metiers/quinquefive.json. Elle n'a pas de majuscules accentuées : l'accent
# est ajouté au-dessus de la lettre, sur 2 rangées.
LETTRES = json.load(open(os.path.join(SRC, "quinquefive.json"), encoding="utf-8"))
ACCENTS = {"É": ("E", ["...#.", "..#.."]), "Ê": ("E", ["..#..", ".#.#."]), "Â": ("A", ["..#..", ".#.#."])}
HAUTEUR_LETTRE = 5


def glyphe(c):
    """7 lignes : 2 d'accent (vides si aucun) puis les 5 de la lettre."""
    if c in ACCENTS:
        base, accent = ACCENTS[c]
        return accent + LETTRES[base]
    l = LETTRES[c]
    return ["." * len(l[0])] * 2 + l


def image(lignes):
    """Image RGBA de lignes de pixels « # » : or, ombre décalée de (1, 1)."""
    w, h = len(lignes[0]) + 1, len(lignes) + 1
    px = [[None] * w for _ in range(h)]
    for y, ligne in enumerate(lignes):
        for x, ch in enumerate(ligne):
            if ch == "#":
                if px[y + 1][x + 1] is None:
                    px[y + 1][x + 1] = OMBRE
                px[y][x] = OR
    return w, h, [b"".join(bytes(c) + b"\xff" if c else b"\0\0\0\0" for c in ligne) for ligne in px]


def mot(texte):
    """Image RGBA du mot : lettres or, espacement 1, ombre décalée de (1, 1)."""
    gl = [glyphe(c) for c in texte]
    w = sum(len(g[0]) for g in gl) + len(gl) - 1 + 1   # +1 pour l'ombre
    h = 2 + HAUTEUR_LETTRE + 1
    px = [[None] * w for _ in range(h)]
    x0 = 0
    for g in gl:
        for y, ligne in enumerate(g):
            for x, ch in enumerate(ligne):
                if ch == "#":
                    if px[y + 1][x0 + x + 1] is None:
                        px[y + 1][x0 + x + 1] = OMBRE
                    px[y][x0 + x] = OR
        x0 += len(g[0]) + 1
    rows = [b"".join(bytes(c) + b"\xff" if c else b"\0\0\0\0" for c in ligne) for ligne in px]
    return w, h, rows


os.makedirs(f"{DEST}/textures/metier", exist_ok=True)
os.makedirs(f"{DEST}/textures/nom", exist_ok=True)
lignes = ["info:", "  namespace: influmetier", "",
          "# Généré par plugins/FluxMenu/assets/gen_influmetier.py, ne pas éditer à la main.",
          "# metier_<id> : illustration du métier dans le cadre du fond menu_metier (menu Carrières).",
          "# nom_<id>    : nom du métier en police QuinqueFive, centré sur la rangée du haut.",
          "", "font_images:"]
largeurs = {}
for jid, texte in METIERS.items():
    shutil.copy(f"{SRC}/{jid}.png", f"{DEST}/textures/metier/{jid}.png")
    w, h, rows = mot(texte)
    png.write(f"{DEST}/textures/nom/{jid}.png", w, h, rows)
    largeurs[jid] = w
    lignes += [f"  metier_{jid}:", "    suggest_in_command: false", "    show_in_gui: false",
               f"    path: metier/{jid}.png", "    scale_ratio: 64", f"    y_position: {13 - PHOTO_Y}",
               f"  nom_{jid}:", "    suggest_in_command: false", "    show_in_gui: false",
               f"    path: nom/{jid}.png", f"    scale_ratio: {h}", f"    y_position: {13 - NOM_Y}"]
# Chiffres du niveau (« chiffre_<n> », 5 x 5 + ombre) : écrits par FluxMenu au-dessus de la barre
# d'XP (%fluxjob_<métier>_levelglyphs_<curseur>%).
os.makedirs(f"{DEST}/textures/chiffre", exist_ok=True)
for c in "0123456789":
    w, h, rows = image(LETTRES[c])
    png.write(f"{DEST}/textures/chiffre/{c}.png", w, h, rows)
    lignes += [f"  chiffre_{c}:", "    suggest_in_command: false", "    show_in_gui: false",
               f"    path: chiffre/{c}.png", f"    scale_ratio: {h}", f"    y_position: {13 - CHIFFRE_Y}"]
open(f"{DEST}/config.yml", "w", encoding="utf-8").write("\n".join(lignes) + "\n")
json.dump(largeurs, open(f"{SRC}/noms.json", "w"), indent=1)
print(len(METIERS), "métiers :", largeurs)
