# Armes de classe RPG : fiches de fabrication

> Généré par `gen_armes.py` : ne pas modifier à la main, relancer le script.

Repère commun : unités de modèle Minecraft (16 = un bloc), **Y vers le haut**, axe de l'arme selon Y en x = 8, z = 8, plat de l'arme dans le plan XY vu de face (+Z, face `south`), épaisseur selon Z. « de » et « à » sont les coins `from` / `to` du cube **avant** rotation ; une rotation tourne autour de son origine, sur un seul axe, par pas de 22,5° (positif = sens direct vu depuis le bout positif de l'axe : autour de Z, le haut penche vers -X). Suffixes `_g` / `_d` : gauche / droite vus de face.

## Brise-Aube (Guerrier) : `fluxdungeons:brise_aube`

Épée à deux mains massive : lame large d'argent à arête renforcée, deux tranchants clairs en biseau, noyau d'énergie bleue sur toute la longueur, pointe en losange, épaulements en crocs, garde d'acier et d'or à quillons relevés, longue poignée de cuir à trois bagues (la bague d'or sépare les deux mains), pommeau d'argent serti.

| Dimension | Valeur |
|---|---|
| Longueur (Y) | 44 (de -16 à 28) |
| Largeur max (X) | 18.28 (de -1.14 à 17.14) |
| Épaisseur max (Z) | 4.101 |
| Prise (point tenu par le poing) | (8, -7, 8) |
| Centre visuel (milieu de l'encombrement) | (8, 6, 8) |
| Longueur en main, 3e personne | 30.8 px de joueur (joueur = 32) |
| Nombre de cubes | **36** |

Palette :

| Matériau (texture) | Couleur | Lumineux |
|---|---|---|
| `argent` | Argent métallique `#C9D4DF` |  |
| `argent_clair` | Argent clair `#EEF4FA` |  |
| `acier` | Acier sombre `#596879` |  |
| `or` | Or ancien `#D6A63C` |  |
| `noyau` | Bleu électrique `#39BFFF` | oui |
| `noyau_profond` | Bleu profond `#1767A5` |  |
| `cuir` | Cuir sombre (ajout : poignée) `#2B303B` |  |

Hiérarchie (groupes, dans l'ordre de construction) : `pommeau` (5), `poignee` (4), `garde` (11), `talon` (3), `lame` (10), `pointe` (3)

| # | Groupe | Cube | De (x, y, z) | À (x, y, z) | Taille | Rotation | Matériau | Note |
|---|---|---|---|---|---|---|---|---|
| 1 | pommeau | `pommeau_pointe` | 7 -16 7 | 9 -15 9 | 2 × 1 × 2 |  | `acier` |  |
| 2 | pommeau | `pommeau_corps` | 6 -15 6.5 | 10 -12.5 9.5 | 4 × 2.5 × 3 |  | `argent` |  |
| 3 | pommeau | `pommeau_chanfrein` | 6.7 -15.05 6.4 | 9.3 -12.45 9.6 | 2.6 × 2.6 × 3.2 | Y 45° autour de (8, -13.75, 8) | `argent_clair` | dépasse en pointe sur les quatre arêtes |
| 4 | pommeau | `pommeau_gemme` | 7.3 -14.45 6.4 | 8.7 -13.05 9.6 | 1.4 × 1.4 × 3.2 |  | `noyau` | superposé : dépasse de 0,1 devant et derrière |
| 5 | pommeau | `pommeau_bague` | 6.5 -12.5 6.75 | 9.5 -12 9.25 | 3 × 0.5 × 2.5 |  | `or` |  |
| 6 | poignee | `poignee` | 7 -12 7 | 9 -2 9 | 2 × 10 × 2 |  | `cuir` |  |
| 7 | poignee | `bague_basse` | 6.8 -10.2 6.8 | 9.2 -9.7 9.2 | 2.4 × 0.5 × 2.4 |  | `acier` |  |
| 8 | poignee | `bague_mains` | 6.7 -7.3 6.7 | 9.3 -6.7 9.3 | 2.6 × 0.6 × 2.6 |  | `or` | sépare les deux mains |
| 9 | poignee | `bague_haute` | 6.8 -4.3 6.8 | 9.2 -3.8 9.2 | 2.4 × 0.5 × 2.4 |  | `acier` |  |
| 10 | garde | `garde_coeur` | 5.5 -2 6.6 | 10.5 1 9.4 | 5 × 3 × 2.8 |  | `acier` |  |
| 11 | garde | `garde_plaque` | 6.3 -1.6 6.5 | 9.7 0.6 9.5 | 3.4 × 2.2 × 3 |  | `or` | superposé : 0,1 devant et derrière |
| 12 | garde | `garde_gemme` | 7.3 -1.2 6.4 | 8.7 0.2 9.6 | 1.4 × 1.4 × 3.2 |  | `noyau` | superposé sur la plaque |
| 13 | garde | `quillon_g` | 0.5 -1.3 7 | 5.6 0.3 9 | 5.1 × 1.6 × 2 |  | `argent` |  |
| 14 | garde | `quillon_d` | 10.4 -1.3 7 | 15.5 0.3 9 | 5.1 × 1.6 × 2 |  | `argent` |  |
| 15 | garde | `quillon_filet_g` | 0.5 0.3 7.2 | 5.6 0.7 8.8 | 5.1 × 0.4 × 1.6 |  | `or` | liseré posé sur le quillon |
| 16 | garde | `quillon_filet_d` | 10.4 0.3 7.2 | 15.5 0.7 8.8 | 5.1 × 0.4 × 1.6 |  | `or` | liseré posé sur le quillon |
| 17 | garde | `quillon_bout_g` | -0.3 -1.4 6.9 | 1.5 1.8 9.1 | 1.8 × 3.2 × 2.2 | Z 22.5° autour de (0.6, 0.2, 8) | `or` | relevé vers l'extérieur |
| 18 | garde | `quillon_bout_d` | 14.5 -1.4 6.9 | 16.3 1.8 9.1 | 1.8 × 3.2 × 2.2 | Z -22.5° autour de (15.4, 0.2, 8) | `or` | relevé vers l'extérieur |
| 19 | garde | `quillon_pointe_g` | -0.85 1.75 7.2 | 0.55 3.15 8.8 | 1.4 × 1.4 × 1.6 | Z 45° autour de (-0.15, 2.45, 8) | `argent_clair` | losange au bout du quillon |
| 20 | garde | `quillon_pointe_d` | 15.45 1.75 7.2 | 16.85 3.15 8.8 | 1.4 × 1.4 × 1.6 | Z -45° autour de (16.15, 2.45, 8) | `argent_clair` | losange au bout du quillon |
| 21 | talon | `talon` | 5.5 1 7.05 | 10.5 4 8.95 | 5 × 3 × 1.9 |  | `acier` |  |
| 22 | talon | `talon_plaque` | 6.2 1.3 6.85 | 9.8 3.7 9.15 | 3.6 × 2.4 × 2.3 |  | `or` | superposé : 0,2 devant et derrière |
| 23 | talon | `talon_gemme` | 7.3 1.8 6.75 | 8.7 3.2 9.25 | 1.4 × 1.4 × 2.5 | Z 45° autour de (8, 2.5, 8) | `noyau` | losange d'énergie sur la plaque |
| 24 | lame | `arete` | 6.5 4 7 | 9.5 24 9 | 3 × 20 × 2 |  | `argent` | structure renforcée, épaisseur 2 |
| 25 | lame | `tranchant_g` | 4.5 4 7.35 | 6.5 24.5 8.65 | 2 × 20.5 × 1.3 |  | `argent_clair` | plus fin que l'arête : marche de 0,35 |
| 26 | lame | `tranchant_d` | 9.5 4 7.35 | 11.5 24.5 8.65 | 2 × 20.5 × 1.3 |  | `argent_clair` | plus fin que l'arête : marche de 0,35 |
| 27 | lame | `renfort` | 6.3 4 6.85 | 9.7 6.5 9.15 | 3.4 × 2.5 × 2.3 |  | `argent_clair` | superposé sur l'arête |
| 28 | lame | `rainure` | 7.1 5 6.9 | 8.9 22 9.1 | 1.8 × 17 × 2.2 |  | `noyau_profond` | superposé : 0,1 devant et derrière |
| 29 | lame | `noyau` | 7.5 5.5 6.8 | 8.5 21.5 9.2 | 1 × 16 × 2.4 |  | `noyau` | superposé sur la rainure |
| 30 | lame | `croc_g` | 3.4 4.2 7.3 | 5 5.8 8.7 | 1.6 × 1.6 × 1.4 | Z 45° autour de (4.2, 5, 8) | `argent_clair` | épaulement en crocs à la base de la lame |
| 31 | lame | `croc_d` | 11 4.2 7.3 | 12.6 5.8 8.7 | 1.6 × 1.6 × 1.4 | Z -45° autour de (11.8, 5, 8) | `argent_clair` | épaulement en crocs à la base de la lame |
| 32 | lame | `entaille_g` | 4.1 13.4 7.25 | 5.3 14.6 8.75 | 1.2 × 1.2 × 1.5 | Z 45° autour de (4.7, 14, 8) | `acier` | brisure du tranchant, à moitié enfoncée |
| 33 | lame | `entaille_d` | 10.7 13.4 7.25 | 11.9 14.6 8.75 | 1.2 × 1.2 × 1.5 | Z -45° autour de (11.3, 14, 8) | `acier` | brisure du tranchant, à moitié enfoncée |
| 34 | pointe | `pointe` | 5.525 22.025 7.35 | 10.475 26.975 8.65 | 4.95 × 4.95 × 1.3 | Z 45° autour de (8, 24.5, 8) | `argent_clair` | losange : largeur 7 = la lame |
| 35 | pointe | `pointe_arete` | 6.23 22.73 7 | 9.77 26.27 9 | 3.54 × 3.54 × 2 | Z 45° autour de (8, 24.5, 8) | `argent` | prolonge l'arête jusqu'à y 27 |
| 36 | pointe | `pointe_noyau` | 7.3 22.5 6.8 | 8.7 23.9 9.2 | 1.4 × 1.4 × 2.4 | Z 45° autour de (8, 23.2, 8) | `noyau` | fin du noyau, dépasse de 0,2 |

Affichages (`display` du modèle) :

| Contexte | Rotation | Translation | Échelle |
|---|---|---|---|
| `thirdperson_righthand` | 0 -90 -22 | 0 8.41 5.37 | 0.7 |
| `thirdperson_lefthand` | 0 90 22 | 0 8.41 5.37 | 0.7 |
| `firstperson_righthand` | 0 -90 -20 | 1.13 5.05 1.8 | 0.42 |
| `firstperson_lefthand` | 0 90 20 | 1.13 5.05 1.8 | 0.42 |
| `gui` | 0 0 -45 | 0.9 0.9 0 | 0.502 |
| `fixed` | 0 180 45 | 0.85 0.85 0 | 0.47 |
| `on_shelf` | 0 180 45 | 0.85 0.85 0 | 0.47 |
| `ground` | 0 0 0 | 0 0.5 0 | 0.25 |
| `head` | 0 180 0 | 0 13 7 | 0.6 |

## Sylve-Céleste (Archer) : `fluxdungeons:sylve_celeste`

Arc long elfique : deux branches de bois en courbe progressive (0°, 22,5°, 45°) qui finissent en crochets dorés relevés, dos de bois clair, nervures d'émeraude lumineuses sur les branches, poignée de bois sombre bandée de vert, cristal émeraude en losange serti d'or au repose-flèche, corde tendue entre les deux encoches.

| Dimension | Valeur |
|---|---|
| Longueur (Y) | 47.048 (de -15.524 à 31.524) |
| Largeur max (X) | 10.173 (de 1.416 à 11.588) |
| Épaisseur max (Z) | 2.8 |
| Prise (point tenu par le poing) | (8.1, 8, 8) |
| Centre visuel (milieu de l'encombrement) | (6.502, 8, 8) |
| Longueur en main, 3e personne | 25.876 px de joueur (joueur = 32) |
| Nombre de cubes | **35** |

Palette :

| Matériau (texture) | Couleur | Lumineux |
|---|---|---|
| `bois_sombre` | Bois sombre `#493526` |  |
| `bois_moyen` | Bois moyen `#765037` |  |
| `bois_clair` | Bois clair `#B78B58` |  |
| `emeraude` | Vert émeraude `#16B879` | oui |
| `vert_profond` | Vert profond `#08754F` |  |
| `or` | Or végétal `#C5A65A` |  |
| `corde` | Corde `#D8D2BC` |  |

Hiérarchie (groupes, dans l'ordre de construction) : `poignee` (8), `branche_haute` (13), `branche_basse` (13), `corde` (1)

| # | Groupe | Cube | De (x, y, z) | À (x, y, z) | Taille | Rotation | Matériau | Note |
|---|---|---|---|---|---|---|---|---|
| 1 | poignee | `poignee` | 6.9 5 6.9 | 9.3 11 9.1 | 2.4 × 6 × 2.2 |  | `bois_sombre` |  |
| 2 | poignee | `bande_1` | 6.8 5.6 6.8 | 9.4 6.6 9.2 | 2.6 × 1 × 2.4 |  | `vert_profond` | superposé : 0,1 autour |
| 3 | poignee | `bande_2` | 6.8 7.5 6.8 | 9.4 8.5 9.2 | 2.6 × 1 × 2.4 |  | `vert_profond` | superposé : 0,1 autour |
| 4 | poignee | `bande_3` | 6.8 9.4 6.8 | 9.4 10.4 9.2 | 2.6 × 1 × 2.4 |  | `vert_profond` | superposé : 0,1 autour |
| 5 | poignee | `poignee_bague_haute` | 6.6 10.8 6.6 | 9.6 11.6 9.4 | 3 × 0.8 × 2.8 |  | `or` |  |
| 6 | poignee | `poignee_bague_basse` | 6.6 4.4 6.6 | 9.6 5.2 9.4 | 3 × 0.8 × 2.8 |  | `or` |  |
| 7 | poignee | `sertissure` | 8.45 6.7 7.5 | 11.05 9.3 8.5 | 2.6 × 2.6 × 1 | Z 45° autour de (9.75, 8, 8) | `or` | derrière le cristal |
| 8 | poignee | `cristal` | 8.95 7 7.2 | 10.95 9 8.8 | 2 × 2 × 1.6 | Z 45° autour de (9.95, 8, 8) | `emeraude` | dépasse de la sertissure devant et derrière |
| 9 | branche_haute | `branche_haute_1` | 7.15 10.65 7.1 | 9.05 17.35 8.9 | 1.9 × 6.7 × 1.8 |  | `bois_moyen` |  |
| 10 | branche_haute | `dos_haute_1` | 8.6 11 7.3 | 9.15 17 8.7 | 0.55 × 6 × 1.4 |  | `bois_clair` | superposé sur le dos de la branche |
| 11 | branche_haute | `branche_haute_2` | 6.102 16.422 7.2 | 7.802 23.122 8.8 | 1.7 × 6.7 × 1.6 | Z 22.5° autour de (6.952, 19.772, 8) | `bois_moyen` |  |
| 12 | branche_haute | `dos_haute_2` | 7.352 16.772 7.4 | 7.902 22.772 8.6 | 0.55 × 6 × 1.2 | Z 22.5° autour de (6.952, 19.772, 8) | `bois_clair` | superposé sur le dos de la branche |
| 13 | branche_haute | `nervure_haute_2` | 6.652 17.372 7.1 | 7.252 22.172 8.9 | 0.6 × 4.8 × 1.8 | Z 22.5° autour de (6.952, 19.772, 8) | `emeraude` | dépasse de 0,1 devant et derrière |
| 14 | branche_haute | `branche_haute_3` | 4.097 22.003 7.3 | 5.597 27.703 8.7 | 1.5 × 5.7 × 1.4 | Z 22.5° autour de (4.847, 24.853, 8) | `bois_moyen` |  |
| 15 | branche_haute | `dos_haute_3` | 5.147 22.353 7.5 | 5.697 27.353 8.5 | 0.55 × 5 × 1 | Z 22.5° autour de (4.847, 24.853, 8) | `bois_clair` | superposé sur le dos de la branche |
| 16 | branche_haute | `nervure_haute_3` | 4.547 22.953 7.2 | 5.147 26.753 8.8 | 0.6 × 3.8 × 1.6 | Z 22.5° autour de (4.847, 24.853, 8) | `emeraude` | dépasse de 0,1 devant et derrière |
| 17 | branche_haute | `branche_haute_4` | 2.357 26.447 7.4 | 3.657 29.647 8.6 | 1.3 × 3.2 × 1.2 | Z 45° autour de (3.007, 28.047, 8) | `bois_moyen` |  |
| 18 | branche_haute | `bague_haute` | 4.754 22.143 7.05 | 6.854 22.943 8.95 | 2.1 × 0.8 × 1.9 | Z 22.5° autour de (5.804, 22.543, 8) | `or` |  |
| 19 | branche_haute | `crochet_haute` | 2.082 28.639 7.5 | 3.082 31.439 8.5 | 1 × 2.8 × 1 | Z -22.5° autour de (2.582, 30.039, 8) | `or` | crochet relevé vers l'avant |
| 20 | branche_haute | `encoche_haute` | 1.623 28.43 7.5 | 2.623 29.43 8.5 | 1 × 1 × 1 | Z 45° autour de (2.123, 28.93, 8) | `or` |  |
| 21 | branche_haute | `perle_haute` | 1.973 28.78 7.4 | 2.573 29.38 8.6 | 0.6 × 0.6 × 1.2 | Z 45° autour de (2.273, 29.08, 8) | `emeraude` | goutte d'émeraude dans l'encoche |
| 22 | branche_basse | `branche_basse_1` | 7.15 -1.35 7.1 | 9.05 5.35 8.9 | 1.9 × 6.7 × 1.8 |  | `bois_moyen` |  |
| 23 | branche_basse | `dos_basse_1` | 8.6 -1 7.3 | 9.15 5 8.7 | 0.55 × 6 × 1.4 |  | `bois_clair` | superposé sur le dos de la branche |
| 24 | branche_basse | `branche_basse_2` | 6.102 -7.122 7.2 | 7.802 -0.422 8.8 | 1.7 × 6.7 × 1.6 | Z -22.5° autour de (6.952, -3.772, 8) | `bois_moyen` |  |
| 25 | branche_basse | `dos_basse_2` | 7.352 -6.772 7.4 | 7.902 -0.772 8.6 | 0.55 × 6 × 1.2 | Z -22.5° autour de (6.952, -3.772, 8) | `bois_clair` | superposé sur le dos de la branche |
| 26 | branche_basse | `nervure_basse_2` | 6.652 -6.172 7.1 | 7.252 -1.372 8.9 | 0.6 × 4.8 × 1.8 | Z -22.5° autour de (6.952, -3.772, 8) | `emeraude` | dépasse de 0,1 devant et derrière |
| 27 | branche_basse | `branche_basse_3` | 4.097 -11.703 7.3 | 5.597 -6.003 8.7 | 1.5 × 5.7 × 1.4 | Z -22.5° autour de (4.847, -8.853, 8) | `bois_moyen` |  |
| 28 | branche_basse | `dos_basse_3` | 5.147 -11.353 7.5 | 5.697 -6.353 8.5 | 0.55 × 5 × 1 | Z -22.5° autour de (4.847, -8.853, 8) | `bois_clair` | superposé sur le dos de la branche |
| 29 | branche_basse | `nervure_basse_3` | 4.547 -10.753 7.2 | 5.147 -6.953 8.8 | 0.6 × 3.8 × 1.6 | Z -22.5° autour de (4.847, -8.853, 8) | `emeraude` | dépasse de 0,1 devant et derrière |
| 30 | branche_basse | `branche_basse_4` | 2.357 -13.647 7.4 | 3.657 -10.447 8.6 | 1.3 × 3.2 × 1.2 | Z -45° autour de (3.007, -12.047, 8) | `bois_moyen` |  |
| 31 | branche_basse | `bague_basse` | 4.754 -6.943 7.05 | 6.854 -6.143 8.95 | 2.1 × 0.8 × 1.9 | Z -22.5° autour de (5.804, -6.543, 8) | `or` |  |
| 32 | branche_basse | `crochet_basse` | 2.082 -15.439 7.5 | 3.082 -12.639 8.5 | 1 × 2.8 × 1 | Z 22.5° autour de (2.582, -14.039, 8) | `or` | crochet relevé vers l'avant |
| 33 | branche_basse | `encoche_basse` | 1.623 -13.43 7.5 | 2.623 -12.43 8.5 | 1 × 1 × 1 | Z 45° autour de (2.123, -12.93, 8) | `or` |  |
| 34 | branche_basse | `perle_basse` | 1.973 -13.38 7.4 | 2.573 -12.78 8.6 | 0.6 × 0.6 × 1.2 | Z 45° autour de (2.273, -13.08, 8) | `emeraude` | goutte d'émeraude dans l'encoche |
| 35 | corde | `corde` | 1.973 -12.93 7.85 | 2.273 28.93 8.15 | 0.3 × 41.861 × 0.3 |  | `corde` | de l'encoche basse à l'encoche haute |

Affichages (`display` du modèle) :

| Contexte | Rotation | Translation | Échelle |
|---|---|---|---|
| `thirdperson_righthand` | -80 80 -30 | -0.95 -2.23 2.14 | 0.55 |
| `thirdperson_lefthand` | -80 -80 30 | -0.95 -2.23 2.14 | 0.55 |
| `firstperson_righthand` | -46.13 90 21.13 | -1.87 5.57 0.85 | 0.32 |
| `firstperson_lefthand` | -46.13 -90 -21.13 | -1.87 5.57 0.85 | 0.32 |
| `gui` | 0 0 -45 | 1.71 -1.71 0 | 0.475 |
| `fixed` | 0 180 45 | -1.6 1.6 0 | 0.446 |
| `on_shelf` | 0 180 45 | -1.6 1.6 0 | 0.446 |
| `ground` | 0 0 0 | 0.35 0 0 | 0.234 |
| `head` | 0 180 0 | 0 13 7 | 0.6 |

## Sceptre du Néant (Mage) : `fluxdungeons:sceptre_neant`

Bâton arcanique : long manche noir veiné de runes violettes, renforts en losange, tête asymétrique (une longue griffe recourbée à gauche, une griffe courte à droite, une griffe arrière), cristal d'améthyste allongé à cœur bleu, six éclats en suspension montant en spirale autour du cristal, trois arcs d'orbite qui suggèrent leur rotation, pointe inférieure de métal.

| Dimension | Valeur |
|---|---|
| Longueur (Y) | 47.501 (de -16 à 31.501) |
| Largeur max (X) | 11.07 (de 2.555 à 13.625) |
| Épaisseur max (Z) | 6.373 |
| Prise (point tenu par le poing) | (8, 2, 8) |
| Centre visuel (milieu de l'encombrement) | (8.09, 7.751, 8.209) |
| Longueur en main, 3e personne | 31.351 px de joueur (joueur = 32) |
| Nombre de cubes | **37** |

Palette :

| Matériau (texture) | Couleur | Lumineux |
|---|---|---|
| `noir` | Noir profond `#171521` |  |
| `violet_sombre` | Violet sombre `#35214F` |  |
| `violet_moyen` | Violet moyen `#7446B5` |  |
| `violet_lumineux` | Violet lumineux `#B47AFF` | oui |
| `amethyste` | Améthyste `#D0A0FF` | oui |
| `energie` | Énergie secondaire `#5A9BFF` | oui |
| `metal` | Métal (ajout : pointe) `#8C86A6` |  |

Hiérarchie (groupes, dans l'ordre de construction) : `pointe` (4), `manche` (9), `tete` (11), `cristal` (4), `eclats` (9)

| # | Groupe | Cube | De (x, y, z) | À (x, y, z) | Taille | Rotation | Matériau | Note |
|---|---|---|---|---|---|---|---|---|
| 1 | pointe | `pointe_fin` | 7.7 -16 7.7 | 8.3 -15.2 8.3 | 0.6 × 0.8 × 0.6 |  | `metal` |  |
| 2 | pointe | `pointe_milieu` | 7.4 -15.2 7.4 | 8.6 -14 8.6 | 1.2 × 1.2 × 1.2 | Y 45° autour de (8, -14.6, 8) | `metal` |  |
| 3 | pointe | `pointe_base` | 7.1 -14.1 7.1 | 8.9 -12.7 8.9 | 1.8 × 1.4 × 1.8 |  | `metal` |  |
| 4 | pointe | `pointe_collier` | 6.9 -12.8 6.9 | 9.1 -12.1 9.1 | 2.2 × 0.7 × 2.2 |  | `violet_sombre` |  |
| 5 | manche | `manche` | 7.2 -12.2 7.2 | 8.8 17 8.8 | 1.6 × 29.2 × 1.6 |  | `noir` |  |
| 6 | manche | `renfort_bas` | 6.9 -6.4 6.9 | 9.1 -5.6 9.1 | 2.2 × 0.8 × 2.2 | Y 45° autour de (8, -6, 8) | `violet_sombre` |  |
| 7 | manche | `prise` | 7 -1 7 | 9 5 9 | 2 × 6 × 2 |  | `violet_sombre` | zone de prise, 0,2 autour du manche |
| 8 | manche | `bague_prise_basse` | 6.8 -1.4 6.8 | 9.2 -0.8 9.2 | 2.4 × 0.6 × 2.4 | Y 45° autour de (8, -1.1, 8) | `violet_moyen` |  |
| 9 | manche | `bague_prise_haute` | 6.8 4.8 6.8 | 9.2 5.4 9.2 | 2.4 × 0.6 × 2.4 | Y 45° autour de (8, 5.1, 8) | `violet_moyen` |  |
| 10 | manche | `renfort_haut` | 6.9 9.6 6.9 | 9.1 10.4 9.1 | 2.2 × 0.8 × 2.2 | Y 45° autour de (8, 10, 8) | `violet_sombre` |  |
| 11 | manche | `rune_1` | 8.75 12 7.8 | 9 13.2 8.2 | 0.25 × 1.2 × 0.4 |  | `violet_lumineux` | dépasse de 0,2 du manche, alternée gauche et droite |
| 12 | manche | `rune_2` | 7 13.8 7.8 | 7.25 15 8.2 | 0.25 × 1.2 × 0.4 |  | `violet_lumineux` | dépasse de 0,2 du manche, alternée gauche et droite |
| 13 | manche | `rune_3` | 8.75 15.4 7.8 | 9 16.6 8.2 | 0.25 × 1.2 × 0.4 |  | `violet_lumineux` | dépasse de 0,2 du manche, alternée gauche et droite |
| 14 | tete | `collier` | 6.5 16.5 6.5 | 9.5 18.5 9.5 | 3 × 2 × 3 | Y 45° autour de (8, 17.5, 8) | `violet_sombre` |  |
| 15 | tete | `coupe` | 6 18.5 6.8 | 10 19.3 9.2 | 4 × 0.8 × 2.4 |  | `violet_moyen` |  |
| 16 | tete | `griffe_g1` | 5.335 18.398 7.4 | 6.335 23.098 8.6 | 1 × 4.7 × 1.2 | Z 22.5° autour de (5.835, 20.748, 8) | `violet_sombre` |  |
| 17 | tete | `griffe_g2` | 4.619 22.246 7.45 | 5.519 26.946 8.55 | 0.9 × 4.7 × 1.1 |  | `violet_sombre` |  |
| 18 | tete | `griffe_g3` | 5.339 26.112 7.5 | 6.139 30.312 8.5 | 0.8 × 4.2 × 1 | Z -22.5° autour de (5.739, 28.212, 8) | `violet_sombre` | se recourbe au-dessus du cristal |
| 19 | tete | `griffe_g_pointe` | 6.109 29.679 7.5 | 7.009 30.579 8.5 | 0.9 × 0.9 × 1 | Z 45° autour de (6.559, 30.129, 8) | `violet_lumineux` |  |
| 20 | tete | `griffe_d1` | 9.524 18.436 7.45 | 10.424 22.136 8.55 | 0.9 × 3.7 × 1.1 | Z -22.5° autour de (9.974, 20.286, 8) | `violet_sombre` | griffe courte : asymétrie |
| 21 | tete | `griffe_d2` | 10.148 21.322 7.5 | 10.948 24.522 8.5 | 0.8 × 3.2 × 1 |  | `violet_sombre` |  |
| 22 | tete | `griffe_d_pointe` | 10.148 24.172 7.55 | 10.948 24.972 8.45 | 0.8 × 0.8 × 0.9 | Z 45° autour de (10.548, 24.572, 8) | `energie` |  |
| 23 | tete | `griffe_arriere` | 7.55 18.8 9.45 | 8.45 23 10.35 | 0.9 × 4.2 × 0.9 | X 22.5° autour de (8, 20.9, 9.9) | `violet_sombre` | penchée vers l'arrière (+Z) |
| 24 | tete | `griffe_arriere_pointe` | 7.65 22.65 10.55 | 8.35 23.35 11.25 | 0.7 × 0.7 × 0.7 | X 45° autour de (8, 23, 10.9) | `violet_lumineux` |  |
| 25 | cristal | `cristal_bas` | 7 20.6 6.9 | 9 22.6 9.1 | 2 × 2 × 2.2 | Z 45° autour de (8, 21.6, 8) | `violet_lumineux` |  |
| 26 | cristal | `cristal` | 6.4 22.8 6.6 | 9.6 26 9.4 | 3.2 × 3.2 × 2.8 | Z 45° autour de (8, 24.4, 8) | `amethyste` | élément principal |
| 27 | cristal | `cristal_haut` | 7 26.4 6.9 | 9 28.4 9.1 | 2 × 2 × 2.2 | Z 45° autour de (8, 27.4, 8) | `violet_lumineux` |  |
| 28 | cristal | `cristal_coeur` | 7.55 23.95 6.5 | 8.45 24.85 9.5 | 0.9 × 0.9 × 3 | Z 45° autour de (8, 24.4, 8) | `energie` | dépasse de 0,1 devant et derrière |
| 29 | eclats | `eclat_1` | 2.9 20 7.5 | 3.9 22 8.5 | 1 × 2 × 1 | Z 22.5° autour de (3.4, 21, 8) | `amethyste` | en suspension, aucun contact |
| 30 | eclats | `eclat_2` | 12.05 22.1 6.35 | 13.15 23.9 7.25 | 1.1 × 1.8 × 0.9 | Z -45° autour de (12.6, 23, 6.8) | `violet_lumineux` | en suspension, aucun contact |
| 31 | eclats | `eclat_3` | 10.95 26.8 9.95 | 11.85 28.4 10.85 | 0.9 × 1.6 × 0.9 | X 22.5° autour de (11.4, 27.6, 10.4) | `amethyste` | en suspension, aucun contact |
| 32 | eclats | `eclat_4` | 4.4 27.9 5.4 | 5.2 29.3 6.2 | 0.8 × 1.4 × 0.8 | X -45° autour de (4.8, 28.6, 5.8) | `energie` | en suspension, aucun contact |
| 33 | eclats | `eclat_5` | 9.05 30.4 7.85 | 9.75 31.4 8.55 | 0.7 × 1 × 0.7 | Z 45° autour de (9.4, 30.9, 8.2) | `violet_lumineux` | en suspension, aucun contact |
| 34 | eclats | `eclat_6` | 12.9 19.1 9.5 | 13.5 19.7 10.1 | 0.6 × 0.6 × 0.6 | Y 45° autour de (13.2, 19.4, 9.8) | `energie` | en suspension, aucun contact |
| 35 | eclats | `orbite_1` | 3.25 23.3 6.85 | 3.55 25.9 7.15 | 0.3 × 2.6 × 0.3 |  | `violet_moyen` | trace d'orbite, à gauche, en avant |
| 36 | eclats | `orbite_2` | 11.15 26.6 8.85 | 11.45 29.2 9.15 | 0.3 × 2.6 × 0.3 | Z 45° autour de (11.3, 27.9, 9) | `violet_moyen` | trace d'orbite, en haut à droite, en arrière |
| 37 | eclats | `orbite_3` | 10.85 19.2 7.85 | 11.15 21.6 8.15 | 0.3 × 2.4 × 0.3 | Z -45° autour de (11, 20.4, 8) | `violet_moyen` | trace d'orbite, en bas à droite |

Affichages (`display` du modèle) :

| Contexte | Rotation | Translation | Échelle |
|---|---|---|---|
| `thirdperson_righthand` | 0 -90 -37 | 0 1.84 3.82 | 0.66 |
| `thirdperson_lefthand` | 0 90 37 | 0 1.84 3.82 | 0.66 |
| `firstperson_righthand` | 0 -90 -10 | 1.13 1.26 0.02 | 0.36 |
| `firstperson_lefthand` | 0 90 10 | 1.13 1.26 0.02 | 0.36 |
| `gui` | 0 0 -45 | -0.08 -0.11 -0.1 | 0.459 |
| `fixed` | 0 180 45 | -0.1 -0.08 0.09 | 0.43 |
| `on_shelf` | 0 180 45 | -0.1 -0.08 0.09 | 0.43 |
| `ground` | 0 0 0 | -0.02 0.06 -0.05 | 0.232 |
| `head` | 0 180 0 | 0 13 7 | 0.6 |

## Reliquaire de l'Aube (Soigneur) : `fluxdungeons:reliquaire_aube`

Bâton sacré symétrique : manche d'ivoire cerclé d'or, prise bandée de turquoise, calice doré qui porte un halo octogonal d'or clair ; au centre, un cristal turquoise en losange étagé (or, turquoise sombre, turquoise, éclat céleste) tenu par quatre rayons ; deux ailes de quatre plumes déployées derrière le halo, bouts de plume blancs lumineux ; étoile au sommet, pointe dorée en bas.

| Dimension | Valeur |
|---|---|
| Longueur (Y) | 48 (de -16 à 32) |
| Largeur max (X) | 20.045 (de -2.022 à 18.022) |
| Épaisseur max (Z) | 4.243 |
| Prise (point tenu par le poing) | (8, 2.85, 8) |
| Centre visuel (milieu de l'encombrement) | (8, 8, 8) |
| Longueur en main, 3e personne | 31.68 px de joueur (joueur = 32) |
| Nombre de cubes | **49** |

Palette :

| Matériau (texture) | Couleur | Lumineux |
|---|---|---|
| `ivoire` | Ivoire `#F2EAD6` |  |
| `blanc` | Blanc lumineux `#FFFFFF` | oui |
| `or` | Or `#D9B45B` |  |
| `or_clair` | Or clair `#F1D58B` |  |
| `turquoise` | Turquoise `#39D8D0` | oui |
| `turquoise_sombre` | Turquoise sombre `#198F91` |  |
| `celeste` | Bleu céleste `#8CDDF0` | oui |

Hiérarchie (groupes, dans l'ordre de construction) : `pointe` (3), `manche` (7), `calice` (3), `halo` (12), `cristal` (4), `couronne` (2), `ailes` (18)

| # | Groupe | Cube | De (x, y, z) | À (x, y, z) | Taille | Rotation | Matériau | Note |
|---|---|---|---|---|---|---|---|---|
| 1 | pointe | `pointe_fin` | 7.6 -16 7.6 | 8.4 -15 8.4 | 0.8 × 1 × 0.8 |  | `or_clair` |  |
| 2 | pointe | `pointe_milieu` | 7.3 -15 7.3 | 8.7 -13.5 8.7 | 1.4 × 1.5 × 1.4 | Y 45° autour de (8, -14.25, 8) | `or` |  |
| 3 | pointe | `pointe_base` | 6.9 -13.5 6.9 | 9.1 -12.3 9.1 | 2.2 × 1.2 × 2.2 |  | `or` |  |
| 4 | manche | `manche` | 7.2 -12.4 7.2 | 8.8 16.5 8.8 | 1.6 × 28.9 × 1.6 |  | `ivoire` |  |
| 5 | manche | `cercle_1` | 6.9 -8 6.9 | 9.1 -7.3 9.1 | 2.2 × 0.7 × 2.2 |  | `or` |  |
| 6 | manche | `cercle_2` | 6.9 -0.6 6.9 | 9.1 0.1 9.1 | 2.2 × 0.7 × 2.2 |  | `or` |  |
| 7 | manche | `cercle_3` | 6.9 5.6 6.9 | 9.1 6.3 9.1 | 2.2 × 0.7 × 2.2 |  | `or` |  |
| 8 | manche | `cercle_4` | 6.9 11.5 6.9 | 9.1 12.2 9.1 | 2.2 × 0.7 × 2.2 |  | `or` |  |
| 9 | manche | `prise` | 7 0.1 7 | 9 5.6 9 | 2 × 5.5 × 2 |  | `turquoise_sombre` | zone de prise, entre deux cercles |
| 10 | manche | `perle_prise` | 7.55 2.4 6.9 | 8.45 3.3 9.1 | 0.9 × 0.9 × 2.2 | Z 45° autour de (8, 2.85, 8) | `celeste` | dépasse de 0,1 devant et derrière |
| 11 | calice | `socle` | 6.5 16.3 6.5 | 9.5 17.5 9.5 | 3 × 1.2 × 3 | Y 45° autour de (8, 16.9, 8) | `or` |  |
| 12 | calice | `calice` | 5.9 17.5 6.8 | 10.1 18.5 9.2 | 4.2 × 1 × 2.4 |  | `or_clair` |  |
| 13 | calice | `col` | 7.2 18.5 7.4 | 8.8 19.7 8.6 | 1.6 × 1.2 × 1.2 |  | `or` |  |
| 14 | halo | `halo_haut` | 5.83 29.5 7.3 | 10.17 30.5 8.7 | 4.34 × 1 × 1.4 |  | `or_clair` |  |
| 15 | halo | `halo_bas` | 5.83 19.5 7.3 | 10.17 20.5 8.7 | 4.34 × 1 × 1.4 |  | `or_clair` |  |
| 16 | halo | `halo_cote_g` | 2.5 22.83 7.3 | 3.5 27.17 8.7 | 1 × 4.34 × 1.4 |  | `or_clair` |  |
| 17 | halo | `halo_cote_d` | 12.5 22.83 7.3 | 13.5 27.17 8.7 | 1 × 4.34 × 1.4 |  | `or_clair` |  |
| 18 | halo | `halo_diag_haut_g` | 2.294 28.036 7.3 | 6.634 29.036 8.7 | 4.34 × 1 × 1.4 | Z 45° autour de (4.464, 28.536, 8) | `or_clair` | segment d'octogone |
| 19 | halo | `halo_diag_haut_d` | 9.366 28.036 7.3 | 13.706 29.036 8.7 | 4.34 × 1 × 1.4 | Z -45° autour de (11.536, 28.536, 8) | `or_clair` | segment d'octogone |
| 20 | halo | `halo_diag_bas_g` | 2.294 20.964 7.3 | 6.634 21.964 8.7 | 4.34 × 1 × 1.4 | Z -45° autour de (4.464, 21.464, 8) | `or_clair` | segment d'octogone |
| 21 | halo | `halo_diag_bas_d` | 9.366 20.964 7.3 | 13.706 21.964 8.7 | 4.34 × 1 × 1.4 | Z 45° autour de (11.536, 21.464, 8) | `or_clair` | segment d'octogone |
| 22 | halo | `rayon_haut` | 7.7 27 7.6 | 8.3 29.6 8.4 | 0.6 × 2.6 × 0.8 |  | `or` |  |
| 23 | halo | `rayon_bas` | 7.7 20.4 7.6 | 8.3 23 8.4 | 0.6 × 2.6 × 0.8 |  | `or` |  |
| 24 | halo | `rayon_g` | 3.4 24.7 7.6 | 6 25.3 8.4 | 2.6 × 0.6 × 0.8 |  | `or` |  |
| 25 | halo | `rayon_d` | 10 24.7 7.6 | 12.6 25.3 8.4 | 2.6 × 0.6 × 0.8 |  | `or` |  |
| 26 | cristal | `ecrin` | 6.2 23.2 7.6 | 9.8 26.8 8.4 | 3.6 × 3.6 × 0.8 | Z 45° autour de (8, 25, 8) | `or` | fond de la sertissure |
| 27 | cristal | `cristal_bord` | 6.5 23.5 7.3 | 9.5 26.5 8.7 | 3 × 3 × 1.4 | Z 45° autour de (8, 25, 8) | `turquoise_sombre` | superposé, en retrait |
| 28 | cristal | `cristal` | 6.7 23.7 7 | 9.3 26.3 9 | 2.6 × 2.6 × 2 | Z 45° autour de (8, 25, 8) | `turquoise` | élément central |
| 29 | cristal | `cristal_eclat` | 7.4 24.7 6.9 | 8.6 25.9 9.1 | 1.2 × 1.2 × 2.2 | Z 45° autour de (8, 25.3, 8) | `celeste` | dépasse de 0,1 devant et derrière |
| 30 | couronne | `etoile_tige` | 7.6 30.5 7.6 | 8.4 32 8.4 | 0.8 × 1.5 × 0.8 |  | `blanc` |  |
| 31 | couronne | `etoile` | 7.45 30.45 7.5 | 8.55 31.55 8.5 | 1.1 × 1.1 × 1 | Z 45° autour de (8, 31, 8) | `or_clair` |  |
| 32 | ailes | `plume_1_g` | -1.848 22.248 7.6 | 4.652 23.548 8.4 | 6.5 × 1.3 × 0.8 | Z -45° autour de (1.402, 22.898, 8) | `ivoire` | derrière le halo (épaisseur 0,8) |
| 33 | ailes | `plume_1_d` | 11.348 22.248 7.6 | 17.848 23.548 8.4 | 6.5 × 1.3 × 0.8 | Z 45° autour de (14.598, 22.898, 8) | `ivoire` | derrière le halo (épaisseur 0,8) |
| 34 | ailes | `plume_1_bout_g` | -1.213 24.313 7.5 | -0.013 25.513 8.5 | 1.2 × 1.2 × 1 | Z 45° autour de (-0.613, 24.913, 8) | `blanc` |  |
| 35 | ailes | `plume_1_bout_d` | 16.013 24.313 7.5 | 17.213 25.513 8.5 | 1.2 × 1.2 × 1 | Z -45° autour de (16.613, 24.913, 8) | `blanc` |  |
| 36 | ailes | `plume_2_g` | -1.772 20.098 7.6 | 4.228 21.398 8.4 | 6 × 1.3 × 0.8 | Z -22.5° autour de (1.228, 20.748, 8) | `ivoire` | derrière le halo (épaisseur 0,8) |
| 37 | ailes | `plume_2_d` | 11.772 20.098 7.6 | 17.772 21.398 8.4 | 6 × 1.3 × 0.8 | Z 22.5° autour de (14.772, 20.748, 8) | `ivoire` | derrière le halo (épaisseur 0,8) |
| 38 | ailes | `plume_2_bout_g` | -1.774 21.143 7.5 | -0.574 22.343 8.5 | 1.2 × 1.2 × 1 | Z 45° autour de (-1.174, 21.743, 8) | `blanc` |  |
| 39 | ailes | `plume_2_bout_d` | 16.574 21.143 7.5 | 17.774 22.343 8.5 | 1.2 × 1.2 × 1 | Z -45° autour de (17.174, 21.743, 8) | `blanc` |  |
| 40 | ailes | `plume_3_g` | -0.6 17.95 7.6 | 4.4 19.25 8.4 | 5 × 1.3 × 0.8 |  | `ivoire` | derrière le halo (épaisseur 0,8) |
| 41 | ailes | `plume_3_d` | 11.6 17.95 7.6 | 16.6 19.25 8.4 | 5 × 1.3 × 0.8 |  | `ivoire` | derrière le halo (épaisseur 0,8) |
| 42 | ailes | `plume_3_bout_g` | -0.8 18 7.5 | 0.4 19.2 8.5 | 1.2 × 1.2 × 1 | Z 45° autour de (-0.2, 18.6, 8) | `blanc` |  |
| 43 | ailes | `plume_3_bout_d` | 15.6 18 7.5 | 16.8 19.2 8.5 | 1.2 × 1.2 × 1 | Z -45° autour de (16.2, 18.6, 8) | `blanc` |  |
| 44 | ailes | `plume_4_g` | 1.337 16.261 7.6 | 4.937 17.561 8.4 | 3.6 × 1.3 × 0.8 | Z 22.5° autour de (3.137, 16.911, 8) | `ivoire` | derrière le halo (épaisseur 0,8) |
| 45 | ailes | `plume_4_d` | 11.063 16.261 7.6 | 14.663 17.561 8.4 | 3.6 × 1.3 × 0.8 | Z -22.5° autour de (12.863, 16.911, 8) | `ivoire` | derrière le halo (épaisseur 0,8) |
| 46 | ailes | `plume_4_bout_g` | 1.244 15.775 7.5 | 2.444 16.975 8.5 | 1.2 × 1.2 × 1 | Z 45° autour de (1.844, 16.375, 8) | `blanc` |  |
| 47 | ailes | `plume_4_bout_d` | 13.556 15.775 7.5 | 14.756 16.975 8.5 | 1.2 × 1.2 × 1 | Z -45° autour de (14.156, 16.375, 8) | `blanc` |  |
| 48 | ailes | `aile_os_g` | 1 19.3 8.4 | 5.2 20.1 8.9 | 4.2 × 0.8 × 0.5 | Z -22.5° autour de (3.1, 19.7, 8.65) | `or` | devant les plumes |
| 49 | ailes | `aile_os_d` | 10.8 19.3 8.4 | 15 20.1 8.9 | 4.2 × 0.8 × 0.5 | Z 22.5° autour de (12.9, 19.7, 8.65) | `or` | devant les plumes |

Affichages (`display` du modèle) :

| Contexte | Rotation | Translation | Échelle |
|---|---|---|---|
| `thirdperson_righthand` | 0 -90 -37 | 0 1.39 3.48 | 0.66 |
| `thirdperson_lefthand` | 0 90 37 | 0 1.39 3.48 | 0.66 |
| `firstperson_righthand` | 0 -90 -10 | 1.13 0.96 -0.03 | 0.36 |
| `firstperson_lefthand` | 0 90 10 | 1.13 0.96 -0.03 | 0.36 |
| `gui` | 0 0 -45 | -0.31 -0.31 0 | 0.446 |
| `fixed` | 0 180 45 | -0.29 -0.29 0 | 0.418 |
| `on_shelf` | 0 180 45 | -0.29 -0.29 0 | 0.418 |
| `ground` | 0 0 0 | 0 0 0 | 0.229 |
| `head` | 0 180 0 | 0 13 7 | 0.6 |
