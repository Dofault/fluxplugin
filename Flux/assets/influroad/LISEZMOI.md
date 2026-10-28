# Kit chemin de progression (générique)

Pièces de chemin à poser entre les slots de n'importe quel menu (grille Minecraft de 18 px).
Chaque pièce existe en 7 états : `eteint`, `vert`, `or`, `bleu`, `rose`, `rouge`, `orange`. Convention des chemins à états (2026-10-28) : `vert` récupéré / atteint, `orange` en attente, `rouge` manqué, `eteint` pas encore.

| Fichier | Taille | Rôle |
| --- | --- | --- |
| `chemin_h_<etat>.png` | 19×3 | relie deux slots voisins sur la même ligne |
| `chemin_v_<etat>.png` | 3×19 | relie deux slots voisins sur la même colonne |
| `chemin_h_demi_<etat>.png` | 10×3 | départ / fin horizontal (du centre d'un slot jusqu'à son bord) |
| `chemin_v_demi_<etat>.png` | 3×10 | départ / fin vertical |
| `chemin_noeud_<etat>.png` | 5×5 | losange décoratif (embranchement, départ, arrivée) |

## Où poser chaque pièce

Pour un slot en colonne `c` (0 à 8) dont le haut est à `y` (coordonnées GUI) :
`slot_x = 8 + 18 × c`, centre = `slot_x + 8`, `y + 8`.

- `h` entre le slot `c` et `c+1` : position `(slot_x + 8, y + 7)`
- `v` entre une ligne et la suivante : position `(slot_x + 7, y + 8)`
- `h_demi` départ à gauche : `(slot_x − 1, y + 7)` · fin à droite : `(slot_x + 8, y + 7)`
- `v_demi` départ en haut : `(slot_x + 7, y − 1)` · fin en bas : `(slot_x + 7, y + 8)`
- `noeud` sur un slot : `(slot_x + 6, y + 6)`

Ordre de dessin : `v` puis `h` puis `noeud`, puis les items par-dessus.

## Règle d'allumage

Une liaison est allumée (couleur) si les deux slots qu'elle relie sont débloqués/récupérés,
sinon `eteint`. Le départ (`demi`) s'allume dès que le premier slot est récupéré.
Une branche vers un palier s'allume quand le slot dont elle part est récupéré.
