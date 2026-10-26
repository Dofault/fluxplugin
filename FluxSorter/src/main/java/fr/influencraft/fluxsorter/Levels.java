package fr.influencraft.fluxsorter;

import org.bukkit.configuration.ConfigurationSection;

import java.util.TreeSet;

/**
 * Niveaux du coffre de tri (section "levels" de la config, "niveaux" chez nous), jamais affichés au joueur.
 * Un réglage absent d'un niveau reprend celui du niveau 1. Un niveau stocké qui n'existe
 * plus dans la config retombe sur le plus haut niveau existant qui lui est inférieur.
 */
public final class Levels {

    private final FluxSorter plugin;

    public Levels(FluxSorter plugin) {
        this.plugin = plugin;
    }

    public int radius(int level) {
        return Math.max(1, getInt(level, "radius", 50));
    }

    public long period(int level) {
        return Math.max(1, getInt(level, "interval-ticks", 40));
    }

    /** Niveau existant le plus proche par le bas (1 au minimum). */
    public int normalize(int level) {
        ConfigurationSection root = plugin.conf().getConfigurationSection("levels");
        if (root == null) return 1;
        TreeSet<Integer> known = new TreeSet<>();
        for (String k : root.getKeys(false)) {
            try {
                known.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
        }
        Integer floor = known.floor(level);
        return floor != null ? floor : (known.isEmpty() ? 1 : known.first());
    }

    private int getInt(int level, String path, int def) {
        ConfigurationSection root = plugin.conf().getConfigurationSection("levels");
        if (root == null) return def;
        int base = root.getInt("1." + path, def);
        return root.getInt(normalize(level) + "." + path, base);
    }
}
