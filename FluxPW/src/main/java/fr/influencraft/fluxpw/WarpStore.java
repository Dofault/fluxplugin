package fr.influencraft.fluxpw;

import fr.influencraft.flux.api.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Warps in Flux's database (SQLite or MySQL/MariaDB, chosen in Flux): {@code flux_pw_warps} (one row per warp, keyed
 * by the warp's UUID) and {@code flux_pw_links} (a player UUID tied to a warp:
 * {@code review}, {@code visit}, {@code block}, {@code favorite}). Everything is loaded at start (a few hundred warps
 * at most) and every change is written through right away.
 */
final class WarpStore {

    static final String REVIEW = "review", VISIT = "visit", BLOCK = "block", FAVORITE = "favorite";

    private static final List<String> COLUMNS = List.of("id", "name", "display_name", "owner", "owner_name", "world",
            "x", "y", "z", "yaw", "pitch", "icon", "description", "category", "status", "password", "admission",
            "visits", "rating", "featured_until", "created");

    private final Logger log;
    private String warps, links;

    WarpStore(Logger log) {
        this.log = log;
    }

    void open() {
        warps = Database.table("flux_pw_warps");
        links = Database.table("flux_pw_links");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            // names of the first version (2026-10-30)
            Database.renameTable(c, Database.table("fluxpw_warps"), warps);
            Database.renameTable(c, Database.table("fluxpw_links"), links);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + warps + " (id " + d.key(36) + " PRIMARY KEY, name " + d.key(64)
                    + " NOT NULL, display_name TEXT, owner " + d.key(36) + " NOT NULL, owner_name TEXT, world TEXT, x DOUBLE, y DOUBLE,"
                    + " z DOUBLE, yaw DOUBLE, pitch DOUBLE, icon TEXT, description TEXT, category TEXT, status TEXT, password TEXT,"
                    + " admission INT NOT NULL DEFAULT 0, visits INT NOT NULL DEFAULT 0, rating INT NOT NULL DEFAULT 0,"
                    + " featured_until BIGINT NOT NULL DEFAULT 0, created BIGINT NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + links + " (warp " + d.key(36) + " NOT NULL, player " + d.key(36)
                    + " NOT NULL, kind " + d.key(16) + " NOT NULL, PRIMARY KEY (warp, player, kind))");
        } catch (SQLException e) {
            log.severe("Could not create the warp tables: " + e.getMessage());
        }
    }

    /** Every warp, with its reviewers, visitors and blocked players. */
    Map<UUID, Warp> load() {
        Map<UUID, Warp> out = new LinkedHashMap<>();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + warps)) {
                while (rs.next()) {
                    Warp w = new Warp(UUID.fromString(rs.getString("id")));
                    w.name = rs.getString("name");
                    w.displayName = rs.getString("display_name");
                    w.owner = UUID.fromString(rs.getString("owner"));
                    w.ownerName = rs.getString("owner_name");
                    w.world = rs.getString("world");
                    w.x = rs.getDouble("x");
                    w.y = rs.getDouble("y");
                    w.z = rs.getDouble("z");
                    w.yaw = rs.getFloat("yaw");
                    w.pitch = rs.getFloat("pitch");
                    w.icon = rs.getString("icon");
                    w.description = rs.getString("description");
                    w.category = rs.getString("category");
                    try {
                        w.status = Warp.Status.valueOf(rs.getString("status"));
                    } catch (RuntimeException e) {
                        w.status = Warp.Status.OPENED;
                    }
                    w.password = rs.getString("password");
                    w.admission = rs.getInt("admission");
                    w.visits = rs.getInt("visits");
                    w.ratingSum = rs.getInt("rating");
                    w.featuredUntil = rs.getLong("featured_until");
                    w.created = rs.getLong("created");
                    out.put(w.id, w);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT warp, player, kind FROM " + links + " WHERE kind <> '" + FAVORITE + "'")) {
                while (rs.next()) {
                    Warp w = out.get(UUID.fromString(rs.getString(1)));
                    if (w == null) continue;
                    UUID player = UUID.fromString(rs.getString(2));
                    switch (rs.getString(3)) {
                        case REVIEW -> w.reviewers.add(player);
                        case VISIT -> w.visitors.add(player);
                        case BLOCK -> w.blocked.add(player);
                        default -> {
                        }
                    }
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.severe("Could not load the warps: " + e.getMessage());
        }
        return out;
    }

    /** Favourite warps of every player, in the order they were added. */
    Map<UUID, Set<UUID>> favorites() {
        Map<UUID, Set<UUID>> out = new LinkedHashMap<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT warp, player FROM " + links + " WHERE kind = ?")) {
            ps.setString(1, FAVORITE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(UUID.fromString(rs.getString(2)), k -> new LinkedHashSet<>()).add(UUID.fromString(rs.getString(1)));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.severe("Could not load the favourite warps: " + e.getMessage());
        }
        return out;
    }

    void save(Warp w) {
        String sql = Database.dialect().upsert(warps, COLUMNS, List.of("id"), COLUMNS.subList(1, COLUMNS.size()));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, w.id.toString());
            ps.setString(i++, w.name);
            ps.setString(i++, w.displayName);
            ps.setString(i++, w.owner.toString());
            ps.setString(i++, w.ownerName);
            ps.setString(i++, w.world);
            ps.setDouble(i++, w.x);
            ps.setDouble(i++, w.y);
            ps.setDouble(i++, w.z);
            ps.setDouble(i++, w.yaw);
            ps.setDouble(i++, w.pitch);
            ps.setString(i++, w.icon);
            ps.setString(i++, w.description);
            ps.setString(i++, w.category);
            ps.setString(i++, w.status.name());
            ps.setString(i++, w.password);
            ps.setInt(i++, w.admission);
            ps.setInt(i++, w.visits);
            ps.setInt(i++, w.ratingSum);
            ps.setLong(i++, w.featuredUntil);
            ps.setLong(i, w.created);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save warp " + w.name + ": " + e.getMessage());
        }
    }

    /** Deletes a warp and everything tied to it (ratings, visits, blocks, favourites). */
    void delete(Warp w) {
        try (Connection c = Database.connection()) {
            for (String table : List.of(links, warps)) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE " + (table.equals(links) ? "warp" : "id") + " = ?")) {
                    ps.setString(1, w.id.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warning("Could not delete warp " + w.name + ": " + e.getMessage());
        }
    }

    /** Ties ({@code on}) or unties a player to a warp. */
    void link(UUID warp, UUID player, String kind, boolean on) {
        String sql = on
                ? Database.dialect().insertIgnore() + " " + links + " (warp, player, kind) VALUES (?, ?, ?)"
                : "DELETE FROM " + links + " WHERE warp = ? AND player = ? AND kind = ?";
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, warp.toString());
            ps.setString(2, player.toString());
            ps.setString(3, kind);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save a warp " + kind + ": " + e.getMessage());
        }
    }

    /** Writes several links at once (imports). */
    void links(UUID warp, List<UUID> players, String kind) {
        List<UUID> list = new ArrayList<>(players);
        if (list.isEmpty()) return;
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().insertIgnore() + " " + links + " (warp, player, kind) VALUES (?, ?, ?)")) {
            for (UUID p : list) {
                ps.setString(1, warp.toString());
                ps.setString(2, p.toString());
                ps.setString(3, kind);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warning("Could not save the warp " + kind + "s: " + e.getMessage());
        }
    }
}
