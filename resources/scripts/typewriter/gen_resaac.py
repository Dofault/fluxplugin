#!/usr/bin/env python3
"""Génère la page Typewriter des placements du nouveau spawn Resaac (monde resaac, Labs).

Usage : python3 gen_resaac.py [dossier plugins du serveur]     (Labs par défaut)   puis `typewriter reload`

Écrit Typewriter/pages/resaac.json (et sa copie staging) :
  - l'hologramme « MONDE RPG » au-dessus du portail RPG (16.5, 4, 53), tourné vers le nord (yaw 180, FIXED) : deux
    images ItemsAdder côte à côte (pixel art + logo), produites par scripts/hologrammes/gen_monde_rpg.py ;
  - l'hologramme « VILLE DU MOIS » (-16, 4, 51.1), même hauteur et même orientation (étoile + logo,
    scripts/hologrammes/gen_ville_du_mois.py).
Les caractères des images sont lus dans le cache d'ItemsAdder (storage/font_images_unicode_cache.yml) : lancer /iazip
après gen_monde_rpg.py et avant ce script. Tout se déplace ensuite dans le panel Typewriter ; relancer le script
écrase ces réglages (identifiants stables).
"""
import sys

from twlib import Page

PLUGINS = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins"
page = Page(PLUGINS, "resaac", "resaac", "resaac")
add, pos = page.add, page.pos


def glyph(name):
    return page.glyph(f"influhologrammes:{name}")


# ------------------------------------------------------------------------------------------- MONDE RPG over the portal
add("hologram", "portail_rpg_titre", text=f"<white>{glyph('monde_rpg_icone')} {glyph('monde_rpg_titre')}</white>",
    spawnLocation=pos(15.5, 4, 50.99, 180), billboardConstraint="FIXED", shadow=False, seeThrough=False,
    backgroundColor=0, opacity=-1, lineWidth=400, textAlignment="CENTER", data=[])

# ------------------------------------------------------------------------------------------- VILLE DU MOIS
# Même hauteur et même orientation que MONDE RPG (images de scripts/hologrammes/gen_ville_du_mois.py).
add("hologram", "ville_du_mois_titre", text=f"<white>{glyph('ville_du_mois_icone')} {glyph('ville_du_mois_titre')}</white>",
    spawnLocation=pos(-16, 4, 51.1, 180), billboardConstraint="FIXED", shadow=False, seeThrough=False,
    backgroundColor=0, opacity=-1, lineWidth=400, textAlignment="CENTER", data=[])

page.write()
