#!/usr/bin/env python3
"""Génère le menu Carrières de Flux (Labs) : menus/metiers.yml et menus/_pages/metiers.yml.

    python3 gen_metiers.py <plugins/FluxTools/menus> <FluxPets/content/cards.yml>

Fond influmenu:menu_metier (176 x 221), tout dans un seul menu, sans l'en-tête du menu principal :
  rangée 0-8                 : retour (0), rejoindre / quitter (7, quitter = double clic), nom du métier
                               écrit au centre (influmetier:nom_<id>, dans le titre), icône Carrières (8)
  cases 9 / 18 / 27 / 36     : les 4 familiers FluxPets du métier (simple, unique, élite, ultime), en portrait
  cadre (colonnes 2 à 6)     : illustration du métier choisi (influmetier:metier_<id>, dans le titre)
  cases 15-17 ... 42-44      : les 12 métiers, une rangée par famille
  inventaire 55-61 / 64-70 / 73-79 : actions rémunérées (21 par page), flèches 63 et 71
  rangée 45-53               : barre des 7 niveaux autour du niveau du joueur (46-52, chemin vert),
                               flèches 45 / 53 pour décaler la fenêtre ([fluxjob] <id> prev|next)
  barre d'action 81-89       : barre d'XP vers le niveau suivant (chemin vert sur les 9 cases vides,
                               9 segments), sans objet ; niveau écrit juste au-dessus
                               (chiffres influmetier:chiffre_<n>, %fluxjob_<id>_levelglyphs_169%)
Une page par métier (metiers_<id>), sans animation : titre, familiers (%influpet_<familier>_level%) et barres.
Grille des métiers, niveaux, actions rémunérées (paginées par Flux) et rejoindre / quitter sont des listes de
Flux construites par FluxTools (JobMenu) : rien à régénérer quand les actions ou les niveaux changent.
À relancer après un changement de cartes ou de métiers (liste METIERS), puis /fluxmenu reload.
"""
import json
import os
import sys

import yaml

# gen_pages (page helpers of the main menu) stays with Flux
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "Flux", "assets"))
from gen_pages import item, page, q, brut, CLIC_G, OR, VIOLET, ROUGE  # noqa: E402

FOND = ":offset_-8::menu_metier:"
# Après le fond (avance 177 - 8 = 169 depuis le début du titre), l'illustration va en x = 38 :
# 38 - 8 = 30 depuis le début du titre, d'où un décalage de 30 - 169.
DECALAGE_PHOTO = 30 - 169
VERT = "&#2ECF7B"
CARTES = [9, 18, 27, 36]
METIER_CASES = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
NIVEAUX = list(range(46, 53))
BARRE_XP = list(range(81, 90))
RETOUR, ADHESION, INFO = 0, 7, 8
FLECHE_PREV, FLECHE_NEXT = 45, 53
# Curseur du titre après le fond (169) puis l'illustration (offset -139, glyphe 64 + 1) : 95.
CURSEUR_APRES_PHOTO = 169 + DECALAGE_PHOTO + 65
ACTIONS = list(range(55, 62)) + list(range(64, 71)) + list(range(73, 80))   # 7 colonnes x 3 rangées
PREV, NEXT = 63, 71
RARETES = [("simple", "Commune"), ("unique", "Rare"), ("elite", "Élite"), ("ultimate", "Ultime")]

# (id EcoJobs, nom, objet, série FluxPets, description courte) ; une rangée par famille.
METIERS = [
    ("mineur", "Mineur", "DIAMOND_PICKAXE", "Mineur", ["Mine les minerais,", "du charbon au diamant."]),
    ("rochiste", "Rochiste", "COBBLESTONE", "Rochiste", ["Casse la roche : pierre,", "andésite, basalte..."]),
    ("deblayeur", "Déblayeur", "IRON_SHOVEL", "Déblayeur", ["Dégage terre, sable,", "gravier et neige."]),
    ("cultivateur", "Cultivateur", "WHEAT", "Cultivateur", ["Récolte blé, carottes,", "melons et canne à sucre."]),
    ("herboriste", "Herboriste", "POPPY", "Herboriste", ["Cueille fleurs, bambou,", "champignons et algues."]),
    ("sylviculteur", "Sylviculteur", "IRON_AXE", "Sylviculteur", ["Abat les arbres,", "toutes essences confondues."]),
    ("fondeur", "Fondeur", "FURNACE", "Fondeur", ["Fond minerais et", "aliments au four."]),
    ("chimiste", "Chimiste", "BREWING_STAND", None, ["Brasse des potions", "à l'alambic."]),
    ("enchanteur", "Enchanteur", "ENCHANTING_TABLE", "Enchanteur", ["Enchante à la table", "ou à l'enclume."]),
    ("pecheur", "Pêcheur", "FISHING_ROD", "Pêcheur", ["Pêche poissons,", "trésors et objets rares."]),
    ("defenseur", "Défenseur", "IRON_SWORD", "Défenseur", ["Élimine les créatures", "hostiles."]),
    ("batisseur", "Bâtisseur", "BRICKS", "Bâtisseur", ["Pose des blocs", "de construction."]),
]


def cartes(serie, toutes):
    """Les 4 cartes du métier (cases 0, 9, 18, 27), de commune à ultime."""
    its = []
    if serie is None:
        for k, slot in enumerate(CARTES):
            its.append(item(f"carte{k + 1}", "itemsadder:influitems:lock", "Pas de carte", slot,
                            ["Ce métier n'a pas encore", "de série de cartes."]))
        return its
    par_rarete = {c["rarity"]: (cid, c) for cid, c in toutes.items() if c.get("series") == serie}
    for k, (rarete, libelle) in enumerate(RARETES):
        if rarete not in par_rarete:
            continue
        cid, c = par_rarete[rarete]
        desc = [l for l in c.get("lore") or [] if l and "Série" not in l]
        pet = c.get("pet") or cid
        nom = c.get("name", cid)
        # Portrait du familier (influportraits, scripts/influcard/portraits.py) plutôt que sa carte (2026-10-15).
        portrait = "itemsadder:influportraits:portrait_" + pet
        its.append(item(f"carte{k + 1}_obtenue", portrait, "", CARTES[k],
                        [f"Rareté : &r&f%img_{rarete}%"] + desc +
                        [VERT + f"Familier niveau &f%influpet_{pet}_level%" + VERT + "."], priorite=1, glow=True,
                        conditions=[f"placeholder: %influpet_{pet}_level% >= 1"]).replace(f'name: "{OR}"', f"name: {q(nom)}"))
        its.append(item(f"carte{k + 1}", portrait, "", CARTES[k],
                        [f"Rareté : &r&f%img_{rarete}%"] + desc + [ROUGE + "Pas encore adopté."],
                        priorite=2).replace(f'name: "{OR}"', f"name: {q(nom)}"))
    return its


def chemins(jid):
    """Barre des 7 niveaux (46-52) et barre d'XP (81-89) : chemins verts allumés par Flux."""
    return {
        "niveaux": {"slots": "[" + ", ".join(map(str, NIVEAUX)) + "]", "progress": q(f"%fluxjob_{jid}_window%"),
                    "color": "vert", "start": "false", "end": "false"},
        "xp": {"slots": "[" + ", ".join(map(str, BARRE_XP)) + "]", "progress": q(f"%fluxjob_{jid}_xpsteps%"),
               "color": "vert", "start": "true", "end": "true"},
    }


def liste(lid, slots, actions=None, extra=()):
    """Liste de Flux remplie par FluxTools (JobMenu) : objets construits en Java, posés sur ces cases."""
    y = [f"  {lid}:", f"    slots: {', '.join(map(str, slots))}", "    item:", "      material: entry"]
    y += [f"      {l}" for l in extra]
    if actions:
        y.append("      actions:")
        y.append(f"        left: [{', '.join(q(a) for a in actions)}]")
    return "\n".join(y)


def main():
    menus, cards_yml = sys.argv[1:3]
    toutes = yaml.safe_load(open(cards_yml, encoding="utf-8"))["cards"]
    base = [
        item("retour", "itemsadder:influitems:btn_back", "Retour", RETOUR, ["Revenir au menu."],
             [CLIC_G + "Menu principal"], {"left": ["[menu] main"]}),
        item("info", "IRON_PICKAXE", "Carrières", INFO,
             ["Choisis un métier à droite :", "ses cartes, ses niveaux et ses", "actions rémunérées s'affichent.",
              "", "Métiers actifs : &f%ecojobs_in_jobs%&7/&f%ecojobs_limit%"]).replace(
            "        name:", "        flags: [HIDE_ATTRIBUTES]\n        name:", 1),
        item("actions_prec", "itemsadder:influitems:btn_prev", "Page précédente", PREV, [],
             clics={"left": ["[list] metiers_actions prev", "[sound] @page"]}, cacher=True,
             conditions=["placeholder: %flux_list_metiers_actions_has_prev% == true"]),
        item("actions_suiv", "itemsadder:influitems:btn_next", "Page suivante", NEXT, [],
             clics={"left": ["[list] metiers_actions next", "[sound] @page"]}, cacher=True,
             conditions=["placeholder: %flux_list_metiers_actions_has_next% == true"]),
    ]
    listes = [
        liste("metiers_grille", METIER_CASES,
              ["[fluxjob] %entry_id% reset", "[page-swap] metiers_%entry_id%", "[sound] @page"]),
        liste("metiers_niveaux", NIVEAUX),
        liste("metiers_niveaux_prec", [FLECHE_PREV], ["[fluxjob] %entry_id% prev", "[sound] @page"], ["hide-tooltip: true"]),
        liste("metiers_niveaux_suiv", [FLECHE_NEXT], ["[fluxjob] %entry_id% next", "[sound] @page"], ["hide-tooltip: true"]),
        liste("metiers_actions", ACTIONS),
        liste("metiers_adhesion", [ADHESION], ["[fluxjob] %entry_id% adhesion"], ['confirm: "%entry_confirm%"']),
    ]
    corps = "\n\n".join("\n".join(l[4:] for l in b.split("\n")) for b in base)
    with open(os.path.join(menus, "metiers.yml"), "w", encoding="utf-8") as f:
        f.write(f"""# Généré par plugins/fluxplugin/FluxTools/assets/gen_metiers.py : ne pas modifier à la main.
# Menu Carrières (EcoJobs), remplace /metiers, /carrieres (DeluxeMenus) et /job, /jobs (EcoJobs) sur Labs.
# Les métiers, niveaux, actions et le bouton rejoindre / quitter sont construits par FluxTools (JobMenu, listes
# metiers_*) ; ordre, icônes et descriptions des métiers : modules.jobs.menu de plugins/FluxTools/config.yml.

title: "{FOND}"
rows: 6
commands: [metiers, carrieres, job, jobs]
override-commands: true
player-inventory: hidden
vanilla-background: false
update-interval: 20

# À l'ouverture, le Mineur est sélectionné.
open-actions:
  - "[fluxjob] mineur reset"
  - "[page-swap] metiers_mineur"
  - "[sound] @page"

page-files:
  - _pages/metiers.yml

lists:

{chr(10).join(listes)}

items:

{corps}
""")
    largeurs = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "Flux", "assets", "metiers", "noms.json")))
    pages = []
    for jid, nom, objet, serie, desc in METIERS:
        # Nom du métier centré sur la rangée du bas : x = 88 - largeur / 2 dans la fenêtre.
        decalage_nom = (88 - largeurs[jid] // 2 - 8) - CURSEUR_APRES_PHOTO
        # Niveau écrit au-dessus de la barre d'XP (avance nette nulle : n'influe pas sur la suite).
        niveau_ecrit = f"%fluxjob_{jid}_levelglyphs_169%"
        titre = q(f"{FOND}{niveau_ecrit}:offset_{DECALAGE_PHOTO}::metier_{jid}::offset_{decalage_nom}::nom_{jid}:")
        pages.append(page(f"metiers_{jid}", cartes(serie, toutes), chemins(jid), titre, animation=False))
    os.makedirs(os.path.join(menus, "_pages"), exist_ok=True)
    with open(os.path.join(menus, "_pages", "metiers.yml"), "w", encoding="utf-8") as f:
        f.write("# Généré par plugins/fluxplugin/FluxTools/assets/gen_metiers.py : ne pas modifier à la main.\n"
                "# Pages du menu Carrières, une par métier : titre, familiers du métier, barres de niveaux et d'XP.\n\npages:\n\n"
                + "\n".join(pages) + "\n")
    print(len(pages), "pages")


if __name__ == "__main__":
    main()
