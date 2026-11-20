#!/usr/bin/env python3
"""Adapte au sol les modèles déjà générés des familiers de familiers_sol.AU_SOL (cf. ce module).

Usage : poser_au_sol.py <dossier BetterModel/models> [id ...]
Sans id : tous les familiers de AU_SOL dont le modèle familier_<id>.bbmodel existe. Ne repose pas un modèle déjà
posé (relancer sans risque). gen_familiers.py le fait déjà à la génération ; ce script sert pour des modèles
existants. Ensuite, sur le serveur : /bm reload, /iazip, /influpet reload.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from familiers_sol import AU_SOL, poser  # noqa: E402

folder = sys.argv[1]
ids = sys.argv[2:] or sorted(AU_SOL)
for pid in ids:
    path = os.path.join(folder, f"familier_{pid}.bbmodel")
    if not os.path.exists(path):
        print(f"absent : {pid}")
        continue
    model = json.load(open(path, encoding="utf-8"))
    grounded = poser(model, pid)
    if grounded == model:
        print(f"déjà au sol : {pid}")
        continue
    with open(path, "w", encoding="utf-8") as f:
        json.dump(grounded, f, ensure_ascii=False)
    print(f"posé au sol : {pid}")
