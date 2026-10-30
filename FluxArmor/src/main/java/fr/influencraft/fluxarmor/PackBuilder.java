package fr.influencraft.fluxarmor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds the pack of an armour on the objmc principle: in the shader (resources/shaders, sources
 * assets/shaders/armor*.vsh.glsl) each face of the vanilla armour boxes becomes a face of the Blockbench model.
 * <p>
 * Each piece (chestplate, leggings, boots) has its own equipment asset {@code fluxarmor:<armour>_<piece>}, because
 * its vanilla model has a different number of boxes ({@link #PIECES}, in the order Minecraft 26.2 draws them: order
 * of a HashMap of the limb names). A box only has 6 faces: the asset stacks as many layers as needed, each carrying at
 * most 6 model faces per box, plus a flat fallback layer.
 * <p>
 * Texture of a data layer, width W >= 32: the lower half (where the vanilla UVs of the boxes read) stays transparent,
 * so the armour is invisible without the shader (Iris). Upper half:
 * <ul>
 * <li>(0, 0) mark (73, 65, 82); (1, 0) (version, number of boxes, 0); box b: (2 + 2b, 0) vanilla size in pixels
 * (width, height, depth), (3 + 2b, 0) (mirror, inflation x 100, 0);</li>
 * <li>row 1 + 6b + f (face f of box b, order down, up, west, north, east, south): (0) (1 present | 2 emissive, 0, 0),
 * (1) normal ((n + 1) x 127.5), then per corner k: (2 + 4k, 3 + 4k) position, 16 bits per axis ((v + 128) x 256), in
 * pixels in the frame of the vanilla box (box centre, y down, z backwards: ModelPart's frame), (4 + 4k, 5 + 4k) u and
 * v in texels of the layer (x 256, 24 bits);</li>
 * <li>below these rows: the rows of the model's texture atlas used by the layer.</li>
 * </ul>
 * Flat fallback layer: the given flat texture (64 x 32 template, any resolution), mark (73, 65, 70) at (0, 0), in the
 * head area these pieces never draw. The shader hides it when it draws the 3D.
 */
final class PackBuilder {

    static final int VERSION = 2;
    static final String NAMESPACE = "fluxarmor";

    /**
     * Vanilla armour box: Blockbench group filling it (and its other accepted names), group copied mirrored when it is
     * missing, vanilla size and inflation, ModelPart mirror, centre in the model frame.
     */
    private record Box(List<String> groups, String mirrorOf, int w, int h, int d, double inflate, boolean mirror,
                       double cx, double cy, double cz) {
    }

    private record Piece(String name, String layer, boolean leggings, List<Box> boxes) {
    }

    // Group names are data of the models (French, like the server's other content).
    private static final List<Piece> PIECES = List.of(
            new Piece("chestplate", "humanoid", false, List.of(
                    new Box(List.of("plastron_bras_droit", "plastron_bras"), null, 4, 12, 4, 1.0, false, -6, 18, 0),
                    new Box(List.of("plastron_bras_gauche"), "plastron_bras_droit", 4, 12, 4, 1.0, true, 6, 18, 0),
                    new Box(List.of("plastron_torse"), null, 8, 12, 4, 1.0, false, 0, 18, 0))),
            new Piece("leggings", "humanoid_leggings", true, List.of(
                    new Box(List.of("jambieres_jambe_gauche"), "jambieres_jambe_droite", 4, 12, 4, 0.4, true, 1.9, 6, 0),
                    new Box(List.of("jambieres_jambe_droite", "jambieres_jambe"), null, 4, 12, 4, 0.4, false, -1.9, 6, 0),
                    new Box(List.of("jambieres_torse"), null, 8, 12, 4, 0.5, false, 0, 18, 0))),
            new Piece("boots", "humanoid", false, List.of(
                    new Box(List.of("bottes_jambe_gauche"), "bottes_jambe_droite", 4, 12, 4, 0.9, true, 1.9, 6, 0),
                    new Box(List.of("bottes_jambe_droite", "bottes_jambe"), null, 4, 12, 4, 0.9, false, -1.9, 6, 0))));

    /** Piece names, in the order of the equipment slots CHEST, LEGS, FEET. */
    static final List<String> PIECE_NAMES = PIECES.stream().map(Piece::name).toList();

    private final File folder;

    PackBuilder(File pluginFolder) {
        this.folder = pluginFolder;
    }

    /** Pack folder of the namespace inside the ItemsAdder content folder. */
    static File assets(File content) {
        return new File(content, "resourcepack/assets/" + NAMESPACE);
    }

    /** True when the pack holds the equipment asset of this armour piece. */
    static boolean built(File content, String armor, String piece) {
        return new File(assets(content), "equipment/" + armor + "_" + piece + ".json").isFile();
    }

    /** Deletes the equipment assets and textures already built (armours removed or renamed). */
    void clean(File content) throws IOException {
        for (String sub : new String[]{"equipment", "textures/entity/equipment"}) {
            File d = new File(assets(content), sub);
            if (!d.exists()) continue;
            try (Stream<Path> s = Files.walk(d.toPath())) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    /** Writes the pack of armour {@code id} under {@code content}/resourcepack; returns a summary. */
    String build(String id, String model, String fallbackHumanoid, String fallbackLeggings, File content) throws IOException {
        Set<String> names = new HashSet<>();
        for (Piece pi : PIECES) for (Box b : pi.boxes()) names.addAll(b.groups());
        BlockbenchModel m = BlockbenchModel.read(new File(folder, model), names);
        File rp = assets(content);
        StringBuilder summary = new StringBuilder(id + ":");
        for (Piece pi : PIECES) {
            // Faces of each box, in the frame of the vanilla box.
            List<List<BlockbenchModel.Face>> perBox = new ArrayList<>();
            int layers = 0, total = 0;
            for (Box b : pi.boxes()) {
                // Missing group: the right limb's one, mirrored (x -> -x).
                List<BlockbenchModel.Face> faces = group(m, b.groups());
                boolean mirrored = faces.isEmpty() && b.mirrorOf() != null;
                if (mirrored) faces = group(m, groupsOf(b.mirrorOf()));
                List<BlockbenchModel.Face> local = new ArrayList<>();
                for (BlockbenchModel.Face f : faces) local.add(local(f, b, mirrored));
                perBox.add(local);
                layers = Math.max(layers, (local.size() + 5) / 6);
                total += local.size();
            }
            if (total == 0) continue;
            List<String> textures = new ArrayList<>();
            BufferedImage fallback = fallback(pi.leggings() ? fallbackLeggings : fallbackHumanoid);
            if (fallback != null) {
                String name = id + "_" + pi.name() + "_2d";
                writePng(fallback, new File(rp, "textures/entity/equipment/" + pi.layer() + "/" + name + ".png"));
                textures.add(name);
            }
            for (int c = 0; c < layers; c++) {
                String name = id + "_" + pi.name() + "_" + c;
                writePng(layer(m, pi, perBox, c), new File(rp, "textures/entity/equipment/" + pi.layer() + "/" + name + ".png"));
                textures.add(name);
            }
            StringBuilder json = new StringBuilder("{\"layers\":{\"" + pi.layer() + "\":[");
            for (int i = 0; i < textures.size(); i++)
                json.append(i == 0 ? "" : ",").append("{\"texture\":\"" + NAMESPACE + ":").append(textures.get(i)).append("\"}");
            writeText(new File(rp, "equipment/" + id + "_" + pi.name() + ".json"), json.append("]}}\n").toString());
            summary.append(' ').append(pi.name()).append(' ').append(total).append(" faces / ").append(layers).append(" layers")
                    .append(fallback == null ? " (no flat fallback)" : "").append(',');
        }
        summary.setLength(summary.length() - 1);
        if (m.skipped > 0) summary.append("; ").append(m.skipped).append(" cubes outside the limb groups skipped");
        return summary.toString();
    }

    /** Accepted names of the group whose main name is {@code name}. */
    private static List<String> groupsOf(String name) {
        for (Piece pi : PIECES) for (Box b : pi.boxes()) if (b.groups().getFirst().equals(name)) return b.groups();
        return List.of(name);
    }

    private static List<BlockbenchModel.Face> group(BlockbenchModel m, List<String> names) {
        for (String n : names) if (m.groups.containsKey(n)) return m.groups.get(n);
        return List.of();
    }

    /** Model face -> frame of the vanilla box (ModelPart: box centre, y down, z backwards). */
    private static BlockbenchModel.Face local(BlockbenchModel.Face f, Box b, boolean mirrored) {
        double sx = mirrored ? -1 : 1;
        double[][] corners = new double[4][];
        for (int k = 0; k < 4; k++) {
            double[] c = f.corners()[k];
            corners[k] = new double[]{sx * c[0] - b.cx(), -(c[1] - b.cy()), -(c[2] - b.cz())};
            for (double v : corners[k])
                if (v < -127 || v > 127) throw new IllegalStateException("A face lies more than 127 pixels away from its box (" + b.groups().getFirst() + ").");
        }
        double[] n = f.normal();
        return new BlockbenchModel.Face(corners, f.uv(), new double[]{sx * n[0], -n[1], -n[2]}, f.emissive());
    }

    private static BufferedImage layer(BlockbenchModel m, Piece pi, List<List<BlockbenchModel.Face>> perBox, int c) {
        int boxes = pi.boxes().size();
        int first = 1 + 6 * boxes;
        // Atlas rows used by the faces of this layer, with one row of margin.
        double vmin = Double.MAX_VALUE, vmax = -Double.MAX_VALUE;
        for (List<BlockbenchModel.Face> faces : perBox)
            for (int s = 0; s < 6 && 6 * c + s < faces.size(); s++)
                for (double[] uv : faces.get(6 * c + s).uv()) {
                    vmin = Math.min(vmin, uv[1]);
                    vmax = Math.max(vmax, uv[1]);
                }
        int y0 = Math.max(0, (int) Math.floor(vmin) - 1), y1 = Math.min(m.atlas.getHeight(), (int) Math.ceil(vmax) + 1);
        int width = Math.max(32, m.atlas.getWidth()), data = first + Math.max(0, y1 - y0);
        BufferedImage img = new BufferedImage(width, 2 * data, BufferedImage.TYPE_INT_ARGB);
        for (int y = y0; y < y1; y++) for (int x = 0; x < m.atlas.getWidth(); x++) img.setRGB(x, first + y - y0, m.atlas.getRGB(x, y));

        img.setRGB(0, 0, argb(73, 65, 82));
        img.setRGB(1, 0, argb(VERSION, boxes, 0));
        for (int b = 0; b < boxes; b++) {
            Box box = pi.boxes().get(b);
            img.setRGB(2 + 2 * b, 0, argb(box.w(), box.h(), box.d()));
            img.setRGB(3 + 2 * b, 0, argb(box.mirror() ? 1 : 0, (int) Math.round(box.inflate() * 100), 0));
            List<BlockbenchModel.Face> faces = perBox.get(b);
            for (int s = 0; s < 6 && 6 * c + s < faces.size(); s++) {
                BlockbenchModel.Face f = faces.get(6 * c + s);
                int r = 1 + 6 * b + s;
                img.setRGB(0, r, argb(1 | (f.emissive() ? 2 : 0), 0, 0));
                double[] n = f.normal();
                img.setRGB(1, r, argb(octet(n[0]), octet(n[1]), octet(n[2])));
                for (int k = 0; k < 4; k++) {
                    int[] p = new int[3];
                    for (int a = 0; a < 3; a++) p[a] = (int) Math.max(0, Math.min(65535, Math.round((f.corners()[k][a] + 128) * 256)));
                    img.setRGB(2 + 4 * k, r, argb(p[0] >> 8, p[0], p[1] >> 8));
                    img.setRGB(3 + 4 * k, r, argb(p[1], p[2] >> 8, p[2]));
                    img.setRGB(4 + 4 * k, r, fixed(f.uv()[k][0]));
                    img.setRGB(5 + 4 * k, r, fixed(f.uv()[k][1] - y0 + first));
                }
            }
        }
        return img;
    }

    private static int octet(double n) {
        return (int) Math.max(0, Math.min(255, Math.round((n + 1) * 127.5)));
    }

    private static int fixed(double v) {
        int t = (int) Math.max(0, Math.min(0xFFFFFF, Math.round(v * 256)));
        return argb(t >> 16, t >> 8, t);
    }

    /** Marked flat fallback texture, or null when missing. */
    private BufferedImage fallback(String path) throws IOException {
        if (path == null) return null;
        File f = new File(folder, path);
        if (!f.isFile()) return null;
        BufferedImage s = ImageIO.read(f);
        if (s.getWidth() < 32 || s.getHeight() < 16) throw new IOException(path + ": flat texture too small (64 x 32 template expected).");
        BufferedImage img = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_ARGB);
        img.getGraphics().drawImage(s, 0, 0, null);
        img.setRGB(0, 0, argb(73, 65, 70));
        return img;
    }

    /**
     * Shaders of the pack, in ItemsAdder's 26.1+ overlay (the last one of its pack.mcmeta, so it wins; an overlay of
     * our own would be ranked before its ones). Never at the root: older clients would load them. ItemsAdder must have
     * generate_shader.1_21_5_plus and emotes.1_21_5_to_1_21_9_shaders set to false, else it writes its own entity
     * shader over ours.
     */
    void writeShaders(File content) throws IOException {
        File rp = new File(content, "resourcepack");
        // ItemsAdder only takes the overlay folder of a content pack declared in its pack.mcmeta.
        writeText(new File(rp, "pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"min_format\":84,\"max_format\":9999,"
                + "\"supported_formats\":[84,9999],\"description\":\"FluxArmor\"},\"overlays\":{\"entries\":[{\"min_format\":84,"
                + "\"max_format\":9999,\"formats\":[84,9999],\"directory\":\"ia_overlay_26_1_plus\"}]}}\n");
        for (String f : new String[]{"entity.vsh", "entity.fsh"}) {
            File dest = new File(rp, "ia_overlay_26_1_plus/assets/minecraft/shaders/core/" + f);
            dest.getParentFile().mkdirs();
            try (InputStream in = getClass().getResourceAsStream("/shaders/" + f)) {
                if (in == null) throw new IOException("Shader " + f + " missing from the jar.");
                Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    private static void writePng(BufferedImage img, File f) throws IOException {
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }

    private static void writeText(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
    }
}
