package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.Modifier;
import fr.influencraft.fluxdungeons.rpg.core.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A player in the rpg universe while he is online: class, level, mana, cooldowns, timed bonuses and his
 * computed stats. Main thread only. His life is the game's own health (scaled to ten hearts on screen), changed only
 * by {@link CombatEngine}.
 */
public final class RpgPlayer {

    /** A bonus for some time (war cry, speed after a leap...): its modifiers count until {@code until} (tick). */
    record Buff(String id, List<Modifier> modifiers, long until) {
    }

    private final UUID uuid;
    private ClassDef heroClass;
    private int level;
    private double xp;
    private double mana;
    private StatSheet stats;
    private final Map<String, Long> ready = new HashMap<>();
    private long nextBasic;
    private long lastHurt = -10_000, lastAttack;
    private final List<Buff> buffs = new ArrayList<>();
    private int casts;

    RpgPlayer(UUID uuid, ClassDef heroClass, int level, double xp) {
        this.uuid = uuid;
        this.heroClass = heroClass;
        this.level = level;
        this.xp = xp;
    }

    public UUID uuid() {
        return uuid;
    }

    public ClassDef heroClass() {
        return heroClass;
    }

    void heroClass(ClassDef c) {
        heroClass = c;
        ready.clear();
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

    public StatSheet stats() {
        return stats;
    }

    void stats(StatSheet stats) {
        this.stats = stats;
        mana = Math.min(mana, stats.get("max_mana"));
    }

    public double mana() {
        return mana;
    }

    void mana(double mana) {
        this.mana = Math.max(0, stats == null ? mana : Math.min(stats.get("max_mana"), mana));
    }

    long readyAt(String slot) {
        return ready.getOrDefault(slot, 0L);
    }

    void cooldown(String slot, long until) {
        ready.put(slot, until);
    }

    long nextBasic() {
        return nextBasic;
    }

    void nextBasic(long tick) {
        nextBasic = tick;
    }

    long lastHurt() {
        return lastHurt;
    }

    void hurt(long tick) {
        lastHurt = tick;
    }

    long lastAttack() {
        return lastAttack;
    }

    void attacked(long tick) {
        lastAttack = tick;
    }

    /** A new cast id: one hit per target per cast. */
    String cast(String slot) {
        return uuid + ":" + slot + ":" + (++casts);
    }

    void buff(String id, List<Modifier> modifiers, long until) {
        buffs.removeIf(b -> b.id().equals(id));
        buffs.add(new Buff(id, modifiers, until));
    }

    /** Modifiers of the bonuses still running; drops the ended ones (true in {@code ended} when one ended). */
    List<Modifier> buffModifiers(long tick, boolean[] ended) {
        List<Modifier> out = new ArrayList<>();
        for (Iterator<Buff> it = buffs.iterator(); it.hasNext(); ) {
            Buff b = it.next();
            if (b.until() <= tick) {
                it.remove();
                if (ended != null) ended[0] = true;
            } else out.addAll(b.modifiers());
        }
        return out;
    }
}
