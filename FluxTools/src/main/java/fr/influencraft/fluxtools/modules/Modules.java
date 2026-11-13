package fr.influencraft.fluxtools.modules;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.function.BiConsumer;

/**
 * Workshop modules of FluxTools (taken over from InfluBridge): repair menu, experience bottles, anvil lock of the
 * items tagged unrepairable. Each one is switched on or off under {@code modules.<id>.enabled} in config.yml. Jobs
 * moved to FluxJobs, enchantment rules to FluxEnchant, the money action bar and the advancements key to Flux
 * (2026-11-13).
 */
public final class Modules {

    private final JavaPlugin plugin;
    private final PluginManager pm = Bukkit.getPluginManager();

    public Modules(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (on("anvil-restrictions")) {
            pm.registerEvents(new AnvilRestrictionListener(), plugin);
        }
        if (on("repair")) {
            command("repair", "influbridge.repair", new RepairMenu(plugin), null);
        }
        if (on("bottlexp")) {
            BottleXpService bottles = new BottleXpService(plugin);
            command("bottlexp", "influbridge.bottlexp", bottles, bottles);
            pm.registerEvents(bottles, plugin);
        }
    }

    private boolean on(String id) {
        return section(id).getBoolean("enabled", true);
    }

    private ConfigurationSection section(String id) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("modules." + id);
        return c != null ? c : new YamlConfiguration();
    }

    /** Registers a module command through Flux (renamable in plugins/Flux/commands.yml). */
    private void command(String id, String permission, CommandExecutor executor, org.bukkit.command.TabCompleter completer,
                         String... aliases) {
        fr.influencraft.flux.api.command.Commands.create(plugin, id).aliases(aliases).permission(permission)
                .bukkit(executor, completer).register();
    }
}
