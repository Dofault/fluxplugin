package fr.influencraft.fluxdungeons.hero;

import java.util.UUID;

/** A player playing his class inside a dungeon: level, experience, mana and ability cooldowns. */
public final class Hero {

    private final UUID uuid;
    private final HeroClass heroClass;
    private int level;
    private double xp;
    private double mana;
    private final long[] readyAt;

    Hero(UUID uuid, HeroClass heroClass, int level, double xp) {
        this.uuid = uuid;
        this.heroClass = heroClass;
        this.level = level;
        this.xp = xp;
        this.mana = heroClass.mana();
        this.readyAt = new long[heroClass.abilities().size()];
    }

    public UUID uuid() {
        return uuid;
    }

    public HeroClass heroClass() {
        return heroClass;
    }

    public int level() {
        return level;
    }

    void level(int level) {
        this.level = level;
    }

    public double xp() {
        return xp;
    }

    void xp(double xp) {
        this.xp = xp;
    }

    public double mana() {
        return mana;
    }

    void mana(double mana) {
        this.mana = Math.max(0, Math.min(heroClass.mana(), mana));
    }

    /** Seconds before the ability can be cast again (0 when ready). */
    public double cooldown(int ability) {
        return Math.max(0, (readyAt[ability] - System.currentTimeMillis()) / 1000.0);
    }

    void cooldown(int ability, double seconds) {
        readyAt[ability] = System.currentTimeMillis() + (long) (seconds * 1000);
    }
}
