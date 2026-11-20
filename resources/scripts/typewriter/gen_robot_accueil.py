#!/usr/bin/env python3
"""Génère les pages Typewriter qui décident QUAND le robot d'accueil se montre (Labs). Chaque joueur a le sien :

  - le robot apparaît devant le joueur et se présente **une fois par jour**, à sa première arrivée au vrai spawn
    (connexion au spawn, /spawn, retour dans le monde du spawn), puis se promène dans les rues comme un habitant ;
  - jamais au spawn du tutoriel : un joueur encore dans le groupe de spawn « tutoriel » de Flux ne le voit pas, il le
    rencontre en revenant au vrai spawn après sa première récolte (demande du 2026-11-19) ;
  - le fact « salue_aujourdhui » se remet à zéro chaque nuit à minuit (cron_fact).

Typewriter ne fait que déclencher : le robot lui-même (modèle, animations, voix située, promenade, clic droit pour
réentendre le discours) est le plugin FluxMascot, piloté par `fluxmascot greet <joueur>`.
Cf. docs/technique/robot-accueil.md.

Usage : python3 gen_robot_accueil.py [dossier plugins du serveur]     puis `typewriter reload`
Pages robot_accueil.json (séquence) et robot_accueil_facts.json ; relancer le script écrase les retouches du panel.
"""
import json
import sys
from pathlib import Path

from twlib import Page

PLUGINS = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins"
WORLD = "resaac"

# Facts de la progression (page global_fact de gen_progression.py) : monde (2 = spawn) et spawn du tutoriel.
progression = json.loads((Path(PLUGINS) / "Typewriter" / "pages" / "global_fact.json").read_text())
FACT = {e["name"]: e["id"] for e in progression["entries"]}
MONDE, TUTORIEL = FACT["monde"], FACT["spawn_tutoriel"]


def c(fact, op, value):
    return {"fact": fact, "operator": op, "value": value}


facts = Page(PLUGINS, "robot_accueil_facts", "accueil", WORLD, type_="static")
AUJOURDHUI = facts.add("cron_fact", "robot/salue_aujourdhui", cron="0 0 0 * * *",
                       comment="1 une fois que le robot s'est présenté aujourd'hui ; remis à 0 chaque nuit à minuit.")
facts.write()

page = Page(PLUGINS, "robot_accueil", "accueil", WORLD, type_="sequence")
# Au vrai spawn, hors tutoriel, pas encore salué aujourd'hui.
QUAND = [c(AUJOURDHUI, "==", 0), c(TUTORIEL, "==", 0), c(MONDE, "==", 2)]
greet = page.add("console_run_command", "robot/se_presenter", criteria=QUAND,
                 modifiers=[{"fact": AUJOURDHUI, "operator": "=", "value": 1}], command="fluxmascot greet %player_name%")

# Les délais laissent le joueur arriver (téléportation, chargement du pack) ; les critères sont relus à la fin.
page.add("on_player_join", "robot/connexion",
         triggers=[page.add("delayed_action", "robot/attendre_connexion", duration=6000, triggers=[greet])])
page.add("on_detect_command_ran", "robot/commande_spawn", command="(?i)spawn", cancel=False,
         triggers=[page.add("delayed_action", "robot/attendre_teleportation", duration=1500, triggers=[greet])])
page.add("change_world_event", "robot/arrivee_au_spawn", toWorld={"enabled": True, "value": WORLD}, cancel=False,
         triggers=[page.add("delayed_action", "robot/attendre_arrivee", duration=2500, triggers=[greet])])
page.write()
