package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.reward.PendingReward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * FluxRewards: the one place where players get every reward waiting for them, plus the daily login and playtime
 * rewards.
 *
 * <ul>
 *   <li><b>/rewards</b> claims everything that fits in the inventory, from every reward source of the suite (the
 *   mailbox, login and playtime, and the sources other plugins register: website votes, career boosters,
 *   referrals...). What does not fit stays pending; nothing is ever dropped.</li>
 *   <li><b>Mailbox</b>: any plugin ({@link Rewards#queue}) or config ({@code /rewards queue}) hands a reward to a
 *   player with its commands and the slots it needs.</li>
 *   <li><b>Login / playtime</b>: see {@link DailyRewards}; shown in the rewards page of the main menu.</li>
 * </ul>
 *
 * <pre>
 *   /rewards [claim]                     claims everything that fits
 *   /rewards menu                        menu of the pending rewards
 *   /rewards day | playtime [n]          claims today's reward / playtime tier n (all when omitted)
 *   /rewards queue &lt;player&gt; &lt;slots&gt; &lt;icon&gt; &lt;label&gt; | &lt;command&gt; [; &lt;command&gt;...]   (console, admin)
 *   /rewards admin day &lt;player&gt; &lt;n&gt; | reset &lt;player&gt; | reload
 * </pre>
 * French sub-commands (recuperer, jour, temps) are accepted too.
 */
public final class FluxRewards extends FluxPlugin implements Listener {

    private DailyRewards daily;
    private Mailbox mailbox;
    private Claimer claimer;

    @Override
    protected List<String> formerNames() {
        return List.of("InfluRecompenses");
    }

    @Override
    protected void enable() {
        daily = new DailyRewards(this);
        daily.load();
        try {
            mailbox = new Mailbox(this);
        } catch (Exception e) {
            getLogger().severe("Mailbox database unavailable, queued rewards disabled: " + e);
        }
        claimer = new Claimer(this);
        if (mailbox != null) {
            Rewards.provideMailbox(this, mailbox, p -> {
                if (p instanceof Player online) claimer.refresh(online);
            });
            Rewards.register(this, mailbox);
        }
        Rewards.register(this, LoginSources.daily(this));
        Rewards.register(this, LoginSources.playtime(this));
        list("rewards", claimer);
        placeholders("fluxrewards", this::placeholder);
        placeholders("recompenses", this::placeholder); // former prefix, used by the existing menus
        command("rewards")
                .description("Claim every reward waiting for you.")
                .aliases("recompenses", "recompense", "daily")
                .usage(messages().raw("usage"))
                .executor(this::onCommand)
                .completer(this::complete)
                .register();

        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) arrive(p);
        // midnight for players staying online
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (daily.countDay(p)) {
                    messages().send(p, "new-day");
                    claimer.refresh(p);
                }
            }
        }, 1200L, 1200L);
        long every = getConfig().getLong("reminder.interval-seconds", 0) * 20L;
        if (every > 0) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    claimer.refresh(p);
                    Bukkit.getScheduler().runTaskLater(this, () -> remind(p), 60L);
                }
            }, every, every);
        }
    }

    @Override
    protected void disable() {
        if (daily != null) daily.saveAll();
        if (mailbox != null) mailbox.close();
    }

    @Override
    public void reload() {
        super.reload();
        daily.load();
    }

    DailyRewards daily() {
        return daily;
    }

    /** Runs reward commands from the console ({@code %player%} and the former {@code {joueur}} = player name). */
    void run(Player player, List<String> commands) {
        for (String cmd : commands) {
            if (cmd == null || cmd.isBlank()) continue;
            String line = cmd.replace("%player%", player.getName()).replace("{joueur}", player.getName()).trim();
            if (line.startsWith("/")) line = line.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        }
    }

    private void arrive(Player p) {
        daily.load(p.getUniqueId());
        daily.countDay(p);
        daily.playtime(p.getUniqueId());
        claimer.refresh(p);
    }

    private void remind(Player p) {
        if (!p.isOnline()) return;
        int count = claimer.pending(p.getUniqueId()).size();
        if (count > 0) messages().send(p, "reminder", "%count%", String.valueOf(count));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        arrive(p);
        Bukkit.getScheduler().runTaskLater(this, () -> remind(p), 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        daily.playtime(e.getPlayer().getUniqueId());
        daily.unload(e.getPlayer().getUniqueId());
        claimer.forget(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("admin")) {
            admin(sender, args);
            return true;
        }
        if (sub.equals("queue")) {
            queue(sender, args);
            return true;
        }
        Player p = Players.of(sender);
        if (p == null) {
            messages().send(sender, "players-only");
            return true;
        }
        switch (sub) {
            case "" -> {
                if (getConfig().getBoolean("command-opens-menu", false) && FluxApi.hasMenu("rewards")) FluxApi.open(p, "rewards");
                else claimer.claimAll(p);
            }
            case "claim", "recuperer" -> claimer.claimAll(p);
            case "menu" -> {
                claimer.refresh(p);
                if (!FluxApi.open(p, "rewards")) claimer.claimAll(p);
            }
            case "day", "jour" -> {
                daily.claimDay(p, Inventories.freeSlots(p), true);
                claimer.refresh(p);
            }
            case "playtime", "temps" -> {
                daily.claimPlaytime(p, args.length > 1 ? parseInt(args[1]) : 0, Inventories.freeSlots(p), true);
                claimer.refresh(p);
            }
            default -> messages().send(p, "help");
        }
        return true;
    }

    /** {@code queue <player> <slots> <icon> <label words...> | <command> [; <command>...]}. */
    private void queue(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fluxrewards.queue") && !sender.hasPermission("influrecompenses.admin")) {
            messages().send(sender, "no-permission");
            return;
        }
        String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        int bar = joined.indexOf(" | ");
        String[] head = (bar < 0 ? joined : joined.substring(0, bar)).trim().split("\\s+", 4);
        if (bar < 0 || head.length < 4) {
            messages().send(sender, "queue-usage");
            return;
        }
        OfflinePlayer target = Players.find(head[0]);
        int slots = parseInt(head[1]);
        if (target == null || slots < 0) {
            messages().send(sender, "queue-usage");
            return;
        }
        List<String> commands = new ArrayList<>();
        for (String c : joined.substring(bar + 3).split(";")) if (!c.isBlank()) commands.add(c.trim());
        if (!Rewards.queue(target, "mailbox", head[3], head[2], slots, commands)) {
            messages().send(sender, "queue-unavailable");
            return;
        }
        if (target instanceof Player online) messages().send(online, "queued", "%label%", head[3]);
        if (!(sender instanceof Player) || sender != target) {
            messages().send(sender, "queue-done", "%player%", Players.name(target, head[0]), "%label%", head[3]);
        }
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("influrecompenses.admin")) {
            messages().send(sender, "no-permission");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return;
        }
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : null;
        if (target == null) {
            messages().send(sender, "admin-usage");
            return;
        }
        switch (sub) {
            case "day", "jour" -> {
                int n = args.length > 3 ? parseInt(args[3]) : -1;
                if (n < 1) {
                    messages().send(sender, "admin-usage");
                    return;
                }
                daily.setDay(target, n);
                messages().send(sender, "admin-day", "%player%", target.getName(), "%day%", String.valueOf(n));
            }
            case "reset" -> {
                daily.reset(target);
                messages().send(sender, "admin-reset", "%player%", target.getName());
            }
            default -> {
                messages().send(sender, "admin-usage");
                return;
            }
        }
        claimer.refresh(target);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("claim", "menu", "day", "playtime"));
            if (sender.hasPermission("influrecompenses.admin")) out.addAll(List.of("admin", "queue"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            out.addAll(List.of("day", "reset", "reload"));
        } else if ((args.length == 3 && args[0].equalsIgnoreCase("admin")) || (args.length == 2 && args[0].equalsIgnoreCase("queue"))) {
            return Players.complete(last);
        }
        return out.stream().filter(s -> s.startsWith(last)).toList();
    }

    // ------------------------------------------------------------------ placeholders

    /**
     * {@code %fluxrewards_...%} (also {@code %recompenses_...%}), indexes from 1, empty when absent:
     * <ul>
     *   <li>{@code pending} (or {@code en_attente}): rewards waiting in every source</li>
     *   <li>{@code jour} (day of the cycle), {@code jours_total}, {@code cycle}, {@code jour_dispo} (oui/non)</li>
     *   <li>{@code jour_<d>_etat}: recupere, disponible, manque, verrouille; {@code jour_<d>_nom}, {@code jour_<d>_ligne_<j>}</li>
     *   <li>{@code temps_joue}, {@code temps_atteints} (path progress), {@code temps_total}</li>
     *   <li>{@code temps_<i>_etat|nom|seuil|restant|ligne_<j>}</li>
     *   <li>{@code a_recuperer}: login and playtime rewards claimable now</li>
     * </ul>
     */
    private String placeholder(Player player, String params) {
        if (params.equals("cycle")) return String.valueOf(daily.cycle());
        if (params.equals("temps_total")) return String.valueOf(daily.playtimeTiers().size());
        if (player == null) return "";
        UUID uuid = player.getUniqueId();
        if (params.equals("pending") || params.equals("en_attente")) {
            List<PendingReward> list = claimer.pending(uuid);
            return String.valueOf(list.size());
        }
        PlayerData d = daily.data(uuid);
        if (d == null) return "";
        switch (params) {
            case "jour" -> {
                return String.valueOf(d.dayOfCycle(daily.cycle()));
            }
            case "jours_total" -> {
                return String.valueOf(d.days);
            }
            case "jour_dispo" -> {
                return Format.yesNo(daily.availableDay(player) != null);
            }
            case "temps_joue" -> {
                return Format.duration(daily.playtime(uuid));
            }
            case "temps_atteints" -> {
                return String.valueOf(daily.playtimeReached(uuid));
            }
            case "a_recuperer" -> {
                return String.valueOf(daily.available(uuid));
            }
            default -> {
            }
        }
        String[] p = params.split("_", 3); // jour_<d>_<field> / temps_<i>_<field>
        if (p.length < 3) return null;
        int n = parseInt(p[1]);
        String field = p[2];
        if (p[0].equals("jour")) {
            if (n < 1 || n > daily.cycle()) return "";
            RewardTier tier = daily.dayTier(n);
            if (field.equals("etat")) return stateId(daily.dayState(d, n));
            if (field.equals("nom")) return tier != null ? tier.name() : messages().raw("day-name", "%day%", String.valueOf(n));
            if (field.startsWith("ligne_")) return tier != null ? line(player, tier.lore(), field) : "";
            return "";
        }
        if (p[0].equals("temps")) {
            List<RewardTier> tiers = daily.playtimeTiers();
            if (n < 1 || n > tiers.size()) return "";
            RewardTier tier = tiers.get(n - 1);
            return switch (field) {
                case "etat" -> stateId(daily.playtimeState(uuid, d, tier));
                case "nom" -> tier.name();
                case "seuil" -> Format.duration(tier.threshold());
                case "restant" -> Format.duration(Math.max(0, tier.threshold() - daily.playtime(uuid)));
                default -> field.startsWith("ligne_") ? line(player, tier.lore(), field) : "";
            };
        }
        return null;
    }

    /** State ids read by the existing menus (French, kept for compatibility). */
    private static String stateId(DailyRewards.State state) {
        return switch (state) {
            case CLAIMED -> "recupere";
            case AVAILABLE -> "disponible";
            case MISSED -> "manque";
            case LOCKED -> "verrouille";
        };
    }

    private static String line(Player player, List<String> lines, String field) {
        int j = parseInt(field.substring("ligne_".length()));
        if (j < 1 || j > lines.size()) return "";
        return Texts.placeholders(lines.get(j - 1), player);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
