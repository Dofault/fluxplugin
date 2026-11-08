package fr.influencraft.fluxdungeons.combat;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Life bars above creatures: their name (and level) on a line, then a red bar with their life written inside in
 * QuinqueFive. Drawn by a font of the resource pack ({@code FluxDungeons/assets/gen_healthbar.py}): bar pictures
 * U+E000 + step, digits, spaces that move the text back or forward.
 * <p>
 * Two text displays ride the creature: the name and the bar, then the life alone, a hair in front (a single text would
 * draw the digits in the same plane as the bar: both flickered over each other). Neither is saved; a lost one is put
 * back at the next update.
 * <p>
 * Dungeon monsters get theirs from FluxDungeons ({@link #attach}). With {@code health-bar.all-mobs}, every other
 * creature near a player gets one too (name translated by each client, level of LevelledMobs when it has one): a rule
 * of the whole server, carried by FluxDungeons as the attack cadence.
 */
public final class HealthBars implements Listener {

    // numbers of the font, the same as gen_healthbar.py
    private static final int STEPS = 32, BAR_ADVANCE = 63, BAR_WIDTH = 62, DIGIT_ADVANCE = 6;
    /** How far in front of the bar the life is drawn (blocks, towards the viewer). */
    private static final float FRONT = 0.02f;
    private static final String TAG = "fluxdungeons_bar";
    /** Creatures this close to a player get a bar ({@code all-mobs}); dropped beyond {@link #DROP}. */
    private static final double NEAR = 16, DROP = 28;

    private final class Bar {
        final LivingEntity entity;
        final Function<LivingEntity, Component> head;
        final boolean world;
        TextDisplay main, life;
        int step = -1;
        double health = -1;

        Bar(LivingEntity entity, Function<LivingEntity, Component> head, boolean world) {
            this.entity = entity;
            this.head = head;
            this.world = world;
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Bar> bars = new HashMap<>();
    private final NamespacedKey levelledMobsLevel = new NamespacedKey("levelledmobs", "level");
    private boolean enabled = true, allMobs;
    private Key font = Key.key("fluxdungeons", "health");
    /** Creatures the world bars leave alone (dungeon monsters and pets: FluxDungeons draws or hides theirs). */
    private Predicate<Entity> skip = e -> false;

    public HealthBars(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void skip(Predicate<Entity> skip) {
        this.skip = skip;
    }

    /** {@code health-bar} of the config. */
    public void load(org.bukkit.configuration.ConfigurationSection c) {
        enabled = c == null || c.getBoolean("enabled", true);
        allMobs = c != null && c.getBoolean("all-mobs", false);
        try {
            font = Key.key(c == null ? "fluxdungeons:health" : c.getString("font", "fluxdungeons:health"));
        } catch (RuntimeException e) {
            font = Key.key("fluxdungeons", "health");
        }
        if (!enabled) clearAll();
        else if (!allMobs) bars.values().removeIf(b -> {
            if (b.world) drop(b);
            return b.world;
        });
        for (Bar b : bars.values()) b.step = -1;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Puts a bar on a creature (a dungeon monster), with its own head line. */
    public void attach(LivingEntity entity, Function<LivingEntity, Component> head) {
        if (!enabled) return;
        Bar old = bars.remove(entity.getUniqueId());
        if (old != null) drop(old);
        Bar b = new Bar(entity, head, false);
        bars.put(entity.getUniqueId(), b);
        update(b);
    }

    /** Draws a creature's bar again if its life changed (cheap when nothing changed). */
    public void update(LivingEntity entity) {
        Bar b = bars.get(entity.getUniqueId());
        if (b != null) update(b);
    }

    /** The creature's life just changed: its bar follows on the next tick, once the change is applied. */
    public void changed(LivingEntity entity) {
        Bar b = bars.get(entity.getUniqueId());
        if (b == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.entity.isValid() && bars.get(b.entity.getUniqueId()) == b) update(b);
        });
    }

    public void remove(Entity entity) {
        Bar b = bars.remove(entity.getUniqueId());
        if (b != null) drop(b);
    }

    public void clearAll() {
        for (Bar b : bars.values()) drop(b);
        bars.clear();
    }

    // ---------------------------------------------------------------- drawing

    private void update(Bar b) {
        LivingEntity e = b.entity;
        if (!e.isValid() || e.isDead()) return;
        if (!riding(b.main, e) || !riding(b.life, e)) {
            if (b.main != null) b.main.remove();
            if (b.life != null) b.life.remove();
            b.main = display(e, 0);
            b.life = display(e, FRONT);
            b.step = -1;
        }
        double max = maxHealth(e);
        double health = Math.max(0, e.getHealth());
        int step = (int) Math.floor(Math.min(1, health / max) * STEPS);
        if (step == b.step && Math.abs(health - b.health) < 0.5) return;
        b.step = step;
        b.health = health;
        Component head = b.head.apply(e);
        String number = shortNumber(health);
        int width = DIGIT_ADVANCE * number.length() - 1;
        int start = (BAR_WIDTH - width) / 2;
        // same two lines in both displays, so the life sits exactly on the bar: name + bar, then blank + life
        b.main.text(Component.empty().append(head).append(Component.newline()).append(glyphs(String.valueOf((char) (0xE000 + step)))));
        StringBuilder life = new StringBuilder();
        space(life, start);
        life.append(number);
        space(life, BAR_ADVANCE - (start + DIGIT_ADVANCE * number.length()));
        b.life.text(Component.text(" ").append(Component.newline()).append(glyphs(life.toString())));
    }

    private Component glyphs(String text) {
        return Component.text(text).font(font).color(NamedTextColor.WHITE).shadowColor(ShadowColor.shadowColor(0));
    }

    private static boolean riding(TextDisplay d, LivingEntity e) {
        return d != null && d.isValid() && e.getPassengers().contains(d);
    }

    private TextDisplay display(LivingEntity e, float front) {
        TextDisplay d = e.getWorld().spawn(e.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setSeeThrough(false);
            t.setViewRange(0.45f);
            t.setLineWidth(400);
            t.setTransformation(new Transformation(new Vector3f(0, 0.25f, front), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            t.addScoreboardTag(TAG);
        });
        e.addPassenger(d);
        return d;
    }

    private static void drop(Bar b) {
        if (b.main != null) b.main.remove();
        if (b.life != null) b.life.remove();
    }

    private static double maxHealth(LivingEntity e) {
        var a = e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return Math.max(1, a == null ? 20 : a.getValue());
    }

    /** Life as written in the bar: 143, 12K, 3M. */
    private static String shortNumber(double health) {
        long h = (long) Math.ceil(health);
        if (h < 10_000) return String.valueOf(h);
        if (h < 10_000_000) return (h / 1000) + "K";
        return (h / 1_000_000) + "M";
    }

    /** Moves the text by {@code pixels} (negative: back) with the space chars of the font. */
    private static void space(StringBuilder sb, int pixels) {
        int base = pixels < 0 ? 0xF001 : 0xF011;
        int left = Math.abs(pixels);
        for (int bit = 6; bit >= 0; bit--) {
            while (left >= (1 << bit)) {
                sb.append((char) (base + bit));
                left -= 1 << bit;
            }
        }
    }

    // ---------------------------------------------------------------- every creature (all-mobs)

    /** Twice a second: bars kept up to date; with all-mobs, creatures near players get one, far ones lose it. */
    private void tick() {
        if (!enabled) return;
        for (Iterator<Bar> it = bars.values().iterator(); it.hasNext(); ) {
            Bar b = it.next();
            if (!b.entity.isValid() || b.entity.isDead() || b.world && !nearPlayer(b.entity, DROP)) {
                drop(b);
                it.remove();
                continue;
            }
            update(b);
        }
        if (!allMobs) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Entity e : p.getNearbyEntities(NEAR, NEAR / 2, NEAR)) {
                if (!(e instanceof LivingEntity le) || bars.containsKey(le.getUniqueId()) || !worldBar(le)) continue;
                Bar b = new Bar(le, this::worldHead, true);
                bars.put(le.getUniqueId(), b);
                update(b);
            }
        }
    }

    /** A creature of the world that gets a bar: not a player, not a decoration, not hidden, not already named. */
    private boolean worldBar(LivingEntity le) {
        if (le instanceof Player || le instanceof ArmorStand || le.isInvisible() || le.isDead()) return false;
        if (le.isCustomNameVisible() || skip.test(le)) return false;
        return true;
    }

    /** Name of a world creature in the viewer's language, with its LevelledMobs level when it has one. */
    private Component worldHead(LivingEntity le) {
        Component name = Component.translatable(le.getType().translationKey()).color(NamedTextColor.WHITE);
        Integer level = le.getPersistentDataContainer().get(levelledMobsLevel, PersistentDataType.INTEGER);
        if (level == null || level <= 0) return name;
        return name.append(Component.text(" Nv. ", NamedTextColor.GRAY)).append(Component.text(level, NamedTextColor.WHITE));
    }

    private static boolean nearPlayer(Entity e, double distance) {
        double d2 = distance * distance;
        for (Player p : e.getWorld().getPlayers()) if (p.getLocation().distanceSquared(e.getLocation()) <= d2) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof LivingEntity le) changed(le);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof LivingEntity le) changed(le);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        remove(e.getEntity());
    }
}
