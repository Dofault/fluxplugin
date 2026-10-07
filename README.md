# InfluCard (bientôt InfluPet)

Plugin Paper 26.2 du serveur Influencraft : cartes à collectionner, boosters, familiers (adoption par booster,
progression par les cartes, modèles BetterModel qui suivent le joueur, effets propres aux familiers).

Documentation complète : `docs/technique/influcard.md` du dépôt serveur.

## Compiler

- JDK 25 (l'API Paper 26.2 est en classes Java 25), Maven.
- Copier dans `libs/` les jars utilisés à la compilation (non versionnés) : `ItemsAdder_4.0.18.jar`,
  `EcoPets-2026.38.jar`, `eco-2026.38-modrinth.jar`, `bettermodel-3.5.0-paper.jar`,
  `packetevents-spigot-2.13.0.jar` (versions exactes : `pom.xml`).
- `mvn package` : jar dans `target/InfluCard.jar`. Sur la machine du serveur, toujours dans un cgroup à mémoire
  plafonnée (une compilation nue peut faire tuer le serveur par le noyau).

Les familiers et leurs effets sont générés par les scripts `scripts/influcard/` du dépôt serveur
(`effets_familiers.py`, `appliquer_effets.py`, `gen_familiers.py`).
