#!/usr/bin/env python3
"""Écrit le contenu généré par gen_familiers.py dans les fichiers de contenu d'InfluPet :
content/cards.yml (card-xp, cards), content/boosters.yml (boosters), content/pets.yml (pets.list).
config.yml et les textes (lang/) ne sont pas touchés (2026-10-08 : config découpée, cf. docs/technique/fluxpets.md).
Usage : merge_config.py <contenu.yml généré> <dossier plugins/InfluPet>"""
import os
import sys

import yaml

contenu, plugin_dir = sys.argv[1:3]
data = yaml.safe_load(open(contenu, encoding="utf-8"))
files = {
    "cards.yml": ("# Cartes (une par familier, liée par « pet ») et XP donnée au familier par carte, selon sa rareté.\n"
                   "# Généré par scripts/influcard/gen_familiers.py + merge_config.py ; modifié aussi par /influpet menu.\n",
                   {"card-xp": data["card-xp"], "cards": data["cards"]}),
    "boosters.yml": ("# Boosters : un par carrière (seul moyen d'adopter ses familiers), un Prestige, 4 paliers (comme les\n"
                     "# caisses). pet-chance = % de chance qu'un tirage soit un familier plutôt qu'une carte.\n"
                     "# Généré par scripts/influcard/gen_familiers.py + merge_config.py ; modifié aussi par /influpet menu.\n",
                     {"boosters": data["boosters"]}),
    "pets.yml": ("# Familiers obtenables dans les boosters (rareté, obtenable). Définitions : pets/<id>.yml.\n"
                      "# Généré par scripts/influcard/gen_familiers.py + merge_config.py ; modifié aussi par /influpet menu.\n",
                      {"pets": {"list": data["pets"]["list"]}}),
}
os.makedirs(os.path.join(plugin_dir, "content"), exist_ok=True)
for name, (header, block) in files.items():
    with open(os.path.join(plugin_dir, "content", name), "w", encoding="utf-8") as f:
        f.write(header + "\n")
        yaml.safe_dump(block, f, allow_unicode=True, sort_keys=False, width=1000)
    print("ok", name)
