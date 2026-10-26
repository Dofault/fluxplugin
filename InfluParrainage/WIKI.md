# InfluParrainage, Wiki complet

> Plugin de système de parrainage pour réseau Minecraft (Paper backend)
> Version : 26.2 | Java 21 | Paper 1.21.11

---

## Table des matières

1. [Architecture](#1-architecture)
2. [Mise à jour depuis une version précédente](#2-mise-à-jour-depuis-une-version-précédente)
   - [Depuis 1.0.3 (renommage InfluParrainage + v26.2)](#21-depuis-103-renommage-influparrainage--v262)
   - [Depuis 1.0.2](#22-depuis-102)
   - [Depuis 1.0.1](#23-depuis-101)
3. [Installation](#3-installation)
4. [Configuration](#4-configuration)
5. [Commandes joueur](#5-commandes-joueur)
6. [Commandes admin](#6-commandes-admin)
7. [Tableau des permissions](#7-tableau-des-permissions)
8. [PlaceholderAPI](#8-placeholderapi)
9. [Système de récompenses](#9-système-de-récompenses)
10. [Anti-abus](#10-anti-abus)
11. [Schéma de base de données](#11-schéma-de-base-de-données)
12. [FAQ](#12-faq)

---

## 1. Architecture

Le plugin **InfluParrainage** est un module Paper unique (pas de composant proxy). Il s'appuie sur :

| Composant | Rôle |
|---|---|
| **Paper** | Gestion des événements joueur, commandes, placeholders |
| **MySQL / MariaDB** | Persistance de tous les liens et données de parrainage (mode `mysql`/`auto`) |
| **SQLite** | Fallback local si MySQL/MariaDB est injoignable (mode `auto`/`local`), shadé dans le JAR |
| **HikariCP** | Pool de connexions (shadé dans le JAR, aucune dépendance côté serveur) |
| **PlaceholderAPI** | Exposition des placeholders (soft-depend, facultatif) |

Toutes les opérations base de données sont **asynchrones** (threads séparés). Les dispatches de commandes de récompenses et les envois de messages en retour vers les joueurs sont toujours ramenés sur le **thread principal** via le scheduler Bukkit.

Le code de parrainage est composé de **6 caractères** tirés d'un alphabet sans ambiguïté visuelle (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789` — sans `O`, `0`, `I`, `1`). Il est généré à la première connexion du joueur et reste permanent.

---

## 2. Mise à jour depuis une version précédente

---

### 2.1 Depuis 1.0.3 (renommage InfluParrainage + v26.2)

Le plugin **Parrainage** a été renommé **InfluParrainage** (convention appliquée aux autres plugins maison : InfluShop, InfluOffrande, InfluBridge, InfluAdvancedEnchantment) et son schéma de version passe de `1.0.x` à `26.2`. Aucun changement de commandes ni de permissions : `/parrainage` et les nœuds `parrainage.*` restent identiques.

Ce renommage introduit un nouveau paramètre `base-de-donnees.mode` dans `config.yml`, absent en 1.0.3 :

```yaml
base-de-donnees:
  mode: auto
  # ...
  fichier-local: "parrainage.db"
```

- `auto` (par défaut) : tente MySQL/MariaDB au démarrage, puis bascule automatiquement sur un fichier **SQLite local** (`fichier-local`, stocké dans le dossier de données du plugin) si la connexion échoue. Pensé pour un environnement de test sans base MariaDB provisionnée (ex : `04_labs`).
- `mysql` : force MySQL/MariaDB, désactive le plugin si la connexion échoue (comportement identique à 1.0.3).
- `local` : force le SQLite local, ignore les identifiants MySQL.

Si vous migrez un `config.yml` existant depuis 1.0.3, ajoutez `base-de-donnees.mode: mysql` (pour conserver le comportement strict d'origine) ou `auto`/`local` selon le besoin, ainsi que `base-de-donnees.fichier-local`.

> Nom de package Java modifié : `fr.influencraft.parrainage` → `fr.influencraft.influparrainage`. Sans impact pour un usage normal (aucune API publique exposée à d'autres plugins).

---

### 2.2 Depuis 1.0.2

La version 1.0.3 est une **version corrective** (bug fixes). Aucune modification du `config.yml` n'est requise.

Si vous avez un fichier `langue_fr.yml` personnalisé déployé sur votre serveur, ajoutez les deux messages suivants dans la section `messages:` :

```yaml
  admin-joueur-pas-parraine: "{prefixe} <red>Ce joueur n'a pas de parrain enregistré."
  admin-reinitialisation-avertissement: "{prefixe} <yellow>⚠ Toutes les données ont été effacées (filleuls, paliers, code). Pour supprimer un seul lien, utilisez <white>/parrainage admin supprimerparrain <filleul></white>."
```

**Corrections apportées en 1.0.3 :**

- `%parrainage_filleuls%` retournait une valeur erronée — corrigé.
- `/parrainage admin supprimerparrain` ne décrémentait pas le compteur du parrain — l'opération est désormais atomique (transaction SQL unique).
- Les récompenses (parrain, filleul, paliers) n'étaient pas distribuées lors d'un parrainage réussi — corrigé.
- `/parrainage admin supprimerparrain` sur un joueur sans parrain affiche maintenant un message d'erreur explicite.
- `/parrainage admin reinitialiser` affiche désormais un avertissement rappelant l'irréversibilité de la commande.

> **Note :** Le placeholder `%parrainage_filleuls%` produit un log de débogage dans la console (`[Parrainage][DEBUG] Placeholder filleuls demandé pour …`). Ce log a été conservé lors du correctif pour faciliter les tests. Il peut être retiré de `ExpansionParrainage.java` une fois le comportement validé.

---

### 2.3 Depuis 1.0.1

Le `config.yml` a été restructuré en 1.0.2. Si vous avez un fichier déployé depuis la version 1.0.1, appliquez les deux modifications suivantes.

**1. Ajouter la section `recompenses-parrainage`** (récompenses distribuées à chaque parrainage) :

```yaml
recompenses-parrainage:
  parrain:
    commandes:
      - "eco give {joueur} 500"
  filleul:
    commandes:
      - "eco give {joueur} 250"
```

**2. Restructurer la section `paliers`** — les commandes sont désormais imbriquées sous `recompenses.parrain` :

```yaml
# Ancienne structure (1.0.1) — ne fonctionne plus
paliers:
  - filleuls: 3
    recompenses:
      commandes:
        - "give {joueur} diamond 5"
      annonce-globale: true

# Nouvelle structure (1.0.2+) — obligatoire
paliers:
  - filleuls: 3
    recompenses:
      parrain:
        commandes:
          - "give {joueur} diamond 5"
      annonce-globale: true
```

> Sans cette mise à jour, les paliers ne déclencheront aucune commande (les seuils et annonces restent fonctionnels).

---

## 3. Installation

1. Placer `InfluParrainage.jar` dans le dossier `plugins/` du serveur Paper.
2. Démarrer le serveur une fois pour générer les fichiers de configuration.
3. Remplir `plugins/InfluParrainage/config.yml` avec les identifiants de votre base MySQL/MariaDB (sauf en mode `local`, voir §4).
4. Redémarrer le serveur (ou recharger avec `/parrainage admin recharger`).

**Dépendances :**

| Dépendance | Type | Remarque |
|---|---|---|
| Paper 1.21.x | Requise | Testé sur 1.21.11 |
| MySQL / MariaDB | Requise en mode `mysql`/`auto` | Le connecteur MySQL fonctionne avec MariaDB |
| PlaceholderAPI | Optionnelle | Les placeholders `%parrainage_*%` ne sont disponibles que si PAPI est présent |

En mode `mysql` (forcé), si la connexion à la base échoue au démarrage, le plugin se désactive automatiquement et consigne l'erreur dans les logs. En mode `auto` (par défaut), un échec de connexion MySQL/MariaDB déclenche une bascule silencieuse vers le fallback SQLite local plutôt qu'une désactivation — voir §2.1 et §4.

---

## 4. Configuration

Fichier : `plugins/InfluParrainage/config.yml`

```yaml
# --- Base de données ---
base-de-donnees:
  mode: auto               # auto (MySQL puis fallback SQLite), mysql (forcé), local (SQLite forcé)
  hote: localhost
  port: 3306
  nom: influencraft
  utilisateur: root
  mot-de-passe: motdepasse
  prefixe-tables: parr_   # préfixe des tables SQL (ex : parr_joueurs, parr_evenements)
  taille-pool: 10          # taille du pool de connexions HikariCP
  fichier-local: parrainage.db   # fichier SQLite (mode local, ou fallback en mode auto)

# --- Langue ---
langue: fr   # correspond au fichier langue_fr.yml dans plugins/InfluParrainage/

# --- Préfixe messages (MiniMessage) ---
prefixe: "<dark_gray>[<gold>Parrainage</gold>]</dark_gray>"

parrainage:
  # Temps de jeu requis en secondes avant de pouvoir utiliser un code (0 = désactivé)
  temps-jeu-minimum: 3600
  # Empêche le parrainage circulaire (filleul qui parraine son propre parrain)
  bloquer-circulaire: true
  # Notifier le joueur de son code dès sa première connexion
  generation-auto-connexion: false

# --- Récompenses distribuées à chaque parrainage ---
recompenses-parrainage:
  # Commandes exécutées pour le parrain à chaque nouveau filleul
  parrain:
    commandes:
      - "eco give {joueur} 500"
      - "minecraft:experience add {joueur} 100 points"
  # Commandes exécutées pour le filleul lors de son premier parrainage
  filleul:
    commandes:
      - "eco give {joueur} 250"
      - "give {joueur} bread 16"

# --- Paliers de parrainage (récompenses bonus pour le parrain) ---
paliers:
  - filleuls: 3
    recompenses:
      parrain:
        commandes:
          - "give {joueur} diamond 5"
          - "eco give {joueur} 1000"
      annonce-globale: true
  - filleuls: 5
    recompenses:
      parrain:
        commandes:
          - "give {joueur} netherite_ingot 2"
          - "lp user {joueur} parent add vip"
      annonce-globale: true
  - filleuls: 10
    recompenses:
      parrain:
        commandes:
          - "give {joueur} netherite_ingot 10"
          - "lp user {joueur} parent add vip+"
      annonce-globale: true

# --- Classement ---
classement:
  taille-affichage: 10
  intervalle-rafraichissement: 300   # en secondes
```

Le placeholder `{joueur}` dans toutes les commandes est remplacé par le pseudo du destinataire de la récompense (parrain ou filleul selon le bloc) au moment de l'exécution via la console.

---

## 5. Commandes joueur

Toutes ces commandes nécessitent d'être un joueur connecté. Elles ne sont **pas** exécutables depuis la console.

**Alias :** `/referral`, `/parr`

---

#### `/parrainage`

**Usage :** `/parrainage`

Sans argument, affiche l'aide des commandes disponibles (équivalent à `/parrainage aide`). Seules les commandes pour lesquelles le joueur a la permission sont listées.

| Permission | Description |
|---|---|
| `parrainage.commande.aide` | Afficher l'aide joueur |

---

#### `/parrainage code`

**Usage :** `/parrainage code`

Affiche le code de parrainage personnel du joueur. Si aucun code n'existe encore (cas exceptionnel), en génère un à la volée et l'affiche. Le code est **cliquable dans le chat** pour le copier directement dans le presse-papiers.

- Le code est permanent et ne change pas (seul un admin peut le réinitialiser via `reinitialiser`).
- Un code est toujours présent : il est généré automatiquement à la première connexion.
- Format : 6 caractères alphanumérique sans ambiguïté visuelle.

| Permission | Description |
|---|---|
| `parrainage.commande.code` | Voir/générer son code de parrainage |

---

#### `/parrainage utiliser <code>`

**Usage :** `/parrainage utiliser <code>`

Utilise un code de parrainage pour se faire parrainer. Le code est insensible à la casse (converti en majuscules automatiquement).

En cas de succès :
1. Le filleul reçoit un message de confirmation avec le pseudo du parrain.
2. Les **récompenses filleul** sont distribuées immédiatement.
3. Les **récompenses parrain** sont distribuées immédiatement.
4. Le parrain reçoit une notification en jeu s'il est connecté.
5. Les **paliers** éventuellement atteints sont déclenchés.
6. Le classement est rafraîchi.

Codes d'erreur possibles :

| Situation | Message affiché |
|---|---|
| Joueur déjà parrainé | `utilisation-deja-parraine` |
| Code inexistant | `utilisation-code-invalide` |
| Propre code | `utilisation-propre-code` |
| Parrainage circulaire (filleul = parrain du parrain) | `utilisation-circulaire` |
| Temps de jeu insuffisant | `utilisation-temps-jeu-insuffisant` |
| Erreur BDD | `erreur-bdd` |

Le contrôle du temps de jeu (`parrainage.temps-jeu-minimum`) est effectué sur le **thread principal** avant tout appel asynchrone, en lisant la statistique `PLAY_ONE_MINUTE` du joueur (en ticks, divisée par 20 pour obtenir des secondes).

| Permission | Description |
|---|---|
| `parrainage.commande.utiliser` | Utiliser un code de parrainage |

---

#### `/parrainage info [joueur]`

**Usage :**
- `/parrainage info` — informations de parrainage du joueur courant
- `/parrainage info <joueur>` — informations d'un autre joueur (permission supplémentaire requise)

Affiche : parrain actuel (ou « Aucun »), nombre de filleuls, code personnel, prochain palier (ou « Palier maximum atteint »). La cible peut être hors ligne.

| Permission | Description |
|---|---|
| `parrainage.commande.info.soi` | Voir ses propres infos de parrainage |
| `parrainage.commande.info.autres` | Voir les infos d'un autre joueur (défaut : `op`) |

---

#### `/parrainage top`

**Usage :** `/parrainage top`

Affiche le classement des meilleurs parrains (nombre de filleuls). La taille de la liste est configurable via `classement.taille-affichage`. Le classement est lu depuis un cache en mémoire rafraîchi périodiquement (`classement.intervalle-rafraichissement`) et après chaque parrainage ou suppression de lien.

| Permission | Description |
|---|---|
| `parrainage.commande.top` | Afficher le classement des parrains |

---

#### `/parrainage aide`

**Usage :** `/parrainage aide`

Affiche la liste des commandes joueur disponibles selon les permissions du joueur. Si le joueur possède au moins une permission admin, un pied de page invite à utiliser `/parrainage admin aide`.

| Permission | Description |
|---|---|
| `parrainage.commande.aide` | Afficher l'aide joueur |

---

## 6. Commandes admin

Ces commandes sont accessibles via `/parrainage admin <sous-commande>`.
La **console** peut exécuter toutes les commandes admin sans vérification de permission.

---

#### `/parrainage admin aide`

**Usage :** `/parrainage admin aide`

Affiche les commandes admin disponibles selon les permissions de l'exécutant. La console voit toutes les commandes.

| Permission | Description |
|---|---|
| `parrainage.admin.aide` | Afficher l'aide admin |

---

#### `/parrainage admin recharger`

**Usage :** `/parrainage admin recharger`

Recharge `config.yml` et le fichier de langue (`langue_fr.yml`) sans redémarrer le serveur. **Ne réinitialise pas** le pool de connexions HikariCP — un redémarrage complet reste nécessaire pour appliquer des modifications de base de données.

| Permission | Description |
|---|---|
| `parrainage.admin.recharger` | Recharger la configuration et les messages |

---

#### `/parrainage admin reinitialiser <joueur>`

**Usage :** `/parrainage admin reinitialiser <joueur>`

Supprime **intégralement** toutes les données de parrainage d'un joueur : code, parrain, liste de filleuls, paliers atteints. Le classement est rafraîchi après l'opération. Cette action est **irréversible**.

Deux messages sont envoyés après exécution :
1. Confirmation de succès.
2. Avertissement rappelant que toutes les données ont été effacées, avec suggestion d'utiliser `supprimerparrain` pour ne supprimer qu'un seul lien.

> **`reinitialiser` vs `supprimerparrain`** : utilisez `supprimerparrain` pour annuler un seul lien de parrainage. Utilisez `reinitialiser` uniquement pour effacer intégralement toutes les données d'un joueur.

La cible peut être hors ligne.

| Permission | Description |
|---|---|
| `parrainage.admin.reinitialiser` | Réinitialiser toutes les données d'un joueur |

---

#### `/parrainage admin definirparrain <joueur> <parrain>`

**Usage :** `/parrainage admin definirparrain <joueur> <parrain>`

Définit manuellement le parrain d'un joueur en base de données, sans distribution de récompenses ni vérification des règles anti-abus. Utile pour corriger manuellement un lien erroné.

- Le parrain doit exister en base (avoir au moins une fois rejoint le serveur).
- La cible peut être hors ligne.

| Permission | Description |
|---|---|
| `parrainage.admin.definirparrain` | Définir un parrain manuellement |

---

#### `/parrainage admin supprimerparrain <filleul>`

**Usage :** `/parrainage admin supprimerparrain <filleul>`

Supprime **uniquement** le lien de parrainage du filleul ciblé. L'opération est atomique (transaction SQL unique) :
1. Suppression de la ligne dans `parr_evenements` pour ce filleul.
2. Mise à null de `parrain_uuid` dans `parr_joueurs` pour ce filleul.
3. Décrémentation de `nb_filleuls` chez le parrain (`GREATEST(0, nb_filleuls - 1)` pour éviter les valeurs négatives).

Après la suppression, le filleul redevient eligible à utiliser un nouveau code de parrainage. Le classement est rafraîchi. Si le joueur n'a pas de parrain enregistré, un message d'erreur (`admin-joueur-pas-parraine`) est affiché sans rien modifier.

Les autres filleuls du même parrain **ne sont pas affectés**.

La cible peut être hors ligne.

| Permission | Description |
|---|---|
| `parrainage.admin.supprimerparrain` | Supprimer le lien de parrainage d'un filleul |

---

#### `/parrainage admin forcerpalier <joueur> <n>`

**Usage :** `/parrainage admin forcerpalier <joueur> <n>`

Déclenche manuellement le palier numéro `<n>` (1-indexé selon l'ordre de la liste `paliers` dans `config.yml`) pour un joueur, même si ce palier a déjà été obtenu. Utile pour re-distribuer une récompense après un correctif ou un test.

- `<n>` doit être un entier valide compris entre 1 et le nombre de paliers configurés.
- La cible doit exister en base.

| Permission | Description |
|---|---|
| `parrainage.admin.forcerpalier` | Déclencher manuellement un palier |

---

#### `/parrainage admin info <joueur>`

**Usage :** `/parrainage admin info <joueur>`

Affiche les informations complètes de débogage d'un joueur : parrain (pseudo + UUID si inconnu), nombre de filleuls, code, prochain palier. Identique à `/parrainage info <joueur>` mais accessible depuis la console.

La cible peut être hors ligne.

| Permission | Description |
|---|---|
| `parrainage.admin.info` | Consulter les informations de débogage d'un joueur |

---

## 7. Tableau des permissions

### Permissions joueur

| Permission | Défaut | Description |
|---|---|---|
| `parrainage.commande.code` | `true` | Voir/générer son code de parrainage |
| `parrainage.commande.utiliser` | `true` | Utiliser un code de parrainage |
| `parrainage.commande.info.soi` | `true` | Voir ses propres infos |
| `parrainage.commande.info.autres` | `op` | Voir les infos d'un autre joueur |
| `parrainage.commande.top` | `true` | Voir le classement |
| `parrainage.commande.aide` | `true` | Afficher l'aide joueur |

### Permissions admin

| Permission | Défaut | Description |
|---|---|---|
| `parrainage.admin.aide` | `op` | Afficher l'aide admin |
| `parrainage.admin.recharger` | `op` | Recharger la configuration |
| `parrainage.admin.reinitialiser` | `op` | Réinitialiser un joueur (toutes données) |
| `parrainage.admin.definirparrain` | `op` | Définir un parrain manuellement |
| `parrainage.admin.supprimerparrain` | `op` | Supprimer un lien de parrainage unique |
| `parrainage.admin.forcerpalier` | `op` | Forcer un palier |
| `parrainage.admin.info` | `op` | Infos de débogage d'un joueur |
| `parrainage.admin.*` | `op` | Toutes les permissions admin (enfant de toutes les `parrainage.admin.*`) |

### Suggestion de groupes de permissions

```yaml
# Joueur standard (déjà par défaut)
parrainage.commande.code
parrainage.commande.utiliser
parrainage.commande.info.soi
parrainage.commande.top
parrainage.commande.aide

# Modérateur (peut consulter les infos de n'importe quel joueur)
parrainage.commande.info.autres

# Administrateur
parrainage.admin.*
```

---

## 8. PlaceholderAPI

Ces placeholders sont disponibles si **PlaceholderAPI** est installé. En l'absence de PAPI, ils ne sont tout simplement pas enregistrés (aucune erreur).

### Placeholders joueur (propres au joueur ciblé)

| Placeholder | Type | Description |
|---|---|---|
| `%parrainage_code%` | `String` | Code de parrainage du joueur |
| `%parrainage_parrain%` | `String` | Pseudo du parrain (ou `Aucun` si aucun) |
| `%parrainage_parrain_uuid%` | `String` | UUID du parrain (ou `Aucun` si aucun) |
| `%parrainage_filleuls%` | `int` | Nombre de filleuls du joueur |
| `%parrainage_rang%` | `int` | Rang du joueur dans le classement global |
| `%parrainage_prochain_palier%` | `String` | Seuil du prochain palier (ou `MAX` si tous atteints) |

> **Note :** Le placeholder `%parrainage_filleuls%` effectue un log de débogage dans la console en version 1.0.3 (`[Parrainage][DEBUG] Placeholder filleuls demandé pour … → valeur : …`). Ce log est une séquelle du correctif de la 1.0.3 et peut être retiré de `ExpansionParrainage.java` une fois validé.

### Placeholders du classement

| Placeholder | Type | Description |
|---|---|---|
| `%parrainage_top_<n>_pseudo%` | `String` | Pseudo du joueur au rang `n` |
| `%parrainage_top_<n>_filleuls%` | `int` | Nombre de filleuls du joueur au rang `n` |

Remplacer `<n>` par un entier (ex : `%parrainage_top_1_pseudo%` = 1er du classement).

Les données du classement sont lues depuis le **cache en mémoire** (`serviceClassement`) — les placeholders `top_*` ne font jamais de requête SQL directe. Tous les autres placeholders lisent directement en base de données.

Tous les placeholders retournent `N/A` si le joueur est inconnu en base ou en cas d'erreur SQL.

---

## 9. Système de récompenses

### Récompenses de parrainage (à chaque utilisation de code réussie)

Configurées sous `recompenses-parrainage` dans `config.yml`. Déclenchées lors d'un `/parrainage utiliser <code>` réussi, dans cet ordre :

1. **Récompenses parrain** — chaque commande de `recompenses-parrainage.parrain.commandes` est exécutée avec `{joueur}` = pseudo du parrain.
2. **Récompenses filleul** — chaque commande de `recompenses-parrainage.filleul.commandes` est exécutée avec `{joueur}` = pseudo du filleul.
3. **Notification parrain** — si le parrain est en ligne, il reçoit un message indiquant le pseudo du nouveau filleul et son compteur total.
4. **Vérification et déclenchement des paliers** — voir ci-dessous.

Toutes les commandes sont exécutées depuis la console (`Bukkit.dispatchCommand`) sur le thread principal. Le parrain n'a **pas besoin d'être en ligne** pour recevoir ses récompenses.

### Paliers (récompenses bonus cumulatives)

Configurés sous `paliers` dans `config.yml`. Chaque palier est déclenché **une seule fois** quand le parrain atteint le seuil de filleuls correspondant.

- Les commandes de `recompenses.parrain.commandes` sont exécutées avec `{joueur}` = pseudo du parrain.
- Si `annonce-globale: true`, un message est diffusé à tous les joueurs en ligne.
- Un palier déjà obtenu **ne peut pas être re-déclenché automatiquement**. La commande admin `forcerpalier` contourne cette protection.
- La vérification de palier se fait immédiatement après l'incrément du compteur de filleuls.

---

## 10. Anti-abus

| Protection | Configurable | Description |
|---|---|---|
| **Auto-parrainage** | Non | Un joueur ne peut pas utiliser son propre code. |
| **Parrainage circulaire** | `bloquer-circulaire: true/false` | Le parrain d'un joueur ne peut pas être parrainé par ce même joueur. |
| **Double parrainage** | Non | Un joueur ne peut être parrainé qu'une seule fois. La source de vérité est la contrainte `UNIQUE` sur `filleul_uuid` dans `parr_evenements`. Après un `supprimerparrain`, le filleul redevient éligible. |
| **Temps de jeu minimum** | `parrainage.temps-jeu-minimum` (secondes, `0` = désactivé) | Un joueur doit avoir joué un minimum de temps avant de pouvoir utiliser un code. La valeur est lue depuis la statistique `PLAY_ONE_MINUTE` (en ticks). |
| **Unicité du code** | Non | Les codes sont générés avec `SecureRandom`. Maximum 20 tentatives avant erreur fatale ; contrainte `UNIQUE` en BDD pour la source de vérité. |

---

## 11. Schéma de base de données

Le plugin crée automatiquement les trois tables suivantes au démarrage (si elles n'existent pas déjà). Le préfixe est configurable via `base-de-donnees.prefixe-tables` (défaut : `parr_`).

Les colonnes et contraintes ci-dessous sont celles du schéma MySQL/MariaDB. En mode `local`/fallback SQLite (voir §2.1 et §4), le schéma est équivalent (mêmes tables, colonnes et clés étrangères) mais adapté à la syntaxe SQLite (`INTEGER PRIMARY KEY AUTOINCREMENT` au lieu de `INT AUTO_INCREMENT`, pas de clause `ENGINE`/`CHARSET`).

### `parr_joueurs`

| Colonne | Type | Description |
|---|---|---|
| `uuid` | `VARCHAR(36)` | UUID du joueur (clé primaire) |
| `pseudo` | `VARCHAR(16)` | Pseudo courant (mis à jour à chaque connexion) |
| `code` | `VARCHAR(8)` | Code de parrainage (unique) |
| `parrain_uuid` | `VARCHAR(36)` | UUID du parrain (NULL si aucun, FK auto-SET NULL si parrain supprimé) |
| `nb_filleuls` | `INT` | Compteur de filleuls (dénormalisé pour les performances du classement) |
| `premiere_connexion` | `TIMESTAMP` | Date de la première connexion |
| `derniere_connexion` | `TIMESTAMP` | Date de la dernière connexion (mis à jour automatiquement) |

### `parr_evenements`

| Colonne | Type | Description |
|---|---|---|
| `id` | `INT AUTO_INCREMENT` | Identifiant unique (clé primaire) |
| `parrain_uuid` | `VARCHAR(36)` | UUID du parrain (FK vers `parr_joueurs`, CASCADE DELETE) |
| `filleul_uuid` | `VARCHAR(36)` | UUID du filleul (unique — un filleul ne peut avoir qu'un parrain) |
| `date_validation` | `TIMESTAMP` | Date du parrainage |

La contrainte `UNIQUE KEY` sur `filleul_uuid` est la **source de vérité** pour savoir si un joueur est déjà parrainé.

### `parr_paliers`

| Colonne | Type | Description |
|---|---|---|
| `parrain_uuid` | `VARCHAR(36)` | UUID du parrain (FK vers `parr_joueurs`, CASCADE DELETE) |
| `seuil` | `INT` | Seuil de filleuls du palier déclenché |
| `date_obtention` | `TIMESTAMP` | Date d'obtention du palier |

Clé primaire composite `(parrain_uuid, seuil)` — garantit qu'un palier ne peut être enregistré qu'une seule fois par joueur.

---

## 12. FAQ

**Q : Puis-je utiliser MariaDB à la place de MySQL ?**
R : Oui. Le connecteur MySQL est compatible avec MariaDB. Utilisez simplement l'adresse de votre serveur MariaDB dans `config.yml`.

**Q : Le plugin fonctionne-t-il sans PlaceholderAPI ?**
R : Oui. PlaceholderAPI est une dépendance optionnelle (soft-depend). Le plugin fonctionne normalement sans lui, mais les placeholders `%parrainage_*%` ne seront pas disponibles.

**Q : Un joueur peut-il changer son code de parrainage ?**
R : Non. Le code est généré une seule fois à la première connexion et reste permanent. Seul un admin peut effacer intégralement les données d'un joueur via `/parrainage admin reinitialiser`, ce qui forcera la génération d'un nouveau code à la reconnexion.

**Q : Quelle est la différence entre `reinitialiser` et `supprimerparrain` ?**
R : `supprimerparrain <filleul>` supprime uniquement le lien entre un filleul et son parrain — le compteur du parrain est décrémenté atomiquement et le filleul peut réutiliser un code. `reinitialiser <joueur>` efface toutes les données du joueur (code, parrain, filleuls, paliers) et ne peut pas être annulé.

**Q : Que se passe-t-il si la BDD est indisponible au démarrage ?**
R : Dépend de `base-de-donnees.mode`. En `mysql` (forcé) : le plugin se désactive automatiquement et consigne l'erreur dans les logs. En `auto` (défaut) : il bascule silencieusement sur le fallback SQLite local (voir question suivante).

**Q : À quoi sert le fallback SQLite local (mode `auto`/`local`) ?**
R : À faire tourner et tester le plugin sur un serveur sans base MySQL/MariaDB provisionnée (ex : environnement de labs), sans configuration supplémentaire. En mode `auto`, si la connexion MySQL échoue au démarrage, le plugin bascule sur un fichier SQLite (`fichier-local`, dans le dossier de données du plugin) avec un schéma équivalent. Ce n'est pas prévu pour la production réseau (une seule connexion en écriture, pas de réplication) — sur un serveur en production, utilisez `mode: mysql`.

**Q : Comment recharger la configuration sans redémarrer ?**
R : Utilisez `/parrainage admin recharger`. Cela recharge `config.yml` et le fichier de langue, mais ne réinitialise pas le pool de connexions BDD. Pour des modifications de la base de données, un redémarrage complet est nécessaire.

**Q : Les commandes admin sont-elles accessibles depuis la console ?**
R : Oui. La console contourne toutes les vérifications de permission pour les sous-commandes admin.

**Q : Les récompenses sont-elles distribuées si le parrain est hors ligne ?**
R : Oui. Les récompenses sont exécutées via `dispatchCommand` depuis la console, indépendamment de la connexion du joueur. Vérifiez que vos commandes de récompenses supportent les joueurs hors ligne si nécessaire (ex : Vault / EcoEnchants le supportent, certains plugins non).

**Q : Comment fonctionne le contrôle du temps de jeu ?**
R : Le plugin lit la statistique Minecraft `PLAY_ONE_MINUTE` (en ticks) du joueur connecté, la divise par 20 pour obtenir des secondes, et compare au seuil configuré dans `parrainage.temps-jeu-minimum`. Mettre cette valeur à `0` désactive le contrôle entièrement.

**Q : Le classement se met-il à jour en temps réel ?**
R : Le classement est maintenu en **cache mémoire** et se rafraîchit automatiquement après chaque parrainage réussi, après chaque `supprimerparrain`, et après chaque `reinitialiser`. Il se rafraîchit aussi périodiquement selon `classement.intervalle-rafraichissement` (défaut : 300 secondes). Les placeholders `%parrainage_top_*%` lisent ce cache — il n'y a jamais de requête SQL directe depuis PAPI.
