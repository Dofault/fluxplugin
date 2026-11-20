#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Genere les textures 16x16 des vinyles pour le content ItemsAdder influvinyles.

Usage : python3 scripts/generer_vinyles_textures.py

Chaque vinyle est un disque noir a sillons avec une etiquette centrale de couleur propre,
prise dans la charte graphique du serveur. Les couleurs sont declarees ci-dessous, une par
id de vinyle : changer une valeur et relancer le script suffit a reteinter un vinyle.

Ces textures sont autonomes (aucune dependance a un pack externe). Pour leur substituer
celles d'un pack tiers, utiliser scripts/import_vinyles_textures.py, qui ecrit aux memes
chemins et ecrase donc ce qui est genere ici.
"""
import math, os, struct, zlib

DST = "/home/influencraft/serveurs/03_semirp/plugins/ItemsAdder/contents/influvinyles/textures/item"

# id de vinyle -> couleur d'etiquette (charte graphique, CLAUDE.md)
COULEURS = {
    "vinyle_cinquieme_etage":  "#B376E5",  # violet clair
    "vinyle_ligne_brisee":     "#5B2D9E",  # violet principal
    "vinyle_cave_treize":      "#8C8FA8",  # gris bleute
    "vinyle_pixels_poussiere": "#2BC4E8",  # bleu systeme
    "vinyle_ruelle_neon":      "#E060A0",  # rose clair
    "vinyle_radio_grillon":    "#2ECF7B",  # vert
    "vinyle_le_createur":      "#F0A800",  # or chaud
    "vinyle_boite_a_musique":  "#FFD94D",  # or vif
    "vinyle_horizon_lointain": "#5CA8E6",  # bleu doux
    "vinyle_galerie":          "#C8256A",  # rose fuchsia
    "vinyle_melancolie":       "#9A6BD0",  # violet grise
    "vinyle_autre_cote":       "#40E0D0",  # turquoise
    "vinyle_marche_cramoisie": "#FF5C5C",  # rouge
    "vinyle_bord_du_vide":     "#1E90FF",  # bleu franc
    "vinyle_relique":          "#A0762E",  # bronze
    "vinyle_bal_des_rouages":  "#F4A261",  # orange sable
    "vinyle_corde_ivoire":     "#E8E0D0",  # ivoire
    "vinyle_larmes_de_verre":  "#97FC79",  # mint glow
    "vinyle_attente_douce":    "#FFB0C8",  # rose pale
    "vinyle_le_gardien":       "#7A4FB0",  # violet profond
    "vinyle_fievre_cratere":   "#FF7A1A",  # orange lave
}

NOIR_DISQUE = (0x17, 0x17, 0x17)
NOIR_SILLON = (0x2A, 0x2A, 0x2A)
NOIR_CONTOUR = (0x0D, 0x0D, 0x0D)  # noir de la charte
TROU = (0x0D, 0x0D, 0x0D)
N = 16


def hexa(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def ecrire_png(path, pixels):
    """PNG RGBA 16x16 sans dependance externe."""
    brut = b"".join(b"\x00" + b"".join(bytes(p) for p in ligne) for ligne in pixels)

    def bloc(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + bloc(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0))
           + bloc(b"IDAT", zlib.compress(brut, 9))
           + bloc(b"IEND", b""))
    open(path, "wb").write(png)


def vinyle(couleur):
    label = hexa(couleur)
    # etiquette assombrie pour le bas du disque : donne du volume sans dessiner d'ombre portee
    label_bas = tuple(int(v * 0.72) for v in label)
    px = [[(0, 0, 0, 0)] * N for _ in range(N)]
    c = 7.5  # centre entre les pixels 7 et 8

    for y in range(N):
        for x in range(N):
            d = math.hypot(x - c, y - c)
            if d > 7.6:
                continue
            if d > 6.8:
                px[y][x] = NOIR_CONTOUR + (255,)
            elif d < 0.9:
                px[y][x] = TROU + (255,)
            elif d < 3.3:
                px[y][x] = (label_bas if y > c else label) + (255,)
            elif d < 3.8:
                px[y][x] = NOIR_CONTOUR + (255,)
            else:
                # sillons concentriques : un anneau clair tous les 2 pixels de rayon
                sillon = int(d) % 2 == 0
                px[y][x] = (NOIR_SILLON if sillon else NOIR_DISQUE) + (255,)

    # reflet diagonal en haut a gauche, comme la brillance d'un vinyle sous la lumiere
    for x, y in [(4, 3), (5, 3), (3, 4), (4, 4), (3, 5), (5, 2), (6, 2)]:
        if px[y][x][3]:
            px[y][x] = (0x4A, 0x4A, 0x52, 255)
    return px


def main():
    os.makedirs(DST, exist_ok=True)
    for vid, couleur in sorted(COULEURS.items()):
        ecrire_png(os.path.join(DST, vid + ".png"), vinyle(couleur))
        print("  %-26s %s" % (vid + ".png", couleur))
    print("\n%d textures generees dans %s" % (len(COULEURS), DST))


if __name__ == "__main__":
    main()
