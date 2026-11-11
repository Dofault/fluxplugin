package fr.influencraft.fluxdungeons.rpg.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fights played in numbers only, to check balance.yml against its targets (tests, and {@code /rpgadmin balance}
 * in game). A fighter attacks at his attack speed and casts a damage skill whenever he can pay it (no cooldown: mana
 * is the only limit), at most one every {@link #CAST_GAP} seconds (his fingers), the most damage per mana first; monsters hit at their attack speed. Single target, no movement, no dodging of
 * telegraphed attacks: it measures raw numbers, the real fight is longer.
 */
public final class Simulator {

    private static final double STEP = 0.05, LIMIT = 900, CAST_GAP = 0.5;

    private final Balance balance;
    private final StatRegistry stats;

    public Simulator(Balance balance, StatRegistry stats) {
        this.balance = balance;
        this.stats = stats;
    }

    /** A player of a class at a level, with the reference gear of that level. */
    public StatSheet player(ClassDef cls, int level) {
        List<Modifier> m = new ArrayList<>(cls.modifiers(level));
        m.addAll(balance.referenceGear(cls.id(), level));
        return StatSheet.compute(stats, m);
    }

    public StatSheet monster(int level, String rank) {
        return StatSheet.compute(stats, balance.monster(level, rank));
    }

    /** Seconds for these players (one per class given) to kill one monster. {@link #LIMIT} when they never do. */
    public double timeToKill(List<ClassDef> party, int level, String rank, long seed) {
        return timeToKill(party, level, rank, 1, seed);
    }

    /**
     * Average seconds per monster when these players kill {@code count} of them one after another (a wave): cooldowns
     * and mana carry over, so the opening burst does not hide the real pace. {@link #LIMIT} when they never finish.
     */
    public double timeToKill(List<ClassDef> party, int level, String rank, int count, long seed) {
        Random rng = new Random(seed);
        StatSheet mob = monster(level, rank);
        double hp = mob.get("max_health");
        int left = count;
        int n = party.size();
        StatSheet[] sheets = new StatSheet[n];
        double[] mana = new double[n], nextBasic = new double[n];
        double[] nextCast = new double[n];
        for (int i = 0; i < n; i++) {
            sheets[i] = player(party.get(i), level);
            mana[i] = sheets[i].get("max_mana");
        }
        for (double t = 0; t < LIMIT; t += STEP) {
            for (int i = 0; i < n; i++) {
                StatSheet s = sheets[i];
                ClassDef c = party.get(i);
                mana[i] = Math.min(s.get("max_mana"), mana[i] + s.get("mana_regen") * STEP);
                if (t >= nextBasic[i]) {
                    hp -= CombatMath.hit(balance, s, level, mob, null, c.basic().hit(), rng::nextDouble).damage();
                    nextBasic[i] = t + CombatMath.attackInterval(s);
                }
                int best = -1;
                double bestValue = -1;
                for (int k = 0; k < ClassDef.SLOTS.size(); k++) {
                    ClassDef.Skill sk = c.skills().get(ClassDef.SLOTS.get(k));
                    if (sk == null || !sk.kind().equals("damage") || t < nextCast[i]) continue;
                    double cost = CombatMath.manaCost(sk.mana(), s);
                    if (cost > mana[i]) continue;
                    double value = sk.coefficient() * sk.hits() / Math.max(1, cost);
                    if (value > bestValue) {
                        bestValue = value;
                        best = k;
                    }
                }
                if (best >= 0) {
                    ClassDef.Skill sk = c.skills().get(ClassDef.SLOTS.get(best));
                    mana[i] -= CombatMath.manaCost(sk.mana(), s);
                    nextCast[i] = t + CAST_GAP;
                    for (int h = 0; h < sk.hits(); h++) hp -= CombatMath.hit(balance, s, level, mob, null, sk.hit(), rng::nextDouble).damage();
                }
            }
            if (hp <= 0) {
                if (--left == 0) return t / count;
                hp = mob.get("max_health");
            }
        }
        return LIMIT;
    }

    /** Seconds for one monster to kill a player of this class who never heals nor dodges telegraphed attacks. */
    public double timeToDie(ClassDef cls, int level, String rank, long seed) {
        Random rng = new Random(seed);
        StatSheet mob = monster(level, rank);
        StatSheet player = player(cls, level);
        double hp = player.get("max_health");
        CombatMath.Hit hit = CombatMath.Hit.physical(1);
        double interval = CombatMath.attackInterval(mob);
        for (double t = 0; t < LIMIT; t += interval) {
            hp -= CombatMath.hit(balance, mob, level, player, null, hit, rng::nextDouble).damage();
            hp = Math.min(player.get("max_health"), hp + player.get("health_regen") * interval);
            if (hp <= 0) return t;
        }
        return LIMIT;
    }

    /**
     * Average of several seeded fights (crits and dodges smoothed out). Normal monsters are fought in waves of ten
     * (they come in groups); other ranks one at a time, fresh.
     */
    public double averageTimeToKill(List<ClassDef> party, int level, String rank, int fights) {
        int wave = rank.equals("normal") ? 10 : 1;
        double sum = 0;
        for (int f = 0; f < fights; f++) sum += timeToKill(party, level, rank, wave, 1000L + f);
        return sum / fights;
    }

    public double averageTimeToDie(ClassDef cls, int level, String rank, int fights) {
        double sum = 0;
        for (int f = 0; f < fights; f++) sum += timeToDie(cls, level, rank, 2000L + f);
        return sum / fights;
    }

    public static double limit() {
        return LIMIT;
    }
}
