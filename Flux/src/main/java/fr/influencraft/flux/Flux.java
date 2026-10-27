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
        shipOwnMenu("rewards");

        Text.init(getLogger());
        PathRenderer.init(getLogger());
        fr.influencraft.flux.menu.ListRenderer.init(getLogger());
        fr.influencraft.flux.api.command.Commands.init(this);
        fr.influencraft.flux.api.data.Database.start(this, getConfig().getConfigurationSection("database"));
        fr.influencraft.flux.providers.Providers.start(this, getConfig().getConfigurationSection("providers"));
        fr.influencraft.flux.api.util.PlayerSettings.open(this);
        fr.influencraft.flux.api.economy.Economy.registerBuiltIns(this);
        items = new ItemFactory(this);
        actions = new Actions(this);
        menus = new MenuService(this, items);
        registry = new MenuRegistry(this);
        readSettings();
        rewards = fr.influencraft.flux.rewards.RewardService.start(this, () -> messages);
        if (rewards != null) rewards.importFormerMailbox(getDataFolder().getParentFile());
        int warnings = registry.load();
        getLogger().info(registry.all().size() + " menu(s) chargé(s)" + (warnings > 0 ? ", " + warnings + " avertissement(s)" : ""));

        getServer().getPluginManager().registerEvents(new MenuListener(this, menus), this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) papiBridge = new PapiBridge(this);
        fr.influencraft.flux.api.FluxApi.bind(this);
        fr.influencraft.flux.api.FluxApi.registerPlaceholders(this, "flux", this::ownPlaceholder);
        packetListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new InventoryHider(menus), PacketListenerPriority.HIGH);
        backgrounds = new ChestBackgrounds(menus, getLogger());
        backgroundListener = PacketEvents.getAPI().getEventManager()
                .registerListener(backgrounds, PacketListenerPriority.HIGH);
        // Après le chargement de tous les plugins : EcoBattlepass peut s'activer après nous.
        getServer().getScheduler().runTask(this, () -> {
        });
    }

    @Override
    public void onDisable() {
        fr.influencraft.flux.api.FluxApi.bind(null);
        if (rewards != null) rewards.stop();
        fr.influencraft.flux.api.util.PlayerSettings.close();
        fr.influencraft.flux.api.data.Database.stop();
        if (papiBridge != null) papiBridge.shutdown();
        if (menus != null) menus.closeAll();
        if (packetListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        if (backgroundListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(backgroundListener);
        if (registry != null) registry.unregisterCommands();
    }

    private fr.influencraft.flux.api.text.Messages messages;
    private fr.influencraft.flux.rewards.RewardService rewards;

    /** Copies one of Flux's own menus when missing (translation under i18n/<language>/ when there is one). */
    private void shipOwnMenu(String id) {
        File target = new File(getDataFolder(), "menus/" + id + ".yml");
        if (target.exists()) return;
        String language = getConfig().getString("language", "en_US");
        String source = getResource("i18n/" + language + "/menus/" + id + ".yml") != null ? "i18n/" + language + "/menus/" + id + ".yml" : "menus/" + id + ".yml";
        try (java.io.InputStream in = getResource(source)) {
            if (in == null) return;
            target.getParentFile().mkdirs();
            java.nio.file.Files.copy(in, target.toPath());
        } catch (java.io.IOException e) {
            getLogger().warning("Could not write menus/" + id + ".yml: " + e.getMessage());
        }
    }

    private void readSettings() {
        reloadConfig();
        clickCooldown = Math.max(0, getConfig().getInt("click-cooldown", 4));
        prefix = getConfig().getString("prefix", "&#FFD94D&lFlux &8► ");
        Text.configureLore(getConfig().getInt("lore.max-line-length", 40), getConfig().getBoolean("lore.repeat-prefix", true));
        fr.influencraft.flux.api.util.Sounds.configure(getConfig().getConfigurationSection("sounds"));
        fr.influencraft.flux.api.discord.Discord.configure(getConfig().getConfigurationSection("discord"));
        fr.influencraft.flux.api.staff.Staff.configure(getConfig().getConfigurationSection("staff"));
        // suite-wide settings: language, currencies, dialogs
        messages = fr.influencraft.flux.api.text.Messages.load(this, getConfig().getString("language", "en_US"));
        java.util.Locale numbers = java.util.Locale.forLanguageTag(messages.language().replace('_', '-'));
        fr.influencraft.flux.api.util.Format.configure(key -> {
            String w = messages.raw("format." + key);
            return w.isEmpty() ? key : w;
        }, numbers);
        fr.influencraft.flux.api.economy.Economy.configure(getConfig().getString("currencies.main", "vault"),
                id -> getConfig().getString("currencies." + id + ".format", "%amount%"));
        fr.influencraft.flux.api.economy.Economy.configureIcons(id -> getConfig().getString("currencies." + id + ".icon", "GOLD_NUGGET"));
        java.util.Map<String, String> dialog = new java.util.HashMap<>();
        var colors = getConfig().getConfigurationSection("dialogs");
        if (colors != null) for (String k : colors.getKeys(false)) dialog.put(k, colors.getString(k, ""));
        for (String k : java.util.List.of("confirm-label", "cancel-label", "copy-label", "link-label")) {
            String v = messages.raw("dialogs." + k);
            if (!v.isEmpty()) dialog.put(k, v);
        }
        fr.influencraft.flux.api.dialog.Dialogs.configure(dialog);
    }

    /** Suite-wide language ({@code language} in Flux's config), default of every plugin of the suite. */
    @Override
    public String language() {
        return messages == null ? "en_US" : messages.language();
    }

    /**
     * {@code %flux_cmd_<plugin>_<id>%}: configured label of a command of the suite;
     * {@code %flux_list_<id>_<field>%}: state of a list (see {@link fr.influencraft.flux.api.list.Lists}).
     */
    private String ownPlaceholder(Player player, String params) {
        if (params.startsWith("rewards_") && rewards != null) return rewards.placeholder(player, params.substring(8));
        if (params.startsWith("cmd_")) {
            String rest = params.substring(4);
            int sep = rest.indexOf('_');
            return sep < 0 ? null : fr.influencraft.flux.api.command.Commands.label(rest.substring(0, sep), rest.substring(sep + 1));
        }
        if (params.startsWith("list_") && player != null) {
            String rest = params.substring(5);
            for (String field : java.util.List.of("has_next", "has_prev", "pages", "page", "count", "filter", "sort", "search")) {
                if (rest.endsWith("_" + field)) {
                    String id = rest.substring(0, rest.length() - field.length() - 1);
                    var session = menus.session(player.getUniqueId());
                    var spec = session == null ? null : session.menu().list(id);
                    return fr.influencraft.flux.api.list.Lists.placeholder(player, id, field, spec == null ? 21 : spec.pageSize());
                }
            }
        }
        return null;
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
        fr.influencraft.flux.api.list.Lists.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.command.Commands.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.economy.Economy.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.reward.Rewards.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.items.CustomItems.unregisterAll(event.getPlugin());
        fr.influencraft.flux.api.shops.ChestShops.unregisterAll(event.getPlugin());
        placeholdersChanged();
        if (hadMenus) menuFoldersChanged();
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
