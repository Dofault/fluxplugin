#!/usr/bin/env python3
"""Génère la page Typewriter du lobby RPG (monde flux_rpg, la Place des Dix Destins) pour Labs.

Usage : python3 gen_lobby_rpg.py [dossier plugins du serveur]     (Labs par défaut)

Écrit Typewriter/pages/lobby_rpg.json (et sa copie staging) :
  - les dix portails de métier : une zone (on_player_near_location) qui lance `/donjon jouer <id>` par le joueur, ou
    dit que le donjon arrive bientôt, et leurs particules (particle_audience) ;
  - les dix hologrammes des noms de métier au-dessus des portails (aile est collée au mur, x 22,99, y 71) ;
  - un logo à la place du nom pour certains portails, avec son image au-dessus (Pêcheur : loutre, Bûcheron :
    araignée, Défenseur : Ossacorne ; scripts/hologrammes/gen_donjon_<id>.py, caractères lus dans le cache d'ItemsAdder : /iazip avant ce script) ;
  - le villageois du menu RPG : clic droit = /personnage (Livre du héros : statistiques puis classes) ;
  - la marchande de potions (2026-11-14) : clic droit = /potions (potions du RPG contre des jetons de donjon).
Tout se déplace ensuite dans le panel Typewriter ; relancer le script écrase ces réglages (identifiants stables).
Les positions des portails suivent la map de gen_lobby.py (plugins/fluxplugin/FluxDungeons/assets/maps).
"""
import sys

from twlib import Page

PLUGINS = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins"
page = Page(PLUGINS, "lobby_rpg", "rpg", "flux_rpg")
add, pos = page.add, page.pos

# ------------------------------------------------------------------------------------------- portals
F = 64
GATE_Z = (25, 32, 39, 46, 53)
GATES = [  # (id, nom, couleur, côté, particule du métier) ; côté -1 : aile ouest (ouverture vers +x)
    ("mineur", "Mineur", "#E3826C", -1, "SMALL_FLAME"), ("rochiste", "Rochiste", "#B8B2AA", -1, "WHITE_ASH"),
    ("deblayeur", "Déblayeur", "#A0855B", -1, "MYCELIUM"), ("fondeur", "Fondeur", "#FF7A3D", -1, "FLAME"),
    ("enchanteur", "Enchanteur", "#B376E5", -1, "ENCHANT"),
    ("cultivateur", "Cultivateur", "#F0A800", 1, "HAPPY_VILLAGER"), ("herboriste", "Herboriste", "#83A85A", 1, "SPORE_BLOSSOM_AIR"),
    ("sylviculteur", "Bûcheron", "#8C6A3E", 1, "FALLING_SPORE_BLOSSOM"), ("pecheur", "Pêcheur", "#43B7B0", 1, "BUBBLE_POP"),
    ("defenseur", "Défenseur", "#C8C8C8", 1, "SOUL"),
]
READY = {"mineur", "rochiste", "deblayeur", "fondeur", "cultivateur", "herboriste", "sylviculteur", "pecheur", "defenseur"}

# logo in place of the name, picture over it (scripts/hologrammes/gen_donjon_<id>.py): ids and the logo's displayed
# height in font pixels (its scale_ratio), from which the picture's rise is computed
LOGOS = {
    "pecheur": ("influhologrammes:donjon_pecheur_logo", "influhologrammes:donjon_pecheur_loutre", 48),
    "sylviculteur": ("influhologrammes:donjon_sylviculteur_logo", "influhologrammes:donjon_sylviculteur_araignee", 31),
    "defenseur": ("influhologrammes:donjon_defenseur_logo", "influhologrammes:donjon_defenseur_ossacorne", 37),
}
FONT_PIXEL = 0.025 * 1.5  # one font pixel in blocks at the names' scale


scale = add("scale3d_data", "nom_portail_taille", scale={"x": 1.5, "y": 1.5, "z": 1.5})
for k, (did, name, colour, side, particle) in enumerate(GATES):
    zc = GATE_Z[k % 5]
    face = 22 * side + (1 if side > 0 else 0)
    mid_x = face + 2 * side + 0.5                      # middle of the alcove (3 deep behind the opening)
    if did in READY:
        action = add("player_run_command", f"portail_{did}_lancer", command=f"donjon jouer {did}")
    else:
        action = add("simple_message", f"portail_{did}_bientot",
                     message=f"<#B376E5>Le donjon <{colour}><bold>{name}</bold></{colour}> arrive bientôt : son portail est déjà prêt.")
    add("on_player_near_location", f"portail_{did}", location=pos(mid_x, F + 1, zc + 0.5), range=1.6, triggers=[action])
    add("particle_audience", f"portail_{did}_halo", position=pos(mid_x, F + 2.5, zc + 0.5), radius=24.0, particle="PORTAL",
        count=8, offset={"x": 0.6, "y": 1.2, "z": 1.0}, speed=0.4, delay=250)
    add("particle_audience", f"portail_{did}_metier", position=pos(mid_x, F + 2.0, zc + 0.5), radius=24.0, particle=particle,
        count=2, offset={"x": 0.5, "y": 1.0, "z": 0.8}, speed=0.01, delay=400)
    # name over the arch: east wing flat against the wall on the arch band (owner, 2026-11-14), west wing as before
    # (0.2 more in x than the first version, Fondeur 2 lower, centred on the panel)
    if side > 0:
        x, y = face - 0.01, F + 7
    else:
        x = face - side * 0.45 + 0.5 + 0.2
        y = F + 9.6 - (2 if did == "fondeur" else 0)
    yaw = -90 if side < 0 else 90
    if did in LOGOS:  # pictures: no background nor shadow
        logo, picture, logo_height = LOGOS[did]
        rise = (logo_height - 2) * FONT_PIXEL  # the picture's ground overlaps the top of the letters by 2 pixels
        # the picture a hair in front of the logo: they overlap by 2 pixels, no flicker
        for key, glyph, dx, dy in ((f"nom_{did}", logo, 0, 0), (f"image_{did}", picture, -side * 0.005, rise)):
            add("hologram", key, text=f"<white>{page.glyph(glyph)}</white>", spawnLocation=pos(x + dx, y + dy, zc + 0.5, yaw),
                billboardConstraint="FIXED", shadow=False, seeThrough=False, backgroundColor=0, opacity=-1, lineWidth=200,
                textAlignment="CENTER", data=[scale])
    else:
        add("hologram", f"nom_{did}", text=f"<{colour}><bold>{name}</bold></{colour}>", spawnLocation=pos(x, y, zc + 0.5, yaw),
            billboardConstraint="FIXED", shadow=True, seeThrough=False, backgroundColor=1073741824, opacity=-1, lineWidth=200,
            textAlignment="CENTER", data=[scale])

# ------------------------------------------------------------------------------------------- the rpg menu villager
vdata = add("villager_data", "pnj_rpg_metier", villagerType="TAIGA", profession="WEAPONSMITH", level="MASTER")
vdef = add("villager_definition", "pnj_rpg", displayName="<#FFD94D><bold>Gardien des Destins</bold>", data=[vdata])
look = add("look_close_activity", "pnj_rpg_regarde")
add("villager_instance", "pnj_rpg_instance", definition=vdef, spawnLocation=pos(1.0, 65, 26.5, 180), activity=look)
open_menu = add("player_run_command", "pnj_rpg_menu", command="personnage")
add("entity_interact_event", "pnj_rpg_clic", definition=vdef, shiftType="ANY", interactionType="RIGHT_CLICK", triggers=[open_menu])

# ------------------------------------------------------------------------------------------- the potion merchant
# 4 blocks west of the menu villager (FluxDungeons rpg.Potions: potions bought with dungeon tokens, by rpg level)
pdata = add("villager_data", "pnj_potions_metier", villagerType="SWAMP", profession="CLERIC", level="MASTER")
pdef = add("villager_definition", "pnj_potions", displayName="<#E060A0><bold>Marchande de potions</bold>", data=[pdata])
plook = add("look_close_activity", "pnj_potions_regarde")
add("villager_instance", "pnj_potions_instance", definition=pdef, spawnLocation=pos(-3.0, 65, 26.5, 180), activity=plook)
shop = add("player_run_command", "pnj_potions_boutique", command="potions")
add("entity_interact_event", "pnj_potions_clic", definition=pdef, shiftType="ANY", interactionType="RIGHT_CLICK", triggers=[shop])

page.write()
