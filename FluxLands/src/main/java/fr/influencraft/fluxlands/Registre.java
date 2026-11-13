package fr.influencraft.fluxlands;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Données propres à FluxLands, rangées par identifiant de ville Lands (ULID) dans
 * Rate of the market fee, showcase of addresses, income, town chosen by each player, effects of each country: in
 * Flux's database since 2026-11-13 (former {@code data.yml} read once). Read and changed on the main thread; a save
 * copies the data on the main thread then writes it in the background.
 */
final class Registre {

    /** Recettes du droit de place d'une ville. */
    static final class Recette {
        double total;
        long transactions;
        /** jour (aaaa-mm-jj) -> montant perçu ce jour-là. */
        final TreeMap<String, Double> jours = new TreeMap<>();
        /** identifiant d'échoppe -> montant total reversé par elle. */
        final Map<Long, Double> parEchoppe = new HashMap<>();

        double jour(LocalDate d) {
            return jours.getOrDefault(d.toString(), 0.0);
        }

        double depuis(int nbJours) {
            LocalDate debut = LocalDate.now().minusDays(nbJours - 1L);
            double s = 0;
            for (Map.Entry<String, Double> e : jours.tailMap(debut.toString(), true).entrySet()) s += e.getValue();
            return s;
        }
    }

    private final FluxLands plugin;
    private final File fichier;
    private final Map<String, Double> taux = new ConcurrentHashMap<>();
    private final Map<String, List<String>> vitrines = new ConcurrentHashMap<>();
    private final Map<String, Recette> recettes = new ConcurrentHashMap<>();
    private final Map<UUID, String> villeChoisie = new ConcurrentHashMap<>();
    /** Active effects chosen by each country (key: country ULID), applied by {@link CountryEffects}. */
    private final Map<String, List<String>> effetsPays = new ConcurrentHashMap<>();
    private volatile boolean modifie;

    Registre(FluxLands plugin) {
        this.plugin = plugin;
        this.fichier = new File(plugin.getDataFolder(), "data.yml");
    }

    void charger() {
        taux.clear();
        vitrines.clear();
        recettes.clear();
        villeChoisie.clear();
        YamlConfiguration y = lireBase();
        if (y == null && fichier.exists()) {
            // COMPAT 2026-11-13: data.yml of the Labs before the database - remove once Labs saved once (at go-live)
            y = YamlConfiguration.loadConfiguration(fichier);
            modifie = true;
        }
        if (y == null) return;
        ConfigurationSection villes = y.getConfigurationSection("villes");
        if (villes != null) {
            for (String id : villes.getKeys(false)) {
                ConfigurationSection v = villes.getConfigurationSection(id);
                if (v == null) continue;
                if (v.contains("taux")) taux.put(id, v.getDouble("taux"));
                List<String> vit = v.getStringList("vitrine");
                if (!vit.isEmpty()) vitrines.put(id, new ArrayList<>(vit));
                ConfigurationSection r = v.getConfigurationSection("recettes");
                if (r != null) {
                    Recette rec = new Recette();
                    rec.total = r.getDouble("total");
                    rec.transactions = r.getLong("transactions");
                    ConfigurationSection j = r.getConfigurationSection("jours");
                    if (j != null) for (String d : j.getKeys(false)) rec.jours.put(d, j.getDouble(d));
                    ConfigurationSection e = r.getConfigurationSection("echoppes");
                    if (e != null) for (String s : e.getKeys(false)) {
                        try {
                            rec.parEchoppe.put(Long.parseLong(s), e.getDouble(s));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    recettes.put(id, rec);
                }
            }
        }
        effetsPays.clear();
        ConfigurationSection pays = y.getConfigurationSection("pays");
        if (pays != null) for (String id : pays.getKeys(false)) {
            List<String> eff = pays.getStringList(id + ".effets");
            if (!eff.isEmpty()) effetsPays.put(id, new ArrayList<>(eff));
        }
        ConfigurationSection choix = y.getConfigurationSection("joueurs");
        if (choix != null) {
            for (String u : choix.getKeys(false)) {
                try {
                    villeChoisie.put(UUID.fromString(u), choix.getString(u + ".ville", ""));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    /** Sérialise sur le fil appelant, écrit en tâche de fond (ou tout de suite si {@code sync}). */
    void sauvegarder(boolean sync) {
        if (!modifie) return;
        modifie = false;
        YamlConfiguration y = new YamlConfiguration();
        java.util.Set<String> ids = new java.util.HashSet<>();
        ids.addAll(taux.keySet());
        ids.addAll(vitrines.keySet());
        ids.addAll(recettes.keySet());
        int garder = plugin.getConfig().getInt("jours-historique", 14);
        String limite = LocalDate.now().minusDays(garder).toString();
        for (String id : ids) {
            String b = "villes." + id;
            if (taux.containsKey(id)) y.set(b + ".taux", taux.get(id));
            if (vitrines.containsKey(id)) y.set(b + ".vitrine", vitrines.get(id));
            Recette r = recettes.get(id);
            if (r != null) {
                y.set(b + ".recettes.total", r.total);
                y.set(b + ".recettes.transactions", r.transactions);
                r.jours.headMap(limite, false).clear();
                for (Map.Entry<String, Double> e : r.jours.entrySet()) y.set(b + ".recettes.jours." + e.getKey(), e.getValue());
                for (Map.Entry<Long, Double> e : r.parEchoppe.entrySet()) y.set(b + ".recettes.echoppes." + e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<UUID, String> e : villeChoisie.entrySet()) y.set("joueurs." + e.getKey() + ".ville", e.getValue());
        for (Map.Entry<String, List<String>> e : effetsPays.entrySet()) y.set("pays." + e.getKey() + ".effets", e.getValue());
        Runnable ecrire = () -> ecrireBase(y);
        if (sync) ecrire.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, ecrire);
    }

    // ── Database (Flux) ─────────────────────────────────────────────────────
    // flux_lands_towns (town -> its section as YAML), flux_lands_players (player -> chosen town),
    // flux_lands_countries (country -> active effects, one per line). Small data, rewritten whole in one transaction.

    private static String table(String name) {
        return fr.influencraft.flux.api.data.Database.table("flux_lands_" + name);
    }

    void ouvrirBase() throws java.sql.SQLException {
        var d = fr.influencraft.flux.api.data.Database.dialect();
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection(); java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("towns") + " (town " + d.key(64) + " NOT NULL PRIMARY KEY, data TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("players") + " (uuid " + d.key(36) + " NOT NULL PRIMARY KEY, town " + d.key(64) + " NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("countries") + " (country " + d.key(64) + " NOT NULL PRIMARY KEY, effects TEXT NOT NULL)");
        }
    }

    /** Everything as the former file's structure, null when the database holds nothing yet. */
    private YamlConfiguration lireBase() {
        YamlConfiguration y = new YamlConfiguration();
        boolean any = false;
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection(); java.sql.Statement st = c.createStatement()) {
            try (java.sql.ResultSet rs = st.executeQuery("SELECT town, data FROM " + table("towns"))) {
                while (rs.next()) {
                    any = true;
                    YamlConfiguration v = new YamlConfiguration();
                    try {
                        v.loadFromString(rs.getString(2));
                    } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                        plugin.getLogger().warning("Town " + rs.getString(1) + " unreadable in the database: " + e.getMessage());
                        continue;
                    }
                    for (String k : v.getKeys(true)) if (!v.isConfigurationSection(k)) y.set("villes." + rs.getString(1) + "." + k, v.get(k));
                }
            }
            try (java.sql.ResultSet rs = st.executeQuery("SELECT uuid, town FROM " + table("players"))) {
                while (rs.next()) {
                    any = true;
                    y.set("joueurs." + rs.getString(1) + ".ville", rs.getString(2));
                }
            }
            try (java.sql.ResultSet rs = st.executeQuery("SELECT country, effects FROM " + table("countries"))) {
                while (rs.next()) {
                    any = true;
                    y.set("pays." + rs.getString(1) + ".effets", java.util.Arrays.asList(rs.getString(2).split("\n")));
                }
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().severe("FluxLands data not read from the database: " + e.getMessage());
            return null;
        }
        return any ? y : null;
    }

    private void ecrireBase(YamlConfiguration y) {
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM " + table("towns"));
                st.executeUpdate("DELETE FROM " + table("players"));
                st.executeUpdate("DELETE FROM " + table("countries"));
                ConfigurationSection villes = y.getConfigurationSection("villes");
                if (villes != null) try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + table("towns") + " (town, data) VALUES (?, ?)")) {
                    for (String id : villes.getKeys(false)) {
                        YamlConfiguration v = new YamlConfiguration();
                        ConfigurationSection s = villes.getConfigurationSection(id);
                        if (s == null) continue;
                        for (String k : s.getKeys(true)) if (!s.isConfigurationSection(k)) v.set(k, s.get(k));
                        ps.setString(1, id);
                        ps.setString(2, v.saveToString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                ConfigurationSection joueurs = y.getConfigurationSection("joueurs");
                if (joueurs != null) try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + table("players") + " (uuid, town) VALUES (?, ?)")) {
                    for (String u : joueurs.getKeys(false)) {
                        ps.setString(1, u);
                        ps.setString(2, joueurs.getString(u + ".ville", ""));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                ConfigurationSection pays = y.getConfigurationSection("pays");
                if (pays != null) try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + table("countries") + " (country, effects) VALUES (?, ?)")) {
                    for (String id : pays.getKeys(false)) {
                        ps.setString(1, id);
                        ps.setString(2, String.join("\n", pays.getStringList(id + ".effets")));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (java.sql.SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (java.sql.SQLException e) {
            modifie = true; // written again at the next save
            plugin.getLogger().severe("FluxLands data not saved: " + e.getMessage());
        }
    }

    // ── Droit de place ──────────────────────────────────────────────────────

    double taux(String ville) {
        double def = plugin.getConfig().getDouble("droit-de-place.taux-defaut", 5.0);
        return borner(taux.getOrDefault(ville, def));
    }

    double borner(double t) {
        double min = plugin.getConfig().getDouble("droit-de-place.taux-min", 0.0);
        double max = plugin.getConfig().getDouble("droit-de-place.taux-max", 15.0);
        return Math.max(min, Math.min(max, Math.round(t * 100.0) / 100.0));
    }

    void definirTaux(String ville, double t) {
        taux.put(ville, borner(t));
        modifie = true;
    }

    Recette recette(String ville) {
        return recettes.getOrDefault(ville, new Recette());
    }

    void encaisser(String ville, long echoppe, double montant) {
        Recette r = recettes.computeIfAbsent(ville, k -> new Recette());
        r.total += montant;
        r.transactions++;
        r.jours.merge(LocalDate.now().toString(), montant, Double::sum);
        r.parEchoppe.merge(echoppe, montant, Double::sum);
        modifie = true;
    }

    // ── Vitrine ─────────────────────────────────────────────────────────────

    List<String> vitrine(String ville) {
        return vitrines.getOrDefault(ville, List.of());
    }

    /** Ajoute ou retire l'adresse ; false si la vitrine est pleine. */
    boolean basculerVitrine(String ville, String adresse, int max) {
        List<String> l = vitrines.computeIfAbsent(ville, k -> new ArrayList<>());
        modifie = true;
        if (l.remove(adresse)) return true;
        if (l.size() >= max) return false;
        l.add(adresse);
        return true;
    }

    void nettoyerVitrine(String ville, java.util.function.Predicate<String> existe) {
        List<String> l = vitrines.get(ville);
        if (l != null && l.removeIf(a -> !existe.test(a))) modifie = true;
    }

    // ── Ville choisie (« ma ville » d'un habitant de plusieurs villes) ──────

    String villeChoisie(UUID joueur) {
        return villeChoisie.get(joueur);
    }

    void choisirVille(UUID joueur, String ville) {
        villeChoisie.put(joueur, ville);
        modifie = true;
    }

    /** Effects a country chose (effect keys, e.g. speed), empty when none. */
    List<String> effetsPays(String pays) {
        return effetsPays.getOrDefault(pays, List.of());
    }

    void definirEffetsPays(String pays, List<String> effets) {
        if (effets.isEmpty()) effetsPays.remove(pays);
        else effetsPays.put(pays, new ArrayList<>(effets));
        modifie = true;
    }

    /** Every country with chosen effects (key: country ULID). */
    Map<String, List<String>> tousEffetsPays() {
        return effetsPays;
    }

    void oublierVille(String ville) {
        taux.remove(ville);
        vitrines.remove(ville);
        recettes.remove(ville);
        villeChoisie.values().removeIf(ville::equals);
        modifie = true;
    }
}
