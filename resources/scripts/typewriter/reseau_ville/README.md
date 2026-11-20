# Réseau de rues de la ville de Resaac

Outils qui ont produit `../pnj_ville_reseau.json` (nœuds et liaisons du réseau de PNJ, lu par `../gen_pnj_ville.py`). À relancer seulement si les rues de la ville changent. Ils écrivent leurs fichiers de travail dans le dossier courant : les lancer depuis un dossier temporaire.

1. `scan.py <rx,rz> ...` : lit les fichiers de région du monde `resaac` de Labs (un fichier `.pkl` par région).
2. `walk.py` : zone accessible à pied depuis le spawn (`reach.pkl`) et carte `map.png`.
3. `streets.py` : cases à ciel ouvert au nord du pont (`ZMAX`), avec leur revêtement (`cells.pkl`).
4. `net.py` : nœuds au centre des rues pavées (un tous les 14 blocs environ), liaisons entre voisins joignables à pied, `net.json` et carte `net.png` à vérifier à l'œil.

Recopier ensuite `net.json` en `../pnj_ville_reseau.json` et relancer `gen_pnj_ville.py`.
