#!/usr/bin/env python3
"""Familiers instruments (rareté Mélodique) sur un serveur, directement dans ses dossiers :

    gen_familiers_musique.py <dossier plugins du serveur>

- BetterModel/models/familier_<id>.bbmodel : les modèles de scripts/personnages/familiers_musique.py
  (assets/personnages/familiers_musique/<id>/), à régénérer d'abord si un modèle a changé ;
- FluxPets/pets/<id>.yml : le familier, sans niveau (max-level 1), sans carte ni booster, sans effet ;
- FluxPets/content/pets.yml : les dix familiers en rareté « melodique » (rarities de FluxPets/config.yml).

Après coup : portraits.py (portraits des menus), /bm reload, /iazip, /influpet reload.
Voir docs/technique/familiers-musique.md.
"""
import os
import shutil
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(os.path.dirname(os.path.dirname(HERE)), "assets", "personnages", "familiers_musique")
ROOT = sys.argv[1]
PETS = os.path.join(ROOT, "FluxPets")
COLOR = "#1FBFA6"
# (id, nom, vole, ce qu'il joue, blocs musicaux joués dans le groupe, gestes d'une note pris à tour de rôle)
FAMILIERS = [
    ("flute", "Flûte", True, "la mélodie", ["flute"], []),
    ("cloche", "Cloche", False, "la mélodie à l'octave", ["bell"], ["hit_0"]),
    ("xylophone", "Xylophone", False, "les réponses entre les phrases", ["xylophone"], ["hit_l", "hit_r"]),
    ("vibraphone", "Vibraphone", False, "la seconde voix", ["iron_xylophone"], []),
    ("harpe", "Harpe", True, "les accords brisés", ["harp"], []),
    ("guitare", "Guitare", False, "les accords grattés", ["guitar"], []),
    ("banjo", "Banjo", False, "les croches picorées", ["banjo"], []),
    ("contrebasse", "Contrebasse", False, "la basse", ["bass"], []),
    ("pling", "Pling", True, "les notes qui scintillent", ["pling"], []),
    ("batterie", "Batterie", False, "le rythme", ["basedrum", "snare", "hat"], ["hit_l", "hit_r"]),
]


class NoAlias(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True


def dump(path, data, header):
    with open(path, "w", encoding="utf-8") as f:
        f.write(header)
        yaml.dump(data, f, Dumper=NoAlias, allow_unicode=True, sort_keys=False, width=1000)


def main():
    content = os.path.join(PETS, "content", "pets.yml")
    pets = yaml.safe_load(open(content, encoding="utf-8"))
    for pid, name, flying, part, instruments, gestures in FAMILIERS:
        shutil.copyfile(os.path.join(SRC, pid, f"familier_{pid}.bbmodel"),
                        os.path.join(ROOT, "BetterModel", "models", f"familier_{pid}.bbmodel"))
        lines = {1: [f"&#B376E5Dans l'orchestre : {part}."]}
        data = {
            "name": f"&{COLOR}{name}",
            "description": "&#B376E5Un instrument vivant, de la famille des familiers musiciens.",
            "entity-texture": f"bettermodel:familier_{pid}",
            "flying": flying,
            "icon": f"itemsadder:influportraits__portrait_{pid}",
            "reveal-animation": "happy",
            # joués dans le groupe des familiers musiciens (PetBands de FluxPets)
            "instruments": instruments,
            "band-gestures": gestures,
            "trail": {"enabled": False, "particle": "note", "gap": 2, "amount": 1, "y-offset": 0.0},
            "max-level": 1,
            "level-xp-requirements": [],
            "xp-gain-methods": [],
            "level-placeholders": [],
            "effects-description": lines,
            "rewards-description": lines,
            "level-up-messages": lines,
            "level-up-effects": [],
            "effects": [],
            "conditions": [],
            "activate-conditions": [],
            "spawn-egg": {"enabled": False, "item": "paper", "name": f"&{COLOR}{name}", "lore": [],
                          "craftable": False, "recipe": [], "withdrawable": False},
        }
        dump(os.path.join(PETS, "pets", pid + ".yml"), data,
             f"# {name} (familier instrument, rareté Mélodique, sans niveau).\n"
             "# Généré par scripts/influcard/gen_familiers_musique.py.\n\n")
        pets["pets"]["list"][pid] = {"rarity": "melodique", "enabled": True}
    dump(content, pets,
         "# Familiers obtenables dans les boosters (rareté, obtenable). Définitions : pets/<id>.yml.\n"
         "# Généré par scripts/influcard/gen_familiers.py + merge_config.py (légendaires de boss :\n"
         "# gen_familiers_boss.py, familiers instruments : gen_familiers_musique.py) ; modifié aussi par\n"
         "# /influpet menu.\n\n")
    print(len(FAMILIERS), "familiers instruments")


if __name__ == "__main__":
    main()
