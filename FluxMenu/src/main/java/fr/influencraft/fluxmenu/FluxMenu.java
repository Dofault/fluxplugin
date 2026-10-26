package fr.influencraft.fluxmenu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import fr.influencraft.fluxmenu.menu.Menu;
import fr.influencraft.fluxmenu.session.ChestBackgrounds;
import fr.influencraft.fluxmenu.session.InventoryHider;
import fr.influencraft.fluxmenu.session.MenuListener;
import fr.influencraft.fluxmenu.session.MenuService;
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
 * FluxMenu : menus maison d'Influencraft. Point clé par rapport à DeluxeMenus : l'inventaire
 * du joueur peut être masqué (et remplacé par des boutons) sans jamais être modifié côté
 * serveur. Voir docs/technique/fluxmenu.md.
 */
public final class FluxMenu extends JavaPlugin {

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
    private Plinko plinko;
    private Slots slots;
    private Roulette roulette;
    private int clickCooldown;
    private String prefix;

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
            plinko = Plinko.register(this);
            slots = Slots.register(this);
            roulette = Roulette.register(this);
        });
    }

    @Override
    public void onDisable() {
        if (passMissions != null) passMissions.unregister();
        if (jobInfo != null) jobInfo.unregister();
        if (collectionInfo != null) collectionInfo.unregister();
        if (plinko != null) {
            plinko.shutdown();
            if (plinko.isRegistered()) plinko.unregister();
        }
        if (slots != null) {
            slots.shutdown();
            if (slots.isRegistered()) slots.unregister();
        }
        if (roulette != null) {
            roulette.shutdown();
            if (roulette.isRegistered()) roulette.unregister();
        }
        if (menus != null) menus.closeAll();
        if (packetListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        if (backgroundListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(backgroundListener);
        if (registry != null) registry.unregisterCommands();
    }

    private void readSettings() {
        reloadConfig();
        clickCooldown = Math.max(0, getConfig().getInt("click-cooldown", 4));
        prefix = getConfig().getString("prefix", "&#FFD94D&lFluxMenu &8► ");
    }

    public MenuService menus() {
        return menus;
    }

    public MenuRegistry registry() {
        return registry;
    }

    public Plinko plinko() {
        return plinko;
    }

    public Slots slots() {
        return slots;
    }

    public Roulette roulette() {
        return roulette;
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
