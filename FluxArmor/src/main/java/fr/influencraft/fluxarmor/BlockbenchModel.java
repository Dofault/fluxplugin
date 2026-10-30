package fr.influencraft.fluxarmor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A Blockbench model ("Generic Model" format) reduced to its faces: each visible face of a cube becomes a
 * quadrilateral in model pixels (rotations of the cube and of its groups applied), with its coordinates in the atlas of
 * the model's textures (all textures side by side).
 * <p>
 * Frame: one skin pixel per unit, feet at y = 0, +x towards the player's left, +z forwards. A face belongs to the limb
 * group (see {@link PackBuilder}) closest above its cube.
 */
final class BlockbenchModel {

    /** Quadrilateral: 4 corners (pixels) in outline order, their atlas coordinates (texels), its normal. */
    record Face(double[][] corners, double[][] uv, double[] normal, boolean emissive) {
    }

    // Corners of a cube, index = (x max ? 4 : 0) + (y max ? 2 : 0) + (z max ? 1 : 0). Corners of each face in the
    // order of its Blockbench UVs (u1 v1, u2 v1, u2 v2, u1 v2), seen from outside, and its normal.
    private static final String[] FACES = {"north", "south", "west", "east", "up", "down"};
    private static final int[][] FACE_CORNERS = {
            {6, 2, 0, 4}, {3, 7, 5, 1}, {2, 3, 1, 0}, {7, 6, 4, 5}, {2, 6, 7, 3}, {1, 5, 4, 0}};
    private static final double[][] NORMALS = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    final BufferedImage atlas;
    final Map<String, List<Face>> groups = new HashMap<>();
    int skipped;

    private BlockbenchModel(BufferedImage atlas) {
        this.atlas = atlas;
    }

    static BlockbenchModel read(File file, Set<String> groupNames) throws IOException {
        JsonObject bb = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();

        // Atlas: textures side by side. UV scale of each texture (uv_width / uv_height), else the model's
        // resolution.
        JsonObject resol = bb.getAsJsonObject("resolution");
        double resU = resol == null ? 16 : resol.get("width").getAsDouble(), resV = resol == null ? 16 : resol.get("height").getAsDouble();
        List<BufferedImage> images = new ArrayList<>();
        List<double[]> placements = new ArrayList<>(); // x in the atlas, texels per u unit, texels per v unit
        List<Boolean> emissives = new ArrayList<>();
        int width = 0, height = 0;
        for (JsonElement e : bb.getAsJsonArray("textures")) {
            JsonObject t = e.getAsJsonObject();
            String src = t.get("source").getAsString();
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(src.substring(src.indexOf(',') + 1))));
            double tu = t.has("uv_width") ? t.get("uv_width").getAsDouble() : resU, tv = t.has("uv_height") ? t.get("uv_height").getAsDouble() : resV;
            placements.add(new double[]{width, img.getWidth() / tu, img.getHeight() / tv});
            String name = t.has("name") ? t.get("name").getAsString().toLowerCase(Locale.ROOT).replace(".png", "") : "";
            boolean emissive = t.has("render_mode") && "emissive".equals(t.get("render_mode").getAsString());
            emissives.add(emissive || name.contains("neon") || name.endsWith("_e"));
            images.add(img);
            width += img.getWidth();
            height = Math.max(height, img.getHeight());
        }
        BufferedImage atlas = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < images.size(); i++) atlas.getGraphics().drawImage(images.get(i), (int) placements.get(i)[0], 0, null);
        BlockbenchModel m = new BlockbenchModel(atlas);

        // Groups (Blockbench 5 keeps them apart, the outliner only keeps their uuid).
        Map<String, JsonObject> groupsByUuid = new HashMap<>();
        if (bb.has("groups")) for (JsonElement e : bb.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            groupsByUuid.put(g.get("uuid").getAsString(), g);
        }
        Map<String, JsonObject> cubes = new HashMap<>();
        for (JsonElement e : bb.getAsJsonArray("elements")) {
            JsonObject c = e.getAsJsonObject();
            cubes.put(c.get("uuid").getAsString(), c);
        }
        for (JsonElement e : bb.getAsJsonArray("outliner")) {
            if (e.isJsonObject()) m.walk(e.getAsJsonObject(), null, new ArrayList<>(), groupsByUuid, cubes, groupNames, placements, emissives);
            else m.skipped++;
        }
        return m;
    }

    private void walk(JsonObject node, String limb, List<JsonObject> chain, Map<String, JsonObject> groupsByUuid,
                           Map<String, JsonObject> cubes, Set<String> groupNames, List<double[]> placements, List<Boolean> emissives) {
        JsonObject g = node.has("name") ? node : groupsByUuid.getOrDefault(node.get("uuid").getAsString(), node);
        String name = g.has("name") ? g.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
        if (groupNames.contains(name)) limb = name;
        chain.add(g);
        for (JsonElement e : node.getAsJsonArray("children")) {
            if (e.isJsonObject()) {
                walk(e.getAsJsonObject(), limb, chain, groupsByUuid, cubes, groupNames, placements, emissives);
                continue;
            }
            JsonObject c = cubes.get(e.getAsString());
            if (c == null || (c.has("type") && !"cube".equals(c.get("type").getAsString()))
                    || (c.has("visibility") && !c.get("visibility").getAsBoolean()) || (c.has("export") && !c.get("export").getAsBoolean())) continue;
            if (limb == null) {
                skipped++;
                continue;
            }
            cube(c, chain, groups.computeIfAbsent(limb, k -> new ArrayList<>()), placements, emissives);
        }
        chain.removeLast();
    }

    private void cube(JsonObject c, List<JsonObject> chain, List<Face> out, List<double[]> placements, List<Boolean> emissives) {
        double[] de = vec(c, "from", 0), a = vec(c, "to", 0);
        double inflate = c.has("inflate") ? c.get("inflate").getAsDouble() : 0;
        double[] stretch = c.has("stretch") ? vec(c, "stretch", 1) : new double[]{1, 1, 1};
        double[] lo = new double[3], hi = new double[3];
        for (int k = 0; k < 3; k++) {
            double centre = (de[k] + a[k]) / 2, demi = Math.abs(a[k] - de[k]) / 2;
            lo[k] = centre - (demi + inflate) * stretch[k];
            hi[k] = centre + (demi + inflate) * stretch[k];
        }
        double[][] corners = new double[8][];
        for (int i = 0; i < 8; i++)
            corners[i] = world(new double[]{(i & 4) == 0 ? lo[0] : hi[0], (i & 2) == 0 ? lo[1] : hi[1], (i & 1) == 0 ? lo[2] : hi[2]}, c, chain, false);

        JsonObject faces = c.getAsJsonObject("faces");
        if (faces == null) return;
        for (int f = 0; f < 6; f++) {
            JsonObject fa = faces.getAsJsonObject(FACES[f]);
            if (fa == null || !fa.has("texture") || fa.get("texture").isJsonNull() || !fa.has("uv")) continue;
            int t = fa.get("texture").getAsInt();
            if (t < 0 || t >= placements.size()) continue;
            double[] p = placements.get(t);
            JsonArray uv = fa.getAsJsonArray("uv");
            double u1 = p[0] + uv.get(0).getAsDouble() * p[1], v1 = uv.get(1).getAsDouble() * p[2];
            double u2 = p[0] + uv.get(2).getAsDouble() * p[1], v2 = uv.get(3).getAsDouble() * p[2];
            double[][] uvs = {{u1, v1}, {u2, v1}, {u2, v2}, {u1, v2}};
            // Face rotation: the UVs turn by one corner per quarter turn.
            for (int r = ((fa.has("rotation") ? fa.get("rotation").getAsInt() : 0) % 360 + 360) % 360; r > 0; r -= 90) {
                double[] last = uvs[3];
                uvs[3] = uvs[2];
                uvs[2] = uvs[1];
                uvs[1] = uvs[0];
                uvs[0] = last;
            }
            double[][] q = new double[4][];
            for (int k = 0; k < 4; k++) q[k] = corners[FACE_CORNERS[f][k]];
            if (area(q) < 1e-6 || transparent(uvs)) continue;
            out.add(new Face(q, uvs, world(NORMALS[f], c, chain, true), emissives.get(t)));
        }
    }

    /** Applies the rotation of the cube (around its origin), then those of its groups, innermost first. */
    private static double[] world(double[] p, JsonObject cube, List<JsonObject> chain, boolean direction) {
        double[] r = rotate(p, cube, direction);
        for (int i = chain.size() - 1; i >= 0; i--) r = rotate(r, chain.get(i), direction);
        return r;
    }

    /** Blockbench rotation (x, then y, then z) around the element's origin; a direction is not moved. */
    private static double[] rotate(double[] p, JsonObject e, boolean direction) {
        double[] rot = e.has("rotation") && e.get("rotation").isJsonArray() ? vec(e, "rotation", 0) : new double[3];
        if (rot[0] == 0 && rot[1] == 0 && rot[2] == 0) return p;
        double[] o = direction || !e.has("origin") ? new double[3] : vec(e, "origin", 0);
        double x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
        double ax = Math.toRadians(rot[0]), ay = Math.toRadians(rot[1]), az = Math.toRadians(rot[2]);
        double y1 = y * Math.cos(ax) - z * Math.sin(ax), z1 = y * Math.sin(ax) + z * Math.cos(ax);
        double x2 = x * Math.cos(ay) + z1 * Math.sin(ay), z2 = -x * Math.sin(ay) + z1 * Math.cos(ay);
        double x3 = x2 * Math.cos(az) - y1 * Math.sin(az), y3 = x2 * Math.sin(az) + y1 * Math.cos(az);
        return new double[]{x3 + o[0], y3 + o[1], z2 + o[2]};
    }

    private static double area(double[][] q) {
        double[] a = sub(q[1], q[0]), b = sub(q[3], q[0]);
        double cx = a[1] * b[2] - a[2] * b[1], cy = a[2] * b[0] - a[0] * b[2], cz = a[0] * b[1] - a[1] * b[0];
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Face whose texture area has no visible texel: no need to draw it. */
    private boolean transparent(double[][] uvs) {
        double umin = Double.MAX_VALUE, umax = -Double.MAX_VALUE, vmin = Double.MAX_VALUE, vmax = -Double.MAX_VALUE;
        for (double[] uv : uvs) {
            umin = Math.min(umin, uv[0]);
            umax = Math.max(umax, uv[0]);
            vmin = Math.min(vmin, uv[1]);
            vmax = Math.max(vmax, uv[1]);
        }
        int x0 = Math.max(0, (int) Math.floor(umin)), x1 = Math.min(atlas.getWidth() - 1, Math.max(x0, (int) Math.ceil(umax) - 1));
        int y0 = Math.max(0, (int) Math.floor(vmin)), y1 = Math.min(atlas.getHeight() - 1, Math.max(y0, (int) Math.ceil(vmax) - 1));
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if ((atlas.getRGB(x, y) >>> 24) != 0) return false;
        return true;
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] vec(JsonObject c, String key, double fallback) {
        if (!c.has(key) || !c.get(key).isJsonArray()) return new double[]{fallback, fallback, fallback};
        JsonArray a = c.getAsJsonArray(key);
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }
}
