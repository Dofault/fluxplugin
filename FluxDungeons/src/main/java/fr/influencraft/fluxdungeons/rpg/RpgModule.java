package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.Simulator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.List;

/**
 * The rpg universe of FluxDungeons (docs/technique/rpg.md), built and wired in one place: configs, storage, items,
 * players, combat engine, skills, keys, lobby world and commands ({@code /rpg}, {@code /classe}, {@code /rpgadmin}).
 */
public final class RpgModule implements Listener {

    private static final String TAG_LEVEL = "fluxrpg_level:", TAG_RANK = "fluxrpg_rank:", TAG_FAMILY = "fluxrpg_family:";
    /** A training dummy: saved with the world, never dies (its life comes back full), removed by command only. */
    private static final String TAG_DUMMY = "fluxrpg_dummy";

    private final FluxPlugin plugin;
    private final RpgConfig config;
    private RpgPlayers players;
    private CombatEngine combat;
    private Controls controls;
    private RpgItems items;
    private RpgWorld world;
    private ClassPages pages;

    public RpgModule(FluxPlugin plugin) {
        this.plugin = plugin;
        this.config = new RpgConfig(plugin);
    }

    public void start() {
        world = new RpgWorld(plugin, plugin.getConfig().getString("rpg.world", "flux_rpg"));
        world.start();
        config.load();
        RpgStore store;
        try {
            store = new RpgStore(plugin.getLogger());
        } catch (SQLException e) {
            plugin.getLogger().severe("Rpg tables unavailable, the rpg is off: " + e.getMessage());
            return;
        }
        items = new RpgItems(plugin);
        if (config.get() != null) items.load(config.get());
        players = new RpgPlayers(plugin, config, store, items);
        combat = new CombatEngine(plugin, config::get, players::get);
        combat.profiles(RpgModule::profileFromTags);
        combat.immortal(e -> e.getScoreboardTags().contains(TAG_DUMMY));
        players.bind(world, combat);
        Skills skills = new Skills(plugin, combat, players);
        controls = new Controls(players, skills, items);
        var pm = Bukkit.getPluginManager();
        pm.registerEvents(players, plugin);
        pm.registerEvents(combat, plugin);
        pm.registerEvents(skills, plugin);
        pm.registerEvents(controls, plugin);
        pm.registerEvents(this, plugin);
        Commands.create(plugin, "classe").description("Rpg: the classes, change class (lobby).").executor((s, l, a) -> {
            if (!(s instanceof Player p)) return true;
            // "choisir <class>": the buttons of the class pages
            if (a.length >= 2 && a[0].equalsIgnoreCase("choisir")) players.pick(p, a[1].toLowerCase(java.util.Locale.ROOT));
            else players.changeClass(p);
            return true;
        }).register();
        if (fr.influencraft.flux.api.screen.Screens.available()) {
            pages = new ClassPages(new ClassPages.Source() {
                public String raw(String key, String... vars) {
                    return plugin.messages().raw(key, vars);
                }

                public java.io.InputStream resource(String path) {
                    return plugin.getResource(path);
                }

                public String color(String classId) {
                    return plugin.getConfig().getString("rpg.class-colors." + classId);
                }

                public String statName(String stat) {
                    return items.statName(stat);
                }

                public void warn(String message) {
                    plugin.getLogger().warning(message);
                }
            });
            players.usePages(pages);
            writePages();
        }
        fr.influencraft.flux.api.FluxApi.registerPlaceholders(plugin, "fluxrpg", this::placeholder);
        Commands.create(plugin, "personnage").aliases("perso").description("Rpg: your class, level and stats.").executor((s, l, a) -> {
            if (s instanceof Player p) players.showSheet(p);
            return true;
        }).register();
        Commands.create(plugin, "rpgadmin").description("Rpg administration: reload, balance, dummy, xp.")
                .permission("fluxdungeons.admin").suggest(false).usage("/rpgadmin <reload|balance [level]|mannequin [level] [rank]|mannequin retirer [tous]|xp <player> <amount>|setspawn>")
                .executor(this::admin).register();
    }

    private void writePages() {
        if (pages == null) return;
        RpgConfig.Set set = config.get();
        long hours = players.cooldownHours();
        // files only: off the server thread
        java.nio.file.Path root = fr.influencraft.flux.api.screen.Screens.pagesFolder();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> pages.write(root, set, hours));
    }

    /**
     * {@code %fluxrpg_<params>%}: the player's rpg character, read by the class pages. class, level_text, level, xp_text,
     * xp_pct, health, health_max, health_pct, mana, mana_max, mana_pct, has_class (yes/no), is_<class> (yes/no),
     * stat_<stat> (its total), zero_<stat> ("zero" when it is 0).
     */
    private String placeholder(Player p, String params) {
        if (p == null || players == null) return null;
        RpgPlayer r = players.get(p);
        RpgConfig.Set set = config.get();
        var m = plugin.messages();
        boolean on = r != null && set != null && r.stats() != null;
        if (params.startsWith("is_")) return on && r.heroClass().id().equals(params.substring(3)) ? "yes" : "no";
        if (params.startsWith("stat_") || params.startsWith("zero_")) {
            String stat = params.substring(5);
            var def = set == null ? null : set.stats().get(stat);
            double v = on && def != null ? r.stats().get(stat) : 0;
            if (params.startsWith("zero_")) return Math.abs(v) < 1e-9 ? "zero" : "";
            return def == null ? "" : RpgPlayers.total(def, v);
        }
        double hp = on ? combat.health(p) : 0, hpMax = on ? r.stats().get("max_health") : 0;
        double mp = on ? r.mana() : 0, mpMax = on ? r.stats().get("max_mana") : 0;
        double need = on ? set.balance().xpToNext(r.level()) : 0;
        return switch (params) {
            case "has_class" -> on ? "yes" : "no";
            case "class" -> ClassPages.plain(on ? m.raw("classes." + r.heroClass().id() + ".name") : m.raw("class-pages.no-class"));
            case "level" -> on ? String.valueOf(r.level()) : "0";
            case "level_text" -> on ? ClassPages.plain(m.raw("class-pages.level", "%level%", String.valueOf(r.level()))) : "";
            case "xp_text" -> on ? ClassPages.plain(m.raw("class-pages.sheet.xp", "%xp%", String.valueOf(Math.round(r.xp())),
                    "%need%", String.valueOf(Math.round(need)))) : "";
            case "xp_pct" -> pct(on ? r.xp() : 0, need);
            case "health" -> String.valueOf(Math.round(hp));
            case "health_max" -> String.valueOf(Math.round(hpMax));
            case "health_pct" -> pct(hp, hpMax);
            case "mana" -> String.valueOf(Math.round(mp));
            case "mana_max" -> String.valueOf(Math.round(mpMax));
            case "mana_pct" -> pct(mp, mpMax);
            default -> null;
        };
    }

    private static String pct(double v, double max) {
        return max <= 0 ? "0" : String.valueOf(Math.max(0, Math.min(100, Math.round(100 * v / max))));
    }

    public void stop() {
        if (players != null) players.stopAll();
    }

    public CombatEngine combat() {
        return combat;
    }

    public RpgPlayers players() {
        return players;
    }

    public RpgWorld world() {
        return world;
    }

    /** The balance in use (null when the rpg configs never loaded). */
    public fr.influencraft.fluxdungeons.rpg.core.Balance balance() {
        return config.get() == null ? null : config.get().balance();
    }

    /** Creatures without an owner: level, rank and family from their tags (dummies), level 1 normal otherwise. */
    public static CombatEngine.MobProfile profileFromTags(LivingEntity e) {
        int level = 1;
        String rank = "normal", family = null;
        for (String t : e.getScoreboardTags()) {
            try {
                if (t.startsWith(TAG_LEVEL)) level = Integer.parseInt(t.substring(TAG_LEVEL.length()));
            } catch (NumberFormatException ignored) {
                // a broken tag keeps level 1
            }
            if (t.startsWith(TAG_RANK)) rank = t.substring(TAG_RANK.length());
            if (t.startsWith(TAG_FAMILY)) family = t.substring(TAG_FAMILY.length());
        }
        return new CombatEngine.MobProfile(level, rank, family, List.of(), 0, 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (controls != null) controls.forget(e.getPlayer());
    }

    /** The dummy nearest to the player within 6 blocks, or every dummy of his world ({@code all}). */
    private void removeDummies(Player p, boolean all) {
        org.bukkit.entity.Entity nearest = null;
        double best = 36;
        int removed = 0;
        for (org.bukkit.entity.Entity e : p.getWorld().getEntities()) {
            if (!e.getScoreboardTags().contains(TAG_DUMMY)) continue;
            if (all) {
                e.remove();
                removed++;
                continue;
            }
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        if (!all && nearest != null) {
            nearest.remove();
            removed = 1;
        }
        plugin.messages().send(p, removed == 0 ? "rpg.dummy-none" : "rpg.dummy-removed", "%count%", String.valueOf(removed));
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (args.length == 0) return false;
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                List<String> problems = config.load();
                if (problems.isEmpty()) {
                    items.load(config.get());
                    for (Player p : Bukkit.getOnlinePlayers()) players.recompute(p);
                    writePages();
                    sender.sendMessage("§aRpg configs reloaded.");
                } else {
                    sender.sendMessage("§c" + problems.size() + " problem(s), previous configs kept:");
                    for (String p : problems) sender.sendMessage("§7- " + p);
                }
            }
            case "balance" -> {
                RpgConfig.Set set = config.get();
                if (set == null) return true;
                Simulator sim = new Simulator(set.balance(), set.stats());
                List<Integer> levels = args.length > 1 ? List.of(Integer.parseInt(args[1])) : set.balance().checkedLevels();
                for (int level : levels) {
                    sender.sendMessage("§eLevel " + level + " §7(seconds to kill: normal / elite / miniboss; seconds to die vs normal)");
                    for (ClassDef c : set.classes().values()) {
                        sender.sendMessage(String.format("§f%-8s §7%5.1f / %5.1f / %6.1f  §8| §7%5.1f", c.id(),
                                sim.averageTimeToKill(List.of(c), level, "normal", 20), sim.averageTimeToKill(List.of(c), level, "elite", 20),
                                sim.averageTimeToKill(List.of(c), level, "miniboss", 5), sim.averageTimeToDie(c, level, "normal", 20)));
                    }
                    sender.sendMessage(String.format("§fparty vs boss §7%.1f s", sim.averageTimeToKill(List.copyOf(set.classes().values()), level, "boss", 5)));
                }
            }
            case "mannequin", "dummy" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length > 1 && (args[1].equalsIgnoreCase("retirer") || args[1].equalsIgnoreCase("remove"))) {
                    removeDummies(p, args.length > 2);
                    return true;
                }
                int level = args.length > 1 ? Integer.parseInt(args[1]) : 1;
                String rank = args.length > 2 ? args[2] : "normal";
                p.getWorld().spawn(p.getLocation(), Husk.class, h -> {
                    h.setAI(false);
                    h.setSilent(true);
                    h.setRemoveWhenFarAway(false);
                    h.setPersistent(true);
                    h.addScoreboardTag(TAG_DUMMY);
                    h.setInvulnerable(false);
                    h.setCollidable(false);
                    h.addScoreboardTag(TAG_LEVEL + level);
                    h.addScoreboardTag(TAG_RANK + rank);
                    h.customName(net.kyori.adventure.text.Component.text("Mannequin " + rank + " " + level));
                    h.setCustomNameVisible(true);
                });
            }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) return true;
                if (world.spawn(p.getLocation())) plugin.messages().send(p, "rpg.spawn-set");
                else plugin.messages().send(p, "rpg.spawn-wrong-world");
            }
            case "xp" -> {
                if (args.length < 3) return false;
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not online.");
                    return true;
                }
                players.giveXp(target, Double.parseDouble(args[2]));
                sender.sendMessage("§aXp given.");
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
