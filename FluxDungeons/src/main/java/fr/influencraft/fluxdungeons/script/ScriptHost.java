package fr.influencraft.fluxdungeons.script;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.Set;

/** A monster running scripts: what the engine needs to know about it. */
public interface ScriptHost {

    LivingEntity entity();

    /** Where it was spawned (SELF_SPAWN, relative block states). */
    Location spawn();

    /** The run it belongs to. */
    String run();

    Map<String, Script> scripts();

    /** Tags set by TAG / UNTAG, read by conditions. */
    Set<String> tags();

    /** Tick each script may run again at. */
    Map<String, Long> cooldowns();

    long globalCooldownUntil();

    void globalCooldownUntil(long tick);

    /** Damage of its hits, the base of DAMAGE multipliers. */
    double baseDamage();

    /** Plays an animation of its model, if it wears one. */
    void animate(String animation);
}
