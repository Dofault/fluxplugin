package fr.influencraft.fluxfamily;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Couples, adoptions and divorces, in Flux's database ({@code flux_family_couples}, {@code flux_family_adoptions},
 * {@code flux_family_divorces}; SQLite or MySQL/MariaDB, chosen in Flux). Keys are player UUIDs. Everything is loaded at
 * start and kept in memory (a few rows per player at most); every change is written through right away.
 */
final class FamilyStore {

    /** The couple's home: a world name (the world may not be loaded) and a position. */
    record Home(String world, double x, double y, double z, float yaw, float pitch) {
        static Home of(Location l) {
            return l == null || l.getWorld() == null ? null
                    : new Home(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    /** Two players together; {@code a} is the lower id (row key). */
    record Couple(UUID a, UUID b, boolean married, long since, long marriedAt, Home home) {
        static Couple of(UUID x, UUID y, boolean married, long since, long marriedAt, Home home) {
            return x.toString().compareTo(y.toString()) <= 0
                    ? new Couple(x, y, married, since, marriedAt, home) : new Couple(y, x, married, since, marriedAt, home);
        }

        UUID other(UUID player) {
            return player.equals(a) ? b : a;
        }

        Couple marry(long at) {
            return new Couple(a, b, true, since, at, home);
        }

        Couple home(Home h) {
            return new Couple(a, b, married, since, marriedAt, h);
        }
    }

    record Adoption(UUID parent, UUID child, long since) {
    }

    private final Logger log;
    private final Map<UUID, Couple> couples = new ConcurrentHashMap<>();
    private final Map<UUID, Adoption> adoptions = new ConcurrentHashMap<>();
    private final Set<UUID> divorced = ConcurrentHashMap.newKeySet();
    private String couplesTable, adoptionsTable, divorcesTable;

    FamilyStore(Logger log) {
        this.log = log;
    }

    void open() {
        couplesTable = Database.table("flux_family_couples");
        adoptionsTable = Database.table("flux_family_adoptions");
        divorcesTable = Database.table("flux_family_divorces");
        String key = Database.dialect().key(36);
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            // names of the first version (2026-10-30)
            Database.renameTable(c, Database.table("fluxfamily_couples"), couplesTable);
            Database.renameTable(c, Database.table("fluxfamily_adoptions"), adoptionsTable);
            String formerDivorces = Database.table("fluxfamily_divorces");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + couplesTable + " (a " + key + " PRIMARY KEY, b " + key
                    + " NOT NULL, married INT NOT NULL, since BIGINT NOT NULL, married_at BIGINT NOT NULL,"
                    + " home_world VARCHAR(64), home_x DOUBLE, home_y DOUBLE, home_z DOUBLE, home_yaw DOUBLE, home_pitch DOUBLE)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + adoptionsTable + " (child " + key + " PRIMARY KEY, parent "
                    + key + " NOT NULL, since BIGINT NOT NULL)");
            // one row per divorce, keyed by the player who left (a player may divorce several times)
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + divorcesTable + " (uuid " + key + " NOT NULL, partner " + key
                    + " NOT NULL, at BIGINT NOT NULL, PRIMARY KEY (uuid, at))");
            if (Database.tableExists(c, formerDivorces)) {
                // the first version keyed divorces by a row number: rows copied, former table dropped
                st.executeUpdate(Database.dialect().insertIgnore() + " " + divorcesTable + " (uuid, partner, at) SELECT uuid, partner, at FROM " + formerDivorces);
                st.executeUpdate("DROP TABLE " + formerDivorces);
            }
        } catch (SQLException e) {
            log.severe("Could not create the FluxFamily tables: " + e.getMessage());
        }
        load();
    }

    private void load() {
        couples.clear();
        adoptions.clear();
        divorced.clear();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT a, b, married, since, married_at, home_world, home_x, home_y,"
                    + " home_z, home_yaw, home_pitch FROM " + couplesTable)) {
                while (rs.next()) {
                    String world = rs.getString(6);
                    Home home = world == null ? null : new Home(world, rs.getDouble(7), rs.getDouble(8), rs.getDouble(9),
                            (float) rs.getDouble(10), (float) rs.getDouble(11));
                    Couple couple = new Couple(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            rs.getInt(3) != 0, rs.getLong(4), rs.getLong(5), home);
                    couples.put(couple.a(), couple);
                    couples.put(couple.b(), couple);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT child, parent, since FROM " + adoptionsTable)) {
                while (rs.next()) {
                    UUID child = UUID.fromString(rs.getString(1));
                    adoptions.put(child, new Adoption(UUID.fromString(rs.getString(2)), child, rs.getLong(3)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT uuid FROM " + divorcesTable)) {
                while (rs.next()) divorced.add(UUID.fromString(rs.getString(1)));
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.severe("Could not read the families: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- couples

    Couple couple(UUID player) {
        return couples.get(player);
    }

    void save(Couple couple) {
        couples.put(couple.a(), couple);
        couples.put(couple.b(), couple);
        String sql = Database.dialect().upsert(couplesTable,
                List.of("a", "b", "married", "since", "married_at", "home_world", "home_x", "home_y", "home_z", "home_yaw", "home_pitch"),
                List.of("a"), List.of("b", "married", "since", "married_at", "home_world", "home_x", "home_y", "home_z", "home_yaw", "home_pitch"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, couple.a().toString());
            ps.setString(2, couple.b().toString());
            ps.setInt(3, couple.married() ? 1 : 0);
            ps.setLong(4, couple.since());
            ps.setLong(5, couple.marriedAt());
            Home h = couple.home();
            ps.setString(6, h == null ? null : h.world());
            ps.setDouble(7, h == null ? 0 : h.x());
            ps.setDouble(8, h == null ? 0 : h.y());
            ps.setDouble(9, h == null ? 0 : h.z());
            ps.setDouble(10, h == null ? 0 : h.yaw());
            ps.setDouble(11, h == null ? 0 : h.pitch());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the couple " + couple.a() + " / " + couple.b() + ": " + e.getMessage());
        }
    }

    void delete(Couple couple) {
        couples.remove(couple.a(), couple);
        couples.remove(couple.b(), couple);
        update("DELETE FROM " + couplesTable + " WHERE a = ?", couple.a().toString());
    }

    int coupleCount() {
        return couples.size() / 2;
    }

    // ---------------------------------------------------------------- adoptions

    Adoption adoption(UUID child) {
        return adoptions.get(child);
    }

    List<UUID> adopted(UUID parent) {
        List<UUID> out = new ArrayList<>();
        for (Adoption a : adoptions.values()) if (a.parent().equals(parent)) out.add(a.child());
        return out;
    }

    void save(Adoption adoption) {
        adoptions.put(adoption.child(), adoption);
        String sql = Database.dialect().upsert(adoptionsTable, List.of("child", "parent", "since"), List.of("child"), List.of("parent", "since"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, adoption.child().toString());
            ps.setString(2, adoption.parent().toString());
            ps.setLong(3, adoption.since());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the adoption of " + adoption.child() + ": " + e.getMessage());
        }
    }

    void deleteAdoption(UUID child) {
        adoptions.remove(child);
        update("DELETE FROM " + adoptionsTable + " WHERE child = ?", child.toString());
    }

    // ---------------------------------------------------------------- divorces

    boolean divorced(UUID player) {
        return divorced.contains(player);
    }

    void divorce(UUID leaver, UUID partner, long at) {
        divorced.add(leaver);
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().insertIgnore() + " " + divorcesTable + " (uuid, partner, at) VALUES (?, ?, ?)")) {
            ps.setString(1, leaver.toString());
            ps.setString(2, partner.toString());
            ps.setLong(3, at);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not record the divorce of " + leaver + ": " + e.getMessage());
        }
    }

    private void update(String sql, String arg) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("FluxFamily: " + e.getMessage());
        }
    }
}
