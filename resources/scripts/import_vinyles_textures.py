#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Importe les textures de vinyles depuis record-player.zip vers le content ItemsAdder influvinyles.

Usage : python3 scripts/import_vinyles_textures.py /chemin/vers/record-player.zip

Le zip d'origine fournit les textures sous assets/minecraft/textures/item/music_disc_*.png ;
ce script les renomme selon l'id du vinyle attendu par contents/influvinyles/config.yml.
Seules les textures d'items sont importees : le modele 3D du jukebox vit deja dans
contents/influjukebox et les fichiers lang du zip sont volontairement ignores
(ils renommaient les disques vanilla).
"""
import os, re, sys, zipfile

DST = "/home/influencraft/serveurs/03_semirp/plugins/ItemsAdder/contents/influvinyles/textures/item"
CFG = "/home/influencraft/serveurs/03_semirp/plugins/ItemsAdder/contents/influvinyles/config.yml"


def mapping_depuis_config():
    """Relit le mapping texture d'origine -> id de vinyle dans les commentaires du config.yml."""
    m, texture_src = {}, None
    for ligne in open(CFG, encoding="utf-8"):
        c = re.search(r"texture d'origine : (\S+)\.png", ligne)
        if c:
            texture_src = c.group(1)
            continue
        i = re.match(r"  (vinyle_\w+):", ligne)
        if i and texture_src:
            m[texture_src] = i.group(1)
            texture_src = None
    return m


def main():
    if len(sys.argv) != 2:
        sys.exit("usage : import_vinyles_textures.py <record-player.zip>")
    zip_path = sys.argv[1]
    m = mapping_depuis_config()
    os.makedirs(DST, exist_ok=True)

    manquantes, importees = [], 0
    with zipfile.ZipFile(zip_path) as z:
        noms = set(z.namelist())
        for src, vid in sorted(m.items()):
            entree = "assets/minecraft/textures/item/%s.png" % src
            if entree not in noms:
                manquantes.append(src)
                continue
            with open(os.path.join(DST, vid + ".png"), "wb") as f:
                f.write(z.read(entree))
            importees += 1
            print("  %-28s -> %s.png" % (src + ".png", vid))

    print("\n%d/%d textures importees dans %s" % (importees, len(m), DST))
    if manquantes:
        print("MANQUANTES dans le zip : %s" % ", ".join(manquantes))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
