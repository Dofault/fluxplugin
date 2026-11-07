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
 *   <li>{@code flux_dungeons_loadouts}: the dungeon inventory of each player, kept between runs, never seen outside.</li>
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
    private String players, heroes, loadouts, vault;

    public Store(Logger log) {
        this.log = log;
    }

    public void open() {
        players = Database.table("flux_dungeons_players");
        heroes = Database.table("flux_dungeons_heroes");
        loadouts = Database.table("flux_dungeons_loadouts");
        vault = Database.table("flux_dungeons_vault");
        String key = Database.dialect().key(36);
        String text = Database.dialect().longText();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " (uuid " + key + " PRIMARY KEY, hero_class VARCHAR(32) NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + heroes + " (uuid " + key + " NOT NULL, hero_class " + Database.dialect().key(32)
                    + " NOT NULL, level INT NOT NULL, xp DOUBLE NOT NULL, kit INT NOT NULL, PRIMARY KEY (uuid, hero_class))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + loadouts + " (uuid " + key + " PRIMARY KEY, items " + text + " NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + vault + " (uuid " + key + " PRIMARY KEY, data " + text + " NOT NULL)");
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

    /** Fresh start (staff): dungeon inventory deleted, every class kit given again at the next run. Levels are kept. */
    public void reset(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement a = c.prepareStatement("DELETE FROM " + loadouts + " WHERE uuid = ?");
             PreparedStatement b = c.prepareStatement("UPDATE " + heroes + " SET kit = 0 WHERE uuid = ?")) {
            a.setString(1, uuid.toString());
            a.executeUpdate();
            b.setString(1, uuid.toString());
            b.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not reset the dungeon inventory of " + uuid + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- dungeon inventory

    public ItemStack[] loadout(UUID uuid) {
        String data = readText(loadouts, "items", uuid);
        return data == null ? null : decode(data);
    }

    public void loadout(UUID uuid, ItemStack[] items) {
        writeText(loadouts, "items", uuid, encode(items));
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
