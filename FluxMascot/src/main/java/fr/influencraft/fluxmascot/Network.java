package fr.influencraft.fluxmascot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The streets the robot strolls on: nodes and links read from a JSON file of the plugin folder
 * ({@code {"world": "resaac", "nodes": [[x, y, z], ...], "edges": [[a, b], ...]}}), the same network the town's
 * inhabitants use (written by scripts/typewriter/gen_pnj_ville.py). Empty when the file is missing: the robot then
 * leaves after its speech.
 */
final class Network {

    private final String world;
    private final List<double[]> nodes = new ArrayList<>();
    private final List<List<Integer>> links = new ArrayList<>();

    private Network(String world) {
        this.world = world;
    }

    static Network load(File file) throws IOException {
        if (!file.isFile()) return new Network("");
        JsonObject o = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        Network n = new Network(o.has("world") ? o.get("world").getAsString() : "");
        for (var e : o.getAsJsonArray("nodes")) {
            JsonArray a = e.getAsJsonArray();
            n.nodes.add(new double[]{a.get(0).getAsDouble() + 0.5, a.get(1).getAsDouble(), a.get(2).getAsDouble() + 0.5});
            n.links.add(new ArrayList<>());
        }
        for (var e : o.getAsJsonArray("edges")) {
            int a = e.getAsJsonArray().get(0).getAsInt(), b = e.getAsJsonArray().get(1).getAsInt();
            if (a < 0 || b < 0 || a >= n.nodes.size() || b >= n.nodes.size()) continue;
            n.links.get(a).add(b);
            n.links.get(b).add(a);
        }
        return n;
    }

    boolean empty() {
        return nodes.isEmpty();
    }

    int size() {
        return nodes.size();
    }

    boolean covers(World w) {
        return !empty() && w.getName().equalsIgnoreCase(world);
    }

    Location location(World w, int node) {
        double[] p = nodes.get(node);
        return new Location(w, p[0], p[1], p[2]);
    }

    /** The node closest to a place. */
    int nearest(Location at) {
        int best = -1;
        double d = Double.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            double v = distanceSquared(i, at);
            if (v < d) {
                d = v;
                best = i;
            }
        }
        return best;
    }

    /** A random node between two distances of a place, -1 when there is none. */
    int randomAround(Location at, double min, double max) {
        List<Integer> ok = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            double v = distanceSquared(i, at);
            if (v >= min * min && v <= max * max) ok.add(i);
        }
        return ok.isEmpty() ? -1 : ok.get(ThreadLocalRandom.current().nextInt(ok.size()));
    }

    /** A random neighbour of a node, not the one it comes from unless it is a dead end. */
    int next(int node, int previous) {
        List<Integer> l = links.get(node);
        if (l.isEmpty()) return node;
        if (l.size() == 1) return l.get(0);
        int pick;
        do pick = l.get(ThreadLocalRandom.current().nextInt(l.size())); while (pick == previous);
        return pick;
    }

    private double distanceSquared(int node, Location at) {
        double[] p = nodes.get(node);
        double dx = p[0] - at.getX(), dy = p[1] - at.getY(), dz = p[2] - at.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
