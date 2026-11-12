package fr.influencraft.fluxtournament;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Tournaments in Flux's database. The last tournaments and every player's totals are kept in memory (menu,
 * leaderboard); writes go to the database off the main thread.
 * <ul>
 *   <li>{@code flux_tournament_runs}: one row per tournament played (id = start time)</li>
 *   <li>{@code flux_tournament_results}: score and rank of each player in a tournament</li>
 *   <li>{@code flux_tournament_players}: totals of a player (tournament points, wins, podiums, tournaments played)</li>
 * </ul>
 */
final class TournamentStore {

    record Result(UUID uuid, String name, int rank, double score) {
    }

    /** A finished tournament. {@code results} best first. */
    record PastRun(long id, String kind, String job, String target, String name, String icon, int amount, long ended,
                   boolean rewarded, List<Result> results) {

        Result of(UUID uuid) {
            for (Result r : results) if (r.uuid().equals(uuid)) return r;
            return null;
        }
    }

    /** Totals of a player. */
    static final class Stats {
        final UUID uuid;
        String name;
        int points, wins, podiums, played;

        Stats(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private final Plugin plugin;
    private String runs, results, players;
    private final List<PastRun> history = new CopyOnWriteArrayList<>();
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();
    private int historySize = 50;

    TournamentStore(Plugin plugin) {
        this.plugin = plugin;
    }

    void historySize(int size) {
        historySize = Math.max(1, size);
        while (history.size() > historySize) history.remove(history.size() - 1);
    }

    void open() {
        runs = Database.table("flux_tournament_runs");
        results = Database.table("flux_tournament_results");
        players = Database.table("flux_tournament_players");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + runs + " (id BIGINT PRIMARY KEY, kind " + d.key(16)
                    + " NOT NULL, job " + d.key(64) + " NOT NULL, target " + d.key(128) + ", name " + d.key(255)
                    + " NOT NULL, icon " + d.key(128) + ", amount INT NOT NULL, ended BIGINT NOT NULL, rewarded INT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + results + " (run BIGINT NOT NULL, uuid " + d.key(36)
                    + " NOT NULL, name " + d.key(32) + " NOT NULL, place INT NOT NULL, score DOUBLE NOT NULL, PRIMARY KEY (run, uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " (uuid " + d.key(36) + " PRIMARY KEY, name "
                    + d.key(32) + " NOT NULL, points INT NOT NULL, wins INT NOT NULL, podiums INT NOT NULL, played INT NOT NULL)");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create the tournament tables", e);
            return;
        }
        load();
    }

    private void load() {
        history.clear();
        stats.clear();
        try (Connection c = Database.connection()) {
            Map<Long, List<Result>> byRun = new HashMap<>();
            List<Object[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, job, target, name, icon, amount, ended, rewarded FROM "
                    + runs + " ORDER BY id DESC LIMIT " + historySize); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getInt(7), rs.getLong(8), rs.getInt(9) != 0});
                    byRun.put(rs.getLong(1), new ArrayList<>());
                }
            }
            if (!rows.isEmpty()) {
                long oldest = (long) rows.get(rows.size() - 1)[0];
                try (PreparedStatement ps = c.prepareStatement("SELECT run, uuid, name, place, score FROM " + results
                        + " WHERE run >= ? ORDER BY run, place")) {
                    ps.setLong(1, oldest);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            List<Result> list = byRun.get(rs.getLong(1));
                            if (list != null) list.add(new Result(UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getDouble(5)));
                        }
                    }
                }
            }
            for (Object[] r : rows) {
                history.add(new PastRun((long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                        (int) r[6], (long) r[7], (boolean) r[8], List.copyOf(byRun.get((long) r[0]))));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, points, wins, podiums, played FROM " + players);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stats s = new Stats(UUID.fromString(rs.getString(1)), rs.getString(2));
                    s.points = rs.getInt(3);
                    s.wins = rs.getInt(4);
                    s.podiums = rs.getInt(5);
                    s.played = rs.getInt(6);
                    stats.put(s.uuid, s);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read the tournaments", e);
        }
    }

    /** Last tournaments, newest first. */
    List<PastRun> history() {
        return Collections.unmodifiableList(history);
    }

    Stats stats(UUID uuid) {
        return stats.get(uuid);
    }

    Map<UUID, Stats> allStats() {
        return stats;
    }

    /**
     * Records a finished tournament: the run, the results, the players' totals ({@code points} = tournament points
     * earned by each player). Memory at once, database off the main thread.
     */
    void record(PastRun run, Map<UUID, Integer> points, int podium) {
        history.add(0, run);
        while (history.size() > historySize) history.remove(history.size() - 1);
        List<Stats> changed = new ArrayList<>();
        for (Result r : run.results()) {
            Stats s = stats.computeIfAbsent(r.uuid(), u -> new Stats(u, r.name()));
            s.name = r.name();
            s.played++;
            if (run.rewarded()) {
                s.points += points.getOrDefault(r.uuid(), 0);
                if (r.rank() == 1) s.wins++;
                if (r.rank() <= podium) s.podiums++;
            }
            Stats copy = new Stats(s.uuid, s.name);
            copy.points = s.points;
            copy.wins = s.wins;
            copy.podiums = s.podiums;
            copy.played = s.played;
            changed.add(copy);
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(run, changed));
    }

    private void write(PastRun run, List<Stats> changed) {
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection()) {
            try (PreparedStatement ps = c.prepareStatement(d.insertIgnore() + " " + runs
                    + " (id, kind, job, target, name, icon, amount, ended, rewarded) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, run.id());
                ps.setString(2, run.kind());
                ps.setString(3, run.job());
                ps.setString(4, run.target());
                ps.setString(5, run.name());
                ps.setString(6, run.icon());
                ps.setInt(7, run.amount());
                ps.setLong(8, run.ended());
                ps.setInt(9, run.rewarded() ? 1 : 0);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(d.insertIgnore() + " " + results
                    + " (run, uuid, name, place, score) VALUES (?, ?, ?, ?, ?)")) {
                for (Result r : run.results()) {
                    ps.setLong(1, run.id());
                    ps.setString(2, r.uuid().toString());
                    ps.setString(3, r.name());
                    ps.setInt(4, r.rank());
                    ps.setDouble(5, r.score());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(d.upsert(players, List.of("uuid", "name", "points", "wins", "podiums", "played"),
                    List.of("uuid"), List.of("name", "points", "wins", "podiums", "played")))) {
                for (Stats s : changed) {
                    ps.setString(1, s.uuid.toString());
                    ps.setString(2, s.name);
                    ps.setInt(3, s.points);
                    ps.setInt(4, s.wins);
                    ps.setInt(5, s.podiums);
                    ps.setInt(6, s.played);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not record tournament " + run.id(), e);
        }
    }
}
