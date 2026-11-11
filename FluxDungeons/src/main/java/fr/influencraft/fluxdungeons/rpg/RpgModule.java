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

    private final FluxPlugin plugin;
    private final RpgConfig config;
    private RpgPlayers players;
    private CombatEngine combat;
    private Controls controls;
    private RpgItems items;
    private RpgWorld world;

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
        players.bind(world, combat);
        Skills skills = new Skills(plugin, combat, players);
        controls = new Controls(players, skills, items);
        var pm = Bukkit.getPluginManager();
        pm.registerEvents(players, plugin);
        pm.registerEvents(combat, plugin);
        pm.registerEvents(skills, plugin);
        pm.registerEvents(controls, plugin);
        pm.registerEvents(this, plugin);
        Commands.create(plugin, "classe").description("Rpg: change class (lobby).").executor((s, l, a) -> {
            if (s instanceof Player p) players.changeClass(p);
            return true;
        }).register();
        Commands.create(plugin, "rpgadmin").description("Rpg administration: reload, balance, dummy, xp.")
                .permission("fluxdungeons.admin").suggest(false).usage("/rpgadmin <reload|balance [level]|mannequin [level] [rank]|xp <player> <amount>|setspawn>")
                .executor(this::admin).register();
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

    /** Creatures without an owner: level, rank and family from their tags (dummies), level 1 normal otherwise. */
    private static CombatEngine.MobProfile profileFromTags(LivingEntity e) {
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
        return new CombatEngine.MobProfile(level, rank, family, List.of());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (controls != null) controls.forget(e.getPlayer());
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (args.length == 0) return false;
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                List<String> problems = config.load();
                if (problems.isEmpty()) {
                    items.load(config.get());
                    for (Player p : Bukkit.getOnlinePlayers()) players.recompute(p);
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
                int level = args.length > 1 ? Integer.parseInt(args[1]) : 1;
                String rank = args.length > 2 ? args[2] : "normal";
                p.getWorld().spawn(p.getLocation(), Husk.class, h -> {
                    h.setAI(false);
                    h.setSilent(true);
                    h.setRemoveWhenFarAway(false);
                    h.setPersistent(true);
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
