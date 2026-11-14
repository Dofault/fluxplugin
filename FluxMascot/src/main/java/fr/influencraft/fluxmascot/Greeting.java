package fr.influencraft.fluxmascot;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * One visit of the robot to one player: it appears in front of him, waves, speaks (the voice is played at its head),
 * waves again, walks away and vanishes. Ticked by {@link Greetings}; {@link #tick()} returns false when it is over.
 */
final class Greeting {

    private static final double HEAD = 1.9;        // height of the robot's head: where the voice comes from
    private static final int CLEARANCE = 3;        // free blocks the robot needs above the ground

    private final Player player;
    private final Settings s;
    private final ModelBridge.Handle model;
    private final Location spot;
    private final int talkAt, byeAt, walkAt, vanishAt, end;
    private float yaw;
    private int tick;
    private boolean spoke;

    private Greeting(Player player, Settings s, ModelBridge.Handle model, Location spot, float yaw) {
        this.player = player;
        this.s = s;
        this.model = model;
        this.spot = spot;
        this.yaw = yaw;
        talkAt = Math.max(s.voiceDelay(), s.appearTicks() + s.greetTicks());
        byeAt = s.voiceDelay() + s.voiceDuration();
        walkAt = Math.max(byeAt, talkAt) + s.greetTicks();
        vanishAt = walkAt + (int) Math.ceil(s.leaveDistance() / s.leaveSpeed() * 20);
        end = vanishAt + s.vanishTicks();
        model.loop(s.idle());
        model.play(s.appear());
        puff();
    }

    /** Starts a visit, null when the model does not exist. */
    static Greeting start(Player player, Settings s) {
        Location spot = spot(player, s);
        float yaw = facing(spot, player.getLocation());
        ModelBridge.Handle model = ModelBridge.show(player, s.model(), spot, yaw);
        return model == null ? null : new Greeting(player, s, model, spot, yaw);
    }

    Player player() {
        return player;
    }

    boolean tick() {
        if (!player.isOnline() || player.getWorld() != spot.getWorld()
                || player.getLocation().distanceSquared(spot) > s.maxDistance() * s.maxDistance()) return false;
        tick++;
        if (tick == s.appearTicks()) model.play(s.greet());
        if (tick == s.voiceDelay()) {
            spoke = true;
            if (!s.voice().isEmpty()) {
                player.playSound(spot.clone().add(0, HEAD, 0), s.voice(), SoundCategory.VOICE, s.voiceVolume(), 1f);
            }
        }
        if (tick == talkAt) model.loop(s.talk());
        if (tick == byeAt) {
            model.stop(s.talk());
            model.play(s.greet());
        }
        if (tick < walkAt) {
            turn(facing(spot, player.getLocation()), 12f);
            model.move(spot, yaw);
        } else if (tick < vanishAt) {
            if (tick == walkAt) model.loop(s.walk());
            walkAway();
        } else if (tick == vanishAt) {
            model.stop(s.walk());
            model.play(s.vanish());
        }
        if (tick >= end) {
            puff();
            return false;
        }
        return true;
    }

    /** Ends the visit now (the voice is cut). */
    void close() {
        model.close();
        if (spoke && !s.voice().isEmpty() && player.isOnline()) player.stopSound(s.voice(), SoundCategory.VOICE);
    }

    private void walkAway() {
        Vector away = spot.toVector().subtract(player.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) away = new Vector(0, 0, 1);
        away.normalize();
        Location target = spot.clone().add(away.clone().multiply(1));
        turn(facing(spot, target), 18f);
        Location next = spot.clone().add(away.multiply(s.leaveSpeed() / 20));
        Location ground = ground(next, spot.getY());
        if (ground != null) {
            spot.setX(ground.getX());
            spot.setY(spot.getY() + Math.max(-0.25, Math.min(0.25, ground.getY() - spot.getY())));
            spot.setZ(ground.getZ());
        }
        model.move(spot, yaw);
    }

    /** Turns towards a yaw, at most `step` degrees per tick. */
    private void turn(float target, float step) {
        float d = ((target - yaw) % 360 + 540) % 360 - 180;
        yaw += Math.max(-step, Math.min(step, d));
    }

    private void puff() {
        player.spawnParticle(Particle.CLOUD, spot.clone().add(0, 1.4, 0), 14, 0.4, 0.7, 0.4, 0.02);
        player.spawnParticle(Particle.END_ROD, spot.clone().add(0, 1.4, 0), 8, 0.4, 0.7, 0.4, 0.03);
    }

    /** Minecraft yaw looking from a point to another. */
    private static float facing(Location from, Location to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1e-6) return from.getYaw();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Where the robot stands: in front of the player, on the ground, as far as the config asks when the way is free. */
    private static Location spot(Player player, Settings s) {
        Location base = player.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        for (double d = s.distance(); d >= s.minDistance() - 1e-6; d -= 0.5) {
            Location ground = ground(base.clone().add(dir.clone().multiply(d)), base.getY());
            if (ground != null && clearPath(base, ground)) return ground;
        }
        return base.clone().add(dir.multiply(s.minDistance()));
    }

    /** Nothing solid between the player's eyes and the robot's head. */
    private static boolean clearPath(Location from, Location spot) {
        Location eye = from.clone().add(0, 1.6, 0);
        Vector to = spot.clone().add(0, HEAD, 0).toVector().subtract(eye.toVector());
        double length = to.length();
        if (length < 0.1) return true;
        return eye.getWorld().rayTraceBlocks(eye, to.normalize(), length) == null;
    }

    /** The ground under (or just above) a point, near the given height, with room for the robot; null when none. */
    private static Location ground(Location at, double nearY) {
        World w = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        int from = (int) Math.floor(nearY) + 2;
        for (int y = from; y >= from - 5; y--) {
            Block floor = w.getBlockAt(x, y - 1, z);
            if (!floor.getType().isSolid()) continue;
            boolean free = true;
            for (int i = 0; i < CLEARANCE && free; i++) free = w.getBlockAt(x, y + i, z).isPassable();
            if (!free) continue;
            double top = floor.getBoundingBox().getMaxY();
            return new Location(w, at.getX(), top > 0 ? top : y, at.getZ());
        }
        return null;
    }
}
