#!/usr/bin/env python3
"""Génère les menus Flux des familiers (FluxPets ne fournit plus aucun menu de joueur).

    python3 gen_menu_familiers.py <plugins/Flux/menus>

- familiers.yml : ouvert par /pets (FluxPets : pet-settings.menu-command = « fluxmenu open familiers %player% »).
  Rangée 0 : retour au menu (0), les 5 emplacements de familiers actifs (2 à 6), informations (8, une carte :
  les familiers montent avec leurs cartes, débloqués / existants, actifs).
  Rangées 2 à 4 : familiers adoptés (7 x 3 = 21 par page, plus haut niveau d'abord, filtre en cours).
  Rangée 5 : filtre (46), précédent (48), tout ranger (49), suivant (50), cacher / montrer (52).
- familier.yml : progression d'un familier (celui choisi par /pets select) : sa carte (4), ses 10 niveaux
  (rangées 2 et 3, colonnes 2 à 6 : débloqué, en cours, verrouillé, avec leurs effets), retour (49).
Données : placeholders %influpet_view_...%, %influpet_slot_...%, %influpet_selected_...% ; actions : /pets
toggle|filter|page|select|view|visibility|deactivate (cf. docs/technique/influpet.md). Portraits :
itemsadder:influportraits:portrait_<id> (scripts/influcard/portraits.py).
"""
import os
import sys

OUT = sys.argv[1]
OR, VIOLET, VERT, ROUGE = "&#FFD94D", "&#B376E5", "&#2ECF7B", "&#FF5C5C"
GAUCHE, DROIT = "&r&7%img_left_click%", "&r&7%img_right_click%"
EFFETS = 8                      # lignes d'effets au plus (les vides sont retirées par Flux)
PORTRAIT = "itemsadder:influportraits:portrait_"
GRILLE = [r * 9 + c for r in range(2, 5) for c in range(1, 8)]
EMPLACEMENTS = list(range(2, 7))
NIVEAUX = [r * 9 + c for r in (2, 3) for c in range(2, 7)]
SON = "[sound] @click"
SON_PAGE = "[sound] @page"


def q(texte):
    return '"' + str(texte).replace("\\", "\\\\").replace('"', '\\"') + '"'


def bouton(L, cle, materiau, nom, slots, lore=(), gauche=(), droit=(), conditions=(), extra=()):
    L.append(f"  {cle}:")
    L.append(f"    material: {q(materiau)}")
    L.append(f"    name: {q(nom)}")
    L.append(f"    slots: [{', '.join(map(str, slots))}]")
    L.append("    flags: [HIDE_ATTRIBUTES]")
    L.extend(extra)
    if conditions:
        L.append("    view-requirements:")
        L.extend(f"      - {q('placeholder: ' + c)}" for c in conditions)
    if lore:
        L.append("    lore:")
        L.extend(f"      - {q(l)}" for l in lore)
    if gauche or droit:
        L.append("    actions:")
        if gauche:
            L.append(f"      left: [{', '.join(q(a) for a in gauche)}]")
        if droit:
            L.append(f"      right: [{', '.join(q(a) for a in droit)}]")
    L.append("")


def fiche(p):
    """Description, effets et progression d'un familier (préfixe de placeholder p, ex. influpet_view_3)."""
    return ([f"{OR}│ %{p}_description%", f"{OR}│"]
            + [f"{OR}│ %{p}_effects_{k}%" for k in range(1, EFFETS + 1)]
            + [f"{OR}│", f"{OR}│ {VIOLET}Progression : &f%{p}_progress% %"])


def entete(titre, commentaire):
    return [f"# Généré par plugins/fluxplugin/FluxPets/assets/gen_menu_familiers.py : ne pas modifier à la main.",
            f"# {commentaire}", "",
            f'title: ":offset_-8::{titre}:"', "rows: 6", "player-inventory: hidden", "vanilla-background: false",
            "update-interval: 20", ""]


# ------------------------------------------------------------------ familiers.yml
L = entete("menu_familiers", "Menu des familiers (FluxPets), ouvert par /pets.")
L += ["open-actions:", '  - "[player] pets view reset"', '  - "[sound] @page"', "",
      "items:", ""]
# Carte d'un familier (Phénix) : rappel que les familiers montent de niveau avec leurs cartes.
bouton(L, "infos", "itemsadder:influcards:carte_phenix", OR + "Familiers", [8],
       [f"{OR}│ {VIOLET}Tes familiers montent de niveau",
        f"{OR}│ {VIOLET}grâce à leurs cartes : fais un",
        f"{OR}│ {VIOLET}clic droit sur une carte pour",
        f"{OR}│ {VIOLET}la donner à son familier.",
        f"{OR}│",
        f"{OR}│ {VIOLET}Débloqué : &f%influpet_total_pets%{VIOLET}/&f%influpet_pets_count%",
        f"{OR}└ {VIOLET}Actifs : &f%influpet_active_pets%{VIOLET}/&f%influpet_max_active_pets%"])
bouton(L, "tout_ranger", "itemsadder:influitems:deny", OR + "Tout ranger", [49],
       [f"{OR}│ {VIOLET}Range tous tes familiers actifs.", f"{OR}└ {GAUCHE} &fTout ranger"],
       gauche=["[player] pets deactivate", SON, "[refresh]"], conditions=["%influpet_active_pets% >= 1"])

L.append("  # Emplacements des familiers actifs : occupé, libre ou verrouillé.")
for n, case in enumerate(EMPLACEMENTS, 1):
    p = f"influpet_slot_{n}"
    bouton(L, f"emplacement_{n}", PORTRAIT + f"%{p}%", f"%{p}_name% {OR}Niv. %{p}_level%", [case],
           fiche(p) + [f"{OR}│", f"{OR}├ {GAUCHE} &fVoir la progression", f"{OR}└ {DROIT} &fRanger"],
           gauche=[f"[player] pets select %{p}%", "[menu] familier"],
           droit=[f"[player] pets toggle %{p}%", SON, "[refresh]"],
           conditions=[f"%{p}_state% == active"], extra=["    priority: -2"])
    bouton(L, f"verrouille_{n}", "itemsadder:influitems:lock", ROUGE + "Emplacement verrouillé", [case],
           [f"{OR}│ {VIOLET}Tu n'as pas encore accès", f"{OR}│ {VIOLET}à cet emplacement.",
            f"{OR}└ {VIOLET}Grade requis : &f%{p}_grade%"],
           conditions=[f"%{p}_state% == locked"], extra=["    priority: -1"])
    bouton(L, f"libre_{n}", "itemsadder:influitems:add", OR + "Emplacement libre", [case],
           [f"{OR}│ {VIOLET}Clique sur un familier", f"{OR}└ {VIOLET}ci-dessous pour l'activer."])

L.append("  # Familiers adoptés de la page (actif : il brille).")
for k, case in enumerate(GRILLE, 1):
    p = f"influpet_view_{k}"
    nom = f"%{p}_name% {OR}Niv. %{p}_level%"
    bouton(L, f"actif_{k}", PORTRAIT + f"%{p}%", nom, [case],
           fiche(p) + [f"{OR}│", f"{OR}│ {VERT}Familier actif",
                       f"{OR}├ {GAUCHE} &fRanger", f"{OR}└ {DROIT} &fVoir la progression"],
           gauche=[f"[player] pets toggle %{p}%", SON, "[refresh]"],
           droit=[f"[player] pets select %{p}%", "[menu] familier"],
           conditions=[f"%{p}_active% == true"], extra=["    priority: -1", "    glow: true"])
    bouton(L, f"familier_{k}", PORTRAIT + f"%{p}%", nom, [case],
           fiche(p) + [f"{OR}│", f"{OR}├ {GAUCHE} &fActiver", f"{OR}└ {DROIT} &fVoir la progression"],
           gauche=[f"[player] pets toggle %{p}%", SON, "[refresh]"],
           droit=[f"[player] pets select %{p}%", "[menu] familier"],
           conditions=[f"%{p}_level% >= 1"])

L.append("  # Rangée du bas.")
bouton(L, "filtre", "itemsadder:influitems:filter", OR + "Filtrer", [46],
       [f"%influpet_view_filter_line_{k}%" for k in range(1, 16)]
       + [f"{OR}│", f"{OR}├ {GAUCHE} &fFiltre suivant", f"{OR}└ {DROIT} &fFiltre précédent"],
       gauche=["[player] pets filter next", SON, "[refresh]"], droit=["[player] pets filter previous", SON, "[refresh]"])
bouton(L, "precedent", "itemsadder:influitems:btn_prev", OR + "Page précédente", [48],
       [f"{OR}└ {GAUCHE} &fPage précédente"], gauche=["[player] pets page previous", SON_PAGE, "[refresh]"],
       conditions=["%influpet_view_page% >= 2"])
bouton(L, "retour", "itemsadder:influitems:btn_back", OR + "Retour", [0],
       [f"{OR}│ {VIOLET}Revenir au menu.", f"{OR}└ {GAUCHE} &fMenu principal"], gauche=["[menu] main"])
bouton(L, "suivant", "itemsadder:influitems:btn_next", OR + "Page suivante", [50],
       [f"{OR}└ {GAUCHE} &fPage suivante"], gauche=["[player] pets page next", SON_PAGE, "[refresh]"],
       conditions=["%influpet_view_pages% > %influpet_view_page%"])
bouton(L, "montrer", "ENDER_EYE", OR + "Montrer mes familiers", [52],
       [f"{OR}│ {VIOLET}Tes familiers sont cachés :", f"{OR}│ {VIOLET}ils ne te suivent plus.",
        f"{OR}└ {GAUCHE} &fLes montrer"],
       gauche=["[player] pets visibility", SON, "[refresh]"], conditions=["%influpet_hidden% == true"],
       extra=["    priority: -1"])
bouton(L, "cacher", "ENDER_PEARL", OR + "Cacher mes familiers", [52],
       [f"{OR}│ {VIOLET}Leurs effets restent actifs,", f"{OR}│ {VIOLET}ils ne te suivent plus.",
        f"{OR}└ {GAUCHE} &fLes cacher"],
       gauche=["[player] pets visibility", SON, "[refresh]"])
open(os.path.join(OUT, "familiers.yml"), "w", encoding="utf-8").write("\n".join(L).rstrip() + "\n")

# ------------------------------------------------------------------ familier.yml
p = "influpet_selected"
L = entete("menu_familier", "Progression d'un familier (choisi par /pets select), ouverte depuis le menu des familiers.")
L += ["items:", ""]
# Exception : ici, la carte du familier plutôt que son portrait (demande du 2026-10-15).
bouton(L, "portrait", f"itemsadder:influcards:carte_%{p}%", f"%{p}_name% {OR}Niv. %{p}_level%", [4],
       [f"{OR}│ %{p}_description%", f"{OR}│",
        f"{OR}│ {VIOLET}Niveau : &f%{p}_level%{VIOLET}/&f%{p}_max_level%",
        f"{OR}└ {VIOLET}Progression : &f%{p}_progress% %"])
for niveau, case in enumerate(NIVEAUX, 1):
    effets = [f"{OR}│ %{p}_rewards_{niveau}_{k}%" for k in range(1, EFFETS + 1)]
    nom = OR + f"Niveau {niveau}"
    garde = f"%{p}_max_level% >= {niveau}"
    bouton(L, f"niveau_{niveau}_debloque", "itemsadder:influitems:checkmark", nom, [case],
           effets + [f"{OR}│", f"{OR}└ {VERT}Débloqué"],
           conditions=[garde, f"%{p}_level_{niveau}% == unlocked"], extra=[f"    amount: {niveau}", "    priority: -2"])
    bouton(L, f"niveau_{niveau}_en_cours", "itemsadder:influitems:tracking", nom, [case],
           effets + [f"{OR}│", f"{OR}│ {VIOLET}Progression : &f%{p}_progress% %",
                     f"{OR}└ {VIOLET}Cartes : &f%{p}_current_xp%{VIOLET}/&f%{p}_required_xp%"],
           conditions=[garde, f"%{p}_level_{niveau}% == current"], extra=[f"    amount: {niveau}", "    priority: -1"])
    bouton(L, f"niveau_{niveau}_verrouille", "itemsadder:influitems:lock", nom, [case],
           effets + [f"{OR}│", f"{OR}└ {ROUGE}Verrouillé"],
           conditions=[garde], extra=[f"    amount: {niveau}"])
bouton(L, "retour", "itemsadder:influitems:btn_back", OR + "Retour", [49],
       [f"{OR}│ {VIOLET}Revenir aux familiers.", f"{OR}└ {GAUCHE} &fFamiliers"], gauche=["[menu] familiers"])
open(os.path.join(OUT, "familier.yml"), "w", encoding="utf-8").write("\n".join(L).rstrip() + "\n")
print("familiers.yml, familier.yml écrits dans", OUT)
