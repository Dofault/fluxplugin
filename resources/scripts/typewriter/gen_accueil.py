#!/usr/bin/env python3
"""Génère la page Typewriter de l'accueil du spawn (Labs) : l'hologramme qui présente Influencraft, en 0, 2, 28 du monde
resaac (là où l'on arrive, ex-SpringRoyal_1.21), tourné vers chaque joueur (billboard CENTER).

Usage : python3 gen_accueil.py [dossier plugins du serveur]     puis `typewriter reload`
Page accueil.json ; le texte et la position se règlent ensuite dans le panel (relancer le script les écrase).
"""
import sys

from twlib import Page

PLUGINS = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins"
page = Page(PLUGINS, "accueil", "accueil", "resaac")

TEXT = "\n".join([
    "<#FFD94D><bold>✦ Bienvenue sur Influencraft ✦</bold></#FFD94D>",
    "<#B376E5>Serveur Semi-RP francophone, en bêta</#B376E5>",
    "",
    "<white>Exerce tes métiers, monte en rang,</white>",
    "<white>fonde ta ville et forge ton destin.</white>",
    "",
    "<#B376E5>Aide :</#B376E5> <white>/aide</white>   <#B376E5>Discord :</#B376E5> <white>/discord</white>",
    "<#B376E5>Site :</#B376E5> <#2BC4E8><u>influencraft.fr</u></#2BC4E8>",
])
scale = page.add("scale3d_data", "accueil_taille", scale={"x": 1.2, "y": 1.2, "z": 1.2})
page.add("hologram", "accueil_hologramme", text=TEXT, spawnLocation=page.pos(0, 2, 28), billboardConstraint="CENTER",
         shadow=True, seeThrough=False, backgroundColor=1342177280, opacity=-1, lineWidth=320, textAlignment="CENTER",
         data=[scale])
page.write()
