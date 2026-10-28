package fr.influencraft.flux.leaderboard;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.leaderboard.Leaderboards;
import fr.influencraft.flux.api.leaderboard.Leaderboards.Board;
import fr.influencraft.flux.api.leaderboard.Leaderboards.Entry;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine of {@link Leaderboards}: boards of Flux's config ({@code leaderboards}), values recorded in the table
 * {@code flux_leaderboard} (online players every {@code refresh-minutes} and on quit; every known player once, a few
 * per second, when a board has no value yet), top kept in memory, placeholders {@code %flux_top_...%}.
 */
public final class LeaderboardService implements Leaderboards.Service, Listener {

    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(?:[.,][0-9]+)?");
    private static final Pattern CODES = Pattern.compile("&#[0-9a-fA-F]{6}|[&§][0-9a-fk-orA-FK-OR]");

    private final Plugin flux;
    private String table;
    private int size = 10;
    private final Map<String, List<Entry>> tops = new ConcurrentHashMap<>();
    private final Map<String, Object[]> ranks = new ConcurrentHashMap<>(); // board:uuid -> {rank, millis}
    /** Last known value of each player per board ("board:uuid"), NaN = none: placeholders never query per render. */
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    private BukkitTask timer, backfill;
    /** Boards already checked for a first fill since the start. */
    private final java.util.Set<String> filled = ConcurrentHashMap.newKeySet();

    public LeaderboardService(Plugin flux) {
        this.flux = flux;
    }

    public void start(ConfigurationSection config) {
        table = Database.table("flux_leaderboard");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (board " + Database.dialect().key(64) + " NOT NULL, uuid "
                    + Database.dialect().key(36) + " NOT NULL, name VARCHAR(32), value DOUBLE NOT NULL, PRIMARY KEY (board, uuid))");
            Database.index(c, "flux_leaderboard_value", table, "board, value", false);
        } catch (SQLException e) {
            flux.getLogger().severe("Could not create " + table + ": " + e.getMessage());
            return;
        }
        Leaderboards.service(this);
        Bukkit.getPluginManager().registerEvents(this, flux);
        configure(config);
    }

    /** (Re)reads the boards of the config; called at start and on reload. */
    public void configure(ConfigurationSection config) {
        Leaderboards.unregisterAll(flux);
        if (timer != null) timer.cancel();
        if (config == null) return;
        size = Math.max(1, config.getInt("size", 10));
        ConfigurationSection boards = config.getConfigurationSection("boards");
        if (boards != null) {
            for (String id : boards.getKeys(false)) {
                ConfigurationSection b = boards.getConfigurationSection(id);
                Leaderboards.Source source = b == null ? null : source(b);
                if (source == null) {
                    flux.getLogger().warning("leaderboards.boards." + id + ": no statistic, money or placeholder, ignored");
                    continue;
                }
                Leaderboards.register(flux, id, source, format(b.getString("format", "number")));
                Leaderboards.name(id, b.getString("name"));
            }
        }
        long period = Math.max(1, config.getInt("refresh-minutes", 10)) * 1200L;
        timer = Bukkit.getScheduler().runTaskTimer(flux, this::refresh, 200L, period);
        Bukkit.getScheduler().runTaskLater(flux, this::startBackfill, 400L);
    }

    public void stop() {
        if (timer != null) timer.cancel();
        if (backfill != null) backfill.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) record(p);
    }

    // ---------------------------------------------------------------- sources and formats

    private Leaderboards.Source source(ConfigurationSection b) {
        if (b.isSet("statistic")) {
            Statistic stat;
            try {
                stat = Statistic.valueOf(b.getString("statistic", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
            return p -> (double) p.getStatistic(stat);
        }
        if (b.getBoolean("money", false)) {
            return p -> Economy.main() == null ? null : Economy.main().balance(p);
        }
        String placeholder = b.getString("placeholder");
        if (placeholder == null) return null;
        return p -> number(placeholder(p, placeholder));
    }

    private static String placeholder(OfflinePlayer p, String raw) {
        if (p.getPlayer() != null) return fr.influencraft.flux.Text.placeholders(p.getPlayer(), raw);
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return raw;
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, raw);
    }

    /** The number written in a placeholder's value ("1 234", "12,5", "&f42 points"), null when there is none. */
    static Double number(String text) {
        if (text == null || text.indexOf('%') >= 0) return null;
        String plain = CODES.matcher(text).replaceAll("").replace(" ", "").replace(" ", "").replace(" ", "");
        Matcher m = NUMBER.matcher(plain.replace(",", plain.contains(".") ? "" : "."));
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    private static DoubleFunction<String> format(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "ticks" -> v -> Format.duration((long) v / 20);
            case "seconds" -> v -> Format.duration((long) v);
            case "money" -> v -> Economy.main() == null ? Format.number(v) : Economy.main().format(v);
            default -> v -> Format.number(v);
        };
    }

    // ---------------------------------------------------------------- recording

    private void refresh() {
        for (Player p : Bukkit.getOnlinePlayers()) record(p);
        reloadTops();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        record(e.getPlayer());
        String suffix = ":" + e.getPlayer().getUniqueId();
        values.keySet().removeIf(k -> k.endsWith(suffix));
        ranks.keySet().removeIf(k -> k.endsWith(suffix));
    }

    private void record(OfflinePlayer p) {
        record(p, Leaderboards.boards().values());
    }

    private void record(OfflinePlayer p, java.util.Collection<Board> boards) {
        Map<String, Double> values = new HashMap<>();
        for (Board b : boards) {
            try {
                Double v = b.source().value(p);
                if (v != null && !v.isNaN()) values.put(b.id(), v);
            } catch (RuntimeException ignored) {
                // a source failing for one player (offline data unreadable) skips him
            }
        }
        if (p.isOnline()) for (Map.Entry<String, Double> e : values.entrySet()) this.values.put(e.getKey() + ":" + p.getUniqueId(), e.getValue());
        if (values.isEmpty()) return;
        String sql = Database.dialect().upsert(table, List.of("board", "uuid", "name", "value"), List.of("board", "uuid"), List.of("name", "value"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, Double> e : values.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, p.getUniqueId().toString());
                ps.setString(3, p.getName() == null ? "?" : p.getName());
                ps.setDouble(4, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            flux.getLogger().warning("Could not record the leaderboards of " + p.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Boards without any value yet, each once per start (a board that stays empty, e.g. its plugin is missing, is not
     * filled again on every reload): only those boards, for every player the server knows, 20 per second.
     */
    private void startBackfill() {
        List<Board> empty = new ArrayList<>();
        for (Board b : Leaderboards.boards().values()) {
            if (filled.add(b.id()) && count(b.id()) == 0) empty.add(b);
        }
        if (empty.isEmpty() || backfill != null) {
            reloadTops();
            return;
        }
        Deque<OfflinePlayer> queue = new ArrayDeque<>(List.of(Bukkit.getOfflinePlayers()));
        flux.getLogger().info("Leaderboards: first fill of " + empty.stream().map(Board::id).toList() + ", " + queue.size() + " players");
        backfill = Bukkit.getScheduler().runTaskTimer(flux, () -> {
            for (int i = 0; i < 20 && !queue.isEmpty(); i++) record(queue.poll(), empty);
            if (queue.isEmpty()) {
                backfill.cancel();
                backfill = null;
                reloadTops();
                flux.getLogger().info("Leaderboards: first fill done");
            }
        }, 20L, 20L);
    }

    private int count(String board) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE board = ?")) {
            ps.setString(1, board);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private void reloadTops() {
        ranks.clear();
        for (String board : Leaderboards.boards().keySet()) {
            List<Entry> top = new ArrayList<>();
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT uuid, name, value FROM " + table + " WHERE board = ? ORDER BY value DESC LIMIT " + size)) {
                ps.setString(1, board);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) top.add(new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getDouble(3)));
                }
            } catch (SQLException e) {
                flux.getLogger().warning("Could not read the leaderboard " + board + ": " + e.getMessage());
            }
            tops.put(board, List.copyOf(top));
        }
    }

    // ---------------------------------------------------------------- reading

    @Override
    public List<Entry> top(String board) {
        return tops.getOrDefault(board.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public Double value(String board, UUID player) {
        String key = board.toLowerCase(Locale.ROOT) + ":" + player;
        Double cached = values.get(key);
        if (cached != null) return cached.isNaN() ? null : cached;
        Double stored = stored(board, player);
        values.put(key, stored == null ? Double.NaN : stored);
        return stored;
    }

    private Double stored(String board, UUID player) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM " + table + " WHERE board = ? AND uuid = ?")) {
            ps.setString(1, board.toLowerCase(Locale.ROOT));
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public int rank(String board, UUID player) {
        String key = board.toLowerCase(Locale.ROOT) + ":" + player;
        Object[] cached = ranks.get(key);
        if (cached != null && System.currentTimeMillis() - (long) cached[1] < 60_000) return (int) cached[0];
        Double mine = value(board, player);
        int rank = 0;
        if (mine != null) {
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE board = ? AND value > ?")) {
                ps.setString(1, board.toLowerCase(Locale.ROOT));
                ps.setDouble(2, mine);
                try (ResultSet rs = ps.executeQuery()) {
                    rank = rs.next() ? rs.getInt(1) + 1 : 0;
                }
            } catch (SQLException e) {
                rank = 0;
            }
        }
        ranks.put(key, new Object[]{rank, System.currentTimeMillis()});
        return rank;
    }

    /**
     * {@code top_<board>_<n>_name}, {@code top_<board>_<n>_value}, {@code top_<board>_rank} (number),
     * {@code top_<board>_place} (« 1er », « 2e »), {@code top_<board>_value}
     * (params after « top_ »); the board id may hold underscores.
     */
    public String placeholder(Player viewer, String params) {
        int last = params.lastIndexOf('_');
        if (last < 0) return null;
        String field = params.substring(last + 1), rest = params.substring(0, last);
        if (field.equals("rank") || field.equals("place") || (field.equals("value") && Leaderboards.board(rest) != null)) {
            if (viewer == null) return "";
            if (!field.equals("value")) {
                int r = rank(rest, viewer.getUniqueId());
                if (r == 0) return "-";
                // place: « 1er », « 2e »... (word format.first-place / format.nth-place of Flux's language file)
                return field.equals("rank") ? String.valueOf(r) : r == 1 ? Format.word("first-place") : r + Format.word("nth-place");
            }
            Double v = value(rest, viewer.getUniqueId());
            return v == null ? "-" : Leaderboards.board(rest).format().apply(v);
        }
        int sep = rest.lastIndexOf('_');
        if (sep < 0) return null;
        String board = rest.substring(0, sep);
        Board b = Leaderboards.board(board);
        if (b == null) return null;
        int n;
        try {
            n = Integer.parseInt(rest.substring(sep + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        List<Entry> top = top(board);
        if (n < 1 || n > top.size()) return field.equals("name") ? "-" : "";
        Entry e = top.get(n - 1);
        return switch (field) {
            case "name" -> e.name();
            case "value" -> b.format().apply(e.value());
            case "uuid" -> e.player().toString();
            default -> null;
        };
    }
}
