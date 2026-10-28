#!/usr/bin/env python3
"""Génère les menus Flux des catégories de collections (un menu par catégorie EcoCollections).

    python3 gen_collections.py <plugins/EcoCollections> <plugins/FluxTools/menus>

Écrit menus/collection_<catégorie>.yml, ouvert par le menu Collection ([menu] collection_<id>).
À relancer (puis /fluxmenu reload) quand on ajoute, retire ou déplace une collection.

Disposition (fond influmenu:menu_collection_categorie) :
- case 4 : icône de la catégorie (terminées / total, pourcentage) ;
- rangées 1 à 4, colonnes 1 à 7 : les collections, 28 par page, dans l'ordre de leur position
  dans EcoCollections (page, rangée, colonne) ; clic = la sélectionner ;
- rangée 5 : précédent (48), retour au menu Collection (49), suivant (50) ;
- inventaire, 1re rangée au milieu (58) : la collection sélectionnée ; 2e rangée (64 à 70) :
  barre de progression de ses paliers (chemin influroad, %fluxcoll_selected_tier%).
"""
import glob
import os
import sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../Flux/assets"))
from lore_tags import convert_yaml  # noqa: E402  (lore lines in the tags of Flux's style.yml)

import yaml

ECO, OUT = sys.argv[1], sys.argv[2]
GRILLE = [r * 9 + c for r in range(1, 5) for c in range(1, 8)]
PAR_PAGE = len(GRILLE)
BARRE = list(range(64, 71))
OR, VIOLET = "&#FFD94D", "&#B376E5"
CLIC = "&r&7%img_left_click%"


def q(texte):
    """Chaîne YAML entre guillemets doubles."""
    return '"' + str(texte).replace("\\", "\\\\").replace('"', '\\"') + '"'


def icone(brut):
    """« cod custom-model-data:52092 » -> (COD, 52092) ; « player_head » -> player-head."""
    morceaux = str(brut).split()
    materiau = morceaux[0]
    cmd = None
    for m in morceaux[1:]:
        if m.startswith("custom-model-data:"):
            cmd = int(m.split(":", 1)[1])
    if materiau.lower() == "player_head":
        return "player-head", cmd
    return materiau.upper(), cmd


def objet(lignes, cle, materiau, cmd, nom, slots, lore, actions=None, extra=()):
    lignes.append(f"  {cle}:")
    lignes.append(f"    material: {q(materiau)}")
    if cmd is not None:
        lignes.append(f"    custom-model-data: {cmd}")
    lignes.append(f"    name: {q(nom)}")
    lignes.append(f"    slots: [{', '.join(map(str, slots))}]")
    lignes.append("    flags: [HIDE_ATTRIBUTES]")
    lignes.extend(extra)
    if lore:
        lignes.append("    lore:")
        lignes.extend(f"      - {q(l)}" for l in lore)
    if actions:
        lignes.append("    actions:")
        lignes.append(f"      left: [{', '.join(q(a) for a in actions)}]")
    lignes.append("")


def indenter(lignes, n):
    return [(" " * n + l) if l else l for l in lignes]


groupes = {}
for f in glob.glob(os.path.join(ECO, "groups", "*.yml")):
    gid = os.path.splitext(os.path.basename(f))[0]
    if not gid.startswith("_"):
        groupes[gid] = yaml.safe_load(open(f, encoding="utf-8"))

collections = {}
for f in glob.glob(os.path.join(ECO, "collections", "*", "*.yml")):
    cid = os.path.splitext(os.path.basename(f))[0]
    if cid.startswith("_"):
        continue
    d = yaml.safe_load(open(f, encoding="utf-8"))
    pos = d.get("gui", {}).get("position", {})
    collections.setdefault(d["group"], []).append(
        ((pos.get("page", 1), pos.get("row", 0), pos.get("column", 0), cid), cid, d))

for gid, g in sorted(groupes.items()):
    liste = [(cid, d) for _, cid, d in sorted(collections.get(gid, []), key=lambda t: t[0])]
    if not liste:
        continue
    nom_groupe = g["name"].split("Voir ", 1)[-1] if "Voir " in g["name"] else g["name"]
    pages = [liste[i:i + PAR_PAGE] for i in range(0, len(liste), PAR_PAGE)]
    L = [f"# Généré par plugins/fluxplugin/FluxTools/assets/gen_collections.py : ne pas modifier à la main.",
         f"# Catégorie « {nom_groupe} » du menu Collection : {len(liste)} collection(s), {len(pages)} page(s).",
         "",
         'title: ":offset_-8::menu_collection_categorie:"',
         "rows: 6",
         "player-inventory: hidden",
         "vanilla-background: false",
         "update-interval: 40",
         "",
         "# À l'ouverture : 1re page, 1re collection sélectionnée.",
         "open-actions:",
         f"  - {q('[fluxcoll] select ' + liste[0][0])}",
         f"  - {q('[page-swap] ' + gid + '_1')}",
         '  - "[sound] @page"',
         "",
         "# Paliers de la collection sélectionnée (2e rangée de l'inventaire).",
         "paths:",
         "  progression:",
         f"    slots: [{', '.join(map(str, BARRE))}]",
         '    progress: "%fluxcoll_selected_tier%"',
         "    color: or",
         "    start: true",
         "    end: true",
         "",
         "items:",
         ""]
    gmat, gcmd = icone(g.get("gui", {}).get("icon", "book"))
    objet(L, "categorie", gmat, gcmd, OR + nom_groupe, [4],
          [f"{OR}│ {VIOLET}Collection : &f%fluxcoll_{gid}_completed%{VIOLET}/&f%fluxcoll_{gid}_total%",
           f"{OR}└ {VIOLET}Progression : &f%fluxcoll_{gid}_percent% %"])
    objet(L, "retour", "itemsadder:influitems:btn_back", None, OR + "Retour", [49],
          [f"{OR}│ {VIOLET}Revenir aux catégories.", f"{OR}└ {CLIC} &fCollection"], ["[menu] collection"])
    objet(L, "barre", "itemsadder:influitems:invisible", None, OR + "Paliers", BARRE,
          [f"{OR}│ {VIOLET}Palier : &f%fluxcoll_selected_tier%{VIOLET}/&f%fluxcoll_selected_max_tier%",
           f"{OR}└ {VIOLET}Progression : &f%fluxcoll_selected_count%{VIOLET}/&f%fluxcoll_selected_required%"])
    L.append("  # Collection sélectionnée (case 58) : une variante par collection.")
    for cid, d in liste:
        mat, cmd = icone(d.get("gui", {}).get("icon", "paper"))
        objet(L, "selection_" + cid, mat, cmd, d.get("name", cid), [58],
              [f"{OR}│ {VIOLET}Palier : &f%ecocollections_{cid}%{VIOLET}/&f%ecocollections_{cid}_max_tier%",
               f"{OR}└ {VIOLET}Progression : &f%ecocollections_{cid}_count%{VIOLET}/&f%ecocollections_{cid}_required%"],
              extra=["    view-requirements:", f"      - {q('placeholder: %fluxcoll_selected% == ' + cid)}"])

    L += ["pages:", ""]
    for n, page in enumerate(pages, 1):
        P = []
        if n > 1:
            objet(P, "precedent", "itemsadder:influitems:btn_prev", None, OR + "Page précédente", [48],
                  [f"{OR}└ {CLIC} &fPage {n - 1}"], [f"[page-swap] {gid}_{n - 1}"])
        else:
            objet(P, "precedent", "itemsadder:influitems:btn_prev", None, OR + "Page précédente", [48],
                  [f"{OR}└ {VIOLET}Tu es sur la première page."])
        if n < len(pages):
            objet(P, "suivant", "itemsadder:influitems:btn_next", None, OR + "Page suivante", [50],
                  [f"{OR}└ {CLIC} &fPage {n + 1}"], [f"[page-swap] {gid}_{n + 1}"])
        else:
            objet(P, "suivant", "itemsadder:influitems:btn_next", None, OR + "Page suivante", [50],
                  [f"{OR}└ {VIOLET}Tu es sur la dernière page."])
        for (cid, d), slot in zip(page, GRILLE):
            mat, cmd = icone(d.get("gui", {}).get("icon", "paper"))
            lore = [f"{OR}│ {VIOLET}Palier : &f%ecocollections_{cid}%{VIOLET}/&f%ecocollections_{cid}_max_tier%",
                    f"{OR}│ {VIOLET}Progression : &f%ecocollections_{cid}_count%{VIOLET}/&f%ecocollections_{cid}_required%"]
            # Variante brillante quand elle est sélectionnée (priorité -1 : passe devant).
            objet(P, "choisie_" + cid, mat, cmd, d.get("name", cid), [slot],
                  lore + [f"{OR}└ {VIOLET}Affichée en bas du menu."],
                  extra=["    priority: -1", "    glow: true", "    view-requirements:",
                         f"      - {q('placeholder: %fluxcoll_selected% == ' + cid)}"])
            objet(P, "c_" + cid, mat, cmd, d.get("name", cid), [slot],
                  lore + [f"{OR}└ {CLIC} &fVoir la progression"],
                  [f"[fluxcoll] select {cid}", "[sound] @click"])
        L += [f"  {gid}_{n}:", "    items:", ""] + indenter(P, 4)

    chemin = os.path.join(OUT, f"collection_{gid}.yml")
    open(chemin, "w", encoding="utf-8").write(convert_yaml("\n".join(L).rstrip() + "\n"))
    print(f"{gid}: {len(liste)} collections, {len(pages)} page(s) -> {chemin}")
