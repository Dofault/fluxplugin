package fr.influencraft.fluxdungeons.mob;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Normalized combat of imported dungeons, after EliteMobs' model: a monster's life and damage are counted in hits
 * rather than in absolute points, so a dungeon is as hard at level 3 as at level 30 for a player of that level, and the
 * level gap (class level against monster level) makes it easier or harder, doubling every {@code levels-per-double}.
 * <ul>
 *   <li>Life: {@code reference-hit × hits-to-kill-mob × health-multiplier}.</li>
 *   <li>Damage to a player: {@code reference-health / hits-to-kill-player × damage-multiplier × gap}, then armour
 *   applies as usual: better armour, fewer hits taken.</li>
 *   <li>Damage of a player: what his weapon and class deal × gap (the other way round).</li>
 * </ul>
 */
public final class Combat {

    private static double referenceHit = 7, hitsToKillMob = 3, referenceHealth = 20, hitsToKillPlayer = 5;
    private static double levelsPerDouble = 7.5, minFactor = 0.25, maxFactor = 4;

    private Combat() {
    }

    public static void load(ConfigurationSection c) {
        if (c == null) return;
        referenceHit = c.getDouble("reference-hit", 7);
        hitsToKillMob = c.getDouble("hits-to-kill-mob", 3);
        referenceHealth = c.getDouble("reference-health", 20);
        hitsToKillPlayer = c.getDouble("hits-to-kill-player", 5);
        levelsPerDouble = Math.max(1, c.getDouble("levels-per-double", 7.5));
        minFactor = c.getDouble("min-factor", 0.25);
        maxFactor = c.getDouble("max-factor", 4);
    }

    /** Life of a normalized monster. */
    public static double health(double healthMultiplier) {
        return Math.max(1, referenceHit * hitsToKillMob * healthMultiplier);
    }

    /** Damage of a normalized monster's hit (multiplier 1) before the level gap, before armour. */
    public static double hit(double damageMultiplier) {
        return referenceHealth / hitsToKillPlayer * damageMultiplier;
    }

    /** Factor of a monster's damage on a player: above 1 when the monster is the stronger. */
    public static double defensive(int mobLevel, int playerLevel) {
        return clamp(Math.pow(2, (mobLevel - playerLevel) / levelsPerDouble));
    }

    /** Factor of a player's damage on a monster: above 1 when the player is the stronger. */
    public static double offensive(int playerLevel, int mobLevel) {
        return clamp(Math.pow(2, (playerLevel - mobLevel) / levelsPerDouble));
    }

    private static double clamp(double f) {
        return Math.max(minFactor, Math.min(maxFactor, f));
    }
}
