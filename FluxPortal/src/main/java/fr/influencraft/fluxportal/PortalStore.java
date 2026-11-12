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

/**
 * Portals in Flux's database ({@code flux_portal_gates}): one row per portal, each side in its own columns. The
 * round portals of the first version ({@code flux_portal_portals}) are not read: they were tests, placed again by hand.
 */
final class PortalStore {

    private final Logger log;
    private final String table = Database.table("flux_portal_gates");

    PortalStore(Logger log) throws SQLException {
        this.log = log;
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + table + " (name " + d.key(64)
                    + " PRIMARY KEY, width INTEGER NOT NULL, height INTEGER NOT NULL, is_open INTEGER NOT NULL");
            for (String s : List.of("s1", "s2")) {
                sql.append(", ").append(s).append("_world ").append(d.key(64)).append(", ").append(s).append("_x DOUBLE PRECISION, ")
                        .append(s).append("_y DOUBLE PRECISION, ").append(s).append("_z DOUBLE PRECISION, ").append(s).append("_yaw DOUBLE PRECISION");
            }
            st.executeUpdate(sql.append(")").toString());
        }
    }

    List<Portal> load() throws SQLException {
        List<Portal> out = new ArrayList<>();
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Portal(rs.getString("name"), side(rs, "s1"), side(rs, "s2"), rs.getInt("width"), rs.getInt("height"), rs.getInt("is_open") != 0));
            }
        }
        return out;
    }

    private static PortalEnd side(ResultSet rs, String p) throws SQLException {
        String w = rs.getString(p + "_world");
        if (w == null) return null;
        return new PortalEnd(w, rs.getDouble(p + "_x"), rs.getDouble(p + "_y"), rs.getDouble(p + "_z"), (float) rs.getDouble(p + "_yaw"));
    }

    boolean save(Portal portal) {
        List<String> cols = new ArrayList<>(List.of("name", "width", "height", "is_open"));
        for (String s : List.of("s1", "s2")) for (String f : List.of("world", "x", "y", "z", "yaw")) cols.add(s + "_" + f);
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(table, cols, List.of("name"), cols.subList(1, cols.size())))) {
            int i = 1;
            ps.setString(i++, portal.name());
            ps.setInt(i++, portal.width());
            ps.setInt(i++, portal.height());
            ps.setInt(i++, portal.open() ? 1 : 0);
            for (PortalEnd e : new PortalEnd[]{portal.side1(), portal.side2()}) {
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
