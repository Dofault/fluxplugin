package fr.influencraft.fluxitems;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Définition d'un outil (un fichier {@code outils/<id>.yml}).
 *
 * <p>Deux modes : {@link Mode#CONTRAT} (Ordinaire, Tendance : étapes enchaînées sans délai, enchantements
 * fixes) et {@link Mode#PASS} (Virale, Phénomène : une quête bonus par jour). Le niveau de l'outil
 * (0 à {@link #niveauMax()}) est indépendant des quêtes : il monte avec l'XP d'outil gagnée à l'usage
 * ({@code points}, par ressource), les quêtes en donnent en bonus. Chaque enchantement à niveaux
 * ({@code enchantements.<id>.<niveau d'outil>: <niveau>}) monte par paliers au fil des niveaux.</p>
 */
final class Outil {

    enum Mode { CONTRAT, PASS }

    enum Type { MINER, RECOLTER, TUER, TIRER }

    record Jalon(int quantite, double recompense) {
    }

    record Quete(String id, String libelle, Type type, Set<String> cibles, int quantite, String icone) {

        boolean vise(String cible) {
            return cibles.contains(cible);
        }
    }

    record Etape(double recompense, double xp, List<Jalon> jalons, List<Quete> quetes) {

        Quete quete(String id) {
            for (Quete q : quetes) if (q.id.equals(id)) return q;
            return quetes.isEmpty() ? null : quetes.get(0);
        }
    }

    final String id;
    final String nom;
    final List<String> description;
    final String palier;
    final String famille;
    final Material materiau;
    /** Modèle 3D du pack (composant item_model), null : apparence vanilla du matériau. */
    final NamespacedKey modele;
    final int durabilite;
    final boolean reparable;
    /** Enchantements fixes (contrats). */
    final Map<String, Integer> enchantements;
    /** Enchantements à niveaux : niveau d'outil -> niveau de l'enchantement débloqué, dans l'ordre d'affichage. */
    final Map<String, TreeMap<Integer, Integer>> progressifs = new LinkedHashMap<>();
    /** XP cumulée pour atteindre chaque niveau (index 0 = niveau 1). */
    final double[] seuils;
    /** XP d'outil par unité de ressource (bloc, culture, monstre). */
    final Map<String, Double> points = new HashMap<>();
    final Mode mode;
    final boolean quotidien;
    final boolean seBrise;
    final List<Etape> etapes;

    private Outil(String id, YamlConfiguration c) {
        this.id = id;
        nom = c.getString("nom", id);
        description = c.getStringList("description");
        palier = c.getString("palier", "ordinaire");
        famille = c.getString("famille", "pioche");
        materiau = Material.matchMaterial(c.getString("materiau", "iron_pickaxe"));
        String cle = c.getString("modele");
        modele = cle == null || cle.isBlank() ? null : NamespacedKey.fromString(cle);
        durabilite = c.getInt("durabilite", 0);
        reparable = c.getBoolean("reparable", true);
        enchantements = new LinkedHashMap<>();
        ConfigurationSection e = c.getConfigurationSection("enchantements");
        if (e != null) {
            for (String k : e.getKeys(false)) {
                String ench = k.toLowerCase(Locale.ROOT);
                ConfigurationSection pr = e.getConfigurationSection(k);
                if (pr == null) {
                    enchantements.put(ench, e.getInt(k));
                    continue;
                }
                TreeMap<Integer, Integer> paliers = new TreeMap<>();
                for (String n : pr.getKeys(false)) paliers.put(Integer.parseInt(n), pr.getInt(n));
                progressifs.put(ench, paliers);
            }
        }
        seuils = c.getDoubleList("niveau.seuils").stream().mapToDouble(Double::doubleValue).toArray();
        ConfigurationSection pt = c.getConfigurationSection("points");
        if (pt != null) for (String k : pt.getKeys(false)) points.put(k.toLowerCase(Locale.ROOT), pt.getDouble(k));
        mode = "pass".equalsIgnoreCase(c.getString("mode")) ? Mode.PASS : Mode.CONTRAT;
        quotidien = c.getBoolean("quotidien", mode == Mode.PASS);
        seBrise = c.getBoolean("se-brise", false);
        etapes = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("etapes")) etapes.add(etape(m));
    }

    static Outil charger(File fichier, Logger log) {
        String id = fichier.getName().replaceFirst("\\.yml$", "");
        Outil o = new Outil(id, YamlConfiguration.loadConfiguration(fichier));
        if (o.materiau == null) {
            log.warning("Outil " + id + " : matériau inconnu, ignoré.");
            return null;
        }
        if (o.etapes.isEmpty()) {
            log.warning("Outil " + id + " : aucune étape, ignoré.");
            return null;
        }
        for (String k : java.util.stream.Stream.concat(o.enchantements.keySet().stream(), o.progressifs.keySet().stream()).toList()) {
            if (enchantement(k) == null) log.warning("Outil " + id + " : enchantement inconnu " + k + ".");
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Etape etape(Map<?, ?> m) {
        double recompense = nombre(m.get("recompense"));
        double xp = nombre(m.get("xp"));
        List<Jalon> jalons = new ArrayList<>();
        Object j = m.get("jalons");
        if (j instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof List<?> p && p.size() >= 2) jalons.add(new Jalon((int) nombre(p.get(0)), nombre(p.get(1))));
            }
        }
        List<Quete> quetes = new ArrayList<>();
        Object q = m.get("quetes");
        if (q instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> qm)) continue;
                Set<String> cibles = new HashSet<>();
                if (qm.get("cibles") instanceof List<?> cl) for (Object x : cl) cibles.add(String.valueOf(x).toLowerCase(Locale.ROOT));
                Type type = Type.valueOf(String.valueOf(((Map<String, Object>) qm).getOrDefault("type", "miner")).toUpperCase(Locale.ROOT));
                quetes.add(new Quete(String.valueOf(qm.get("id")), String.valueOf(qm.get("libelle")), type, cibles,
                        Math.max(1, (int) nombre(qm.get("quantite"))),
                        String.valueOf(((Map<String, Object>) qm).getOrDefault("icone", "paper"))));
            }
        }
        return new Etape(recompense, xp, jalons, quetes);
    }

    private static double nombre(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** Enchantement vanilla ou EcoEnchants (EcoEnchants les inscrit dans l'espace {@code minecraft}). */
    static Enchantment enchantement(String id) {
        NamespacedKey cle = id.contains(":") ? NamespacedKey.fromString(id) : NamespacedKey.minecraft(id);
        if (cle == null) return null;
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(cle);
    }

    boolean aNiveaux() {
        return seuils.length > 0;
    }

    int niveauMax() {
        return seuils.length;
    }

    int niveau(double xp) {
        int n = 0;
        while (n < seuils.length && xp >= seuils[n]) n++;
        return n;
    }

    /** XP cumulée du niveau n (0 pour le niveau 0). */
    double seuil(int n) {
        return n <= 0 ? 0 : seuils[Math.min(n, seuils.length) - 1];
    }

    /** Niveau de l'enchantement {@code id} débloqué au niveau d'outil donné (0 = pas encore). */
    int valeur(String id, int niveau) {
        TreeMap<Integer, Integer> p = progressifs.get(id);
        Map.Entry<Integer, Integer> e = p == null ? null : p.floorEntry(niveau);
        return e == null ? 0 : e.getValue();
    }

    int valeurMax(String id) {
        TreeMap<Integer, Integer> p = progressifs.get(id);
        return p == null || p.isEmpty() ? 0 : p.lastEntry().getValue();
    }

    /** Niveau d'outil qui a débloqué la valeur actuelle de l'enchantement (0 si aucune). */
    int niveauDebloque(String id, int niveau) {
        TreeMap<Integer, Integer> p = progressifs.get(id);
        Integer k = p == null ? null : p.floorKey(niveau);
        return k == null ? 0 : k;
    }

    /** Niveau d'outil du prochain palier de l'enchantement, ou -1 s'il est au maximum. */
    int niveauSuivant(String id, int niveau) {
        TreeMap<Integer, Integer> p = progressifs.get(id);
        Integer k = p == null ? null : p.higherKey(niveau);
        return k == null ? -1 : k;
    }

    /** Nœuds du chemin du menu : jalons + fin pour un contrat d'une seule étape, sinon les étapes. */
    boolean noeudsJalons() {
        return mode == Mode.CONTRAT && etapes.size() == 1 && !etapes.get(0).jalons.isEmpty();
    }

    int noeuds() {
        return noeudsJalons() ? etapes.get(0).jalons.size() + 1 : etapes.size();
    }
}
