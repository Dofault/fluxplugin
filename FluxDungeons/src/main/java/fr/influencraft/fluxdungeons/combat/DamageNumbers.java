package fr.influencraft.fluxdungeons.combat;

import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Damage numbers of the whole server, in place of CMI's ({@code ShowDamageNumbers} off in its Settings/Combat.yml),
 * with the same look and rule: a number rising above whoever a player hurts, {@code &c❤5} on a creature,
 * {@code &c⚔5} on a player ({@code damage-numbers} of the config). Vanilla hits are read here once every plugin has
 * had its say; hits of the rpg engine (whose game damage is cancelled) are shown by the engine through {@link #show}.
 */
public final class DamageNumbers implements Listener {

    private final Plugin plugin;
    private boolean enabled = true;
    private String mobFormat = "&c❤{damage}", playerFormat = "&c⚔{damage}";
    /** Rpg extras in the same style: a crit, a dodge. */
    private String critFormat = "&#FFD94D❤{damage} ✦", dodgeFormat = "&7Esquive";
    /** Entities whose hits the rpg engine shows itself (their game damage is cancelled, never seen here). */
    private java.util.function.Predicate<Entity> handledElsewhere = e -> false;

    public DamageNumbers(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(ConfigurationSection c) {
        enabled = c == null || c.getBoolean("enabled", true);
        mobFormat = c == null ? "&c❤{damage}" : c.getString("mob-format", "&c❤{damage}");
        playerFormat = c == null ? "&c⚔{damage}" : c.getString("player-format", "&c⚔{damage}");
        critFormat = c == null ? "&#FFD94D❤{damage} ✦" : c.getString("crit-format", "&#FFD94D❤{damage} ✦");
        dodgeFormat = c == null ? "&7Esquive" : c.getString("dodge-format", "&7Esquive");
    }

    public void handledElsewhere(java.util.function.Predicate<Entity> p) {
        this.handledElsewhere = p;
    }

    /** A vanilla hit a player dealt, after every plugin (and not cancelled). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!enabled || !(e.getEntity() instanceof LivingEntity target) || handledElsewhere.test(target)) return;
        Entity d = e.getDamager();
        boolean byPlayer = d instanceof Player || d instanceof Projectile pr && pr.getShooter() instanceof Player;
        if (!byPlayer || e.getFinalDamage() <= 0) return;
        show(target, e.getFinalDamage());
    }

    /** Shows a hit a player dealt to this entity (also called by the rpg engine). */
    public void show(LivingEntity target, double amount) {
        show(target, amount, false);
    }

    /**
     * A hit: on a player {@code player-format} (a monster's rpg hit included), on a creature {@code mob-format}, or
     * {@code crit-format} for an rpg crit.
     */
    public void show(LivingEntity target, double amount, boolean crit) {
        if (!enabled || amount <= 0 || !target.isValid()) return;
        String format = target instanceof Player ? playerFormat : crit ? critFormat : mobFormat;
        rise(target, Texts.colors(format.replace("{damage}", format(amount))));
    }

    /** An rpg hit dodged ({@code dodge-format}). */
    public void dodge(LivingEntity target) {
        if (enabled && target.isValid()) rise(target, Texts.colors(dodgeFormat));
    }

    /** A text rising above an entity for a moment (also the rpg heals). */
    public void rise(LivingEntity target, net.kyori.adventure.text.Component text) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = target.getEyeLocation().add(r.nextDouble(-0.4, 0.4), 0.3 + r.nextDouble(0, 0.3), r.nextDouble(-0.4, 0.4));
        TextDisplay display = target.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setShadowed(true);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            t.setTeleportDuration(10);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (display.isValid()) display.teleport(display.getLocation().add(0, 0.6, 0));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, display::remove, 16L);
    }

    /** 5, 4.5, 120: one decimal only when it has one. */
    static String format(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.floor(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }
}
