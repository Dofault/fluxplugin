package fr.influencraft.fluxrewards;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * Placeholders {@code %recompenses_...%} lus par le menu (index à partir de 1, vide si absent).
 * <ul>
 *   <li>{@code jour} (jour du cycle), {@code jours_total}, {@code cycle}, {@code jour_dispo} (oui/non)</li>
 *   <li>{@code jour_<d>_etat} : recupere, disponible, manque, verrouille ;
 *       {@code jour_<d>_nom}, {@code jour_<d>_ligne_<j>}</li>
 *   <li>{@code temps_joue} (« 12h05 »), {@code temps_atteints} (progression du chemin),
 *       {@code temps_total}</li>
 *   <li>{@code temps_<i>_etat|nom|seuil|restant|ligne_<j>}</li>
 *   <li>{@code a_recuperer} : nombre de récompenses à récupérer maintenant</li>
 * </ul>
 */
public final class ExpansionRecompenses extends PlaceholderExpansion {

    private final FluxRewards plugin;
    private final ServiceRecompenses service;

    public ExpansionRecompenses(FluxRewards plugin, ServiceRecompenses service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override public @NotNull String getIdentifier() { return "recompenses"; }
    @Override public @NotNull String getAuthor() { return "Influencraft"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equals("cycle")) return String.valueOf(service.cycle());
        if (params.equals("temps_total")) return String.valueOf(service.temps().size());
        if (player == null) return "";
        DonneesJoueur d = service.donnees(player.getUniqueId());
        if (d == null) return "";
        var uuid = player.getUniqueId();

        switch (params) {
            case "jour" -> { return String.valueOf(d.jourDuCycle(service.cycle())); }
            case "jours_total" -> { return String.valueOf(d.jours); }
            case "jour_dispo" -> {
                int j = d.jourDuCycle(service.cycle());
                return j > 0 && service.etatJour(d, j) == ServiceRecompenses.Etat.DISPONIBLE ? "oui" : "non";
            }
            case "temps_joue" -> { return Duree.texte(service.tempsJoue(uuid)); }
            case "temps_atteints" -> { return String.valueOf(service.tempsAtteints(uuid)); }
            case "a_recuperer" -> { return String.valueOf(service.aRecuperer(uuid)); }
            default -> { }
        }

        String[] p = params.split("_", 3); // jour_<d>_<champ> / temps_<i>_<champ>
        if (p.length < 3) return null;
        int n = entier(p[1]);
        String champ = p[2];
        if (p[0].equals("jour")) {
            if (n < 1 || n > service.cycle()) return "";
            Palier palier = service.palierJour(n);
            if (champ.equals("etat")) return service.etatJour(d, n).name().toLowerCase(Locale.ROOT);
            if (champ.equals("nom")) return palier != null ? palier.nom() : "Jour " + n;
            if (champ.startsWith("ligne_")) return palier != null ? ligne(player, palier.description(), champ) : "";
            return "";
        }
        if (p[0].equals("temps")) {
            List<Palier> temps = service.temps();
            if (n < 1 || n > temps.size()) return "";
            Palier palier = temps.get(n - 1);
            return switch (champ) {
                case "etat" -> service.etatTemps(uuid, d, palier).name().toLowerCase(Locale.ROOT);
                case "nom" -> palier.nom();
                case "seuil" -> Duree.texte(palier.seuil());
                case "restant" -> Duree.texte(Math.max(0, palier.seuil() - service.tempsJoue(uuid)));
                default -> champ.startsWith("ligne_") ? ligne(player, palier.description(), champ) : "";
            };
        }
        return null;
    }

    private static String ligne(OfflinePlayer player, List<String> lignes, String champ) {
        int j = entier(champ.substring("ligne_".length()));
        if (j < 1 || j > lignes.size()) return "";
        String t = lignes.get(j - 1);
        return t.indexOf('%') >= 0 ? PlaceholderAPI.setPlaceholders(player, t) : t;
    }

    private static int entier(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
