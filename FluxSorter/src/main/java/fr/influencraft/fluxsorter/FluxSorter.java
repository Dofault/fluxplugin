package fr.influencraft.fluxsorter;

import me.angeschossen.lands.api.LandsIntegration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Coffre de tri : un coffre en cuivre oxydé ciré marqué par le plugin, qui envoie
 * son contenu vers les coffres en cuivre de sa zone Lands contenant déjà le même objet.
 */
public final class FluxSorter extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private LandsIntegration lands;
    private SorterItem sorterItem;
    private SorterRegistry registry;
    private SortManager sortManager;
    private SorterGolem sorterGolem;
    private Levels levels;
    private ConfigurationSection conf;

    /**
     * Clés PDC sous l'espace de noms "influtri", quel que soit le nom du plugin (renommé Sorting Chest
     * pour la version publique) : les coffres de tri déjà posés ou vendus restent reconnus.
     */
    static NamespacedKey key(String key) {
        return NamespacedKey.fromString("influtri:" + key);
    }

    /** Reprend une seule fois le dossier plugins/FluxSorter (nom d'avant Sorting Chest), sans le supprimer. */
    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "SortingChest", "InfluTri");
        Path old = getDataFolder().toPath().resolveSibling("FluxSorter");
        Path target = getDataFolder().toPath();
        if (!Files.isDirectory(old) || Files.exists(target)) return;
        try (Stream<Path> files = Files.walk(old)) {
            for (Path from : (Iterable<Path>) files::iterator) {
                Files.copy(from, target.resolve(old.relativize(from).toString()), StandardCopyOption.COPY_ATTRIBUTES);
            }
            getLogger().info("Copied configuration from plugins/FluxSorter.");
        } catch (IOException e) {
            getLogger().severe("Could not copy plugins/FluxSorter: " + e.getMessage());
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        conf = Settings.of(getConfig());
        lands = LandsIntegration.of(this);
        levels = new Levels(this);
        sorterItem = new SorterItem(this);
        registry = new SorterRegistry(this);
        sorterGolem = new SorterGolem(this);
        sortManager = new SortManager(this);

        getServer().getPluginManager().registerEvents(new SorterListener(this), this);
        getServer().getPluginManager().registerEvents(registry, this);
        getServer().getPluginManager().registerEvents(sorterGolem, this);
        registry.start();
    }

    @Override
    public void onDisable() {
        if (sortManager != null) sortManager.stopAll();
        if (registry != null) registry.stop();
    }

    public LandsIntegration lands() { return lands; }
    public SorterItem sorterItem() { return sorterItem; }
    public SorterRegistry registry() { return registry; }
    public SortManager sortManager() { return sortManager; }
    public SorterGolem sorterGolem() { return sorterGolem; }
    public Levels levels() { return levels; }
    public ConfigurationSection conf() { return conf; }

    public Component message(String key, TagResolver... resolvers) {
        return MM.deserialize(conf.getString("messages." + key, key), resolvers);
    }

    public static Component mm(String raw, TagResolver... resolvers) {
        return MM.deserialize(raw, resolvers);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            sortManager.stopAll();
            reloadConfig();
            conf = Settings.of(getConfig());
            registry.restartParticles();
            sender.sendMessage(message("reload-ok"));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(message("player-not-found"));
                return true;
            }
            int amount = 1;
            int level = 1;
            try {
                if (args.length >= 3) amount = Math.max(1, Math.min(64 * 36, Integer.parseInt(args[2])));
                if (args.length >= 4) level = levels.normalize(Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
                return false;
            }
            int left = amount;
            while (left > 0) {
                int stack = Math.min(64, left);
                left -= stack;
                target.getInventory().addItem(sorterItem.create(stack, level)).values()
                        .forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
            }
            sender.sendMessage(message("give-ok",
                    Placeholder.unparsed("amount", String.valueOf(amount)),
                    Placeholder.unparsed("player", target.getName()),
                    Placeholder.unparsed("nombre", String.valueOf(amount)),
                    Placeholder.unparsed("joueur", target.getName())));
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
