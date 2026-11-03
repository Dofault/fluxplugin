package fr.influencraft.fluxafk;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.tab.TabList;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxafk.api.Afk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FluxAFK: a player idle for {@code idle-time} (or typing {@code /afk}) is taken to the AFK zone. There he cannot
 * act; the first key he presses, head movement, click or command brings him back where he was. While he stays, a bar
 * fills up under a title in the middle of the screen, and each full bar keeps the configured rewards for him in Flux's
 * {@code /rewards}. Goal: idle players stay connected instead of leaving.
 *
 * <p>Idle time is FluxAFK's own: only what the player does counts (a key pressed, the head turned, a click, the chat,
 * a command), never being moved (water, pushing, a vehicle). In the zone the same rule decides when he leaves, so
 * players standing on the same spot cannot push each other out: pushing is turned off for them
 * ({@link TabList#collision}), and a push without any key pressed only puts the player back on his place.</p>
 *
 * <pre>
 *   /afk                                                      go AFK now, or come back
 *   /fluxafk setzone | check|send|leave &lt;player&gt; | reload    (admin)
 * </pre>
 */
public final class FluxAFK extends FluxPlugin {

    /** How a player leaves the zone. */
    enum Leave {
        /** Back to where he was (he moved, clicked, typed a command, /afk again). */
        BACK,
        /** He stays where another plugin took him (teleport, death). */
        STAY,
        /** Disconnected: his return place stays in the database for his next join. */
        QUIT
    }

    /** One stay in the zone. */
    static final class Session {
        final Location origin;
        final boolean flying;
        final float fallDistance;
        /** Taken to the zone (false: frozen where he was, no zone set). */
        final boolean moved;
        final int since;
        /** Where he stands once settled (grace over), the place a push puts him back to. */
        Location anchor;
        int rewards;
        boolean leaving;

        Session(Location origin, boolean flying, float fallDistance, boolean moved, int since) {
            this.origin = origin;
            this.flying = flying;
            this.fallDistance = fallDistance;
            this.moved = moved;
            this.since = since;
        }
    }

    private record RewardEntry(Reward reward, String permission, double chance) {
    }

    private final AfkStore store = new AfkStore(getLogger());
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Last real action of every online player (ms). */
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    /** Seconds spent in the zone towards the next round of rewards, during the current stay only. */
    private final Map<UUID, Integer> progress = new HashMap<>();
    private final Map<UUID, Long> lastCombat = new HashMap<>();
    /** Players whose next fall is harmless (brought back in the air, flight lost), with the tick they came back. */
    private final Map<UUID, Integer> protectedFall = new HashMap<>();
    /** Players of the zone whose bar fills up this second (one per connection, see max-per-ip). */
    private final Set<UUID> earning = new HashSet<>();

    private long idleSeconds, cycleSeconds;
    private int dailyLimit, maxPerIp, combatSeconds, graceSeconds, barSteps;
    private boolean announce, chatLeaves;
    private double driftTolerance;
    private ZoneId timeZone = ZoneId.of("Europe/Paris");
    private String zoneSpec = "", barGlyph = "";
    private Set<String> disabledWorlds = Set.of(), allowedCommands = Set.of();
    private List<BoundingBox> excludedBoxes = List.of();
    private List<String> excludedWorlds = List.of();
    private List<RewardEntry> rewards = List.of();

    private boolean teleporting;
    private boolean warnedRewards;
    private int ticks;

    @Override
    protected void enable() {
        settings();
        store.open();
        Afk.register(new Afk.Service() {
            public boolean afk(Player player) {
                return sessions.containsKey(player.getUniqueId());
            }

            public double progress(Player player) {
                return ratio(player.getUniqueId());
            }
        });
        placeholders("fluxafk", this::placeholder);
        TabList.piece(this, "away", p -> sessions.containsKey(p.getUniqueId()) ? "true" : "");
        command("afk")
                .description("Go AFK now, or come back.")
                .permission("fluxafk.use")
                .executor(this::onAfk)
                .register();
        command("fluxafk")
                .description("AFK zone administration.")
                .permission("fluxafk.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer(this::completeAdmin)
                .register();
        Bukkit.getPluginManager().registerEvents(new AfkListener(this), this);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastActive.put(p.getUniqueId(), now);
            restore(p);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    protected void disable() {
        // a reload or a stop brings everybody back now, nothing left to restore at the next join
        for (Player p : Bukkit.getOnlinePlayers()) leave(p, Leave.BACK, false);
    }

    @Override
    public void reload() {
        super.reload();
        settings();
    }

    private void settings() {
        idleSeconds = Math.max(0, Format.parseDuration(getConfig().getString("idle-time", "10m")));
        cycleSeconds = Math.max(10, Format.parseDuration(getConfig().getString("reward-every", "15m")));
        dailyLimit = Math.max(0, getConfig().getInt("daily-limit", 0));
        maxPerIp = Math.max(0, getConfig().getInt("max-per-ip", 1));
        combatSeconds = Math.max(0, getConfig().getInt("combat-seconds", 15));
        graceSeconds = Math.max(0, getConfig().getInt("grace-seconds", 2));
        driftTolerance = Math.max(0.2, getConfig().getDouble("drift-tolerance", 1.0));
        announce = getConfig().getBoolean("announce", true);
        chatLeaves = getConfig().getBoolean("chat-leaves", false);
        zoneSpec = getConfig().getString("zone", "");
        barGlyph = getConfig().getString("bar.glyph", "fluxafk:bar_{step}");
        barSteps = Math.max(1, getConfig().getInt("bar.steps", 20));
        try {
            timeZone = ZoneId.of(getConfig().getString("time-zone", "Europe/Paris"));
        } catch (RuntimeException e) {
            getLogger().warning("Unknown time-zone, using Europe/Paris.");
            timeZone = ZoneId.of("Europe/Paris");
        }
        disabledWorlds = lower(getConfig().getStringList("disabled-worlds"));
        Set<String> allowed = lower(getConfig().getStringList("allowed-commands"));
        allowedCommands = allowed;
        List<BoundingBox> boxes = new ArrayList<>();
        List<String> boxWorlds = new ArrayList<>();
        for (String area : getConfig().getStringList("excluded-areas")) {
            String[] a = area.split(";");
            try {
                boxes.add(new BoundingBox(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                        Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6])));
                boxWorlds.add(a[0].toLowerCase(Locale.ROOT));
            } catch (RuntimeException e) {
                getLogger().warning("Unreadable excluded area (world;x1;y1;z1;x2;y2;z2): " + area);
            }
        }
        excludedBoxes = boxes;
        excludedWorlds = boxWorlds;
        List<RewardEntry> entries = new ArrayList<>();
        ConfigurationSection section = getConfig().getConfigurationSection("rewards");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection r = section.getConfigurationSection(id);
                if (r == null) continue;
                entries.add(new RewardEntry(Reward.fromConfig(r, "afk", id), r.getString("permission", ""),
                        r.getDouble("chance", 1.0)));
            }
        }
        rewards = entries;
        // a missing section reads as an empty one (Bukkit defaults): say it, full bars would silently give nothing
        if (entries.isEmpty()) getLogger().warning("No reward in the rewards section of config.yml: full bars give nothing.");
        if (!zoneSpec.isBlank() && zone() == null) getLogger().warning("AFK zone unreadable or in an unloaded world: " + zoneSpec);
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) out.add(v.toLowerCase(Locale.ROOT));
        return out;
    }

    /** The AFK zone, or null when none is set (players then stay frozen where they are). */
    private Location zone() {
        if (zoneSpec == null || zoneSpec.isBlank()) return null;
        String[] a = zoneSpec.split(";");
        if (a.length < 4) return null;
        World world = Bukkit.getWorld(a[0]);
        if (world == null) return null;
        try {
            return new Location(world, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    a.length > 4 ? Float.parseFloat(a[4]) : 0f, a.length > 5 ? Float.parseFloat(a[5]) : 0f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- state, used by the listener

    Session session(Player p) {
        return sessions.get(p.getUniqueId());
    }

    /** The player did something himself: his idle time starts again. Any thread. */
    void active(Player p) {
        lastActive.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /** Arrived long enough ago for head movements to count. */
    boolean settled(Session s) {
        return ticks - s.since >= graceSeconds;
    }

    boolean teleporting() {
        return teleporting;
    }

    double driftTolerance() {
        return driftTolerance;
    }

    boolean chatLeaves() {
        return chatLeaves;
    }

    /** Whether a command typed in the zone keeps the player there (allowed list, or /afk itself). */
    boolean keepsAfk(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        int colon = l.indexOf(':');
        if (colon >= 0) l = l.substring(colon + 1);
        if (allowedCommands.contains(l)) return true;
        Command c = Bukkit.getCommandMap().getCommand(l);
        return c != null && c.getName().equals(Commands.label(getName(), "afk"));
    }

    void combat(Player p) {
        lastCombat.put(p.getUniqueId(), System.currentTimeMillis());
    }

    boolean fallProtected(Player p) {
        return protectedFall.remove(p.getUniqueId()) != null;
    }

    /** Leave on the next tick (from a move or input event, where teleporting is not safe). */
    void leaveSoon(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.leaving) return;
        s.leaving = true;
        Bukkit.getScheduler().runTask(this, () -> leave(p, Leave.BACK, true));
    }

    void joined(Player p) {
        active(p);
        restore(p);
    }

    void quit(Player p) {
        leave(p, Leave.QUIT, false);
        UUID id = p.getUniqueId();
        lastActive.remove(id);
        progress.remove(id);
        lastCombat.remove(id);
        protectedFall.remove(id);
        store.unload(id);
    }

    // ---------------------------------------------------------------- entering and leaving

    /** Whether the player may be taken to the zone where he stands (game mode, world, excluded areas). */
    private boolean placeAllows(Player p) {
        if (p.isDead() || p.isSleeping()) return false;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        String world = p.getWorld().getName().toLowerCase(Locale.ROOT);
        if (disabledWorlds.contains(world)) return false;
        Location at = p.getLocation();
        for (int i = 0; i < excludedBoxes.size(); i++) {
            if (excludedWorlds.get(i).equals(world) && excludedBoxes.get(i).contains(at.toVector())) return false;
        }
        return true;
    }

    private boolean inCombat(Player p) {
        Long at = lastCombat.get(p.getUniqueId());
        return combatSeconds > 0 && at != null && System.currentTimeMillis() - at < combatSeconds * 1000L;
    }

    /** Takes the player to the zone; false when that was refused (by the place or by another plugin). */
    boolean enter(Player p) {
        UUID id = p.getUniqueId();
        if (sessions.containsKey(id)) return true;
        if (!placeAllows(p)) return false;
        Location origin = p.getLocation();
        boolean flying = p.isFlying();
        float fall = p.getFallDistance();
        Location zone = zone();
        if (zone != null) {
            // saved before moving: a crash in the zone still brings him back at his next join
            store.saveReturn(id, origin, flying);
            if (p.isInsideVehicle()) p.leaveVehicle();
            if (!move(p, zone)) {
                store.clearReturn(id);
                return false;
            }
            if (flying) p.setFlying(false);
            p.setFallDistance(0);
        }
        progress.remove(id);
        sessions.put(id, new Session(origin, flying, fall, zone != null, ticks));
        TabList.collision(p, false);
        TabList.refresh(p);
        if (announce) broadcast("announce-on", p);
        show(p, sessions.get(id), today());
        return true;
    }

    /** Ends the stay in the zone (nothing when the player is not AFK). */
    void leave(Player p, Leave how, boolean message) {
        UUID id = p.getUniqueId();
        Session s = sessions.remove(id);
        if (s == null) return;
        earning.remove(id);
        TabList.collision(p, true);
        TabList.refresh(p);
        p.clearTitle();
        p.sendActionBar(Component.empty());
        progress.remove(id); // the bar always starts again from 0 on the next stay
        if (how == Leave.QUIT) return; // return place kept: restore() puts him back at his next join
        if (how == Leave.BACK && s.moved) bringBack(p, s.origin, s.flying, s.fallDistance);
        if (s.moved) store.clearReturn(id);
        active(p);
        if (announce) broadcast("announce-off", p);
        if (!message) return;
        if (s.rewards > 0) messages().send(p, "back", "%count%", String.valueOf(s.rewards));
        else messages().send(p, "back-none");
    }

    /** Puts a player who left the zone while disconnected (or during a crash) back where he was. */
    private void restore(Player p) {
        AfkStore.Return back = store.loadReturn(p.getUniqueId());
        if (back == null) return;
        // one tick later: the join position is applied after the join event
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
            bringBack(p, back.location(), back.flying(), 0);
            store.clearReturn(p.getUniqueId());
        });
    }

    private void bringBack(Player p, Location origin, boolean flying, float fallDistance) {
        move(p, origin);
        if (flying && p.getAllowFlight()) {
            p.setFlying(true);
        } else if (flying) {
            // was flying, cannot any more (FluxFly stopped it in the zone's world): the fall must not hurt
            protectedFall.put(p.getUniqueId(), ticks);
        } else {
            // a fall started before /afk goes on as if nothing happened
            p.setFallDistance(fallDistance);
        }
    }

    private boolean move(Player p, Location to) {
        teleporting = true;
        try {
            return p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            teleporting = false;
        }
    }

    private void broadcast(String key, Player about) {
        for (Player viewer : Bukkit.getOnlinePlayers()) messages().send(viewer, key, "%player%", about.getName());
    }

    // ---------------------------------------------------------------- every second

    private String today() {
        return LocalDate.now(timeZone).toString();
    }

    private double ratio(UUID id) {
        return Math.min(1.0, progress.getOrDefault(id, 0) / (double) cycleSeconds);
    }

    private boolean limited(UUID id, String day) {
        return dailyLimit > 0 && store.rounds(id, day) >= dailyLimit;
    }

    private void tick() {
        ticks++;
        String day = today();
        long now = System.currentTimeMillis();
        chooseEarners();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Session s = sessions.get(id);
            if (s == null) {
                long idle = (now - lastActive.getOrDefault(id, now)) / 1000;
                if (idleSeconds > 0 && idle >= idleSeconds && p.hasPermission("fluxafk.auto") && !p.hasPermission("fluxafk.exempt")) {
                    if (!enter(p)) active(p); // refused here: try again after another idle-time, not every second
                }
                continue;
            }
            if (s.anchor == null && settled(s)) s.anchor = p.getLocation();
            if (earning.contains(id) && !limited(id, day)) {
                int seconds = progress.merge(id, 1, Integer::sum);
                if (seconds >= cycleSeconds) {
                    progress.put(id, 0);
                    reward(p, s, day);
                }
            }
            show(p, s, day);
        }
        // a protection not used on the next landing is dropped
        protectedFall.entrySet().removeIf(e -> {
            Player p = Bukkit.getPlayer(e.getKey());
            return p == null || (ticks - e.getValue() > 1 && p.isOnGround());
        });
    }

    /** One connection fills one bar at a time: per IP, the first players to arrive earn. */
    private void chooseEarners() {
        earning.clear();
        List<Player> afk = new ArrayList<>();
        for (UUID id : sessions.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) afk.add(p);
        }
        afk.sort(Comparator.comparingInt(p -> sessions.get(p.getUniqueId()).since));
        Map<String, Integer> perIp = new HashMap<>();
        for (Player p : afk) {
            InetSocketAddress address = p.getAddress();
            String ip = address == null || address.getAddress() == null ? p.getUniqueId().toString() : address.getAddress().getHostAddress();
            if (maxPerIp > 0 && perIp.merge(ip, 1, Integer::sum) > maxPerIp) continue;
            earning.add(p.getUniqueId());
        }
    }

    private void reward(Player p, Session s, String day) {
        if (!Rewards.available()) {
            if (!warnedRewards) getLogger().warning("Flux's reward manager is not running: AFK rewards are not given.");
            warnedRewards = true;
            return;
        }
        store.addRound(p.getUniqueId(), day);
        int given = 0;
        for (RewardEntry e : rewards) {
            if (!e.permission().isBlank() && !p.hasPermission(e.permission())) continue;
            if (e.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= e.chance()) continue;
            if (Rewards.give(p, e.reward())) given++;
            else getLogger().warning("AFK reward not kept for " + p.getName() + ": " + e.reward().label());
        }
        // the welcome back message counts what really waits in /rewards
        s.rewards += given;
        if (given > 0) Sounds.cue(p, "complete");
    }

    /** Title "You are AFK", the bar under it, the time left (or why it is paused) in the action bar. */
    private void show(Player p, Session s, String day) {
        UUID id = p.getUniqueId();
        double ratio = ratio(id);
        Title title = Title.title(messages().get("title", p), Texts.parse(bar(p, ratio), p),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1600), Duration.ofMillis(400)));
        p.showTitle(title);
        if (!earning.contains(id)) {
            messages().actionBar(p, "actionbar-ip");
        } else if (limited(id, day)) {
            messages().actionBar(p, "actionbar-limit");
        } else {
            long left = cycleSeconds - progress.getOrDefault(id, 0);
            messages().actionBar(p, "actionbar", "%time%", Format.duration(left));
        }
    }

    /** The textured bar (one glyph per step) when the pack has it, else a text bar (Bedrock players too). */
    private String bar(Player p, double ratio) {
        int step = (int) Math.floor(ratio * barSteps);
        String glyph = Clients.bedrock(p) ? null : CustomItems.glyph(barGlyph.replace("{step}", String.valueOf(step)));
        if (glyph != null) return "&f" + glyph;
        return messages().raw("bar-text", "%bar%", Format.bar(ratio, 1.0, barSteps, "&#B376E5", "&8"));
    }

    // ---------------------------------------------------------------- commands

    private boolean onAfk(CommandSender sender, String label, String[] args) {
        Player p = Players.of(sender);
        if (p == null) return true;
        if (sessions.containsKey(p.getUniqueId())) {
            leave(p, Leave.BACK, true);
            return true;
        }
        if (inCombat(p)) {
            messages().send(p, "refused-combat");
            return true;
        }
        if (!enter(p)) messages().send(p, "refused-place");
        return true;
    }

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                reload();
                messages().send(sender, "reloaded");
            }
            case "setzone" -> {
                Player p = Players.of(sender);
                if (p == null) return true;
                Location l = p.getLocation();
                String spec = String.format(Locale.ROOT, "%s;%.2f;%.2f;%.2f;%.1f;%.1f", l.getWorld().getName(),
                        l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
                getConfig().set("zone", spec);
                saveConfig();
                zoneSpec = spec;
                messages().send(p, "zone-set");
            }
            case "check", "send", "leave" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    messages().send(sender, args.length > 1 ? "unknown-player" : "admin-usage");
                    return true;
                }
                UUID id = target.getUniqueId();
                boolean afk = sessions.containsKey(id);
                if (sub.equals("check")) {
                    messages().send(sender, "check", "%player%", target.getName(),
                            "%state%", messages().raw(afk ? "state-afk" : "state-active"),
                            "%percent%", String.valueOf((int) Math.floor(ratio(id) * 100)),
                            "%today%", String.valueOf(store.rounds(id, today())));
                } else if (sub.equals("send")) {
                    messages().send(sender, enter(target) ? "sent" : "cannot-send", "%player%", target.getName());
                } else if (afk) {
                    leave(target, Leave.BACK, true);
                    messages().send(sender, "brought-back", "%player%", target.getName());
                } else {
                    messages().send(sender, "not-afk", "%player%", target.getName());
                }
            }
            default -> messages().send(sender, "admin-usage");
        }
        return true;
    }

    private List<String> completeAdmin(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("setzone", "check", "send", "leave", "reload")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 2 && List.of("check", "send", "leave").contains(args[0].toLowerCase(Locale.ROOT))) return Players.complete(args[1]);
        return List.of();
    }

    // ---------------------------------------------------------------- placeholders

    /**
     * {@code %fluxafk_afk%} (true / false), {@code %fluxafk_progress%} (percent of the bar), {@code %fluxafk_next%}
     * (time to the next reward), {@code %fluxafk_today%} (rounds today), {@code %fluxafk_count%} (players AFK).
     */
    private String placeholder(Player player, String key) {
        if (key.equals("count")) return String.valueOf(sessions.size());
        if (player == null) return "";
        UUID id = player.getUniqueId();
        return switch (key) {
            case "afk" -> String.valueOf(sessions.containsKey(id));
            case "progress" -> String.valueOf((int) Math.floor(ratio(id) * 100));
            case "next" -> Format.duration(cycleSeconds - progress.getOrDefault(id, 0));
            case "today" -> String.valueOf(store.rounds(id, today()));
            default -> null;
        };
    }
}
