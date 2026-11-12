package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.mob.Fx;
import fr.influencraft.fluxdungeons.mob.MobTemplate;
import fr.influencraft.fluxdungeons.mob.Mobs;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lair of a digging boss (2026-11-11, first: the Field Mole, King Taupérion): a dungeon whose file has a
 * {@code burrows:} section. When a run starts, its (empty) world gets a square arena of earth and, under it, a network
 * of galleries different for every run ({@link GalleryPlan}); everything goes with the run's world.
 * With {@code approach: N} the ground runs N blocks further south of the arena: the players start on a meadow there,
 * go down a burrow hole into the first galleries and the maze, and only under the arena do they hear the boss (the
 * sounds below are kept for its own ground; before it, only quiet sounds of earth and water).
 * <ol>
 *   <li><b>Hunt</b>: the players start in the galleries and look for the boss. The way to its nest is marked by drill
 *   rings on the walls; dust drifts along it in front of a player who follows it; tremors now and then come from the
 *   way to go; the boss can be heard passing behind the walls; it snores in its nest.</li>
 *   <li><b>Encounter</b>: a player reaches the nest: the boss (asleep there until then) turns, revs its drill and
 *   charges; the nest falls in, everyone is thrown up, the screen goes dark.</li>
 *   <li><b>Fight</b>: everyone falls back onto the arena; the boss surges in the middle and the fight starts there.
 *   The galleries are closed for good.</li>
 * </ol>
 * Nothing of the map is changed during the fight (the boss' effects are displays and packets).
 */
final class Burrows {

    enum Stage { HUNT, ENCOUNTER, FIGHT }

    /** One lair: its plan, where it stands, the boss, the stage of the run. */
    static final class Lair {
        final GalleryPlan plan;
        final World world;
        final int ox, oz;
        final String bossId;
        Stage stage = Stage.HUNT;
        LivingEntity boss;
        int ticks;
        int nextTremor, nextPassage, nextSnore;
        /** Somebody has come under the arena (the boss' ground): its sounds start. */
        boolean near;
        final Map<java.util.UUID, Integer> nextQuiet = new HashMap<>();

        Lair(GalleryPlan plan, World world, int ox, int oz, String bossId) {
            this.plan = plan;
            this.world = world;
            this.ox = ox;
            this.oz = oz;
            this.bossId = bossId;
        }

        Location at(double x, double y, double z) {
            return new Location(world, ox + x, y, oz + z);
        }

        Location at(GalleryPlan.Point p) {
            return at(p.x(), p.floor() + 1, p.z());
        }

        Location nest() {
            return at(plan.nest.x(), plan.nest.floor() + 1, plan.nest.z());
        }

        /** Middle of the arena, on its floor. */
        Location arena() {
            int s = plan.settings.size();
            return new Location(world, ox + s / 2.0, plan.settings.top() + 1, oz + plan.settings.arenaZ0() + s / 2.0, 0, 0);
        }

        /** Local z of a place. */
        double localZ(Location at) {
            return at.getZ() - oz;
        }
    }

    private static final Material[] ORES = {Material.COAL_ORE, Material.COPPER_ORE, Material.IRON_ORE};
    private static final Color DUST = Color.fromRGB(0x96765A);

    private final FluxPlugin plugin;
    private final Runs runs;
    private final Mobs mobs;
    private final Map<String, Lair> lairs = new HashMap<>();

    Burrows(FluxPlugin plugin, Runs runs, Mobs mobs) {
        this.plugin = plugin;
        this.runs = runs;
        this.mobs = mobs;
    }

    Lair of(Run run) {
        return lairs.get(run.id);
    }

    void forget(Run run) {
        lairs.remove(run.id);
    }

    // ---------------------------------------------------------------- building

    /**
     * Builds the arena and a new network of galleries in the run's world, a few layers per tick, puts the boss asleep
     * in its nest, sets the run's start in the entrance, then runs {@code done}.
     */
    void prepare(Run run, Runnable done) {
        ConfigurationSection c = run.def.burrows();
        int size = Math.max(20, c.getInt("size", 30)), top = c.getInt("floor", 63);
        int depth = size + Math.max(0, c.getInt("approach", 0));
        long seed = c.contains("seed") ? c.getLong("seed") : ThreadLocalRandom.current().nextLong();
        GalleryPlan plan;
        try {
            plan = GalleryPlan.generate(GalleryPlan.Settings.of(size, depth, top), seed);
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Lair of " + run.def.id() + ": " + e.getMessage());
            done.run();
            return;
        }
        World w = run.world;
        Lair lair = new Lair(plan, w, -size / 2, -depth + size / 2, c.getString("boss", ""));
        lairs.put(run.id, lair);
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setTime(c.getLong("time", 6000));
        GalleryPlan.Settings s = plan.settings;
        Random r = new Random(seed ^ 0x5DEECE66DL);
        int[] y = {s.bottom()};
        int perTick = Math.max(1, c.getInt("layers-per-tick", 4));
        new BukkitRunnable() {
            int step;

            @Override
            public void run() {
                if (!lairs.containsKey(run.id) || !w.equals(run.world)) {
                    cancel();
                    return;
                }
                if (step == 0) {
                    for (int k = 0; k < perTick && y[0] <= s.top(); k++, y[0]++) mass(lair, y[0], r);
                    if (y[0] > s.top()) step = 1;
                    return;
                }
                cancel();
                carve(lair);
                dress(lair, r);
                marks(lair, r);
                nest(lair, r);
                chambers(lair, r);
                arena(lair, r);
                surface(lair, r);
                Location start = entrance(lair);
                run.checkpoint = start;
                run.origin = start.clone();
                spawnBoss(run, lair);
                guards(run, lair, c.getConfigurationSection("guards"), r);
                plugin.getLogger().info("Lair of " + run.def.id() + " built (seed " + plan.seed + ", " + plan.dug()
                        + " blocks dug, trail of " + plan.trail.size() + " points).");
                done.run();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * One layer of the earth mass: grass on top, then dirt (with coarse and rooted dirt, clay, gravel pockets), a dry
     * band of sand on sandstone between the two levels, damp dirt and mud lower down, stone, tuff and a little
     * deepslate at the bottom (rock only as deeper layers), a few ores, a solid shell.
     */
    private void mass(Lair l, int y, Random r) {
        GalleryPlan.Settings s = l.plan.settings;
        int size = s.size();
        boolean rich = s.depth() > size;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < s.depth(); z++) {
                Material m;
                double n = noise(l.plan.seed, x / 4.0, y / 3.0, z / 4.0);
                double n2 = noise(l.plan.seed ^ 0x2545F491L, x / 2.5, y / 2.5, z / 2.5);
                if (y == s.top()) {
                    m = rich ? (n < -0.45 ? Material.COARSE_DIRT : Material.GRASS_BLOCK)
                            : n < -0.25 ? Material.COARSE_DIRT : n > 0.42 ? Material.ROOTED_DIRT : Material.DIRT;
                } else if (y >= s.top() - 5) {
                    m = n < -0.3 ? Material.COARSE_DIRT : n > 0.35 ? Material.ROOTED_DIRT : Material.DIRT;
                } else if (!rich) {
                    if (y >= s.upper() - 1) {
                        m = n < -0.2 ? Material.STONE : n > 0.3 ? Material.ROOTED_DIRT : n > 0.1 ? Material.COARSE_DIRT : Material.DIRT;
                    } else if (y >= s.lower() + 4) {
                        m = n < -0.35 ? Material.GRANITE : n < 0.05 ? Material.STONE : n < 0.35 ? Material.DIRT : Material.COARSE_DIRT;
                    } else {
                        m = n < -0.3 ? Material.GRANITE : n < 0.15 ? Material.STONE : n < 0.4 ? Material.ANDESITE : Material.TUFF;
                    }
                } else if (y >= s.upper() - 1) {
                    // the upper galleries: earth everywhere, roots from the trees above, clay and gravel pockets
                    m = n2 > 0.55 ? Material.GRAVEL : n2 < -0.6 ? Material.CLAY
                            : n < -0.3 ? Material.COARSE_DIRT : n > 0.4 ? Material.ROOTED_DIRT : n > 0.3 ? Material.PACKED_MUD : Material.DIRT;
                } else if (y >= s.lower() + 6) {
                    // a dry sandy band between the levels, on sandstone
                    m = y >= s.lower() + 8 ? (n < 0.2 ? Material.SAND : Material.DIRT)
                            : n < -0.2 ? Material.SANDSTONE : n < 0.3 ? Material.SAND : Material.COARSE_DIRT;
                } else if (y >= s.lower() - 1) {
                    // the lower galleries: damp earth, mud, clay
                    m = n2 > 0.5 ? Material.MUD : n2 < -0.55 ? Material.CLAY
                            : n < -0.35 ? Material.COARSE_DIRT : n > 0.35 ? Material.ROOTED_DIRT : n > 0.2 ? Material.PACKED_MUD : Material.DIRT;
                } else {
                    m = n < -0.3 ? Material.STONE : n < 0.1 ? Material.TUFF : n < 0.4 ? Material.DIRT : Material.DEEPSLATE;
                }
                if (y < s.top() - 3 && r.nextDouble() < (rich ? 0.004 : 0.012)) m = ORES[y < s.lower() + 2 ? 2 : r.nextInt(ORES.length)];
                l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(m, false);
            }
        }
    }

    /** Smooth value noise in [-1, 1] (cheap, deterministic per seed). */
    private static double noise(long seed, double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double v = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    double h = hash(seed, xi + dx, yi + dy, zi + dz);
                    v += h * (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
                }
            }
        }
        return v;
    }

    private static double hash(long seed, int x, int y, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return ((h >>> 11) / (double) (1L << 53)) * 2 - 1;
    }

    private void carve(Lair l) {
        GalleryPlan.Settings s = l.plan.settings;
        for (int y = s.bottom(); y <= s.top(); y++) {
            for (int z = 0; z < s.depth(); z++) {
                for (int x = 0; x < s.size(); x++) {
                    if (l.plan.air(x, y, z)) l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(Material.AIR, false);
                }
            }
        }
    }

    private boolean solid(Lair l, int x, int y, int z) {
        return !l.plan.air(x, y, z);
    }

    /** Floors, ceilings and walls: loose earth on the floor, hanging roots, roots near the surface, glow lichen. */
    private void dress(Lair l, Random r) {
        GalleryPlan.Settings s = l.plan.settings;
        BlockFace[] sides = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        boolean rich = s.depth() > s.size();
        for (int y = s.bottom() + 1; y <= (rich ? s.top() : l.plan.roof()); y++) {
            for (int z = 0; z < s.depth(); z++) {
                for (int x = 0; x < s.size(); x++) {
                    if (!l.plan.air(x, y, z)) continue;
                    Block b = l.world.getBlockAt(l.ox + x, y, l.oz + z);
                    if (rich) {
                        // nothing loose over a gallery (sand and gravel would fall), roots in the walls and ceilings
                        Block over = b.getRelative(BlockFace.UP);
                        if (solid(l, x, y + 1, z) && (over.getType() == Material.SAND || over.getType() == Material.GRAVEL))
                            over.setType(over.getType() == Material.SAND ? Material.SANDSTONE : Material.COARSE_DIRT, false);
                        if (solid(l, x, y + 1, z) && y >= s.upper() - 1 && r.nextDouble() < 0.05)
                            over.setType(Material.MANGROVE_ROOTS, false);
                    }
                    if (solid(l, x, y - 1, z)) {
                        double k = r.nextDouble();
                        Block under = b.getRelative(BlockFace.DOWN);
                        if (!isOre(under.getType())) {
                            if (k < 0.16) under.setType(Material.COARSE_DIRT, false);
                            else if (k < 0.22) under.setType(Material.ROOTED_DIRT, false);
                            else if (k < 0.25) under.setType(Material.GRAVEL, false);
                            else if (rich && k < 0.31) under.setType(y < s.upper() - 3 ? Material.MUD : Material.PACKED_MUD, false);
                            else if (rich && y < s.upper() - 3 && k < 0.34) under.setType(Material.MOSS_BLOCK, false);
                        }
                    }
                    if (solid(l, x, y + 1, z) && l.plan.air(x, y - 1, z) && r.nextDouble() < 0.07) {
                        b.setType(Material.HANGING_ROOTS, false);
                        continue;
                    }
                    for (BlockFace f : sides) {
                        int nx = x + f.getModX(), nz = z + f.getModZ();
                        if (!solid(l, nx, y, nz)) continue;
                        Block wall = b.getRelative(f);
                        if (y >= s.upper() - 1 && wall.getType() == Material.DIRT && r.nextDouble() < 0.25) wall.setType(Material.ROOTED_DIRT, false);
                        if (rich && y < s.upper() - 3 && wall.getType() == Material.DIRT && r.nextDouble() < 0.06) wall.setType(Material.MOSS_BLOCK, false);
                        if (b.getType() == Material.AIR && r.nextDouble() < 0.035) {
                            b.setType(Material.GLOW_LICHEN, false);
                            if (b.getBlockData() instanceof MultipleFacing mf) {
                                mf.setFace(f, true);
                                b.setBlockData(mf, false);
                            }
                        }
                    }
                }
            }
        }
        // a dim light all along the tunnels (invisible light blocks), so the galleries can be walked without torches
        for (List<GalleryPlan.Point> t : l.plan.tunnels) {
            for (int i = 0; i < t.size(); i += 20) light(l, t.get(i), 3, 8);
        }
        for (GalleryPlan.Node n : l.plan.nodes) light(l, new GalleryPlan.Point(n.x(), n.z(), n.floor()), 3, n.kind() == GalleryPlan.Kind.ROOM ? 10 : 8);
    }

    private static boolean isOre(Material m) {
        for (Material o : ORES) if (o == m) return true;
        return false;
    }

    private void light(Lair l, GalleryPlan.Point p, int up, int level) {
        int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z());
        for (int y = p.floor() + up; y >= p.floor() + 1; y--) {
            if (!l.plan.air(x, y, z)) continue;
            Block b = l.world.getBlockAt(l.ox + x, y, l.oz + z);
            if (b.getType() != Material.AIR) continue;
            b.setType(Material.LIGHT, false);
            if (b.getBlockData() instanceof Levelled lv) {
                lv.setLevel(level);
                b.setBlockData(lv, false);
            }
            return;
        }
    }

    /**
     * The boss' way: drill rings on the walls every few blocks along the trail (its own tunnels), loose earth on its
     * floor; claw marks at the dead ends and in the rooms.
     */
    private void marks(Lair l, Random r) {
        List<GalleryPlan.Point> trail = l.plan.trail;
        for (int i = 6; i < trail.size() - 6; i += 12) {
            GalleryPlan.Point p = trail.get(i), q = trail.get(i + 1);
            double dx = q.x() - p.x(), dz = q.z() - p.z(), len = Math.hypot(dx, dz);
            if (len < 1e-6) continue;
            dx /= len;
            dz /= len;
            for (int y = p.floor(); y <= p.floor() + 5; y++) {
                for (int x = (int) p.x() - 3; x <= (int) p.x() + 3; x++) {
                    for (int z = (int) p.z() - 3; z <= (int) p.z() + 3; z++) {
                        double rx = x + 0.5 - p.x(), rz = z + 0.5 - p.z();
                        double along = rx * dx + rz * dz;
                        if (Math.abs(along) > 0.55 || Math.hypot(rx, rz) > 3.2 || !solid(l, x, y, z)) continue;
                        if (!touchesAir(l, x, y, z)) continue;
                        Block b = l.world.getBlockAt(l.ox + x, y, l.oz + z);
                        if (b.getType().isSolid()) b.setType(Material.PACKED_MUD, false);
                    }
                }
            }
        }
        for (int i = 0; i < trail.size(); i += 3) {
            GalleryPlan.Point p = trail.get(i);
            if (r.nextDouble() > 0.5) continue;
            Block floor = l.world.getBlockAt(l.ox + (int) Math.floor(p.x()), p.floor(), l.oz + (int) Math.floor(p.z()));
            if (floor.getType().isSolid() && !l.plan.air((int) Math.floor(p.x()), p.floor(), (int) Math.floor(p.z())))
                floor.setType(r.nextBoolean() ? Material.COARSE_DIRT : Material.ROOTED_DIRT, false);
        }
        for (GalleryPlan.Point d : l.plan.deadEnds) claws(l, d, r);
        for (GalleryPlan.Node n : l.plan.nodes) if (n.kind() == GalleryPlan.Kind.ROOM) claws(l, new GalleryPlan.Point(n.x(), n.z(), n.floor()), r);
    }

    private boolean touchesAir(Lair l, int x, int y, int z) {
        return l.plan.air(x + 1, y, z) || l.plan.air(x - 1, y, z) || l.plan.air(x, y, z + 1) || l.plan.air(x, y, z - 1)
                || l.plan.air(x, y + 1, z) || l.plan.air(x, y - 1, z);
    }

    /** Three claw scratches (vertical stripes of packed mud, a block apart) on a wall near a point. */
    private void claws(Lair l, GalleryPlan.Point near, Random r) {
        int cx = (int) Math.floor(near.x()), cz = (int) Math.floor(near.z()), y0 = near.floor() + 1;
        List<int[]> walls = new ArrayList<>();
        for (int x = cx - 4; x <= cx + 4; x++) {
            for (int z = cz - 4; z <= cz + 4; z++) {
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (solid(l, x, y0 + 1, z) && l.plan.air(x + d[0], y0 + 1, z + d[1]) && l.plan.air(x + d[0], y0 + 2, z + d[1]))
                        walls.add(new int[]{x, z, d[0], d[1]});
                }
            }
        }
        if (walls.isEmpty()) return;
        int[] w = walls.get(r.nextInt(walls.size()));
        int tx = -w[3], tz = w[2];          // along the wall
        for (int k = -1; k <= 1; k++) {
            int x = w[0] + tx * k * 2, z = w[1] + tz * k * 2;
            for (int dy = 0; dy < 3; dy++) {
                int y = y0 + dy + (k == 0 ? 1 : 0);
                if (!solid(l, x, y, z) || !(l.plan.air(x + w[2], y, z + w[3]))) continue;
                l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(Material.PACKED_MUD, false);
            }
        }
    }

    /** The nest: a floor of roots and mud, mangrove roots on the walls, warm light from shroomlights in the vault. */
    private void nest(Lair l, Random r) {
        GalleryPlan.Node n = l.plan.nest;
        double R = GalleryPlan.NEST_RADIUS;
        int cx = (int) Math.floor(n.x()), cz = (int) Math.floor(n.z());
        for (int x = cx - 7; x <= cx + 7; x++) {
            for (int z = cz - 7; z <= cz + 7; z++) {
                double d = Math.hypot(x + 0.5 - n.x(), z + 0.5 - n.z());
                if (d > R + 1) continue;
                for (int y = n.floor() - 1; y <= n.floor() + 8; y++) {
                    if (l.plan.air(x, y, z) && solid(l, x, y - 1, z)) {
                        Block f = l.world.getBlockAt(l.ox + x, y - 1, l.oz + z);
                        double k = r.nextDouble();
                        f.setType(k < 0.4 ? Material.ROOTED_DIRT : k < 0.6 ? Material.PODZOL : k < 0.75 ? Material.MUD : Material.COARSE_DIRT, false);
                    }
                    if (solid(l, x, y, z) && touchesAir(l, x, y, z) && r.nextDouble() < 0.18) {
                        l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(r.nextBoolean() ? Material.MUDDY_MANGROVE_ROOTS : Material.PACKED_MUD, false);
                    }
                }
            }
        }
        // shroomlights in the vault and a light in the middle: the nest glows warm, seen from the tunnels
        int placed = 0;
        for (int tries = 0; tries < 60 && placed < 4; tries++) {
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(1, R - 1);
            int x = (int) Math.floor(n.x() + Math.cos(a) * d), z = (int) Math.floor(n.z() + Math.sin(a) * d);
            for (int y = n.floor() + 9; y > n.floor() + 2; y--) {
                if (solid(l, x, y, z) && l.plan.air(x, y - 1, z)) {
                    l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(Material.SHROOMLIGHT, false);
                    placed++;
                    break;
                }
            }
        }
        light(l, new GalleryPlan.Point(n.x(), n.z(), n.floor()), 5, 12);
    }

    /**
     * The rooms (rich lairs only): mushrooms and moss along their edges, a puddle or two in the damp ones; never in the
     * middle where the guards fight.
     */
    private void chambers(Lair l, Random r) {
        GalleryPlan.Settings s = l.plan.settings;
        if (s.depth() <= s.size()) return;
        for (GalleryPlan.Node n : l.plan.nodes) {
            if (n.kind() != GalleryPlan.Kind.ROOM && n.kind() != GalleryPlan.Kind.JUNCTION) continue;
            int cx = (int) Math.floor(n.x()), cz = (int) Math.floor(n.z());
            int puddles = n.kind() == GalleryPlan.Kind.ROOM && n.level() == 1 || n.floor() < s.upper() - 1 ? 2 : r.nextInt(2);
            for (int x = cx - 5; x <= cx + 5; x++) {
                for (int z = cz - 5; z <= cz + 5; z++) {
                    double d = Math.hypot(x + 0.5 - n.x(), z + 0.5 - n.z());
                    if (d < 2.6) continue;
                    int y = l.plan.standY(x, n.floor(), z);
                    if (y == Integer.MIN_VALUE || y > s.top()) continue;
                    boolean edge = !l.plan.standable(x + 1, y, z) || !l.plan.standable(x - 1, y, z)
                            || !l.plan.standable(x, y, z + 1) || !l.plan.standable(x, y, z - 1);
                    if (!edge) continue;
                    Block b = l.world.getBlockAt(l.ox + x, y, l.oz + z);
                    if (b.getType() != Material.AIR) continue;
                    double k = r.nextDouble();
                    if (k < 0.08) b.setType(r.nextBoolean() ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM, false);
                    else if (k < 0.16) b.setType(Material.MOSS_CARPET, false);
                    else if (k < 0.2 && puddles > 0) {
                        // a puddle: the floor block turns to water, solid all round it at its level
                        Block f = b.getRelative(BlockFace.DOWN);
                        boolean closed = true;
                        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
                            if (!f.getRelative(face).getType().isSolid()) closed = false;
                        }
                        if (closed) {
                            f.setType(Material.WATER, false);
                            puddles--;
                        }
                    }
                }
            }
        }
    }

    /**
     * The surface around the lair (rich lairs only): the approach meadow (grass, molehills, mole holes, bushes, a few
     * trees, stones), the burrow hole the players go down, and a ring of wooded hills all round that hides the edges;
     * invisible walls along the meadow's edge.
     */
    private void surface(Lair l, Random r) {
        GalleryPlan.Settings s = l.plan.settings;
        if (s.depth() <= s.size()) return;
        int top = s.top(), size = s.size(), depth = s.depth(), margin = 16;
        // wooded hills all round
        for (int x = -margin; x < size + margin; x++) {
            for (int z = -margin; z < depth + margin; z++) {
                int out = Math.max(Math.max(-x, x - size + 1), Math.max(-z, z - depth + 1));
                if (out <= 0) continue;
                double n = noise(l.plan.seed ^ 0x51ED27L, x / 6.0, 0, z / 6.0);
                int h = (int) Math.round(Math.min(14, out * (0.7 + 0.35 * n)));
                for (int y = s.bottom(); y <= top + h; y++) {
                    Material m = y == top + h ? Material.GRASS_BLOCK : y > top + h - 3 ? Material.DIRT : Material.STONE;
                    l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(m, false);
                }
                if (out > 2 && r.nextDouble() < 0.06) tree(l, x, top + h + 1, z, r);
                else if (r.nextDouble() < 0.12) l.world.getBlockAt(l.ox + x, top + h + 1, l.oz + z).setType(Material.SHORT_GRASS, false);
            }
        }
        // the approach meadow
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < s.arenaZ0() - 1; z++) {
                Block b = l.world.getBlockAt(l.ox + x, top + 1, l.oz + z);
                if (b.getType() != Material.AIR || l.plan.air(x, top, z)) continue;
                double k = r.nextDouble();
                if (k < 0.18) b.setType(r.nextDouble() < 0.8 ? Material.SHORT_GRASS : Material.FERN, false);
                else if (k < 0.2) b.setType(r.nextBoolean() ? Material.DANDELION : Material.POPPY, false);
            }
        }
        for (int i = 0; i < 14; i++) {
            int x = 2 + r.nextInt(size - 4), z = 2 + r.nextInt(Math.max(1, s.arenaZ0() - 6));
            if (l.plan.air(x, top, z) || l.plan.air(x, top - 1, z) || near(l.plan.hole, x, z, 4)) continue;
            if (i < 9) molehill(l, x, top, z, r);
            else {
                // a mole hole: one block deep, a rim of loose earth
                l.world.getBlockAt(l.ox + x, top, l.oz + z).setType(Material.AIR, false);
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                    l.world.getBlockAt(l.ox + x + d[0], top + 1, l.oz + z + d[1]).setType(Material.AIR, false);
                l.world.getBlockAt(l.ox + x, top - 1, l.oz + z).setType(Material.COARSE_DIRT, false);
            }
        }
        for (int i = 0; i < 6; i++) {
            int x = r.nextBoolean() ? 1 + r.nextInt(4) : size - 2 - r.nextInt(4), z = 1 + r.nextInt(Math.max(1, s.arenaZ0() - 3));
            if (l.plan.air(x, top, z)) continue;
            if (i < 3) tree(l, x, top + 1, z, r);
            else {
                l.world.getBlockAt(l.ox + x, top + 1, l.oz + z).setType(Material.AZALEA_LEAVES, false);
                l.world.getBlockAt(l.ox + x + 1, top + 1, l.oz + z).setType(Material.FLOWERING_AZALEA_LEAVES, false);
            }
        }
        for (int i = 0; i < 4; i++) {
            int x = 1 + r.nextInt(size - 2), z = 1 + r.nextInt(Math.max(1, s.arenaZ0() - 3));
            if (!l.plan.air(x, top, z)) l.world.getBlockAt(l.ox + x, top + 1, l.oz + z).setType(r.nextBoolean() ? Material.MOSSY_COBBLESTONE : Material.ANDESITE, false);
        }
        // the burrow hole: loose earth heaped round it, roots hanging in its throat
        if (l.plan.hole != null) {
            int hx = (int) Math.floor(l.plan.hole.x()), hz = (int) Math.floor(l.plan.hole.z());
            for (int x = hx - 4; x <= hx + 4; x++) {
                for (int z = hz - 4; z <= hz + 4; z++) {
                    double d = Math.hypot(x - hx, z - hz);
                    if (d < 2.2 || d > 3.8 || l.plan.air(x, top, z)) continue;
                    l.world.getBlockAt(l.ox + x, top + 1, l.oz + z).setType(r.nextBoolean() ? Material.COARSE_DIRT : Material.ROOTED_DIRT, false);
                }
            }
            for (int x = hx - 3; x <= hx + 3; x++) {
                for (int z = hz - 3; z <= hz + 3; z++) {
                    for (int y = top - 3; y <= top; y++) {
                        if (l.plan.air(x, y, z) && !l.plan.air(x, y + 1, z) && y + 1 <= top && r.nextDouble() < 0.3)
                            l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(Material.HANGING_ROOTS, false);
                    }
                }
            }
        }
        // invisible walls along the meadow's edge (the arena has its own)
        for (int x = -1; x <= size; x++) {
            for (int z = -1; z < s.arenaZ0(); z++) {
                if (x == -1 || x == size || z == -1) {
                    for (int y = top + 1; y <= top + 14; y++) l.world.getBlockAt(l.ox + x, y, l.oz + z).setType(Material.BARRIER, false);
                }
            }
        }
    }

    private static boolean near(GalleryPlan.Point p, int x, int z, double d) {
        return p != null && Math.hypot(p.x() - x, p.z() - z) < d;
    }

    private void molehill(Lair l, int x, int top, int z, Random r) {
        l.world.getBlockAt(l.ox + x, top + 1, l.oz + z).setType(Material.COARSE_DIRT, false);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (r.nextDouble() < 0.7) l.world.getBlockAt(l.ox + x + d[0], top + 1, l.oz + z + d[1]).setType(Material.DIRT, false);
        }
        if (r.nextBoolean()) l.world.getBlockAt(l.ox + x, top + 2, l.oz + z).setType(Material.ROOTED_DIRT, false);
    }

    private void tree(Lair l, int x, int y, int z, Random r) {
        int h = 4 + r.nextInt(3);
        boolean birch = r.nextDouble() < 0.3;
        for (int k = 0; k < h; k++) l.world.getBlockAt(l.ox + x, y + k, l.oz + z).setType(birch ? Material.BIRCH_LOG : Material.OAK_LOG, false);
        Material leaves = birch ? Material.BIRCH_LEAVES : Material.OAK_LEAVES;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx * dx + dz * dz + dy * dy * 2 > 6 || r.nextDouble() < 0.08) continue;
                    Block b = l.world.getBlockAt(l.ox + x + dx, y + h - 1 + dy, l.oz + z + dz);
                    if (b.getType() != Material.AIR) continue;
                    b.setType(leaves, false);
                    if (b.getBlockData() instanceof org.bukkit.block.data.type.Leaves lv) {
                        lv.setPersistent(true);
                        b.setBlockData(lv, false);
                    }
                }
            }
        }
    }

    /** The arena: its earth floor is the top of the mass; a low bank of earth around, invisible walls above it. */
    private void arena(Lair l, Random r) {
        GalleryPlan.Settings s = l.plan.settings;
        int size = s.size(), top = s.top(), z0 = s.arenaZ0();
        boolean rich = s.depth() > size;
        for (int x = -1; x <= size; x++) {
            for (int z = -1; z <= size; z++) {
                boolean ring = x == -1 || z == -1 || x == size || z == size;
                boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                if (ring) {
                    // with an approach the galleries run under its southern side: the walls only stand above ground
                    for (int y = rich ? top + 1 : s.bottom(); y <= top + 12; y++) l.world.getBlockAt(l.ox + x, y, l.oz + z0 + z).setType(Material.BARRIER, false);
                } else if (rich && !edge && r.nextDouble() < 0.06) {
                    // turned earth on the floor, flat
                    l.world.getBlockAt(l.ox + x, top, l.oz + z0 + z).setType(r.nextBoolean() ? Material.COARSE_DIRT : Material.ROOTED_DIRT, false);
                } else if (edge) {
                    Block b = l.world.getBlockAt(l.ox + x, top + 1, l.oz + z0 + z);
                    b.setType(r.nextDouble() < 0.5 ? Material.COARSE_DIRT : Material.ROOTED_DIRT, false);
                    if (r.nextDouble() < 0.3) b.getRelative(BlockFace.UP).setType(Material.DIRT, false);
                }
            }
        }
    }

    private Location entrance(Lair l) {
        GalleryPlan.Settings s = l.plan.settings;
        if (l.plan.hole != null) {
            // on the meadow, at the far end from the arena, facing the burrow hole
            Location at = l.at(s.size() / 2.0, s.top() + 1, 3.5);
            at.setDirection(l.at(l.plan.hole.x(), s.top() + 1, l.plan.hole.z()).toVector().subtract(at.toVector()).setY(0));
            at.setPitch(0);
            return at;
        }
        GalleryPlan.Node e = l.plan.entrance;
        int x = (int) Math.floor(e.x()), z = (int) Math.floor(e.z());
        int y = l.plan.standY(x, e.floor(), z);
        if (y == Integer.MIN_VALUE) y = e.floor() + 1;
        Location at = l.at(x + 0.5, y, z + 0.5);
        Vector to = l.at(l.plan.trail.get(Math.min(8, l.plan.trail.size() - 1))).toVector().subtract(at.toVector());
        at.setDirection(to.setY(0).lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : to);
        at.setPitch(0);
        return at;
    }

    /** The boss, asleep in its nest until the players find it; it counts as the objective of the run. */
    private void spawnBoss(Run run, Lair l) {
        MobTemplate t = mobs.template(l.bossId);
        if (t == null) {
            plugin.getLogger().warning("Unknown boss " + l.bossId + " in the lair of " + run.def.id());
            return;
        }
        Location at = l.nest();
        int x = (int) Math.floor(l.plan.nest.x()), z = (int) Math.floor(l.plan.nest.z());
        int y = l.plan.standY(x, l.plan.nest.floor(), z);
        if (y != Integer.MIN_VALUE) at.setY(y);
        Location entrance = l.at(l.plan.entrance.x(), at.getY(), l.plan.entrance.z());
        at.setDirection(entrance.toVector().subtract(at.toVector()).setY(0));
        at.setPitch(0);
        LivingEntity boss = mobs.spawn(t, at, run.id);
        if (boss == null) return;
        l.boss = boss;
        mobs.sleep(boss, true);
        mobs.objective(boss, l.bossId);
        run.mobs.add(boss.getUniqueId());
        run.objectivesLeft.add(l.bossId);
        run.objectivesTotal = run.objectivesLeft.size();
    }

    /**
     * Monsters waiting in the galleries ({@code guards:} of the burrows section): {@code mobs} = id: weight, a group of
     * {@code per-room: [min, max]} in each room and {@code per-junction: [min, max]} at each crossing (never the
     * entrance nor the nest), {@code extra-per-player} more per player beyond the first (share of a monster, rounded
     * at random). They do not have to die: the boss is the objective.
     */
    private void guards(Run run, Lair l, ConfigurationSection g, Random r) {
        if (g == null) return;
        ConfigurationSection weights = g.getConfigurationSection("mobs");
        if (weights == null) return;
        List<MobTemplate> pool = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        double total = 0;
        for (String id : weights.getKeys(false)) {
            MobTemplate t = mobs.template(id);
            if (t == null) {
                plugin.getLogger().warning("Unknown guard " + id + " in the lair of " + run.def.id());
                continue;
            }
            pool.add(t);
            w.add(Math.max(0, weights.getDouble(id)));
            total += Math.max(0, weights.getDouble(id));
        }
        if (pool.isEmpty() || total <= 0) return;
        List<Integer> room = g.getIntegerList("per-room"), junction = g.getIntegerList("per-junction");
        double extra = g.getDouble("extra-per-player", 0.5) * Math.max(0, run.players().size() - 1);
        int placed = 0;
        boolean rich = l.plan.settings.depth() > l.plan.settings.size();
        for (GalleryPlan.Node n : l.plan.nodes) {
            List<Integer> range = n.kind() == GalleryPlan.Kind.ROOM ? room : n.kind() == GalleryPlan.Kind.JUNCTION ? junction : null;
            if (range == null || range.isEmpty()) continue;
            // a calm start (nobody near the entrance), fewer but nearer the boss under the arena
            if (rich && Math.hypot(n.x() - l.plan.entrance.x(), n.z() - l.plan.entrance.z()) < 10) continue;
            if (rich && n.kind() == GalleryPlan.Kind.JUNCTION && l.plan.underArena(n.z()) && r.nextBoolean()) continue;
            int min = range.get(0), max = range.size() > 1 ? range.get(1) : min;
            int count = min + (max > min ? r.nextInt(max - min + 1) : 0);
            count += (int) Math.floor(extra) + (r.nextDouble() < extra - Math.floor(extra) ? 1 : 0);
            for (int i = 0; i < count; i++) {
                double roll = r.nextDouble() * total;
                MobTemplate t = pool.get(pool.size() - 1);
                for (int k = 0; k < pool.size(); k++) {
                    roll -= w.get(k);
                    if (roll <= 0) {
                        t = pool.get(k);
                        break;
                    }
                }
                int x = (int) Math.floor(n.x() + r.nextDouble(-1.5, 1.5)), z = (int) Math.floor(n.z() + r.nextDouble(-1.5, 1.5));
                int y = l.plan.standY(x, n.floor(), z);
                if (y == Integer.MIN_VALUE) {
                    x = (int) Math.floor(n.x());
                    z = (int) Math.floor(n.z());
                    y = l.plan.standY(x, n.floor(), z);
                }
                if (y == Integer.MIN_VALUE) continue;
                Location at = l.at(x + 0.5, y, z + 0.5);
                at.setYaw(r.nextFloat() * 360);
                LivingEntity mob = mobs.spawn(t, at, run.id);
                if (mob == null) continue;
                run.mobs.add(mob.getUniqueId());
                placed++;
            }
        }
        plugin.getLogger().info("Lair of " + run.def.id() + ": " + placed + " guards in the galleries.");
    }

    // ---------------------------------------------------------------- hunt

    /** Every half second (Runs' tick) while the run plays. */
    void tick(Run run) {
        Lair l = lairs.get(run.id);
        if (l == null || l.stage != Stage.HUNT) return;
        l.ticks += 10;
        List<Player> players = runs.fighters(run);
        if (players.isEmpty()) return;
        if (l.ticks == 60) for (Player p : players) p.sendActionBar(Texts.parse(plugin.messages().raw("burrows.hint")));
        // somebody walked into the nest: the encounter
        Location nest = l.nest();
        for (Player p : players) {
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - nest.getX(), pl.getZ() - nest.getZ()) <= GalleryPlan.NEST_RADIUS + 0.5
                    && Math.abs(pl.getY() - nest.getY()) < 4) {
                encounter(run, l, p);
                return;
            }
        }
        // with an approach, the boss is only heard once somebody is under the arena; before, quiet earth sounds
        boolean rich = l.plan.settings.depth() > l.plan.settings.size();
        if (rich) {
            boolean nowNear = false;
            for (Player p : players) {
                if (p.getLocation().getY() <= l.plan.settings.top() && l.plan.underArena(l.localZ(p.getLocation()))) nowNear = true;
            }
            if (nowNear && !l.near) {
                l.near = true;
                l.nextTremor = l.ticks + 60;
                l.nextPassage = Math.max(l.nextPassage, l.ticks + 160);
                for (Player p : players) {
                    p.playSound(p.getLocation().add(0, -4, 0), "entity.warden.dig", SoundCategory.HOSTILE, 1.0f, 0.4f);
                    p.playSound(p.getLocation(), "block.sculk_shrieker.shriek", SoundCategory.AMBIENT, 0.25f, 0.5f);
                    p.sendActionBar(Texts.parse(plugin.messages().raw("burrows.near")));
                }
            }
            for (Player p : players) quiet(l, p);
            if (!l.near) {
                if (l.ticks % 20 == 0) for (Player p : players) guide(l, p);
                return;
            }
        }
        // it snores in its nest
        if (l.ticks >= l.nextSnore) {
            l.nextSnore = l.ticks + 80;
            snd(nest, "entity.sniffer.idle", 1.8f, 0.5f);
            snd(nest, "entity.polar_bear.ambient", 1.2f, 0.4f);
        }
        // dust drifting along the trail in front of whoever follows it
        if (l.ticks % 20 == 0) for (Player p : players) guide(l, p);
        // a tremor coming from the way to the nest
        if (l.ticks >= l.nextTremor) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            l.nextTremor = l.ticks + 20 * r.nextInt(14, 22);
            for (Player p : players) tremor(l, p);
        }
        // the boss passes behind the walls, near somebody
        if (l.ticks >= l.nextPassage && l.ticks > 200) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            l.nextPassage = l.ticks + 20 * r.nextInt(28, 42);
            passage(l, players.get(r.nextInt(players.size())));
        }
    }

    /**
     * Quiet sounds of the burrows for a player outside the boss' ground (drips, earth and gravel trickling, roots
     * creaking), now and then, from a few blocks away; nothing under the arena (the boss' sounds take over there).
     */
    private void quiet(Lair l, Player p) {
        if (l.plan.underArena(l.localZ(p.getLocation())) || p.getLocation().getY() > l.plan.settings.top()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int next = l.nextQuiet.getOrDefault(p.getUniqueId(), l.ticks + 100);
        if (l.ticks < next) {
            l.nextQuiet.putIfAbsent(p.getUniqueId(), next);
            return;
        }
        l.nextQuiet.put(p.getUniqueId(), l.ticks + 20 * r.nextInt(7, 15));
        Location at = p.getLocation().add(r.nextDouble(-5, 5), r.nextDouble(0, 3), r.nextDouble(-5, 5));
        switch (r.nextInt(5)) {
            case 0 -> p.playSound(at, "block.pointed_dripstone.drip_water", SoundCategory.AMBIENT, 0.6f, r.nextFloat(0.8f, 1.2f));
            case 1 -> p.playSound(at, "block.gravel.break", SoundCategory.AMBIENT, 0.2f, 0.6f);
            case 2 -> p.playSound(at, "block.rooted_dirt.break", SoundCategory.AMBIENT, 0.25f, 0.5f);
            case 3 -> p.playSound(at, "block.hanging_roots.break", SoundCategory.AMBIENT, 0.3f, 0.7f);
            default -> {
                // far away, hard to place: something moving under the ground
                if (l.ticks > 1200) p.playSound(p.getLocation().add(0, -6, 0), "block.rooted_dirt.step", SoundCategory.AMBIENT, 0.35f, 0.5f);
            }
        }
    }

    /** Index of the trail point nearest to a place (and its distance, in the array's second slot). */
    private static double[] nearestOnTrail(Lair l, Location at) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        List<GalleryPlan.Point> trail = l.plan.trail;
        for (int i = 0; i < trail.size(); i++) {
            Location p = l.at(trail.get(i));
            double d = p.distanceSquared(at);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return new double[]{best, Math.sqrt(bd)};
    }

    /** Falling dust a few blocks ahead along the trail, when the player is on it (shows him the way). */
    private void guide(Lair l, Player p) {
        double[] n = nearestOnTrail(l, p.getLocation());
        if (n[0] < 0 || n[1] > 5) return;
        int i = (int) n[0];
        List<GalleryPlan.Point> trail = l.plan.trail;
        BlockData dirt = Material.DIRT.createBlockData();
        for (int k = 1; k <= 3; k++) {
            int j = Math.min(trail.size() - 1, i + k * 10);
            Location at = l.at(trail.get(j)).add(0, 2.6, 0);
            p.spawnParticle(Particle.FALLING_DUST, at, 4, 0.5, 0.2, 0.5, 0, dirt);
        }
    }

    /** A tremor: the ground shakes, dust falls, a deep sound from the way to the nest (a few blocks ahead on it). */
    private void tremor(Lair l, Player p) {
        double[] n = nearestOnTrail(l, p.getLocation());
        if (n[0] < 0) return;
        List<GalleryPlan.Point> trail = l.plan.trail;
        Location from = l.at(trail.get(Math.min(trail.size() - 1, (int) n[0] + 30)));
        // heard from the trail's direction, loud enough wherever he is
        Vector dir = from.toVector().subtract(p.getLocation().toVector());
        Location src = p.getLocation().add(dir.lengthSquared() > 1e-4 ? dir.normalize().multiply(6) : new Vector());
        p.playSound(src, "entity.warden.dig", SoundCategory.HOSTILE, 1.4f, 0.5f);
        p.playSound(src, "block.rooted_dirt.break", SoundCategory.HOSTILE, 1.2f, 0.5f);
        p.playSound(src, "block.grindstone.use", SoundCategory.HOSTILE, 0.5f, 0.4f);
        p.spawnParticle(Particle.FALLING_DUST, p.getLocation().add(0, 3, 0), 30, 2.5, 0.3, 2.5, 0, Material.DIRT.createBlockData());
        p.sendHurtAnimation(0);
        p.sendActionBar(Texts.parse(plugin.messages().raw("burrows.tremor")));
    }

    /** The boss heard (and its dust seen) going along a tunnel near the player, 6 to 14 blocks away. */
    private void passage(Lair l, Player p) {
        List<List<GalleryPlan.Point>> near = new ArrayList<>();
        boolean rich = l.plan.settings.depth() > l.plan.settings.size();
        for (List<GalleryPlan.Point> t : l.plan.tunnels) {
            if (rich && !l.plan.underArena(t.get(t.size() / 2).z())) continue;
            double best = Double.MAX_VALUE;
            for (GalleryPlan.Point q : t) best = Math.min(best, l.at(q).distance(p.getLocation()));
            if (best >= 6 && best <= 14) near.add(t);
        }
        if (near.isEmpty()) return;
        List<GalleryPlan.Point> t = near.get(ThreadLocalRandom.current().nextInt(near.size()));
        boolean back = ThreadLocalRandom.current().nextBoolean();
        int span = Math.min(t.size(), 60);
        Fx fx = mobs.fx();
        new BukkitRunnable() {
            int k;

            @Override
            public void run() {
                if (k >= span || !p.isOnline() || l.stage != Stage.HUNT) {
                    cancel();
                    return;
                }
                GalleryPlan.Point q = t.get(back ? t.size() - 1 - k : k);
                Location at = l.at(q);
                if (k % 4 == 0) {
                    snd(at, "entity.warden.dig", 1.3f, 0.6f);
                    snd(at, "block.rooted_dirt.break", 1.0f, 0.6f);
                }
                if (k % 8 == 0) snd(at, "block.grindstone.use", 0.7f, 0.45f);
                l.world.spawnParticle(Particle.FALLING_DUST, at.clone().add(0, 3, 0), 6, 1.2, 0.2, 1.2, 0, Material.DIRT.createBlockData());
                if (k % 6 == 0) fx.trail(at);
                k += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ---------------------------------------------------------------- encounter and transition

    /**
     * The scene of the nest (about 7 s): the boss turns and revs its drill, charges the player, the nest falls in and
     * everyone is thrown up; the screen goes dark; everyone falls onto the arena; the boss surges in the middle; fight.
     */
    private void encounter(Run run, Lair l, Player finder) {
        l.stage = Stage.ENCOUNTER;
        LivingEntity boss = l.boss;
        Fx fx = mobs.fx();
        Location nest = l.nest();
        BlockData dirt = Material.DIRT.createBlockData();
        for (Player p : runs.online(run)) {
            p.showTitle(Title.title(Texts.parse(plugin.messages().raw("burrows.found-title")),
                    Texts.parse(plugin.messages().raw("burrows.found-subtitle")),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2500), Duration.ofMillis(400))));
        }
        Location[] target = {finder.getLocation()};
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!runs.all().contains(run) || run.state != Run.State.RUNNING) {
                    cancel();
                    return;
                }
                if (boss == null || !boss.isValid()) {
                    // no boss (unknown id): straight to the arena
                    if (t == 0) toArena(run, l);
                    cancel();
                    return;
                }
                Location at = boss.getLocation();
                if (finder.isOnline() && finder.getWorld() == l.world) target[0] = finder.getLocation();
                Vector dir = target[0].toVector().subtract(at.toVector()).setY(0);
                if (dir.lengthSquared() < 1e-4) dir = at.getDirection().setY(0);
                dir.normalize();
                if (t == 0) {
                    turn(boss, dir);
                    mobs.play(boss, "detect");
                    snd(at, "entity.ravager.roar", 2f, 0.5f);
                    snd(at, "block.grindstone.use", 1.6f, 0.5f);
                    fx.crater(at, 4, 80, 0.4, Fx.BROKEN_EARTH);
                    fx.collapse(nest, GalleryPlan.NEST_RADIUS, 6, 5, dirt);
                    for (Player p : runs.fighters(run)) if (p.getLocation().distanceSquared(nest) < 400) p.sendHurtAnimation(0);
                }
                if (t > 0 && t < 30 && t % 3 == 0) Fx.drillSpin(at.clone().add(dir.clone().multiply(3)).add(0, 1, 0), dir, 0.5, t, dirt);
                if (t == 30) {
                    turn(boss, dir);
                    mobs.play(boss, "charge_windup");
                    snd(at, "entity.ravager.ambient", 1.8f, 0.5f);
                }
                if (t > 30 && t < 58 && t % 2 == 0) {
                    for (double k = 2; k < 10; k += 0.8) {
                        Location p = at.clone().add(dir.clone().multiply(k)).add(0, 0.15, 0);
                        l.world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(t > 46 ? Color.fromRGB(0xFF5C5C) : DUST, 1.3f));
                    }
                    if (t % 4 == 0) snd(at, "block.grindstone.use", 1.4f, 0.5f + (t - 30) * 0.02f);
                }
                if (t == 58) {
                    mobs.loop(boss, "charge_run", true);
                    snd(at, "entity.ravager.roar", 2f, 0.6f);
                }
                if (t > 58 && t < 72) {
                    Location next = at.clone().add(dir.clone().multiply(0.7));
                    next.setDirection(dir);
                    double reach = target[0].distance(at);
                    if (reach > 2.2 && next.clone().add(0, 1, 0).getBlock().isPassable()) Mobs.move(boss, next);
                    fx.spray(at, dir.clone().multiply(-1).setY(0.6), dirt, 8);
                }
                if (t == 72) {
                    // the hit: everybody in the nest thrown up, the nest falls in
                    mobs.loop(boss, "charge_run", false);
                    mobs.play(boss, "charge_stop");
                    snd(at, "entity.generic.explode", 2f, 0.5f);
                    snd(at, "entity.zombie.attack_iron_door", 1.6f, 0.5f);
                    snd(at, "block.rooted_dirt.break", 2f, 0.4f);
                    l.world.spawnParticle(Particle.EXPLOSION, at.clone().add(dir.clone().multiply(2.5)).add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0);
                    fx.collapse(nest, GalleryPlan.NEST_RADIUS + 1, 30, 6, dirt);
                    fx.debris(at.clone().add(dir.clone().multiply(2.5)).add(0, 1, 0), 18, dirt, 1.4);
                    for (Player p : runs.online(run)) {
                        p.sendHurtAnimation(0);
                        if (p.getLocation().distanceSquared(nest) < 14 * 14) p.setVelocity(dir.clone().multiply(0.4).setY(1.25));
                    }
                }
                if (t == 78) {
                    for (Player p : runs.online(run)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0, false, false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false));
                        p.showTitle(Title.title(Texts.parse(" "), Texts.parse(plugin.messages().raw("burrows.thrown")),
                                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(400))));
                    }
                }
                if (t == 84) {
                    toArena(run, l);
                    cancel();
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static void turn(LivingEntity e, Vector dir) {
        Location at = e.getLocation();
        at.setDirection(dir);
        at.setPitch(0);
        Mobs.move(e, at);
    }

    /** Everyone falls onto the arena, the boss surges in its middle, the fight starts; the galleries are closed. */
    private void toArena(Run run, Lair l) {
        Location center = l.arena();
        Fx fx = mobs.fx();
        List<Player> all = runs.online(run);
        for (int i = 0; i < all.size(); i++) {
            Player p = all.get(i);
            double a = Math.PI * 2 * i / Math.max(1, all.size()) + 0.4;
            Location land = center.clone().add(Math.cos(a) * 7.5, 6, Math.sin(a) * 7.5);
            land.setDirection(center.toVector().subtract(land.toVector()).setY(0));
            land.setPitch(10);
            runs.teleport(p, land);
            p.setFallDistance(0);
            p.setVelocity(new Vector(0, -0.2, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 26, 0, false, false));
            p.playSound(land, "block.rooted_dirt.break", SoundCategory.HOSTILE, 1.4f, 0.6f);
        }
        run.checkpoint = center.clone().add(0, 0, -9);
        run.checkpoint.setDirection(new Vector(0, 0, 1));
        LivingEntity boss = l.boss;
        if (boss == null || !boss.isValid()) {
            l.stage = Stage.FIGHT;
            return;
        }
        // the boss under the middle of the arena, hidden, then it surges
        Location spot = center.clone();
        spot.setDirection(new Vector(0, 0, -1));
        Mobs.move(boss, spot.clone().add(0, 0, 1.6));
        mobs.parts(boss, false, BONES);
        mobs.rehome(boss, center);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!runs.all().contains(run) || !boss.isValid()) {
                    cancel();
                    return;
                }
                if (t >= 10 && t < 40 && t % 2 == 0) {
                    Fx.rumble(spot, 3.2, (t - 10) / 30.0, (t > 28 ? Color.fromRGB(0xFF5C5C) : DUST));
                    if (t % 6 == 0) snd(spot, "entity.warden.dig", 1.6f, 0.5f + (t - 10) * 0.012f);
                }
                if (t == 10) fx.crater(spot, 3.5, 80, 0.4, Fx.BROKEN_EARTH);
                if (t == 20) for (Player p : runs.online(run)) {
                    p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation(), 20, 0.5, 0.1, 0.5, 0, Material.DIRT.createBlockData());
                }
                if (t == 40) {
                    mobs.parts(boss, true, BONES);
                    mobs.play(boss, "emerge");
                }
                if (t == 48) {
                    fx.eruption(spot, 3, 2.4);
                    fx.shockwave(spot, 8, 8, 0.5);
                    snd(spot, "entity.generic.explode", 2f, 0.5f);
                    snd(spot, "entity.ravager.roar", 2f, 0.5f);
                    for (Player p : runs.fighters(run)) {
                        p.sendHurtAnimation(0);
                        if (p.getLocation().distanceSquared(spot) < 16) {
                            Vector v = p.getLocation().toVector().subtract(spot.toVector()).setY(0);
                            p.setVelocity((v.lengthSquared() < 1e-4 ? new Vector(1, 0, 0) : v.normalize()).multiply(0.8).setY(0.4));
                        }
                        p.showTitle(Title.title(Texts.parse(plugin.messages().raw("burrows.fight-title")),
                                Texts.parse(plugin.messages().raw("burrows.fight-subtitle")),
                                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2200), Duration.ofMillis(500))));
                    }
                    runs.bossOf(run, boss);
                }
                if (t == 80) {
                    mobs.sleep(boss, false);
                    l.stage = Stage.FIGHT;
                    cancel();
                }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Bones of the boss' model hidden while it waits under the arena (cf. MolePowers.BONES). */
    private static final List<String> BONES = List.of("Body", "Head", "Drill", "Tail", "Arm_front_right", "Arm_front_left",
            "Paw_front_right", "Paw_front_left", "Leg_back_right", "Leg_back_left");

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }
}
