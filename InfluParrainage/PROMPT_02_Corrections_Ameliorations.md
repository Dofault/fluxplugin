# Prompt 02 — Corrections et Améliorations · Plugin Parrainage

> À coller dans Claude Code sur le projet existant. Traiter les points dans l'ordre indiqué.

---

## Contexte

Le plugin Paper 1.21.8 "Parrainage" est déjà implémenté. Ce prompt apporte des corrections et des nouvelles fonctionnalités. Ne pas toucher aux systèmes déjà fonctionnels (BDD, génération de codes, paliers, cache classement, PAPI) sauf indication explicite ci-dessous.

---

## Point 1 — Refonte des permissions : granularité par sous-commande

### Problème
Les permissions actuelles sont trop larges (`parrainage.utiliser` couvre toutes les commandes joueur d'un coup). Il faut une permission distincte par sous-commande pour permettre une gestion fine via LuckPerms.

### Nouvelle arborescence des permissions

```yaml
# --- Commandes joueur ---
parrainage.commande.code:
  description: Afficher et générer son code de parrainage
  default: true

parrainage.commande.utiliser:
  description: Utiliser un code de parrainage
  default: true

parrainage.commande.info.soi:
  description: Consulter ses propres informations de parrainage
  default: true

parrainage.commande.info.autres:
  description: Consulter les informations de parrainage d'un autre joueur
  default: op

parrainage.commande.top:
  description: Afficher le classement des parrains
  default: true

parrainage.commande.aide:
  description: Afficher l'aide des commandes joueur
  default: true

# --- Commandes admin (sous /parrainage admin) ---
parrainage.admin.aide:
  description: Afficher l'aide des commandes admin
  default: op

parrainage.admin.recharger:
  description: Recharger la configuration et les messages
  default: op

parrainage.admin.reinitialiser:
  description: Réinitialiser les données de parrainage d'un joueur
  default: op

parrainage.admin.definirparrain:
  description: Définir manuellement le parrain d'un joueur
  default: op

parrainage.admin.supprimerparrain:
  description: Supprimer le lien de parrainage d'un joueur
  default: op

parrainage.admin.forcerpalier:
  description: Déclencher manuellement un palier pour un joueur
  default: op

parrainage.admin.info:
  description: Consulter les informations de débogage d'un joueur
  default: op

# --- Permission globale admin (hérite de toutes les parrainage.admin.*) ---
parrainage.admin.*:
  description: Accès complet à toutes les commandes admin du parrainage
  default: op
  children:
    parrainage.admin.aide: true
    parrainage.admin.recharger: true
    parrainage.admin.reinitialiser: true
    parrainage.admin.definirparrain: true
    parrainage.admin.supprimerparrain: true
    parrainage.admin.forcerpalier: true
    parrainage.admin.info: true
```

### Mise à jour plugin.yml

Remplacer l'ancienne section `permissions:` par l'arborescence ci-dessus dans `plugin.yml`. Mettre à jour chaque vérification de permission dans `CommandeParrainage` et `CommandeParrainageAdmin` pour utiliser la permission granulaire correspondante.

---

## Point 2 — Fusion des commandes admin sous `/parrainage admin`

### Problème
La commande `/parrainageadmin` (alias `/parradmin`) est une commande séparée. Il faut la supprimer et intégrer les sous-commandes admin directement sous `/parrainage admin <sous-commande>`.

### Nouvelle structure de commande unique

```
/parrainage code
/parrainage utiliser <code>
/parrainage info [joueur]
/parrainage top
/parrainage aide
/parrainage admin aide
/parrainage admin recharger
/parrainage admin reinitialiser <joueur>
/parrainage admin definirparrain <joueur> <parrain>
/parrainage admin supprimerparrain <joueur>
/parrainage admin forcerpalier <joueur> <n>
/parrainage admin info <joueur>
```

### Implémentation

- Supprimer la classe `CommandeParrainageAdmin.java` et son enregistrement dans `PluginParrainage`.
- Dans `CommandeParrainage`, détecter si le premier argument est `"admin"` :
  - Si oui, déléguer au gestionnaire admin interne en passant `args[1]` comme sous-commande.
  - Vérifier la permission `parrainage.admin.<sous-commande>` avant d'exécuter.
  - Si l'émetteur est la console (`ConsoleCommandSender`), autoriser toutes les sous-commandes admin sans vérification de permission.
- Supprimer la déclaration de `parrainageadmin` dans `plugin.yml` (commandes et permissions).
- Les alias `/parradmin` et `/referraladmin` sont supprimés.

---

## Point 3 — Commandes d'aide `/parrainage aide` et `/parrainage admin aide`

### `/parrainage aide`

Affiche la liste des commandes joueur auxquelles l'émetteur a accès (filtrer selon ses permissions). Format MiniMessage, entête + une ligne par commande.

Ajouter dans `langue_fr.yml` :

```yaml
  # --- Aide joueur ---
  aide-entete: "{prefixe} <gold><bold>— Commandes Parrainage —</bold></gold>"
  aide-ligne: "<gray> <gold>▸</gold> <white>{commande}</white> <dark_gray>—</dark_gray> <gray>{description}</gray>"
  aide-pied: "<dark_gray>Utilisez <white>/parrainage admin aide</white> pour les commandes admin."

  # Descriptions des commandes joueur (pour l'aide)
  aide-desc-code: "Afficher votre code de parrainage"
  aide-desc-utiliser: "Utiliser un code de parrainage"
  aide-desc-info-soi: "Voir vos informations de parrainage"
  aide-desc-info-autres: "Voir les infos d'un autre joueur"
  aide-desc-top: "Afficher le classement des parrains"

  # --- Aide admin ---
  aide-admin-entete: "{prefixe} <red><bold>— Commandes Admin Parrainage —</bold></red>"
  aide-admin-ligne: "<gray> <red>▸</red> <white>{commande}</white> <dark_gray>—</dark_gray> <gray>{description}</gray>"

  # Descriptions des commandes admin (pour l'aide)
  aide-admin-desc-recharger: "Recharger la configuration"
  aide-admin-desc-reinitialiser: "Réinitialiser les données d'un joueur"
  aide-admin-desc-definirparrain: "Définir manuellement un parrain"
  aide-admin-desc-supprimerparrain: "Supprimer un lien de parrainage"
  aide-admin-desc-forcerpalier: "Déclencher un palier manuellement"
  aide-admin-desc-info: "Infos de débogage d'un joueur"
```

### Comportement

- `/parrainage aide` : affiche uniquement les commandes pour lesquelles le joueur possède la permission correspondante. Si aucune commande n'est accessible, afficher `permission-refusee`.
- `/parrainage admin aide` : accessible uniquement avec au moins une permission `parrainage.admin.*`. Affiche uniquement les sous-commandes admin que le joueur peut utiliser.
- La commande `/parrainage` sans argument affiche automatiquement `/parrainage aide`.

---

## Point 4 — Code cliquable dans le chat (copie presse-papier)

### Comportement attendu

Lorsqu'un joueur reçoit un message contenant son code (commandes `code-affichage` et `code-genere`), le code doit être rendu cliquable via l'événement MiniMessage `copy_to_clipboard`.

### Implémentation dans `GestionnaireLangue`

Dans la méthode qui construit et envoie les messages contenant `{code}`, ne pas remplacer `{code}` par une simple chaîne de texte. À la place, construire un composant MiniMessage avec :

```java
// Exemple de construction du composant cliquable
Component composantCode = Component.text(valeurCode)
    .color(NamedTextColor.GOLD)
    .decorate(TextDecoration.BOLD)
    .clickEvent(ClickEvent.copyToClipboard(valeurCode))
    .hoverEvent(HoverEvent.showText(
        GestionnaireLangue.getMessage("code-survol-copier") // voir ci-dessous
    ));
```

Ajouter dans `langue_fr.yml` :

```yaml
  # Infobulle affichée au survol du code cliquable
  code-survol-copier: "<gray>Cliquez pour copier votre code"
```

### Contrainte

Cette logique de remplacement par composant cliquable s'applique **uniquement** aux messages `code-affichage` et `code-genere`. Les autres messages affichant `{code}` (comme `info-code`) doivent aussi être rendus cliquables de la même façon. Dans tous les cas, le texte brut du code reste visible normalement — seul le comportement au clic change.

---

## Point 5 — Documentation Wiki

À la fin de l'implémentation des points 1 à 4, générer un fichier `WIKI.md` à la racine du projet avec la structure suivante. Rédiger en français, ton documentation claire et sobre.

```markdown
# Wiki — Plugin Parrainage · InfluenCraft

> Version 1.0.0 · Auteur : Entr0pik

---

## Présentation

[Description fonctionnelle du plugin en 3-4 phrases]

---

## Installation

[Prérequis, placement du jar, configuration initiale de config.yml]

---

## Configuration

[Explication de chaque clé de config.yml avec valeurs par défaut et description]

---

## Commandes

### Commandes joueur

| Commande | Description | Permission |
|---|---|---|
| `/parrainage code` | ... | `parrainage.commande.code` |
| ... | | |

### Commandes admin

| Commande | Description | Permission |
|---|---|---|
| `/parrainage admin recharger` | ... | `parrainage.admin.recharger` |
| ... | | |

---

## Permissions

[Tableau complet de toutes les permissions avec description et valeur par défaut]

---

## Placeholders PlaceholderAPI

[Tableau complet de tous les %parrainage_...% avec description et exemple de valeur]

---

## Système de paliers

[Explication du fonctionnement, format de configuration, exemple]

---

## Anti-abus

[Explication des protections en place : auto-parrainage, circulaire, temps de jeu]

---

## FAQ

[3-5 questions fréquentes anticipées]
```

Produire ce fichier avec le contenu réel (pas de placeholders vides) correspondant exactement à l'implémentation finale du plugin.

---

## Récapitulatif des fichiers modifiés

| Fichier | Nature de la modification |
|---|---|
| `plugin.yml` | Permissions granulaires, suppression de `parrainageadmin` |
| `langue_fr.yml` | Ajout des clés `aide-*`, `aide-admin-*`, `code-survol-copier` |
| `CommandeParrainage.java` | Intégration admin, logique aide, permissions granulaires |
| `CommandeParrainageAdmin.java` | **Suppression** |
| `PluginParrainage.java` | Désenregistrement de l'ancienne commande admin |
| `GestionnaireLangue.java` | Remplacement `{code}` par composant cliquable |
| `WIKI.md` | **Nouveau fichier** à la racine du projet |

**Ne pas modifier** : `GestionnaireBDD`, `DepotJoueur`, `DepotEvenement`, `DepotPalier`, `ServiceParrainage`, `ServicePalier`, `ServiceClassement`, `EcouteurConnexion`, `ExpansionParrainage`.
