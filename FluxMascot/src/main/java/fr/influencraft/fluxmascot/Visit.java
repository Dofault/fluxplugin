package fr.influencraft.fluxmascot;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The robot of one player (nobody else sees it). It appears in front of him, waves and speaks, then strolls on the
 * town's streets like any inhabitant; a right click makes it stop and speak again. Without a street network where it
 * stands, it walks a few blocks away and vanishes after the speech.
 * Ticked by {@link Visits}; {@link #tick()} returns false when the visit is over.
 */
final class Visit {

    private enum State { SPEECH, STROLL, LEAVE }

    private final FluxMascot plugin;
    private final Player player;
    private final Settings s;
    private final Network network;
    private final ModelBridge.Handle model;
    private final Interaction click;
    private final Location spot;
    private float yaw;
    private State state;
    private int tick;                      // ticks in the current state
    private boolean voiceOn;
    private boolean greeted;               // false: the speech starts without the appearing wave (asked by a click)
    // stroll
    private final Deque<Location> way = new ArrayDeque<>();
    private int node = -1, previous = -1, pause;
    private boolean walking;

    private Visit(FluxMascot plugin, Player player, ModelBridge.Handle model, Location spot, float yaw, boolean speech) {
        this.plugin = plugin;
        this.player = player;
        this.s = plugin.settings();
        this.network = plugin.network();
        this.model = model;
        this.spot = spot;
        this.yaw = yaw;
        this.click = spot.getWorld().spawn(spot, Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth((float) s.clickWidth());
            e.setInteractionHeight((float) s.clickHeight());
            e.setResponsive(true);
        });
        player.showEntity(plugin, click);
        model.loop(s.idle());
        model.play(s.appear());
        puff();
        greeted = true;
        state = speech ? State.SPEECH : State.STROLL;
        if (!speech) pause = s.appearTicks() + 20;
    }

    /** The robot appears in front of the player and makes its speech; null when the model does not exist. */
    static Visit greet(FluxMascot plugin, Player player) {
        Location spot = inFront(player, plugin.settings());
        return create(plugin, player, spot, true);
    }

    /** The robot appears on a street near the player and strolls, without speech; null when it cannot. */
    static Visit stroll(FluxMascot plugin, Player player) {
        Network n = plugin.network();
        Settings s = plugin.settings();
        if (!s.roam() || !n.covers(player.getWorld())) return null;
        int at = n.randomAround(player.getLocation(), s.relocateMin(), s.relocateMax());
        if (at < 0) at = n.nearest(player.getLocation());
        Location spot = Ground.at(n.location(player.getWorld(), at), n.location(player.getWorld(), at).getY());
        if (spot == null) return null;
        Visit v = create(plugin, player, spot, false);
        if (v != null) v.node = at;
        return v;
    }

    private static Visit create(FluxMascot plugin, Player player, Location spot, boolean speech) {
        float yaw = facing(spot, player.getLocation(), 0);
        ModelBridge.Handle model = ModelBridge.show(player, plugin.settings().model(), plugin.settings().scale(), spot, yaw);
        return model == null ? null : new Visit(plugin, player, model, spot, yaw, speech);
    }

    Player player() {
        return player;
    }

    boolean owns(org.bukkit.entity.Entity e) {
        return e.equals(click);
    }

    /** Right click on the robot: it stops and says its speech again. */
    void clicked() {
        if (state != State.STROLL) return;
        stopWalking();
        way.clear();
        state = State.SPEECH;
        tick = 0;
        greeted = false;
    }

    boolean tick() {
        if (!player.isOnline() || player.getWorld() != spot.getWorld()) return false;
        tick++;
        boolean alive = switch (state) {
            case SPEECH -> speech();
            case STROLL -> strollTick();
            case LEAVE -> leave();
        };
        if (alive) {
            model.move(spot, yaw);
            if (click.isValid()) click.teleport(spot);
        }
        return alive;
    }

    /** Ends the visit now (the voice is cut). */
    void close() {
        model.close();
        if (click.isValid()) click.remove();
        if (voiceOn && player.isOnline()) player.stopSound(s.voice(), SoundCategory.VOICE);
    }

    // ------------------------------------------------------------------ speech
    private boolean speech() {
        int wave = greeted ? s.appearTicks() : 0;            // a click skips the appearing, not the wave
        int speak = greeted ? s.voiceDelay() : 10;
        int talkAt = Math.max(speak, wave + s.greetTicks());
        int byeAt = speak + s.voiceDuration();
        int endAt = Math.max(byeAt, talkAt) + s.greetTicks();
        if (player.getLocation().distanceSquared(spot) > s.maxDistance() * s.maxDistance()) {
            cutVoice();                                      // the player walked away: no speech to nobody
            model.stop(s.talk());
            return afterSpeech();
        }
        if (tick == Math.max(1, wave)) model.play(s.greet());
        if (tick == speak && !s.voice().isEmpty()) {
            voiceOn = true;
            player.playSound(spot.clone().add(0, s.voiceHeight(), 0), s.voice(), SoundCategory.VOICE, s.voiceVolume(), 1f);
        }
        if (tick == talkAt) model.loop(s.talk());
        if (tick == byeAt) {
            voiceOn = false;
            model.stop(s.talk());
            model.play(s.greet());
        }
        turn(facing(spot, player.getLocation(), yaw), 12f);
        return tick < endAt || afterSpeech();
    }

    private boolean afterSpeech() {
        tick = 0;
        if (s.roam() && network.covers(spot.getWorld())) {
            state = State.STROLL;
            pause = 10;
        } else {
            state = State.LEAVE;
        }
        return true;
    }

    private void cutVoice() {
        if (voiceOn && !s.voice().isEmpty()) player.stopSound(s.voice(), SoundCategory.VOICE);
        voiceOn = false;
    }

    // ------------------------------------------------------------------ stroll on the streets
    private boolean strollTick() {
        double far = s.roamMaxDistance();
        if (player.getLocation().distanceSquared(spot) > far * far) relocate();
        if (pause > 0) {
            pause--;
            if (player.getLocation().distanceSquared(spot) < 36) turn(facing(spot, player.getLocation(), yaw), 8f);
            return true;
        }
        if (way.isEmpty()) {
            int target = node < 0 ? network.nearest(spot) : network.next(node, previous);
            Location goal = network.location(spot.getWorld(), target);
            way.addAll(Ground.path(spot, goal));
            previous = node;
            node = target;
            if (way.isEmpty()) {                             // no way there (a door closed, a block placed): wait, try another
                pause = 40;
                stopWalking();
                return true;
            }
        }
        if (!walking) {
            walking = true;
            model.loop(s.walk());
        }
        double left = s.speed() / 20;
        while (left > 1e-6 && !way.isEmpty()) {
            Location next = way.peek();
            double dx = next.getX() - spot.getX(), dz = next.getZ() - spot.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            turn(facing(spot, next, yaw), 14f);
            if (d <= left) {
                spot.setX(next.getX());
                spot.setZ(next.getZ());
                left -= d;
                way.poll();
            } else {
                spot.setX(spot.getX() + dx / d * left);
                spot.setZ(spot.getZ() + dz / d * left);
                left = 0;
            }
            double dy = next.getY() - spot.getY();
            spot.setY(spot.getY() + Math.max(-0.2, Math.min(0.2, dy)));
        }
        if (way.isEmpty()) {
            if (ThreadLocalRandom.current().nextDouble() < s.pauseChance()) {
                pause = ThreadLocalRandom.current().nextInt(s.pauseMin(), Math.max(s.pauseMin() + 1, s.pauseMax()));
                stopWalking();
            }
        }
        return true;
    }

    private void stopWalking() {
        if (walking) model.stop(s.walk());
        walking = false;
    }

    /** The player went far away: the robot is moved, unseen, to a street closer to him. */
    private void relocate() {
        int at = network.randomAround(player.getLocation(), s.relocateMin(), s.relocateMax());
        if (at < 0) return;
        Location l = network.location(spot.getWorld(), at);
        Location ground = Ground.at(l, l.getY());
        if (ground == null) return;
        spot.setX(ground.getX());
        spot.setY(ground.getY());
        spot.setZ(ground.getZ());
        way.clear();
        node = at;
        previous = -1;
        stopWalking();
        pause = 20;
    }

    // ------------------------------------------------------------------ leaving (no streets here)
    private boolean leave() {
        int walkTicks = (int) Math.ceil(s.leaveDistance() / s.speed() * 20);
        if (tick == 1) model.loop(s.walk());
        if (tick <= walkTicks) {
            Vector away = spot.toVector().subtract(player.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(0, 0, 1);
            away.normalize();
            turn(facing(spot, spot.clone().add(away), yaw), 18f);
            Location ground = Ground.at(spot.clone().add(away.multiply(s.speed() / 20)), spot.getY());
            if (ground != null) {
                spot.setX(ground.getX());
                spot.setY(spot.getY() + Math.max(-0.2, Math.min(0.2, ground.getY() - spot.getY())));
                spot.setZ(ground.getZ());
            }
        } else if (tick == walkTicks + 1) {
            model.stop(s.walk());
            model.play(s.vanish());
        }
        if (tick >= walkTicks + s.vanishTicks()) {
            puff();
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers
    /** Turns towards a yaw, at most `step` degrees per tick. */
    private void turn(float target, float step) {
        float d = ((target - yaw) % 360 + 540) % 360 - 180;
        yaw += Math.max(-step, Math.min(step, d));
    }

    private void puff() {
        player.spawnParticle(Particle.CLOUD, spot.clone().add(0, 1.0, 0), 12, 0.3, 0.5, 0.3, 0.02);
        player.spawnParticle(Particle.END_ROD, spot.clone().add(0, 1.0, 0), 6, 0.3, 0.5, 0.3, 0.03);
    }

    /** Minecraft yaw looking from a point to another (the given yaw when they are the same point). */
    private static float facing(Location from, Location to, float same) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1e-6) return same;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** In front of the player, on the ground, as far as the config asks when nothing is in the way. */
    private static Location inFront(Player player, Settings s) {
        Location base = player.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        for (double d = s.distance(); d >= s.minDistance() - 1e-6; d -= 0.5) {
            Location ground = Ground.at(base.clone().add(dir.clone().multiply(d)), base.getY() + 1);
            if (ground != null && Math.abs(ground.getY() - base.getY()) <= 2.5 && clearSight(base, ground, s)) return ground;
        }
        return base.clone().add(dir.multiply(s.minDistance()));
    }

    /** Nothing solid between the player's eyes and the robot's head. */
    private static boolean clearSight(Location from, Location spot, Settings s) {
        Location eye = from.clone().add(0, 1.6, 0);
        Vector to = spot.clone().add(0, s.voiceHeight(), 0).toVector().subtract(eye.toVector());
        double length = to.length();
        return length < 0.1 || eye.getWorld().rayTraceBlocks(eye, to.normalize(), length) == null;
    }
}
