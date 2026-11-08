package fr.influencraft.fluxdungeons;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * FluxDungeons' tables in Flux's database:
 * <ul>
 *   <li>{@code flux_dungeons_players}: the class each player plays.</li>
 *   <li>{@code flux_dungeons_heroes}: level and experience of a player in each class he tried, and whether its
 *   starting kit was handed out.</li>
 *   <li>{@code flux_dungeons_lost}: gear lost by players who died in a dungeon. Locked until someone wins that
 *   dungeon, then handed back through Flux's rewards ({@code fluxdungeons claimlost}), deleted once claimed.</li>
 *   <li>{@code flux_dungeons_vault}: what a player had on him before entering a dungeon (inventory, experience,
 *   health, place...). Written before he enters, deleted once he got it back: a player who disconnects, or a server
 *   that crashes, during a run gets his things back on his next join.</li>
 * </ul>
 */
public final class Store {

    /** Level, experience and kit flag of a player in one class. */
    public record HeroRow(int level, double xp, boolean kitGiven) {
    }

    private final Logger log;
    /** Gear lost in a dungeon. */
    public record Lost(String id, UUID owner, String dungeon, int stacks) {
    }

    private String players, heroes, vault, lost;

    public Store(Logger log) {
        this.log = log;
    }

    public void open() {
        players = Database.table("flux_dungeons_players");
        heroes = Database.table("flux_dungeons_heroes");
        vault = Database.table("flux_dungeons_vault");
        lost = Database.table("flux_dungeons_lost");
        String key = Database.dialect().key(36);
        String text = Database.dialect().longText();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " (uuid " + key + " PRIMARY KEY, hero_class VARCHAR(32) NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + heroes + " (uuid " + key + " NOT NULL, hero_class " + Database.dialect().key(32)
                    + " NOT NULL, level INT NOT NULL, xp DOUBLE NOT NULL, kit INT NOT NULL, PRIMARY KEY (uuid, hero_class))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + vault + " (uuid " + key + " PRIMARY KEY, data " + text + " NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + lost + " (id " + key + " PRIMARY KEY, uuid " + Database.dialect().key(36)
                    + " NOT NULL, dungeon VARCHAR(64) NOT NULL, items " + text + " NOT NULL, stacks INT NOT NULL, created BIGINT NOT NULL,"
                    + " released INT NOT NULL)");
        } catch (SQLException e) {
            log.severe("Could not create the FluxDungeons tables: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- class

    public String heroClass(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT hero_class FROM " + players + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warning("Could not read the class of " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    public void heroClass(UUID uuid, String heroClass) {
        String sql = Database.dialect().upsert(players, List.of("uuid", "hero_class"), List.of("uuid"), List.of("hero_class"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, heroClass);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the class of " + uuid + ": " + e.getMessage());
        }
    }

    /** Every class this player tried, by id. */
    public Map<String, HeroRow> heroes(UUID uuid) {
        Map<String, HeroRow> out = new HashMap<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT hero_class, level, xp, kit FROM " + heroes + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), new HeroRow(rs.getInt(2), rs.getDouble(3), rs.getInt(4) != 0));
            }
        } catch (SQLException e) {
            log.warning("Could not read the classes of " + uuid + ": " + e.getMessage());
        }
        return out;
    }

    public void hero(UUID uuid, String heroClass, HeroRow row) {
        String sql = Database.dialect().upsert(heroes, List.of("uuid", "hero_class", "level", "xp", "kit"),
                List.of("uuid", "hero_class"), List.of("level", "xp", "kit"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, heroClass);
            ps.setInt(3, row.level());
            ps.setDouble(4, row.xp());
            ps.setInt(5, row.kitGiven() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the class progress of " + uuid + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- vault (state before entering)

    public String vault(UUID uuid) {
        return readText(vault, "data", uuid);
    }

    public boolean saveVault(UUID uuid, String data) {
        return writeText(vault, "data", uuid, data);
    }

    public void deleteVault(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + vault + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not delete the saved state of " + uuid + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- gear lost in dungeons

    /** Locks gear lost in a dungeon; returns its id, null when it could not be written (logged). */
    public String addLost(UUID owner, String dungeon, ItemStack[] items, boolean released) {
        int stacks = 0;
        for (ItemStack i : items) if (i != null && !i.isEmpty()) stacks++;
        if (stacks == 0) return null;
        String id = UUID.randomUUID().toString();
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("INSERT INTO " + lost
                + " (id, uuid, dungeon, items, stacks, created, released) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, owner.toString());
            ps.setString(3, dungeon);
            ps.setString(4, encode(items));
            ps.setInt(5, stacks);
            ps.setLong(6, System.currentTimeMillis());
            ps.setInt(7, released ? 1 : 0);
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            log.severe("Could not keep the gear lost by " + owner + " in " + dungeon + ": " + e.getMessage());
            return null;
        }
    }

    /** Releases every locked gear of a dungeon (someone won it) and returns what was released. */
    public List<Lost> release(String dungeon) {
        List<Lost> out = new java.util.ArrayList<>();
        try (Connection c = Database.connection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, uuid, stacks FROM " + lost + " WHERE dungeon = ? AND released = 0")) {
                ps.setString(1, dungeon);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Lost(rs.getString(1), UUID.fromString(rs.getString(2)), dungeon, rs.getInt(3)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + lost + " SET released = 1 WHERE id = ?")) {
                for (Lost l : out) {
                    ps.setString(1, l.id());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warning("Could not release the gear lost in " + dungeon + ": " + e.getMessage());
        }
        return out;
    }

    /** Number of locked gears waiting in a dungeon. */
    public int lockedCount(String dungeon) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + lost + " WHERE dungeon = ? AND released = 0")) {
            ps.setString(1, dungeon);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Takes a released gear out (claimed): its items, null when it does not exist, is still locked or is not his. */
    public ItemStack[] takeLost(String id, UUID owner) {
        try (Connection c = Database.connection()) {
            String data;
            try (PreparedStatement ps = c.prepareStatement("SELECT items FROM " + lost + " WHERE id = ? AND uuid = ? AND released = 1")) {
                ps.setString(1, id);
                ps.setString(2, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    data = rs.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + lost + " WHERE id = ?")) {
                ps.setString(1, id);
                if (ps.executeUpdate() == 0) return null;
            }
            return decode(data);
        } catch (SQLException e) {
            log.warning("Could not take the lost gear " + id + ": " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    public static String encode(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? ItemStack.empty() : items[i];
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(copy));
    }

    public static ItemStack[] decode(String data) {
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
        for (int i = 0; i < items.length; i++) if (items[i] != null && items[i].isEmpty()) items[i] = null;
        return items;
    }

    private String readText(String table, String column, UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warning("Could not read " + table + " of " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private boolean writeText(String table, String column, UUID uuid, String value) {
        String sql = Database.dialect().upsert(table, List.of("uuid", column), List.of("uuid"), List.of(column));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.severe("Could not write " + table + " of " + uuid + ": " + e.getMessage());
            return false;
        }
    }
}
