package fr.influencraft.fluxdungeons.api;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Fighting in the rpg universe from another plugin (FluxTopDown's spells): every hit and heal still goes through the
 * rpg combat engine of FluxDungeons (stats, defenses, crits, dungeon rules, damage numbers). Absent when the rpg
 * module does not run: {@link #available()} false, hits land nowhere.
 */
public final class Combat {

    public interface Service {
        /** He fights with an rpg class here (stats known). */
        boolean fighter(Player player);

        /** Creatures a player may hurt around a point (no players, a model's hit box counted as its creature). */
        List<LivingEntity> enemies(Location at, double radius);

        /** A new cast id: one cast hits each target once. */
        String cast(Player player, String slot);

        /**
         * A hit of {@code coefficient} times his attack ({@code magic}: his magic power) with an optional element.
         * Returns the damage dealt, 0 when dodged, -1 when it did not land (refused, already hit by this cast).
         */
        double strike(Player attacker, LivingEntity target, boolean magic, double coefficient, String element, String cast);

        /** Heals him by {@code coefficient} times his magic power (healing power counted), shown above him. */
        void heal(Player player, double coefficient);

        /** Seconds between two basic attacks at his attack speed. */
        double attackInterval(Player player);

        /** His clicks and keys belong to the caller: the class's own attack and skills stop reacting to them. */
        void takeControls(Player player, boolean taken);
    }

    private static volatile Service service;

    private Combat() {
    }

    public static void register(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    public static boolean fighter(Player player) {
        Service s = service;
        return s != null && s.fighter(player);
    }

    public static List<LivingEntity> enemies(Location at, double radius) {
        Service s = service;
        return s == null ? List.of() : s.enemies(at, radius);
    }

    public static String cast(Player player, String slot) {
        Service s = service;
        return s == null ? null : s.cast(player, slot);
    }

    public static double strike(Player attacker, LivingEntity target, boolean magic, double coefficient, String element, String cast) {
        Service s = service;
        return s == null ? -1 : s.strike(attacker, target, magic, coefficient, element, cast);
    }

    public static void heal(Player player, double coefficient) {
        Service s = service;
        if (s != null) s.heal(player, coefficient);
    }

    public static void takeControls(Player player, boolean taken) {
        Service s = service;
        if (s != null) s.takeControls(player, taken);
    }

    public static double attackInterval(Player player) {
        Service s = service;
        return s == null ? 1 : s.attackInterval(player);
    }
}
