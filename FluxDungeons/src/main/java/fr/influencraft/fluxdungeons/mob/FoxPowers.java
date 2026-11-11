package fr.influencraft.fluxdungeons.mob;

import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of the Sylvan Fox (Renard sylvestre), boss of the herbalist dungeon: a fox made of leaves, the guardian of
 * an old herbalists' sanctuary. It fights as an animal: its claws, its jaw, its tail, its paws on the ground; the
 * plants of its sanctuary answer them. Every timing follows its model's animations tick for tick
 * (docs/technique/boss/renard-sylvestre.md, assets/monstres/renard_feuilles_anim.py):
 * <ul>
 *   <li>{@code sylve_awaken} (awaken 85, opening): the arena is empty, leaves gather, roots come out, the fox rises out
 *   of the soil (30-50), sets its feet, turns its head to the players and growls (76); invisible and harmless before,
 *   invulnerable until 85;</li>
 *   <li>{@code sylve_claws} (claws 30): crouch, the right paw rises, two swipes in front of it (17, then 22 with the
 *   left paw, wider and harder); nobody behind it is touched;</li>
 *   <li>{@code sylve_seed} (seed 33): a seed forms before its snout (8-18) and leaves at 20 towards where the farthest
 *   player stands: a slow arc, a trail of leaves, an arrow breaks it; it bursts into a quick bloom: small hit, brief
 *   slowness;</li>
 *   <li>{@code sylve_roots} (roots 54): both front paws strike the ground (12), a ring of roots runs out (12-24), the
 *   zones under the players are marked (24-36) and close at 36: hit and held a moment; roots back underground by 54;</li>
 *   <li>{@code sylve_realm} (realm 58, ultimate): it stops, the arena's light flows to it, its eyes light up (18), roar
 *   (36), a wave runs to the walls (43-58) raising roots on the spots marked since 10; then for {@code realm-ticks}
 *   its realm stays: thorn patches near the walls, glowing plants, stronger attacks, and four life sprouts that
 *   players can harvest for a sylvan protection (herbalists harvest them faster and share it); realm_end (20).</li>
 * </ul>
 * Out of abilities it trots after its prey and runs when the prey gets away. Nothing ever changes in the map: roots,
 * flowers, thorns and sprouts are displays, removed with the fight (and never saved).
 */
final class FoxPowers implements Listener {

    static final Set<String> TYPES = Set.of("sylve_awaken", "sylve_claws", "sylve_seed", "sylve_roots", "sylve_realm");
    static final String MODEL = "renard_feuilles";
    /** Scoreboard tag given by the herbalist preparation of the dungeon (offering door): softer roots and seeds. */
    static final String TISANE = "fdgrant_tisane_sylve";
    private static final String TAG = "fluxdungeons_fx";
    /** Every bone shown once it has awoken (the lit eyes stay hidden until the ultimate). */
    private static final List<String> BONES = List.of("Root", "Body", "Neck", "h_Head", "Jaw", "Ear_right", "Ear_left",
            "Tail_base", "Tail_mid", "Tail_tip", "Leg_front_right", "Leg_front_left", "Leg_back_right", "Leg_back_left",
            "Paw_front_right", "Paw_front_left", "Paw_back_right", "Paw_back_left");
    private static final List<String> EYES = List.of("Eyes_lit_empty");
    private static final Color[] LEAVES = {Color.fromRGB(0x35563A), Color.fromRGB(0x496B3F), Color.fromRGB(0x64864A),
            Color.fromRGB(0x83A85A), Color.fromRGB(0xA9C878)};
    private static final Color OLIVE = Color.fromRGB(0x83A85A), POLLEN = Color.fromRGB(0xE8D57A), RED = Color.fromRGB(0xFF5C5C),
            SAGE = Color.fromRGB(0xB3C77B);
    private static final BlockData ROOT = Material.MANGROVE_ROOTS.createBlockData(), DIRT = Material.ROOTED_DIRT.createBlockData(),
            LEAF = Material.AZALEA_LEAVES.createBlockData(), MOSS = Material.MOSS_BLOCK.createBlockData(),
            THORN = Material.SWEET_BERRY_BUSH.createBlockData(), SPROUT = Material.FLOWERING_AZALEA.createBlockData();
    private static final Material[] BLOOM = {Material.ALLIUM, Material.AZURE_BLUET, Material.CORNFLOWER, Material.OXEYE_DAISY,
            Material.LILY_OF_THE_VALLEY, Material.BLUE_ORCHID};
    /** Plants of the arena that light up while the realm lasts. */
    private static final Set<Material> GLOWING = Set.of(Material.FLOWERING_AZALEA, Material.AZALEA, Material.TORCHFLOWER,
            Material.PITCHER_PLANT, Material.SPORE_BLOSSOM, Material.BIG_DRIPLEAF, Material.LARGE_FERN, Material.FERN,
            Material.ALLIUM, Material.BLUE_ORCHID, Material.LILY_OF_THE_VALLEY, Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
            Material.GLOW_LICHEN, Material.FLOWERING_AZALEA_LEAVES);
    private static final Material[] CRACKED = {Material.ROOTED_DIRT, Material.COARSE_DIRT, Material.MOSS_BLOCK};

    /** A fox and what it is doing. */
    private static final class Brain {
        final LivingEntity e;
        boolean awakened, hidden, sprinting, enraged;
        int realmEnd = -1;
        RockPowers.Caster caster;
        double strength = 1;
        final List<Entity> realmFx = new ArrayList<>();
        final List<double[]> thorns = new ArrayList<>();
        final List<Sprout> sprouts = new ArrayList<>();

        Brain(LivingEntity e) {
            this.e = e;
        }
    }

    /** A life sprout of the realm: a display and the box players click; clicks needed by player. */
    private record Sprout(Location at, BlockDisplay look, Interaction box, Map<UUID, Integer> clicks, Brain owner) {
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Mobs mobs;
    private final NamespacedKey hold, sprint, rage, realmSpeed;
    private final Map<UUID, Brain> brains = new HashMap<>();
    private final Set<Entity> alive = new HashSet<>();
    /** Sylvan protection: player, tick it ends. */
    private final Map<UUID, Integer> protection = new HashMap<>();
    private final Map<UUID, Sprout> sproutBoxes = new HashMap<>();
    private int ticks;

    FoxPowers(Plugin plugin, Fx fx, Mobs mobs) {
        this.plugin = plugin;
        this.fx = fx;
        this.mobs = mobs;
        this.hold = new NamespacedKey(plugin, "fox_hidden");
        this.sprint = new NamespacedKey(plugin, "fox_sprint");
        this.rage = new NamespacedKey(plugin, "fox_rage");
        this.realmSpeed = new NamespacedKey(plugin, "fox_realm");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Brain b = brain(c.entity());
        b.caster = c;
        switch (ab.type()) {
            case "sylve_awaken" -> awaken(ab, c, b, target);
            case "sylve_claws" -> claws(ab, c, b, target);
            case "sylve_seed" -> seed(ab, c, b);
            case "sylve_roots" -> roots(ab, c, b);
            case "sylve_realm" -> realm(ab, c, b);
            default -> {
            }
        }
        return true;
    }

    /**
     * Whether casting now makes sense. Nothing before the entrance; the claws want someone in front of it within
     * reach, the seed someone keeping away in sight, the roots someone in the arena around it, the realm someone around
     * it and no realm already running.
     */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        Brain b = brain(e);
        if (!b.awakened) return "sylve_awaken".equals(ab.type());
        return switch (ab.type()) {
            case "sylve_awaken" -> false;
            case "sylve_claws" -> {
                double reach = ab.number("reach", 3.9);
                for (Player p : fighters(e.getLocation(), reach + 0.6))
                    if (Math.abs(side(e, p.getLocation())) <= 65 && e.hasLineOfSight(p)) yield true;
                yield false;
            }
            case "sylve_seed" -> farthest(e, ab.number("min-range", 7), ab.number("max-range", 26), null) != null;
            case "sylve_roots" -> !fighters(e.getLocation(), ab.number("reach", 14)).isEmpty();
            case "sylve_realm" -> b.realmEnd < 0 && !fighters(e.getLocation(), ab.number("reach", 18)).isEmpty();
            default -> true;
        };
    }

    void clear() {
        for (Entity d : alive) if (d.isValid()) d.remove();
        alive.clear();
        sproutBoxes.clear();
        brains.clear();
        protection.clear();
    }

    /** A fox just appeared (or came back with its chunk): hidden at once until its entrance. */
    void spawned(LivingEntity e) {
        Brain b = brain(e);
        if (!b.awakened) waitHidden(b);
    }

    private Brain brain(LivingEntity e) {
        return brains.computeIfAbsent(e.getUniqueId(), k -> new Brain(e));
    }

    // ---------------------------------------------------------------- every tick

    private void tick() {
        ticks++;
        if (ticks % 10 == 0) {
            // a fox is known from its model, among the dungeon monsters of loaded worlds
            for (World w : Bukkit.getWorlds()) {
                for (LivingEntity le : w.getLivingEntities()) {
                    if (brains.containsKey(le.getUniqueId()) || mobs.templateOf(le) == null) continue;
                    MobTemplate t = mobs.templateOfEntity(le);
                    if (t != null && MODEL.equals(t.model())) brain(le);
                }
            }
        }
        for (Iterator<Brain> it = brains.values().iterator(); it.hasNext(); ) {
            Brain b = it.next();
            if (!b.e.isValid() || b.e.isDead()) {
                endRealm(b, false);
                it.remove();
                continue;
            }
            if (!b.awakened) {
                waitHidden(b);
                continue;
            }
            locomotion(b);
            if (b.realmEnd >= 0) realmTick(b);
        }
        // the sylvan protection: a few green motes around who carries it
        protection.entrySet().removeIf(en -> en.getValue() <= Bukkit.getCurrentTick());
        if (ticks % 6 == 0) {
            for (UUID id : protection.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 2, 0.4, 0.5, 0.4, 0);
            }
        }
    }

    /** Before its entrance: invisible, cannot be hurt, does not move (its opening move wakes it). */
    private void waitHidden(Brain b) {
        if (b.hidden) return;
        b.hidden = true;
        b.e.setInvulnerable(true);
        mobs.parts(b.e, false, BONES);
        mobs.parts(b.e, false, EYES);
        speed(b.e, hold, -1, true);
    }

    /** Trots after its prey; runs (faster, run animation) once the prey is more than 9 blocks away. */
    private void locomotion(Brain b) {
        LivingEntity e = b.e;
        boolean enraged = e.getHealth() < 0.4 * maxHealth(e);
        if (enraged != b.enraged) {
            b.enraged = enraged;
            speed(e, rage, 0.15, enraged);
        }
        if (!(e instanceof Mob m) || !m.hasAI()) {
            if (b.sprinting) setSprint(b, false);
            return;
        }
        Player target = m.getTarget() instanceof Player p && Mobs.fighting(p) && p.getWorld() == e.getWorld() ? p : null;
        double d = target == null ? 0 : flatDistance(e.getLocation(), target.getLocation());
        if (!b.sprinting && d > 9) setSprint(b, true);
        else if (b.sprinting && d < 6) setSprint(b, false);
        if (b.sprinting && ticks % 4 == 0) {
            Location at = e.getLocation();
            e.getWorld().spawnParticle(Particle.BLOCK, at, 3, 0.4, 0.05, 0.4, 0, blockUnder(at));
            if (ticks % 8 == 0) leaves(at.clone().add(0, 0.6, 0), 2, 0.4);
        }
    }

    private void setSprint(Brain b, boolean on) {
        b.sprinting = on;
        speed(b.e, sprint, 0.55, on);
        mobs.loop(b.e, "run", on);
        if (on) snd(b.e.getLocation(), "entity.fox.aggro", 1.2f, 0.7f);
    }

    /** A transient speed modifier ({@code amount}: share of its speed added, -1 = cannot move). */
    private static void speed(LivingEntity e, NamespacedKey key, double amount, boolean on) {
        AttributeInstance inst = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (inst == null) return;
        inst.removeModifier(key);
        if (on) inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
    }

    // ---------------------------------------------------------------- shared

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static BlockData blockUnder(Location at) {
        Block b = Fx.ground(at.getWorld(), at.getBlockX(), at.getY(), at.getBlockZ());
        return b == null ? Material.MOSS_BLOCK.createBlockData() : b.getBlockData();
    }

    private static List<Player> fighters(Location at, double within) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(at) <= within * within) out.add(p);
        }
        return out;
    }

    private static double flatDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static Vector forward(LivingEntity e) {
        Vector f = e.getLocation().getDirection().setY(0);
        return f.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : f.normalize();
    }

    private static Vector right(Vector f) {
        return new Vector(-f.getZ(), 0, f.getX());
    }

    private static Vector flat(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        return v.lengthSquared() < 1e-6 ? from.getDirection().setY(0).normalize() : v.normalize();
    }

    private static float yawTo(Vector dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
    }

    /** Signed angle of a place from its front, in degrees: > 0 on its right, < 0 on its left. */
    private static double side(LivingEntity e, Location at) {
        Vector f = forward(e), to = flat(e.getLocation(), at);
        return Math.toDegrees(Math.atan2(right(f).dot(to), f.dot(to)));
    }

    /** A point of its body: {@code x} blocks to its right, {@code y} up, {@code ahead} in front of its origin. */
    private static Location body(LivingEntity e, double x, double y, double ahead) {
        Vector f = forward(e);
        return e.getLocation().clone().add(right(f).multiply(x)).add(0, y, 0).add(f.multiply(ahead));
    }

    private static double groundY(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.2); by >= (int) Math.floor(y) - 4; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isCollidable()) return by + b.getBoundingBox().getHeight();
        }
        return y;
    }

    private static Location onGround(Location l) {
        Location g = l.clone();
        g.setY(groundY(l.getWorld(), l.getX(), l.getY(), l.getZ()));
        return g;
    }

    private static void face(LivingEntity e, Location to) {
        e.setRotation(yawTo(flat(e.getLocation(), to)), 0);
    }

    private static void shake(Location at, double within) {
        for (Player p : fighters(at, within)) p.sendHurtAnimation(0);
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private static void title(Location at, MobTemplate.Ability ab, int ticks) {
        String title = ab.text("title", ""), subtitle = ab.text("subtitle", "");
        if (title.isBlank() && subtitle.isBlank()) return;
        for (Player p : fighters(at, 48)) {
            p.showTitle(net.kyori.adventure.title.Title.title(Texts.parse(title), Texts.parse(subtitle),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200),
                            java.time.Duration.ofMillis(Math.max(500, ticks * 50L)), java.time.Duration.ofMillis(400))));
        }
    }

    /** Green leaves in the palette of its coat. */
    private static void leaves(Location at, int count, double spread) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            at.getWorld().spawnParticle(Particle.TINTED_LEAVES, at, 1, spread, spread * 0.6, spread, 0, LEAVES[r.nextInt(LEAVES.length)]);
        }
    }

    private static Player farthest(LivingEntity e, double min, double max, Player except) {
        Player best = null;
        double bestD = -1;
        for (Player p : fighters(e.getLocation(), max)) {
            double d = p.getLocation().distance(e.getLocation());
            if (p != except && d >= min && d > bestD && e.hasLineOfSight(p)) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    private void timeline(LivingEntity e, int length, java.util.function.IntConsumer body) {
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!e.isValid() || e.isDead() || t > length) {
                    cancel();
                    return;
                }
                body.accept(t);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private boolean protectedNow(Player p) {
        Integer until = protection.get(p.getUniqueId());
        return until != null && until > Bukkit.getCurrentTick();
    }

    /** A hit of the fox: stronger in its realm, halved for whoever carries the sylvan protection. */
    private void hit(RockPowers.Caster c, Brain b, Player p, double power) {
        double share = protectedNow(p) ? 0.5 : 1;
        if (share < 1) c.hitShare(p, power * b.strength, share);
        else c.hit(p, power * b.strength);
    }

    /** Held by roots for {@code ticks}: slowed hard, no jump (halved by the herbalist tisane, none under protection). */
    private void rootHold(Player p, int ticks) {
        if (protectedNow(p)) return;
        int t = p.getScoreboardTags().contains(TISANE) ? ticks / 2 : ticks;
        if (t <= 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, t, 4, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, t, 128, false, false));
        p.sendActionBar(Texts.parse("&#83A85APris dans les racines !"));
    }

    private BlockDisplay display(Location at, BlockData data, Transformation tr, int light) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, bd -> {
            bd.setBlock(data);
            bd.setPersistent(false);
            bd.setTransformation(tr);
            if (light >= 0) bd.setBrightness(new Display.Brightness(light, 15));
            bd.addScoreboardTag(TAG);
        });
        alive.add(d);
        return d;
    }

    private void forget(Entity d) {
        alive.remove(d);
        if (d.isValid()) d.remove();
    }

    private static void moveTo(Display d, Transformation tr, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tr);
    }

    /**
     * A root out of the ground at {@code base}: a mangrove-root column {@code height} tall leaning {@code lean} degrees
     * towards {@code dir} (outwards when null), rising in {@code rise} ticks and sinking back after {@code life}.
     */
    private List<BlockDisplay> root(Location base, double height, double width, double lean, Vector dir, int rise, int life) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = base.clone();
        at.setYaw(0);
        at.setPitch(0);
        float yaw = dir == null ? (float) r.nextDouble(0, Math.PI * 2) : (float) Math.atan2(dir.getX(), dir.getZ());
        List<BlockDisplay> parts = new ArrayList<>();
        float[][] pieces = {{(float) width, (float) (height * 0.6), 0}, {(float) (width * 0.6), (float) (height * 0.55), 12}};
        Vector3f p = new Vector3f();
        for (float[] pc : pieces) {
            Quaternionf q = new Quaternionf().rotateY(yaw).rotateX((float) Math.toRadians(lean + pc[2]));
            Vector3f corner = q.transform(new Vector3f(-pc[0] / 2, 0, -pc[0] / 2)).add(p);
            Transformation up = new Transformation(corner, q, new Vector3f(pc[0], pc[1], pc[0]), new Quaternionf());
            Transformation down = new Transformation(new Vector3f(corner).sub(0, (float) height + 0.3f, 0), q,
                    new Vector3f(pc[0], pc[1], pc[0]), new Quaternionf());
            BlockDisplay d = display(at, ROOT, down, -1);
            parts.add(d);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, up, rise), 1L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, down, 10), rise + (long) life);
            Bukkit.getScheduler().runTaskLater(plugin, () -> forget(d), rise + life + 11L);
            p.add(q.transform(new Vector3f(0, pc[1] * 0.85f, 0)));
        }
        at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), 6, 0.25, 0.05, 0.25, 0, DIRT);
        return parts;
    }

    // ---------------------------------------------------------------- entrance (awaken 85)

    private void awaken(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int length = 85;
        c.hold(length + 2);
        e.setInvulnerable(true);
        if (target != null) face(e, target.getLocation());
        c.animate("awaken");
        // shown once its animation holds it under the ground
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e.isValid()) mobs.parts(e, true, BONES);
            speed(e, hold, -1, false);
            b.hidden = false;
        }, 2L);
        timeline(e, length, t -> {
            Location at = e.getLocation();
            // 0-30 leaves gather above the spot, spiralling in; roots come out of the ground; wood creaks
            if (t < 30 && t % 2 == 0) {
                for (int i = 0; i < 3; i++) {
                    double a = t * 0.35 + i * Math.PI * 2 / 3, r = 3.2 - t * 0.08;
                    leaves(at.clone().add(Math.cos(a) * r, 0.4 + t * 0.03, Math.sin(a) * r), 2, 0.1);
                }
            }
            if (t == 0) {
                snd(at, "block.azalea_leaves.break", 1.2f, 0.6f);
                snd(at, "block.creaking_heart.idle", 1.6f, 0.6f);
            }
            if (t == 10 || t == 16 || t == 22) {
                for (int i = 0; i < 2; i++) {
                    double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), r = 1.6 + ThreadLocalRandom.current().nextDouble(0.9);
                    Location base = onGround(at.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
                    root(base, 1.4, 0.4, 18, new Vector(Math.cos(a), 0, Math.sin(a)), 6, 50 - t);
                }
                snd(at, "block.mangrove_roots.place", 1.4f, 0.6f);
                snd(at, "entity.creaking.twitch", 1.0f, 0.6f);
            }
            if (t == 26) snd(at, "entity.creaking.sway", 1.2f, 0.5f);
            // 30-50 it rises out of the soil
            if (t == 30 || t == 38) {
                fx.debris(at, 8, DIRT, 0.7);
                w.spawnParticle(Particle.BLOCK, at, 30, 1.2, 0.1, 1.2, 0, blockUnder(at));
                snd(at, "block.rooted_dirt.break", 1.6f, 0.6f);
                snd(at, "block.moss.break", 1.4f, 0.7f);
            }
            if (t > 30 && t < 50 && t % 3 == 0) leaves(at.clone().add(0, 1.0, 0), 4, 0.8);
            if (t == 34) fx.crater(at, 2.5, 50, 0.3, CRACKED);
            // 50-58 four feet down
            if (t == 50 || t == 52 || t == 54 || t == 56) {
                snd(at, "block.azalea_leaves.step", 1.2f, 0.7f);
                w.spawnParticle(Particle.BLOCK, at, 6, 0.8, 0.05, 1.0, 0, blockUnder(at));
            }
            // 70 it turns to the players, 76 growl, leaves turning around its paws
            if (t == 70) {
                Player p = target != null && target.isValid() ? target : farthest(e, 0, 40, null);
                if (p != null) face(e, p.getLocation());
                title(at, ab, 50);
            }
            if (t == 76) {
                snd(at, "entity.fox.aggro", 2.0f, 0.5f);
                snd(at, "entity.wolf.growl", 1.4f, 0.6f);
            }
            if (t >= 74 && t <= 85 && t % 2 == 0) {
                for (int i = 0; i < 4; i++) {
                    double a = t * 0.5 + i * Math.PI / 2;
                    leaves(body(e, Math.cos(a) * 0.9, 0.25, -0.5 + Math.sin(a) * 0.9), 1, 0.05);
                }
            }
            if (t == length) {
                e.setInvulnerable(false);
                b.awakened = true;
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- Griffes sylvestres (claws 30)

    private void claws(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double p1 = ab.number("claw-power", 5), p2 = ab.number("claw-power-2", 6.5), reach = ab.number("reach", 3.9);
        Set<UUID> first = new HashSet<>(), second = new HashSet<>();
        c.hold(32);
        c.animate("claws");
        timeline(e, 30, t -> {
            Location at = e.getLocation();
            if (t <= 13 && target != null && target.isValid() && Mobs.fighting(target)) face(e, target.getLocation());
            // 0-8 crouch: small leaves at the front paws, a soft rustle
            if (t == 1) snd(at, "block.azalea_leaves.step", 0.9f, 0.8f);
            if (t < 8 && t % 2 == 0) {
                leaves(body(e, 0.25, 0.15, 0.55), 1, 0.15);
                leaves(body(e, -0.25, 0.15, 0.55), 1, 0.15);
            }
            // 8-13 the right paw rises: moss and leaves turn around it
            if (t == 9) snd(at, "block.moss.step", 1.2f, 0.7f);
            if (t >= 8 && t < 13) {
                double a = t * 0.9;
                Location paw = body(e, 0.25, 0.55, 0.85);
                w.spawnParticle(Particle.BLOCK, paw.clone().add(Math.cos(a) * 0.35, 0, Math.sin(a) * 0.35), 1, 0, 0, 0, 0, MOSS);
                leaves(paw, 1, 0.12);
            }
            if (t == 14) snd(at, "entity.player.attack.sweep", 1.4f, 1.1f);
            if (t == 17) swipe(c, b, e, first, reach, 60, p1, -1, 14);
            if (t == 19) snd(at, "entity.player.attack.sweep", 1.6f, 0.9f);
            if (t == 22) swipe(c, b, e, second, reach + 0.3, 70, p2, 1, 22);
            if (t > 22 && t < 30 && t % 3 == 0) leaves(body(e, 0, 0.3, 1.6), 2, 0.6);
        });
    }

    /**
     * One swipe of a paw in front of it: players within {@code reach} and {@code half} degrees of its front are hit
     * once and thrown a little across ({@code across}: -1 towards its left, 1 towards its right).
     */
    private void swipe(RockPowers.Caster c, Brain b, LivingEntity e, Set<UUID> struck, double reach, double half, double power,
                       int across, int leafCount) {
        World w = e.getWorld();
        Location at = e.getLocation();
        Vector f = forward(e), r = right(f);
        // the arc it tears in front of it, from one side to the other, and the ground scratched
        for (int i = -3; i <= 3; i++) {
            double a = Math.toRadians(i * half / 3.0 * -across);
            Vector d = f.clone().multiply(Math.cos(a)).add(r.clone().multiply(Math.sin(a)));
            Location p = at.clone().add(d.multiply(reach * 0.7)).add(0, 0.6 - i * 0.08 * across, 0);
            w.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0);
        }
        leaves(body(e, 0, 0.5, 1.8), leafCount, 0.7);
        Location ground = body(e, 0, 0.1, 1.8);
        w.spawnParticle(Particle.BLOCK, ground, 10 + leafCount / 2, 0.6, 0.05, 0.4, 0, blockUnder(ground));
        snd(at, "entity.player.attack.strong", 1.4f, 0.7f);
        snd(at, "block.azalea_leaves.break", 1.4f, 0.8f);
        boolean any = false;
        for (Player p : fighters(at, reach + 0.8)) {
            if (struck.contains(p.getUniqueId()) || Math.abs(side(e, p.getLocation())) > half) continue;
            if (Math.abs(p.getLocation().getY() - at.getY()) > 2.2 || !e.hasLineOfSight(p)) continue;
            struck.add(p.getUniqueId());
            hit(c, b, p, power);
            p.setVelocity(f.clone().multiply(0.45).add(r.clone().multiply(0.35 * across)).setY(0.25));
            any = true;
        }
        if (any) snd(at, "entity.fox.bite", 1.4f, 0.6f);
        else snd(ground, "block.rooted_dirt.break", 1.2f, 0.8f);
    }

    // ---------------------------------------------------------------- Graine prédatrice (seed 33)

    private void seed(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Player target = farthest(e, ab.number("min-range", 7), ab.number("max-range", 26), null);
        if (target == null) return;
        Player second = b.enraged || b.realmEnd >= 0 ? farthest(e, ab.number("min-range", 7), ab.number("max-range", 26), target) : null;
        ItemDisplay[] seed = {null};
        c.hold(35);
        c.animate("seed");
        face(e, target.getLocation());
        timeline(e, 33, t -> {
            Location at = e.getLocation();
            if (t <= 20 && target.isValid()) face(e, target.getLocation());
            Location snout = body(e, 0, 1.05, 1.75);
            // 0-8 a few green motes at its snout, a very soft plant sound
            if (t == 0) snd(at, "block.azalea_leaves.hit", 0.6f, 1.6f);
            if (t < 8 && t % 2 == 0)
                w.spawnParticle(Particle.DUST, snout, 1, 0.12, 0.08, 0.12, 0, new Particle.DustOptions(LEAVES[3], 0.7f));
            // 8-18 the seed forms: it grows, turns, leaves, pollen and small lights around it
            if (t == 8) {
                seed[0] = seedDisplay(snout);
                snd(snout, "item.bone_meal.use", 1.0f, 1.4f);
            }
            if (t == 14) snd(snout, "block.chorus_flower.grow", 0.8f, 1.6f);
            if (seed[0] != null && t >= 8 && t < 20) {
                float k = Math.min(1f, (t - 7) / 10f);
                spinSeed(seed[0], snout, t * 22, 0.12f + 0.33f * k);
                if (t % 2 == 0) leaves(snout, 1, 0.15);
                if (t % 3 == 0) w.spawnParticle(Particle.DUST, snout, 1, 0.15, 0.15, 0.15, 0, new Particle.DustOptions(POLLEN, 0.6f));
                if (t % 4 == 0) w.spawnParticle(Particle.FIREFLY, snout, 1, 0.2, 0.2, 0.2, 0);
            }
            // 20 the head snaps forward: the seed leaves towards where its target stands now
            if (t == 20 && seed[0] != null) {
                snd(snout, "entity.fox.spit", 1.6f, 0.6f);
                snd(snout, "entity.sniffer.drop_seed", 1.4f, 0.8f);
                fly(c, b, seed[0], snout, target.getLocation(), ab);
                seed[0] = null;
            }
            // enraged or in its realm: a second seed for another player keeping away
            if (t == 26 && second != null && second.isValid()) {
                ItemDisplay s2 = seedDisplay(snout);
                spinSeed(s2, snout, 0, 0.45f);
                snd(snout, "entity.fox.spit", 1.4f, 0.8f);
                fly(c, b, s2, snout, second.getLocation(), ab);
            }
        });
    }

    private ItemDisplay seedDisplay(Location at) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, it -> {
            it.setItemStack(new ItemStack(Material.PITCHER_POD));
            it.setPersistent(false);
            it.setBrightness(new Display.Brightness(13, 15));
            it.setTeleportDuration(1);
            it.addScoreboardTag(TAG);
        });
        alive.add(d);
        return d;
    }

    private static void spinSeed(ItemDisplay d, Location at, float degrees, float size) {
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(degrees)).rotateX(0.4f);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(size, size, size), new Quaternionf()));
        d.teleport(at);
    }

    /**
     * The flight of a seed: a gentle arc to where the target stood at the launch, {@code 15 to 25} ticks depending on
     * the distance, a trail of leaves; bursts on a player it meets, on a block, or where it lands. An arrow through it
     * breaks it (no burst damage).
     */
    private void fly(RockPowers.Caster c, Brain b, ItemDisplay s, Location from, Location to, MobTemplate.Ability ab) {
        World w = s.getWorld();
        Location dest = onGround(to.clone()).add(0, 0.2, 0);
        double dist = from.distance(dest);
        int duration = (int) Math.max(15, Math.min(25, Math.round(dist * 1.05)));
        double arc = Math.min(3.0, 0.12 * dist);
        Vector start = from.toVector(), delta = dest.toVector().subtract(start);
        new BukkitRunnable() {
            int t;
            Location prev = from.clone();

            @Override
            public void run() {
                if (!s.isValid()) {
                    cancel();
                    return;
                }
                t++;
                double k = Math.min(1, t / (double) duration);
                Location now = start.clone().add(delta.clone().multiply(k)).add(new Vector(0, arc * 4 * k * (1 - k), 0)).toLocation(w);
                spinSeed(s, now, t * 40, 0.45f);
                leaves(now, 1, 0.08);
                if (t % 3 == 0) w.spawnParticle(Particle.DUST, now, 1, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(POLLEN, 0.5f));
                for (AbstractArrow arrow : w.getNearbyEntitiesByType(AbstractArrow.class, now, 0.9)) {
                    if (arrow.isInBlock()) continue;
                    arrow.remove();
                    w.spawnParticle(Particle.BLOCK, now, 12, 0.2, 0.2, 0.2, 0, LEAF);
                    snd(now, "block.azalea_leaves.break", 1.2f, 1.4f);
                    forget(s);
                    cancel();
                    return;
                }
                boolean met = false;
                for (Player p : fighters(now, 2.5)) {
                    if (distToSegment(p.getLocation().toVector().add(new Vector(0, 0.9, 0)), prev.toVector(), now.toVector()) < 0.9) met = true;
                }
                if (met || k >= 1 || !now.getBlock().isPassable()) {
                    forget(s);
                    cancel();
                    burst(c, b, met ? now : onGround(now), ab);
                    return;
                }
                prev = now;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** The seed bursts: leaves thrown around, a quick bloom of flowers, small roots; small hit and brief slowness. */
    private void burst(RockPowers.Caster c, Brain b, Location at, MobTemplate.Ability ab) {
        World w = at.getWorld();
        double radius = ab.number("burst-radius", 2.6), power = ab.number("seed-power", 5);
        int slow = (int) ab.number("slow-ticks", 25);
        snd(at, "block.azalea_leaves.break", 1.6f, 0.7f);
        snd(at, "block.flowering_azalea.place", 1.4f, 0.8f);
        snd(at, "block.mangrove_roots.break", 1.4f, 0.7f);
        w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.4, 0), 24, 0.6, 0.4, 0.6, 0, LEAF);
        leaves(at.clone().add(0, 0.6, 0), 16, 1.0);
        w.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 0.3, 0), 10, radius * 0.5, 0.2, radius * 0.5, 0);
        Fx.ring(at.clone().add(0, 0.05, 0), radius, OLIVE, 1.1f, 0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // a quick bloom: flowers pop out of the ground and fade
        for (int i = 0; i < 6; i++) {
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(0.4, radius * 0.8);
            Location f = onGround(at.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            Transformation small = new Transformation(new Vector3f(-0.02f, 0, -0.02f), new Quaternionf(), new Vector3f(0.04f, 0.04f, 0.04f), new Quaternionf());
            Transformation full = new Transformation(new Vector3f(-0.35f, 0, -0.35f), new Quaternionf().rotateY((float) a), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf());
            BlockDisplay flower = display(f, BLOOM[r.nextInt(BLOOM.length)].createBlockData(), small, -1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(flower, full, 4), 1L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(flower, small, 8), 22L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> forget(flower), 31L);
        }
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + r.nextDouble(-0.3, 0.3);
            root(onGround(at.clone().add(Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9)), 0.8, 0.3, 25, new Vector(Math.cos(a), 0, Math.sin(a)), 3, 14);
        }
        for (Player p : fighters(at, radius)) {
            if (Math.abs(p.getLocation().getY() - at.getY()) > 2.5) continue;
            hit(c, b, p, power);
            if (!protectedNow(p) && !p.getScoreboardTags().contains(TISANE))
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 0, false, true));
            p.setVelocity(flat(at, p.getLocation()).multiply(0.4).setY(0.25));
        }
    }

    private static double distToSegment(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double len = ab.lengthSquared();
        double k = len < 1e-9 ? 0 : Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / len));
        return p.distance(a.clone().add(ab.multiply(k)));
    }

    // ---------------------------------------------------------------- Étreinte des racines (roots 54)

    private void roots(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double slamPower = ab.number("slam-power", 3), trapPower = ab.number("trap-power", 6), zone = ab.number("zone-radius", 2.0),
                waveRadius = ab.number("wave-radius", 13);
        int holdTicks = (int) ab.number("root-ticks", 16), extra = (int) ab.number("extra-zones", 2);
        List<Location> zones = new ArrayList<>();
        c.hold(56);
        c.animate("roots");
        timeline(e, 54, t -> {
            Location at = e.getLocation();
            // 0-8 anchoring: leaves at its paws, roots creak
            if (t == 0) snd(at, "block.hanging_roots.step", 1.0f, 0.6f);
            if (t < 8 && t % 2 == 0) leaves(body(e, 0, 0.15, 0.7), 2, 0.3);
            // 12 both front paws strike the ground
            if (t == 12) {
                Location paws = body(e, 0, 0.1, 0.9);
                snd(at, "entity.ravager.step", 1.6f, 0.5f);
                snd(at, "block.roots.break", 1.6f, 0.6f);
                snd(at, "block.azalea_leaves.break", 1.2f, 0.6f);
                w.spawnParticle(Particle.BLOCK, paws, 30, 1.0, 0.1, 1.0, 0, blockUnder(paws));
                leaves(paws.clone().add(0, 0.4, 0), 10, 0.8);
                w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, paws.clone().add(0, 0.5, 0), 12, 1.0, 0.4, 1.0, 0);
                fx.crater(paws, 2.2, 40, 0.25, CRACKED);
                shake(at, 10);
                for (Player p : fighters(paws, 2.6)) {
                    hit(c, b, p, slamPower);
                    p.setVelocity(flat(paws, p.getLocation()).multiply(0.5).setY(0.35));
                }
                // the zones: under each player in the arena, and a few more around it
                for (Player p : fighters(at, waveRadius + 3)) zones.add(onGround(p.getLocation()));
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < extra; i++) {
                    double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(4, waveRadius - 2);
                    zones.add(onGround(at.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d)));
                }
            }
            // 12-24 a ring of low roots runs out from it
            if (t > 12 && t <= 24) {
                double rr = waveRadius * (t - 12) / 12.0;
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 5; i++) {
                    double a = r.nextDouble(Math.PI * 2);
                    Location base = onGround(at.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr));
                    if (Math.abs(base.getY() - at.getY()) > 2) continue;
                    root(base, 0.45, 0.32, 30, new Vector(Math.cos(a), 0, Math.sin(a)), 2, 34 - (t - 12));
                }
                if (t % 3 == 0) snd(at, "block.roots.place", 1.2f, 0.7f + (t - 12) * 0.02f);
            }
            // 24-36 the zones are marked: a ring that turns red, root tips poking out, a ticking
            if (t >= 24 && t < 36 && t % 2 == 0) {
                for (Location z : zones) {
                    Fx.ring(z.clone().add(0, 0.08, 0), zone, t >= 31 ? RED : OLIVE, 1.2f, t * 0.06);
                    if (t % 4 == 0) w.spawnParticle(Particle.BLOCK, z.clone().add(0, 0.1, 0), 4, zone * 0.4, 0.02, zone * 0.4, 0, DIRT);
                }
                if (t % 4 == 0) snd(at, "block.big_dripleaf.tilt_down", 0.9f, 0.6f + (t - 24) * 0.03f);
            }
            if (t == 26) for (Location z : zones) for (int i = 0; i < 3; i++) {
                double a = i * Math.PI * 2 / 3;
                root(z.clone().add(Math.cos(a) * zone * 0.8, 0, Math.sin(a) * zone * 0.8), 0.25, 0.2, 10, null, 4, 8);
            }
            // 36 the zones close: roots rise in a cage, whoever is inside is hit and held
            if (t == 36) {
                for (Location z : zones) {
                    for (int i = 0; i < 6; i++) {
                        double a = i * Math.PI / 3;
                        Location base = z.clone().add(Math.cos(a) * zone * 0.85, 0, Math.sin(a) * zone * 0.85);
                        root(base, 1.7, 0.4, -24, new Vector(Math.cos(a), 0, Math.sin(a)), 3, 8);
                    }
                    w.spawnParticle(Particle.BLOCK, z.clone().add(0, 0.3, 0), 20, zone * 0.5, 0.2, zone * 0.5, 0, DIRT);
                    snd(z, "block.mangrove_roots.place", 1.6f, 0.5f);
                    snd(z, "entity.creaking.attack", 1.0f, 0.7f);
                    for (Player p : fighters(z, zone + 0.4)) {
                        if (Math.abs(p.getLocation().getY() - z.getY()) > 2) continue;
                        hit(c, b, p, trapPower);
                        rootHold(p, holdTicks);
                    }
                }
            }
            // 44-54 everything sinks back
            if (t == 44) snd(at, "block.hanging_roots.break", 1.2f, 0.6f);
            if (t > 44 && t % 3 == 0) for (Location z : zones) w.spawnParticle(Particle.BLOCK, z.clone().add(0, 0.2, 0), 3, zone * 0.4, 0.05, zone * 0.4, 0, DIRT);
        });
    }

    // ---------------------------------------------------------------- Royaume de la sylve (realm 58 + realm-ticks + realm_end 20)

    private void realm(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location home = c.home().clone();
        double edge = ab.number("arena-radius", 14), rootPower = ab.number("root-power", 8);
        int realmTicks = (int) ab.number("realm-ticks", 100), spots = (int) ab.number("spots", 14);
        boolean shield = ab.flag("shielded", true);
        List<double[]> plan = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < spots; i++) {
            double a = (i + r.nextDouble(-0.3, 0.3)) * Math.PI * 2 / spots, d = r.nextDouble(5, edge - 1.5);
            plan.add(new double[]{Math.cos(a) * d, Math.sin(a) * d, d});
        }
        Set<Integer> fired = new HashSet<>();
        c.hold(60);
        c.animate("realm");
        if (shield) e.setInvulnerable(true);
        b.realmEnd = Bukkit.getCurrentTick() + 58 + realmTicks;
        b.strength = ab.number("realm-strength", 1.25);
        title(home, ab, 60);
        timeline(e, 58, t -> {
            Location at = e.getLocation();
            // 0-10 sudden stop, a deep growl of wood; the arena's leaves flow to it
            if (t == 0) {
                snd(at, "block.creaking_heart.idle", 2.0f, 0.4f);
                snd(at, "entity.wolf.growl", 1.2f, 0.4f);
            }
            if (t < 35 && t % 2 == 0) {
                for (int i = 0; i < 4; i++) {
                    double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(6, edge);
                    Location from = onGround(home.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d)).add(0, r.nextDouble(0.3, 2.5), 0);
                    w.spawnParticle(Particle.TRAIL, from, 1, 0, 0, 0, 0,
                            new Particle.Trail(at.clone().add(0, 1, 0), LEAVES[r.nextInt(LEAVES.length)], 18 + r.nextInt(10)));
                }
            }
            // the spots of the coming roots, marked from 10 (olive, red from 38)
            if (t >= 10 && t % 3 == 0) {
                for (int i = 0; i < plan.size(); i++) {
                    if (fired.contains(i)) continue;
                    double[] s = plan.get(i);
                    Fx.ring(onGround(home.clone().add(s[0], 0, s[1])).add(0, 0.08, 0), 1.4, t >= 38 ? RED : OLIVE, 1.1f, t * 0.05);
                }
            }
            // 10-35 gathering: pollen rising, the ground around it awakes, the coat shivers; eyes lit at 18
            if (t >= 10 && t < 35 && t % 2 == 0) {
                w.spawnParticle(Particle.FIREFLY, at.clone().add(0, 1, 0), 2, 1.2, 0.8, 1.6, 0);
                w.spawnParticle(Particle.DUST, at.clone().add(0, 0.5 + (t - 10) * 0.08, 0), 3, 1.0, 0.6, 1.4, 0,
                        new Particle.DustOptions(POLLEN, 0.7f));
                w.spawnParticle(Particle.HAPPY_VILLAGER, at, 2, 1.8, 0.05, 1.8, 0);
                if (t % 6 == 0) snd(at, "block.azalea_leaves.hit", 1.2f, 0.5f + (t - 10) * 0.02f);
            }
            if (t == 18) {
                c.parts(true, EYES);
                snd(at, "block.amethyst_block.resonate", 1.0f, 0.6f);
            }
            if (t == 24) snd(at, "block.creaking_heart.spawn", 1.4f, 0.6f);
            // 36 the roar: the leaves are blown away, players close to it pushed back
            if (t == 36) {
                snd(at, "entity.fox.screech", 2.6f, 0.5f);
                snd(at, "entity.ravager.roar", 1.6f, 1.3f);
                snd(at, "block.azalea_leaves.break", 2.0f, 0.5f);
                for (int i = 0; i < 12; i++) {
                    double a = i * Math.PI / 6;
                    w.spawnParticle(Particle.GUST, at.clone().add(Math.cos(a) * 1.8, 1.0, Math.sin(a) * 1.8), 1, 0, 0, 0, 0);
                }
                leaves(at.clone().add(0, 1.2, 0), 24, 2.0);
                shake(at, 24);
                for (Player p : fighters(at, 4.5)) p.setVelocity(flat(at, p.getLocation()).multiply(1.2).setY(0.35));
            }
            // 43-58 the wave runs to the walls; each marked spot raises its roots when the wave reaches it
            if (t == 43) {
                glowPlants(b, home, edge + 3);
                snd(at, "block.mangrove_roots.place", 2.0f, 0.4f);
            }
            if (t >= 43 && t <= 58) {
                double rr = edge * (t - 43) / 15.0;
                for (int i = 0; i < 26; i++) {
                    double a = i * Math.PI * 2 / 26;
                    Location p = onGround(home.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr)).add(0, 0.2, 0);
                    w.spawnParticle(Particle.TINTED_LEAVES, p, 1, 0.1, 0.05, 0.1, 0, LEAVES[i % LEAVES.length]);
                    if (i % 3 == 0) w.spawnParticle(Particle.COMPOSTER, p, 1, 0.1, 0.05, 0.1, 0);
                }
                for (int i = 0; i < plan.size(); i++) {
                    double[] s = plan.get(i);
                    if (fired.contains(i) || s[2] > rr) continue;
                    fired.add(i);
                    Location spot = onGround(home.clone().add(s[0], 0, s[1]));
                    for (int k = 0; k < 4; k++) {
                        double a = k * Math.PI / 2 + 0.4;
                        root(spot.clone().add(Math.cos(a) * 0.6, 0, Math.sin(a) * 0.6), 2.0, 0.45, -16, new Vector(Math.cos(a), 0, Math.sin(a)), 3, 14);
                    }
                    snd(spot, "block.roots.break", 1.2f, 0.6f);
                    for (Player p : fighters(spot, 1.6)) {
                        if (Math.abs(p.getLocation().getY() - spot.getY()) > 2) continue;
                        hit(c, b, p, rootPower);
                        p.setVelocity(new Vector(0, 0.75, 0));
                    }
                }
                // leaves turning around the four pillars of the arena
                if (t % 2 == 0) for (int k = 0; k < 4; k++) {
                    double a = k * Math.PI / 2 + Math.PI / 4;
                    double pa = t * 0.4;
                    Location pillar = home.clone().add(Math.cos(a) * edge * 0.95, 1.5, Math.sin(a) * edge * 0.95);
                    leaves(pillar.clone().add(Math.cos(pa) * 1.2, (t - 43) * 0.12, Math.sin(pa) * 1.2), 1, 0.05);
                }
            }
            if (t == 58) {
                if (shield) e.setInvulnerable(false);
                speed(e, realmSpeed, 0.2, true);
                thornsAndSprouts(b, home, edge, ab);
            }
        });
    }

    /** Copies of the arena's plants, lit and outlined, laid over them while the realm lasts. */
    private void glowPlants(Brain b, Location home, double radius) {
        World w = home.getWorld();
        int r = (int) Math.ceil(radius), count = 0;
        for (int dx = -r; dx <= r && count < 48; dx++) {
            for (int dz = -r; dz <= r && count < 48; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                for (int dy = -1; dy <= 8 && count < 48; dy++) {
                    Block bl = w.getBlockAt(home.getBlockX() + dx, home.getBlockY() + dy, home.getBlockZ() + dz);
                    if (!GLOWING.contains(bl.getType())) continue;
                    BlockDisplay d = display(bl.getLocation(), bl.getBlockData(),
                            new Transformation(new Vector3f(-0.01f, -0.01f, -0.01f), new Quaternionf(), new Vector3f(1.02f, 1.02f, 1.02f), new Quaternionf()), 15);
                    d.setGlowing(true);
                    d.setGlowColorOverride(Color.fromRGB(0xA5D86C));
                    b.realmFx.add(d);
                    count++;
                }
            }
        }
    }

    /** Thorn patches along the walls (they hurt who stands in them) and four life sprouts to harvest. */
    private void thornsAndSprouts(Brain b, Location home, double edge, MobTemplate.Ability ab) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int patches = (int) ab.number("thorn-patches", 8);
        for (int i = 0; i < patches; i++) {
            double a = (i + 0.5) * Math.PI * 2 / patches + r.nextDouble(-0.15, 0.15), d = edge - 1.6;
            Location at = onGround(home.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            b.thorns.add(new double[]{at.getX(), at.getY(), at.getZ()});
            for (int k = 0; k < 3; k++) {
                Location p = at.clone().add(r.nextDouble(-0.9, 0.9), 0, r.nextDouble(-0.9, 0.9));
                BlockDisplay bush = display(p, THORN, new Transformation(new Vector3f(-0.5f, -1f, -0.5f), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()), -1);
                Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(bush, new Transformation(new Vector3f(-0.5f, 0, -0.5f), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()), 6), 1L);
                b.realmFx.add(bush);
            }
            snd(at, "block.sweet_berry_bush.place", 1.0f, 0.7f);
        }
        int count = (int) ab.number("sprouts", 4);
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count + Math.PI / count, d = ab.number("sprout-distance", 8);
            Location at = onGround(home.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            BlockDisplay look = display(at, SPROUT, new Transformation(new Vector3f(-0.05f, 0, -0.05f), new Quaternionf(), new Vector3f(0.1f, 0.1f, 0.1f), new Quaternionf()), 15);
            look.setGlowing(true);
            look.setGlowColorOverride(Color.fromRGB(0xD2F49B));
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(look, new Transformation(new Vector3f(-0.35f, 0, -0.35f), new Quaternionf(), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()), 10), 1L);
            Interaction box = at.getWorld().spawn(at, Interaction.class, it -> {
                it.setInteractionWidth(1.0f);
                it.setInteractionHeight(1.0f);
                it.setResponsive(true);
                it.setPersistent(false);
                it.addScoreboardTag(TAG);
            });
            alive.add(box);
            Sprout s = new Sprout(at, look, box, new HashMap<>(), b);
            b.sprouts.add(s);
            sproutBoxes.put(box.getUniqueId(), s);
            snd(at, "item.bone_meal.use", 1.2f, 0.8f);
        }
        for (Player p : fighters(home, edge + 4))
            p.sendActionBar(Texts.parse("&#A5C96BDes pousses de vie ont germé : récolte-les (clic droit) !"));
    }

    /** While the realm lasts: thorns hurt, pollen floats, the sprouts shine; then it fades (realm_end). */
    private void realmTick(Brain b) {
        int now = Bukkit.getCurrentTick();
        World w = b.e.getWorld();
        if (ticks % 15 == 0) {
            for (double[] th : b.thorns) {
                Location at = new Location(w, th[0], th[1], th[2]);
                for (Player p : fighters(at, 1.9)) {
                    if (protectedNow(p) || Math.abs(p.getLocation().getY() - th[1]) > 2) continue;
                    if (b.caster != null) b.caster.hitShare(p, 1.5, 1);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 0, false, true));
                    snd(p.getLocation(), "entity.player.hurt_sweet_berry_bush", 1.0f, 1.0f);
                }
            }
        }
        if (ticks % 5 == 0) {
            Location c = b.e.getLocation();
            w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, c, 4, 8, 2.5, 8, 0);
            w.spawnParticle(Particle.FIREFLY, c, 2, 9, 2, 9, 0);
            for (Sprout s : b.sprouts) if (s.box().isValid()) w.spawnParticle(Particle.HAPPY_VILLAGER, s.at().clone().add(0, 0.6, 0), 1, 0.25, 0.25, 0.25, 0);
        }
        if (now >= b.realmEnd) endRealm(b, true);
    }

    /** The realm fades: displays sink and vanish, eyes back to normal; realm_end when it is free to play it. */
    private void endRealm(Brain b, boolean animate) {
        if (b.realmEnd < 0) return;
        b.realmEnd = -1;
        b.strength = 1;
        speed(b.e, realmSpeed, 0, false);
        for (Entity d : b.realmFx) {
            if (d instanceof BlockDisplay bd && bd.isValid()) {
                Transformation tr = bd.getTransformation();
                moveTo(bd, new Transformation(new Vector3f(tr.getTranslation()).sub(0, 1.1f, 0), tr.getLeftRotation(), tr.getScale(), tr.getRightRotation()), 16);
            }
        }
        List<Entity> fading = new ArrayList<>(b.realmFx);
        b.realmFx.clear();
        Bukkit.getScheduler().runTaskLater(plugin, () -> fading.forEach(this::forget), 18L);
        for (Sprout s : b.sprouts) {
            sproutBoxes.remove(s.box().getUniqueId());
            forget(s.box());
            forget(s.look());
        }
        b.sprouts.clear();
        b.thorns.clear();
        if (!animate || !b.e.isValid()) return;
        mobs.parts(b.e, false, EYES);
        Location at = b.e.getLocation();
        snd(at, "block.azalea_leaves.fall", 1.4f, 0.6f);
        leaves(at.clone().add(0, 1, 0), 10, 1.5);
        if (b.e instanceof Mob m && m.hasAI() && b.caster != null) {
            b.caster.hold(22);
            b.caster.animate("realm_end");
        }
    }

    // ---------------------------------------------------------------- life sprouts (herbalist interaction)

    /**
     * Harvesting a life sprout (right click): three clicks, one for a skilled herbalist (job level
     * {@code 20}+). It gives the sylvan protection for 12 seconds: no root holds you, half damage from the fox; an
     * expert herbalist (40+) shares it with the players within 5 blocks.
     */
    @EventHandler
    public void onSprout(PlayerInteractEntityEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND) return;
        Sprout s = sproutBoxes.get(ev.getRightClicked().getUniqueId());
        if (s == null) return;
        ev.setCancelled(true);
        Player p = ev.getPlayer();
        if (!Mobs.fighting(p)) return;
        int level = herbalist(p);
        int needed = level >= 20 ? 1 : 3;
        int done = s.clicks().merge(p.getUniqueId(), 1, Integer::sum);
        World w = s.at().getWorld();
        w.spawnParticle(Particle.BLOCK, s.at().clone().add(0, 0.4, 0), 6, 0.2, 0.2, 0.2, 0, LEAF);
        snd(s.at(), "block.cave_vines.pick_berries", 1.0f, 0.9f + done * 0.1f);
        if (done < needed) {
            p.sendActionBar(Texts.parse("&#A5C96BRécolte… " + done + "/" + needed
                    + (level < 20 ? " &#B376E5(un herboriste niveau 20 la cueille d'un geste)" : "")));
            return;
        }
        sproutBoxes.remove(s.box().getUniqueId());
        s.owner().sprouts.remove(s);
        forget(s.box());
        forget(s.look());
        snd(s.at(), "block.amethyst_block.chime", 1.2f, 1.4f);
        w.spawnParticle(Particle.HAPPY_VILLAGER, s.at().clone().add(0, 0.8, 0), 14, 0.5, 0.5, 0.5, 0);
        int until = Bukkit.getCurrentTick() + 240;
        List<Player> given = new ArrayList<>();
        given.add(p);
        if (level >= 40) for (Player o : fighters(p.getLocation(), 5)) if (o != p) given.add(o);
        for (Player o : given) {
            protection.put(o.getUniqueId(), until);
            o.sendActionBar(Texts.parse("&#2ECF7BProtection sylvestre : les racines ne te retiennent plus, le renard frappe moins fort (12 s)"));
        }
        if (given.size() > 1) p.sendMessage(Texts.parse("&#A5C96BTon savoir d'herboriste protège aussi " + (given.size() - 1) + " allié(s)."));
    }

    /** Herbalist job level of a player, 0 without a jobs provider. */
    private static int herbalist(Player p) {
        try {
            return Jobs.available() ? Jobs.get().level(p, "herboriste") : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
