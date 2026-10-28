package fr.influencraft.flux.rewards;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.reward.StoredReward;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Flux's reward manager ({@link Rewards}): storage, {@code /rewards} (claim everything that fits, or the menu
 * {@code rewards}), list {@code rewards}, action {@code [rewards] claim [id|source:<source>]}, placeholders
 * {@code %flux_rewards_count%} / {@code %flux_rewards_slots%}, join reminder. Every claim runs on the main thread:
 * each reward is marked as claimed before its commands run.
 */
public final class RewardService implements Rewards.Backend, Listener {

    public static final String MENU = "rewards";
    public static final String LIST = "rewards";

    private final JavaPlugin flux;
    private final Supplier<Messages> messages;
    private final RewardStore store;
    /** Rewards given in the last second, per player: one message and one sound for a burst (catch-up, crate). */
    private final Map<UUID, List<String>> burst = new HashMap<>();
    private final DiscordNotifier discord;

    private RewardService(JavaPlugin flux, Supplier<Messages> messages, RewardStore store) {
        this.flux = flux;
        this.messages = messages;
        this.store = store;
        this.discord = DiscordNotifier.create(flux, store, () -> plain(messages.get().raw("rewards.discord-optout-done")));
    }

    public static RewardService start(JavaPlugin flux, Supplier<Messages> messages) {
        RewardStore store;
        try {
            store = new RewardStore(flux.getDataFolder(), flux.getLogger());
        } catch (SQLException e) {
            flux.getLogger().severe("Rewards database unavailable, rewards disabled: " + e);
            return null;
        }
        RewardService service = new RewardService(flux, messages, store);
        Rewards.bind(service);
        Lists.register(flux, LIST, query -> service.entries(query.viewer()));
        FluxApi.registerAction(flux, "rewards", service::action);
        Commands.create(flux, "rewards").description("Claim every reward waiting for you.")
                .usage("/rewards [claim|menu]").executor(service::command).completer(service::complete).register();
        Bukkit.getPluginManager().registerEvents(service, flux);
        long every = flux.getConfig().getLong("rewards.reminder-interval-seconds", 0) * 20L;
        if (every > 0) Bukkit.getScheduler().runTaskTimer(flux, () -> Bukkit.getOnlinePlayers().forEach(service::remind), every, every);
        // expiry and its reminders, every 30 minutes (first pass 2 minutes after the start)
        Bukkit.getScheduler().runTaskTimerAsynchronously(flux, service::expiry, 2400L, 36000L);
        return service;
    }

    public void stop() {
        Rewards.bind(null);
        store.close();
    }

    // ------------------------------------------------------------------ Backend

    @Override
    public boolean give(OfflinePlayer player, Reward reward) {
        if (!store.add(player.getUniqueId(), reward)) return false;
        sync(() -> {
            Player online = player.getPlayer();
            if (online == null) return;
            changed(online);
            if (flux.getConfig().getBoolean("rewards.notify-on-give", true)) notifyLater(online, reward.label());
        });
        return true;
    }

    private void notifyLater(Player player, String label) {
        List<String> labels = burst.get(player.getUniqueId());
        if (labels != null) {
            labels.add(label);
            return;
        }
        burst.put(player.getUniqueId(), new ArrayList<>(List.of(label)));
        Bukkit.getScheduler().runTaskLater(flux, () -> {
            List<String> all = burst.remove(player.getUniqueId());
            if (all == null || !player.isOnline()) return;
            if (all.size() == 1) messages.get().send(player, "rewards.given", "%label%", all.get(0));
            else messages.get().send(player, "rewards.given-many", "%count%", String.valueOf(all.size()));
            Sounds.cue(player, "alert");
        }, 20L);
    }

    @Override
    public boolean given(UUID player, String key) {
        return store.has(player, key);
    }

    @Override
    public List<StoredReward> pending(UUID player) {
        return store.pending(player);
    }

    @Override
    public ClaimResult claim(Player player, String source, long id, boolean verbose) {
        int room = Inventories.freeSlots(player);
        int delivered = 0, remaining = 0;
        for (StoredReward stored : store.pending(player.getUniqueId())) {
            Reward r = stored.reward();
            if (id >= 0 && stored.id() != id) continue;
            if (source != null && !source.equalsIgnoreCase(r.source())) continue;
            if (r.slots() > room) {
                remaining++;
                continue;
            }
            if (!store.markClaimed(stored.id())) continue; // claimed meanwhile
            room -= r.slots();
            run(player, r.commands());
            delivered++;
            // the commands may have filled more (or fewer) slots than announced
            room = Math.min(room, Inventories.freeSlots(player));
        }
        if (verbose) {
            Messages m = messages.get();
            if (delivered == 0 && remaining == 0) m.send(player, "rewards.nothing");
            if (delivered > 0) m.send(player, "rewards.claimed", "%count%", String.valueOf(delivered));
            if (remaining > 0) m.send(player, "rewards.remaining", "%count%", String.valueOf(remaining));
        }
        if (delivered > 0) {
            flux.getLogger().info("[Rewards] " + player.getName() + " claimed " + delivered + " reward(s)"
                    + (source == null ? "" : " (" + source + ")"));
        }
        changed(player);
        return new ClaimResult(delivered, remaining);
    }

    @Override
    public void open(Player player) {
        Rewards.collect(player);
        Lists.reset(player, LIST);
        if (!FluxApi.hasMenu(MENU) || !FluxApi.open(player, MENU)) claim(player, null, -1, true);
    }

    private void run(Player player, List<String> commands) {
        for (String cmd : commands) {
            if (cmd == null || cmd.isBlank()) continue;
            String line = cmd.replace("%player%", player.getName()).replace("{joueur}", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString()).trim();
            if (line.startsWith("/")) line = line.substring(1);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            } catch (RuntimeException e) {
                flux.getLogger().warning("[Rewards] Command failed for " + player.getName() + ": " + line + " (" + e + ")");
            }
        }
    }

    private void changed(Player player) {
        Lists.invalidate(player, LIST);
        MenuView view = FluxApi.view(player);
        if (view != null) view.refreshButtons();
    }

    // ------------------------------------------------------------------ expiry

    /** Time a reward waits before it is lost (rewards.expire-days, 0 = never), in ms. */
    private long expiryMs() {
        return Math.max(0, flux.getConfig().getLong("rewards.expire-days", 21)) * 86_400_000L;
    }

    /**
     * Async: warns the players whose rewards expire soon (rewards.expire-reminders, e.g. 7d and 24h: in game when
     * online, Discord private message through DiscordSRV when the account is linked), then expires the old ones.
     * The latest reminder is handled first: after a long stop, a player only gets the closest one.
     */
    private void expiry() {
        long delay = expiryMs();
        if (delay <= 0) return;
        long now = System.currentTimeMillis();
        long from = expiryStart();
        List<Long> before = new ArrayList<>();
        for (String raw : flux.getConfig().getStringList("rewards.expire-reminders")) {
            long seconds = fr.influencraft.flux.api.util.Format.parseDuration(raw);
            if (seconds > 0 && seconds * 1000L < delay) before.add(seconds * 1000L);
        }
        before.sort(java.util.Comparator.reverseOrder()); // 7d, 24h
        for (int i = before.size() - 1; i >= 0; i--) {
            long left = before.get(i);
            long createdBefore = now - (delay - left);
            if (createdBefore <= from) continue; // rewards older than the expiry count from its start
            Map<UUID, Integer> due = store.due(createdBefore, i + 1);
            if (due.isEmpty()) continue;
            store.markWarned(createdBefore, i + 1);
            String when = fr.influencraft.flux.api.util.Format.duration(left / 1000L);
            for (Map.Entry<UUID, Integer> e : due.entrySet()) warn(e.getKey(), e.getValue(), when);
        }
        Map<UUID, Integer> lost = now - delay <= from ? Map.of() : store.expire(now - delay);
        if (!lost.isEmpty()) {
            flux.getLogger().info("Rewards expired: " + lost);
            sync(() -> lost.forEach((uuid, count) -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    changed(p);
                    messages.get().send(p, "rewards.expired", "%count%", String.valueOf(count));
                }
            }));
        }
    }

    private volatile long expiryStart = -1;

    /**
     * Day the expiry started (file rewards-expiry-start.txt, written on the first run): rewards waiting from before
     * count their delay from it, so turning the expiry on never takes old rewards at once.
     */
    private long expiryStart() {
        if (expiryStart >= 0) return expiryStart;
        java.io.File file = new java.io.File(flux.getDataFolder(), "rewards-expiry-start.txt");
        try {
            if (file.isFile()) {
                expiryStart = Long.parseLong(java.nio.file.Files.readString(file.toPath()).trim());
            } else {
                expiryStart = System.currentTimeMillis();
                java.nio.file.Files.writeString(file.toPath(), String.valueOf(expiryStart));
            }
        } catch (java.io.IOException | NumberFormatException e) {
            flux.getLogger().warning("rewards-expiry-start.txt unreadable, expiry counted from now: " + e);
            expiryStart = System.currentTimeMillis();
        }
        return expiryStart;
    }

    private void warn(UUID uuid, int count, String when) {
        Messages m = messages.get();
        sync(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                m.send(p, "rewards.expiring");
                fr.influencraft.flux.api.util.Sounds.cue(p, "alert");
            }
        });
        if (discord != null && flux.getConfig().getBoolean("rewards.discord-reminders", true)) {
            discord.send(uuid, plain(m.raw("rewards.discord-expiring")), plain(m.raw("rewards.discord-optout-button")));
        }
    }

    /** Discord text: no Minecraft colour codes. */
    private static String plain(String raw) {
        return fr.influencraft.flux.api.text.Texts.plain(fr.influencraft.flux.api.text.Texts.colors(raw));
    }

    private void sync(Runnable task) {
        if (!flux.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(flux, task);
    }

    // ------------------------------------------------------------------ menu, placeholders

    private List<ListEntry> entries(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        Messages m = messages.get();
        for (StoredReward stored : store.pending(viewer.getUniqueId())) {
            Reward r = stored.reward();
            ItemStack icon = Items.of(r.icon(), viewer);
            if (icon != null) icon.setAmount(Math.max(1, Math.min(99, r.amount())));
            String source = m.raw("rewards.sources." + r.source());
            out.add(ListEntry.of(String.valueOf(stored.id())).item(icon)
                    .set("label", r.label())
                    .set("amount", r.amount())
                    .set("slots", r.slots())
                    .set("source", source.isEmpty() ? r.source() : source)
                    .set("date", fr.influencraft.flux.api.util.Format.date(stored.created()))
                    .set("expire", expiryMs() <= 0 ? "" : fr.influencraft.flux.api.util.Format.duration(
                            Math.max(0, Math.max(stored.created(), expiryStart()) + expiryMs() - System.currentTimeMillis()) / 1000L)));
        }
        return out;
    }

    /** {@code %flux_rewards_<field>%}: {@code count}, {@code slots} (slots needed by everything waiting). */
    public String placeholder(Player player, String field) {
        if (player == null) return "";
        List<StoredReward> list = store.pending(player.getUniqueId());
        return switch (field) {
            case "count" -> String.valueOf(list.size());
            case "slots" -> String.valueOf(list.stream().mapToInt(s -> s.reward().slots()).sum());
            case "has" -> String.valueOf(!list.isEmpty());
            default -> null;
        };
    }

    /**
     * {@code [rewards] claim}, {@code [rewards] claim <id>}, {@code [rewards] claim source:<source>},
     * {@code [rewards] claim key:<key>} (one reward of a path, e.g. {@code key:referral:tier:5}), {@code [rewards] open}.
     */
    private void action(Player player, MenuView view, String argument) {
        String[] args = argument.trim().split("\\s+");
        if (args[0].equalsIgnoreCase("open")) {
            open(player);
            return;
        }
        if (!args[0].equalsIgnoreCase("claim")) return;
        Rewards.collect(player);
        if (args.length < 2) {
            claim(player, null, -1, true);
        } else if (args[1].startsWith("source:")) {
            claim(player, args[1].substring(7), -1, true);
        } else if (args[1].startsWith("key:")) {
            String key = args[1].substring(4);
            StoredReward match = store.pending(player.getUniqueId()).stream()
                    .filter(r -> key.equals(r.reward().key())).findFirst().orElse(null);
            if (match == null) messages.get().send(player, "rewards.nothing");
            else claim(player, null, match.id(), true);
        } else {
            try {
                claim(player, null, Long.parseLong(args[1]), true);
            } catch (NumberFormatException e) {
                claim(player, null, -1, true);
            }
        }
    }

    // ------------------------------------------------------------------ join, reminder

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // let the other plugins load the player's data first
        Bukkit.getScheduler().runTaskLater(flux, () -> {
            if (!player.isOnline()) return;
            Rewards.collect(player);
            Bukkit.getScheduler().runTaskLater(flux, () -> remind(player), 60L);
        }, 40L);
    }

    private void remind(Player player) {
        if (!player.isOnline()) return;
        int count = store.pending(player.getUniqueId()).size();
        if (count == 0) return;
        messages.get().send(player, "rewards.reminder", "%count%", String.valueOf(count));
        Sounds.cue(player, "alert");
    }

    // ------------------------------------------------------------------ /rewards

    private boolean command(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        Messages m = messages.get();
        if (sub.equals("give")) {
            give(sender, args);
            return true;
        }
        if (sub.equals("list")) {
            list(sender, args);
            return true;
        }
        Player p = Players.of(sender);
        if (p == null) {
            m.send(sender, "rewards.players-only");
            return true;
        }
        switch (sub) {
            case "" -> {
                if (flux.getConfig().getBoolean("rewards.command-opens-menu", true)) open(p);
                else {
                    Rewards.collect(p);
                    claim(p, null, -1, true);
                }
            }
            case "claim", "recuperer" -> {
                Rewards.collect(p);
                claim(p, args.length > 1 ? args[1] : null, -1, true);
            }
            case "menu" -> open(p);
            case "discord" -> {
                // switches the Discord reminders of the rewards about to expire
                java.util.UUID id = p.getUniqueId();
                Bukkit.getScheduler().runTaskAsynchronously(flux, () -> {
                    boolean off = !store.discordOptOut(id);
                    store.setDiscordOptOut(id, off);
                    sync(() -> m.send(p, off ? "rewards.discord-off" : "rewards.discord-on"));
                });
            }
            default -> m.send(p, "rewards.help");
        }
        return true;
    }

    /** {@code give <player> <slots> <icon> <label words...> | <command> [; <command>...]} (console, admin). */
    private void give(CommandSender sender, String[] args) {
        Messages m = messages.get();
        if (!sender.hasPermission("flux.rewards.give")) {
            m.send(sender, "rewards.no-permission");
            return;
        }
        String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        int bar = joined.indexOf(" | ");
        String[] head = (bar < 0 ? joined : joined.substring(0, bar)).trim().split("\\s+", 4);
        int slots = head.length > 1 ? parse(head[1]) : -1;
        OfflinePlayer target = head.length > 0 ? Players.find(head[0]) : null;
        if (bar < 0 || head.length < 4 || target == null || slots < 0) {
            m.send(sender, "rewards.give-usage");
            return;
        }
        List<String> commands = new ArrayList<>();
        for (String c : joined.substring(bar + 3).split(";")) if (!c.isBlank()) commands.add(c.trim());
        Reward reward = Reward.of("mailbox", head[3]).icon(head[2]).slots(slots).commands(commands);
        if (!give(target, reward)) {
            m.send(sender, "rewards.give-failed");
            return;
        }
        if (!(sender instanceof Player) || sender != target) {
            m.send(sender, "rewards.give-done", "%player%", Players.name(target, head[0]), "%label%", head[3]);
        }
    }

    private void list(CommandSender sender, String[] args) {
        Messages m = messages.get();
        if (!sender.hasPermission("flux.rewards.give")) {
            m.send(sender, "rewards.no-permission");
            return;
        }
        OfflinePlayer target = args.length > 1 ? Players.find(args[1]) : null;
        if (target == null) {
            m.send(sender, "rewards.list-usage");
            return;
        }
        List<StoredReward> list = store.pending(target.getUniqueId());
        m.send(sender, "rewards.list-header", "%player%", Players.name(target, args[1]), "%count%", String.valueOf(list.size()));
        for (StoredReward s : list) {
            m.send(sender, "rewards.list-line", "%id%", String.valueOf(s.id()), "%source%", s.reward().source(),
                    "%label%", s.reward().label(), "%slots%", String.valueOf(s.reward().slots()));
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("claim", "menu", "discord"));
            if (sender.hasPermission("flux.rewards.give")) out.addAll(List.of("give", "list"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("list"))) {
            return Players.complete(last);
        }
        return out.stream().filter(s -> s.startsWith(last)).toList();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ former FluxRewards mailbox

    /** Moves the rewards of the former FluxRewards mailbox ({@code plugins/FluxRewards/mailbox.db}) once. */
    public void importFormerMailbox(File pluginsFolder) {
        File old = new File(new File(pluginsFolder, "FluxRewards"), "mailbox.db");
        if (!old.isFile()) return;
        int moved = 0;
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + old.getAbsolutePath());
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, uuid, source, label, icon, slots, commands FROM rewards ORDER BY id")) {
            while (rs.next()) {
                Reward r = new Reward(rs.getString(3), "mailbox:" + rs.getLong(1), rs.getString(4), rs.getString(5), 1,
                        rs.getInt(6), Arrays.asList(rs.getString(7).split("\n")));
                if (store.add(UUID.fromString(rs.getString(2)), r)) moved++;
            }
        } catch (Exception e) {
            flux.getLogger().warning("Former FluxRewards mailbox not imported: " + e);
            return;
        }
        File done = new File(old.getParentFile(), "mailbox.db.imported-in-flux");
        if (old.renameTo(done)) {
            new File(old.getPath() + "-wal").delete();
            new File(old.getPath() + "-shm").delete();
        }
        flux.getLogger().info(moved + " reward(s) of the former FluxRewards mailbox moved into Flux.");
    }
}
