package fr.influencraft.fluxpw;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Teleports with a delay: the player stands still for {@code seconds}, a step or a jump is fine, walking away,
 * falling, taking damage (optional) or leaving cancels it. A new teleport replaces the one still waiting. The caller
 * learns the outcome through {@code done} (true = arrived), exactly once, also when the plugin stops.
 *
 * <p>While waiting: a ring of particles around the player, a title each second and sounds, the same as CMI's
 * warm-up for warps, homes and spawn (see {@link WaitLook}), so every teleport of the server looks the same.</p>
 */
final class Teleports implements Listener {

    private static final double MAX_STEP_SQUARED = 0.5 * 0.5, MAX_RISE = 1.5;
    private static final Title.Times TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(1250), Duration.ofMillis(250));

    private final FluxPW plugin;
    private final WaitLook look;
    private final Map<UUID, Pending> pending = new HashMap<>();
    boolean cancelOnDamage = true;

    private final class Pending {
        final Player player;
        final Location from, to;
        final Consumer<Boolean> done;
        int left;
        int frame, seconds;
        BukkitTask countdown, ring;

        Pending(Player player, Location to, int seconds, Consumer<Boolean> done) {
            this.player = player;
            this.from = player.getLocation().clone();
            this.to = to;
            this.left = seconds;
            this.done = done;
        }

        boolean moved(Location now) {
            if (now == null || now.getWorld() != from.getWorld()) return true;
            double dx = now.getX() - from.getX(), dz = now.getZ() - from.getZ();
            return dx * dx + dz * dz > MAX_STEP_SQUARED || Math.abs(now.getY() - from.getY()) > MAX_RISE;
        }

        void tick() {
            if (!player.isOnline()) {
                finish(this, false);
                return;
            }
            if (left <= 0) {
                arrive(this);
                return;
            }
            String time = String.valueOf(left);
            if (!look.title.isEmpty() || !look.subtitle.isEmpty()) {
                player.showTitle(Title.title(Texts.parse(Texts.replace(look.title, "%time%", time), player),
                        Texts.parse(Texts.replace(look.subtitle, "%time%", time), player), TIMES));
            }
            if (!look.actionBar.isEmpty()) Texts.actionBar(player, Texts.replace(look.actionBar, "%time%", time));
            if (seconds++ > 0) Sounds.play(player, look.soundTick);
            left--;
        }

        void draw() {
            if (player.isOnline() && look.ring != null) look.ring.draw(player.getLocation(), frame++);
        }
    }

    Teleports(FluxPW plugin) {
        this.plugin = plugin;
        this.look = new WaitLook(plugin);
    }

    /** Reads {@code teleport} of the config (see {@link WaitLook}). */
    void settings(ConfigurationSection teleport) {
        cancelOnDamage = teleport == null || teleport.getBoolean("cancel-on-damage", true);
        look.settings(teleport);
    }

    void start(Player player, Location to, int seconds, Consumer<Boolean> done) {
        Pending previous = pending.remove(player.getUniqueId());
        if (previous != null) finish(previous, false);
        Pending p = new Pending(player, to, seconds, done);
        if (seconds <= 0) {
            arrive(p);
            return;
        }
        look.refresh();
        pending.put(player.getUniqueId(), p);
        plugin.messages().send(player, "teleport-delay", "%time%", String.valueOf(seconds));
        Sounds.play(player, look.soundStart);
        p.countdown = Bukkit.getScheduler().runTaskTimer(plugin, p::tick, 0L, 20L);
        if (look.ring != null) p.ring = Bukkit.getScheduler().runTaskTimer(plugin, p::draw, 0L, look.ring.interval);
    }

    private static void stop(Pending p) {
        if (p.countdown != null) p.countdown.cancel();
        if (p.ring != null) p.ring.cancel();
        if (p.seconds > 0 && p.player.isOnline()) p.player.clearTitle();
    }

    private void arrive(Pending p) {
        pending.remove(p.player.getUniqueId(), p);
        stop(p);
        if (p.to.getWorld() == null || !p.to.isWorldLoaded() || !p.player.isOnline()) {
            p.done.accept(false);
            return;
        }
        // another plugin (combat, jail...) may refuse the teleport
        p.player.teleportAsync(p.to).thenAccept(ok -> {
            if (ok) Sounds.play(p.player, look.soundArrive);
            p.done.accept(ok);
        });
    }

    private void finish(Pending p, boolean arrived) {
        pending.remove(p.player.getUniqueId(), p);
        stop(p);
        if (!arrived && p.player.isOnline()) Sounds.play(p.player, look.soundCancel);
        p.done.accept(arrived);
    }

    /** Cancels every waiting teleport (plugin stopping): their callers refund what they held. */
    void cancelAll() {
        for (Pending p : new ArrayList<>(pending.values())) finish(p, false);
    }

    List<UUID> waiting() {
        return List.copyOf(pending.keySet());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Pending p = pending.get(e.getPlayer().getUniqueId());
        if (p != null && p.moved(e.getTo())) finish(p, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!cancelOnDamage || !(e.getEntity() instanceof Player player)) return;
        Pending p = pending.get(player.getUniqueId());
        if (p != null) finish(p, false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Pending p = pending.get(e.getPlayer().getUniqueId());
        if (p != null) finish(p, false);
    }
}
