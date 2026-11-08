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
 * wandering, no fleeing, no fight of its own with other creatures. For now every pet has the same life and attack
 * ({@code pets} of the config). Their models that follow players outside are hidden meanwhile
 * ({@link Companions#suppress}).
 */
public final class PetFighters {

    /** Scoreboard tag of dungeon pets: other plugins leave them alone (FluxStack never stacks tagged mobs). */
    public static final String TAG = "fluxdungeons_pet";
    /** Monsters this close to the owner are attacked without him fighting them. */
    private static final double SIGHT = 12;
    /** A target further than this from the owner is let go (the pet does not run across the map). */
    private static final double CHASE = 18;
    /** Further than this from its owner (or in another world), the pet comes back to him at once. */
    private static final double LEASH = 24;
    /** Distance from the owner of each pet's place around him. */
    private static final double RING = 2.2;

    private final class Fighter {
        final UUID owner;
        final String name;
        final Mob mob;
        final Mobs.Worn model;
        final boolean flying;
        final int place;
        final Predicate<Entity> enemy;
        UUID target;
        int lastAttack = -1000, age;
        boolean removed;

        Fighter(UUID owner, String name, Mob mob, Mobs.Worn model, boolean flying, int place, Predicate<Entity> enemy) {
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
            Location spot = place(owner, list.size(), c.flying());
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
        if (!list.isEmpty()) {
            byOwner.put(owner.getUniqueId(), list);
            Companions.suppress(owner.getUniqueId(), true);
        }
    }

    /** Removes a player's pets (he left, died, the run ended). */
    void despawn(Player owner) {
        despawn(owner.getUniqueId());
    }

    void despawn(UUID owner) {
        List<Fighter> list = byOwner.remove(owner);
        Companions.suppress(owner, false);
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

    /** The owner fights this monster (he hit it, or it hit him): his pets go for it. */
    void focus(Player owner, LivingEntity monster) {
        List<Fighter> list = byOwner.get(owner.getUniqueId());
        if (list == null) return;
        for (Fighter f : list) if (f.enemy.test(monster)) f.target = monster.getUniqueId();
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
        if (list.isEmpty()) {
            byOwner.remove(owner.getUniqueId());
            Companions.suppress(owner.getUniqueId(), false);
        }
    }

    /** Where a pet stands when it has nothing to fight: its own place on a ring behind and around its owner. */
    private static Location place(Player owner, int index, boolean flying) {
        Location l = owner.getLocation();
        double angle = Math.toRadians(l.getYaw() + 180 + (index - 2) * 40);
        Location at = l.clone().add(-Math.sin(angle) * RING, flying ? 1.4 : 0, Math.cos(angle) * RING);
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
                mob.teleport(place(owner, f.place, f.flying));
                f.target = null;
                return;
            }
            LivingEntity target = target(owner);
            if (target != null) {
                fight(target);
                return;
            }
            Location spot = place(owner, f.place, f.flying);
            if (mob.getLocation().distanceSquared(spot) > 2.25) {
                if (f.age % 5 == 0 || !mob.getPathfinder().hasPath()) mob.getPathfinder().moveTo(spot, distanceSpeed(mob.getLocation().distance(spot)));
            } else {
                mob.getPathfinder().stopPathfinding();
                mob.lookAt(owner);
            }
        }

        /** The monster to fight: the one the owner fights, else the closest near him; null when none. */
        private LivingEntity target(Player owner) {
            Entity current = f.target == null ? null : Bukkit.getEntity(f.target);
            if (current instanceof LivingEntity le && usable(le, owner)) return le;
            f.target = null;
            if (f.age % 10 != 0) return null;
            LivingEntity best = null;
            double bestD = SIGHT * SIGHT;
            for (Entity e : owner.getNearbyEntities(SIGHT, SIGHT / 2, SIGHT)) {
                if (!(e instanceof LivingEntity le) || !usable(le, owner)) continue;
                double d = e.getLocation().distanceSquared(owner.getLocation());
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
                if (now - f.lastAttack >= attackTicks) {
                    f.lastAttack = now;
                    if (f.model != null) f.model.play("attack");
                    mob.swingMainHand();
                    // damage dealt by the pet: counts for its owner (DungeonListener), vanilla armour applies
                    target.damage(attack, mob);
                }
                return;
            }
            if (f.age % 5 == 0 || !mob.getPathfinder().hasPath()) {
                Location aim = f.flying ? target.getLocation().add(0, target.getHeight() * 0.6, 0) : target.getLocation();
                mob.getPathfinder().moveTo(aim, speed);
            }
        }

        /** Faster when far, so a pet left behind catches up. */
        private double distanceSpeed(double distance) {
            return distance > 8 ? speed * 1.5 : speed;
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
