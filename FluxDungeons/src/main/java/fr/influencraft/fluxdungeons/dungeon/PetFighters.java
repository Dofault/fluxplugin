package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.pets.Companions;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.mob.Mobs;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pets taken into a run: each one is a tamed wolf wearing the pet's model, that follows its owner and attacks the
 * monsters of the run. For now every pet has the same life and attack ({@code pets} of the config). Their models
 * that follow players outside are hidden meanwhile ({@link Companions#suppress}).
 */
public final class PetFighters {

    /** Scoreboard tag of dungeon pets: other plugins leave them alone (FluxStack never stacks tagged mobs). */
    public static final String TAG = "fluxdungeons_pet";
    private static final double SIGHT = 12, LEASH = 20;

    private record Fighter(UUID owner, LivingEntity entity, Runnable model) {
    }

    private final Mobs mobs;
    private final NamespacedKey ownerKey;
    private final Map<UUID, List<Fighter>> byOwner = new HashMap<>();
    private double health = 40, attack = 6;

    PetFighters(Plugin plugin, Mobs mobs) {
        this.mobs = mobs;
        this.ownerKey = new NamespacedKey(plugin, "pet_owner");
    }

    void settings(double health, double attack) {
        this.health = Math.max(1, health);
        this.attack = Math.max(0, attack);
    }

    /** Brings the chosen pets next to their owner. */
    void spawn(Player owner, List<String> ids, Location at) {
        despawn(owner);
        if (ids.isEmpty()) return;
        Map<String, Companions.Companion> owned = new HashMap<>();
        for (Companions.Companion c : Companions.owned(owner)) owned.put(c.id(), c);
        List<Fighter> list = new ArrayList<>();
        for (String id : ids) {
            Companions.Companion c = owned.get(id);
            if (c == null) continue;
            Wolf wolf = at.getWorld().spawn(at, Wolf.class, CreatureSpawnEvent.SpawnReason.CUSTOM, w -> {
                w.addScoreboardTag(TAG);
                w.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
                w.setTamed(true);
                w.setOwner(owner);
                w.setSitting(false);
                w.customName(Texts.parse(c.name()));
                w.setCustomNameVisible(true);
                w.setPersistent(false);
                w.setRemoveWhenFarAway(false);
                w.setCanPickupItems(false);
                set(w, Attribute.MAX_HEALTH, health);
                set(w, Attribute.ATTACK_DAMAGE, attack);
                w.setHealth(health);
            });
            list.add(new Fighter(owner.getUniqueId(), wolf, mobs.dress(wolf, c.model())));
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

    /**
     * Twice a second: dead pets forgotten, lost pets brought back to their owner, idle pets sent at the closest
     * monster of the run ({@code enemy}).
     */
    void tick(Player owner, Predicate<Entity> enemy) {
        List<Fighter> list = byOwner.get(owner.getUniqueId());
        if (list == null) return;
        list.removeIf(f -> {
            if (f.entity.isValid() && !f.entity.isDead()) return false;
            remove(f);
            return true;
        });
        for (Fighter f : list) {
            LivingEntity pet = f.entity;
            if (pet.getWorld() != owner.getWorld() || pet.getLocation().distanceSquared(owner.getLocation()) > LEASH * LEASH) {
                pet.teleport(owner.getLocation());
                if (pet instanceof Wolf w) w.setTarget(null);
                continue;
            }
            if (!(pet instanceof Wolf w)) continue;
            LivingEntity target = w.getTarget();
            if (target != null && target.isValid() && !target.isDead() && enemy.test(target)) continue;
            LivingEntity best = null;
            double bestD = SIGHT * SIGHT;
            for (Entity e : pet.getNearbyEntities(SIGHT, SIGHT / 2, SIGHT)) {
                if (!(e instanceof LivingEntity le) || le.isDead() || !enemy.test(le)) continue;
                double d = e.getLocation().distanceSquared(pet.getLocation());
                if (d < bestD) {
                    bestD = d;
                    best = le;
                }
            }
            if (best != null) w.setTarget(best);
        }
        if (list.isEmpty()) {
            byOwner.remove(owner.getUniqueId());
            Companions.suppress(owner.getUniqueId(), false);
        }
    }

    private static void remove(Fighter f) {
        if (f.model != null) {
            try {
                f.model.run();
            } catch (RuntimeException ignored) {
                // model already gone with its entity
            }
        }
        if (f.entity.isValid()) f.entity.remove();
    }

    private static void set(LivingEntity e, Attribute a, double v) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }
}
