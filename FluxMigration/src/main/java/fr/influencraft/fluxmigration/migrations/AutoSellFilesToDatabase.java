package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import fr.influencraft.fluxshop.api.AutoSellExceptions;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * 2026-11-13: the players' own auto-sell exception lists, one file per player ({@code autosell/<uuid>.yml} of FluxShop,
 * formerly VaultSupermarket), copied into FluxShop's table {@code flux_shop_autosell}. A player who already has a
 * list there is left as he is. Remove once production ran it.
 */
public final class AutoSellFilesToDatabase implements Migration {

    private final FluxMigration plugin;

    public AutoSellFilesToDatabase(FluxMigration plugin) {
        this.plugin = plugin;
    }

    private File folder() {
        File plugins = plugin.getDataFolder().getParentFile();
        File own = new File(plugins, "FluxShop/autosell");
        return own.isDirectory() ? own : new File(plugins, "VaultSupermarket/autosell");
    }

    @Override
    public String id() {
        return "2026-11-13_autosell_files_to_database";
    }

    @Override
    public String description() {
        return "FluxShop auto-sell exception files -> flux_shop_autosell";
    }

    @Override
    public boolean ready() {
        return folder().isDirectory() && Bukkit.getPluginManager().isPluginEnabled("FluxShop") && AutoSellExceptions.available();
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
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                if (!y.isList("exceptions")) {
                    skipped++;
                    continue;
                }
                if (AutoSellExceptions.custom(id)) {
                    kept++;
                    continue;
                }
                AutoSellExceptions.set(id, y.getStringList("exceptions"));
                copied++;
            }
        }
        return copied + " list(s) copied, " + kept + " already there, " + skipped + " unreadable";
    }
}
