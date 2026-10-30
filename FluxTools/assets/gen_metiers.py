#!/usr/bin/env python3
"""Génère le menu Carrières de Flux (Labs) : menus/metiers.yml et menus/_pages/metiers.yml.

    python3 gen_metiers.py <plugins/FluxTools/menus> <FluxPets/content/cards.yml>

Thème vanilla (2026-10-29) : fond fluxui:bg_metiers (Flux/assets/vanilla/gen_ui.py), le reste en widgets de Flux.
  rangée 0                   : retour (0), nom du métier dans le champ sombre (widget text), rejoindre /
                               quitter (7, quitter = double clic), info (8)
  cases 9 / 18 / 27 / 36     : les 4 familiers FluxPets du métier, en portrait
  cadre                      : illustration du métier choisi (influmetier:metier_<id>, widget image)
  cases 15-17 ... 42-44      : les 12 métiers (case du métier affiché éclairée, widget lit)
  rangée 5                   : barre d'XP vers le niveau suivant, « Niv. N » dessus, XP au survol (widget bar)
  inventaire 54-80           : actions rémunérées (27 par page)
  barre d'action 81 / 89     : pagination des actions (widget pager : rien quand tout tient sur une page)
Une page par métier (metiers_<id>) : familiers et widgets du métier. Grille, actions et rejoindre / quitter sont
des listes de Flux construites par FluxTools (JobMenu). À relancer après un changement de cartes ou de métiers
(liste METIERS), puis /fluxmenu reload.
"""
import os
import sys

import yaml

# gen_pages (page helpers of the main menu) stays with Flux
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "Flux", "assets"))
from gen_pages import item, page, q, CLIC_G, OR, VIOLET, ROUGE  # noqa: E402

FOND = ":offset_-8::bg_metiers:"
VERT = "&#2ECF7B"
CARTES = [9, 18, 27, 36]
METIER_CASES = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
RETOUR, ADHESION, INFO = 0, 7, 8
ACTIONS = list(range(54, 81))       # les 3 rangées de l'inventaire
PREV, NEXT = 81, 89                 # pagination, sur la barre d'action
BARRE = {"x": 6, "y": 109, "width": 164, "survol": "45-53"}     # cadre dessiné dans le fond
NOM = {"x": 77, "y": 23}            # centre du champ sombre de la rangée 0
PHOTO_X = 34
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


def widgets(jid, nom, case):
    """Widgets de la page d'un métier : nom, illustration, case éclairée, barre d'XP (8 espaces : dans une page)."""
    b = BARRE
    return "\n".join([
        "    widgets:",
        "      nom:",
        f"        type: text", f"        x: {NOM['x']}", f"        y: {NOM['y']}", "        align: center",
        f"        text: {q(nom)}",
        "      illustration:",
        "        type: image", f"        glyph: {q('metier_' + jid)}", f"        x: {PHOTO_X}", "        width: 64",
        "      selection:",
        "        type: lit", f"        slot: {case}",
        "      niveau:",
        "        type: bar", f"        x: {b['x']}", f"        y: {b['y']}", f"        width: {b['width']}",
        f"        progress: {q(f'%ecojobs_{jid}_percentage_progress%')}", "        max: 100", "        colour: green",
        f"        text: {q(f'Niv. %ecojobs_{jid}_level%')}",
        "        tooltip:",
        f"          slots: {q(b['survol'])}",
        f"          name: {q(f'{OR}Niveau %ecojobs_{jid}_level%')}",
        "          lore:",
        f"            - {q(f'@kv Expérience | %ecojobs_{jid}_current_xp%&7/&f%ecojobs_{jid}_required_xp%')}",
        f"            - {q(f'@kv Progression | %ecojobs_{jid}_percentage_progress% %')}",
    ])


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
        item("retour", "itemsadder:fluxui:btn_back_orange", "Retour", RETOUR, ["Revenir au menu."],
             [CLIC_G + "Menu principal"], {"left": ["[menu] main"]}),
        item("info", "itemsadder:fluxui:btn_info_blue", "Carrières", INFO,
             ["Choisis un métier à droite. Ses familiers, son niveau et ses actions rémunérées s'affichent.",
              "", "Métiers actifs : &f%ecojobs_in_jobs%&7/&f%ecojobs_limit%"]),
    ]
    listes = [
        liste("metiers_grille", METIER_CASES,
              ["[fluxjob] %entry_id% reset", "[page-swap] metiers_%entry_id%", "[sound] @page"]),
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

# Pagination des actions : boutons et compteur seulement quand elles ne tiennent pas sur une page.
widgets:
  pages:
    type: pager
    list: metiers_actions
    prev: {PREV}
    next: {NEXT}

items:

{corps}
""")
    pages = []
    for k, (jid, nom, objet, serie, desc) in enumerate(METIERS):
        bloc = page(f"metiers_{jid}", cartes(serie, toutes), {}, q(FOND), animation=False)
        pages.append(bloc.rstrip("\n") + "\n" + widgets(jid, nom, METIER_CASES[k]) + "\n")
    os.makedirs(os.path.join(menus, "_pages"), exist_ok=True)
    with open(os.path.join(menus, "_pages", "metiers.yml"), "w", encoding="utf-8") as f:
        f.write("# Généré par plugins/fluxplugin/FluxTools/assets/gen_metiers.py : ne pas modifier à la main.\n"
                "# Pages du menu Carrières, une par métier : familiers, nom, illustration, case éclairée, barre d'XP.\n\npages:\n\n"
                + "\n".join(pages) + "\n")
    print(len(pages), "pages")


if __name__ == "__main__":
    main()
