#!/usr/bin/env python3
"""Génère le menu Carrières de Flux (Labs) : menus/metiers.yml et menus/_pages/metiers.yml.

    python3 gen_metiers.py <dossier menus/ de Flux> <dossier EcoJobs/jobs> <FluxPets/content/cards.yml>

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
Une page par métier et par page d'actions (metiers_<id>_<n>), sans animation. Données : EcoJobs
(%ecojobs_...%), Flux (%fluxjob_...% : actions rémunérées, fenêtre de niveaux), FluxPets
(%influpet_<familier>_level%). Rejoindre / quitter : plus tard.
À relancer après un changement de métiers, d'actions rémunérées ou de cartes, puis /fluxmenu reload.
"""
import json
import math
import os
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_pages import item, page, q, brut, CLIC_G, OR, VIOLET, ROUGE  # noqa: E402

FOND = ":offset_-8::menu_metier:"
# Après le fond (avance 177 - 8 = 169 depuis le début du titre), l'illustration va en x = 38 :
# 38 - 8 = 30 depuis le début du titre, d'où un décalage de 30 - 169.
DECALAGE_PHOTO = 30 - 169
VERT = "&#2ECF7B"
CARTES = [9, 18, 27, 36]
METIER_CASES = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
NIVEAUX = list(range(46, 53))
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
FLAGS = "        flags: [HIDE_ATTRIBUTES]"


def avec_flags(bloc, objet):
    """Les outils et armes affichent leurs attributs : on les masque."""
    if any(objet.endswith(s) for s in ("_PICKAXE", "_SHOVEL", "_AXE", "_SWORD")):
        lignes = bloc.split("\n")
        return "\n".join(lignes[:2] + [FLAGS] + lignes[2:])
    return bloc


def niveau(jid):
    return [f"Niveau : &f%ecojobs_{jid}_level%", f"Progression : &f%ecojobs_{jid}_percentage_progress%%"]


def nb_actions(jobs_dir, jid):
    """Même règle que Flux (JobInfo) : blocs d'une liste positive, sinon une entrée par déclencheur."""
    conf = yaml.safe_load(open(os.path.join(jobs_dir, f"{jid}.yml"), encoding="utf-8"))
    blocs = []
    for m in conf.get("xp-gain-methods") or []:
        xp = m.get("multiplier", m.get("value"))
        filtres = m.get("filters") or {}
        if xp is not None and isinstance(filtres.get("blocks"), list):
            for b in filtres["blocks"]:
                if str(b).lower() not in blocs:
                    blocs.append(str(b).lower())
    if blocs:
        return len(blocs)
    return sum(1 for m in conf.get("xp-gain-methods") or []
               if m.get("trigger") is not None and m.get("multiplier", m.get("value")) is not None)


def bouton_metier(jid, nom, objet, desc, slot, choisi=False):
    """Bouton d'un métier : actif (brille) ou inactif ; le métier affiché brille aussi."""
    clics = {"left": ["[sound] minecraft:item.book.page_turn 1 1"] if choisi else
             [f"[fluxjob] {jid} reset", f"[page-swap] metiers_{jid}_1", "[sound] minecraft:item.book.page_turn 1 1"]}
    action = [] if choisi else [CLIC_G + "Voir le métier"]
    qte = f"%fluxjob_{jid}_levelmod%"
    cle = f"{jid}_choisi" if choisi else jid
    its = [
        item(f"{cle}_actif", objet, nom, slot, niveau(jid) + desc + [VERT + "Métier actif."], action, clics,
             priorite=1, glow=True, quantite=qte, conditions=[f"placeholder: %influbridge_job_active_{jid}% == true"]),
        item(f"{cle}_inactif", objet, nom, slot, niveau(jid) + desc + [ROUGE + "Métier inactif."], action, clics,
             priorite=2, glow=choisi, quantite=qte),
    ]
    return [avec_flags(b, objet) for b in its]


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


def barre_niveaux(jid):
    """7 niveaux autour du niveau du joueur (fenêtre calculée par Flux, %fluxjob_..._lvl_<k>%)."""
    its = []
    for k, slot in enumerate(NIVEAUX, 1):
        lvl = f"%fluxjob_{jid}_lvl_{k}%"
        qte = f"%fluxjob_{jid}_lvlmod_{k}%"
        lignes = [brut(f"%fluxjob_{jid}_level_{k}_line_{n}%") for n in range(1, 7)]
        its.append(item(f"niveau{k}_atteint", "itemsadder:influitems:chest_checkmark", f"Niveau {lvl}", slot,
                        lignes + [VERT + "Atteint."], priorite=1, quantite=qte,
                        conditions=[f"placeholder: {lvl} < %ecojobs_{jid}_level%"]))
        its.append(item(f"niveau{k}_actuel", "itemsadder:influitems:stairs", f"Niveau {lvl}", slot,
                        lignes + [f"XP : &f%ecojobs_{jid}_current_xp%&7/&f%ecojobs_{jid}_required_xp%",
                                  f"Progression : &f%ecojobs_{jid}_percentage_progress%%", VERT + "Ton niveau actuel."],
                        priorite=2, glow=True, quantite=qte, conditions=[f"placeholder: {lvl} == %ecojobs_{jid}_level%"]))
        its.append(item(f"niveau{k}", "itemsadder:influitems:lock", f"Niveau {lvl}", slot,
                        lignes + [ROUGE + "Pas encore atteint."], priorite=3, quantite=qte))
    # Flèches : affichées seulement s'il y a des niveaux avant / après la fenêtre.
    its.append(item("niveaux_prev", "itemsadder:influitems:btn_prev", "Niveaux précédents", FLECHE_PREV, [],
                    clics={"left": [f"[fluxjob] {jid} prev", "[sound] minecraft:item.book.page_turn 1 1"]}, cacher=True,
                    conditions=[f"placeholder: %fluxjob_{jid}_lvl_1% > 1"]))
    its.append(item("niveaux_next", "itemsadder:influitems:btn_next", "Niveaux suivants", FLECHE_NEXT, [],
                    clics={"left": [f"[fluxjob] {jid} next", "[sound] minecraft:item.book.page_turn 1 1"]}, cacher=True,
                    conditions=[f"placeholder: %fluxjob_{jid}_lvl_7% < %fluxjob_{jid}_max%"]))
    chemin = {"slots": "[" + ", ".join(map(str, NIVEAUX)) + "]", "progress": q(f"%fluxjob_{jid}_window%"),
              "color": "vert", "start": "false", "end": "false"}
    return its, {"niveaux": chemin}


BARRE_XP = list(range(81, 90))


def barre_xp(jid):
    """Barre d'XP du métier : un chemin sur toute la barre d'action, allumé jusqu'à la progression
    vers le niveau suivant (%fluxjob_<id>_xpsteps%, 0 à 9). Aucun objet dans la barre d'action :
    le client affiche le nom de l'objet « tenu » au-dessus de la barre, même menu ouvert.
    L'XP détaillée est dans l'infobulle du niveau actuel (barre de niveaux)."""
    chemin = {"slots": "[" + ", ".join(map(str, BARRE_XP)) + "]", "progress": q(f"%fluxjob_{jid}_xpsteps%"),
              "color": "vert", "start": "true", "end": "true"}
    return [], {"xp": chemin}


def adhesion(jid, nom):
    """Case 46 : rejoindre ce métier, ou le quitter (payant : double clic de confirmation)."""
    return [
        # Double clic : le 1er clic affiche le message de confirmation (option « confirm » de Flux).
        item("adhesion_quitter", "itemsadder:influitems:deny", "Quitter le métier", ADHESION,
             ["Ta progression est gardée.", f"Coût : &f%influbridge_job_leave_price_{jid}%"],
             [CLIC_G + "Double clic pour quitter"],
             {"left": [f"[player] metier quitter {jid}", "[refresh]"]},
             priorite=1, conditions=[f"placeholder: %influbridge_job_active_{jid}% == true"]).replace(
            "        priority: 1", '        priority: 1\n        confirm: "&#f0716fClique encore pour quitter le métier."', 1),
        item("adhesion_rejoindre", "itemsadder:influitems:accept", "Rejoindre ce métier", ADHESION,
             ["Commence à gagner des BitBlock", "avec ce métier.", f"Coût : &f%influbridge_job_join_price_{jid}%"],
             [CLIC_G + "Rejoindre"],
             {"left": [f"[player] metier rejoindre {jid}", "[sound] minecraft:entity.player.levelup 1 1.4", "[refresh]"]},
             priorite=2, conditions=[f"placeholder: %influbridge_job_can_join_{jid}% == true"]),
        item("adhesion_complet", "itemsadder:influitems:lock", "Plus de place", ADHESION,
             ["Tu exerces déjà le maximum", "de métiers pour ton rang :", "&f%ecojobs_in_jobs%&7/&f%ecojobs_limit%",
              ROUGE + "Monte en rang pour en exercer plus."], priorite=3),
    ]


def actions_page(jid, n, total, count):
    """Actions rémunérées n° (n-1)*15+1 à n*15 (détail calculé par Flux, %fluxjob_..._action_<i>_...%)."""
    its = []
    for k, slot in enumerate(ACTIONS):
        i = (n - 1) * len(ACTIONS) + k + 1
        if i > count:
            break
        b = f"%fluxjob_{jid}_action_{i}"
        its.append(item(f"action{k + 1}", f"{b}_icon%", f"{b}_name%", slot,
                        [f"XP : {VERT}{b}_xp%", f"{b}_money%"]))
    if n > 1:
        its.append(item("precedent", "itemsadder:influitems:btn_prev", "Page précédente", PREV, [],
                        clics={"left": [f"[page-swap] metiers_{jid}_{n - 1}", "[sound] minecraft:item.book.page_turn 1 1"]},
                        cacher=True))
    if n < total:
        its.append(item("suivant", "itemsadder:influitems:btn_next", "Page suivante", NEXT, [],
                        clics={"left": [f"[page-swap] metiers_{jid}_{n + 1}", "[sound] minecraft:item.book.page_turn 1 1"]},
                        cacher=True))
    return its


def main():
    menus, jobs_dir, cards_yml = sys.argv[1:4]
    toutes = yaml.safe_load(open(cards_yml, encoding="utf-8"))["cards"]
    grille = []
    for (jid, nom, objet, _s, desc), slot in zip(METIERS, METIER_CASES):
        grille += bouton_metier(jid, nom, objet, desc, slot)
    base = [
        item("retour", "itemsadder:influitems:btn_back", "Retour", RETOUR, ["Revenir au menu."],
             [CLIC_G + "Menu principal"], {"left": ["[menu] main"]}),
        avec_flags(item("info", "IRON_PICKAXE", "Carrières", INFO,
                        ["Choisis un métier à droite :", "ses cartes, ses niveaux et ses", "actions rémunérées s'affichent.",
                         "", "Métiers actifs : &f%ecojobs_in_jobs%&7/&f%ecojobs_limit%"]), "IRON_PICKAXE"),
    ] + grille
    corps = "\n\n".join("\n".join(l[4:] for l in b.split("\n")) for b in base)
    with open(os.path.join(menus, "metiers.yml"), "w", encoding="utf-8") as f:
        f.write(f"""# Généré par plugins/fluxplugin/Flux/assets/gen_metiers.py : ne pas modifier à la main.
# Menu Carrières (EcoJobs), remplace /metiers, /carrieres (DeluxeMenus) et /job, /jobs (EcoJobs) sur Labs.

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
  - "[page-swap] metiers_mineur_1"
  - "[sound] minecraft:item.book.page_turn 1 1"

page-files:
  - _pages/metiers.yml

items:

{corps}
""")
    largeurs = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "metiers", "noms.json")))
    pages = []
    for (jid, nom, objet, serie, desc), slot in zip(METIERS, METIER_CASES):
        count = nb_actions(jobs_dir, jid)
        total = max(1, math.ceil(count / len(ACTIONS)))
        niveaux, chemins = barre_niveaux(jid)
        xp, chemin_xp = barre_xp(jid)
        niveaux = niveaux + xp
        chemins = {**chemins, **chemin_xp}
        # Nom du métier centré sur la rangée du bas : x = 88 - largeur / 2 dans la fenêtre.
        decalage_nom = (88 - largeurs[jid] // 2 - 8) - CURSEUR_APRES_PHOTO
        # Niveau écrit au-dessus de la barre d'XP (avance nette nulle : n'influe pas sur la suite).
        niveau_ecrit = f"%fluxjob_{jid}_levelglyphs_169%"
        titre = q(f"{FOND}{niveau_ecrit}:offset_{DECALAGE_PHOTO}::metier_{jid}::offset_{decalage_nom}::nom_{jid}:")
        for n in range(1, total + 1):
            its = bouton_metier(jid, nom, objet, desc, slot, choisi=True)
            its += cartes(serie, toutes) + niveaux + adhesion(jid, nom) + actions_page(jid, n, total, count)
            pages.append(page(f"metiers_{jid}_{n}", its, chemins, titre, animation=False))
        print(f"{jid} : {count} actions, {total} page(s)")
    os.makedirs(os.path.join(menus, "_pages"), exist_ok=True)
    with open(os.path.join(menus, "_pages", "metiers.yml"), "w", encoding="utf-8") as f:
        f.write("# Généré par plugins/fluxplugin/Flux/assets/gen_metiers.py : ne pas modifier à la main.\n"
                "# Pages du menu Carrières : une par métier et par page d'actions rémunérées.\n\npages:\n\n"
                + "\n".join(pages) + "\n")
    print(len(pages), "pages")


if __name__ == "__main__":
    main()
