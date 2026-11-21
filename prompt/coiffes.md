# Cinq coiffes MMORPG pour Minecraft : fiche d'exécution

Tu vas modéliser et peindre cinq coiffes portées sur la tête d'un joueur Minecraft : une casquette, des lunettes de soleil de frimeur, un bob, un chapeau de cowboy et un bandeau de sport. Ce sont des cosmétiques d'un serveur MMORPG fantasy : chaque objet reste exactement ce qu'il est (une casquette reste une casquette), mais dans une version qui semble sortie d'un univers de fantasy, avec une identité propre.

Toutes les décisions artistiques sont déjà prises ci-dessous : formes, dimensions, couleurs, façon de peindre. Ton travail est de les exécuter fidèlement. N'ajoute aucun cube, aucune couleur et aucun ornement qui n'est pas décrit. Si une consigne te semble impossible à réaliser telle quelle, arrête-toi et dis laquelle, plutôt que d'improviser.

L'objectif est un modèle simple et lisible : peu de cubes, des silhouettes franches, des textures propres. La précision est dans cette fiche, pas dans le nombre de détails du modèle.

## 1. Repère et règles de construction communes

**Repère.** Une unité vaut un pixel de skin. La tête du joueur est le cube qui va de x = -4 à 4, y = 24 à 32, z = -4 à 4. Le visage est sur la face +z (l'avant). +x est la gauche du porteur, -x sa droite. +y est le haut. Les yeux d'un skin classique sont entre y = 27 et y = 28.

**Écriture des cubes.** Chaque cube est donné par son coin minimal `[x, y, z]` et son coin maximal `[x, y, z]`. Une rotation est donnée par un axe, un angle, un pivot, et surtout par son effet en clair (« l'avant plonge vers le bas ») : c'est l'effet décrit qui fait foi, vérifie-le sur l'aperçu.

**Distance à la tête.** La seconde couche du skin (cheveux, capuche) s'arrête à 4,5 du centre. Toute pièce qui enveloppe la tête a donc ses parois entre 4 et 5 : jamais plus près, sinon la couche du skin passe au travers.

**Ce qu'il ne faut pas faire.**

- Aucun cube plus petit que 2 pixels sur deux de ses dimensions. Un détail plus petit se peint sur la texture, il ne se modélise pas.
- Aucun cube qui dépasse d'un seul pixel pour figurer un petit détail (bouton, rivet, boucle, œillet). Les seuls décalages d'un pixel autorisés sont ceux listés dans cette fiche, et ils portent tous sur de grandes surfaces.
- Aucune face cachée peinte pour rien : les faces tournées vers l'intérieur de la tête sont retirées.
- Pas de rotation sur deux axes à la fois.

## 2. Règles de peinture communes

Tu es le peintre. Chaque texture se peint pixel par pixel, un pixel de texture par pixel de modèle (pas de texture étirée, pas de filtre, pas de dégradé automatique).

**Lumière.** Elle vient d'en haut, de l'avant et de la droite du porteur. Conséquences, identiques sur les cinq objets :

- face du dessus : ton clair de la matière ;
- face du dessous : ton d'ombre de la matière ;
- faces avant et droite (-x) : ton de base, avec la rangée du haut en ton clair sur les six dixièmes de sa largeur côté lumière ;
- faces arrière et gauche (+x) : ton de base, avec la rangée du bas en ton d'ombre.

**Trois tons par matière, pas plus.** Chaque matière a un ton de base, un ton clair et un ton d'ombre, donnés dans la palette de l'objet. Une quatrième couleur « sombre profond » sert uniquement aux creux indiqués. N'invente pas de ton intermédiaire.

**Façon de peindre selon la matière.**

- **Tissu (toile, lin, éponge)** : grandes plages unies du ton de base. Le relief vient seulement de la rangée claire du haut et de la rangée ou colonne d'ombre décrites plus haut. Une couture se peint comme une ligne d'un pixel en ton d'ombre, continue, jamais en pointillé. Pas de bruit, pas de pixels dispersés.
- **Feutre** : comme le tissu, mais les transitions se font en marches franches (une rangée entière change de ton), ce qui donne l'aspect épais et rigide.
- **Cuir** : ton de base mat, une bande claire de deux pixels de large au centre des faces du dessus (là où la lumière accroche), les bords laissés au ton de base. Les creux et les dessous en ton d'ombre.
- **Corne et os (montures, ornements)** : aplat du ton de base, un seul trait clair d'un pixel le long de l'arête du haut, un seul trait d'ombre le long de l'arête du bas. Aspect poli, sans grain.
- **Verre teinté** : bandes horizontales franches de haut en bas, sans tramage entre elles. Un seul reflet, court, en diagonale, d'un ton plus clair de la même teinte. Jamais de blanc pur : sur un visage, un pixel blanc se lit comme un œil.
- **Métal (cuivre)** : réservé aux petits ornements peints. Ton de base, un pixel clair en haut à droite du motif, un pixel d'ombre en bas à gauche.
- **Plume** : barbes peintes en rangées, chaque rangée d'un seul ton, la pointe plus sombre que la base. Une ligne centrale (le rachis) d'un pixel en ton d'ombre sur toute la longueur.

**Interdits de peinture.** Pas de noir pur ni de blanc pur. Pas de contour sombre autour des motifs. Pas de pixel isolé d'une couleur différente de ses voisins, sauf ceux que la fiche demande. Pas de tramage en damier.

**Résolution.** Une texture par coiffe, en 32 x 32. Le chapeau de cowboy seul passe en 64 x 32 si ses faces ne tiennent pas. Dans les grilles ci-dessous, chaque lettre est un pixel, les rangées vont du haut vers le bas, et une face se lit telle qu'on la voit de l'extérieur.

## 3. Casquette de l'Éclaireur (`eclaireur`)

**Idée.** La casquette d'un éclaireur des pistes sèches : une vraie casquette à visière, à laquelle on a cousu un couvre-nuque de lin qui protège du soleil. C'est ce couvre-nuque qui fait sa signature : de dos et de profil, on la reconnaît tout de suite. Toile terre cuite passée, lin sable, visière de cuir, une agrafe vert-de-gris pour seul accent.

**Silhouette.** Calotte basse et arrondie par une marche, visière longue qui plonge, trois pans de lin qui tombent derrière et sur les côtés jusqu'au bas de la tête. L'avant reste dégagé : le visage est entièrement visible.

**Palette.**

| Lettre | HEX | Rôle |
| --- | --- | --- |
| T | `#A8644A` | toile terre cuite, ton de base (dominante) |
| L | `#C4836A` | toile, ton clair |
| S | `#7C4638` | toile, ton d'ombre |
| N | `#D8C7A0` | lin sable du couvre-nuque, base (secondaire) |
| C | `#EADDBB` | lin, ton clair |
| M | `#B3A07A` | lin, ton d'ombre |
| V | `#4A3328` | cuir de la visière, base (tertiaire) |
| U | `#6B4B3A` | cuir, ton clair |
| D | `#3A2622` | sombre profond (dessous de visière, creux) |
| G | `#5FA08C` | vert-de-gris, accent (agrafe seulement) |

**Cubes (6).**

| Nom | De | À | Rotation | Matière | Rôle |
| --- | --- | --- | --- | --- | --- |
| `calotte_bas` | [-5, 30, -5] | [5, 33, 5] | aucune | toile | corps de la casquette, coiffe le haut de la tête |
| `calotte_haut` | [-4, 33, -4] | [4, 34, 4] | aucune | toile | marche qui arrondit le sommet |
| `visiere` | [-4, 30, 5] | [4, 31, 10] | axe X, 12°, pivot [0, 30.5, 5] : le bord avant descend | cuir | visière longue de 5 pixels |
| `nuque` | [-5, 25, -5] | [5, 30, -4] | axe X, 8°, pivot [0, 30, -4.5] : le bas s'écarte vers l'arrière | lin | pan arrière du couvre-nuque |
| `pan_droit` | [-5, 26, -5] | [-4, 30, -1] | aucune | lin | pan sur l'oreille droite, un pixel plus court que la nuque |
| `pan_gauche` | [4, 26, -5] | [5, 30, -1] | aucune | lin | symétrique de `pan_droit` |

`calotte_bas` n'a pas de face du dessous. `visiere` n'a pas de face arrière. Les pans n'ont pas de face tournée vers la tête.

**Peinture.**

- `calotte_bas`, quatre faces de côté (10 x 3) : rangée du haut `L` côté lumière puis `T`, rangée du milieu `T`, rangée du bas en lin `N` tout autour : c'est le galon qui fait le tour de la casquette et la relie au couvre-nuque. Sur la face avant, l'agrafe : un carré de 2 x 2 en `G` centré, à cheval sur les rangées du milieu et du bas.

  ```
  LLLLLLTTTT
  TTTTGGTTTT
  NNNNGGNNNN
  ```

- `calotte_haut`, dessus (8 x 8) : ton `L`, avec une croix de coutures en `T`, large de deux pixels (colonnes 4 et 5, rangées 4 et 5), qui sépare le dessus en quatre quartiers de 3 x 3. Côtés (8 x 1) : `T`.
- `calotte_bas`, dessus (visible seulement sur l'anneau d'un pixel autour de `calotte_haut`) : `T`.
- `visiere`, dessus (8 x 5) : `V` partout, avec une bande `U` de deux pixels de profondeur au milieu, qui s'arrête à un pixel des bords gauche et droit. Dessous : `D`. Tranches : `V`.
- `nuque`, face arrière (10 x 5) : `N`, rangée du haut `C` côté lumière, colonne de gauche du porteur en `M`, et la rangée du bas en `M` (l'ourlet). Une couture verticale d'un pixel en `M` au centre, du haut jusqu'à l'ourlet.
- `pan_droit` et `pan_gauche`, face extérieure (4 x 4) : `N`, rangée du bas `M`. Tranche avant (1 x 4) : `M`.

**À vérifier sur l'aperçu.** De face, on voit une casquette normale et rien d'autre ne gêne le visage. De profil, le couvre-nuque descend bien plus bas que la visière. La visière plonge vers le bas, elle ne remonte pas.

## 4. Lunettes du Mirage (`mirage`)

**Idée.** Les lunettes de soleil d'un frimeur des oasis : trop grandes exprès, monture en corne ivoire, verres teintés qui passent du bleu canard profond au pêche comme un mirage sur l'horizon. La signature est leur démesure : les verres débordent d'un pixel de chaque côté du visage.

**Silhouette.** Une barre de sourcils de 12 pixels de large (la tête en fait 8), deux grands verres rectangulaires de 5 x 4 dessous, deux branches épaisses qui partent vers l'arrière. Rien d'autre.

**Palette.**

| Lettre | HEX | Rôle |
| --- | --- | --- |
| I | `#E4D9C0` | corne ivoire, base (dominante) |
| H | `#F3EBD6` | corne, ton clair |
| O | `#B9AC90` | corne, ton d'ombre |
| P | `#1F4B55` | verre, bleu canard profond (secondaire) |
| Q | `#3F8A7E` | verre, vert d'eau (tertiaire, et reflet) |
| A | `#E8B48A` | verre, pêche (bas du verre) |
| K | `#D0694F` | corail, accent (charnières seulement) |

**Cubes (5).** Les faces avant sont à z = 5,1 et les faces arrière à z = 4,1 : les lunettes sont à cheval sur la seconde couche du skin, donc collées au visage sur n'importe quel skin.

| Nom | De | À | Rotation | Matière | Rôle |
| --- | --- | --- | --- | --- | --- |
| `barre` | [-6, 29, 4.1] | [6, 30, 5.1] | aucune | corne | barre de sourcils, tient les deux verres |
| `verre_droit` | [-6, 25, 4.1] | [-1, 29, 5.1] | aucune | verre | verre de 5 x 4 sur l'œil droit |
| `verre_gauche` | [1, 25, 4.1] | [6, 29, 5.1] | aucune | verre | symétrique |
| `branche_droite` | [-5, 28, -2] | [-4.5, 30, 4.1] | aucune | corne | branche haute de 2 pixels, jusqu'à l'oreille |
| `branche_gauche` | [4.5, 28, -2] | [5, 30, 4.1] | aucune | corne | symétrique |

Pas de face arrière sur `barre` ni sur les verres. Pas de face du dessus sur les verres. Pas de face intérieure ni de face avant sur les branches. Il n'y a volontairement pas de pont entre les verres : la barre fait ce travail, et les deux pixels de peau visibles entre les verres dessinent le nez.

**Peinture.**

- Verres, face avant (5 x 4). Le verre droit se lit ainsi, le gauche est son miroir (le reflet reste côté extérieur du visage) :

  ```
  PQPPP
  QPPPP
  QQQQQ
  AAAAA
  ```

  Les deux `Q` du coin sont le reflet en diagonale. Tranches et dessous des verres : `O` (c'est la monture vue par la tranche).
- `barre`, face avant (12 x 1) : `K K` aux deux extrémités (un pixel corail à chaque bout, les charnières), `I` entre les deux. Dessus : `H`. Dessous : `O`. Tranches : `I`.
- Branches, face extérieure (6 x 2) : rangée du haut `H`, rangée du bas `I`, et la dernière colonne à l'arrière en `O`. Dessus `H`, dessous `O`.

**À vérifier sur l'aperçu.** De face, les verres dépassent bien d'un pixel de chaque côté de la tête et cachent les yeux. Aucun pixel du verre n'est blanc ou presque blanc. De profil, la branche est horizontale et touche la tête.

## 5. Bob du Passeur (`passeur`)

**Idée.** Le bob d'un passeur de gué des marais : toile cirée vert lichen, doublure brique, une corde de chanvre nouée autour de la calotte. Sa signature : le bord avant est relevé et plaqué contre la calotte, ce qui montre la doublure brique et dégage le front, pendant que le reste du bord tombe.

**Silhouette.** Calotte haute et droite, bord tombant en deux marches sur les côtés et l'arrière, grand rabat relevé à l'avant, légèrement penché vers l'avant.

**Palette.**

| Lettre | HEX | Rôle |
| --- | --- | --- |
| E | `#7E8C6A` | toile lichen, base (dominante) |
| F | `#9BA884` | toile, ton clair |
| J | `#5F6C52` | toile, ton d'ombre |
| B | `#B8705C` | doublure brique, base (secondaire) |
| R | `#CD8A74` | doublure, ton clair |
| W | `#8F5346` | doublure, ton d'ombre |
| Y | `#D6C49A` | corde de chanvre, base (accent) |
| Z | `#A8956D` | corde, ton d'ombre |
| X | `#3E4636` | sombre profond (dessous du bord) |

**Cubes (6).**

| Nom | De | À | Rotation | Matière | Rôle |
| --- | --- | --- | --- | --- | --- |
| `calotte` | [-5, 31, -5] | [5, 35, 5] | aucune | toile | calotte droite, haute de 4 |
| `bord_haut` | [-6, 30, -6] | [6, 31, 6] | aucune | toile | première marche du bord, tout autour |
| `bord_droit` | [-7, 28, -5] | [-5, 30, 5] | aucune | toile | bord tombant sur le côté droit |
| `bord_gauche` | [5, 28, -5] | [7, 30, 5] | aucune | toile | symétrique |
| `bord_arriere` | [-7, 28, -7] | [7, 30, -5] | aucune | toile | bord tombant à l'arrière, sur toute la largeur |
| `rabat` | [-6, 31, 6] | [6, 34, 7] | axe X, 15°, pivot [0, 31, 6.5] : le haut s'écarte vers l'avant | doublure | bord avant relevé, 12 de large, 3 de haut |

`calotte` n'a pas de face du dessous. Les bords tombants n'ont pas de face tournée vers la tête. Il n'y a volontairement aucun bord tombant à l'avant : le front reste visible à partir de y = 30.

**Peinture.**

- `calotte`, côtés (10 x 4) : rangée du haut `F` côté lumière puis `E`, deux rangées `E`, rangée du bas en corde `Y`. Sur la corde, un pixel sur trois est en `Z` (`YYZYYZYYZY`) : c'est la torsade, et c'est le seul motif répété autorisé. Sur la face droite seulement, le nœud : un carré de 2 x 2 en `Y` avec son pixel bas gauche en `Z`, placé sur les rangées 3 et 4, à trois pixels du bord arrière.
- `calotte`, dessus (10 x 10) : `F`, avec le pourtour d'un pixel en `E` (la couture du fond).
- `bord_haut`, dessus : `E`. Tranches (12 x 1) : `E`. Dessous : `X`.
- `bord_droit`, `bord_gauche`, `bord_arriere`, faces extérieures (2 de haut) : rangée du haut `E`, rangée du bas `J`. Dessous : `X`. Dessus : `E`.
- `rabat`, face avant (12 x 3) : c'est la doublure. Rangée du haut `R` côté lumière puis `B`, rangée du milieu `B`, rangée du bas `W`. Une ligne de piqûre : la deuxième rangée porte un pixel `W` aux colonnes 1 et 12 seulement (les deux points d'attache du rabat). Face arrière du rabat (visible au-dessus de `bord_haut`) : `E`. Tranches et dessus : `J`.

**À vérifier sur l'aperçu.** De face, on voit un rectangle brique devant la calotte verte, et le front du joueur est dégagé. De profil et de dos, le bord tombe bien plus bas qu'à l'avant. La corde fait le tour complet sans rupture aux angles.

## 6. Chapeau du Traqueur (`traqueur`)

**Idée.** Le chapeau d'un chasseur de primes des hauts plateaux : un vrai chapeau de cowboy, mais en feutre gris fumée et non en cuir brun, avec un bandeau bleu pétrole garni de trois conchos de cuivre. Sa signature est sa carrure : bords latéraux roulés très haut, pointes avant et arrière qui plongent, calotte haute au pli marqué.

**Silhouette.** Vu de face, un grand V évasé formé par les deux bords roulés. Vu de profil, une ligne qui plonge à l'avant et à l'arrière. La calotte est haute (6 pixels en tout) et son sommet est creusé par une gouttière.

**Palette.**

| Lettre | HEX | Rôle |
| --- | --- | --- |
| T | `#8F857A` | feutre gris fumée, base (dominante) |
| L | `#AEA598` | feutre, ton clair |
| S | `#6A6158` | feutre, ton d'ombre |
| D | `#453E38` | sombre profond (gouttière, dessous des bords) |
| P | `#2F4F5C` | bandeau bleu pétrole, base (secondaire) |
| Q | `#47707D` | bandeau, ton clair |
| K | `#B8703F` | cuivre des conchos (accent) |
| H | `#E0A070` | cuivre, ton clair |

**Cubes (9).**

| Nom | De | À | Rotation | Matière | Rôle |
| --- | --- | --- | --- | --- | --- |
| `bord_centre` | [-5, 30, -7] | [5, 31, 7] | aucune | feutre | partie plate du bord, sous la calotte |
| `bord_avant` | [-4, 30, 7] | [4, 31, 10] | axe X, 15°, pivot [0, 30.5, 7] : la pointe avant descend | feutre | pointe avant, plus étroite que le centre |
| `bord_arriere` | [-4, 30, -10] | [4, 31, -7] | axe X, 15°, pivot [0, 30.5, -7] : la pointe arrière descend | feutre | symétrique avant-arrière |
| `bord_droit` | [-9, 30, -7] | [-5, 31, 7] | axe Z, 30°, pivot [-5, 30.5, 0] : le bord extérieur monte | feutre | bord latéral roulé |
| `bord_gauche` | [5, 30, -7] | [9, 31, 7] | axe Z, 30°, pivot [5, 30.5, 0] : le bord extérieur monte | feutre | symétrique |
| `calotte_bas` | [-5, 31, -5] | [5, 34, 5] | aucune | feutre | bas de la calotte, porte le bandeau |
| `calotte_haut` | [-4, 34, -4] | [4, 36, 4] | aucune | feutre | haut de la calotte, en retrait d'un pixel |
| `crete_droite` | [-4, 36, -4] | [-2, 37, 4] | aucune | feutre | bourrelet droit du pli |
| `crete_gauche` | [2, 36, -4] | [4, 37, 4] | aucune | feutre | bourrelet gauche du pli |

`calotte_bas`, `calotte_haut` et les crêtes n'ont pas de face du dessous. Les bords latéraux n'ont pas de face tournée vers `bord_centre`. Les deux crêtes laissent entre elles une gouttière de 4 pixels de large : c'est le pli du chapeau.

**Peinture.**

- `calotte_bas`, côtés (10 x 3) : rangée du haut `L` côté lumière puis `T`, puis deux rangées de bandeau, `Q` au-dessus et `P` en dessous. Sur la face avant seulement, trois conchos, chacun un carré de 2 x 2 à cheval sur les deux rangées du bandeau, séparés par un pixel de bandeau, le groupe centré. Chaque concho : `KH` au-dessus, `KK` en dessous.

  ```
  LLLLLLTTTT
  QKHQKHQKHQ
  PKKPKKPKKP
  ```

- `calotte_haut`, côtés (8 x 2) : rangée du haut `T`, rangée du bas `S` (la calotte se resserre, le bas est dans l'ombre du retrait). Dessus (8 x 8), visible seulement dans la gouttière : `D` au centre sur deux pixels de large, `S` de part et d'autre.
- Crêtes, dessus (2 x 8) : `L`. Côtés : `T`. Le flanc qui donne dans la gouttière : `S`.
- `bord_centre`, dessus : `T`. Dessous : `D`. Tranches : `S`.
- `bord_avant` et `bord_arriere`, dessus (8 x 3) : `T`, avec la rangée du bout (la plus éloignée de la calotte) en `L` : la pointe accroche la lumière. Dessous : `D`. Tranches : `S`.
- `bord_droit` et `bord_gauche`, dessus (4 x 14) : trois colonnes `T` côté calotte, la colonne extérieure en `L` sur toute la longueur : c'est l'arête roulée qui prend la lumière. Dessous : `D`, avec la colonne extérieure en `S`. Tranche extérieure : `L`.

**À vérifier sur l'aperçu.** De face, les deux bords roulés montent nettement plus haut que le bord central et forment un V. De profil, l'avant et l'arrière plongent. De dessus, la gouttière sombre est bien visible entre les deux crêtes. Les trois conchos sont alignés et lisibles à distance.

## 7. Bandeau du Zéphyr (`zephyr`)

**Idée.** Le bandeau de sport d'un coureur des crêtes : une large bande d'éponge bleu orage, rayée ivoire et safran, sans nœud. Pour la touche fantasy, deux petites ailes de plumes claires fixées au-dessus des oreilles et couchées vers l'arrière, comme si le vent les rabattait. Les ailes sont la signature : elles changent la silhouette de la tête de face comme de profil.

**Silhouette.** Une bande de 3 pixels de haut qui fait le tour du front, et une aile de chaque côté, plus haute que le sommet de la tête, penchée en arrière.

**Palette.**

| Lettre | HEX | Rôle |
| --- | --- | --- |
| B | `#3E5873` | éponge bleu orage, base (dominante) |
| L | `#587696` | éponge, ton clair |
| S | `#2C4057` | éponge, ton d'ombre |
| C | `#E6DCC6` | ivoire : rayure centrale et plumes (secondaire) |
| M | `#BFB59C` | ivoire, ton d'ombre |
| G | `#D49A3A` | safran : fines rayures (accent) |
| P | `#8FA3B5` | gris bleuté : pointe des plumes (tertiaire) |

**Cubes (6).** La bande est faite de quatre parois et non d'un seul cube traversant la tête.

| Nom | De | À | Rotation | Matière | Rôle |
| --- | --- | --- | --- | --- | --- |
| `avant` | [-5, 28, 4] | [5, 31, 5] | aucune | éponge | bande sur le front |
| `arriere` | [-5, 28, -5] | [5, 31, -4] | aucune | éponge | bande à l'arrière |
| `cote_droit` | [-5, 28, -4] | [-4, 31, 4] | aucune | éponge | bande sur la tempe droite |
| `cote_gauche` | [4, 28, -4] | [5, 31, 4] | aucune | éponge | symétrique |
| `aile_droite` | [-6, 29, -3] | [-5, 34, 1] | axe X, 25°, pivot [-5.5, 29, -1] : le haut de l'aile part vers l'arrière | plume | aile de 4 de large et 5 de haut |
| `aile_gauche` | [5, 29, -3] | [6, 34, 1] | même rotation, pivot [5.5, 29, -1] | plume | symétrique |

Les parois n'ont pas de face tournée vers la tête. Les ailes n'ont pas de face intérieure.

**Peinture.**

- Bande, faces extérieures (3 de haut), identique sur les quatre parois pour que la rayure se rejoigne aux angles. La bande reste bleue avant tout : rangée du haut `L` côté lumière puis `B`, rangée du milieu `C` (la rayure ivoire), rangée du bas `B`, avec la dernière colonne de chaque face en `S`. Le safran ne sert qu'à l'avant : sur la face avant, la rayure ivoire est interrompue en son centre par quatre pixels `GCCG`, soit deux traits safran qui encadrent le milieu du front.
- Bande, dessus : `L`. Dessous : `S`.
- Ailes, face extérieure (4 x 5), aile droite telle qu'on la voit de l'extérieur, l'avant du joueur à gauche de la grille. L'aile gauche est son miroir.

  ```
  .PPP
  PCCP
  CCMC
  CMCC
  MCCM
  ```

  Le point de la première rangée est un pixel transparent : c'est le seul pixel transparent de toute la série, il donne à l'aile sa pointe arrondie. La diagonale de `M` qui monte de l'avant bas vers l'arrière haut est le rachis. Les `P` du haut sont les pointes des plumes. Le `M` aux deux coins du bas ancre l'aile dans le bandeau.
- Ailes, tranches (1 de large) : `M`. Dessus : `P`.

**À vérifier sur l'aperçu.** De face, les deux ailes dépassent le sommet de la tête. De profil, l'aile penche vers l'arrière, pas vers l'avant. La rayure ivoire fait le tour de la tête sans décalage aux quatre angles.

## 8. Fabrication dans le dépôt

Ces coiffes s'ajoutent aux coiffes existantes de FluxCosmetics, sans en remplacer aucune.

- Écris un constructeur par coiffe dans `plugins/fluxplugin/FluxCosmetics/assets/gen_coiffes.py` (ou un module voisin comme `coiffes_chapeaux.py`), avec les outils de `coiffes_kit.py` : `Cube`, `mirror`, `paint`, `stamp`, `soft`, `walls`. Lis d'abord un constructeur existant pour reprendre ses conventions (ordre des faces, sens des grilles, signe des rotations). Ajoute les cinq identifiants à `HATS`.
- Génère les aperçus sans `--deploy`, regarde les huit vues de chaque coiffe, corrige, et recommence tant qu'un point de vérification échoue.
- Seulement ensuite : `--deploy`, puis une entrée par coiffe dans `FluxCosmetics/src/main/resources/config.yml` (et la config de Labs), sur le modèle des coiffes existantes. Noms affichés et raretés : Bandeau du Zéphyr (commun), Bob du Passeur (commun), Casquette de l'Éclaireur (unique), Chapeau du Traqueur (unique), Lunettes du Mirage (épique). La couleur du nom est le ton clair de la matière dominante de la coiffe.
- Le pack se reconstruit avec `/iazip` sur Labs. Lance `resources/sync.py` avant de commiter.

## 9. Contrôle final

Avant de dire que c'est terminé, réponds à chacune de ces questions pour chacune des cinq coiffes, en regardant les aperçus et non le code :

1. Quelqu'un qui ne connaît pas la fiche nomme-t-il l'objet du premier coup (casquette, lunettes de soleil, bob, chapeau de cowboy, bandeau de sport) ?
2. La signature annoncée est-elle visible sur au moins deux vues (couvre-nuque, verres démesurés, rabat brique, bords roulés et gouttière, ailes) ?
3. Le nombre de cubes est-il exactement celui de la fiche (6, 5, 6, 9, 6) ?
4. Toutes les couleurs de la texture viennent-elles de la palette de la coiffe, sans aucune autre ?
5. Chaque rotation produit-elle l'effet décrit en clair (ce qui doit descendre descend, ce qui doit partir en arrière part en arrière) ?
6. Aucune face ne clignote ni ne traverse la tête ou la seconde couche du skin ?
7. Le visage reste-t-il lisible (seules les lunettes ont le droit de cacher les yeux) ?
8. De loin, à la taille réelle en jeu, les matières se distinguent-elles encore les unes des autres ?
9. L'icône d'inventaire montre-t-elle la coiffe entière, cadrée sur elle ?

Si une réponse est non, corrige et revérifie. Termine par un court bilan : pour chaque coiffe, le nombre de cubes, la taille de la texture, ce qui a dû être ajusté par rapport à la fiche et pourquoi. Dis ce qui a réellement été généré et déployé, et ce qui ne l'a pas été. Montre les aperçus.
