package fr.influencraft.fluxpets.pets;

import fr.influencraft.fluxpets.Texts;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What a ridden pet does beyond carrying its owner (2026-11-15), all of it read from the pet's {@code mount} section:
 * <ul>
 *   <li>its attack: the left click or the drop key plays an animation of its model, hostile mobs in front of it are
 *   hurt at the animation's impact ticks, then it recharges; the mount is slowed meanwhile;</li>
 *   <li>its sounds: one footfall sound at each landing of the gait that plays (in step with the animation), a voice
 *   now and then, a jump and a landing sound;</li>
 *   <li>its swimming: a mount that swims floats at the surface (the horse's gravity is turned down, then up, around
 *   the water line) and keeps its speed in water; the others behave as any horse (slow, the rider thrown off in deep
 *   water).</li>
 * </ul>
 * Only hostile mobs ({@link Enemy}) are hurt, through the rider (his damage event: protections and job pay apply).
 */
final class MountKit {

    private MountKit() {
    }

    // ------------------------------------------------------------------ attack

    /** The rider asks for the attack: starts it, or says why not. */
    static void attack(PetDisplay.Follower f, long tick) {
        Pet.Attack attack = f.pet.mount().attack();
        Player rider = f.owner;
        if (attack == null) return;
        int level = PetData.level(rider, f.pet);
        if (level < attack.level()) {
            bar(rider, "mount-attack-level", "%attack%", attack.name(), "%level%", String.valueOf(attack.level()));
            return;
        }
        if (tick < f.attackReady) {
            bar(rider, "mount-attack-wait", "%attack%", attack.name(),
                    "%seconds%", String.valueOf((f.attackReady - tick + 19) / 20));
            return;
        }
        f.attackStart = tick;
        f.attackReady = tick + attack.cooldown();
        f.gaitWanted(null);                                 // the attack plays alone, the gait comes back after it
        f.tracker.animate(attack.animation());
        if (attack.sound() != null) play(f.carrier.getLocation(), attack.sound());
        bar(rider, "mount-attack-used", "%attack%", attack.name());
    }

    /** The attack plays (the gait waits, the mount is slowed). */
    static boolean attacking(PetDisplay.Follower f, long tick) {
        Pet.Attack attack = f.pet.mount().attack();
        return attack != null && f.attackStart >= 0 && tick - f.attackStart < attack.ticks();
    }

    /** Each tick of a ridden pet: the impacts of its attack. */
    static void tickAttack(PetDisplay.Follower f, Location at, float yaw, long tick) {
        Pet.Attack attack = f.pet.mount().attack();
        if (attack == null || f.attackStart < 0) return;
        long since = tick - f.attackStart;
        if (since >= attack.ticks()) {
            f.attackStart = -1;
            f.tracker.stopAnimation(attack.animation());
            return;
        }
        if (attack.impacts().contains((int) since)) impact(f, attack, at, yaw);
    }

    private static void impact(PetDisplay.Follower f, Pet.Attack attack, Location at, float yaw) {
        Player rider = f.owner;
        double rad = Math.toRadians(yaw);
        Vector facing = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        double damage = attack.damageAt(PetData.level(rider, f.pet));
        double cos = Math.cos(Math.toRadians(Math.min(180, attack.angle())));
        double reach = attack.range() + f.pet.size() / 2;          // from the centre of a big model
        int hit = 0;
        for (Entity e : at.getWorld().getNearbyEntities(at, reach, reach, reach)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity target) || target.isDead()) continue;
            Vector to = target.getLocation().toVector().subtract(at.toVector());
            double height = to.getY();
            to.setY(0);
            double dist = to.length();
            if (dist > reach || height > reach || height < -2) continue;
            if (attack.angle() < 180 && dist > 1.0E-3 && to.clone().normalize().dot(facing) < cos) continue;
            target.damage(damage, rider);
            if (dist > 1.0E-3) target.setVelocity(target.getVelocity().add(to.normalize().multiply(0.45).setY(0.25)));
            hit++;
        }
        Location front = at.clone().add(facing.clone().multiply(Math.min(reach, 1.5 + f.pet.size() / 2))).add(0, 0.8, 0);
        particle(front, attack.particle());
        if (attack.hitSound() != null && (hit > 0 || attack.sound() == null)) play(front, attack.hitSound());
    }

    private static void particle(Location at, String name) {
        if (name == null || name.isBlank()) return;
        try {
            Particle p = Particle.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            if (p.getDataType() == Void.class) at.getWorld().spawnParticle(p, at, 6, 0.9, 0.4, 0.9, 0.02);
        } catch (IllegalArgumentException ignored) {
            // unknown particle in the pet file: none
        }
    }

    private static void bar(Player player, String key, String... replacements) {
        player.sendActionBar(Texts.of(PetMessages.format(key, replacements)));
    }

    /** Hint shown when the owner gets on: how to attack, or from which level. */
    static void hint(PetDisplay.Follower f) {
        Pet.Attack attack = f.pet.mount().attack();
        if (attack == null) return;
        boolean ready = PetData.level(f.owner, f.pet) >= attack.level();
        bar(f.owner, ready ? "mount-attack-hint" : "mount-attack-level", "%attack%", attack.name(),
                "%level%", String.valueOf(attack.level()));
    }

    // ------------------------------------------------------------------ sounds

    static void play(Location at, Pet.Snd s) {
        for (Pet.Snd one = s; one != null; one = one.also()) {
            float pitch = one.pitch() * (0.94f + ThreadLocalRandom.current().nextFloat() * 0.12f);
            at.getWorld().playSound(at, one.key(), SoundCategory.NEUTRAL, one.volume(), pitch);
        }
    }

    /**
     * Each tick of a ridden pet: footfalls of the gait that plays ({@code gait}: walk, run or swim, null when it
     * stands or attacks; {@code gaitTicks} since it started), voice, jump and landing.
     */
    static void tickSounds(PetDisplay.Follower f, Location at, String gait, boolean moving, boolean grounded, long tick) {
        Pet.Sounds sounds = f.pet.mount().sounds();
        if (sounds == null) return;
        // jump and landing: off the ground while rising, back on it after a real flight
        if (!grounded) {
            if (f.airTicks == 0 && at.getY() - f.lastSoundY > 0.15 && sounds.jump() != null) play(at, sounds.jump());
            f.airTicks++;
        } else {
            if (f.airTicks >= 5 && sounds.land() != null) play(at, sounds.land());
            f.airTicks = 0;
        }
        f.lastSoundY = at.getY();
        if (!moving || gait == null) return;
        boolean swim = "swim".equals(gait);
        Pet.Steps steps = swim ? sounds.swim() : "run".equals(gait) ? sounds.run() : sounds.walk();
        List<Pet.Snd> list = swim ? sounds.swimStep() : sounds.step();
        if (steps != null && !list.isEmpty() && (grounded || swim)) {
            int phase = f.gaitTicks % steps.cycle();
            int k = steps.at().indexOf(phase);
            if (k >= 0) play(at, list.get((f.stepCount++) % list.size()));
        }
        if (!sounds.voice().isEmpty() && tick >= f.voiceAt) {
            if (f.voiceAt > 0) play(at, sounds.voice().get(ThreadLocalRandom.current().nextInt(sounds.voice().size())));
            int span = Math.max(1, sounds.voiceMax() - sounds.voiceMin());
            f.voiceAt = tick + sounds.voiceMin() + ThreadLocalRandom.current().nextInt(span);
        }
    }

    // ------------------------------------------------------------------ swimming

    /**
     * A swimming mount in water floats: the horse rises while the water covers three quarters of the rider's seat,
     * hangs where it is while it covers a third, sinks as usual otherwise. Its gravity is an attribute, so the
     * rider's own game applies it. Returns whether it is in water.
     */
    static boolean buoyancy(PetDisplay.Follower f, AbstractHorse horse) {
        var gravity = horse.getAttribute(Attribute.GRAVITY);
        boolean in = horse.isInWater();
        if (gravity == null) return in;
        double wanted = 0.08;
        if (in) {
            double seat = f.pet.mount().seat();
            Location at = horse.getLocation();
            if (water(at, seat * 0.75)) wanted = -0.035;
            else if (water(at, seat * 0.35)) wanted = 0.0;
        }
        if (Math.abs(gravity.getBaseValue() - wanted) > 1.0E-6) gravity.setBaseValue(wanted);
        return in;
    }

    private static boolean water(Location feet, double up) {
        return feet.getWorld().getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() + up), feet.getBlockZ())
                .getType() == org.bukkit.Material.WATER;
    }
}
