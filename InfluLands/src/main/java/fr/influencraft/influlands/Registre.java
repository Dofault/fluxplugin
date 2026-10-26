package fr.influencraft.influlands;

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
 * Données propres à InfluLands, rangées par identifiant de ville Lands (ULID) dans
 * {@code data.yml} : taux du droit de place, vitrine d'adresses, recettes, ville choisie
 * par chaque joueur. Tout se lit et s'écrit sur le fil principal ; la sauvegarde
 * sérialise sur le fil principal puis écrit le fichier en tâche de fond.
 */
final class Registre {

    /** Recettes du droit de place d'une ville. */
    static final class Recette {
        double total;
        long transactions;
        /** jour (aaaa-mm-jj) -> montant perçu ce jour-là. */
        final TreeMap<String, Double> jours = new TreeMap<>();
        /** identifiant d'échoppe InfluEchoppe -> montant total reversé par elle. */
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

    private final InfluLands plugin;
    private final File fichier;
    private final Map<String, Double> taux = new ConcurrentHashMap<>();
    private final Map<String, List<String>> vitrines = new ConcurrentHashMap<>();
    private final Map<String, Recette> recettes = new ConcurrentHashMap<>();
    private final Map<UUID, String> villeChoisie = new ConcurrentHashMap<>();
    private volatile boolean modifie;

    Registre(InfluLands plugin) {
        this.plugin = plugin;
        this.fichier = new File(plugin.getDataFolder(), "data.yml");
    }

    void charger() {
        taux.clear();
        vitrines.clear();
        recettes.clear();
        villeChoisie.clear();
        if (!fichier.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(fichier);
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
        if (!modifie && fichier.exists()) return;
        modifie = false;
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Données InfluLands (taux, vitrines, recettes du droit de place).",
                "Clé = identifiant ULID de la ville Lands. Ne pas modifier serveur allumé."));
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
        String contenu = y.saveToString();
        Runnable ecrire = () -> {
            try {
                plugin.getDataFolder().mkdirs();
                File tmp = new File(fichier.getParentFile(), "data.yml.tmp");
                java.nio.file.Files.writeString(tmp.toPath(), contenu);
                java.nio.file.Files.move(tmp.toPath(), fichier.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                plugin.getLogger().warning("Sauvegarde de data.yml impossible : " + ex);
            }
        };
        if (sync) ecrire.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, ecrire);
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

    void oublierVille(String ville) {
        taux.remove(ville);
        vitrines.remove(ville);
        recettes.remove(ville);
        villeChoisie.values().removeIf(ville::equals);
        modifie = true;
    }
}
