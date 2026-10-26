package fr.influencraft.flux;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import fr.influencraft.flux.menu.Menu;
import fr.influencraft.flux.session.ChestBackgrounds;
import fr.influencraft.flux.session.InventoryHider;
import fr.influencraft.flux.session.MenuListener;
import fr.influencraft.flux.session.MenuService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Flux : menus maison d'Influencraft. Point clé par rapport à DeluxeMenus : l'inventaire
 * du joueur peut être masqué (et remplacé par des boutons) sans jamais être modifié côté
 * serveur. Voir docs/technique/fluxmenu.md.
 */
public final class Flux extends JavaPlugin implements fr.influencraft.flux.api.FluxApi.Backend, org.bukkit.event.Listener {

    private ItemFactory items;
    private MenuRegistry registry;
    private MenuService menus;
    private Actions actions;
    private PacketListenerCommon packetListener;
    private PacketListenerCommon backgroundListener;
    private ChestBackgrounds backgrounds;
    private PassMissions passMissions;
    private JobInfo jobInfo;
    private CollectionInfo collectionInfo;
    private PapiBridge papiBridge;
    private int clickCooldown;
    private String prefix;

    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "FluxMenu");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File menusDir = new File(getDataFolder(), "menus");
        if (!menusDir.isDirectory()) saveResource("menus/main.yml", false);

        Text.init(getLogger());
        PathRenderer.init(getLogger());
        Dialogs.init(getLogger());
        items = new ItemFactory(this);
        actions = new Actions(this);
        menus = new MenuService(this, items);
        registry = new MenuRegistry(this);
        readSettings();
        int warnings = registry.load();
        getLogger().info(registry.all().size() + " menu(s) chargé(s)" + (warnings > 0 ? ", " + warnings + " avertissement(s)" : ""));

        getServer().getPluginManager().registerEvents(new MenuListener(this, menus), this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) papiBridge = new PapiBridge(this);
        fr.influencraft.flux.api.FluxApi.bind(this);
        packetListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new InventoryHider(menus), PacketListenerPriority.HIGH);
        backgrounds = new ChestBackgrounds(menus, getLogger());
        backgroundListener = PacketEvents.getAPI().getEventManager()
                .registerListener(backgrounds, PacketListenerPriority.HIGH);
        // Après le chargement de tous les plugins : EcoBattlepass peut s'activer après nous.
        getServer().getScheduler().runTask(this, () -> {
            passMissions = PassMissions.register(this);
            jobInfo = JobInfo.register(this);
            collectionInfo = CollectionInfo.register(this);
        });
    }

    @Override
    public void onDisable() {
        if (passMissions != null) passMissions.unregister();
        if (jobInfo != null) jobInfo.unregister();
        if (collectionInfo != null) collectionInfo.unregister();
        fr.influencraft.flux.api.FluxApi.bind(null);
        if (papiBridge != null) papiBridge.shutdown();
        if (menus != null) menus.closeAll();
        if (packetListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        if (backgroundListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(backgroundListener);
        if (registry != null) registry.unregisterCommands();
    }

    private void readSettings() {
        reloadConfig();
        clickCooldown = Math.max(0, getConfig().getInt("click-cooldown", 4));
        prefix = getConfig().getString("prefix", "&#FFD94D&lFlux &8► ");
    }

    public MenuService menus() {
        return menus;
    }

    public MenuRegistry registry() {
        return registry;
    }

    // ---------------------------------------------------------------- FluxApi backend

    @Override
    public boolean open(Player player, String menuId) {
        Menu menu = registry.get(menuId);
        if (menu == null) {
            getLogger().warning("Menu inconnu « " + menuId + " » (FluxApi.open)");
            return false;
        }
        return menus.open(player, menu);
    }

    @Override
    public fr.influencraft.flux.api.MenuView view(Player player) {
        var session = menus.session(player.getUniqueId());
        return session == null || !session.isOpen() ? null : new FluxView(this, player, session);
    }

    @Override
    public boolean hasMenu(String menuId) {
        return registry.get(menuId) != null;
    }

    @Override
    public void menuFoldersChanged() {
        int warnings = registry.load();
        getLogger().info(registry.all().size() + " menu(s) chargé(s) après l'ajout d'un dossier de menus"
                + (warnings > 0 ? ", " + warnings + " avertissement(s)" : ""));
    }

    @Override
    public void placeholdersChanged() {
        if (papiBridge != null) papiBridge.sync();
    }

    /** A plugin of the suite stops: its actions, placeholders, item sources and menus go with it. */
    @org.bukkit.event.EventHandler
    public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent event) {
        if (event.getPlugin() == this) return;
        boolean hadMenus = fr.influencraft.flux.api.FluxApi.unregisterAll(event.getPlugin());
        placeholdersChanged();
        if (hadMenus) menuFoldersChanged();
    }

    public CollectionInfo collectionInfo() {
        return collectionInfo;
    }

    public JobInfo jobInfo() {
        return jobInfo;
    }

    public Actions actions() {
        return actions;
    }

    public int clickCooldown() {
        return clickCooldown;
    }

    public String prefix() {
        return prefix;
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(Text.line(sender instanceof Player p ? p : null, prefix + text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fluxmenu.admin")) {
            msg(sender, "&#FF5C5CPermission manquante.");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> {
                if (args.length < 2) {
                    msg(sender, "&#B376E5/" + label + " open <menu> [joueur]");
                    return true;
                }
                Menu menu = registry.get(args[1]);
                if (menu == null) {
                    msg(sender, "&#FF5C5CMenu inconnu : &f" + args[1]);
                    return true;
                }
                Player target;
                if (args.length >= 3) {
                    target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        msg(sender, "&#FF5C5CJoueur introuvable : &f" + args[2]);
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    msg(sender, "&#FF5C5CPrécise un joueur depuis la console.");
                    return true;
                }
                menus.open(target, menu);
            }
            case "reload" -> {
                menus.closeAll();
                readSettings();
                items.clearCache();
                Text.resetHooks();
                backgrounds.reset();
                PathRenderer.reset();
                if (jobInfo != null) jobInfo.clear();
                int warnings = registry.load();
                msg(sender, "&#2ECF7B" + registry.all().size() + " menu(s) rechargé(s)"
                        + (warnings > 0 ? " &#FF5C5C(" + warnings + " avertissement(s), voir la console)" : "") + ".");
            }
            case "list" -> {
                for (Menu m : registry.all()) {
                    msg(sender, "&#FFD94D" + m.id() + " &8- &#B376E5" + m.rows() + " rangées, inventaire "
                            + m.inventoryMode().name().toLowerCase(Locale.ROOT) + ", " + m.buttons().size() + " boutons"
                            + (m.commands().isEmpty() ? "" : ", /" + String.join(" /", m.commands())));
                }
            }
            default -> msg(sender, "&#B376E5/" + label + " open <menu> [joueur] &8| &#B376E5reload &8| &#B376E5list");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("fluxmenu.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("open", "reload", "list")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            for (Menu m : registry.all()) if (m.id().startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(m.id());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("open")) {
            return null; // noms des joueurs
        }
        return out;
    }
}
