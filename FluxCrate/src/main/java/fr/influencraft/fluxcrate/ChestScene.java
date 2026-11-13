package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A placed crate shown as a chest that opens ({@link Crate.Chest}): its parts are item displays at the centre of the
 * block, all in the space of the model (16 pixels = 1 block, front to the north). Vanilla turns an item display half a
 * turn around Y before its transformation, so in that space the front looks to +z and the hinge is behind (-z); the
 * crate faces {@code yaw} (the front turned toward whoever placed it).
 * <p>
 * Idle: the ornament turns slowly, snow and a few glowing motes drift. Opening (about 6 s, the reward drawn when the
 * key is taken): the keyhole lights up, the ornament spins faster and gold and ice orbit the crate, the seam flickers,
 * the lid rattles then opens on its hinge, a beam rises, the reward rises out of the chest and is given, then sinks back
 * and the lid closes on a puff of snow. Every part moves by client-side interpolation, one update every 2 ticks. The
 * opener can open the next key while the reward hovers or the lid closes: the lid opens again for the next reward.
 */
final class ChestScene {

    // timeline of an opening, in ticks
    private static final int AWAKEN = 8, OPEN = 32, REVEAL = 40, GIVE = 56, SINK = 106, CLOSE = 112, SHUT = 122, END = 126;
    private static final float IDLE_SPIN = (float) Math.toRadians(0.6);   // ornament, per tick: a turn in 30 s
    private static final float FAST_SPIN = (float) Math.toRadians(20);
    private static final UUID DEMO = new UUID(0, 0);
    private static final Color GOLD = Color.fromRGB(0xF5D98B), ICE = Color.fromRGB(0xA7DFF2), WHITE = Color.WHITE;

    private final FluxCrate plugin;
    private final PlacedCrates placed;
    final String key;
    private final Location block;
    private final Crate crate;
    private final Crate.Chest chest;
    private final float yaw;
    private final Map<String, UUID> parts = new LinkedHashMap<>();

    private float spin, spinSpeed = IDLE_SPIN, wiggle, rewardSpin;
    private Tween lid = Tween.still(0), beam = Tween.still(0), rise = Tween.still(0);
    private boolean keyholeOn, lightOn;
    private BukkitTask task;
    private int t;
    private UUID opener, shown, label;
    private Crate.Reward reward;
    private boolean given;

    /** A value moving from one number to another between two ticks of the scene. */
    private record Tween(float from, float to, int start, int ticks, boolean easeIn) {
        static Tween still(float v) {
            return new Tween(v, v, 0, 0, false);
        }

        float at(int t) {
            if (ticks <= 0 || t >= start + ticks) return to;
            if (t <= start) return from;
            float u = (t - start) / (float) ticks;
            float e;
            if (easeIn) e = u * u;
            else {   // ease out with a slight overshoot: the lid swings a little past its angle then settles
                float c1 = 1.2f, c3 = c1 + 1, v = u - 1;
                e = 1 + c3 * v * v * v + c1 * v * v;
            }
            return from + (to - from) * e;
        }
    }

    ChestScene(FluxCrate plugin, PlacedCrates placed, String key, Location block, Crate crate, float yawDegrees) {
        this.plugin = plugin;
        this.placed = placed;
        this.key = key;
        this.block = block;
        this.crate = crate;
        this.chest = crate.effects().chest();
        this.yaw = (float) Math.toRadians(yawDegrees);
    }

    boolean busy() {
        return task != null;
    }

    UUID opener() {
        return opener;
    }

    /** The opener may open the next key now: the reward of this one was given. */
    boolean canChain(Player p) {
        return busy() && given && p.getUniqueId().equals(opener);
    }

    // ------------------------------------------------------------------ parts

    private float scale() {
        return crate.effects().scale();
    }

    /** Puts back the parts that are missing (spawned in a loaded chunk, gone with it). */
    void ensure() {
        part("body", crate.effects().model(), true);
        part("lid", chest.lid(), false);
        part("ornament", chest.ornament(), false);
        part("keyhole", chest.keyhole(), false);
        part("light", chest.light(), false);
        part("beam", chest.beam(), false);
    }

    private void part(String name, String spec, boolean shadow) {
        if (spec == null) return;
        UUID id = parts.get(name);
        Entity e = id == null ? null : Bukkit.getEntity(id);
        if (e != null && e.isValid()) return;
        ItemStack item = Items.of(spec, null);
        if (item == null) return;
        ItemDisplay d = block.getWorld().spawn(block.clone().add(0.5, 0.5, 0.5), ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBrightness(new Display.Brightness(15, 15));
            if (shadow) {
                display.setShadowRadius(0.5f * scale());
                display.setShadowStrength(0.7f);
            }
            display.setTransformation(pose(name));
            display.getPersistentDataContainer().set(PlacedCrates.HOLOGRAM, PersistentDataType.BYTE, (byte) 1);
        });
        parts.put(name, d.getUniqueId());
    }

    private ItemDisplay display(String name) {
        UUID id = parts.get(name);
        return id != null && Bukkit.getEntity(id) instanceof ItemDisplay d && d.isValid() ? d : null;
    }

    private Quaternionf facing() {
        return new Quaternionf().rotationY(yaw + wiggle);
    }

    /** The model's floor on the block's floor whatever its size. */
    private Vector3f base() {
        return new Vector3f(0, (scale() - 1) / 2, 0);
    }

    /** Current pose of a part. */
    private Transformation pose(String name) {
        float s = scale();
        Vector3f size = new Vector3f(s, s, s);
        Quaternionf none = new Quaternionf();
        return switch (name) {
            case "lid" -> onHinge(new Quaternionf());
            case "ornament" -> onHinge(new Quaternionf().rotationY(spin));
            case "keyhole" -> new Transformation(base(), facing(), keyholeOn ? size : new Vector3f(), none);
            case "light" -> new Transformation(base(), facing(), lightOn ? size : new Vector3f(), none);
            case "beam" -> new Transformation(base(), facing(), new Vector3f(s, s * beam.at(t), s), none);
            default -> new Transformation(base(), facing(), size, none);
        };
    }

    /** A part carried by the lid, turned by {@code self} first, then by the lid around its hinge. */
    private Transformation onHinge(Quaternionf self) {
        float s = scale();
        Quaternionf h = new Quaternionf().rotationX((float) -Math.toRadians(lid.at(t)));
        Vector3f p = new Vector3f(0, chest.hingeY() / 16 - 0.5f, -(chest.hingeZ() / 16 - 0.5f));
        Vector3f moved = h.transform(new Vector3f(p));
        Vector3f offset = facing().transform(new Vector3f(p).sub(moved).mul(s));
        return new Transformation(base().add(offset), facing(), new Vector3f(s, s, s), h.mul(self));
    }

    private void move(String name, int ticks) {
        ItemDisplay d = display(name);
        if (d == null) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(pose(name));
    }

    /** A point of the model (pixels) in the world. */
    private Location at(float x, float y, float z) {
        Vector3f v = new Vector3f(-(x / 16 - 0.5f), y / 16 - 0.5f, -(z / 16 - 0.5f)).mul(scale());
        facing().transform(v).add(base());
        return block.clone().add(0.5 + v.x, 0.5 + v.y, 0.5 + v.z);
    }

    // ------------------------------------------------------------------ idle

    /** Every second, when no opening plays: the ornament turns on, snow and motes drift. */
    void idle(int beat) {
        spin += IDLE_SPIN * 20;
        move("ornament", 20);
        World w = block.getWorld();
        Particle snow = crate.effects().ambient();
        Location top = at(8, 26, 8);
        if (snow != null) w.spawnParticle(snow, top, 2, 0.55, 0.2, 0.55, 0.0);
        if (beat % 2 == 0) w.spawnParticle(Particle.FIREFLY, at(8, 11, 8), 1, 0.45, 0.12, 0.35, 0);
    }

    // ------------------------------------------------------------------ opening

    /**
     * Starts the opening for this player, who already paid his key; the reward is given at its moment. Without a
     * player, a demonstration (staff, /fluxcrate demo): the reward is shown, never given.
     */
    void start(Player p, Crate.Reward won) {
        opener = p == null ? DEMO : p.getUniqueId();
        reward = won;
        given = false;
        t = 0;
        placed.hologram(key, false);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** The opener opens his next key while the last reward hovers or the lid closes. */
    void chain(Crate.Reward won) {
        removeReward();
        reward = won;
        given = false;
        float now = lid.at(t);
        boolean closing = t >= CLOSE;
        t = REVEAL - 6;
        lid = new Tween(now, chest.openAngle(), t, closing ? 8 : 1, false);
        beam = new Tween(beam.at(t), 1, t, 6, false);
        spinSpeed = FAST_SPIN / 2;
        show("light", true);
        if (closing) sounds("open");
        sounds("activate");
    }

    private void tick() {
        Player p = Bukkit.getPlayer(opener);
        if (display("body") == null) {   // chunk unloaded under the crate
            flush();
            return;
        }
        World w = block.getWorld();
        switch (t) {
            case 0 -> {
                show("keyhole", true);
                sounds("activate");
                Location hole = at(8, 6.5f, 2.6f);
                w.spawnParticle(Particle.DUST, hole, 6, 0.08, 0.1, 0.08, 0, new Particle.DustOptions(GOLD, 0.6f));
                w.spawnParticle(Particle.DUST, hole, 4, 0.08, 0.1, 0.08, 0, new Particle.DustOptions(ICE, 0.6f));
            }
            case 10 -> sounds("awaken");
            case 12, 16 -> show("light", true);
            case 14 -> show("light", false);
            case 18, 24 -> {
                lid = new Tween(0, 4, t, 2, false);
                wiggle = (float) Math.toRadians(t == 18 ? 2 : -2);
                sounds("rattle");
            }
            case 20, 26 -> {
                lid = new Tween(lid.at(t), 0, t, 2, true);
                wiggle = 0;
            }
            case OPEN -> {
                lid = new Tween(0, chest.openAngle(), t, 12, false);
                beam = new Tween(0, 1, t + 2, 10, false);
                sounds("open");
                burstSound();
                Location in = at(8, 10, 8);
                w.spawnParticle(Particle.DUST, in, 16, 0.35, 0.25, 0.25, 0, new Particle.DustOptions(GOLD, 1f));
                w.spawnParticle(Particle.SNOWFLAKE, in, 12, 0.3, 0.2, 0.25, 0.05);
                w.spawnParticle(Particle.END_ROD, in, 6, 0.2, 0.1, 0.2, 0.04);
            }
            case REVEAL -> {
                spawnReward(p);
                rise = new Tween(0, 1, t, GIVE - REVEAL, false);
                sounds("reveal");
            }
            case GIVE -> {
                give(p);
                spawnLabel();
                Location r = rewardAt(1);
                w.spawnParticle(Particle.END_ROD, r, 8, 0.25, 0.25, 0.25, 0.02);
                w.spawnParticle(Particle.DUST, r, 10, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(GOLD, 0.8f));
                burstSound();
            }
            case SINK -> {
                removeLabel();
                rise = new Tween(1, 0, t, 8, true);
                beam = new Tween(beam.at(t), 0, t, 8, true);
            }
            case CLOSE -> {
                removeReward();
                lid = new Tween(lid.at(t), 0, t, SHUT - CLOSE, true);
            }
            case SHUT -> {
                sounds("close");
                w.spawnParticle(Particle.SNOWFLAKE, at(8, 9.5f, 8), 14, 0.5, 0.05, 0.4, 0.02);
                show("light", false);
                show("keyhole", false);
                spinSpeed = IDLE_SPIN;
            }
            case END -> {
                end();
                return;
            }
            default -> {
            }
        }
        if (t >= AWAKEN && t < OPEN) {
            spinSpeed = IDLE_SPIN + (FAST_SPIN - IDLE_SPIN) * (t - AWAKEN) / (float) (OPEN - AWAKEN);
            if (t % 2 == 0) orbit(w);
        } else if (t >= OPEN && t < SINK) {
            spinSpeed = Math.max(FAST_SPIN / 3, spinSpeed * 0.96f);
            if (t % 4 == 0) w.spawnParticle(Particle.END_ROD, at(8, 9, 8), 0, 0, 1, 0, 0.04);
            if (t % 9 == 0) w.spawnParticle(Particle.FIREFLY, at(8, 12, 8), 1, 0.4, 0.3, 0.3, 0);
        } else if (t >= SINK && t < SHUT) {
            spinSpeed = Math.max(IDLE_SPIN, spinSpeed * 0.9f);
        }
        spin += spinSpeed;
        rewardSpin += 0.1f;
        if (t % 2 == 0) {
            for (String part : List.of("body", "lid", "ornament", "keyhole", "light", "beam")) move(part, 2);
            moveReward();
        }
        t++;
    }

    private void orbit(World w) {
        float progress = (t - AWAKEN) / (float) (OPEN - AWAKEN);
        Color[] colours = {GOLD, ICE, WHITE};
        for (int k = 0; k < 3; k++) {
            double a = t * 0.35 + k * 2 * Math.PI / 3;
            double r = 0.75 * scale();
            Location c = block.clone().add(0.5 + r * Math.cos(a), 0.25 + progress * 1.0 * scale(), 0.5 + r * Math.sin(a));
            w.spawnParticle(Particle.DUST, c, 1, 0, 0, 0, 0, new Particle.DustOptions(colours[k], 0.8f));
        }
        if (t % 6 == 0) w.spawnParticle(Particle.END_ROD, at(8, 14, 8), 1, 0.35, 0.2, 0.35, 0.01);
    }

    private void show(String part, boolean on) {
        if (part.equals("keyhole")) keyholeOn = on;
        else lightOn = on;
        move(part, 0);
    }

    // ------------------------------------------------------------------ reward

    /** Height of the reward above the block's floor, from inside the chest (0) to its place above it (1). */
    private float rewardHeight(float progress) {
        return scale() * (0.45f + 0.95f * progress);
    }

    private Location rewardAt(float progress) {
        return block.clone().add(0.5, rewardHeight(progress), 0.5);
    }

    private void spawnReward(Player p) {
        ItemStack icon = CrateMenus.icon(reward, p);
        if (icon == null) return;
        ItemDisplay d = block.getWorld().spawn(block.clone().add(0.5, 0, 0.5), ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(icon);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTransformation(rewardPose(0, 0));
            display.getPersistentDataContainer().set(PlacedCrates.HOLOGRAM, PersistentDataType.BYTE, (byte) 1);
        });
        shown = d.getUniqueId();
    }

    private Transformation rewardPose(float progress, float size) {
        float bob = progress >= 1 ? 0.04f * (float) Math.sin(t * 0.15) : 0;
        return new Transformation(new Vector3f(0, rewardHeight(progress) + bob, 0), new Quaternionf().rotationY(rewardSpin),
                new Vector3f(size, size, size), new Quaternionf());
    }

    private void moveReward() {
        if (shown == null || !(Bukkit.getEntity(shown) instanceof ItemDisplay d)) return;
        float progress = rise.at(t);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(rewardPose(progress, 0.85f * Math.min(1, progress * 1.4f)));
    }

    private void spawnLabel() {
        if (reward == null) return;
        Location at = rewardAt(1).add(0, 0.5, 0);
        TextDisplay d = block.getWorld().spawn(at, TextDisplay.class, text -> {
            text.setPersistent(false);
            text.setBillboard(Display.Billboard.CENTER);
            text.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            text.setShadowed(true);
            text.text(Texts.parse(reward.shownName()));
            text.getPersistentDataContainer().set(PlacedCrates.HOLOGRAM, PersistentDataType.BYTE, (byte) 1);
        });
        label = d.getUniqueId();
    }

    private void removeLabel() {
        Entity e = label == null ? null : Bukkit.getEntity(label);
        if (e != null) e.remove();
        label = null;
    }

    private void removeReward() {
        removeLabel();
        Entity e = shown == null ? null : Bukkit.getEntity(shown);
        if (e != null) e.remove();
        shown = null;
    }

    /** Gives the reward once (the player may have left: nothing then, as with the roll menu). */
    private void give(Player p) {
        if (given || reward == null) return;
        given = true;
        if (p == null || !p.isOnline()) return;
        plugin.give(p, crate, reward);
        plugin.celebrate(p, crate);
    }

    // ------------------------------------------------------------------ end

    /** The reward now if not given yet (the opener leaves, the server stops), then back to idle at once. */
    void flush() {
        if (busy() && !given) give(Bukkit.getPlayer(opener));
        end();
    }

    private void end() {
        if (task != null) task.cancel();
        task = null;
        removeReward();
        opener = null;
        reward = null;
        lid = Tween.still(0);
        beam = Tween.still(0);
        rise = Tween.still(0);
        wiggle = 0;
        keyholeOn = lightOn = false;
        spinSpeed = IDLE_SPIN;
        for (String part : List.of("body", "lid", "ornament", "keyhole", "light", "beam")) move(part, 4);
        placed.hologram(key, true);
    }

    /** Sneak + right click (every key at once): a flash of the lock and the seam, a burst. */
    void flash() {
        if (busy()) return;
        keyholeOn = lightOn = true;
        move("keyhole", 0);
        move("light", 0);
        sounds("reveal");
        burstSound();
        Location in = at(8, 11, 8);
        block.getWorld().spawnParticle(Particle.DUST, in, 14, 0.4, 0.2, 0.3, 0, new Particle.DustOptions(GOLD, 1f));
        block.getWorld().spawnParticle(Particle.SNOWFLAKE, in, 10, 0.4, 0.2, 0.3, 0.04);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (busy()) return;
            keyholeOn = lightOn = false;
            move("keyhole", 0);
            move("light", 0);
        }, 30L);
    }

    void remove() {
        flush();
        for (UUID id : parts.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        parts.clear();
    }

    // ------------------------------------------------------------------ sounds

    private void sounds(String moment) {
        for (String s : chest.sounds(moment)) play(s);
    }

    private void burstSound() {
        String s = crate.effects().burstSound();
        if (s != null && !s.isBlank()) play(s);
    }

    private void play(String spec) {
        String[] p = spec.trim().split("\\s+");
        if (p.length == 0 || p[0].isEmpty()) return;
        block.getWorld().playSound(block.clone().add(0.5, 0.6, 0.5), p[0], SoundCategory.BLOCKS,
                p.length > 1 ? number(p[1]) : 1f, p.length > 2 ? number(p[2]) : 1f);
    }

    private static float number(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 1f;
        }
    }
}
