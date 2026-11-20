#!/usr/bin/env python3
"""Génère les habitants qui se promènent dans la ville de Resaac (Labs) : un réseau de rues Typewriter et 30 PNJ qui
y marchent au hasard. Le réseau couvre les rues pavées de la ville et s'arrête à l'entrée du pont (sud).

Usage : python3 gen_pnj_ville.py [dossier plugins du serveur]     puis `typewriter reload`

Écrit :
  - Typewriter/assets/artifacts/<id>.json : le réseau (nœuds, liaisons), depuis pnj_ville_reseau.json (produit par
    les outils de reseau_ville/, cf. son README) ;
  - FluxMascot/network.json : le même réseau pour le robot d'accueil (`fluxmascot reload` pour le relire) ;
  - Typewriter/pages/pnj_ville.json (et staging) : le réseau, l'activité de promenade, 30 définitions et instances.
Relancer le script écrase les retouches du panel sur cette page ET sur le réseau (identifiants stables).
"""
import hashlib
import json
import math
import random
import sys
import uuid
from pathlib import Path

from twlib import Page

PLUGINS = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins"
HERE = Path(__file__).parent
WORLD = "resaac"
# UUID du monde resaac de Labs (world/dimensions/minecraft/resaac/data/paper/metadata.dat) : les artefacts de réseau
# désignent le monde par son UUID, pas par son nom. À relire sur le serveur visé s'il change (prod).
WORLD_UUID = sys.argv[2] if len(sys.argv) > 2 else "313cd091-5ca9-4304-ad31-62e0a81705dd"
COUNT = 30
RADIUS = 70.0            # un PNJ choisit sa prochaine destination parmi les nœuds à moins de 70 blocs

page = Page(PLUGINS, "pnj_ville", "resaac", WORLD)
net = json.loads((HERE / "pnj_ville_reseau.json").read_text())


def node_id(i):
    return int(hashlib.sha256(f"pnj-ville-noeud-{net['nodes'][i]}".encode()).hexdigest()[:7], 16)


def loc(i):
    x, y, z = net["nodes"][i]
    return {"world": WORLD_UUID, "x": x + 0.5, "y": y + 0.5, "z": z + 0.5, "yaw": 0.0, "pitch": 0.0}


nodes = [{"id": node_id(i), "location": loc(i), "radius": 1.5} for i in range(len(net["nodes"]))]
edges = []
for a, b in net["edges"]:
    d = round(math.dist(net["nodes"][a], net["nodes"][b]), 1)
    edges += [{"start": node_id(a), "end": node_id(b), "weight": d, "length": d},
              {"start": node_id(b), "end": node_id(a), "weight": d, "length": d}]
artifact_id = str(uuid.uuid5(uuid.UUID("5b2d9e00-0000-4000-8000-00000000c1e5"), "pnj_ville/reseau"))
folder = Path(PLUGINS) / "Typewriter" / "assets" / "artifacts"
folder.mkdir(parents=True, exist_ok=True)
(folder / f"{artifact_id}.json").write_text(json.dumps(
    {"nodes": nodes, "edges": edges, "modifications": [], "negativeNodes": []}))

# Le robot d'accueil (FluxMascot) se promène sur les mêmes rues : il lit sa propre copie du réseau.
mascot = Path(PLUGINS) / "FluxMascot"
mascot.mkdir(exist_ok=True)
(mascot / "network.json").write_text(json.dumps({"world": WORLD, "nodes": net["nodes"], "edges": net["edges"]}))

road = page.add("base_road_network", "ville/reseau", artifactId=artifact_id)
stroll = page.add("random_patrol_activity", "ville/promenade", roadNetwork=road, radius=RADIUS)

# Skins : les trente habitants dessinés par scripts/skins/pnj_ville/gen_skins.py, signés par envoyer_skins.py
# (skins.json). Aucun nom affiché au-dessus des habitants (demande du 2026-11-14) : displayName vide.
SKINS = json.loads((HERE.parent / "skins" / "pnj_ville" / "skins.json").read_text())
skins = [{"texture": v["texture"], "signature": v["signature"]} for k, v in sorted(SKINS.items())]
if len(skins) < COUNT:
    raise SystemExit(f"{len(skins)} skins signés pour {COUNT} habitants : lancer envoyer_skins.py")
rnd = random.Random(2026)
spots = rnd.sample(range(len(nodes)), min(COUNT, len(nodes)))
for n in range(COUNT):
    definition = page.add("npc_definition", f"ville/habitant_{n + 1:02d}", displayName="",
                          skin=skins[n % len(skins)], data=[])
    x, y, z = net["nodes"][spots[n % len(spots)]]
    page.add("npc_instance", f"ville/habitant_{n + 1:02d}_instance", definition=definition,
             spawnLocation=page.pos(x + 0.5, y, z + 0.5, rnd.uniform(-180, 180)), data=[], activity=stroll)
page.write()
print(f"réseau {artifact_id} : {len(nodes)} nœuds, {len(edges) // 2} liaisons ; {COUNT} habitants")
