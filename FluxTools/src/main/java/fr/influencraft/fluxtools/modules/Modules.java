package fr.influencraft.fluxtools.modules;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.function.BiConsumer;

/**
 * Optional utility modules of FluxTools (taken over from InfluBridge), each one switched on or off
 * under {@code modules.<id>.enabled} in config.yml. A module whose plugin is missing (EcoJobs, CMI,
 * ProtocolLib...) turns itself off with a console warning instead of failing the whole plugin.
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
        if (on("mending")) {
            pm.registerEvents(new MendingRestrictionListener(plugin.getLogger(), section("mending").getBoolean("log", false)),
                    plugin);
        }
        if (on("repair")) {
            command("repair", "influbridge.repair", new RepairMenu(plugin), null);
        }
        if (on("bottlexp")) {
            BottleXpService bottles = new BottleXpService(plugin);
            command("bottlexp", "influbridge.bottlexp", bottles, bottles);
            pm.registerEvents(bottles, plugin);
        }
        if (on("enchant-splitter")) {
            EnchantSplitter splitter = new EnchantSplitter(plugin);
            command("enchantsplit", null, splitter, splitter, "split", "separer");
            pm.registerEvents(splitter, plugin);
        }
        if (on("advancement-redirect") && requires("advancement-redirect", "ProtocolLib")) {
            new AdvancementRedirectListener(plugin, section("advancement-redirect").getString("command", "menu")).register();
        }
        if (on("jobs") && requires("jobs", "EcoJobs")) {
            JobChoiceCommand jobs = new JobChoiceCommand();
            command("job", null, jobs, jobs, "metier");
            pm.registerEvents(new NativeJobsMenuRedirectListener(plugin), plugin);
        }
        if (on("job-xp-bossbar") && requires("job-xp-bossbar", "EcoJobs")) {
            ConfigurationSection c = section("job-xp-bossbar");
            JobXpBossBarListener bar = new JobXpBossBarListener(plugin, c.getLong("hide-after-seconds", 4),
                    c.getString("color", "GREEN"), c.getString("overlay", "NOTCHED_20"));
            pm.registerEvents(bar, plugin);
            toggleCommand("jobxpbar", new String[0], true, bar::isEnabled, bar::setEnabled,
                    "&#FFD94D&lRéglages &8► &#B376E5Notif. XP métier &#2ECF7Bactivée.",
                    "&#FFD94D&lRéglages &8► &#B376E5Notif. XP métier &#FF5C5Cdésactivée.");
        }
        if (on("money-gain-actionbar") && requires("money-gain-actionbar", "CMI")) {
            enableMoneyGainActionBar();
        }
        if (on("vault-late-binding") && requires("vault-late-binding", "eco")) {
            // ServerLoadEvent fires after every plugin's onEnable, so after CMIEInjector hooked Vault
            pm.registerEvents(new Listener() {
                @EventHandler
                public void onServerLoad(ServerLoadEvent event) {
                    LateVaultEconomyBinder.bindIfNeeded();
                }
            }, plugin);
        }
        fr.influencraft.flux.api.FluxApi.registerPlaceholders(plugin, "fluxtools",
                new ToolsExpansion(pm.isPluginEnabled("EcoJobs"))::request);
    }

    private void enableMoneyGainActionBar() {
        ConfigurationSection c = section("money-gain-actionbar");
        // DurabilityNotifier shows its own tool-break alerts in the action bar on the same trigger (mining):
        // read its config instead of duplicating its threshold.
        boolean durabilityGuard = false;
        int durabilityThreshold = 30;
        if (pm.isPluginEnabled("DurabilityNotifier")) {
            File file = new File(plugin.getDataFolder().getParentFile(), "DurabilityNotifier/config.yml");
            if (file.isFile()) {
                YamlConfiguration other = YamlConfiguration.loadConfiguration(file);
                durabilityGuard = other.getBoolean("notifications.types.action-bar.enabled", true);
                durabilityThreshold = other.getInt("notifications.required-durability", 30);
            }
        }
        MoneyGainActionBarListener bar = new MoneyGainActionBarListener(plugin, c.getDouble("hide-after-seconds", 1.5),
                c.getString("color", "&#B376E5"), durabilityGuard, durabilityThreshold);
        pm.registerEvents(bar, plugin);
        toggleCommand("moneynotif", new String[]{"bitblocknotif"}, false, null, bar::setEnabled,
                "&#FFD94D&lRéglages &8► &#B376E5Notif. gain %img_bitblock% &#2ECF7Bactivées.",
                "&#FFD94D&lRéglages &8► &#B376E5Notif. gain %img_bitblock% &#FF5C5Cdésactivées.");
    }

    /**
     * Player toggle used by the settings menu buttons: "on" / "off", plus "toggle" (or no argument) when
     * {@code allowToggle} is set.
     */
    private void toggleCommand(String name, String[] aliases, boolean allowToggle, java.util.function.Predicate<Player> current,
                               BiConsumer<Player, Boolean> set, String onMessage, String offMessage) {
        command(name, null, (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Cette commande est réservée aux joueurs.");
                return true;
            }
            boolean state;
            if (allowToggle && (args.length == 0 || args[0].equalsIgnoreCase("toggle"))) {
                state = !current.test(player);
            } else if (args.length == 1 && args[0].equalsIgnoreCase("on")) {
                state = true;
            } else if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
                state = false;
            } else {
                return false;
            }
            set.accept(player, state);
            player.sendMessage(fr.influencraft.flux.api.text.Texts.parse(state ? onMessage : offMessage, player));
            return true;
        }, null, aliases);
    }

    private boolean on(String id) {
        return section(id).getBoolean("enabled", true);
    }

    private ConfigurationSection section(String id) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("modules." + id);
        return c != null ? c : new YamlConfiguration();
    }

    private boolean requires(String id, String dependency) {
        if (pm.isPluginEnabled(dependency)) return true;
        plugin.getLogger().warning("Module " + id + " disabled: " + dependency + " is not installed.");
        return false;
    }

    /** Registers a module command through Flux (renamable in plugins/Flux/commands.yml). */
    private void command(String id, String permission, CommandExecutor executor, org.bukkit.command.TabCompleter completer,
                         String... aliases) {
        fr.influencraft.flux.api.command.Commands.create(plugin, id).aliases(aliases).permission(permission)
                .bukkit(executor, completer).register();
    }
}
