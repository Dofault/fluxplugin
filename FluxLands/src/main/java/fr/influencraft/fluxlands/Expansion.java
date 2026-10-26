package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.land.Land;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Placeholders {@code %influlands_...%} (détail : docs/technique/influlands.md).
 * <pre>
 *   ma_&lt;champ&gt;            « ma ville » du joueur (nom, niveau, solde, taux, echoppes...)
 *   fiche_&lt;champ&gt;         ville consultée (fiche, habitants, filtre « Ville consultée »)
 *   ici_&lt;champ&gt;           ville où se tient le joueur
 *   nb_villes               nombre de villes dont le joueur est habitant
 *   monpays_&lt;champ&gt;       pays de ma ville (nom, niveau, villes, habitants, capitale, chef, tresor...)
 *   paysfiche_&lt;champ&gt;     pays consulté ; nb_pays : nombre de pays
 *   liste_&lt;n&gt;_&lt;champ&gt;     ligne n de la page affichée de la liste courante
 *   liste_total|page|pages|precedent|suivant|filtre|tri|recherche|type
 *   liste_filtre_&lt;k&gt;, liste_tri_&lt;k&gt;   lignes des choix (le choix actif est marqué)
 *   vitrine_&lt;n&gt;_&lt;champ&gt;   adresses épinglées de la ville consultée
 *   top_&lt;n&gt;_&lt;champ&gt;       échoppes de ma ville qui ont le plus reversé
 *   jour_&lt;k&gt;_montant|nom|barre   recettes des 7 derniers jours de ma ville (k=1 : il y a 6 jours)
 *   adresseville_&lt;nom&gt;     ville d'une adresse (pour les menus d'InfluPW eux-mêmes)
 *   taux_ici                droit de place là où se tient le joueur
 *   modules_echoppes|adresses  oui/non selon la présence d'InfluEchoppe / InfluPW
 * </pre>
 */
final class Expansion extends PlaceholderExpansion {

    private final FluxLands plugin;

    Expansion(FluxLands plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "influlands";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Influencraft";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer off, @NotNull String params) {
        Player p = off != null ? off.getPlayer() : null;
        if (p == null) return "";
        String s = params.toLowerCase(Locale.ROOT);

        if (s.startsWith("ma_")) return champ(plugin.champsVille(plugin.villes().maVille(p), p), s.substring(3));
        if (s.startsWith("fiche_")) {
            Land land = plugin.villes().parId(plugin.vues().etat(p).fiche);
            return champ(plugin.champsVille(land, p), s.substring(6));
        }
        if (s.startsWith("ici_")) return champ(plugin.champsVille(plugin.villes().a(p.getLocation()), p), s.substring(4));
        if (s.equals("nb_villes")) return String.valueOf(plugin.villes().duJoueur(p).size());
        if (s.startsWith("monpays_")) return champ(plugin.pays().champs(plugin.pays().monPays(p), p), s.substring(8));
        if (s.startsWith("paysfiche_")) return champ(plugin.pays().champs(plugin.pays().parId(plugin.vues().etat(p).paysFiche), p), s.substring(10));
        if (s.equals("nb_pays")) return String.valueOf(plugin.pays().tous().size());
        if (s.equals("modules_echoppes")) return Fmt.oui(plugin.aEchoppes());
        if (s.equals("modules_adresses") || s.equals("modules_warps")) return Fmt.oui(plugin.aAdresses());
        if (s.equals("taux_ici")) {
            Land land = plugin.villes().a(p.getLocation());
            return plugin.villes().percoit(land) ? Fmt.taux(plugin.registre().taux(Villes.id(land))) : "0";
        }
        if (s.startsWith("liste_")) return liste(p, s.substring(6));
        if (s.startsWith("vitrine_")) return vitrine(p, s.substring(8));
        if (s.startsWith("top_")) return top(p, s.substring(4));
        if (s.startsWith("jour_")) return jour(p, s.substring(5));
        if (s.startsWith("warpville_") || s.startsWith("adresseville_")) {
            String nom = params.substring(params.indexOf('_') + 1);
            Fiches.Adresse a = plugin.aAdresses() ? adresseParNom(nom) : null;
            return a == null || a.villeNom().isEmpty() ? "Hors ville" : a.villeNom();
        }
        if (s.startsWith("parcelle_")) return parcelle(p, s.substring(9));
        if (s.startsWith("role_")) return role(p, s.substring(5));
        if (s.startsWith("banque_")) return banque(p, s.substring(7));
        if (s.startsWith("annonce_")) return annonce(p, s.substring(8));
        if (s.equals("selection")) return plugin.gestion().selection(p);
        if (s.equals("annonces")) return String.valueOf(plugin.gestion().annonces().size());
        return null;
    }

    private Fiches.Adresse adresseParNom(String nom) {
        for (Fiches.Adresse a : plugin.adresses()) if (a.nom().equalsIgnoreCase(nom)) return a;
        return null;
    }

    private static String champ(Map<String, String> m, String champ) {
        if (m == null) return "";
        if (champ.equals("existe")) return m.getOrDefault("existe", "non");
        return m.getOrDefault(champ, "");
    }

    /** « 3_nom » -> {3, "nom"} ; null si mal formé. */
    private static Object[] indexEtChamp(String s) {
        int u = s.indexOf('_');
        if (u <= 0) return null;
        try {
            return new Object[]{Integer.parseInt(s.substring(0, u)), s.substring(u + 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String liste(Player p, String s) {
        Vues vues = plugin.vues();
        Vues.Etat e = vues.etat(p);
        List<Map<String, String>> lignes = vues.lignes(p);
        switch (s) {
            case "total": return String.valueOf(lignes.size());
            case "page": return String.valueOf(e.page + 1);
            case "pages": return String.valueOf(vues.pages(e));
            case "precedent": return Fmt.oui(e.page > 0);
            case "suivant": return Fmt.oui(e.page + 1 < vues.pages(e));
            case "filtre": return Vues.FILTRES.get(e.type).get(Math.min(e.filtre, Vues.FILTRES.get(e.type).size() - 1));
            case "tri": return Vues.TRIS.get(e.type).get(Math.min(e.tri, Vues.TRIS.get(e.type).size() - 1));
            case "recherche": return e.recherche;
            case "type": return e.type.name().toLowerCase(Locale.ROOT);
            case "budget": return Vues.BUDGETS_NOMS[e.budget];
            case "taille": return Vues.TAILLES_NOMS[e.taille];
            default: break;
        }
        if (s.startsWith("filtre_")) return choix(Vues.FILTRES.get(e.type), e.filtre, s.substring(7), e, p);
        if (s.startsWith("tri_")) return choix(Vues.TRIS.get(e.type), e.tri, s.substring(4), e, p);
        if (s.startsWith("budget_")) return choix(List.of(Vues.BUDGETS_NOMS), e.budget, s.substring(7), e, p);
        if (s.startsWith("taille_")) return choix(List.of(Vues.TAILLES_NOMS), e.taille, s.substring(7), e, p);
        Object[] ic = indexEtChamp(s);
        if (ic == null) return "";
        Map<String, String> l = vues.ligne(p, (Integer) ic[0]);
        if (l == null) return ((String) ic[1]).equals("existe") ? "non" : "";
        return champ(l, (String) ic[1]);
    }

    /** Ligne k d'une liste de choix : le choix actif en or avec une flèche, les autres en violet. */
    private String choix(List<String> options, int actif, String k, Vues.Etat e, Player p) {
        int i;
        try {
            i = Integer.parseInt(k) - 1;
        } catch (NumberFormatException ex) {
            return "";
        }
        if (i < 0 || i >= options.size()) return "";
        String nom = options.get(i);
        if (nom.equals("Ville consultée")) {
            Land f = plugin.villes().parId(e.fiche);
            if (f == null) return ""; // rien à consulter : choix masqué
            nom = "À " + f.getName();
        }
        if (nom.equals("Ma nation") && plugin.villes().maVille(p) == null) return "";
        return i == actif ? "&#FFD94D▸ &f" + nom : "&#B376E5  " + nom;
    }

    private String vitrine(Player p, String s) {
        Object[] ic = indexEtChamp(s);
        if (ic == null) return "";
        Land land = plugin.villes().parId(plugin.vues().etat(p).fiche);
        if (land == null) return ((String) ic[1]).equals("existe") ? "non" : "";
        List<String> ids = plugin.registre().vitrine(Villes.id(land));
        int n = (Integer) ic[0];
        Fiches.Adresse a = n >= 1 && n <= ids.size() ? plugin.adresse(ids.get(n - 1)) : null;
        if (a == null) return ((String) ic[1]).equals("existe") ? "non" : "";
        return champ(plugin.vues().champsWarp(a, p, true, plugin.villes().maVille(p)), (String) ic[1]);
    }

    private String top(Player p, String s) {
        Object[] ic = indexEtChamp(s);
        if (ic == null) return "";
        Land ma = plugin.villes().maVille(p);
        if (ma == null) return ((String) ic[1]).equals("existe") ? "non" : "";
        String id = Villes.id(ma);
        List<Fiches.Echoppe> dansVille = new ArrayList<>();
        for (Fiches.Echoppe e : plugin.echoppes()) if (id.equals(e.villeId())) dansVille.add(e);
        dansVille.sort((a, b) -> Double.compare(plugin.vues().droitReverse(b), plugin.vues().droitReverse(a)));
        int n = (Integer) ic[0];
        if (n < 1 || n > dansVille.size()) return ((String) ic[1]).equals("existe") ? "non" : "";
        return champ(plugin.vues().champsEchoppe(dansVille.get(n - 1)), (String) ic[1]);
    }

    /** Recettes des 7 derniers jours : k=7 aujourd'hui, k=1 il y a 6 jours ; barre = 1 à 9 relative au meilleur jour. */
    private String jour(Player p, String s) {
        Object[] ic = indexEtChamp(s);
        if (ic == null) return "";
        int k = (Integer) ic[0];
        if (k < 1 || k > 7) return "";
        Land ma = plugin.villes().maVille(p);
        Registre.Recette r = ma != null ? plugin.registre().recette(Villes.id(ma)) : new Registre.Recette();
        LocalDate d = LocalDate.now().minusDays(7L - k);
        double v = r.jour(d);
        switch ((String) ic[1]) {
            case "montant": return Fmt.argent(v);
            case "nom": return k == 7 ? "Aujourd'hui" : d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE);
            case "barre": {
                double max = 0;
                for (int j = 0; j < 7; j++) max = Math.max(max, r.jour(LocalDate.now().minusDays(j)));
                return String.valueOf(max <= 0 ? 1 : Math.max(1, (int) Math.ceil(9 * v / max)));
            }
            default: return "";
        }
    }

    // ── Gestion de ma ville : parcelle choisie, rôle choisi, banque, brouillon d'annonce ──

    private String parcelle(Player p, String champ) {
        Land ma = plugin.villes().maVille(p);
        me.angeschossen.lands.api.land.Area a = plugin.gestion().parcelle(ma, plugin.vues().etat(p).parcelle);
        if (a == null) return champ.equals("existe") ? "non" : "";
        return champ(plugin.gestion().champsParcelle(a), champ);
    }

    private String role(Player p, String champ) {
        Land ma = plugin.villes().maVille(p);
        me.angeschossen.lands.api.role.Role r = plugin.gestion().role(ma, plugin.vues().etat(p).role);
        if (r == null) return champ.equals("existe") ? "non" : "";
        return switch (champ) {
            case "existe" -> "oui";
            case "nom" -> Couleurs.legacy(r.getColorName());
            case "icone" -> Fiches.materiau(r.getIcon());
            case "type" -> Gestion.typeRole(r);
            case "priorite" -> String.valueOf(r.getPriority());
            case "modifiable" -> Fmt.oui(r.getType() != me.angeschossen.lands.api.role.enums.RoleType.OWNER);
            default -> "";
        };
    }

    private String banque(Player p, String champ) {
        Land ma = plugin.villes().maVille(p);
        Vues.Etat e = plugin.vues().etat(p);
        if (ma == null) return "";
        return switch (champ) {
            case "montant" -> Fmt.argent(e.montant);
            case "solde" -> Fmt.argent(ma.getBalance());
            case "jours" -> ma.getUpkeepCosts() > 0 ? String.valueOf((long) Math.floor(ma.getBalance() / ma.getUpkeepCosts())) : "illimité";
            case "assez" -> Fmt.oui(ma.getBalance() >= e.montant);
            case "perso" -> {
                var rsp = org.bukkit.Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                yield rsp != null ? Fmt.argent(rsp.getProvider().getBalance(p)) : "?";
            }
            default -> "";
        };
    }

    private String annonce(Player p, String champ) {
        Vues.Etat e = plugin.vues().etat(p);
        return switch (champ) {
            case "type" -> e.location ? "Location" : "Vente";
            case "location" -> Fmt.oui(e.location);
            case "prix" -> Fmt.argent(e.prix);
            case "periode" -> e.periode + " j";
            case "duree" -> e.duree + " j";
            case "parcelle" -> e.parcelle;
            default -> "";
        };
    }
}
