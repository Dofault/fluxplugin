package fr.influencraft.fluxdungeons.dungeon;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.pets.Companions;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.mob.Mobs;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pets taken into a run. Each one is a silent, invisible mob wearing the pet's model (a ground body, or a flying one
 * for pets with {@code flying}), whose vanilla behaviour is removed and replaced by ours ({@link Brain}, Paper's mob
 * goals): stay next to its owner, each pet at its own place around him; attack what the owner fights (what he hits,
 * what hits him), else the closest monster of the run near him; come back to him when too far. Nothing else: no
 * fleeing, no fight of its own with other creatures.
 * <p>
 * Each pet has its own temper ({@link Temper}, drawn when it appears) so five pets never move as one: its distance and
 * side around the owner, how late it reacts when he moves, its speed, how often it fidgets around its place, how
 * readily it switches to the owner's target, the side it attacks from, the rhythm of its hits. Pets also spread over
 * the monsters around rather than all piling on the same one. For now every pet has the same life and attack
 * ({@code pets} of the config). The pets that follow players outside are hidden by Runs for the whole run.
 */
public final class PetFighters {

    /** Scoreboard tag of dungeon pets: other plugins leave them alone (FluxStack never stacks tagged mobs). */
    public static final String TAG = "fluxdungeons_pet";
    /** Monsters this close to the owner are attacked without him fighting them. */
    private static final double SIGHT = 12;
    /** A target further than this from the owner is let go (the pet does not run across the map). */
    private static final double CHASE = 18;
    /** Two pets of the same owner closer than this step away from each other (blocks). */
    private static final double SPACE = 1.1;
    /** Further than this from its owner (or in another world), the pet comes back to him at once. */
    private static final double LEASH = 24;
    /** Distance from the owner of each pet's place around him. */

    /** What makes a pet move its own way, drawn once. */
    private record Temper(double ring, double angle, double height, int patience, double pace, int fidget,
                          double loyalty, double side) {
        static Temper draw(int index) {
            java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
            return new Temper(
                    1.6 + r.nextDouble() * 1.8,                       // distance from the owner
                    (index - 2) * 40 + r.nextDouble(-22, 22),         // side around him (behind = 0)
                    1.0 + r.nextDouble() * 1.4,                       // flying height
                    r.nextInt(0, 16),                                 // ticks before it reacts to the owner moving
                    0.85 + r.nextDouble() * 0.3,                      // speed
                    r.nextInt(50, 140),                               // ticks between two fidgets
                    0.45 + r.nextDouble() * 0.55,                     // chance to switch to the owner's target
                    r.nextDouble() * Math.PI * 2);                    // side it attacks from
        }
    }

    private final class Fighter {
        final UUID owner;
        final String name;
        final Mob mob;
        final Mobs.Worn model;
        final boolean flying;
        final int place;
        final Predicate<Entity> enemy;
        final Temper temper;
        UUID target;
        int lastAttack = -1000, nextAttack, age, nextFidget, waiting;
        /** Small offset of its place, changed when it fidgets. */
        double driftX, driftZ;
        boolean removed;

        Fighter(UUID owner, String name, Mob mob, Mobs.Worn model, boolean flying, int place, Predicate<Entity> enemy) {
            this.temper = Temper.draw(place);
            this.nextFidget = temper.fidget;
            this.owner = owner;
            this.name = name;
            this.mob = mob;
            this.model = model;
            this.flying = flying;
            this.place = place;
            this.enemy = enemy;
        }
    }

    private final FluxPlugin plugin;
    private final Mobs mobs;
    private final NamespacedKey ownerKey;
    private final GoalKey<Mob> brainKey;
    private final Map<UUID, List<Fighter>> byOwner = new HashMap<>();
    private double health = 40, attack = 6, speed = 1.2, reach = 1.2;
    private int attackTicks = 20;
    private EntityType ground = EntityType.WOLF, air = EntityType.BEE;

    PetFighters(FluxPlugin plugin, Mobs mobs) {
        this.plugin = plugin;
        this.mobs = mobs;
        this.ownerKey = new NamespacedKey(plugin, "pet_owner");
        this.brainKey = GoalKey.of(Mob.class, new NamespacedKey(plugin, "pet_brain"));
    }

    void settings(org.bukkit.configuration.ConfigurationSection c) {
        health = Math.max(1, c.getDouble("pets.health", 40));
        attack = Math.max(0, c.getDouble("pets.attack", 6));
        attackTicks = Math.max(5, c.getInt("pets.attack-ticks", 20));
        speed = Math.max(0.2, c.getDouble("pets.speed", 1.2));
        reach = Math.max(0.5, c.getDouble("pets.reach", 1.2));
        ground = type(c.getString("pets.body.ground", "WOLF"), EntityType.WOLF);
        air = type(c.getString("pets.body.flying", "BEE"), EntityType.BEE);
    }

    private EntityType type(String name, EntityType fallback) {
        try {
            EntityType t = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            if (t.getEntityClass() != null && Mob.class.isAssignableFrom(t.getEntityClass())) return t;
        } catch (IllegalArgumentException ignored) {
            // logged below
        }
        plugin.getLogger().warning("Pet body " + name + " is not a mob, " + fallback + " is used.");
        return fallback;
    }

    /** Brings the chosen pets next to their owner; {@code enemy} tells what they may attack. */
    void spawn(Player owner, List<String> ids, Location at, Predicate<Entity> enemy) {
        despawn(owner);
        if (ids.isEmpty()) return;
        Map<String, Companions.Companion> owned = new HashMap<>();
        for (Companions.Companion c : Companions.owned(owner)) owned.put(c.id(), c);
        List<Fighter> list = new ArrayList<>();
        for (String id : ids) {
            Companions.Companion c = owned.get(id);
            if (c == null) continue;
            EntityType body = c.flying() ? air : ground;
            Location spot = owner.getLocation();
            Entity spawned = at.getWorld().spawn(spot.getWorld() == at.getWorld() ? spot : at,
                    body.getEntityClass(), CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                        Mob m = (Mob) e;
                        m.addScoreboardTag(TAG);
                        m.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
                        m.customName(Texts.parse(c.name()));
                        m.setCustomNameVisible(true);
                        m.setSilent(true);
                        m.setPersistent(false);
                        m.setRemoveWhenFarAway(false);
                        m.setCanPickupItems(false);
                        m.setCollidable(false);
                        if (m instanceof org.bukkit.entity.Ageable a) a.setAdult();
                        set(m, Attribute.MAX_HEALTH, health);
                        set(m, Attribute.ATTACK_DAMAGE, attack);
                        set(m, Attribute.FOLLOW_RANGE, LEASH + 8);
                        m.setHealth(health);
                    });
            Mob mob = (Mob) spawned;
            Bukkit.getMobGoals().removeAllGoals(mob);
            Fighter f = new Fighter(owner.getUniqueId(), c.name(), mob, mobs.dress(mob, c.model()), c.flying(), list.size(), enemy);
            Bukkit.getMobGoals().addGoal(mob, 0, new Brain(f));
            list.add(f);
        }
        if (!list.isEmpty()) byOwner.put(owner.getUniqueId(), list);
    }

    /** Removes a player's pets (he left, died, the run ended). */
    void despawn(Player owner) {
        despawn(owner.getUniqueId());
    }

    void despawn(UUID owner) {
        List<Fighter> list = byOwner.remove(owner);
        if (list == null) return;
        for (Fighter f : list) remove(f);
    }

    void despawnAll() {
        for (UUID u : List.copyOf(byOwner.keySet())) despawn(u);
    }

    /** True for a dungeon pet. */
    public boolean isPet(Entity e) {
        return e != null && e.getScoreboardTags().contains(TAG);
    }

    /** Owner of a dungeon pet, null for anything else. */
    public UUID owner(Entity e) {
        if (!isPet(e)) return null;
        String s = e.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The owner fights this monster (he hit it, or it hit him): his pets go for it, each one as readily as its temper
     * says (an idle pet always does).
     */
    void focus(Player owner, LivingEntity monster) {
        List<Fighter> list = byOwner.get(owner.getUniqueId());
        if (list == null) return;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (Fighter f : list) {
            if (!f.enemy.test(monster)) continue;
            if (f.target == null || r.nextDouble() < f.temper.loyalty) f.target = monster.getUniqueId();
        }
    }

    /** How many pets of this owner, other than {@code self}, are on this monster. */
    private int crowd(Fighter self, UUID monster) {
        List<Fighter> list = byOwner.get(self.owner);
        if (list == null) return 0;
        int n = 0;
        for (Fighter f : list) if (f != self && monster.equals(f.target)) n++;
        return n;
    }

    /** Twice a second: pets that died are forgotten (their owner is told). */
    void tick(Player owner) {
        List<Fighter> list = byOwner.get(owner.getUniqueId());
        if (list == null) return;
        list.removeIf(f -> {
            if (f.mob.isValid() && !f.mob.isDead()) return false;
            if (!f.removed) plugin.messages().send(owner, "pet.down", "%pet%", f.name);
            remove(f);
            return true;
        });
        if (list.isEmpty()) byOwner.remove(owner.getUniqueId());
    }

    /** Where a pet stands when it has nothing to fight: its own place around its owner, from its temper. */
    private static Location place(Player owner, Fighter f) {
        Location l = owner.getLocation();
        Temper t = f.temper;
        double angle = Math.toRadians(l.getYaw() + 180 + t.angle);
        Location at = l.clone().add(-Math.sin(angle) * t.ring + f.driftX, f.flying ? t.height : 0, Math.cos(angle) * t.ring + f.driftZ);
        return at.getBlock().isPassable() ? at : l.clone();
    }

    private void remove(Fighter f) {
        f.removed = true;
        if (f.model != null) {
            try {
                f.model.close();
            } catch (RuntimeException ignored) {
                // model already gone with its entity
            }
        }
        if (f.mob.isValid()) f.mob.remove();
    }

    private static void set(LivingEntity e, Attribute a, double v) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    /** The whole behaviour of a pet, the only goal it has. */
    private final class Brain implements Goal<Mob> {

        private final Fighter f;

        Brain(Fighter f) {
            this.f = f;
        }

        @Override
        public boolean shouldActivate() {
            return true;
        }

        @Override
        public boolean shouldStayActive() {
            return !f.removed;
        }

        @Override
        public void tick() {
            f.age++;
            Mob mob = f.mob;
            Player owner = Bukkit.getPlayer(f.owner);
            if (owner == null || owner.isDead() || owner.getGameMode() == GameMode.SPECTATOR) {
                mob.getPathfinder().stopPathfinding();
                return;
            }
            Location home = owner.getLocation();
            if (mob.getWorld() != owner.getWorld() || mob.getLocation().distanceSquared(home) > LEASH * LEASH) {
                mob.getPathfinder().stopPathfinding();
                mob.teleport(place(owner, f));
                f.target = null;
                return;
            }
            LivingEntity target = target(owner);
            if (target != null) {
                f.waiting = 0;
                fight(target);
            } else {
                idle(owner);
            }
            spread();
        }

        /**
         * Pets go through each other (they never push their owner), so two of them could stand in the same spot:
         * a pet closer than {@code SPACE} to another of the same owner steps away from it.
         */
        private void spread() {
            List<Fighter> list = byOwner.get(f.owner);
            if (list == null || list.size() < 2) return;
            Location me = f.mob.getLocation();
            double pushX = 0, pushZ = 0;
            for (Fighter o : list) {
                if (o == f || !o.mob.isValid() || o.mob.getWorld() != f.mob.getWorld()) continue;
                Location other = o.mob.getLocation();
                double dx = me.getX() - other.getX(), dz = me.getZ() - other.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 >= SPACE * SPACE || Math.abs(me.getY() - other.getY()) > 1.5) continue;
                if (d2 < 1.0E-4) {
                    // exactly on top of each other: split along their own sides
                    dx = Math.cos(f.temper.side);
                    dz = Math.sin(f.temper.side);
                    d2 = 1;
                }
                double d = Math.sqrt(d2);
                double strength = (SPACE - Math.min(d, SPACE)) / SPACE;
                pushX += dx / d * strength;
                pushZ += dz / d * strength;
            }
            if (pushX == 0 && pushZ == 0) return;
            org.bukkit.util.Vector v = f.mob.getVelocity();
            f.mob.setVelocity(v.setX(v.getX() + pushX * 0.12).setZ(v.getZ() + pushZ * 0.12));
        }

        /**
         * Nothing to fight: back to its place, a little late (its patience), then it fidgets now and then (a step
         * aside, a look around) instead of standing still like the others.
         */
        private void idle(Player owner) {
            Mob mob = f.mob;
            Temper t = f.temper;
            java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
            if (f.age >= f.nextFidget) {
                f.nextFidget = f.age + t.fidget + r.nextInt(-20, 21);
                f.driftX = r.nextDouble(-1.2, 1.2);
                f.driftZ = r.nextDouble(-1.2, 1.2);
            }
            Location spot = place(owner, f);
            double d2 = mob.getLocation().distanceSquared(spot);
            if (d2 > 1.0) {
                // it waits a moment before following, each pet its own moment
                if (f.waiting < t.patience && d2 < 36) {
                    f.waiting++;
                    return;
                }
                if (f.age % 5 == 0 || !mob.getPathfinder().hasPath()) {
                    mob.getPathfinder().moveTo(spot, distanceSpeed(Math.sqrt(d2)));
                }
            } else {
                f.waiting = 0;
                mob.getPathfinder().stopPathfinding();
                if (f.age % 40 < 25) mob.lookAt(owner);
                else mob.lookAt(spot.clone().add(r.nextDouble(-4, 4), 0, r.nextDouble(-4, 4)));
            }
        }

        /** The monster to fight: the one the owner fights, else the closest near him; null when none. */
        private LivingEntity target(Player owner) {
            Entity current = f.target == null ? null : Bukkit.getEntity(f.target);
            if (current instanceof LivingEntity le && usable(le, owner)) return le;
            f.target = null;
            if ((f.age + f.place * 3) % 10 != 0) return null;
            LivingEntity best = null;
            double bestD = SIGHT * SIGHT;
            bestD = Double.MAX_VALUE;
            for (Entity e : owner.getNearbyEntities(SIGHT, SIGHT / 2, SIGHT)) {
                if (!(e instanceof LivingEntity le) || !usable(le, owner)) continue;
                // the closest to the owner, but a monster other pets already fight counts as further away
                double d = e.getLocation().distanceSquared(owner.getLocation()) + 30 * crowd(f, le.getUniqueId());
                if (d < bestD) {
                    bestD = d;
                    best = le;
                }
            }
            if (best != null) f.target = best.getUniqueId();
            return best;
        }

        private boolean usable(LivingEntity le, Player owner) {
            return le.isValid() && !le.isDead() && le.getWorld() == owner.getWorld() && f.enemy.test(le)
                    && le.getLocation().distanceSquared(owner.getLocation()) <= CHASE * CHASE;
        }

        private void fight(LivingEntity target) {
            Mob mob = f.mob;
            mob.lookAt(target);
            double gap = mob.getLocation().distance(target.getLocation())
                    - (mob.getBoundingBox().getWidthX() + target.getBoundingBox().getWidthX()) / 2;
            double dy = Math.abs(mob.getLocation().getY() - target.getLocation().getY());
            if (gap <= reach && dy <= target.getHeight() + 1) {
                mob.getPathfinder().stopPathfinding();
                int now = Bukkit.getCurrentTick();
                if (now - f.lastAttack >= f.nextAttack) {
                    f.lastAttack = now;
                    // not all at the same beat: each hit waits a little more or less
                    f.nextAttack = (int) Math.round(attackTicks * java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.8, 1.25));
                    if (f.model != null) f.model.play("attack");
                    mob.swingMainHand();
                    // damage dealt by the pet: counts for its owner (DungeonListener), vanilla armour applies
                    target.damage(attack, mob);
                }
                return;
            }
            if (f.age % 5 == 0 || !mob.getPathfinder().hasPath()) {
                // each pet comes from its own side, so they surround the monster instead of piling up
                double around = (target.getBoundingBox().getWidthX() / 2) + 0.6;
                Location aim = target.getLocation().add(Math.cos(f.temper.side) * around, f.flying ? target.getHeight() * 0.6 : 0,
                        Math.sin(f.temper.side) * around);
                if (!aim.getBlock().isPassable()) aim = target.getLocation();
                mob.getPathfinder().moveTo(aim, speed * f.temper.pace);
            }
        }

        /** Faster when far, so a pet left behind catches up. */
        private double distanceSpeed(double distance) {
            return (distance > 8 ? speed * 1.5 : speed) * f.temper.pace;
        }

        @Override
        public GoalKey<Mob> getKey() {
            return brainKey;
        }

        @Override
        public EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.TARGET);
        }
    }
}
