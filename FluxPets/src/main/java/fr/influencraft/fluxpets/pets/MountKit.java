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
 *   <li>its attack: the left click or the drop key plays an animation of its model; at the animation's impact ticks
 *   it strikes in front of it (melee), sends a ring of debris around it (wave) or shoots a salvo of projectiles
 *   (volley), then it recharges; the mount is slowed meanwhile;</li>
 *   <li>its sounds: one footfall sound at each landing of the gait that plays (in step with the animation), a voice
 *   now and then, a jump and a landing sound;</li>
 *   <li>its grip: a mount stepping down a block sticks to the ground instead of gliding off the ledge;</li>
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
        switch (attack.effect()) {
            case "wave" -> {
                wave(f, attack, at.clone().add(facing.clone().multiply(attack.forward())), damage);
                return;
            }
            case "volley" -> {
                volley(f, attack, at, yaw, damage);
                return;
            }
            default -> {
            }
        }
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

    // ------------------------------------------------------------------ wave

    /** A ring of debris spreading from {@code centre} in 6 ticks; every hostile mob within the range is hurt at once. */
    private static void wave(PetDisplay.Follower f, Pet.Attack attack, Location centre, double damage) {
        Player rider = f.owner;
        double reach = attack.range() + (attack.forward() > 0 ? 0 : f.pet.size() / 2);
        int hit = 0;
        for (Entity e : centre.getWorld().getNearbyEntities(centre, reach, reach, reach)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity target) || target.isDead()) continue;
            Vector to = target.getLocation().toVector().subtract(centre.toVector());
            if (Math.abs(to.getY()) > 3) continue;
            to.setY(0);
            double dist = to.length();
            if (dist > reach) continue;
            target.damage(damage, rider);
            Vector push = dist > 1.0E-3 ? to.normalize().multiply(0.7) : new Vector();
            target.setVelocity(target.getVelocity().add(push.setY(0.4)));
            hit++;
        }
        if (attack.hitSound() != null) play(centre, attack.hitSound());
        org.bukkit.block.data.BlockData debris = block(attack.item());
        new org.bukkit.scheduler.BukkitRunnable() {
            int step;

            @Override
            public void run() {
                if (++step > 6 || centre.getWorld() == null) {
                    cancel();
                    return;
                }
                double r = reach * step / 6.0;
                int points = Math.max(10, (int) (r * 7));
                for (int i = 0; i < points; i++) {
                    double a = 2 * Math.PI * i / points;
                    Location p = centre.clone().add(Math.cos(a) * r, 0.25, Math.sin(a) * r);
                    if (debris != null) p.getWorld().spawnParticle(Particle.BLOCK, p, 2, 0.15, 0.2, 0.15, debris);
                    if (i % 3 == 0) particle(p, attack.particle(), 1);
                }
            }
        }.runTaskTimer(Pets.plugin(), 0L, 1L);
    }

    private static org.bukkit.block.data.BlockData block(String name) {
        org.bukkit.Material m = name == null ? null : org.bukkit.Material.matchMaterial(name);
        return m != null && m.isBlock() ? m.createBlockData() : null;
    }

    // ------------------------------------------------------------------ volley

    private record Shot(org.bukkit.entity.ItemDisplay display, Vector step, Player rider, double damage, Pet.Attack attack,
                        double[] left) {
    }

    private static final List<Shot> SHOTS = new java.util.ArrayList<>();
    private static org.bukkit.scheduler.BukkitTask shotTask;

    /** A salvo: {@code count} projectiles from the mount's head, fanned out, each hurting the first hostile mob it meets. */
    private static void volley(PetDisplay.Follower f, Pet.Attack attack, Location at, float yaw, double damage) {
        org.bukkit.Material material = attack.item() == null ? null : org.bukkit.Material.matchMaterial(attack.item());
        if (material == null || !material.isItem()) material = org.bukkit.Material.SNOWBALL;
        double rad = Math.toRadians(yaw);
        Vector facing = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        Location mouth = at.clone().add(facing.clone().multiply(f.pet.size() / 2)).add(0, f.pet.mount().seat() * 0.8, 0);
        if (attack.hitSound() != null) play(mouth, attack.hitSound());
        for (int i = 0; i < attack.count(); i++) {
            double off = attack.count() == 1 ? 0 : (i - (attack.count() - 1) / 2.0) * attack.spread();
            double a = Math.toRadians(yaw + off);
            Vector dir = new Vector(-Math.sin(a), -0.04, Math.cos(a)).normalize();
            Location from = mouth.clone();
            from.setYaw((float) (yaw + off));
            org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(material);
            org.bukkit.entity.ItemDisplay display = from.getWorld().spawn(from, org.bukkit.entity.ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(stack);
                d.setTeleportDuration(1);
                d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            });
            SHOTS.add(new Shot(display, dir.multiply(1.1), f.owner, damage, attack, new double[]{attack.range()}));
        }
        if (shotTask == null) shotTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(Pets.plugin(), MountKit::tickShots, 1L, 1L);
    }

    private static void tickShots() {
        for (java.util.Iterator<Shot> it = SHOTS.iterator(); it.hasNext(); ) {
            Shot s = it.next();
            org.bukkit.entity.ItemDisplay d = s.display();
            boolean over = !d.isValid() || !s.rider().isOnline();
            if (!over) {
                Location to = d.getLocation().add(s.step());
                s.left()[0] -= s.step().length();
                org.bukkit.block.Block block = to.getBlock();
                LivingEntity struck = null;
                for (Entity e : to.getWorld().getNearbyEntities(to, 1.1, 1.3, 1.1)) {
                    if (e instanceof Enemy && e instanceof LivingEntity le && !le.isDead()) {
                        struck = le;
                        break;
                    }
                }
                if (struck != null) {
                    struck.damage(s.damage(), s.rider());
                    particle(to, s.attack().particle(), 6);
                    to.getWorld().playSound(to, "entity.arrow.hit", SoundCategory.NEUTRAL, 0.7f, 1.2f);
                    over = true;
                } else if (!block.isPassable() || s.left()[0] <= 0) {
                    if (!block.isPassable()) to.getWorld().spawnParticle(Particle.BLOCK, to, 5, 0.15, 0.15, 0.15, block.getBlockData());
                    over = true;
                } else {
                    d.teleport(to);
                }
            }
            if (over) {
                if (d.isValid()) d.remove();
                it.remove();
            }
        }
        if (SHOTS.isEmpty() && shotTask != null) {
            shotTask.cancel();
            shotTask = null;
        }
    }

    /** The plugin stops or reloads: nothing left flying. */
    static void clear() {
        for (Shot s : SHOTS) if (s.display().isValid()) s.display().remove();
        SHOTS.clear();
        if (shotTask != null) {
            shotTask.cancel();
            shotTask = null;
        }
    }

    private static void particle(Location at, String name) {
        particle(at, name, 6);
    }

    private static void particle(Location at, String name, int amount) {
        if (name == null || name.isBlank()) return;
        try {
            Particle p = Particle.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            double s = amount > 1 ? 0.9 : 0.1;
            if (p.getDataType() == Void.class) at.getWorld().spawnParticle(p, at, amount, s, 0.4 * (amount > 1 ? 1 : 0.3), s, 0.02);
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
     * A swimming mount in water floats with the water line at its rider's seat (the pet's body in the water, its rider
     * out of it): the horse rises while the water covers the seat, hangs where it is while the water is within 0.3
     * block under it, sinks as usual otherwise. Its gravity is an attribute, so the rider's own game applies it.
     * Returns whether it is in water. (Until 2026-11-15 it floated half out of the water: the otter looked set on it.)
     */
    static boolean buoyancy(PetDisplay.Follower f, AbstractHorse horse) {
        var gravity = horse.getAttribute(Attribute.GRAVITY);
        boolean in = horse.isInWater();
        if (gravity == null || !in) return in;            // out of water its gravity is stick()'s
        double wanted = 0.08;
        double seat = f.pet.mount().seat();
        Location at = horse.getLocation();
        if (water(at, seat + 0.05)) wanted = -0.035;
        else if (water(at, seat - 0.3)) wanted = 0.0;
        if (Math.abs(gravity.getBaseValue() - wanted) > 1.0E-6) gravity.setBaseValue(wanted);
        return true;
    }

    // ------------------------------------------------------------------ sticking to the ground

    /** Gravity of a mount stepping down (blocks per tick squared; 0.08 is the game's). */
    private static final double STICK_GRAVITY = 0.45;
    /** Deepest step it is pulled down (blocks): one block and its margin. */
    private static final double STICK_DROP = 1.3;

    /**
     * A mount walking off a ledge of one block sticks to the ground instead of gliding off it (owner's idea,
     * 2026-11-15; the game has a step height to climb, nothing to step down): while it is off the ground without having
     * risen (no jump) and the ground is at most {@link #STICK_DROP} under it, its gravity is {@link #STICK_GRAVITY}
     * (an attribute: the rider's own game applies it), back to the game's as soon as it lands, rises or the drop is
     * deeper. {@code gap}: blocks between its feet and the ground under them. Not in water.
     */
    static void stick(PetDisplay.Follower f, AbstractHorse horse, double gap, double y) {
        var gravity = horse.getAttribute(Attribute.GRAVITY);
        if (gravity == null) return;
        boolean grounded = gap < 0.05;
        if (grounded) f.rose = false;
        else if (y - f.stickY > 0.02) f.rose = true;
        f.stickY = y;
        double wanted = !grounded && !f.rose && gap <= STICK_DROP ? STICK_GRAVITY : 0.08;
        if (Math.abs(gravity.getBaseValue() - wanted) > 1.0E-6) gravity.setBaseValue(wanted);
    }

    private static boolean water(Location feet, double up) {
        return feet.getWorld().getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() + up), feet.getBlockZ())
                .getType() == org.bukkit.Material.WATER;
    }
}
