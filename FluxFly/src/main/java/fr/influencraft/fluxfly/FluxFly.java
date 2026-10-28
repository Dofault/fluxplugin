package fr.influencraft.fluxfly;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.fluxfly.api.FlyTime;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FluxFly: a fly time bank per player. {@code /fly} turns flight on in the allowed worlds and spends the bank while
 * the player is actually flying; the bank is filled by rewards ({@code fluxfly add <player> <duration>}).
 * {@code fluxfly.unlimited} flies without spending (VIP), {@code fluxfly.bypass} flies everywhere (staff).
 *
 * <p>FluxFly only ever turns off the flight it turned on itself: flight given by another plugin (staff mode,
 * creative, spectator) is never touched.</p>
 *
 * <pre>
 *   /fly [player] [on|off]
 *   /fluxfly add|remove|set|check &lt;player&gt; [duration] | reload    (admin)
 * </pre>
 */
public final class FluxFly extends FluxPlugin implements Listener {

    private final FlyStore store = new FlyStore(getLogger());
    /** Players flying through FluxFly. */
    private final Set<UUID> flying = new HashSet<>();
    /** Players whose next fall is harmless (flight just stopped), with the tick it stopped. */
    private final Map<UUID, Integer> protectedFall = new HashMap<>();
    private final Map<UUID, Long> lastCombat = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();

    private List<String> worlds = List.of();
    private long startingTime;
    private int combatSeconds;
    private boolean fallProtection;
    private int fatigue;
    private boolean bossbar;
    private BossBar.Color barColor = BossBar.Color.WHITE;
    private int ticks;

    @Override
    protected void enable() {
        settings();
        store.open();
        FlyTime.register(new FlyTime.Service() {
            public long get(UUID p) {
                return store.get(p, startingTime);
            }

            public boolean known(UUID p) {
                return store.known(p);
            }

            public void set(UUID p, long seconds) {
                store.set(p, Math.max(FlyStore.UNLIMITED, seconds));
            }

            public void add(UUID p, long seconds) {
                long now = store.get(p, startingTime);
                if (now != FlyStore.UNLIMITED) store.set(p, Math.max(0, now + seconds));
            }
        });
        placeholders("fluxfly", this::placeholder);
        command("fly")
                .description("Turn your flight on or off (fly time).")
                .permission("fluxfly.use")
                .executor(this::onFly)
                .completer((sender, args) -> args.length == 1 && sender.hasPermission("fluxfly.others")
                        ? Players.complete(args[0]) : List.of())
                .register();
        command("fluxfly")
                .description("Fly time administration.")
                .permission("fluxfly.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer(this::completeAdmin)
                .register();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) store.load(p.getUniqueId(), startingTime);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    protected void disable() {
        for (UUID id : List.copyOf(flying)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stop(p, null);
        }
        store.saveOnline();
    }

    @Override
    public void reload() {
        super.reload();
        settings();
    }

    private void settings() {
        worlds = getConfig().getStringList("worlds");
        startingTime = getConfig().getLong("starting-time", 10);
        combatSeconds = getConfig().getInt("combat-seconds", 10);
        fallProtection = getConfig().getBoolean("fall-protection", true);
        fatigue = getConfig().getInt("mining-fatigue", 1);
        bossbar = getConfig().getBoolean("bossbar.enabled", true);
        try {
            barColor = BossBar.Color.valueOf(getConfig().getString("bossbar.color", "WHITE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            barColor = BossBar.Color.WHITE;
        }
    }

    // ---------------------------------------------------------------- flight

    private boolean allowedWorld(Player p) {
        if (p.hasPermission("fluxfly.bypass")) return true;
        for (String w : worlds) if (w.equalsIgnoreCase(p.getWorld().getName())) return true;
        return false;
    }

    private boolean free(Player p) {
        return p.hasPermission("fluxfly.bypass") || p.hasPermission("fluxfly.unlimited")
                || store.get(p.getUniqueId(), startingTime) == FlyStore.UNLIMITED;
    }

    private boolean inCombat(Player p) {
        Long at = lastCombat.get(p.getUniqueId());
        return combatSeconds > 0 && at != null && System.currentTimeMillis() - at < combatSeconds * 1000L;
    }

    /** Turns FluxFly's flight on; the reason message key when refused, null when done. */
    private String start(Player p) {
        if (!allowedWorld(p)) return "world";
        if (inCombat(p)) return "combat";
        if (!free(p) && store.get(p.getUniqueId(), startingTime) <= 0) return "no-time";
        flying.add(p.getUniqueId());
        protectedFall.remove(p.getUniqueId());
        p.setAllowFlight(true);
        p.setFlying(true);
        showBar(p);
        return null;
    }

    /** Turns FluxFly's flight off (message key or null for none). */
    private void stop(Player p, String message) {
        if (!flying.remove(p.getUniqueId())) return;
        boolean wasFlying = p.isFlying();
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        if (fallProtection && wasFlying) protectedFall.put(p.getUniqueId(), ticks);
        hideBar(p);
        if (message != null) messages().send(p, message, "%time%", time(p.getUniqueId()));
    }

    private void tick() {
        ticks++;
        for (UUID id : List.copyOf(flying)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                flying.remove(id);
                continue;
            }
            if (!allowedWorld(p)) {
                stop(p, "world-left");
                continue;
            }
            if (p.isFlying()) {
                if (!free(p)) {
                    long left = store.get(id, startingTime) - 1;
                    store.spend(id, Math.max(0, left));
                    if (left <= 0) {
                        stop(p, "time-out");
                        continue;
                    }
                }
                if (fatigue > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 40, fatigue - 1, true, false, false));
            }
            showBar(p);
        }
        // a protection not used on the next landing is dropped (no stored « free fall » for later)
        protectedFall.entrySet().removeIf(e -> {
            Player p = Bukkit.getPlayer(e.getKey());
            return p == null || (ticks - e.getValue() > 1 && p.isOnGround());
        });
        if (ticks % 60 == 0) store.saveOnline();
    }

    private void showBar(Player p) {
        if (!bossbar) return;
        String text = free(p) ? messages().raw("bossbar-unlimited") : messages().raw("bossbar", "%time%", time(p.getUniqueId()));
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(Texts.parse(text, p), 1f, barColor, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(Texts.parse(text, p));
        }
    }

    private void hideBar(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    private String time(UUID id) {
        long s = store.get(id, startingTime);
        return s == FlyStore.UNLIMITED ? messages().raw("unlimited") : Format.duration(s);
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        store.load(e.getPlayer().getUniqueId(), startingTime);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        stop(p, null);
        protectedFall.remove(p.getUniqueId());
        lastCombat.remove(p.getUniqueId());
        store.unload(p.getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        if (flying.contains(e.getPlayer().getUniqueId()) && !allowedWorld(e.getPlayer())) stop(e.getPlayer(), "world-left");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null || attacker == victim) return;
        long now = System.currentTimeMillis();
        for (Player p : new Player[]{victim, attacker}) {
            lastCombat.put(p.getUniqueId(), now);
            if (flying.contains(p.getUniqueId())) stop(p, "combat");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        if (protectedFall.remove(p.getUniqueId()) != null) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- commands

    private boolean onFly(CommandSender sender, String label, String[] args) {
        Player target;
        if (args.length > 0 && sender.hasPermission("fluxfly.others")) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                messages().send(sender, "unknown-player");
                return true;
            }
            boolean on = args.length > 1 ? args[1].equalsIgnoreCase("on") : !flying.contains(target.getUniqueId());
            if (on) {
                flying.add(target.getUniqueId());
                target.setAllowFlight(true);
                showBar(target);
            } else {
                stop(target, null);
            }
            messages().send(sender, "admin-others", "%player%", target.getName(), "%state%", messages().raw(on ? "on" : "off"));
            return true;
        }
        Player p = Players.of(sender);
        if (p == null) return true;
        if (flying.contains(p.getUniqueId())) {
            stop(p, "fly-off");
            return true;
        }
        String refused = start(p);
        if (refused == null) {
            messages().send(p, free(p) ? "fly-on-unlimited" : "fly-on", "%time%", time(p.getUniqueId()));
        } else {
            messages().send(p, refused, "%worlds%", String.join(", ", worlds), "%time%", time(p.getUniqueId()));
        }
        return true;
    }

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (args.length < 2 || !List.of("add", "remove", "set", "check").contains(sub)) {
            messages().send(sender, "admin-usage");
            return true;
        }
        OfflinePlayer target = Players.find(args[1]);
        if (target == null) {
            messages().send(sender, "unknown-player");
            return true;
        }
        UUID id = target.getUniqueId();
        String name = Players.name(target, args[1]);
        if (sub.equals("check")) {
            messages().send(sender, "admin-check", "%player%", name, "%time%", time(id));
            return true;
        }
        if (args.length < 3) {
            messages().send(sender, "admin-usage");
            return true;
        }
        long seconds = args[2].equalsIgnoreCase("unlimited") || args[2].equals("-1") ? FlyStore.UNLIMITED : Format.parseDuration(args[2]);
        if (seconds < 0 && seconds != FlyStore.UNLIMITED) {
            messages().send(sender, "bad-duration");
            return true;
        }
        switch (sub) {
            case "set" -> FlyTime.set(id, seconds);
            case "add" -> FlyTime.add(id, seconds);
            default -> FlyTime.add(id, -seconds);
        }
        messages().send(sender, "admin-set", "%player%", name, "%time%", time(id));
        return true;
    }

    private List<String> completeAdmin(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("add", "remove", "set", "check", "reload")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 2) return Players.complete(args[1]);
        if (args.length == 3) return List.of("10m", "30m", "1h");
        return List.of();
    }

    // ---------------------------------------------------------------- placeholders

    /** {@code %fluxfly_time%} (formatted), {@code %fluxfly_seconds%}, {@code %fluxfly_flying%} (true / false). */
    private String placeholder(Player player, String key) {
        if (player == null) return "";
        UUID id = player.getUniqueId();
        return switch (key) {
            case "time" -> time(id);
            case "seconds" -> String.valueOf(store.get(id, startingTime));
            case "flying" -> String.valueOf(flying.contains(id));
            default -> null;
        };
    }
}
