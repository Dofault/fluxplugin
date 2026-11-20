#!/usr/bin/env python3
"""Génère le menu Carrières de Flux (Labs) : menus/metiers.yml et menus/_pages/metiers.yml.

    python3 gen_metiers.py <plugins/FluxJobs/menus>

Thème vanilla (2026-10-29) : fond fluxui:bg_metiers (Flux/assets/vanilla/gen_ui.py), le reste en widgets de Flux.
  rangée 0                   : retour (0), nom du métier dans le champ sombre (widget text), rejoindre /
                               quitter (7, quitter = double clic), info (8)
  cases 9 / 18 / 27 / 36 / 45 : les familiers du métier puis son légendaire de boss, liste metiers_familiers :
                               les boutons mêmes du menu des familiers (Companions.button de Flux, 2026-11-20)
  cadre                      : illustration du métier choisi (influmetier:metier_<id>, widget image)
  cases 15-17 ... 42-44      : les 12 métiers (plus de case éclairée depuis le 2026-11-15)
  rangée 5 (46-53)           : barre d'XP vers le niveau suivant, « Niv. N » dessus, XP au survol (widget bar)
  inventaire 54-80           : actions rémunérées (27 par page)
  barre d'action 81 / 89     : pagination des actions (widget pager : rien quand tout tient sur une page)
Une page par métier (metiers_<id>) : widgets du métier. Grille, actions et rejoindre / quitter sont
des listes de Flux construites par FluxJobs (JobMenu). À relancer après un changement de métiers
(liste METIERS), puis /fluxmenu reload.
"""
import os
import sys


# gen_pages (page helpers of the main menu) stays with Flux
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "Flux", "assets"))
from gen_pages import item, page, q, CLIC_G, OR  # noqa: E402

FOND = ":offset_-8::bg_metiers:"
CARTES = [9, 18, 27, 36, 45]
METIER_CASES = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
RETOUR, ADHESION, INFO = 0, 7, 8
ACTIONS = list(range(54, 81))       # les 3 rangées de l'inventaire
PREV, NEXT = 81, 89                 # pagination, sur la barre d'action
# cadre dessiné dans le fond : centré sur la rangée 5 (cases y 108-123, cadre de 12 px), de l'illustration (x 33) au
# bord droit de la grille des métiers (x 170) ; remplissage violet, couleur du thème (2026-11-15)
BARRE = {"x": 33, "y": 110, "width": 138, "survol": "46-53", "couleur": "purple"}
NOM = {"x": 77, "y": 23}            # centre du champ sombre de la rangée 0
PHOTO_X = 33

# (id EcoJobs, nom, objet, série FluxPets, description courte) ; une rangée par famille.
METIERS = [
    ("mineur", "Mineur", "DIAMOND_PICKAXE", "Mineur", ["Mine les minerais,", "du charbon au diamant."]),
    ("rochiste", "Rochiste", "COBBLESTONE", "Rochiste", ["Casse la roche : pierre,", "andésite, basalte..."]),
    ("deblayeur", "Déblayeur", "IRON_SHOVEL", "Déblayeur", ["Dégage terre, sable,", "gravier et neige."]),
    ("cultivateur", "Cultivateur", "WHEAT", "Cultivateur", ["Récolte blé, carottes,", "melons et canne à sucre."]),
    ("herboriste", "Herboriste", "POPPY", "Herboriste", ["Cueille fleurs, bambou,", "champignons et algues."]),
    ("sylviculteur", "Bûcheron", "IRON_AXE", "Bûcheron", ["Abat les arbres,", "toutes essences confondues."]),
    ("fondeur", "Fondeur", "FURNACE", "Fondeur", ["Fond minerais et", "aliments au four."]),
    ("chimiste", "Chimiste", "BREWING_STAND", None, ["Brasse des potions", "à l'alambic."]),
    ("enchanteur", "Enchanteur", "ENCHANTING_TABLE", "Enchanteur", ["Enchante à la table", "ou à l'enclume."]),
    ("pecheur", "Pêcheur", "FISHING_ROD", "Pêcheur", ["Pêche poissons,", "trésors et objets rares."]),
    ("defenseur", "Défenseur", "IRON_SWORD", "Défenseur", ["Élimine les créatures", "hostiles."]),
    ("batisseur", "Bâtisseur", "BRICKS", "Bâtisseur", ["Pose des blocs", "de construction."]),
]


def widgets(jid, nom, case):
    """Widgets de la page d'un métier : nom, illustration, barre d'XP (8 espaces : dans une page). Plus de case
    éclairée sur le métier affiché (2026-11-15, demande du propriétaire)."""
    b = BARRE
    return "\n".join([
        "    widgets:",
        "      nom:",
        f"        type: text", f"        x: {NOM['x']}", f"        y: {NOM['y']}", "        align: center",
        f"        text: {q(nom)}",
        "      illustration:",
        "        type: image", f"        glyph: {q('metier_' + jid)}", f"        x: {PHOTO_X}", "        width: 64",
        "      niveau:",
        "        type: bar", f"        x: {b['x']}", f"        y: {b['y']}", f"        width: {b['width']}",
        f"        progress: {q(f'%ecojobs_{jid}_percentage_progress%')}", "        max: 100", f"        colour: {b['couleur']}",
        f"        text: {q(f'Niv. %ecojobs_{jid}_level%')}",
        "        tooltip:",
        f"          slots: {q(b['survol'])}",
        f"          name: {q(f'{OR}Niveau %ecojobs_{jid}_level%')}",
        "          lore:",
        f"            - {q(f'@kv Expérience | %ecojobs_{jid}_current_xp%&7/&f%ecojobs_{jid}_required_xp%')}",
        f"            - {q(f'@kv Progression | %ecojobs_{jid}_percentage_progress% %')}",
    ])


def liste(lid, slots, actions=None, extra=()):
    """Liste de Flux remplie par FluxJobs (JobMenu) : objets construits en Java, posés sur ces cases."""
    y = [f"  {lid}:", f"    slots: {', '.join(map(str, slots))}", "    item:", "      material: entry"]
    y += [f"      {l}" for l in extra]
    if actions:
        y.append("      actions:")
        y.append(f"        left: [{', '.join(q(a) for a in actions)}]")
    return "\n".join(y)


def main():
    menus = sys.argv[1]
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
        # les familiers du métier : le bouton même du menu des familiers (fiche, effets, clics), donné par FluxPets
        # à travers Flux (Companions.button) ; quel familier va à quel métier : job-series de FluxPets/config.yml
        liste("metiers_familiers", CARTES) + """
      actions:
        left: ["%entry_left%", "%entry_left2%", "[sound] @click"]
        right: ["%entry_right%", "%entry_right2%"]""",
    ]
    corps = "\n\n".join("\n".join(l[4:] for l in b.split("\n")) for b in base)
    with open(os.path.join(menus, "metiers.yml"), "w", encoding="utf-8") as f:
        f.write(f"""# Généré par plugins/fluxplugin/FluxJobs/assets/gen_metiers.py : ne pas modifier à la main.
# Menu Carrières (EcoJobs), remplace /metiers, /carrieres (DeluxeMenus) et /job, /jobs (EcoJobs) sur Labs.
# Les métiers, niveaux, actions et le bouton rejoindre / quitter sont construits par FluxJobs (JobMenu, listes
# metiers_*) ; ordre, icônes et descriptions des métiers : jobs.menu de plugins/FluxJobs/config.yml.

title: "{FOND}"
rows: 6
commands: [metiers, carrieres, job, jobs]
override-commands: true
player-inventory: hidden
vanilla-background: false
update-interval: 20

# À l'ouverture : le métier demandé (/metier progression <métier>, familiers du tutoriel), sinon le premier (Mineur).
open-actions:
  - "[fluxjob] opening reset"
  - "[page-swap] metiers_%fluxjob_selected%"
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
        bloc = page(f"metiers_{jid}", [], {}, q(FOND), animation=False)
        pages.append(bloc.rstrip("\n") + "\n" + widgets(jid, nom, METIER_CASES[k]) + "\n")
    os.makedirs(os.path.join(menus, "_pages"), exist_ok=True)
    with open(os.path.join(menus, "_pages", "metiers.yml"), "w", encoding="utf-8") as f:
        f.write("# Généré par plugins/fluxplugin/FluxJobs/assets/gen_metiers.py : ne pas modifier à la main.\n"
                "# Pages du menu Carrières, une par métier : familiers, nom, illustration, case éclairée, barre d'XP.\n\npages:\n\n"
                + "\n".join(pages) + "\n")
    print(len(pages), "pages")


if __name__ == "__main__":
    main()
