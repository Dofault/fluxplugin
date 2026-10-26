# FluxOffering

Plugin Paper (1.21.8) qui gere des **collections d'items** ("offrandes") que les joueurs
remplissent progressivement, en plusieurs depots, sauvegardes par joueur. Le plugin ne
donne **aucune** recompense et ne gere **aucun** rankup : il expose uniquement la
progression via PlaceholderAPI. C'est a vous d'exploiter ces placeholders (rankup,
quete Typewriter, recompense...) dans vos propres systemes.

Dependances :
- **ItemsAdder** (soft depend) — pour utiliser des items custom dans les collections. Le
  plugin fonctionne parfaitement sans (items 100% vanilla) si ItemsAdder n'est pas installe.
- **PlaceholderAPI** (soft depend) — pour exposer `%offrandes_*%`. Le plugin fonctionne
  sans (les depots marchent, seuls les placeholders sont indisponibles).
- **Vault** (soft depend) — pour le depot de BitBlock (`money` d'une collection, voir
  ci-dessous). Sans Vault (ou sans plugin d'economie derriere), tout le reste du plugin
  fonctionne normalement ; seul le depot d'argent echoue proprement.
- Aucune autre dependance, aucune base de donnees a configurer.

---

## Arborescence du projet

```
FluxOffering/
├── pom.xml
├── README.md
└── src/main/
    ├── java/fr/influencraft/fluxoffering/
    │   ├── FluxOfferingPlugin.java          # onEnable/onDisable, cablage
    │   ├── command/
    │   │   ├── OffrandeCommand.java             # /offrande (joueur)
    │   │   ├── OffrandeTabCompleter.java
    │   │   ├── FluxOfferingAdminCommand.java    # /influoffrande (admin : create/reset/set/check/reload)
    │   │   ├── FluxOfferingAdminTabCompleter.java
    │   │   └── TabCompleteUtil.java
    │   ├── config/
    │   │   ├── ConfigManager.java             # config.yml + collections.yml (lecture/ecriture)
    │   │   ├── GuiSettings.java
    │   │   ├── Settings.java
    │   │   └── MessagesManager.java           # messages.yml
    │   ├── data/
    │   │   ├── DataManager.java               # stockage YAML par joueur
    │   │   └── PlayerDataListener.java        # cache au join/quit
    │   ├── gui/
    │   │   ├── FluxOfferingHolder.java        # marqueur d'inventaire
    │   │   ├── CollectionMenuHolder.java / CollectionMenu.java
    │   │   ├── GuiListener.java                # tous les clics/drags
    │   │   ├── GuiItems.java                   # boutons nav/filtre/editeur
    │   │   ├── EditorChatListener.java         # saisie de quantite au tchat (editeur admin)
    │   │   └── PendingQuantityInput.java
    │   ├── integration/
    │   │   ├── ItemsAdderHook.java
    │   │   ├── PlaceholderHook.java
    │   │   └── OffrandePlaceholderExpansion.java
    │   ├── model/
    │   │   ├── ItemKey.java                    # materiau vanilla OU id ItemsAdder
    │   │   ├── CollectionDefinition.java
    │   │   └── PlayerData.java
    │   ├── service/
    │   │   ├── DepositService.java             # scan/consommation d'inventaire
    │   │   └── DepositResult.java
    │   ├── text/
    │   │   └── TextUtil.java                   # legacy + hex + MiniMessage + PAPI
    │   ├── wiki/
    │   │   └── WikiLinks.java                  # URL de la page wiki d'un objet demande
    │   └── util/
    │       ├── AccessUtil.java
    │       ├── AmountFormatter.java
    │       ├── ProgressCalculator.java
    │       └── SoundUtil.java
    └── resources/
        ├── plugin.yml
        ├── config.yml
        ├── collections.yml
        └── messages.yml
```

---

## Compilation

```bash
cd plugins/FluxOffering
mvn package
```

Le jar compile (`target/FluxOffering-1.0.0.jar`) est automatiquement copie vers
`serveurs/03_semirp/plugins/FluxOffering.jar` par le plugin `maven-antrun-plugin`
declare dans le `pom.xml` — un simple restart/reload du serveur suffit ensuite.

> L'API ItemsAdder est compilee directement contre le jar installe sur le serveur
> (`serveurs/03_semirp/plugins/ItemsAdder_4.0.17.jar`, dependance `system` dans le
> `pom.xml`, meme technique que `plugins/mana-sync`) : aucune version buildee de
> `com.github.LoneDev6:api-itemsadder` n'existe sur jitpack pour la branche 4.0.x au
> moment de l'ecriture. Si vous mettez a jour ItemsAdder, mettez a jour le numero de
> version dans le `pom.xml` (`<version>`/`<systemPath>`).

---

## Configuration

### `config.yml` — reglages globaux et GUI

- `settings.accept-renamed-items` (bool) : accepter un item renomme a l'enclume.
- `settings.accept-damaged-tools` (bool) : accepter un outil/armure a durabilite entamee.
- Enchantements : un objet depose doit porter exactement les enchantements de l'objet demande (aucun pour un materiau vanilla, ceux de la definition pour un item ItemsAdder), vanilla et EcoEnchants confondus (EcoEnchants s'inscrit dans le registre vanilla). Une pioche enchantee ne remplit donc jamais une exigence `DIAMOND_PICKAXE`.
- `settings.autosave-interval-minutes` : frequence de sauvegarde de fond (en plus de la
  sauvegarde immediate a chaque depot et a l'arret du serveur).
- `gui.*` : taille d'inventaire, emplacements des slots d'items/navigation/filtre/editeur,
  sons, materiaux des boutons (ItemsAdder `namespace:id` ou materiau vanilla), formats de titre.
  `gui.item-slots` est une grille de 3 lignes de 7 (21 slots) ; la 1ere case de cette liste
  est reservee a l'argent (voir plus bas) des qu'une collection en demande, ou en permanence
  en mode edition.
- `wiki.*` : lien wiki propose sur un objet que le joueur n'a pas (voir « Lien wiki » plus bas).
  `wiki.enabled` coupe la fonction, `wiki.url-format` porte `{title}`, `wiki.titles` liste les
  rares objets dont le titre de page ne se deduit pas de leur id.

### `collections.yml` — les offrandes elles-memes

Fichier **separe** de `config.yml` : c'est celui-ci que l'editeur en jeu (bouton
d'edition, admin/op) reecrit a chaque modification, pour ne jamais risquer de perdre les
commentaires de `config.yml`.

```yaml
collections:
  collection1:
    displayname: "&6Offrande du Mineur"
    permission: ""                     # vide = accessible a tous les joueurs (offrandes.use)
    money: 1000                        # BitBlock requis, 0 = aucun argent demande
    items:
      DIAMOND: 64
      IRON_INGOT: 128
      "influitems:mana_crystal": 10000 # id ItemsAdder namespace:id (guillemets obligatoires)
    names:                             # optionnel : nom affiche par item
      DIAMOND: "Diamant"
```

- **Cle d'item** : un materiau Bukkit en toutes lettres (`DIAMOND`) OU un id ItemsAdder
  `namespace:id` (detecte automatiquement des qu'il contient `:`).
- **`money`** : montant de BitBlock (Vault) requis pour completer l'offrande, en plus des
  items. 0 (ou absent) = pas d'argent demande, la case dediee n'apparait pas pour les
  joueurs. Se modifie en jeu (bouton dedie dans l'editeur, cf. plus bas) ou en console via
  `/influoffrande money <collection> <montant>`.
- **Compatibilite items ItemsAdder generes par un autre plugin** (ex. les poissons
  CustomFishing bases sur un modele ItemsAdder) : si l'item ne porte pas le tag interne
  ItemsAdder (cas frequent quand un plugin tiers construit son propre ItemStack en
  reprenant juste le Material + CustomModelData du modele, sans passer par l'API
  ItemsAdder), le depot retombe automatiquement sur une comparaison Material +
  CustomModelData contre l'item de reference. Ca ignore volontairement tout le reste
  (nom, lore, taille du poisson...) — seul le modele visuel compte pour identifier l'item.
- **Effet brillant** : l'icone d'un item (ou de l'argent) brille uniquement une fois son
  objectif depose en entier, jamais tant que c'est incomplet. Les livres enchantes brillent
  nativement et ne sont pas concernes.
- **Quantites > 64** : affichees comme un seul icone. La quantite
  suit le nom de l'item : `(4 x64)`, `(8 x16)`, `(1 x64 + 36)`, ou un nombre simple `(30)`
  pour un item non stackable ou moins d'un stack. Format personnalisable dans `messages.yml`
  (`format.amount-*`).
- **Livres enchantes** : une cle `enchant:<id>` (id vanilla, ex. `"enchant:sharpness": 100`)
  demande un cumul de **niveaux** de cet enchantement. Chaque livre enchante (vanilla,
  `ENCHANTED_BOOK` avec l'enchantement stocke) ajoute son niveau : Tranchant V = +5. Choix des
  livres "au plus juste" (le plus haut niveau qui ne depasse pas le reste, sinon le plus bas) ;
  le depassement du dernier livre est perdu, pas rembourse. Un livre portant plusieurs
  enchantements demandes par la meme offrande credite chacun d'eux. Dans l'editeur, cliquer
  un livre enchante de son inventaire ajoute l'exigence d'enchantement (pas le materiau).
  Clic droit = un seul livre. Noms par defaut (items et enchantements vanilla) traduits cote client (`<lang:...>`) selon la langue du joueur,
  surchargeables via `names:` (texte fixe).

#### Permissions par rang — comment ca marche

Chaque collection a un champ `permission` optionnel. La regle d'acces
(`util/AccessUtil.java`) est : **ops/`offrandes.admin` voient tout** ; sinon **`permission`
vide = ouvert a tous** ; sinon il faut soit **la permission**, soit **avoir deja termine
cette offrande par le passe** (verifie sur la progression sauvegardee, independamment de la
permission actuelle).

Cette derniere regle est ce qui fait marcher la progression par rang sans configuration
supplementaire : un joueur `repute` qui a fini l'offrande `citoyen` y garde acces (il l'a
completee, peu importe qu'il n'ait plus la permission `citoyen`), mais n'a pas acces a
`supreme` (ni la permission, ni completee). Il suffit donc de donner a **chaque grade la
permission de sa propre offrande uniquement** — pas besoin de lister les offrandes
precedentes :

```
/lp group citoyen permission set offrandes.collection.citoyen true
/lp group repute permission set offrandes.collection.repute true
/lp group supreme permission set offrandes.collection.supreme true
```

avec, dans `collections.yml` :

```yaml
citoyen:
  permission: "offrandes.collection.citoyen"
repute:
  permission: "offrandes.collection.repute"
supreme:
  permission: "offrandes.collection.supreme"
```

### `messages.yml`

Tous les textes joueur/admin/GUI. Supporte simultanement, dans la meme chaine :
- legacy (`&7`, `&#RRGGBB`)
- MiniMessage (`<gradient>...</gradient>`, `<hover>`...)
- placeholders PlaceholderAPI (`%joueur_x%`, et les glyphes `%img_x%` ItemsAdder/influtags)

Les `{accolades}` (ex. `{amount}`, `{name}`, `{percent}`) sont des placeholders internes
propres a chaque message, documentes en commentaire juste au-dessus dans le fichier.

---

## Commandes

`/offrande` est strictement joueur, `/influoffrande` strictement admin — separes pour
qu'une collection ne puisse jamais s'appeler `reset`/`set`/`check`/`reload` et devenir
inaccessible, et pour qu'un joueur ne voie jamais les sous-commandes admin en tab-completion.

| Commande | Permission | Effet |
| --- | --- | --- |
| `/offrande` | `offrandes.use` (defaut: tous) | Equivalent a `/offrande <grade du joueur>` (via `%luckperms_current_group_on_track_standard%`, pour ignorer les grades staff hors track) — pas de liste generique. Si aucune offrande ne correspond a son grade, message "offrande inconnue" |
| `/offrande <collection>` | `offrandes.use` | Ouvre l'interface de depot de cette offrande precise (typiquement une offrande d'un rang passe, deja completee) |
| `/offrande depot <collection> <montant>` | `offrandes.use` | Depose ce montant de BitBlock sur l'offrande. Pas faite pour etre tapee : c'est la commande cliquable envoyee au clic gauche sur la case argent, pour que le montant ne passe pas par le tchat |
| `/influoffrande create <id> [nom affiche]` | `offrandes.admin` | Cree une offrande vide (id : lettres/chiffres/`_`/`-` uniquement, pas de point). A remplir ensuite via l'editeur en jeu (`/offrande <id>`, bouton d'edition) |
| `/influoffrande remove <id>` | `offrandes.admin` | Supprime une offrande de `collections.yml` (ne touche pas a la progression deja sauvegardee des joueurs) |
| `/influoffrande rename <id> <nouveau nom>` | `offrandes.admin` | Change le nom affiche d'une offrande |
| `/influoffrande permission <id> <permission\|clear>` | `offrandes.admin` | Definit (ou retire avec `clear`) la permission requise pour acceder a une offrande |
| `/influoffrande money <id> <montant>` | `offrandes.admin` | Definit le BitBlock requis pour cette offrande (0 = retire l'exigence) |
| `/influoffrande reset <joueur> <collection\|all>` | `offrandes.admin` | Reinitialise la progression (items et argent deposes) |
| `/influoffrande set <joueur> <collection> <item\|money> <quantite>` | `offrandes.admin` | Force une valeur (bornee entre 0 et le requis) ; `money` force le montant depose par le joueur |
| `/influoffrande check <joueur> [collection]` | `offrandes.admin` | Affiche la progression d'un joueur |
| `/influoffrande reload` | `offrandes.admin` | Recharge `config.yml`/`collections.yml`/`messages.yml` sans redemarrer |

Tab-completion complete sur les noms de collection pour `/offrande`, et sur toutes les
sous-commandes/joueurs en ligne/items d'une collection pour `/influoffrande`.

## Interface en jeu

- **Depot** : chaque clic sur un item consomme automatiquement ce que le joueur possede
  (jusqu'a la quantite demandee), joue un son (`entity.experience_orb.pickup` par
  defaut) et affiche `+32 Diamant (56/64)` + le pourcentage global. Une offrande deja
  complete pour ce joueur reste consultable, en lecture seule.
- **Argent** (case dediee, 1ere case de la grille, seulement si `money > 0`) : clic droit
  depose d'un coup tout le solde du joueur (plafonne a ce qu'il reste a devoir) ; clic
  gauche ferme le menu et envoie dans le tchat la commande `/offrande depot <collection>`
  toute prete et cliquable : un clic l'ecrit dans la barre de saisie, le joueur n'a plus qu'a
  taper son montant (depot progressif). Le solde vient de Vault. Le montant transite donc par
  une commande et jamais par le tchat : l'ancienne invite « ecris le montant dans le tchat »
  laissait le nombre s'afficher publiquement, les plugins de formatage de tchat rediffusant
  leur propre copie du message avant meme que l'evenement soit annule.
- **Tout deposer** (bouton dedie) : depose d'un coup tout ce qui peut l'etre sur tous les
  items de l'offrande (items uniquement, pas l'argent — action separee et deliberee via la
  case dediee), avec un message recapitulatif.
- **Lien wiki** : un objet dont le joueur n'a aucun exemplaire n'offre aucun depot, sa case
  propose donc a la place `%img_left_click% Voir le wiki de l'objet`, et le clic gauche envoie
  dans le tchat un bloc encadre (calque sur le `/discord` de DiscordSRV, `gui.wiki-link` dans
  `messages.yml`) avec le lien cliquable de la page. L'URL est derivee de l'id vanilla de
  l'objet : `fr.minecraft.wiki` garde des redirections depuis les titres anglais vers ses
  articles francais (`Stone` -> Roche, `Rotten Flesh` -> Chair putrefiee), donc un objet
  ajoute a une offrande a son lien sans intervention. Les 5 ids dont le titre de page ne se
  deduit pas (`SLIME_BALL` -> `Slimeball`...) sont listes dans `wiki.titles` (config.yml).
  Un item non vanilla (ItemsAdder, espece CustomFishing) n'a pas de page : le bouton ne
  s'affiche pas, le clic retombe sur le message habituel « tu n'as pas cet objet ».
- **Filtre** (bouton dedie) : "Tout" par defaut ; "Deposes" = exigences deja entierement
  remplies ; "Non deposes" = tout ce qu'il reste a deposer. Les deux filtres portent sur
  l'avancement de l'offrande, jamais sur l'inventaire du joueur, et sont donc exactement
  complementaires. Le choix est memorise dans le fichier de donnees du joueur
  (`menu-filter-mode`), donc conserve a la reouverture du menu comme apres une reconnexion.
- **Pagination** : fleches automatiques si plus d'items que de slots disponibles.
- **Editeur** (admin/op, bouton en haut a gauche) : clic gauche sur un item existant pour
  changer sa quantite (saisie au tchat, pour permettre des valeurs bien au-dela de 64),
  clic droit pour le retirer ; cliquer un item de son propre inventaire pendant l'edition
  l'ajoute comme nouvelle exigence (quantite demandee ensuite au tchat). La case argent
  (toujours visible en edition, meme a 0) suit le meme principe : clic gauche pour
  definir/changer le montant demande au tchat, clic droit pour retirer l'exigence.

---

## Placeholders (PlaceholderAPI)

| Placeholder | Valeur |
| --- | --- |
| `%offrandes_<collection>%` | `true` ou `false` |
| `%offrandes_<collection>_progress%` | `2/3` (types d'items completes / total — l'argent, si demande, compte comme un type de plus une fois entierement depose) |
| `%offrandes_<collection>_percent%` | `73` (pourcentage global, entier 0-100 — moyenne du ratio items et du ratio argent quand une collection demande les deux, pour qu'un gros montant n'ecrase pas la progression sur les items) |

`<collection>` est l'id tel qu'ecrit dans `collections.yml` (ex. `collection1`).
Fonctionne aussi bien pour un joueur en ligne que hors-ligne (lecture directe du fichier
de sauvegarde).

---

## Stockage

Un fichier YAML par joueur, `plugins/FluxOffering/data/<uuid>.yml`. Le montant de BitBlock
depose sur une collection vit dans la meme section que les items de cette collection, sous
une cle `money` reservee (aucune cle d'item ne peut valoir litteralement `money`, cf.
`ItemKey.parse`). Choix retenu plutot
qu'une base de donnees : chaque fichier pese quelques centaines d'octets, les ecritures
ne sont declenchees que par un depot ou une modification admin (jamais en boucle/poll),
et ca evite toute dependance JDBC supplementaire. Sauvegarde immediate a chaque depot,
sauvegarde de securite periodique (`autosave-interval-minutes`) et sauvegarde totale a
l'arret du serveur.

---

## Note de migration — ancien systeme `/offrande` (DeluxeMenus)

Ce plugin remplace l'ancien dispatcher DeluxeMenus qui possedait deja la commande
`/offrande` (`plugins/DeluxeMenus/gui_menus/offrandes/offrande.yml` + 11 menus
`offrande_<rang>.yml`, un depot par metas booleennes sans quantites reelles). Ces 12
fichiers ont ete desactives (`register_command: false`, contenu conserve) pour liberer
la commande — ils restent ouvrables via `[openguimenu]` si besoin, mais plus par un
joueur tapant `/offrande_citoyen` etc.

**Pont Typewriter (fait, 2026-07-22) :** l'ancien flux posait une meta persistante finale
(ex. `offrande-citoyen`) lue par Typewriter pour declencher le dialogue de rankup. Les 7
quetes de passage de rang qui existent a ce jour (Citoyen -> Repute -> Populaire -> Eminent
-> Figure -> Influenceur -> Icone) sont rebranchees sur `%offrandes_<collection>%` (fait
`value_placeholder`, nomme d'apres le rang **cible** obtenu, ex. le fait de la quete
Citoyen s'appelle `offrande_repute` et lit `%offrandes_citoyen%`). L'etape finale de
chaque quete exige desormais **deux** conditions : l'offrande (`%offrandes_<collection>%
== true`) et un palier de succes EpicAchievements (`%achievements_points%`, fait global
`epicachievement`), suivant la courbe de progression officielle : Citoyen 150, Repute 225,
Populaire 340, Eminent 500, Figure 750, Influenceur 1150, Icone 1700 (branche cote
Typewriter), puis Legende 3400, Mythe 6800, Souverain 13500 (pas encore branchables, ces
quetes n'existent pas encore). Le titre affiche au declenchement est passe de « Offrande
completee » a « Etape finale completee ». La quete Legende n'existe pas encore dans
Typewriter malgre la collection `legende` deja presente dans `collections.yml` — a creer
avant de pouvoir brancher `%offrandes_legende%`. Le contenu reel des collections
(quantites par rang) reste a definir dans `collections.yml` a partir de `docs/offrande.md`.

## Compatibilite Paper 26.2 / 26.3

Compile contre l'API Paper 26.2 (serveur actuel), `api-version: "26.2"` dans `plugin.yml`,
donc chargeable tel quel en 26.3. Verifier la compatibilite 26.3 avant migration :
`mvn -P paper-26.3 clean compile` (version pilotee par la propriete `paper.api.version`).
