package fr.influencraft.fluxdungeons.dungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Plan of a network of galleries dug by a huge creature under a square arena (pure code, no Bukkit: tested by
 * {@code GalleryPlanTest}). Coordinates are local: x from 0 to {@code size - 1}, z from 0 to {@code depth - 1}, y
 * absolute. The arena covers the last {@code size} rows of z; with {@code depth > size} the network also runs under an
 * approach ground south of it (the first galleries and the maze), entered by a burrow hole dug from the surface, and
 * the junctions under the arena (the boss' own ground) are the last ones met.
 * <p>
 * Two levels of junctions on a 3 x 3 grid each (jittered), linked by a random spanning tree (dead ends) plus a few
 * extra links (passages that meet again), two long ramps between the levels, short spurs ending in nothing. Every link
 * is a sinuous tunnel (Catmull-Rom curve through two offset control points) carved as a chain of ellipsoids about 3
 * blocks wide and 4 high at the centre, its floor wavering by a block. A few junctions are wider rooms; the junction of
 * the lower level farthest from the entrance is the nest, a big chamber. The trail is the shortest way from the
 * entrance to the nest (the boss' own way, marked by the dungeon). A plan is checked walkable (steps of one block,
 * head room) before being used; {@link #generate} tries other seeds until one is.
 */
public final class GalleryPlan {

    /** Settings: arena side, length of the whole ground (arena + approach), top of the mass, the two gallery floors. */
    public record Settings(int size, int depth, int top, int upper, int lower, int bottom) {
        public static Settings of(int size, int top) {
            return of(size, size, top);
        }

        public static Settings of(int size, int depth, int top) {
            return new Settings(size, Math.max(size, depth), top, top - 11, top - 20, top - 27);
        }

        /** First z row of the arena (0 without an approach). */
        public int arenaZ0() {
            return depth - size;
        }

        /** Rows of junctions of the upper level under the approach ground. */
        int approachRows() {
            return (int) Math.round((depth - size) / (size / 3.0));
        }
    }

    public enum Kind { JUNCTION, ROOM, NEST, ENTRANCE }

    /** A junction: centre (x, z), floor (the block walked on), level 0 (upper) or 1 (lower). */
    public record Node(int id, double x, double z, int floor, int level, Kind kind) {
    }

    /** A point of a tunnel: centre (x, z) and the floor under it. */
    public record Point(double x, double z, int floor) {
    }

    public final Settings settings;
    public final long seed;
    private final boolean[] air;
    private final int height;
    public final List<Node> nodes = new ArrayList<>();
    /** Every tunnel (links, ramps, spurs) as a list of points. */
    public final List<List<Point>> tunnels = new ArrayList<>();
    /** Ends of the spurs (dead ends). */
    public final List<Point> deadEnds = new ArrayList<>();
    /** From the entrance to the nest, along the tunnels. */
    public final List<Point> trail = new ArrayList<>();
    public Node entrance, nest;
    /** The burrow hole on the surface (approach ground only), where the players come down; null without an approach. */
    public Point hole;
    public static final double NEST_RADIUS = 5.2;
    private final List<int[]> links = new ArrayList<>();
    private final List<List<Point>> linkPaths = new ArrayList<>();

    private GalleryPlan(Settings s, long seed) {
        this.settings = s;
        this.seed = seed;
        this.height = s.top() - s.bottom() + 1;
        this.air = new boolean[s.size() * s.depth() * height];
    }

    /** A walkable plan, from this seed or the next ones. */
    public static GalleryPlan generate(Settings s, long seed) {
        for (int i = 0; i < 40; i++) {
            GalleryPlan p = new GalleryPlan(s, seed + i * 7919L);
            p.build();
            if (p.walkable()) return p;
        }
        throw new IllegalStateException("no walkable gallery plan near seed " + seed);
    }

    // ---------------------------------------------------------------- cells

    public boolean air(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= settings.size() || z >= settings.depth() || y < settings.bottom()) return false;
        if (y > settings.top()) return y <= settings.top() + 3;              // the open air over the ground
        return air[index(x, y, z)];
    }

    private int index(int x, int y, int z) {
        return ((y - settings.bottom()) * settings.depth() + z) * settings.size() + x;
    }

    /** True under the arena (and the row just before it): the boss' own ground. */
    public boolean underArena(double z) {
        return z >= settings.arenaZ0() - 1;
    }

    /** Lowest and highest x / z a tunnel may reach (a solid shell of 2 blocks stays around). */
    private int lo() {
        return 2;
    }

    private int hi() {
        return settings.size() - 3;
    }

    private int hiZ() {
        return settings.depth() - 3;
    }

    /** Highest y a tunnel may reach: 3 solid blocks stay under the arena floor. */
    public int roof() {
        return settings.top() - 4;
    }

    private void set(int x, int y, int z) {
        if (x < lo() || z < lo() || x > hi() || z > hiZ() || y <= settings.bottom()) return;
        // 3 solid blocks stay under the arena; under the approach ground only the burrow hole reaches the surface
        if (y > (surfaceOpen ? settings.top() : roof()) || (y > roof() && underArena(z))) return;
        air[index(x, y, z)] = true;
    }

    private boolean surfaceOpen;

    /**
     * Ellipsoid of air above a floor: centre {@code floor + 1 + lift}, horizontal radius {@code rw}, vertical
     * {@code rh}; the floor block itself is never dug.
     */
    private void carve(double cx, int floor, double cz, double rw, double rh, double lift) {
        double cy = floor + 1 + lift;
        int r = (int) Math.ceil(rw);
        for (int y = floor + 1; y <= (int) Math.ceil(cy + rh); y++) {
            double dy = (y + 0.5 - cy - 0.5) / rh;
            double k = 1 - dy * dy;
            if (k <= 0) continue;
            double reach = rw * Math.sqrt(k);
            for (int x = (int) Math.floor(cx - r); x <= (int) Math.ceil(cx + r); x++) {
                for (int z = (int) Math.floor(cz - r); z <= (int) Math.ceil(cz + r); z++) {
                    double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
                    if (dx * dx + dz * dz <= reach * reach) set(x, y, z);
                }
            }
        }
    }

    // ---------------------------------------------------------------- building

    private void build() {
        Random r = new Random(seed);
        Settings s = settings;
        double span = hi() - lo() - 2;
        int grid = 3, extraRows = s.approachRows();
        // rows of junctions per level: the upper level runs under the approach and the arena, the lower one under the
        // arena only; the cells of a level are numbered i * rows + j (i across x, j along z)
        int[] rows = {grid + extraRows, grid};
        int[][] id = new int[2][];
        for (int level = 0; level < 2; level++) {
            int n0 = rows[level];
            id[level] = new int[grid * n0];
            double z0 = level == 0 ? lo() + 1 : s.arenaZ0() + lo() + 1;
            double spanZ = level == 0 ? hiZ() - lo() - 2 : span;
            for (int i = 0; i < grid; i++) {
                for (int j = 0; j < n0; j++) {
                    double x = lo() + 1 + (i + 0.5) * span / grid + r.nextDouble(-1.3, 1.3);
                    double z = z0 + (j + 0.5) * spanZ / n0 + r.nextDouble(-1.3, 1.3);
                    // the lower level never rises: the nest's high vault stays under the upper floors
                    int floor = level == 0 ? s.upper() + r.nextInt(3) - 1 : s.lower() - r.nextInt(2);
                    Node n = new Node(nodes.size(), x, z, floor, level, Kind.JUNCTION);
                    id[level][i * n0 + j] = n.id();
                    nodes.add(n);
                }
            }
            // random spanning tree over the grid (dead ends), then a couple of extra links (loops)
            List<int[]> candidates = new ArrayList<>();
            for (int i = 0; i < grid; i++) {
                for (int j = 0; j < n0; j++) {
                    if (i + 1 < grid) candidates.add(new int[]{i * n0 + j, (i + 1) * n0 + j});
                    if (j + 1 < n0) candidates.add(new int[]{i * n0 + j, i * n0 + j + 1});
                }
            }
            Collections.shuffle(candidates, r);
            int[] parent = new int[grid * n0];
            for (int k = 0; k < parent.length; k++) parent[k] = k;
            List<int[]> extra = new ArrayList<>();
            for (int[] c : candidates) {
                int a = find(parent, c[0]), b = find(parent, c[1]);
                if (a != b) {
                    parent[a] = b;
                    links.add(new int[]{id[level][c[0]], id[level][c[1]]});
                } else {
                    extra.add(c);
                }
            }
            int loops = 2 + (level == 0 ? extraRows / 2 : 0);
            for (int k = 0; k < Math.min(loops, extra.size()); k++) links.add(new int[]{id[level][extra.get(k)[0]], id[level][extra.get(k)[1]]});
        }
        // two long ramps between the levels: an upper junction over the arena to a lower one two cells away
        List<int[]> ramps = new ArrayList<>();
        for (int i = 0; i < grid; i++) {
            for (int j = 0; j < grid; j++) {
                for (int i2 = 0; i2 < grid; i2++) {
                    for (int j2 = 0; j2 < grid; j2++) {
                        int di = Math.abs(i - i2), dj = Math.abs(j - j2);
                        if (di == 2 && dj == 0 || di == 0 && dj == 2)
                            ramps.add(new int[]{id[0][i * rows[0] + j + extraRows], id[1][i2 * grid + j2]});
                    }
                }
            }
        }
        Collections.shuffle(ramps, r);
        int made = 0;
        List<Integer> usedTop = new ArrayList<>();
        for (int[] rp : ramps) {
            if (made == 2) break;
            if (usedTop.contains(rp[0])) continue;
            Node a = nodes.get(rp[0]), b = nodes.get(rp[1]);
            if (made == 1) {
                // the second ramp far from the first one
                Node a0 = nodes.get(usedTop.get(0));
                if (Math.hypot(a.x() - a0.x(), a.z() - a0.z()) < span * 0.5) continue;
            }
            usedTop.add(rp[0]);
            links.add(rp);
            made++;
        }
        // entrance: an upper corner junction (with an approach: the middle one of the first row, the farthest from the
        // arena); the nest: the lower junction farthest from it along the links
        int entranceId = extraRows > 0 ? id[0][rows[0]] : id[0][r.nextBoolean() ? 0 : grid * grid - 1];
        List<Point>[] paths = carveLinks(r);
        double[] dist = distances(entranceId);
        int nestId = -1;
        for (int k = 0; k < grid * grid; k++) {
            int n = id[1][k];
            if (dist[n] < Double.MAX_VALUE && (nestId < 0 || dist[n] > dist[nestId])) nestId = n;
        }
        if (nestId < 0) return;
        mark(entranceId, Kind.ENTRANCE);
        mark(nestId, Kind.NEST);
        entrance = nodes.get(entranceId);
        nest = nodes.get(nestId);
        // rooms: one per level under the arena, and with an approach one more per row of it (never the entrance)
        for (int level = 0; level < 2; level++) {
            List<Integer> pick = new ArrayList<>();
            for (int i = 0; i < grid; i++) {
                for (int j = level == 0 ? extraRows : 0; j < rows[level]; j++) {
                    int n = id[level][i * rows[level] + j];
                    if (n != entranceId && n != nestId) pick.add(n);
                }
            }
            mark(pick.get(r.nextInt(pick.size())), Kind.ROOM);
        }
        for (int j = 1; j < extraRows; j++) {
            int n = id[0][r.nextInt(grid) * rows[0] + j];
            if (n != entranceId && nodes.get(n).kind() == Kind.JUNCTION) mark(n, Kind.ROOM);
        }
        // junctions, rooms, entrance, nest
        for (Node n : nodes) {
            switch (n.kind()) {
                case NEST -> {
                    carve(n.x(), n.floor(), n.z(), NEST_RADIUS, 3.3, 2.4);
                    carve(n.x(), n.floor(), n.z(), NEST_RADIUS * 0.75, 3.4, 2.4);
                }
                case ROOM -> carve(n.x(), n.floor(), n.z(), extraRows > 0 ? 4.2 : 3.4, 2.6, 1.6);
                case ENTRANCE -> carve(n.x(), n.floor(), n.z(), 2.8, 2.4, 1.4);
                default -> carve(n.x(), n.floor(), n.z(), 2.1, 2.1, 1.2);
            }
        }
        // short spurs ending in nothing (dead ends with claw marks)
        int spurs = 3 + extraRows;
        for (int tries = 0; spurs > 0 && tries < 60; tries++) {
            Node from = nodes.get(r.nextInt(nodes.size()));
            if (from.kind() == Kind.NEST || from.kind() == Kind.ENTRANCE) continue;
            double a = r.nextDouble(Math.PI * 2), len = r.nextDouble(4.5, 7);
            double ex = from.x() + Math.cos(a) * len, ez = from.z() + Math.sin(a) * len;
            if (ex < lo() + 1.5 || ez < lo() + 1.5 || ex > hi() - 1.5 || ez > hiZ() - 1.5) continue;
            Point end = new Point(ex, ez, from.floor() + (r.nextBoolean() ? 1 : 0));
            List<Point> path = curve(r, new Point(from.x(), from.z(), from.floor()), end, 1.2);
            dig(path, r);
            tunnels.add(path);
            deadEnds.add(end);
            spurs--;
        }
        // the burrow hole: on the approach ground, about halfway to the arena; a ramp goes down from it to the entrance
        if (extraRows > 0) {
            Node e = nodes.get(entranceId);
            double hz = Math.min(s.arenaZ0() - 8, e.z() + 2 * (s.top() - e.floor()) + 2);
            hole = new Point(e.x() + r.nextDouble(-2, 2), hz, s.top());
            List<Point> ramp = new ArrayList<>(curve(r, hole, new Point(e.x(), e.z(), e.floor()), 1.4));
            // a straight even slope: the floor goes down one block every two
            for (int i = 0; i < ramp.size(); i++) {
                Point q = ramp.get(i);
                double t = (double) i / Math.max(1, ramp.size() - 1);
                ramp.set(i, new Point(q.x(), q.z(), (int) Math.round(s.top() - 1 + (e.floor() - s.top() + 1) * t)));
            }
            surfaceOpen = true;
            dig(ramp, r);
            surfaceOpen = false;
            tunnels.add(ramp);
        }
        // the trail: shortest way from the entrance to the nest
        List<Integer> route = route(entranceId, nestId);
        for (int k = 0; k + 1 < route.size(); k++) {
            int a = route.get(k), b = route.get(k + 1);
            for (int li = 0; li < links.size(); li++) {
                int[] l = links.get(li);
                if (l[0] == a && l[1] == b) trail.addAll(paths[li]);
                else if (l[0] == b && l[1] == a) {
                    List<Point> rev = new ArrayList<>(paths[li]);
                    Collections.reverse(rev);
                    trail.addAll(rev);
                } else continue;
                break;
            }
        }
    }

    private void mark(int id, Kind kind) {
        Node n = nodes.get(id);
        nodes.set(id, new Node(n.id(), n.x(), n.z(), n.floor(), n.level(), kind));
    }

    private static int find(int[] parent, int a) {
        while (parent[a] != a) a = parent[a] = parent[parent[a]];
        return a;
    }

    @SuppressWarnings("unchecked")
    private List<Point>[] carveLinks(Random r) {
        List<Point>[] paths = new List[links.size()];
        for (int k = 0; k < links.size(); k++) {
            Node a = nodes.get(links.get(k)[0]), b = nodes.get(links.get(k)[1]);
            boolean ramp = a.level() != b.level();
            List<Point> path = curve(r, new Point(a.x(), a.z(), a.floor()), new Point(b.x(), b.z(), b.floor()), ramp ? 1.6 : 2.6);
            dig(path, r);
            paths[k] = path;
            tunnels.add(path);
            linkPaths.add(path);
        }
        return paths;
    }

    /** Points about every 0.3 block along a sinuous curve between two points (offset control points), floors blended. */
    private List<Point> curve(Random r, Point a, Point b, double sway) {
        double dx = b.x() - a.x(), dz = b.z() - a.z(), len = Math.hypot(dx, dz);
        double px = -dz / Math.max(1e-6, len), pz = dx / Math.max(1e-6, len);
        double o1 = r.nextDouble(-sway, sway), o2 = r.nextDouble(-sway, sway);
        double[][] c = {
                {a.x(), a.z()},
                {a.x(), a.z()},
                {a.x() + dx / 3 + px * o1, a.z() + dz / 3 + pz * o1},
                {a.x() + dx * 2 / 3 + px * o2, a.z() + dz * 2 / 3 + pz * o2},
                {b.x(), b.z()},
                {b.x(), b.z()}};
        double poly = 0;
        for (int i = 1; i < c.length; i++) poly += Math.hypot(c[i][0] - c[i - 1][0], c[i][1] - c[i - 1][1]);
        int steps = Math.max(4, (int) Math.ceil(poly / 0.3));     // the control polygon is longer than the curve
        double wobble = a.floor() == b.floor() ? 0.6 : 0, phase = r.nextDouble(Math.PI * 2);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps, u = t * 3;
            int seg = Math.min(2, (int) u);
            double f = u - seg;
            double x = catmull(c[seg][0], c[seg + 1][0], c[seg + 2][0], c[seg + 3][0], f);
            double z = catmull(c[seg][1], c[seg + 1][1], c[seg + 2][1], c[seg + 3][1], f);
            // a ramp climbs evenly (an eased slope is too steep in its middle to walk)
            double fl = a.floor() + (b.floor() - a.floor()) * (wobble == 0 ? t : smooth(t)) + wobble * Math.sin(phase + t * Math.PI * 2) * Math.sin(Math.PI * t);
            out.add(new Point(x, z, (int) Math.round(fl)));
        }
        return out;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** Digs a tunnel along its points: about 3 wide, 4 high (3 with an approach: a narrower burrow), wavering a little. */
    private void dig(List<Point> path, Random r) {
        double phase = r.nextDouble(Math.PI * 2);
        boolean narrow = settings.approachRows() > 0;
        for (int i = 0; i < path.size(); i++) {
            Point p = path.get(i);
            double rw = (narrow ? 1.6 : 1.75) + 0.2 * Math.sin(phase + i * 0.23);
            carve(p.x(), p.floor(), p.z(), rw, narrow ? 1.75 : 2.0, narrow ? 1.0 : 1.2);
        }
    }

    private double[] distances(int from) {
        double[] d = new double[nodes.size()];
        Arrays.fill(d, Double.MAX_VALUE);
        d[from] = 0;
        boolean[] done = new boolean[nodes.size()];
        for (int it = 0; it < nodes.size(); it++) {
            int u = -1;
            for (int k = 0; k < d.length; k++) if (!done[k] && d[k] < Double.MAX_VALUE && (u < 0 || d[k] < d[u])) u = k;
            if (u < 0) break;
            done[u] = true;
            for (int li = 0; li < links.size(); li++) {
                int[] l = links.get(li);
                int v = l[0] == u ? l[1] : l[1] == u ? l[0] : -1;
                if (v < 0) continue;
                double w = linkPaths.get(li).size();
                if (d[u] + w < d[v]) d[v] = d[u] + w;
            }
        }
        return d;
    }

    private List<Integer> route(int from, int to) {
        double[] d = distances(from);
        List<Integer> out = new ArrayList<>();
        int at = to;
        out.add(at);
        while (at != from) {
            int best = -1;
            for (int li = 0; li < links.size(); li++) {
                int[] l = links.get(li);
                int v = l[0] == at ? l[1] : l[1] == at ? l[0] : -1;
                if (v < 0) continue;
                double w = linkPaths.get(li).size();
                if (Math.abs(d[v] + w - d[at]) < 1e-6) {
                    best = v;
                    break;
                }
            }
            if (best < 0) break;
            out.add(best);
            at = best;
        }
        Collections.reverse(out);
        return out;
    }

    // ---------------------------------------------------------------- checks

    /** A player can stand here: two air blocks over a solid one. */
    public boolean standable(int x, int y, int z) {
        return air(x, y, z) && air(x, y + 1, z) && !air(x, y - 1, z) && y - 1 >= settings.bottom();
    }

    /** The stand cell of a column at a floor (the floor block + 1), searched a block up and down. */
    public int standY(int x, int floor, int z) {
        for (int y : new int[]{floor + 1, floor + 2, floor, floor + 3}) if (standable(x, y, z)) return y;
        return Integer.MIN_VALUE;
    }

    /** Stand cells reachable from the entrance by walking (steps of one block, head room to climb one). */
    public boolean[] reachable() {
        int s = settings.size(), depthZ = settings.depth();
        boolean[] seen = new boolean[air.length];
        if (entrance == null) return seen;
        int ex = (int) Math.floor(entrance.x()), ez = (int) Math.floor(entrance.z());
        int ey = standY(ex, entrance.floor(), ez);
        if (ey == Integer.MIN_VALUE) return seen;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{ex, ey, ez});
        seen[index(ex, ey, ez)] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : dirs) {
                for (int dy = -1; dy <= 1; dy++) {
                    int nx = c[0] + d[0], ny = c[1] + dy, nz = c[2] + d[1];
                    if (nx < 0 || nz < 0 || nx >= s || nz >= depthZ) continue;
                    if (ny > settings.top()) continue;
                    if (!standable(nx, ny, nz)) continue;
                    if (dy == 1 && !air(c[0], c[1] + 2, c[2])) continue;      // head room to jump up
                    if (dy == -1 && !air(nx, ny + 2, nz)) continue;            // head room to step down
                    int k = index(nx, ny, nz);
                    if (seen[k]) continue;
                    seen[k] = true;
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }
        return seen;
    }

    /** The nest can be walked to, and most of the floor is reachable (no big closed pocket). */
    public boolean walkable() {
        if (entrance == null || nest == null || trail.isEmpty()) return false;
        boolean[] seen = reachable();
        int nx = (int) Math.floor(nest.x()), nz = (int) Math.floor(nest.z());
        int ny = standY(nx, nest.floor(), nz);
        if (ny == Integer.MIN_VALUE || !seen[index(nx, ny, nz)]) return false;
        int stand = 0, reached = 0;
        int s = settings.size();
        for (int y = settings.bottom() + 1; y < settings.top(); y++) {
            for (int z = 0; z < settings.depth(); z++) {
                for (int x = 0; x < s; x++) {
                    if (!standable(x, y, z)) continue;
                    stand++;
                    if (seen[index(x, y, z)]) reached++;
                }
            }
        }
        return stand > 0 && reached >= stand * 0.9;
    }

    /** Count of dug cells (for tests and logs). */
    public int dug() {
        int n = 0;
        for (boolean b : air) if (b) n++;
        return n;
    }

    /** Top-down map of a level for logs and tests: '#' rock, '.' air at this height, 'E' entrance, 'N' nest. */
    public String map(int y) {
        StringBuilder sb = new StringBuilder();
        int s = settings.size();
        for (int z = 0; z < settings.depth(); z++) {
            for (int x = 0; x < s; x++) {
                char ch = air(x, y, z) ? '.' : '#';
                if (entrance != null && (int) entrance.x() == x && (int) entrance.z() == z) ch = 'E';
                if (nest != null && (int) nest.x() == x && (int) nest.z() == z) ch = 'N';
                sb.append(ch);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
