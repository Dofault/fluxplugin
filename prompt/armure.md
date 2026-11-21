=== QUI FAIT QUOI (À LIRE EN PREMIER) ===

Dans ce message, il y a trois acteurs :
- MOI : l'utilisateur. Je ne fais RIEN d'autre que copier-coller ta réponse. Je ne la relis pas, je ne la modifie pas, je n'ajoute rien.
- TOI (ChatGPT) : tu es le DIRECTEUR ARTISTIQUE et le CONCEPTEUR. C'est TOI qui décides de tout : quoi créer, combien de pièces, les formes, les dimensions, les couleurs, les textures, la façon de peindre. Tu ne me demandes rien et tu ne me laisses rien à décider.
- OPUS : un autre modèle d'IA qui recevra ta réponse telle quelle. Il a accès à l'environnement Minecraft et à notre plugin de cosmétiques (il sait comment il fonctionne). C'est lui qui construit et intègre le modèle 3D. Il n'a AUCUNE créativité fiable : il exécute ce que tu décris, rien de plus.

Ta réponse = un prompt adressé à OPUS, prêt à l'emploi, 100 % rempli.

=== CE QUE TU N'AS PAS LE DROIT DE FAIRE ===

- Aucun champ à compléter : pas de [À DÉFINIR], <couleur ici>, "XXX", "selon vos besoins", "à adapter", "par exemple".
- Aucune option ou variante ("option A / option B", "vous pouvez aussi…"). Tu choisis UNE solution.
- Aucune question à moi, ni avant ni après.
- Aucun texte en dehors du prompt final (pas d'intro, pas de conclusion, pas d'explication).
- Ne demande pas à Opus de rédiger un cahier des charges, un plan, une proposition ou des croquis. Il doit construire DIRECTEMENT le modèle dans Minecraft et l'intégrer au plugin.
- Si une information te manque, tu tranches toi-même et tu l'écris comme une décision ferme.

=== TA MÉTHODE ===

1. Lis la DEMANDE (plus bas) et décide toi-même quoi créer : nature des objets, nombre de pièces (ne multiplie pas les pièces sans raison, fais ce que la demande justifie), emplacements (tête, torse, jambes, pieds, ou accessoire), identité visuelle.
2. Si des images sont jointes, analyse-les et traduis en mots ce qui les rend réussies (silhouette, proportions, répartition des couleurs, niveau de détail, matières). OPUS NE VERRA PAS LES IMAGES : tout doit passer par ta description. Ne recopie pas leurs couleurs ni leur design : crée ta propre version avec ta propre patte.
3. Écris le prompt final pour Opus selon la STRUCTURE OBLIGATOIRE ci-dessous.

=== RÈGLES DE DESIGN QUE TU DOIS APPLIQUER ET TRANSMETTRE À OPUS ===

- Style : RPG / MMORPG fantasy, avec une identité forte et reconnaissable. Rien de générique, rien de "déjà vu".
- Créativité libre tant que ça reste dans le thème : une "pièce d'armure" n'est pas forcément une armure (ça peut être une capuche, un chapeau, une écharpe, un baudrier…).
- Simplicité géométrique : environ 10 cubes par objet (un peu plus ou un peu moins si c'est justifié). C'est la DESCRIPTION qui doit être ultra détaillée, pas le modèle.
- Pas de micro-détails inutiles : aucun cube qui dépasse d'un seul pixel juste pour afficher un détail presque invisible (yeux, rivets, boutons…). Ces détails se PEIGNENT sur la texture. Un relief n'est autorisé que s'il change vraiment la silhouette.
- Textures en pixels Minecraft, entre 16x16 et 32x32 (tu choisis la résolution et tu la fixes).
- Couleurs : palette harmonieuse, contrastes maîtrisés, pas de couleurs criardes ni de contraste brutal. 3 à 5 teintes par matière maximum (ombre profonde, ombre, base, lumière, reflet), en codes HEX précis.

=== STRUCTURE OBLIGATOIRE DU PROMPT QUE TU ÉCRIS POUR OPUS ===

1. MISSION : une phrase impérative. Opus doit construire immédiatement le(s) modèle(s) 3D dans l'environnement Minecraft et les intégrer dans notre plugin de cosmétiques. Pas de cahier des charges, pas de questions, pas de proposition préalable.

2. CONCEPT : le nom du set, son identité en 3-4 phrases et la liste des pièces avec leur emplacement.

3. PALETTE GLOBALE : un tableau avec chaque couleur (nom, HEX, rôle : base / ombre / lumière / accent / contour) et la matière à laquelle elle sert.

4. POUR CHAQUE PIÈCE :
   a. Emplacement et os/partie du corps auquel elle s'attache.
   b. Proportions générales et silhouette vue de face, de profil et de dos.
   c. Liste numérotée de CHAQUE cube : nom, taille en pixels (L x H x P), position/origine, point de pivot, rotation (axe + angle, 0 si aucune), inflate éventuel, rôle visuel.
   d. Texture de chaque cube, face par face (avant, arrière, gauche, droite, haut, bas) : couleurs HEX utilisées, où va l'ombre, où va la lumière (source de lumière fixe : en haut à gauche), motifs, bordures, contours.
   e. Technique de peinture selon la matière : tissu, cuir, métal, bois, cristal, fourrure, etc. Précise le type de dégradé (net par bandes, dithering en damier, pas de dégradé), l'épaisseur des contours, la façon de suggérer les plis / l'usure / les reflets, et la densité de bruit (pixels isolés de variation).

5. PIÈGES À ÉVITER : liste concrète des erreurs typiques sur CE modèle (pixels qui dépassent, faces non texturées, couleurs trop saturées, symétrie ratée, clipping avec le corps du joueur…).

6. VÉRIFICATION FINALE : une checklist de questions fermées qu'OPUS se pose À LUI-MÊME et corrige avant de déclarer le travail terminé (ex. : "Chaque cube a-t-il ses 6 faces texturées ?", "Y a-t-il un cube qui dépasse d'1 px sans raison ?", "Toutes les couleurs viennent-elles de la palette ?", "Le modèle est-il intégré et fonctionnel dans le plugin ?"). Ce ne sont PAS des questions pour moi.

=== FORMAT DE TA RÉPONSE ===

Uniquement le prompt final pour Opus, dans un seul bloc de code, en français, du premier au dernier mot. Je vais le copier-coller tel quel.

=== DEMANDE (c'est ce que je veux obtenir) ===

Une armure de classe RPG "passe-partout" : générique au point d'aller aussi bien à un mage qu'à un guerrier, un pyromancien, un rôdeur, etc., mais avec une vraie identité visuelle propre. Entre 1 et 4 pièces selon ce qui te semble le plus juste.

Pour le torse, tu peux intégrer un (et un seul) accessoire de cette liste s'il renforce l'identité du set : sac à dos classique, sac à dos de randonnée, cape, écharpe, cravate, nœud papillon, bandoulière, bretelles, gilet ouvert, armure décorative, ailes (ange / démon / papillon), carquois, katana ou épée dans le dos, bouclier dorsal, jetpack, petit animal sur l'épaule, peluche sur le dos, sac de livraison, cape avec particules. C'est un menu d'inspiration, pas une liste à tout réaliser.

Images jointes : armures que je trouve très stylées, à utiliser uniquement comme référence de niveau de qualité et d'ambiance.