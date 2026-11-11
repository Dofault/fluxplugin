package fr.influencraft.fluxpass;

import fr.influencraft.flux.api.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Data of the pass in Flux's database. Online players are cached ({@link PlayerPass}); tier, points and claims are
 * written at once, mission progress every few seconds and when the player leaves.
 * <ul>
 *   <li>{@code flux_pass_players}: tier and points of the season</li>
 *   <li>{@code flux_pass_claims}: rewards claimed (season, tier, track)</li>
 *   <li>{@code flux_pass_progress}: progress of each mission, valid for one period and one variant</li>
 *   <li>{@code flux_pass_draws}: variant drawn for each mission in the current period, the same for everybody</li>
 * </ul>
 */
final class PassStore {

    /** Progress of one mission of a player. */
    static final class Progress {
        long period;
        String variant;
        double value;
        boolean done;
        boolean dirty;

        Progress(long period, String variant, double value, boolean done) {
            this.period = period;
            this.variant = variant;
            this.value = value;
            this.done = done;
        }
    }

    /** What the pass knows of an online player. */
    static final class PlayerPass {
        final UUID uuid;
        int tier;
        int points;
        final Set<String> claims = ConcurrentHashMap.newKeySet();
        final Map<String, Progress> progress = new ConcurrentHashMap<>();

        PlayerPass(UUID uuid) {
            this.uuid = uuid;
        }

        static String claimKey(int tier, Season.Track track) {
            return tier + ":" + track.key();
        }
    }

    record Draw(long period, String variant) {
    }

    private final Logger log;
    private String players, claims, progress, draws;
    private final Map<UUID, PlayerPass> cache = new ConcurrentHashMap<>();

    PassStore(Logger log) {
        this.log = log;
    }

    void open() {
        players = Database.table("flux_pass_players");
        claims = Database.table("flux_pass_claims");
        progress = Database.table("flux_pass_progress");
        draws = Database.table("flux_pass_draws");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " (uuid " + d.key(36) + " PRIMARY KEY, season "
                    + d.key(32) + " NOT NULL, tier INT NOT NULL, points INT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + claims + " (uuid " + d.key(36) + " NOT NULL, season " + d.key(32)
                    + " NOT NULL, tier INT NOT NULL, track " + d.key(16) + " NOT NULL, PRIMARY KEY (uuid, season, tier, track))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + progress + " (uuid " + d.key(36) + " NOT NULL, category " + d.key(64)
                    + " NOT NULL, mission " + d.key(64) + " NOT NULL, period BIGINT NOT NULL, variant " + d.key(64)
                    + " NOT NULL, progress DOUBLE NOT NULL, done INT NOT NULL, PRIMARY KEY (uuid, category, mission))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + draws + " (category " + d.key(64) + " NOT NULL, mission " + d.key(64)
                    + " NOT NULL, period BIGINT NOT NULL, variant " + d.key(64) + " NOT NULL, PRIMARY KEY (category, mission))");
        } catch (SQLException e) {
            log.severe("Could not create the pass tables: " + e.getMessage());
        }
    }

    PlayerPass cached(UUID uuid) {
        return cache.get(uuid);
    }

    Iterable<PlayerPass> online() {
        return cache.values();
    }

    /** Loads a player for the season (a row of another season starts him again at tier 0). */
    PlayerPass load(UUID uuid, String season) {
        PlayerPass p = new PlayerPass(uuid);
        try (Connection c = Database.connection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT season, tier, points FROM " + players + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && season.equals(rs.getString(1))) {
                        p.tier = rs.getInt(2);
                        p.points = rs.getInt(3);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT tier, track FROM " + claims + " WHERE uuid = ? AND season = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, season);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) p.claims.add(rs.getInt(1) + ":" + rs.getString(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT category, mission, period, variant, progress, done FROM "
                    + progress + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        p.progress.put(rs.getString(1) + "/" + rs.getString(2),
                                new Progress(rs.getLong(3), rs.getString(4), rs.getDouble(5), rs.getInt(6) != 0));
                    }
                }
            }
        } catch (SQLException e) {
            log.warning("Could not load the pass of " + uuid + ": " + e.getMessage());
        }
        cache.put(uuid, p);
        return p;
    }

    /** Saves and forgets a player who left. */
    void unload(UUID uuid) {
        PlayerPass p = cache.remove(uuid);
        if (p != null) saveProgress(p);
    }

    void saveTier(PlayerPass p, String season) {
        String sql = Database.dialect().upsert(players, List.of("uuid", "season", "tier", "points"), List.of("uuid"),
                List.of("season", "tier", "points"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, p.uuid.toString());
            ps.setString(2, season);
            ps.setInt(3, p.tier);
            ps.setInt(4, p.points);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the tier of " + p.uuid + ": " + e.getMessage());
        }
    }

    /** Records a claim; false when it was already there (claimed twice at the same time). */
    boolean claim(PlayerPass p, String season, int tier, Season.Track track) {
        if (!p.claims.add(PlayerPass.claimKey(tier, track))) return false;
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().insertIgnore() + " " + claims
                     + " (uuid, season, tier, track) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, p.uuid.toString());
            ps.setString(2, season);
            ps.setInt(3, tier);
            ps.setString(4, track.key());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warning("Could not save a claim of " + p.uuid + ": " + e.getMessage());
            p.claims.remove(PlayerPass.claimKey(tier, track));
            return false;
        }
    }

    /** Forgets every claim of a player in the season (admin reset). */
    void clearClaims(PlayerPass p, String season) {
        p.claims.clear();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + claims + " WHERE uuid = ? AND season = ?")) {
            ps.setString(1, p.uuid.toString());
            ps.setString(2, season);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not clear the claims of " + p.uuid + ": " + e.getMessage());
        }
    }

    void saveProgress(PlayerPass p) {
        String sql = Database.dialect().upsert(progress, List.of("uuid", "category", "mission", "period", "variant", "progress", "done"),
                List.of("uuid", "category", "mission"), List.of("period", "variant", "progress", "done"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int n = 0;
            for (Map.Entry<String, Progress> e : p.progress.entrySet()) {
                Progress pr = e.getValue();
                if (!pr.dirty) continue;
                pr.dirty = false;
                String[] key = e.getKey().split("/", 2);
                ps.setString(1, p.uuid.toString());
                ps.setString(2, key[0]);
                ps.setString(3, key[1]);
                ps.setLong(4, pr.period);
                ps.setString(5, pr.variant);
                ps.setDouble(6, pr.value);
                ps.setInt(7, pr.done ? 1 : 0);
                ps.addBatch();
                n++;
            }
            if (n > 0) ps.executeBatch();
        } catch (SQLException e) {
            log.warning("Could not save the missions of " + p.uuid + ": " + e.getMessage());
        }
    }

    void saveOnline() {
        for (PlayerPass p : cache.values()) saveProgress(p);
    }

    Map<String, Draw> draws() {
        Map<String, Draw> out = new HashMap<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT category, mission, period, variant FROM " + draws);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getString(1) + "/" + rs.getString(2), new Draw(rs.getLong(3), rs.getString(4)));
        } catch (SQLException e) {
            log.warning("Could not read the drawn missions: " + e.getMessage());
        }
        return out;
    }

    void saveDraw(String category, String mission, Draw draw) {
        String sql = Database.dialect().upsert(draws, List.of("category", "mission", "period", "variant"),
                List.of("category", "mission"), List.of("period", "variant"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, mission);
            ps.setLong(3, draw.period());
            ps.setString(4, draw.variant());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save a drawn mission: " + e.getMessage());
        }
    }
}
