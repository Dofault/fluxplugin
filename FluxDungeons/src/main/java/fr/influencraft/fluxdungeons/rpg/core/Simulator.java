package fr.influencraft.fluxdungeons.rpg.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fights played in numbers only, to check balance.yml against its targets (tests, and {@code /rpgadmin balance}
 * in game). A fighter attacks at his attack speed and casts a damage skill whenever he can pay it (no cooldown: mana
 * is the only limit), at most one every {@link #CAST_GAP} seconds (his fingers), the most damage per mana first;
 * monsters hit at their attack speed. No movement, no dodging of telegraphed attacks: it measures raw numbers, the
 * real fight is longer.
 *
 * <p>Monsters may stand together (a <b>pack</b>): a skill then hits as many of them as its {@code targets}, and the
 * mechanic of each elemental class plays as in game, from the {@code passive} numbers of the class:</p>
 * <ul>
 *   <li>frost ({@code chill-max}): stacks, freeze, shatter on the next cast, shards on the neighbours (chain), life
 *   back on a shatter;</li>
 *   <li>fire ({@code burn-max}): burn stacks ticking every second, wildfire when a burning monster dies, heat as
 *   bonus damage, the blaze (faster basic attack hitting several monsters, life back when it starts);</li>
 *   <li>plants ({@code seed-max}): seeds, bloom (hit, heal counted for the fighter's survival).</li>
 * </ul>
 * A skill lasting {@code duration} seconds spreads its {@code hits} over it; {@code extra.final} is one last hit.
 */
public final class Simulator {

    private static final double STEP = 0.05, LIMIT = 900, CAST_GAP = 0.5;
    /** Monsters around one that its shards, flames or pollen reach, at most (they do not all stand together). */
    private static final int NEIGHBOURS = 3;

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

    // ------------------------------------------------------------------ the fight

    private static final class Mob {
        double hp;
        // frost
        int chill;
        double frozenUntil = -1;
        int frozenBy;
        // fire
        int burn;
        double burnUntil, nextBurn;
        int burnOwner;
        // plants
        int seeds;
        double nextBloom;

        boolean alive() {
            return hp > 0;
        }
    }

    private static final class Fighter {
        final ClassDef cls;
        final StatSheet sheet;
        double mana, nextBasic, nextCast;
        double heat, lastHeat = -100, blazeUntil = -1, lastMend = -100;
        double healed;
        int casts;

        Fighter(ClassDef cls, StatSheet sheet) {
            this.cls = cls;
            this.sheet = sheet;
            this.mana = sheet.get("max_mana");
        }
    }

    /** A hit of a skill still to come (a zone that lasts). */
    private record Pending(double at, int fighter, ClassDef.Skill skill, int cast, int index, boolean last) {
    }

    private final class Fight {
        final int level;
        final Random rng;
        final StatSheet mobSheet;
        final List<Fighter> fighters = new ArrayList<>();
        final List<Mob> mobs = new ArrayList<>();
        final List<Pending> pending = new ArrayList<>();
        final boolean immortal;
        double t, dealt;

        Fight(List<ClassDef> party, int level, StatSheet mobSheet, int alive, boolean immortal, long seed) {
            this.level = level;
            this.rng = new Random(seed);
            this.mobSheet = mobSheet;
            this.immortal = immortal;
            for (ClassDef c : party) fighters.add(new Fighter(c, player(c, level)));
            for (int i = 0; i < alive; i++) spawn();
        }

        void spawn() {
            Mob m = new Mob();
            m.hp = mobSheet.get("max_health");
            mobs.add(m);
        }

        List<Mob> alive() {
            List<Mob> out = new ArrayList<>();
            for (Mob m : mobs) if (m.alive()) out.add(m);
            return out;
        }

        /** One step of every fighter and of what burns. */
        void step() {
            for (int i = 0; i < fighters.size(); i++) {
                Fighter f = fighters.get(i);
                f.mana = Math.min(f.sheet.get("max_mana"), f.mana + f.sheet.get("mana_regen") * STEP);
                heatTick(f);
                if (t >= f.nextBasic) basic(i, f);
                cast(i, f);
            }
            for (int k = pending.size() - 1; k >= 0; k--) {
                Pending p = pending.get(k);
                if (p.at() > t) continue;
                pending.remove(k);
                Fighter f = fighters.get(p.fighter());
                if (p.last()) lastHit(p.fighter(), f, p.skill(), p.cast());
                else skillHit(p.fighter(), f, p.skill(), p.cast(), p.index());
            }
            burns();
            t += STEP;
        }

        // ---- attacks

        void basic(int id, Fighter f) {
            boolean blaze = t < f.blazeUntil;
            double speed = f.sheet.get("attack_speed") + (blaze ? f.cls.passive("blaze-speed", 0) : 0);
            f.nextBasic = t + 1 / Math.max(0.01, speed);
            List<Mob> targets = alive();
            int n = Math.min(targets.size(), blaze ? (int) Math.max(1, f.cls.passive("blaze-bolts", 1)) : Math.max(1, f.cls.basic().targets()));
            int cast = ++f.casts * 100;
            for (int k = 0; k < n; k++) hit(id, f, targets.get(k), f.cls.basic().hit(), cast, true, null);
        }

        void cast(int id, Fighter f) {
            if (t < f.nextCast) return;
            int alive = alive().size();
            if (alive == 0) return;
            ClassDef.Skill best = null;
            double bestValue = -1;
            for (String slot : ClassDef.SLOTS) {
                ClassDef.Skill sk = f.cls.skills().get(slot);
                if (sk == null || !sk.kind().equals("damage")) continue;
                double cost = CombatMath.manaCost(sk.mana(), f.sheet);
                if (cost > f.mana) continue;
                double value = (sk.coefficient() * sk.hits() + sk.number("final", 0)) * Math.min(alive, sk.targets()) / Math.max(1, cost);
                if (value > bestValue) {
                    bestValue = value;
                    best = sk;
                }
            }
            if (best == null) return;
            f.mana -= CombatMath.manaCost(best.mana(), f.sheet);
            f.nextCast = t + CAST_GAP;
            int cast = ++f.casts;
            boolean lasting = best.duration() > 0 && best.hits() > 1;
            double gap = lasting ? best.duration() / best.hits() : 0;
            for (int h = 0; h < best.hits(); h++) {
                if (lasting) pending.add(new Pending(t + gap * (h + 1), id, best, cast * 100 + h, h, false));
                else skillHit(id, f, best, cast * 100 + h, h);
            }
            if (best.number("final", 0) > 0) {
                if (lasting) pending.add(new Pending(t + best.duration() + 0.3, id, best, cast * 100 + 99, 0, true));
                else lastHit(id, f, best, cast * 100 + 99);
            }
            // the maneater withers into healing pollen
            if (best.number("heal", 0) > 0) f.healed += CombatMath.heal(f.sheet, best.number("heal", 0), 0);
        }

        void skillHit(int id, Fighter f, ClassDef.Skill sk, int cast, int index) {
            List<Mob> targets = alive();
            int n = Math.min(targets.size(), sk.targets());
            for (int k = 0; k < n; k++) hit(id, f, targets.get(k), sk.hit(), cast, false, sk, index);
        }

        void lastHit(int id, Fighter f, ClassDef.Skill sk, int cast) {
            List<Mob> targets = alive();
            int n = Math.min(targets.size(), sk.targets());
            CombatMath.Hit hit = new CombatMath.Hit(sk.damage(), sk.element(), sk.number("final", 0), 0, true, true);
            for (int k = 0; k < n; k++) {
                Mob m = targets.get(k);
                if (strike(f, m, hit) && f.cls.passive().containsKey("burn-max")) ignite(id, f, m, (int) sk.number("final-burn", 2));
            }
        }

        void hit(int id, Fighter f, Mob m, CombatMath.Hit hit, int cast, boolean basic, ClassDef.Skill sk) {
            hit(id, f, m, hit, cast, basic, sk, 0);
        }

        /** One hit of a fighter and what his class's mechanic makes of it. */
        void hit(int id, Fighter f, Mob m, CombatMath.Hit hit, int cast, boolean basic, ClassDef.Skill sk, int index) {
            if (!m.alive()) return;
            if (!strike(f, m, hit)) return;
            ClassDef c = f.cls;
            if (c.passive().containsKey("burn-max")) {
                heat(f, c.passive(basic ? "heat-basic" : "heat-spell", basic ? 6 : 3));
                if (m.alive()) ignite(id, f, m, basic ? 1 : (int) sk.number("burn", 1));
            } else if (c.passive().containsKey("chill-max")) {
                if (!m.alive()) return;
                int root = cast / 100;
                if (m.frozenUntil > t && m.frozenBy != root) shatter(id, f, m, root, 0);
                else if (m.frozenUntil <= t) chill(f, m, sk != null && sk.type().equals("winter_comet") ? 99 : basic ? 1 : (int) sk.number("chill", 1), root);
            } else if (c.passive().containsKey("seed-max")) {
                if (!m.alive()) return;
                int planted = basic ? 1 : sk.type().equals("brambles") ? ((index + 1) % (int) Math.max(1, sk.number("seed-every", 2)) == 0 ? 1 : 0) : (int) sk.number("seed", 1);
                seed(f, m, planted, 0);
            }
        }

        /** The damage chain; false when dodged. Deaths are handled here (wildfire). */
        boolean strike(Fighter f, Mob m, CombatMath.Hit hit) {
            if (!m.alive()) return false;
            CombatMath.Outcome o = CombatMath.hit(balance, f.sheet, level, mobSheet, null, hit, rng::nextDouble);
            if (o.dodged()) return false;
            double bonus = f.cls.passive().containsKey("burn-max")
                    ? f.cls.passive("heat-damage", 0) * (t < f.blazeUntil ? 1 : Math.floor(f.heat / 10) / 10) : 0;
            take(m, o.damage() * (1 + bonus));
            return true;
        }

        void take(Mob m, double damage) {
            dealt += damage;
            if (immortal) return;
            boolean was = m.alive();
            m.hp -= damage;
            if (was && !m.alive() && m.burn > 0) wildfire(m);
        }

        // ---- frost

        void chill(Fighter f, Mob m, int stacks, int root) {
            int max = (int) Math.max(1, f.cls.passive("chill-max", 3));
            m.chill = Math.min(max, m.chill + stacks);
            if (m.chill >= max) {
                m.chill = 0;
                m.frozenUntil = t + f.cls.passive("freeze", 1.6);
                m.frozenBy = root;
            }
        }

        void shatter(int id, Fighter f, Mob m, int root, int depth) {
            m.frozenUntil = -1;
            if (depth == 0 && t - f.lastMend >= 1) {
                f.lastMend = t;
                f.healed += f.cls.passive("shatter-heal", 0) * f.sheet.get("max_health");
            }
            double coefficient = f.cls.passive("shatter", 1.4);
            strike(f, m, CombatMath.Hit.magic(coefficient, "frost"));
            CombatMath.Hit splash = CombatMath.Hit.magic(coefficient * f.cls.passive("shatter-splash", 0.35), "frost");
            int reached = 0;
            for (Mob other : alive()) {
                if (other == m || reached++ >= NEIGHBOURS) continue;
                if (!strike(f, other, splash) || !other.alive()) continue;
                if (other.frozenUntil > t && depth < 6) shatter(id, f, other, root, depth + 1);
                else if (other.frozenUntil <= t) chill(f, other, 1, -root - 1);
            }
        }

        // ---- fire

        void ignite(int id, Fighter f, Mob m, int stacks) {
            if (stacks <= 0) return;
            int max = (int) Math.max(1, f.cls.passive("burn-max", 5));
            if (m.burn == 0) m.nextBurn = t + 1;
            m.burn = Math.min(max, m.burn + stacks);
            m.burnUntil = t + f.cls.passive("burn-seconds", 5);
            m.burnOwner = id;
        }

        void burns() {
            for (Mob m : mobs) {
                if (!m.alive() || m.burn == 0) continue;
                if (t >= m.burnUntil) {
                    m.burn = 0;
                    continue;
                }
                if (t < m.nextBurn) continue;
                m.nextBurn = t + 1;
                Fighter f = fighters.get(m.burnOwner);
                strike(f, m, new CombatMath.Hit(CombatMath.Type.MAGIC, "fire", f.cls.passive("burn", 0.1) * m.burn, 0, false, false));
            }
        }

        void wildfire(Mob dead) {
            Fighter f = fighters.get(dead.burnOwner);
            double max = Math.max(1, f.cls.passive("burn-max", 5));
            CombatMath.Hit hit = new CombatMath.Hit(CombatMath.Type.MAGIC, "fire", f.cls.passive("wildfire", 0.8) * dead.burn / max, 0, false, true);
            int stacks = (int) f.cls.passive("wildfire-stacks", 2);
            dead.burn = 0;
            int reached = 0;
            for (Mob other : alive()) {
                if (reached++ >= NEIGHBOURS) break;
                if (strike(f, other, hit) && other.alive()) ignite(dead.burnOwner, f, other, stacks);
            }
        }

        void heat(Fighter f, double amount) {
            f.lastHeat = t;
            if (t < f.blazeUntil) return;
            f.heat = Math.min(100, f.heat + amount);
            if (f.heat >= 100) {
                f.blazeUntil = t + f.cls.passive("blaze-seconds", 7);
                f.heat = 0;
                f.healed += f.cls.passive("blaze-heal", 0) * f.sheet.get("max_health");
            }
        }

        void heatTick(Fighter f) {
            if (f.heat > 0 && t >= f.blazeUntil && t - f.lastHeat > f.cls.passive("heat-decay-after", 4)) {
                f.heat = Math.max(0, f.heat - f.cls.passive("heat-decay", 12) * STEP);
            }
        }

        // ---- plants

        void seed(Fighter f, Mob m, int stacks, int depth) {
            if (stacks <= 0) return;
            int max = (int) Math.max(1, f.cls.passive("seed-max", 3));
            m.seeds = Math.min(max, m.seeds + stacks);
            if (m.seeds < max || t < m.nextBloom) return;
            m.seeds = 0;
            m.nextBloom = t + f.cls.passive("bloom-gap", 2);
            strike(f, m, CombatMath.Hit.magic(f.cls.passive("bloom", 1), "nature"));
            f.healed += CombatMath.heal(f.sheet, f.cls.passive("bloom-heal", 0.35), 0);
            if (depth > 4) return;
            int reached = 0;
            for (Mob other : alive()) {
                if (other == m || reached++ >= NEIGHBOURS) continue;
                seed(f, other, 1, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------ measures

    /** Seconds for these players (one per class given) to kill one monster. {@link #LIMIT} when they never do. */
    public double timeToKill(List<ClassDef> party, int level, String rank, long seed) {
        return timeToKill(party, level, rank, 1, seed);
    }

    /**
     * Average seconds per monster when these players kill {@code count} of them one after another (a wave): mana
     * carries over, so the opening burst does not hide the real pace. {@link #LIMIT} when they never finish.
     */
    public double timeToKill(List<ClassDef> party, int level, String rank, int count, long seed) {
        Fight f = new Fight(party, level, monster(level, rank), 1, false, seed);
        int left = count;
        while (f.t < LIMIT) {
            f.step();
            if (f.alive().isEmpty()) {
                if (--left == 0) return f.t / count;
                f.mobs.clear();
                f.spawn();
            }
        }
        return LIMIT;
    }

    /**
     * Seconds for these players to kill {@code size} monsters standing together (a pulled pack): area skills and the
     * classes' chain mechanics count. {@link #LIMIT} when they never finish.
     */
    public double packTime(List<ClassDef> party, int level, String rank, int size, long seed) {
        Fight f = new Fight(party, level, monster(level, rank), size, false, seed);
        while (f.t < LIMIT) {
            f.step();
            if (f.alive().isEmpty()) return f.t;
        }
        return LIMIT;
    }

    /**
     * Seconds for these players to kill the boss of their level: its life in balance.yml is for a party of four, each
     * player present brings a quarter of it (the rule of the dungeons).
     */
    public double bossTime(List<ClassDef> party, int level, long seed) {
        List<Modifier> m = new ArrayList<>(balance.monster(level, "boss"));
        m.add(Modifier.multiply("max_health", Math.min(4, party.size()) / 4.0 - 1, "party"));
        Fight f = new Fight(party, level, StatSheet.compute(stats, m), 1, false, seed);
        while (f.t < LIMIT) {
            f.step();
            if (f.alive().isEmpty()) return f.t;
        }
        return LIMIT;
    }

    public double averageBossTime(List<ClassDef> party, int level, int fights) {
        double sum = 0;
        for (int i = 0; i < fights; i++) sum += bossTime(party, level, 4000L + i);
        return sum / fights;
    }

    /**
     * Seconds for these players to clear a room: {@code packs} packs of {@code size} monsters, one pack after the
     * other, mana carried over (the opening burst does not hide the real pace). {@link #LIMIT} when they never finish.
     */
    public double roomTime(List<ClassDef> party, int level, int packs, int size, long seed) {
        Fight f = new Fight(party, level, monster(level, "normal"), size, false, seed);
        int left = packs;
        while (f.t < LIMIT) {
            f.step();
            if (f.alive().isEmpty()) {
                if (--left == 0) return f.t;
                f.mobs.clear();
                f.pending.clear();
                for (int i = 0; i < size; i++) f.spawn();
            }
        }
        return LIMIT;
    }

    public double averageRoomTime(List<ClassDef> party, int level, int packs, int size, int fights) {
        double sum = 0;
        for (int i = 0; i < fights; i++) sum += roomTime(party, level, packs, size, 5000L + i);
        return sum / fights;
    }

    public double averagePackTime(List<ClassDef> party, int level, String rank, int size, int fights) {
        double sum = 0;
        for (int i = 0; i < fights; i++) sum += packTime(party, level, rank, size, 3000L + i);
        return sum / fights;
    }

    /** Damage per second of one player on {@code size} monsters of his level that never die, over a minute. */
    public double damagePerSecond(ClassDef cls, int level, int size, long seed) {
        Fight f = new Fight(List.of(cls), level, monster(level, "normal"), size, true, seed);
        while (f.t < 60) f.step();
        return f.dealt / 60;
    }

    /** Life per second a player gives himself by fighting one monster (the druid's blooms), over a minute. */
    public double sustain(ClassDef cls, int level, long seed) {
        Fight f = new Fight(List.of(cls), level, monster(level, "normal"), 1, true, seed);
        while (f.t < 60) f.step();
        return f.fighters.get(0).healed / 60;
    }

    /**
     * Seconds for one monster to kill a player of this class who never dodges telegraphed attacks; he fights back, so
     * the life his own class gives him counts (health regeneration, the druid's blooms).
     */
    public double timeToDie(ClassDef cls, int level, String rank, long seed) {
        Random rng = new Random(seed);
        StatSheet mob = monster(level, rank);
        StatSheet player = player(cls, level);
        double hp = player.get("max_health");
        CombatMath.Hit hit = CombatMath.Hit.physical(1);
        double interval = CombatMath.attackInterval(mob);
        double regen = player.get("health_regen") + sustain(cls, level, seed);
        for (double t = 0; t < LIMIT; t += interval) {
            hp -= CombatMath.hit(balance, mob, level, player, null, hit, rng::nextDouble).damage();
            if (hp <= 0) return t;
            hp = Math.min(player.get("max_health"), hp + regen * interval);
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
