package fr.influencraft.fluxportal;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Portals in Flux's database ({@code flux_portal_portals}): one row per portal, each end in its own columns. */
final class PortalStore {

    private final Logger log;
    private final String table = Database.table("portal_portals");

    PortalStore(Logger log) throws SQLException {
        this.log = log;
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + table + " (name " + d.key(64) + " PRIMARY KEY, size DOUBLE PRECISION NOT NULL");
            for (String e : List.of("entry", "exit")) {
                sql.append(", ").append(e).append("_world ").append(d.key(64)).append(", ").append(e).append("_x DOUBLE PRECISION, ")
                        .append(e).append("_y DOUBLE PRECISION, ").append(e).append("_z DOUBLE PRECISION, ").append(e).append("_yaw DOUBLE PRECISION");
            }
            st.executeUpdate(sql.append(")").toString());
        }
    }

    List<Portal> load() throws SQLException {
        List<Portal> out = new ArrayList<>();
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Portal(rs.getString("name"), end(rs, "entry"), end(rs, "exit"), rs.getDouble("size")));
        }
        return out;
    }

    private static PortalEnd end(ResultSet rs, String p) throws SQLException {
        String w = rs.getString(p + "_world");
        if (w == null) return null;
        return new PortalEnd(w, rs.getDouble(p + "_x"), rs.getDouble(p + "_y"), rs.getDouble(p + "_z"), (float) rs.getDouble(p + "_yaw"));
    }

    boolean save(Portal portal) {
        List<String> cols = new ArrayList<>(List.of("name", "size"));
        for (String e : List.of("entry", "exit")) for (String f : List.of("world", "x", "y", "z", "yaw")) cols.add(e + "_" + f);
        List<String> updated = new ArrayList<>(cols.subList(1, cols.size()));
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(table, cols, List.of("name"), updated))) {
            int i = 1;
            ps.setString(i++, portal.name());
            ps.setDouble(i++, portal.size());
            for (PortalEnd e : new PortalEnd[]{portal.entry(), portal.exit()}) {
                ps.setString(i++, e == null ? null : e.world());
                ps.setObject(i++, e == null ? null : e.x());
                ps.setObject(i++, e == null ? null : e.y());
                ps.setObject(i++, e == null ? null : e.z());
                ps.setObject(i++, e == null ? null : (double) e.yaw());
            }
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.severe("Portal " + portal.name() + " not saved: " + e.getMessage());
            return false;
        }
    }

    boolean delete(String name) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.severe("Portal " + name + " not removed: " + e.getMessage());
            return false;
        }
    }
}
