package fr.influencraft.fluxdungeons.combat;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Effects of the dungeons, kept by FluxDungeons instead of vanilla potions (exact strength, exact length, nothing
 * shown in the inventory, no particles around the player): {@link #slow} and {@link #haste} change the speed,
 * {@link #guard} cuts the damage taken, {@link #might} raises the damage dealt (read by {@link CombatRules}). A new
 * effect of the same kind keeps the strongest strength and the latest end. Blindness stays the vanilla one: nothing
 * else darkens a screen.
 */
public final class Statuses {

    private enum Kind { SLOW, HASTE, GUARD, MIGHT }

    private record Effect(double strength, int until) {
    }

    private static final Map<UUID, Map<Kind, Effect>> ACTIVE = new HashMap<>();
    private static final Map<UUID, LivingEntity> HOLDERS = new HashMap<>();
    private static NamespacedKey slowKey, hasteKey;

    private Statuses() {
    }

    public static void start(Plugin plugin) {
        slowKey = new NamespacedKey(plugin, "status_slow");
        hasteKey = new NamespacedKey(plugin, "status_haste");
        Bukkit.getScheduler().runTaskTimer(plugin, Statuses::tick, 2L, 2L);
    }

    /** Slower by {@code share} (0.3 = 30 % slower) for {@code ticks}. */
    public static void slow(LivingEntity e, double share, int ticks) {
        add(e, Kind.SLOW, Math.max(0, Math.min(0.95, share)), ticks);
    }

    /** Faster by {@code share} for {@code ticks}. */
    public static void haste(LivingEntity e, double share, int ticks) {
        add(e, Kind.HASTE, Math.max(0, share), ticks);
    }

    /** Takes {@code share} less damage (0.4 = 40 % less) for {@code ticks}. */
    public static void guard(LivingEntity e, double share, int ticks) {
        add(e, Kind.GUARD, Math.max(0, Math.min(0.95, share)), ticks);
    }

    /** Deals {@code share} more damage (0.3 = 30 % more) for {@code ticks}. */
    public static void might(LivingEntity e, double share, int ticks) {
        add(e, Kind.MIGHT, Math.max(0, share), ticks);
    }

    /** Multiplier of the damage this entity takes now. */
    public static double taken(Entity e) {
        Effect g = get(e, Kind.GUARD);
        return g == null ? 1 : 1 - g.strength();
    }

    /** Multiplier of the damage this entity deals now. */
    public static double dealt(Entity e) {
        Effect m = get(e, Kind.MIGHT);
        return m == null ? 1 : 1 + m.strength();
    }

    /** Every effect taken off (leaving a run, death). */
    public static void clear(LivingEntity e) {
        ACTIVE.remove(e.getUniqueId());
        HOLDERS.remove(e.getUniqueId());
        speed(e, Kind.SLOW, 0);
        speed(e, Kind.HASTE, 0);
    }

    private static void add(LivingEntity e, Kind kind, double strength, int ticks) {
        if (ticks <= 0 || strength <= 0) return;
        int until = Bukkit.getCurrentTick() + ticks;
        Map<Kind, Effect> mine = ACTIVE.computeIfAbsent(e.getUniqueId(), k -> new HashMap<>());
        Effect old = mine.get(kind);
        Effect now = old == null ? new Effect(strength, until) : new Effect(Math.max(old.strength(), strength), Math.max(old.until(), until));
        mine.put(kind, now);
        HOLDERS.put(e.getUniqueId(), e);
        if (kind == Kind.SLOW || kind == Kind.HASTE) speed(e, kind, now.strength());
    }

    private static Effect get(Entity e, Kind kind) {
        Map<Kind, Effect> mine = e == null ? null : ACTIVE.get(e.getUniqueId());
        Effect f = mine == null ? null : mine.get(kind);
        return f == null || f.until() < Bukkit.getCurrentTick() ? null : f;
    }

    private static void speed(LivingEntity e, Kind kind, double strength) {
        AttributeInstance inst = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (inst == null) return;
        NamespacedKey key = kind == Kind.SLOW ? slowKey : hasteKey;
        inst.removeModifier(key);
        if (strength > 0) inst.addModifier(new AttributeModifier(key, kind == Kind.SLOW ? -strength : strength,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    private static void tick() {
        int now = Bukkit.getCurrentTick();
        for (Iterator<Map.Entry<UUID, Map<Kind, Effect>>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<Kind, Effect>> en = it.next();
            LivingEntity e = HOLDERS.get(en.getKey());
            for (Iterator<Map.Entry<Kind, Effect>> ef = en.getValue().entrySet().iterator(); ef.hasNext(); ) {
                Map.Entry<Kind, Effect> f = ef.next();
                if (f.getValue().until() >= now) continue;
                ef.remove();
                if (e != null && e.isValid() && (f.getKey() == Kind.SLOW || f.getKey() == Kind.HASTE)) speed(e, f.getKey(), 0);
            }
            if (en.getValue().isEmpty() || e == null || !e.isValid()) {
                if (e != null && e.isValid()) {
                    speed(e, Kind.SLOW, 0);
                    speed(e, Kind.HASTE, 0);
                }
                it.remove();
                HOLDERS.remove(en.getKey());
            }
        }
    }
}
