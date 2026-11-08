package fr.influencraft.fluxdungeons.hero;

import java.util.UUID;

/** A player playing his class inside a dungeon: level, experience and mana (abilities cost mana, nothing else). */
public final class Hero {

    private final UUID uuid;
    private final HeroClass heroClass;
    private int level;
    private double xp;
    private double mana;

    Hero(UUID uuid, HeroClass heroClass, int level, double xp) {
        this.uuid = uuid;
        this.heroClass = heroClass;
        this.level = level;
        this.xp = xp;
        this.mana = heroClass.mana();
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

}
