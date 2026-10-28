package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.fluxfly.api.FlyTime;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * 2026-10-28, go-live of FluxFly: fly time of PowerFly ({@code plugins/PowerFly/database.yml}, {@code <uuid>.time}
 * in seconds, -1 = unlimited) copied to FluxFly. A player FluxFly already knows is left as he is.
 * Remove once production runs FluxFly.
 */
public final class PowerFlyToFluxFly implements Migration {

    private final FluxMigration plugin;

    public PowerFlyToFluxFly(FluxMigration plugin) {
        this.plugin = plugin;
    }

    private File source() {
        return new File(plugin.getDataFolder().getParentFile(), "PowerFly/database.yml");
    }

    @Override
    public String id() {
        return "2026-10-28_powerfly_to_fluxfly";
    }

    @Override
    public String description() {
        return "PowerFly fly time -> FluxFly";
    }

    @Override
    public boolean ready() {
        return source().isFile() && Bukkit.getPluginManager().isPluginEnabled("FluxFly") && FlyTime.available();
    }

    @Override
    public String run() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(source());
        int copied = 0, kept = 0, skipped = 0;
        for (String key : data.getKeys(false)) {
            ConfigurationSection row = data.getConfigurationSection(key);
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                skipped++;
                continue;
            }
            if (row == null || !row.isSet("time")) {
                skipped++;
                continue;
            }
            if (FlyTime.known(id)) {
                kept++;
                continue;
            }
            long time = row.getLong("time");
            FlyTime.set(id, time < 0 ? FlyTime.UNLIMITED : time);
            copied++;
        }
        return copied + " copied, " + kept + " already in FluxFly, " + skipped + " unreadable";
    }
}
