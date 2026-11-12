package fr.influencraft.fluxportal.window;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How a block state looks in a window: one of a few shapes the shader draws, and its top, side and bottom textures
 * (16x16, ARGB) taken from its model, as the client would (the server's pack first: custom blocks keep their look).
 * Without any texture source, a block is drawn in its map colour. Window technique of RPE-portals (GPL-3.0).
 */
public final class BlockLooks {

    public static final int CUBE = 0, CUTOUT = 1, CROSS = 2, LIQUID = 3, SLAB_BOTTOM = 4, SLAB_TOP = 5, VINE = 6, THIN = 7;

    /** A look. {@code faces}: the plant offset code for a cross, the walls a vine climbs for a vine. */
    public record Look(int shape, int[] top, int[] side, int[] bottom, char axis, int faces, int colour) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Look l && l.shape == shape && l.axis == axis && l.faces == faces
                    && Arrays.equals(l.top, top) && Arrays.equals(l.side, side) && Arrays.equals(l.bottom, bottom);
        }

        @Override
        public int hashCode() {
            return ((shape * 31 + axis) * 31 + faces) * 31 + Arrays.hashCode(top) * 7 + Arrays.hashCode(side) * 3 + Arrays.hashCode(bottom);
        }

        public boolean occludes() {
            return shape == CUBE || shape == CUTOUT;
        }
    }

    private final Assets assets;
    private final Map<String, Optional<JsonObject>> models = new HashMap<>();
    private final Map<String, int[]> textures = new HashMap<>();
    private final Map<String, Look> looks = new HashMap<>();
    private final Map<Look, Look> interned = new HashMap<>();
    private final Map<String, int[]> tints = new HashMap<>();
    private int[] grassMap, foliageMap;

    public BlockLooks(Assets assets) {
        this.assets = assets;
    }

    public Assets assets() {
        return assets;
    }

    public void reset() {
        models.clear();
        textures.clear();
        looks.clear();
        interned.clear();
        tints.clear();
        grassMap = null;
        foliageMap = null;
    }

    public static boolean empty(BlockData data) {
        Material m = data.getMaterial();
        return m.isAir() || m == Material.BARRIER || m == Material.LIGHT || m == Material.STRUCTURE_VOID;
    }

    public Look of(BlockData data, int[] tint) {
        String key = data.getAsString() + "|" + tint[0] + "|" + tint[1];
        Look known = looks.get(key);
        if (known != null) return known;
        Look look = interned.computeIfAbsent(resolve(data, tint[0], tint[1]), l -> l);
        looks.put(key, look);
        return look;
    }

    /** Water over what lies under it: the cell shows the bottom through the surface. */
    public Look liquidOver(Look water, Look bottom) {
        int[] under = bottom == null ? water.top() : bottom.top();
        return interned.computeIfAbsent(new Look(LIQUID, water.top(), water.side(), under, 'y', 0, water.colour()), l -> l);
    }

    public static int average(Look look) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int c : look.top()) {
            if (c >>> 24 < 128) continue;
            r += c >> 16 & 0xFF;
            g += c >> 8 & 0xFF;
            b += c & 0xFF;
            n++;
        }
        return n == 0 ? look.colour() : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    // ------------------------------------------------------------------ from the model

    private Look resolve(BlockData data, int grass, int foliage) {
        Material m = data.getMaterial();
        String name = m.getKey().getKey();
        int mapColour = data.getMapColor().asRGB();
        if (!assets.ready()) return flat(data, mapColour);
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN) {
            int[] t = tint(texture("block/water_still"), 0x3F76E4);
            return new Look(LIQUID, t, t, t, 'y', 0, mapColour);
        }
        if (m == Material.LAVA) {
            int[] t = texture("block/lava_still");
            return new Look(LIQUID, t, t, t, 'y', 0, mapColour);
        }
        List<JsonObject> parts = modelsFor(m.getKey().getNamespace() + ":" + name, data);
        if (parts.isEmpty()) return flat(data, mapColour);
        Map<String, String> vars = new HashMap<>();
        List<JsonObject> elements = new ArrayList<>();
        flatten(parts.get(0), vars, elements);
        if (elements.isEmpty()) return flat(data, mapColour);
        int tintColour = tintFor(name, grass, foliage);

        if (data instanceof MultipleFacing mf && (name.contains("vine") || name.equals("glow_lichen") || name.equals("sculk_vein"))) {
            int[] t = faceTexture(firstFace(elements.get(0)), vars, tintColour);
            int bits = (mf.hasFace(BlockFace.NORTH) ? 1 : 0) | (mf.hasFace(BlockFace.EAST) ? 2 : 0)
                    | (mf.hasFace(BlockFace.SOUTH) ? 4 : 0) | (mf.hasFace(BlockFace.WEST) ? 8 : 0);
            return new Look(VINE, t, t, t, 'y', bits == 0 ? 15 : bits, mapColour);
        }
        // a plant: two planes turned 45 degrees
        for (JsonObject el : elements) {
            JsonObject rot = el.getAsJsonObject("rotation");
            if (rot != null && rot.has("axis") && rot.get("axis").getAsString().equals("y")
                    && Math.abs(Math.abs(rot.get("angle").getAsFloat()) - 45f) < 1f) {
                int[] t = faceTexture(firstFace(el), vars, tintColour);
                return new Look(CROSS, t, t, t, 'y', offsetCode(data), mapColour);
            }
        }
        int[] up = null, down = null, side = null;
        boolean full = false;
        float minY = 16, maxY = 0;
        for (JsonObject el : elements) {
            float[] from = vec(el, "from"), to = vec(el, "to");
            minY = Math.min(minY, from[1]);
            maxY = Math.max(maxY, to[1]);
            boolean layer = from[0] <= 0 && from[2] <= 0 && to[0] >= 16 && to[2] >= 16;
            boolean isFull = layer && from[1] <= 0 && to[1] >= 16;
            if (!layer) continue;
            full |= isFull;
            JsonObject faces = el.getAsJsonObject("faces");
            if (faces == null) continue;
            up = over(up, faces, "up", vars, tintColour);
            down = over(down, faces, "down", vars, tintColour);
            for (String s : new String[]{"north", "south", "east", "west"}) {
                if (faces.has(s)) {
                    side = over(side, faces, s, vars, tintColour);
                    break;
                }
            }
        }
        if (up == null && side == null) {
            int[] t = faceTexture(firstFace(elements.get(0)), vars, tintColour);
            boolean cube = m.isSolid() && m.isOccluding();
            return new Look(cube ? CUBE : CROSS, t, t, t, 'y', cube ? 0 : offsetCode(data), mapColour);
        }
        if (up == null) up = side;
        if (side == null) side = up;
        if (down == null) down = up;
        char axis = data instanceof Orientable o ? Character.toLowerCase(o.getAxis().name().charAt(0)) : 'y';
        if (data instanceof Slab slab) {
            int shape = switch (slab.getType()) {
                case BOTTOM -> SLAB_BOTTOM;
                case TOP -> SLAB_TOP;
                default -> CUBE;
            };
            return new Look(shape, up, side, down, axis, 0, mapColour);
        }
        if (!full && maxY - minY <= 3) return new Look(THIN, up, side, down, axis, 0, mapColour);
        boolean seeThrough = hasHoles(up) || hasHoles(side) || Tag.LEAVES.isTagged(m);
        return new Look(seeThrough ? CUTOUT : CUBE, up, side, down, axis, 0, mapColour);
    }

    private Look flat(BlockData data, int rgb) {
        int[] t = new int[256];
        Arrays.fill(t, 0xFF000000 | rgb);
        Material m = data.getMaterial();
        int shape = m == Material.WATER || m == Material.LAVA ? LIQUID : Tag.LEAVES.isTagged(m) ? CUTOUT : !m.isSolid() ? CROSS : CUBE;
        return new Look(shape, t, t, t, 'y', 0, rgb);
    }

    /** The models of a block state, from its blockstates file (first matching variant, or every matching part). */
    private List<JsonObject> modelsFor(String id, BlockData data) {
        List<JsonObject> out = new ArrayList<>();
        Optional<JsonObject> states = assets.json(id, "blockstates");
        if (states.isEmpty()) return out;
        Map<String, String> props = properties(data.getAsString());
        JsonObject s = states.get();
        if (s.has("variants")) {
            for (Map.Entry<String, JsonElement> e : s.getAsJsonObject("variants").entrySet()) {
                if (matches(e.getKey(), props)) {
                    model(e.getValue()).ifPresent(out::add);
                    break;
                }
            }
        } else if (s.has("multipart")) {
            for (JsonElement part : s.getAsJsonArray("multipart")) {
                JsonObject p = part.getAsJsonObject();
                if (!p.has("when") || when(p.getAsJsonObject("when"), props)) model(p.get("apply")).ifPresent(out::add);
            }
        }
        return out;
    }

    private Optional<JsonObject> model(JsonElement apply) {
        JsonObject a = apply.isJsonArray() ? apply.getAsJsonArray().get(0).getAsJsonObject() : apply.getAsJsonObject();
        return loadModel(a.get("model").getAsString());
    }

    private Optional<JsonObject> loadModel(String ref) {
        return models.computeIfAbsent(Assets.id(ref), id -> assets.json(id, "models"));
    }

    /** Texture variables (the nearest model wins) and the first elements found, up the parents. */
    private void flatten(JsonObject model, Map<String, String> vars, List<JsonObject> elements) {
        JsonObject m = model;
        for (int depth = 0; m != null && depth < 16; depth++) {
            if (m.has("textures")) {
                for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("textures").entrySet()) {
                    JsonElement t = e.getValue();
                    if (t.isJsonObject() && t.getAsJsonObject().has("sprite")) t = t.getAsJsonObject().get("sprite");
                    if (t.isJsonPrimitive()) vars.putIfAbsent(e.getKey(), t.getAsString());
                }
            }
            if (elements.isEmpty() && m.has("elements")) {
                for (JsonElement el : m.getAsJsonArray("elements")) elements.add(el.getAsJsonObject());
            }
            m = m.has("parent") ? loadModel(m.get("parent").getAsString()).orElse(null) : null;
        }
    }

    private static Map<String, String> properties(String state) {
        Map<String, String> props = new HashMap<>();
        int open = state.indexOf('[');
        if (open < 0) return props;
        for (String kv : state.substring(open + 1, state.length() - 1).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return props;
    }

    private static boolean matches(String key, Map<String, String> props) {
        if (key.isEmpty()) return true;
        for (String kv : key.split(",")) {
            int eq = kv.indexOf('=');
            if (eq >= 0 && !kv.substring(eq + 1).equals(props.get(kv.substring(0, eq)))) return false;
        }
        return true;
    }

    private static boolean when(JsonObject when, Map<String, String> props) {
        if (when.has("OR")) {
            for (JsonElement e : when.getAsJsonArray("OR")) if (when(e.getAsJsonObject(), props)) return true;
            return false;
        }
        if (when.has("AND")) {
            for (JsonElement e : when.getAsJsonArray("AND")) if (!when(e.getAsJsonObject(), props)) return false;
            return true;
        }
        for (Map.Entry<String, JsonElement> e : when.entrySet()) {
            String have = props.get(e.getKey());
            boolean any = false;
            for (String v : e.getValue().getAsString().split("\\|")) any |= v.equals(have);
            if (!any) return false;
        }
        return true;
    }

    private static float[] vec(JsonObject el, String key) {
        JsonArray a = el.getAsJsonArray(key);
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static JsonObject firstFace(JsonObject el) {
        JsonObject faces = el.getAsJsonObject("faces");
        if (faces == null) return new JsonObject();
        for (String s : new String[]{"north", "south", "east", "west", "up", "down"}) {
            if (faces.has(s)) return faces.getAsJsonObject(s);
        }
        return new JsonObject();
    }

    /** A face drawn over what the elements under it already gave (overlays of grass sides). */
    private int[] over(int[] under, JsonObject faces, String face, Map<String, String> vars, int tintColour) {
        if (!faces.has(face)) return under;
        int[] t = faceTexture(faces.getAsJsonObject(face), vars, tintColour);
        if (under == null) return t;
        int[] out = under.clone();
        for (int i = 0; i < 256; i++) {
            int a = t[i] >>> 24;
            if (a == 0) continue;
            int r = mix(out[i] >> 16 & 0xFF, t[i] >> 16 & 0xFF, a);
            int g = mix(out[i] >> 8 & 0xFF, t[i] >> 8 & 0xFF, a);
            int b = mix(out[i] & 0xFF, t[i] & 0xFF, a);
            out[i] = Math.max(out[i] >>> 24, a) << 24 | r << 16 | g << 8 | b;
        }
        return out;
    }

    private static int mix(int a, int b, int alpha) {
        return (a * (255 - alpha) + b * alpha) / 255;
    }

    private int[] faceTexture(JsonObject face, Map<String, String> vars, int tintColour) {
        String ref = face.has("texture") ? face.get("texture").getAsString() : "#particle";
        for (int i = 0; i < 8 && ref.startsWith("#"); i++) ref = vars.getOrDefault(ref.substring(1), "block/stone");
        int[] t = texture(ref);
        return face.has("tintindex") ? tint(t, tintColour) : t;
    }

    /** A texture brought to 16x16 (first frame of an animation), magenta when missing. */
    private int[] texture(String ref) {
        return textures.computeIfAbsent(Assets.id(ref), id -> {
            int[] out = new int[256];
            Arrays.fill(out, 0xFFFF00FF);
            Optional<BufferedImage> img = assets.texture(id);
            if (img.isEmpty()) return out;
            BufferedImage im = img.get();
            int w = im.getWidth();
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) out[y * 16 + x] = im.getRGB(x * w / 16, y * w / 16);
            return out;
        });
    }

    private static int[] tint(int[] t, int rgb) {
        if (rgb == 0xFFFFFF) return t;
        int[] out = new int[256];
        int tr = rgb >> 16 & 0xFF, tg = rgb >> 8 & 0xFF, tb = rgb & 0xFF;
        for (int i = 0; i < 256; i++) {
            int c = t[i];
            out[i] = c & 0xFF000000 | (c >> 16 & 0xFF) * tr / 255 << 16 | (c >> 8 & 0xFF) * tg / 255 << 8 | (c & 0xFF) * tb / 255;
        }
        return out;
    }

    private static boolean hasHoles(int[] t) {
        for (int c : t) if (c >>> 24 < 128) return true;
        return false;
    }

    private static int tintFor(String name, int grass, int foliage) {
        if (name.equals("spruce_leaves")) return 0x619961;
        if (name.equals("birch_leaves")) return 0x80A755;
        if (name.equals("lily_pad")) return 0x208030;
        if (name.contains("leaves") || name.contains("vine")) return foliage;
        if (name.equals("grass_block") || name.contains("grass") || name.contains("fern") || name.equals("sugar_cane")) return grass;
        return name.contains("stem") ? 0xE0C71C : 0xFFFFFF;
    }

    // ------------------------------------------------------------------ biome colours

    /** Grass and foliage colours where a block stands (from the client's colour maps, special biomes as the game). */
    public int[] tint(World world, int x, int y, int z) {
        Biome biome = world.getBiome(x, y, z);
        String key = world.getName() + "|" + biome.getKey();
        int[] known = tints.get(key);
        if (known != null) return known;
        double temp = world.getTemperature(x, y, z), wet = world.getHumidity(x, y, z);
        int grass = colormap(true, temp, wet), foliage = colormap(false, temp, wet);
        switch (biome.getKey().getKey()) {
            case "badlands", "eroded_badlands", "wooded_badlands" -> {
                grass = 0x90814D;
                foliage = 0x9E814D;
            }
            case "swamp" -> grass = foliage = 0x6A7039;
            case "mangrove_swamp" -> {
                grass = 0x6A7039;
                foliage = 0x8DB127;
            }
            case "cherry_grove" -> grass = foliage = 0xB6DB61;
            case "pale_garden" -> {
                grass = 0x778272;
                foliage = 0x878D76;
            }
            case "dark_forest" -> grass = (grass & 0xFEFEFE) + 0x28340A >> 1;
            default -> {
            }
        }
        int[] t = {grass, foliage};
        tints.put(key, t);
        return t;
    }

    private int colormap(boolean grass, double temperature, double humidity) {
        int[] map = grass ? grassMap : foliageMap;
        if (map == null) {
            map = new int[65536];
            Arrays.fill(map, grass ? 0x91BD59 : 0x77AB2F);
            Optional<BufferedImage> img = assets.image("assets/minecraft/textures/colormap/" + (grass ? "grass" : "foliage") + ".png");
            if (img.isPresent() && img.get().getWidth() == 256) img.get().getRGB(0, 0, 256, 256, map, 0, 256);
            if (grass) grassMap = map;
            else foliageMap = map;
        }
        double t = Math.max(0, Math.min(1, temperature));
        double h = Math.max(0, Math.min(1, humidity)) * t;
        return map[(int) ((1 - h) * 255) * 256 + (int) ((1 - t) * 255)] & 0xFFFFFF;
    }

    // ------------------------------------------------------------------ plant offsets

    /** 0 none, 1 sideways, 2 sideways and down, 3 sideways a little: how the game nudges a plant off its block. */
    private static final Map<Material, Integer> OFFSETS = new EnumMap<>(Material.class);

    static int offsetCode(BlockData data) {
        return OFFSETS.computeIfAbsent(data.getMaterial(), m -> probeOffset(data));
    }

    /** The server's own offset of the state at a few places: whether it moves, how far, and if it goes down. */
    private static int probeOffset(BlockData data) {
        try {
            Object state = data.getClass().getMethod("getState").invoke(data);
            Class<?> posClass = Class.forName("net.minecraft.core.BlockPos");
            Constructor<?> pos = posClass.getConstructor(int.class, int.class, int.class);
            Method offset = null;
            for (Method m : state.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getOffset") && p.length >= 1 && p[p.length - 1] == posClass) offset = m;
            }
            if (offset == null) return fallbackOffset(data);
            boolean vertical = false, any = false;
            double maxH = 0;
            for (int i = 0; i < 48; i++) {
                Object at = pos.newInstance(i * 37 - 811, 64, i * 53 + 129);
                Object v = offset.getParameterCount() == 1 ? offset.invoke(state, at) : offset.invoke(state, null, at);
                double vx = v.getClass().getField("x").getDouble(v), vy = v.getClass().getField("y").getDouble(v), vz = v.getClass().getField("z").getDouble(v);
                any |= vx != 0 || vy != 0 || vz != 0;
                vertical |= vy != 0;
                maxH = Math.max(maxH, Math.max(Math.abs(vx), Math.abs(vz)));
            }
            return !any ? 0 : vertical ? 2 : maxH > 0.13 ? 1 : 3;
        } catch (Throwable t) {
            return fallbackOffset(data);
        }
    }

    private static int fallbackOffset(BlockData data) {
        String name = data.getMaterial().getKey().getKey();
        if (name.contains("grass") || name.endsWith("fern")) return 2;
        return Tag.FLOWERS.isTagged(data.getMaterial()) ? 1 : 0;
    }
}
