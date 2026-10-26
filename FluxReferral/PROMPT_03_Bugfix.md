# Prompt 03 — Corrections de bugs · Plugin Parrainage

> À coller dans Claude Code sur le projet existant.
> Traiter les points dans l'ordre numéroté.
> **Ne pas toucher** aux systèmes non mentionnés : GestionnaireBDD, ServiceClassement, EcouteurConnexion, CommandeParrainage (partie aide et code cliquable), ExpansionParrainage sauf Point 1.

---

## Point 1 — Placeholder `%parrainage_filleuls%` non fonctionnel

### Symptôme
Le placeholder `%parrainage_filleuls%` retourne une valeur vide ou erronée.

### Cause probable
Dans `ExpansionParrainage`, la méthode `onPlaceholderRequest` utilise probablement un identifiant de clé incorrect (faute de frappe, mauvaise casse, ou le champ `nb_filleuls` n'est pas lu depuis le bon objet).

### Correction à apporter dans `ExpansionParrainage.java`

1. Vérifier que le `switch` (ou `if/else`) sur l'identifiant du placeholder couvre bien la valeur exacte `"filleuls"` (ce qui correspond à `%parrainage_filleuls%`).
2. Vérifier que la valeur lue est bien `joueur.nbFilleuls()` (ou le getter équivalent sur le record `JoueurParrainage`).
3. Vérifier que `DepotJoueur.trouverParUUID()` est bien appelé (et non une méthode retournant un objet partiel sans le champ `nb_filleuls`).
4. Si la donnée est lue depuis le cache du classement (`ServiceClassement`), s'assurer que le cache contient bien tous les joueurs et pas uniquement ceux ayant au moins un filleul.
5. Ajouter un log de débogage temporaire en console (`[Parrainage][DEBUG] Placeholder filleuls demandé pour {uuid} → valeur : {n}`) pour confirmer le flux lors des tests. Le retirer une fois le bug confirmé corrigé.

---

## Point 2 — Suppression d'un lien de parrainage : le compteur du parrain n'est pas décrémenté

### Symptôme
Après `/parrainage admin supprimerparrain <filleul>`, le champ `nb_filleuls` du parrain reste inchangé dans `parr_joueurs`. L'info du parrain affiche toujours 1 filleul alors que le lien a été supprimé.

### Cause
La commande `supprimerparrain` supprime uniquement la ligne dans `parr_evenements` et efface `parrain_uuid` dans `parr_joueurs` pour le filleul, mais elle ne décrémente pas `nb_filleuls` chez le parrain.

### Correction à apporter

**Dans `DepotEvenement.java`**, créer (ou corriger) une méthode :

```java
/**
 * Supprime le lien de parrainage d'un filleul et décrémente le compteur du parrain.
 * Opération atomique : les deux mises à jour se font dans la même transaction SQL.
 *
 * @param filleulUUID UUID du filleul dont on supprime le lien
 * @return UUID du parrain concerné, ou Optional.empty() si aucun lien n'existait
 */
public CompletableFuture<Optional<UUID>> supprimerLienParrainage(UUID filleulUUID)
```

Cette méthode doit, dans une **transaction unique** :
1. Récupérer `parrain_uuid` depuis `parr_joueurs` pour le filleul (pour savoir quel parrain décrementer).
2. Si `parrain_uuid` est NULL → retourner `Optional.empty()`, rien à faire.
3. `DELETE FROM parr_evenements WHERE filleul_uuid = ?`
4. `UPDATE parr_joueurs SET parrain_uuid = NULL WHERE uuid = ?` (filleul)
5. `UPDATE parr_joueurs SET nb_filleuls = GREATEST(0, nb_filleuls - 1) WHERE uuid = ?` (parrain)
6. `COMMIT`

Utiliser `GREATEST(0, nb_filleuls - 1)` pour éviter de passer en négatif en cas d'incohérence.

**Dans `ServiceParrainage.java`**, mettre à jour la méthode `supprimerParrain()` pour appeler cette nouvelle méthode atomique et, une fois la suppression confirmée, invalider/rafraîchir le cache du classement pour le parrain concerné.

**Dans `CommandeParrainage.java`** (branche admin), afficher le message de succès uniquement si la suppression a effectivement trouvé un lien (réponse `Optional` non vide), sinon afficher `joueur-pas-parraine` (voir ajout dans langue_fr.yml ci-dessous).

Ajouter dans `langue_fr.yml` :
```yaml
  admin-joueur-pas-parraine: "{prefixe} <red>Ce joueur n'a pas de parrain enregistré."
```

---

## Point 3 — Récompenses manquantes : parrain, filleul, et paliers

### Symptôme
Aucune récompense n'est distribuée lors de l'activation d'un parrainage, ni côté parrain ni côté filleul. Les récompenses de paliers ne se déclenchent pas non plus.

### Nouvelle architecture des récompenses dans `config.yml`

Remplacer la section `paliers:` existante par la structure complète suivante :

```yaml
# ===================================================
# Récompenses distribuées lors de la validation
# d'un parrainage (commande /parrainage utiliser)
# ===================================================
recompenses-parrainage:
  # Récompenses pour le parrain à chaque nouveau filleul
  parrain:
    commandes:
      - "eco give {joueur} 500"
      - "minecraft:experience add {joueur} 100 points"
  # Récompenses pour le filleul lors de son premier parrainage
  filleul:
    commandes:
      - "eco give {joueur} 250"
      - "give {joueur} bread 16"

# ===================================================
# Paliers de parrainage (récompenses bonus pour le parrain)
# ===================================================
paliers:
  - filleuls: 3
    recompenses:
      # Récompenses bonus pour le parrain au palier
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
```

> `{joueur}` est remplacé par le pseudo du destinataire de la récompense (parrain ou filleul selon le bloc) au moment de l'exécution via `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)`.

### Correction à apporter dans `GestionnaireConfig.java`

Mettre à jour le chargement de la config pour lire :
- `recompenses-parrainage.parrain.commandes` → liste de commandes pour le parrain à chaque filleul
- `recompenses-parrainage.filleul.commandes` → liste de commandes pour le filleul
- `paliers[n].recompenses.parrain.commandes` → commandes bonus du palier pour le parrain

### Correction à apporter dans `ServiceParrainage.java`

Dans la méthode de validation du parrainage, après l'INSERT dans `parr_evenements` et l'incrément de `nb_filleuls`, ajouter **obligatoirement** dans cet ordre :

```
1. Exécuter recompenses-parrainage.parrain.commandes pour le parrain
   (remplacer {joueur} par pseudo du parrain)
2. Exécuter recompenses-parrainage.filleul.commandes pour le filleul
   (remplacer {joueur} par pseudo du filleul)
3. Appeler ServicePalier.verifierEtDeclencher(parrainUUID, nbFilleulsActuel)
```

Les dispatches de commandes se font toujours sur le thread principal (via `Bukkit.getScheduler().runTask(plugin, () -> ...)`), même si le reste du traitement est async.

### Correction à apporter dans `ServicePalier.java`

Vérifier que `verifierEtDeclencher` lit bien `paliers[n].recompenses.parrain.commandes` depuis `GestionnaireConfig` et exécute chaque commande avec `{joueur}` remplacé par le pseudo du parrain.

---

## Point 4 — Suppression d'un lien unique sans effacer tous les filleuls

### Symptôme
`/parrainage admin supprimerparrain <filleul>` (corrigé au Point 2) doit supprimer **uniquement** le lien du filleul ciblé. Le joueur filleul doit ensuite pouvoir utiliser un nouveau code de parrainage. La réinitialisation complète (`reinitialiser`) ne doit être utilisée que pour effacer intégralement un parrain.

### Vérifications à effectuer

**Dans `DepotJoueur.java`**, s'assurer que `supprimerLienParrainage` (Point 2) ne touche **que** la ligne du filleul ciblé dans `parr_joueurs`, et **que** la ligne de ce filleul dans `parr_evenements`. Les autres filleuls du même parrain ne doivent pas être affectés.

**Dans `ServiceParrainage.java`**, vérifier la méthode `peutUtiliserCode(UUID filleulUUID)` (ou équivalent) : elle doit contrôler la présence d'une ligne dans `parr_evenements WHERE filleul_uuid = ?`, **pas** la présence de `parrain_uuid` dans `parr_joueurs`. Ces deux colonnes doivent rester synchronisées (le Point 2 les met à jour ensemble), mais la source de vérité pour "est-ce que ce joueur est déjà parrainé" est `parr_evenements.filleul_uuid`.

Après un `supprimerparrain` correct (Point 2) :
- `parr_evenements` ne contient plus de ligne pour ce filleul → il peut réutiliser un code ✓
- `parr_joueurs.parrain_uuid` du filleul est NULL → cohérent ✓
- `parr_joueurs.nb_filleuls` du parrain est décrémenté → cohérent ✓
- Les autres filleuls du parrain sont intacts → non touchés ✓

**Dans `CommandeParrainage.java`** (branche admin `reinitialiser`), ajouter un avertissement dans le message de succès pour rappeler que cette commande efface toutes les données (parrain + filleuls + paliers + code) :

Ajouter dans `langue_fr.yml` :
```yaml
  admin-reinitialisation-avertissement: "{prefixe} <yellow>⚠ Toutes les données ont été effacées (filleuls, paliers, code). Pour supprimer un seul lien, utilisez <white>/parrainage admin supprimerparrain <filleul></white>."
```

Ce message est envoyé **après** `admin-reinitialisation-succes` lors d'un `/parrainage admin reinitialiser`.

---

## Récapitulatif des fichiers modifiés

| Fichier | Modification |
|---|---|
| `ExpansionParrainage.java` | Correction lecture `nb_filleuls` (Point 1) |
| `DepotEvenement.java` | Méthode atomique `supprimerLienParrainage` avec transaction (Point 2) |
| `ServiceParrainage.java` | Décrémentation parrain, dispatch récompenses parrain+filleul, vérif source de vérité (Points 2, 3, 4) |
| `ServicePalier.java` | Lecture `paliers[n].recompenses.parrain.commandes` (Point 3) |
| `GestionnaireConfig.java` | Chargement `recompenses-parrainage.*` et nouvelle structure `paliers[n].recompenses.parrain` (Point 3) |
| `CommandeParrainage.java` | Message `joueur-pas-parraine`, avertissement `reinitialiser` (Points 2, 4) |
| `config.yml` | Nouvelle section `recompenses-parrainage`, restructuration `paliers` (Point 3) |
| `langue_fr.yml` | Ajout `admin-joueur-pas-parraine`, `admin-reinitialisation-avertissement` (Points 2, 4) |

**Ne pas modifier** : `GestionnaireBDD`, `DepotPalier`, `ServiceClassement`, `EcouteurConnexion`, `GestionnaireLangue`, `PluginParrainage`, `JoueurParrainage`, `Palier`.
