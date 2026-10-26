"""Génère le pack ItemsAdder « influroad » (chemins de progression Flux).

Chaque pièce du kit (25 PNG : 5 pièces x 5 états) devient une font_image par hauteur
de pose : une font_image a une hauteur fixe (y_position), donc une entrée par pièce et
par position verticale, toutes pointant vers le même PNG. Nom : <pièce>_<état>_y<Y>
où Y est le haut de la pièce en coordonnées de la fenêtre (0 = haut de la fenêtre).
Flux (PathRenderer) calcule les mêmes noms et les décalages horizontaux.

Usage : python3 gen_influroad.py <dossier du kit> <contents/influroad>
"""
import os, shutil, sys

KIT, OUT = sys.argv[1], sys.argv[2]
PIECES = ["chemin_h", "chemin_v", "chemin_h_demi", "chemin_v_demi", "chemin_noeud"]
STATES = ["eteint", "vert", "or", "bleu", "rose"]
HEIGHT = {"chemin_h": 3, "chemin_v": 19, "chemin_h_demi": 3, "chemin_v_demi": 10, "chemin_noeud": 5}

# Haut des cases d'un menu de 6 rangées : coffre, 3 rangées d'inventaire, barre d'action.
ROWS = [18 + 18 * r for r in range(6)] + [139, 157, 175] + [197]
# Inventaire d'un coffre de 2 rangées (menu de l'outil, FluxTools) : 103 + (2 - 4) x 18, puis barre d'action.
ROWS += [67, 85, 103, 125]
# Décalage vertical de chaque pièce par rapport au haut de la case (cf. LISEZMOI.md).
DY = {"chemin_h": [7], "chemin_h_demi": [7], "chemin_v": [8], "chemin_v_demi": [-1, 8], "chemin_noeud": [6]}

os.makedirs(f"{OUT}/textures/chemin", exist_ok=True)
for p in PIECES:
    for s in STATES:
        shutil.copy(f"{KIT}/{p}_{s}.png", f"{OUT}/textures/chemin/{p}_{s}.png")

lines = ["info:", "  namespace: influroad", "",
         "# Généré par plugins/fluxplugin/Flux/assets/influroad/gen_influroad.py, ne pas éditer à la main.",
         "# Pièces du chemin de progression Flux, une entrée par hauteur de pose (y<Y>).",
         "# y_position = 13 - Y : le haut de la pièce tombe Y px sous le haut de la fenêtre.", "",
         "font_images:"]
count = 0
for p in PIECES:
    ys = sorted({row + dy for row in ROWS for dy in DY[p]})
    for s in STATES:
        for y in ys:
            lines += [f"  {p}_{s}_y{y}:", "    suggest_in_command: false", "    show_in_gui: false",
                      f"    path: chemin/{p}_{s}.png", f"    scale_ratio: {HEIGHT[p]}", f"    y_position: {13 - y}"]
            count += 1
open(f"{OUT}/config.yml", "w").write("\n".join(lines) + "\n")
print(count, "font_images écrites dans", OUT)
