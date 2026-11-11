package fr.influencraft.fluxpass;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.effects.Effects;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.flux.api.value.Values;
import fr.influencraft.fluxpass.Missions.Category;
import fr.influencraft.fluxpass.Missions.Mission;
import fr.influencraft.fluxpass.Missions.Variant;
import fr.influencraft.fluxpass.PassStore.PlayerPass;
import fr.influencraft.fluxpass.Season.Track;
import fr.influencraft.fluxpass.api.Pass;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * FluxPass: the season pass. Tiers climbed with points, a reward per tier on the free and the premium track, and
 * missions (daily, weekly...) that give the points. Missions are priced by FluxValue (what they ask costs the same
 * play time) and counted by the effect engine of Flux. Replaces EcoBattlepass; the menu is a Flux menu ({@code pass}).
 *
 * <pre>
 *   /pass                                   opens the menu
 *   /pass claim &lt;tier&gt; &lt;free|premium&gt;
 *   /fluxpass points|tier|reset|complete|renew|preview|reload ...   (admin)
 * </pre>
 * Menu actions: {@code [fluxpass] category <id>}, {@code [fluxpass] claim <tier> <free|premium>}.
 */
public final class FluxPass extends FluxPlugin implements Listener {

    private final PassStore store = new PassStore(getLogger());
    private final PassService pass = new PassService(this, store);
    private final PassMenu menu = new PassMenu(this, pass);
    private String menuId = "pass";
    private int renewalTask = -1, saveTask = -1;

    @Override
    protected void enable() {
        if (!new File(getDataFolder(), "pass.yml").exists()) saveResource("pass.yml", false);
        File folder = new File(getDataFolder(), "missions");
        if (!folder.isDirectory()) saveResource("missions/daily.yml", false);
        store.open();
        settings();
        Values.onChange(() -> Bukkit.getScheduler().runTask(this, this::reloadMissions));
        Effects.whenReady(this, pass::engineReady);
        placeholders("fluxpass", menu::placeholder);
        action("fluxpass", (player, view, argument) -> {
            String[] a = argument.trim().split("\\s+");
            if (a.length == 2 && a[0].equalsIgnoreCase("category")) {
                menu.select(player, a[1]);
            } else if (a.length == 3 && a[0].equalsIgnoreCase("claim")) {
                claim(player, a[1], a[2]);
            }
            if (view != null) view.refresh();
        });
        command("pass")
                .description("Open the season pass.")
                .permission("fluxpass.use")
                .executor(this::onPass)
                .register();
        command("fluxpass")
                .description("Season pass administration.")
                .permission("fluxpass.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer(this::completeAdmin)
                .register();
        Pass.register(new Pass.Service() {
            public int tier(Player player) {
                PlayerPass pp = store.cached(player.getUniqueId());
                return pp == null ? 0 : pp.tier;
            }

            public void addPoints(Player player, int points) {
                PlayerPass pp = store.cached(player.getUniqueId());
                if (pp != null) pass.addPoints(player, pp, points);
            }
        });
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    protected void disable() {
        pass.stop();
        store.saveOnline();
        Pass.register(null);
    }

    @Override
    public void reload() {
        super.reload();
        settings();
    }

    private void settings() {
        menuId = getConfig().getString("menu.id", "pass");
        menu.perPage = Math.max(1, getConfig().getInt("menu.tiers-per-page", 7));
        menu.missionSlots = Math.max(1, getConfig().getInt("menu.mission-slots", 7));
        Season season = Season.load(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "pass.yml")), getLogger()::warning);
        pass.configure(season, Missions.load(new File(getDataFolder(), "missions"), priced(), getLogger()::warning),
                getConfig().getString("premium-permission", "fluxpass.premium"));
        menu.register();
        if (renewalTask != -1) Bukkit.getScheduler().cancelTask(renewalTask);
        if (saveTask != -1) Bukkit.getScheduler().cancelTask(saveTask);
        long check = Math.max(5, getConfig().getLong("renewal-check-seconds", 30)) * 20L;
        renewalTask = Bukkit.getScheduler().runTaskTimer(this, pass::tick, check, check).getTaskId();
        long save = Math.max(10, getConfig().getLong("save-seconds", 60)) * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(this, store::saveOnline, save, save).getTaskId();
        // players already online keep their data, but a new season starts them again
        for (Player p : Bukkit.getOnlinePlayers()) {
            store.unload(p.getUniqueId());
            join(p);
        }
    }

    /** Triggers whose amount FluxValue computes (config priced-triggers); other missions keep their own amount. */
    private java.util.Set<String> priced() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String t : getConfig().getStringList("priced-triggers")) out.add(t.toLowerCase(Locale.ROOT));
        return out;
    }

    /** FluxValue changed its values: what the missions ask is priced again. */
    private void reloadMissions() {
        pass.configure(pass.season, Missions.load(new File(getDataFolder(), "missions"), priced(), getLogger()::warning), pass.premiumPermission);
        for (Player p : Bukkit.getOnlinePlayers()) PassMenu.invalidate(p);
    }

    // ---------------------------------------------------------------- players

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        store.unload(event.getPlayer().getUniqueId());
        menu.forget(event.getPlayer());
    }

    private void join(Player player) {
        PlayerPass pp = store.load(player.getUniqueId(), pass.season.id);
        pass.addPoints(null, pp, 0);
    }

    // ---------------------------------------------------------------- claims

    private void claim(Player player, String tierText, String trackText) {
        Track track = Track.of(trackText);
        int tier;
        try {
            tier = Integer.parseInt(tierText);
        } catch (NumberFormatException e) {
            tier = -1;
        }
        if (track == null || tier < 1) {
            messages().send(player, "claim-none");
            return;
        }
        switch (pass.claim(player, tier, track)) {
            case DONE -> {
                messages().send(player, "claimed", "%tier%", String.valueOf(tier));
                Sounds.cue(player, "complete");
            }
            case NOT_REACHED -> fail(player, "claim-not-reached", tier);
            case ALREADY -> fail(player, "claim-already", tier);
            case PREMIUM -> fail(player, "claim-premium", tier);
            case NO_REWARD -> fail(player, "claim-none", tier);
        }
    }

    private void fail(Player player, String key, int tier) {
        messages().send(player, key, "%tier%", String.valueOf(tier));
        Sounds.cue(player, "error");
    }

    // ---------------------------------------------------------------- commands

    private boolean onPass(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("claim")) {
            claim(player, args[1], args[2]);
            return true;
        }
        if (!FluxApi.open(player, menuId)) messages().send(player, "no-menu");
        return true;
    }

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            messages().send(sender, "admin-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reload();
                messages().send(sender, "reloaded");
            }
            case "points", "tier", "reset" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                PlayerPass pp = target == null ? null : store.cached(target.getUniqueId());
                if (pp == null) {
                    messages().send(sender, "unknown-player");
                    return true;
                }
                if (args[0].equalsIgnoreCase("reset")) {
                    pass.setTier(pp, 0);
                    store.clearClaims(pp, pass.season.id);
                    pp.progress.clear();
                    pass.addPoints(null, pp, 0);
                    messages().send(sender, "admin-reset", "%player%", target.getName());
                } else {
                    int n = args.length > 2 ? parse(args[2]) : Integer.MIN_VALUE;
                    if (n == Integer.MIN_VALUE) {
                        messages().send(sender, "admin-usage");
                        return true;
                    }
                    if (args[0].equalsIgnoreCase("tier")) pass.setTier(pp, n);
                    else pass.addPoints(target, pp, n);
                    messages().send(sender, "admin-state", "%player%", target.getName(), "%tier%", String.valueOf(pp.tier),
                            "%points%", String.valueOf(pp.points));
                }
                PassMenu.invalidate(target);
            }
            case "complete" -> {
                Player target = args.length > 3 ? Bukkit.getPlayerExact(args[1]) : null;
                PlayerPass pp = target == null ? null : store.cached(target.getUniqueId());
                Category c = args.length > 3 ? pass.missions.category(args[2]) : null;
                Mission m = c == null ? null : c.mission(args[3]);
                if (pp == null || m == null) {
                    messages().send(sender, "admin-usage");
                    return true;
                }
                PassStore.Progress pr = pass.progress(pp, c, m);
                if (!pr.done) pass.complete(target, pp, c, m, pr);
                messages().send(sender, "admin-done");
            }
            case "renew" -> {
                Category c = args.length > 1 ? pass.missions.category(args[1]) : null;
                if (c == null) {
                    messages().send(sender, "admin-usage");
                    return true;
                }
                pass.redraw(c);
                for (Player p : Bukkit.getOnlinePlayers()) PassMenu.invalidate(p);
                messages().send(sender, "admin-done");
            }
            case "preview" -> preview(sender, args.length > 1 ? args[1] : null);
            default -> messages().send(sender, "admin-usage");
        }
        return true;
    }

    /** What every variant asks and what prices it (FluxValue), to check the balance of a category. */
    private void preview(CommandSender sender, String only) {
        for (Category c : pass.missions.all()) {
            if (only != null && !c.id().equalsIgnoreCase(only)) continue;
            sender.sendMessage("§e" + c.id() + " §7(" + c.missions().size() + " missions)");
            for (Mission m : c.missions()) {
                Variant drawn = pass.drawn(c, m);
                for (Variant v : m.variants()) {
                    sender.sendMessage("§7 " + (v == drawn ? "§a▶ " : "  ") + "§f" + m.id() + "/" + v.id() + " §7x§f"
                            + v.amount() + " §8(" + v.pricedBy() + ")");
                }
            }
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private List<String> completeAdmin(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Stream.of("points", "tier", "reset", "complete", "renew", "preview", "reload")
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("points", "tier", "reset", "complete").contains(sub)) return Players.complete(args[1]);
        List<String> categories = new ArrayList<>();
        for (Category c : pass.missions.all()) categories.add(c.id());
        if ((args.length == 2 && (sub.equals("renew") || sub.equals("preview"))) || (args.length == 3 && sub.equals("complete"))) {
            return categories.stream().filter(s -> s.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 4 && sub.equals("complete")) {
            Category c = pass.missions.category(args[2]);
            return c == null ? List.of() : c.missions().stream().map(Mission::id).toList();
        }
        return List.of();
    }
}
