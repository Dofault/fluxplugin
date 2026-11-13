package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import fr.influencraft.fluxoffering.api.Offerings;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-13: offering progress, one file per player ({@code data/<uuid>.yml} of FluxOffering, formerly InfluOffrande:
 * {@code collections.<collection>.<item>: amount}, {@code .money} for the money), copied into FluxOffering's tables.
 * A player FluxOffering already knows is left as he is. Remove once production ran it.
 */
public final class OfferingFilesToDatabase implements Migration {

    private final FluxMigration plugin;

    public OfferingFilesToDatabase(FluxMigration plugin) {
        this.plugin = plugin;
    }

    private File folder() {
        File plugins = plugin.getDataFolder().getParentFile();
        File own = new File(plugins, "FluxOffering/data");
        return own.isDirectory() ? own : new File(plugins, "InfluOffrande/data");
    }

    @Override
    public String id() {
        return "2026-11-13_offering_files_to_database";
    }

    @Override
    public String description() {
        return "FluxOffering progress files -> flux_offering_players / flux_offering_deposits";
    }

    @Override
    public boolean ready() {
        return folder().isDirectory() && Bukkit.getPluginManager().isPluginEnabled("FluxOffering") && Offerings.available();
    }

    @Override
    public String run() {
        int copied = 0, kept = 0, skipped = 0;
        File[] files = folder().listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                UUID id;
                try {
                    id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                } catch (IllegalArgumentException e) {
                    skipped++;
                    continue;
                }
                if (Offerings.known(id)) {
                    kept++;
                    continue;
                }
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                Map<String, Map<String, Integer>> items = new HashMap<>();
                Map<String, Long> money = new HashMap<>();
                ConfigurationSection cols = y.getConfigurationSection("collections");
                if (cols != null) {
                    for (String col : cols.getKeys(false)) {
                        ConfigurationSection s = cols.getConfigurationSection(col);
                        if (s == null) continue;
                        for (String key : s.getKeys(false)) {
                            if (key.equals("money")) money.put(col, s.getLong(key));
                            else items.computeIfAbsent(col, k -> new HashMap<>()).put(key, s.getInt(key));
                        }
                    }
                }
                Offerings.put(id, y.getString("player-name"), y.getString("menu-filter-mode", "ALL"), items, money);
                copied++;
            }
        }
        return copied + " player(s) copied, " + kept + " already there, " + skipped + " unreadable";
    }
}
