package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.economy.BalanceChangeEvent;
import fr.influencraft.flux.api.economy.Ledger;
import fr.influencraft.flux.api.economy.Origin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Flux's account history ({@link Ledger}): records every {@link BalanceChangeEvent} with its origin in the table
 * {@code flux_core_ledger}.
 *
 * <ul>
 *   <li>Merging: a movement joins the open line of the same player, currency, origin and direction when that line was
 *   touched less than {@code merge-seconds} ago and neither carries a detail. A miner paid on every block makes one
 *   line per stretch of mining, not thousands.</li>
 *   <li>Writes leave the main thread: lines are kept in memory and written every few seconds by one database thread
 *   (insert, then update while the line stays open). Order is kept: one thread, one queue.</li>
 *   <li>Online players' lines are cached (loaded before they join, at most {@code menu-lines}); the menu reads the
 *   cache only.</li>
 *   <li>Lines older than {@code keep-days} are deleted once a day.</li>
 * </ul>
 */
public final class LedgerService implements Ledger.Backend, Listener {

    private static final String TABLE = Database.table("flux_core_ledger");

    /** A line being built (shared by the open map and the player's cache, so merging updates both). */
    static final class Entry {
        long id = -1;
        final UUID player;
        final String currency;
        final Origin origin;
        double amount;
        int count;
        double balance;
        final long first;
        long last;
        boolean dirty = true;

        Entry(UUID player, String currency, Origin origin, double amount, double balance, long at) {
            this.player = player;
            this.currency = currency;
            this.origin = origin;
            this.amount = amount;
            this.count = 1;
            this.balance = balance;
            this.first = at;
            this.last = at;
        }

        Ledger.Line line() {
            return new Ledger.Line(id, currency, origin, amount, count, balance, first, last);
        }
    }

    private record Key(UUID player, String currency, String origin, boolean gain) {
    }

    private final JavaPlugin flux;
    private final OriginResolver resolver = new OriginResolver();
    private final ExecutorService db = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Flux-ledger");
        t.setDaemon(true);
        return t;
    });
    /** Lines still open to merging, and lines waiting to be written. Guarded by {@code this}. */
    private final Map<Key, Entry> open = new HashMap<>();
    private final List<Entry> pending = new ArrayList<>();
    /** Recent lines of online players (and of histories looked at), most recent first. Guarded by {@code this}. */
    private final Map<UUID, LinkedList<Entry>> recent = new HashMap<>();
    private volatile boolean enabled = true;
    private volatile long mergeMs = 300_000;
    /** Origins merged by clock hour instead of the merge window (ledger.hourly: jobs paid on every action). */
    private volatile java.util.Set<String> hourly = java.util.Set.of("ecojobs", "libreforge");
    private volatile int keepDays = 90;
    private volatile int menuLines = 300;
    private volatile ConfigurationSection labels;
    /** ledger.debug-unknown: log where a movement without origin comes from (thread, stack), to name it. */
    private volatile boolean debugUnknown;
    private volatile long lastUnknownLog;
    private volatile Map<String, String> ignored = Map.of();
    /** Changes announced by {@link Ledger#expect}, waiting for their late event (CMI). Oldest first. */
    private final java.util.Deque<Expected> expected = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final long EXPECT_MS = 30_000;

    private record Expected(UUID player, String currency, double delta, Origin origin, long at) {
    }

    public LedgerService(JavaPlugin flux) {
        this.flux = flux;
    }

    public void start() {
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            // name before 2026-10-31
            Database.renameTable(c, Database.table("flux_ledger"), TABLE, "flux_ledger_player", "flux_ledger_age");
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id " + d.autoId() + ", uuid " + d.key(36) + " NOT NULL, currency "
                    + d.key(32) + " NOT NULL, origin " + d.key(96) + " NOT NULL, detail TEXT, verb " + d.key(24) + ", amount DOUBLE NOT NULL,"
                    + " count INT NOT NULL, balance DOUBLE NOT NULL, first_at BIGINT NOT NULL, last_at BIGINT NOT NULL)");
            Database.index(c, "flux_core_ledger_player", TABLE, "uuid, last_at", false);
            Database.index(c, "flux_core_ledger_age", TABLE, "last_at", false);
        } catch (SQLException e) {
            flux.getLogger().severe("Account history unavailable (table " + TABLE + "): " + e.getMessage());
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, flux);
        Ledger.bind(this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(flux, () -> db.execute(this::flush), 100L, 100L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(flux, () -> db.execute(this::purge), 1200L, 20L * 86400);
        // players already online (reload)
        for (var p : Bukkit.getOnlinePlayers()) history(p.getUniqueId());
    }

    public void configure(ConfigurationSection config) {
        if (config == null) return;
        enabled = config.getBoolean("enabled", true);
        mergeMs = Math.max(0, config.getLong("merge-seconds", 300)) * 1000L;
        if (config.isList("hourly")) {
            hourly = java.util.Set.copyOf(config.getStringList("hourly").stream().map(x -> x.toLowerCase(java.util.Locale.ROOT)).toList());
        }
        keepDays = Math.max(1, config.getInt("keep-days", 90));
        menuLines = Math.max(20, config.getInt("menu-lines", 300));
        labels = config.getConfigurationSection("origins");
        debugUnknown = config.getBoolean("debug-unknown", false);
        resolver.configure(config);
        Map<String, String> skip = new HashMap<>();
        for (String id : config.getStringList("ignored")) skip.put(id.toLowerCase(java.util.Locale.ROOT), id);
        ignored = skip;
    }

    public void stop() {
        Ledger.bind(null);
        db.execute(this::flush);
        db.shutdown();
        try {
            if (!db.awaitTermination(10, TimeUnit.SECONDS)) flux.getLogger().warning("Account history: last lines not written in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ recording

    /** Runs on the thread that changed the balance: the origin context and the stack are this movement's. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBalance(BalanceChangeEvent event) {
        if (!enabled) return;
        double delta = Math.round(event.delta() * 100) / 100.0;
        if (Math.abs(delta) < 0.005) return;
        Origin origin = Ledger.current();
        if (origin == null) origin = claimExpected(event);
        if (origin == null) origin = resolver.resolve();
        if (debugUnknown && origin == Origin.UNKNOWN) logUnknown(event);
        if (ignored.containsKey(origin.id().toLowerCase(java.util.Locale.ROOT))) return;
        record(event.player(), event.currency(), delta, event.to(), origin, System.currentTimeMillis());
    }

    @Override
    public int renameCurrency(String from, String to) throws SQLException {
        int moved;
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + TABLE + " SET currency = ? WHERE currency = ?")) {
            ps.setString(1, to);
            ps.setString(2, from);
            moved = ps.executeUpdate();
        }
        // histories already in memory were read under the former id: read again on the next look
        synchronized (this) {
            recent.clear();
        }
        for (var p : Bukkit.getOnlinePlayers()) history(p.getUniqueId());
        return moved;
    }

    @Override
    public Runnable expect(UUID player, String currency, double delta) {
        Origin origin = Ledger.current();
        if (origin == null) origin = resolver.resolve();
        if (origin == null || origin.unknown()) return () -> { };
        Expected e = new Expected(player, currency, delta, origin, System.currentTimeMillis());
        expected.add(e);
        return () -> expected.remove(e);
    }

    /** Origin announced for this change (oldest matching announcement, consumed), or null. Drops stale ones. */
    private Origin claimExpected(BalanceChangeEvent event) {
        long now = System.currentTimeMillis();
        expected.removeIf(e -> now - e.at() > EXPECT_MS);
        for (Expected e : expected) {
            if (e.player().equals(event.player()) && e.currency().equalsIgnoreCase(event.currency())
                    && Math.abs(e.delta() - event.delta()) < 0.011 && expected.remove(e)) {
                return e.origin();
            }
        }
        return null;
    }

    /** One log line per 10 s at most: thread and the first frames of the stack, to write a class rule or fix a plugin. */
    private void logUnknown(BalanceChangeEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastUnknownLog < 10_000) return;
        lastUnknownLog = now;
        StringBuilder frames = new StringBuilder();
        StackWalker.getInstance().forEach(f -> {
            if (frames.length() < 1500) frames.append("\n  at ").append(f.getClassName()).append('.').append(f.getMethodName());
        });
        flux.getLogger().info("[ledger] movement without origin: " + event.currency() + " " + event.delta() + " on thread "
                + Thread.currentThread().getName() + frames);
    }

    synchronized void record(UUID player, String currency, double delta, double balance, Origin origin, long now) {
        Key key = new Key(player, currency, origin.id(), delta >= 0);
        Entry line = open.get(key);
        boolean byHour = hourly(origin);
        if (line != null && origin.detail() == null && line.origin.detail() == null
                && (byHour ? hour(line.first) == hour(now) : now - line.last <= mergeMs)) {
            line.amount = Math.round((line.amount + delta) * 100) / 100.0;
            line.count++;
            line.balance = balance;
            line.last = now;
            if (!line.dirty) {
                line.dirty = true;
                pending.add(line);
            }
            return;
        }
        line = new Entry(player, currency, origin, delta, balance, now);
        open.put(key, line);
        pending.add(line);
        LinkedList<Entry> lines = recent.get(player);
        if (lines != null) {
            lines.addFirst(line);
            while (lines.size() > menuLines) lines.removeLast();
        }
    }

    /** Whether lines of this origin are merged by clock hour (and shown as "18h" in the menu). */
    public boolean hourly(Origin origin) {
        return origin != null && hourly.contains(origin.plugin().toLowerCase(java.util.Locale.ROOT));
    }

    /** The clock hour of a time, in the server's zone (hours since the epoch). */
    static long hour(long millis) {
        int offset = java.time.ZoneId.systemDefault().getRules().getOffset(java.time.Instant.ofEpochMilli(millis)).getTotalSeconds();
        return Math.floorDiv(millis / 1000 + offset, 3600);
    }

    /** Database thread: writes the pending lines, forgets the open lines past the merge window. */
    private void flush() {
        List<Entry> batch;
        long now = System.currentTimeMillis();
        synchronized (this) {
            batch = new ArrayList<>(pending);
            pending.clear();
            for (Entry e : batch) e.dirty = false;
            open.values().removeIf(e -> hourly(e.origin) ? hour(e.first) != hour(now) : now - e.last > mergeMs);
        }
        if (batch.isEmpty()) return;
        try (Connection c = Database.connection()) {
            for (Entry e : batch) {
                double amount, balance;
                int count;
                long last;
                synchronized (this) {
                    amount = e.amount;
                    balance = e.balance;
                    count = e.count;
                    last = e.last;
                }
                if (e.id < 0) insert(c, e, amount, balance, count, last);
                else update(c, e, amount, balance, count, last);
            }
        } catch (SQLException ex) {
            flux.getLogger().warning("Account history: " + batch.size() + " line(s) not written, retried later: " + ex.getMessage());
            synchronized (this) {
                for (Entry e : batch) {
                    if (!e.dirty) {
                        e.dirty = true;
                        pending.add(e);
                    }
                }
            }
        }
    }

    private void insert(Connection c, Entry e, double amount, double balance, int count, long last) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, currency, origin, detail, verb, amount, count, balance, first_at, last_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.player.toString());
            ps.setString(2, e.currency);
            ps.setString(3, e.origin.id());
            ps.setString(4, e.origin.detail());
            ps.setString(5, e.origin.verb());
            ps.setDouble(6, amount);
            ps.setInt(7, count);
            ps.setDouble(8, balance);
            ps.setLong(9, e.first);
            ps.setLong(10, last);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) e.id = keys.getLong(1);
            }
        }
    }

    private void update(Connection c, Entry e, double amount, double balance, int count, long last) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + TABLE + " SET amount = ?, count = ?, balance = ?, last_at = ? WHERE id = ?")) {
            ps.setDouble(1, amount);
            ps.setInt(2, count);
            ps.setDouble(3, balance);
            ps.setLong(4, last);
            ps.setLong(5, e.id);
            ps.executeUpdate();
        }
    }

    private void purge() {
        long before = System.currentTimeMillis() - keepDays * 86_400_000L;
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE last_at < ?")) {
            ps.setLong(1, before);
            int n = ps.executeUpdate();
            if (n > 0) flux.getLogger().info("Account history: " + n + " line(s) older than " + keepDays + " days deleted");
        } catch (SQLException e) {
            flux.getLogger().warning("Account history: purge failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ reading

    @Override
    public CompletableFuture<List<Ledger.Line>> history(UUID player) {
        synchronized (this) {
            List<Ledger.Line> known = cached(player);
            if (known != null) return CompletableFuture.completedFuture(known);
        }
        CompletableFuture<List<Ledger.Line>> result = new CompletableFuture<>();
        try {
            db.execute(() -> {
                flush(); // what is still in memory reaches the table first
                List<Entry> loaded = load(player);
                synchronized (this) {
                    LinkedList<Entry> lines = recent.get(player);
                    if (lines == null) {
                        lines = new LinkedList<>(loaded);
                        // lines still open in memory are the same objects as the loaded rows: keep the live ones
                        for (Entry live : open.values()) {
                            if (!live.player.equals(player)) continue;
                            for (int i = 0; i < lines.size(); i++) if (lines.get(i).id == live.id) lines.set(i, live);
                        }
                        recent.put(player, lines);
                    }
                    result.complete(lines.stream().map(Entry::line).toList());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            result.complete(List.of());
        }
        return result;
    }

    @Override
    public synchronized List<Ledger.Line> cached(UUID player) {
        LinkedList<Entry> lines = recent.get(player);
        return lines == null ? null : lines.stream().map(Entry::line).toList();
    }

    private List<Entry> load(UUID player) {
        List<Entry> out = new ArrayList<>();
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, currency, origin, detail, verb, amount, count, balance, first_at, last_at FROM " + TABLE
                        + " WHERE uuid = ? ORDER BY last_at DESC LIMIT ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, menuLines);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Origin origin = Origin.parse(rs.getString(3)).detail(rs.getString(4)).verb(rs.getString(5));
                    Entry e = new Entry(player, rs.getString(2), origin, rs.getDouble(6), rs.getDouble(8), rs.getLong(9));
                    e.id = rs.getLong(1);
                    e.count = rs.getInt(7);
                    e.last = rs.getLong(10);
                    e.dirty = false;
                    out.add(e);
                }
            }
        } catch (SQLException e) {
            flux.getLogger().warning("Account history of " + player + " not read: " + e.getMessage());
        }
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) history(event.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        synchronized (this) {
            recent.remove(event.getPlayer().getUniqueId());
        }
    }

    /** A staff member looked at an offline player's history: forget it when nobody needs it. */
    public synchronized void release(UUID player) {
        if (Bukkit.getPlayer(player) == null) recent.remove(player);
    }

    // ------------------------------------------------------------------ labels

    /**
     * Label of an origin: {@code ledger.origins."plugin:key"}, the plugin's lang {@code origins.<key>} (suite plugin),
     * {@code ledger.origins.<plugin>}, the plugin's lang {@code origins.default}, then the plugin name.
     */
    @Override
    public String label(Origin origin) {
        if (origin.unknown()) return text("unknown", "?");
        ConfigurationSection l = labels;
        if (!origin.key().isEmpty()) {
            String own = l == null ? null : l.getString(origin.id());
            if (own != null) return own;
            String lang = pluginLang(origin.plugin(), "origins." + origin.key());
            if (lang != null) return lang;
        }
        String plugin = l == null ? null : l.getString(origin.plugin());
        if (plugin != null) return plugin;
        String lang = pluginLang(origin.plugin(), "origins.default");
        return lang != null ? lang : origin.plugin();
    }

    private String text(String key, String fallback) {
        ConfigurationSection l = labels;
        return l == null ? fallback : l.getString(key, fallback);
    }

    private static String pluginLang(String pluginName, String key) {
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        if (!(p instanceof FluxPlugin fp) || fp.messages() == null || !fp.messages().has(key)) return null;
        String v = fp.messages().raw(key);
        return v.isEmpty() ? null : v;
    }
}
