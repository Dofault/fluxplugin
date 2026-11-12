package fr.influencraft.fluxportal.window;

import fr.influencraft.fluxportal.Frame;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The window of one side of a portal: what is behind the other side, drawn by the client's text shader
 * ({@code resources/window/*}) from maps hung in invisible item frames over the hole. Each map is 128x128 symbols
 * ({@link MapColours}):
 * <ul>
 *   <li>28 maps hold one block layer each of the far side (128 across, 63 deep, from 8 blocks under the hole's middle
 *   up): a cell is a material (of up to 19 per map) and a light level; under it the header row (where this side is,
 *   its size, whether it is open, the materials' shapes and textures, the far side's place and dimension), the
 *   textures themselves (up to 27 tiles) and the full blocks just above and below the layer (ambient occlusion).</li>
 *   <li>a horizon map (ground height and colour of 3x3 columns up to ~380 blocks away), a canopy map (tree tops over
 *   it) and a cloud map (the game's clouds over the far side, with its clock).</li>
 * </ul>
 * Every map hangs in every block of the hole (and a margin around it), so all of them draw over each pixel; each
 * fragment marches its own layer and writes its depth by distance, which sorts them. The far side is read a layer at a
 * time ({@link #sample}), while players are near. Window technique of RPE-portals (GPL-3.0).
 */
final class Window {

    static final int WIDTH = 128, DEPTH = 63, MAPS = 28, BASE = -8, TILES = 27, MATERIALS = 19;
    static final int HEADER = DEPTH * WIDTH;
    private static final int TABLE = 16, FAR = 111, OCCLUDERS = 120, ENV = 122;
    private static final int OCC_X = 48, OCC_Y = 112, OCC_W = 80, OCC_FROM = 24, OCC_DEPTH = 40;
    private static final int H_CELL = 3, H_ACROSS = 64, H_DEPTH = 127, H_BIAS = 60;
    private static final int CLOUD_FIELDS = 16, CLOUD_PERIOD = 102400;
    static final String TAG = "fluxportal_window";

    final String key;
    final Frame here, there;
    final World hereWorld, thereWorld;
    private final MapColours colours;
    private final BlockLooks looks;

    final List<MapView> maps = new ArrayList<>();
    private final List<Layer> layers = new ArrayList<>();
    private MapView horizonMap, canopyMap, cloudMap;
    private final Layer horizon = new Layer(), canopy = new Layer(), clouds = new Layer();
    private final byte[] heights = new byte[H_ACROSS * H_DEPTH], tops = new byte[H_ACROSS * H_DEPTH];
    private final byte[] canopyHeights = new byte[H_ACROSS * H_DEPTH], canopyTops = new byte[H_ACROSS * H_DEPTH];
    final List<ItemFrame> frames = new ArrayList<>();
    private final int farEnv, nearEnv, fog;
    private boolean open;
    /** Next layer to read again, layers read since the window opened. */
    int next, sampled;
    long horizonAt = Long.MIN_VALUE;
    boolean horizonBusy;
    private int cloudTicks = -1;
    private boolean closed;

    Window(String key, Frame here, World hereWorld, Frame there, World thereWorld, boolean open, MapColours colours, BlockLooks looks) {
        this.key = key;
        this.here = here;
        this.there = there;
        this.hereWorld = hereWorld;
        this.thereWorld = thereWorld;
        this.open = open;
        this.colours = colours;
        this.looks = looks;
        int px = (int) Math.floor(there.worldX(0, 8)), py = (int) Math.floor(there.worldY(0)), pz = (int) Math.floor(there.worldZ(0, 8));
        farEnv = env(thereWorld);
        nearEnv = env(hereWorld);
        fog = fogOf(thereWorld, px, py, pz);
        Arrays.fill(heights, colours.symbol(199));
        Arrays.fill(canopyHeights, colours.symbol(199));
    }

    // ------------------------------------------------------------------ maps and frames

    /** Takes its maps (from {@code take}), hangs them over the hole. */
    void hang(Plugin plugin, java.util.function.Supplier<MapView> take, int margin) {
        for (int k = 0; k < MAPS; k++) {
            Layer layer = new Layer();
            maps.add(prepare(take.get(), layer));
            layers.add(layer);
        }
        horizonMap = prepare(take.get(), horizon);
        canopyMap = prepare(take.get(), canopy);
        cloudMap = prepare(take.get(), clouds);
        for (int k = 0; k < MAPS; k++) writeHeader(layers.get(k).pixels, k, null);
        writeHorizon();
        writeClouds();

        int[] n = {here.nx(), here.nz()};
        BlockFace face = n[0] > 0 ? BlockFace.EAST : n[0] < 0 ? BlockFace.WEST : n[1] > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        int hw = (int) Math.round(here.halfWidth() - 0.5), height = (int) Math.round(here.halfHeight() * 2), by = here.bottomY();
        for (int i = -hw - margin; i <= hw + margin; i++) {
            for (int j = -margin; j < height + margin; j++) {
                // the block in front of the plane: the frame hangs on the plane, facing out
                int fx = (int) Math.floor(here.worldX(i, 0.5)), fz = (int) Math.floor(here.worldZ(i, 0.5));
                Location at = new Location(hereWorld, fx + 0.5, by + j + 0.5, fz + 0.5);
                for (MapView map : allMaps()) {
                    ItemStack item = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) item.getItemMeta();
                    meta.setMapView(map);
                    item.setItemMeta(meta);
                    frames.add(hereWorld.spawn(at, ItemFrame.class, frame -> {
                        frame.setFacingDirection(face, true);
                        frame.setFixed(true);
                        frame.setVisible(false);
                        frame.setInvulnerable(true);
                        frame.setSilent(true);
                        frame.setPersistent(false);
                        frame.setItem(item, false);
                        frame.addScoreboardTag(TAG);
                    }));
                }
            }
        }
    }

    private static MapView prepare(MapView map, Layer layer) {
        for (MapRenderer r : map.getRenderers()) map.removeRenderer(r);
        map.addRenderer(layer);
        map.setTrackingPosition(false);
        map.setUnlimitedTracking(false);
        return map;
    }

    List<MapView> allMaps() {
        List<MapView> all = new ArrayList<>(maps);
        if (horizonMap != null) all.addAll(List.of(horizonMap, canopyMap, cloudMap));
        return all;
    }

    /** Takes the frames down; the maps go back to the caller's pool. */
    List<MapView> unhang() {
        closed = true;
        for (ItemFrame f : frames) f.remove();
        frames.clear();
        List<MapView> all = allMaps();
        for (MapView map : all) for (MapRenderer r : map.getRenderers()) map.removeRenderer(r);
        maps.clear();
        horizonMap = canopyMap = cloudMap = null;
        return all;
    }

    /** Opens or shuts the hole without taking anything down. */
    void open(boolean open) {
        if (this.open == open) return;
        this.open = open;
        for (int k = 0; k < layers.size(); k++) if (writeHeader(layers.get(k).pixels, k, null)) layers.get(k).version++;
        if (writeHeader(horizon.pixels, MAPS, null)) horizon.version++;
        if (writeHeader(canopy.pixels, MAPS + 1, null)) canopy.version++;
        if (writeHeader(clouds.pixels, MAPS + 2, null)) clouds.version++;
    }

    // ------------------------------------------------------------------ the far side, layer by layer

    /** Reads layer {@code k} of the far side again (main thread). */
    void sample(int k) {
        World world = thereWorld;
        int floorY = (int) Math.floor(there.worldY(0));
        int y = floorY + BASE + k;
        BlockLooks.Look[] cellLook = new BlockLooks.Look[DEPTH * WIDTH];
        int[] cellLight = new int[cellLook.length];
        Map<BlockLooks.Look, Integer> counts = new IdentityHashMap<>();
        if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
            for (int iz = 0; iz < DEPTH; iz++) {
                double c = iz + 0.5;
                for (int ix = 0; ix < WIDTH; ix++) {
                    int ia = ix - WIDTH / 2;
                    int x = (int) Math.floor(there.worldX(-ia, c)), z = (int) Math.floor(there.worldZ(-ia, c));
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    BlockData data = world.getBlockData(x, y, z);
                    if (BlockLooks.empty(data)) continue;
                    BlockLooks.Look look = looks.of(data, looks.tint(world, x, y, z));
                    int i = iz * WIDTH + ix;
                    if (look.shape() == BlockLooks.LIQUID) {
                        // only the surface: the depth under it, and what lies at the bottom
                        if (y + 1 < world.getMaxHeight() && world.getBlockData(x, y + 1, z).getMaterial() == data.getMaterial()) continue;
                        int depth = 0;
                        BlockLooks.Look bottom = null;
                        for (int dy = 1; dy <= 8 && y - dy >= world.getMinHeight(); dy++) {
                            BlockData under = world.getBlockData(x, y - dy, z);
                            if (BlockLooks.empty(under)) break;
                            BlockLooks.Look u = looks.of(under, looks.tint(world, x, y - dy, z));
                            if (u.shape() == BlockLooks.CROSS || u.shape() == BlockLooks.VINE || u.shape() == BlockLooks.LIQUID) {
                                depth++;
                                continue;
                            }
                            bottom = u;
                            break;
                        }
                        look = looks.liquidOver(look, bottom);
                        cellLight[i] = Math.min(15, depth * 2);
                    } else {
                        int l = Math.max(light(world, x, y + 1, z),
                                light(world, (int) Math.floor(there.worldX(-ia, c - 1)), y, (int) Math.floor(there.worldZ(-ia, c - 1))));
                        if (l < 15) {
                            l = Math.max(l, Math.max(light(world, x + 1, y, z), light(world, x - 1, y, z)));
                            l = Math.max(l, Math.max(light(world, x, y, z + 1), light(world, x, y, z - 1)));
                            l = Math.max(l, light(world, x, y - 1, z));
                        }
                        cellLight[i] = l;
                    }
                    cellLook[i] = look;
                    counts.merge(look, 1, Integer::sum);
                }
            }
        }

        // the materials of this layer: full blocks first (ambient occlusion counts them), then the most seen
        List<BlockLooks.Look> order = new ArrayList<>(counts.keySet());
        order.sort((a, b) -> a.occludes() != b.occludes() ? (a.occludes() ? -1 : 1) : counts.get(b) - counts.get(a));
        Map<String, Integer> tileIndex = new HashMap<>();
        List<int[]> tiles = new ArrayList<>();
        Map<BlockLooks.Look, Integer> materialOf = new IdentityHashMap<>();
        List<int[]> materials = new ArrayList<>();
        for (BlockLooks.Look look : order) {
            if (materials.size() >= MATERIALS) break;
            int top = tile(look.top(), tileIndex, tiles), side = tile(look.side(), tileIndex, tiles), bottom = tile(look.bottom(), tileIndex, tiles);
            if (side < 0) side = top;
            if (top < 0) top = side;
            if (bottom < 0) bottom = top;
            if (top < 0) continue;
            materialOf.put(look, materials.size());
            materials.add(new int[]{look.shape(), top, side, bottom, flags(look)});
        }

        byte[] fresh = new byte[WIDTH * WIDTH];
        for (int i = 0; i < cellLook.length; i++) {
            BlockLooks.Look look = cellLook[i];
            if (look == null) continue;
            Integer m = materialOf.get(look);
            if (m == null) m = nearest(look, materialOf);
            if (m != null) fresh[i] = colours.symbol(m * 8 + (cellLight[i] >> 1));
        }
        for (int t = 0; t < tiles.size(); t++) colours.encodeTile(tiles.get(t), fresh, t % 8 * 16, 64 + t / 8 * 16);

        // full blocks just under and over the layer, seven to a symbol
        int[] occ = new int[2 * OCC_W * OCC_DEPTH / 7 + 1];
        for (int side = 0; side < 2; side++) {
            int oy = y + (side == 0 ? -1 : 1);
            if (oy < world.getMinHeight() || oy >= world.getMaxHeight()) continue;
            for (int iz = 0; iz < OCC_DEPTH; iz++) {
                double c = iz + 0.5;
                for (int ix = OCC_FROM; ix < OCC_FROM + OCC_W; ix++) {
                    int ia = ix - WIDTH / 2;
                    int x = (int) Math.floor(there.worldX(-ia, c)), z = (int) Math.floor(there.worldZ(-ia, c));
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    BlockData data = world.getBlockData(x, oy, z);
                    if (BlockLooks.empty(data) || !looks.of(data, looks.tint(world, x, oy, z)).occludes()) continue;
                    int idx = side * OCC_W * OCC_DEPTH + iz * OCC_W + (ix - OCC_FROM);
                    occ[idx / 7] |= 1 << idx % 7;
                }
            }
        }
        for (int p = 0; p < 2 * OCC_W * OCC_DEPTH / 7 + 1; p++) fresh[(OCC_Y + p / OCC_W) * WIDTH + OCC_X + p % OCC_W] = colours.symbol(occ[p]);

        writeHeader(fresh, k, materials);
        Layer layer = layers.get(k);
        if (!Arrays.equals(fresh, layer.pixels)) {
            System.arraycopy(fresh, 0, layer.pixels, 0, fresh.length);
            layer.version++;
        }
    }

    private static int light(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return 15;
        Block b = world.getBlockAt(x, y, z);
        return Math.max(b.getLightFromSky(), b.getLightFromBlocks());
    }

    private static int tile(int[] texels, Map<String, Integer> index, List<int[]> tiles) {
        String key = Arrays.toString(texels);
        Integer known = index.get(key);
        if (known != null) return known;
        if (tiles.size() >= TILES) return -1;
        tiles.add(texels);
        index.put(key, tiles.size() - 1);
        return tiles.size() - 1;
    }

    /** The material of this layer looking most like {@code look} (colour, then shape). */
    private static Integer nearest(BlockLooks.Look look, Map<BlockLooks.Look, Integer> materialOf) {
        Integer best = null;
        int bestD = Integer.MAX_VALUE;
        for (Map.Entry<BlockLooks.Look, Integer> e : materialOf.entrySet()) {
            int a = look.colour(), b = e.getKey().colour();
            int dr = (a >> 16 & 0xFF) - (b >> 16 & 0xFF), dg = (a >> 8 & 0xFF) - (b >> 8 & 0xFF), db = (a & 0xFF) - (b & 0xFF);
            int d = dr * dr + dg * dg + db * db + (look.shape() == e.getKey().shape() ? 0 : 20000);
            if (d < bestD) {
                bestD = d;
                best = e.getValue();
            }
        }
        return best;
    }

    /** A material's flags: the axis of a log as the grid has it (x16), the walls a vine climbs in the grid. */
    private int flags(BlockLooks.Look look) {
        int axis = 0;
        if (look.axis() != 'y') {
            int[] g = grid(there.carryDirection(here, look.axis() == 'x' ? new Vector(1, 0, 0) : new Vector(0, 0, 1)));
            axis = g[0] != 0 ? 1 : 2;
        }
        int faces = 0;
        if (look.shape() == BlockLooks.CROSS) faces = look.faces();
        if (look.shape() == BlockLooks.VINE) {
            Vector[] dirs = {new Vector(0, 0, -1), new Vector(1, 0, 0), new Vector(0, 0, 1), new Vector(-1, 0, 0)};
            for (int i = 0; i < 4; i++) {
                if ((look.faces() & 1 << i) == 0) continue;
                int[] g = grid(there.carryDirection(here, dirs[i]));
                if (g[0] > 0) faces |= 1;
                if (g[0] < 0) faces |= 2;
                if (g[1] > 0) faces |= 4;
                if (g[1] < 0) faces |= 8;
            }
        }
        return axis * 16 + faces;
    }

    /** A direction of this side's world in the window's grid (x across, z away into the window). */
    private int[] grid(Vector v) {
        return new int[]{(int) Math.round(here.a(here.worldX(0, 0) + v.getX(), here.worldZ(0, 0) + v.getZ())),
                (int) Math.round(-here.c(here.worldX(0, 0) + v.getX(), here.worldZ(0, 0) + v.getZ()))};
    }

    private static int env(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> 1;
            case THE_END -> 2;
            default -> 0;
        };
    }

    /** The far side's fog (and sky) colour: the Nether's by biome, the End's, else a blue by temperature. */
    private static int fogOf(World world, int x, int y, int z) {
        String biome = world.getBiome(x, y, z).getKey().getKey();
        return switch (world.getEnvironment()) {
            case NETHER -> switch (biome) {
                case "crimson_forest" -> 0x330303;
                case "warped_forest" -> 0x1A051A;
                case "soul_sand_valley" -> 0x1B4745;
                case "basalt_deltas" -> 0x685F70;
                default -> 0x330808;
            };
            case THE_END -> 0x181318;
            default -> {
                float f = (float) Math.max(-1, Math.min(1, world.getTemperature(x, y, z) / 3.0));
                yield Color.HSBtoRGB(0.62222224f - f * 0.05f, 0.5f + f * 0.1f, 1f) & 0xFFFFFF;
            }
        };
    }

    /** The header row of a map; false when nothing changed. */
    private boolean writeHeader(byte[] px, int slab, List<int[]> materials) {
        int bx = (int) Math.floor(here.worldX(0, 0)), by = (int) Math.floor(here.worldY(0)), bz = (int) Math.floor(here.worldZ(0, 0));
        int[] fields = {-1, -1, slab, MAPS,
                Math.floorMod(bx, 40000) / 200, Math.floorMod(bx, 40000) % 200,
                (by + 1000) / 200, (by + 1000) % 200,
                Math.floorMod(bz, 40000) / 200, Math.floorMod(bz, 40000) % 200,
                here.facing(), (int) Math.round(here.heightOffset() * 199),
                (int) Math.min(199, Math.round(here.halfWidth() * 20)), (int) Math.min(199, Math.round(here.halfHeight() * 20)),
                open ? 1 : 0};
        boolean changed = false;
        for (int i = 0; i < fields.length; i++) {
            byte v = i == 0 ? colours.mark0() : i == 1 ? colours.mark1() : colours.symbol(fields[i]);
            if (px[HEADER + i] != v) {
                px[HEADER + i] = v;
                changed = true;
            }
        }
        long fbx = Math.floorMod((long) Math.floor(there.worldX(0, 0)), 1L << 28), fbz = Math.floorMod((long) Math.floor(there.worldZ(0, 0)), 1L << 28);
        int[] far = {(int) (fbx / 8000000), (int) (fbx / 40000 % 200), (int) (fbx / 200 % 200), (int) (fbx % 200),
                (int) (fbz / 8000000), (int) (fbz / 40000 % 200), (int) (fbz / 200 % 200), (int) (fbz % 200), there.facing()};
        for (int i = 0; i < far.length; i++) changed |= put(px, HEADER + FAR + i, colours.symbol(far[i]));
        int[] env = {farEnv, (fog >> 16 & 0xFF) * 199 / 255, (fog >> 8 & 0xFF) * 199 / 255, (fog & 0xFF) * 199 / 255, nearEnv};
        for (int i = 0; i < env.length; i++) changed |= put(px, HEADER + ENV + i, colours.symbol(env[i]));
        if (materials != null) {
            int occluders = 0;
            while (occluders < materials.size() && materials.get(occluders)[0] <= BlockLooks.CUTOUT) occluders++;
            px[HEADER + OCCLUDERS] = colours.symbol(occluders);
            for (int m = 0; m < materials.size(); m++) {
                for (int f = 0; f < 5; f++) px[HEADER + TABLE + m * 5 + f] = colours.symbol(materials.get(m)[f]);
            }
        }
        return changed;
    }

    private static boolean put(byte[] px, int at, byte v) {
        if (px[at] == v) return false;
        px[at] = v;
        return true;
    }

    // ------------------------------------------------------------------ far land, tree tops, clouds

    /** Reads the ground and tree tops of the far land (chunks loaded in the background, never generated). */
    void readHorizon(long now) {
        horizonBusy = true;
        horizonAt = now;
        World world = thereWorld;
        int floor = (int) Math.floor(there.worldY(0)) + BASE;
        boolean roofed = world.getEnvironment() == World.Environment.NETHER;
        int roofStart = floor + MAPS;
        Map<Long, List<int[]>> byChunk = new HashMap<>();
        for (int hz = 0; hz < H_DEPTH; hz++) {
            for (int hx = 0; hx < H_ACROSS; hx++) {
                double a = hx * H_CELL + 1.5 - H_ACROSS * H_CELL / 2.0, c = hz * H_CELL + 1.5;
                int x = (int) Math.floor(there.worldX(-a, c)), z = (int) Math.floor(there.worldZ(-a, c));
                long key = (long) (x >> 4) << 32 | (z >> 4) & 0xFFFFFFFFL;
                byChunk.computeIfAbsent(key, kk -> new ArrayList<>()).add(new int[]{hz * H_ACROSS + hx, x, z});
            }
        }
        AtomicInteger left = new AtomicInteger(byChunk.size());
        for (Map.Entry<Long, List<int[]>> e : byChunk.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) e.getKey().longValue();
            world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, err) -> {
                if (chunk != null && !closed) {
                    ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
                    for (int[] col : e.getValue()) column(world, snap, col, floor, roofed, roofStart);
                }
                if (left.decrementAndGet() == 0) {
                    horizonBusy = false;
                    if (!closed) writeHorizon();
                }
            });
        }
    }

    private void column(World world, ChunkSnapshot snap, int[] col, int floor, boolean roofed, int roofStart) {
        int lx = col[1] & 15, lz = col[2] & 15;
        int top = Math.min(world.getMaxHeight() - 1, snap.getHighestBlockYAt(lx, lz));
        if (roofed) {
            // under the Nether's roof: the first open space under the layers' top
            top = Math.min(top, roofStart);
            while (top > world.getMinHeight() && !BlockLooks.empty(snap.getBlockData(lx, top, lz))) top--;
        }
        BlockData data = snap.getBlockData(lx, top, lz);
        while (top > world.getMinHeight() && BlockLooks.empty(data)) data = snap.getBlockData(lx, --top, lz);
        if (BlockLooks.empty(data)) return;
        int[] tint = looks.tint(world, col[1], top, col[2]);
        BlockLooks.Look look = looks.of(data, tint);
        int canopyTop = Integer.MIN_VALUE, canopyColour = 0;
        while (top > world.getMinHeight()) {
            boolean leaves = look.shape() == BlockLooks.CUTOUT;
            boolean through = leaves || look.shape() == BlockLooks.CROSS || look.shape() == BlockLooks.VINE || Tag.LOGS.isTagged(data.getMaterial());
            if (!through) break;
            if (leaves && canopyTop == Integer.MIN_VALUE) {
                canopyTop = top;
                canopyColour = BlockLooks.average(look);
            }
            data = snap.getBlockData(lx, --top, lz);
            while (top > world.getMinHeight() && BlockLooks.empty(data)) data = snap.getBlockData(lx, --top, lz);
            look = looks.of(data, tint);
        }
        if (canopyTop != Integer.MIN_VALUE) {
            canopyHeights[col[0]] = colours.symbol(Math.max(0, Math.min(198, canopyTop - floor + H_BIAS)));
            canopyTops[col[0]] = colours.nearest(canopyColour);
        } else canopyHeights[col[0]] = colours.symbol(199);
        heights[col[0]] = colours.symbol(Math.max(0, Math.min(198, top - floor + H_BIAS)));
        tops[col[0]] = colours.nearest(BlockLooks.average(look));
    }

    private void writeHorizon() {
        writeColumns(horizon, heights, tops, MAPS);
        writeColumns(canopy, canopyHeights, canopyTops, MAPS + 1);
    }

    private void writeColumns(Layer layer, byte[] h, byte[] t, int slab) {
        byte[] px = new byte[WIDTH * WIDTH];
        for (int hz = 0; hz < H_DEPTH; hz++) {
            int row = hz < DEPTH ? hz : hz + 1;   // the header row is left free
            for (int hx = 0; hx < H_ACROSS; hx++) {
                px[row * WIDTH + hx] = h[hz * H_ACROSS + hx];
                px[row * WIDTH + H_ACROSS + hx] = t[hz * H_ACROSS + hx];
            }
        }
        writeHeader(px, slab, null);
        if (!Arrays.equals(px, layer.pixels)) {
            System.arraycopy(px, 0, layer.pixels, 0, px.length);
            layer.version++;
        }
    }

    private static boolean[] cloudBits;

    private boolean[] cloudBits() {
        if (cloudBits != null) return cloudBits;
        if (!looks.assets().ready()) return null;
        Optional<BufferedImage> img = looks.assets().image("assets/minecraft/textures/environment/clouds.png");
        if (img.isEmpty() || img.get().getWidth() != 256 || img.get().getHeight() != 256) return null;
        boolean[] bits = new boolean[65536];
        for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) bits[z * 256 + x] = img.get().getRGB(x, z) >>> 24 >= 10;
        cloudBits = bits;
        return bits;
    }

    static void forgetClouds() {
        cloudBits = null;
    }

    /** The clouds over the far side and their clock (written again when the far side's time moved). */
    void writeClouds() {
        long near = hereWorld.getGameTime(), far = thereWorld.getGameTime();
        int ticks = (int) Math.floorMod(far - Math.floorMod(near, 24000L), CLOUD_PERIOD);
        boolean[] bits = farEnv == 0 ? cloudBits() : null;
        int ready = bits == null ? 0 : 1;
        if (ticks == cloudTicks && clouds.pixels[HEADER + CLOUD_FIELDS] == colours.symbol(ready)) return;
        cloudTicks = ticks;
        byte[] px = new byte[WIDTH * WIDTH];
        if (bits != null) {
            for (int p = 0; p * 7 < bits.length; p++) {
                int v = 0;
                for (int b = 0; b < 7 && p * 7 + b < bits.length; b++) if (bits[p * 7 + b]) v |= 1 << b;
                int r = p / WIDTH;
                if (r >= DEPTH) r++;
                px[r * WIDTH + p % WIDTH] = colours.symbol(v);
            }
        }
        writeHeader(px, MAPS + 2, null);
        int fx = Math.floorMod((int) Math.floor(there.worldX(0, 0)), 3072), fz = Math.floorMod((int) Math.floor(there.worldZ(0, 0)), 3072);
        int fy = (int) Math.floor(there.worldY(0)) + BASE + 1000;
        int[] fields = {ready, fx / 200, fx % 200, fz / 200, fz % 200, there.facing(), fy / 200, fy % 200, ticks / 40000, ticks / 200 % 200, ticks % 200};
        for (int i = 0; i < fields.length; i++) px[HEADER + CLOUD_FIELDS + i] = colours.symbol(fields[i]);
        System.arraycopy(px, 0, clouds.pixels, 0, px.length);
        clouds.version++;
    }

    /** One map's pixels, drawn on it only when they changed. */
    private static final class Layer extends MapRenderer {
        final byte[] pixels = new byte[WIDTH * WIDTH];
        int version, drawn = -1;

        Layer() {
            super(false);
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (drawn == version) return;
            for (int y = 0; y < WIDTH; y++) for (int x = 0; x < WIDTH; x++) canvas.setPixel(x, y, pixels[y * WIDTH + x]);
            drawn = version;
        }
    }
}
