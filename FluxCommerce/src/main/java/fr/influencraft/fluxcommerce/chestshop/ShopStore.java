package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Shops in Flux's database (SQLite or MySQL/MariaDB, chosen in Flux): {@code flux_commerce_chestshop_offers} (one row per
 * shop, the item as Paper's item bytes in Base64) and {@code flux_commerce_chestshop_friends} (players allowed to fill a
 * shop), named {@code flux_chestshop_*} before 2026-11-01.
 * Everything is loaded at start; changes are written right away, stock counts in the background.
 *
 * <p>The tables of the former QuickShop fork were called {@code flux_chestshop_shops}, {@code _data}... in its own
 * database: the names used here never meet them.</p>
 */
final class ShopStore {

    private final Logger log;
    private String offers, friends;

    ShopStore(Logger log) {
        this.log = log;
    }

    void open() {
        offers = Database.table("flux_commerce_chestshop_offers");
        friends = Database.table("flux_commerce_chestshop_friends");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            // names before FluxCommerce (2026-11-01)
            Database.renameTable(c, Database.table("flux_chestshop_offers"), offers, "flux_chestshop_offers_place");
            Database.renameTable(c, Database.table("flux_chestshop_friends"), friends);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + offers + " (id " + d.autoId() + ", world " + d.key(64)
                    + " NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, owner " + d.key(36) + ", owner_name TEXT,"
                    + " item TEXT NOT NULL, bundle INT NOT NULL DEFAULT 1, price DOUBLE NOT NULL, buying INT NOT NULL DEFAULT 0,"
                    + " unlimited INT NOT NULL DEFAULT 0, options TEXT, stock INT NOT NULL DEFAULT -1,"
                    + " created BIGINT NOT NULL DEFAULT 0, source " + d.key(64) + ", look " + d.key(16) + ", wood " + d.key(32)
                    + ", sign_face " + d.key(16) + ")");
            // columns added on 2026-11-01 (sign or floating display)
            for (String column : List.of("look", "wood", "sign_face")) {
                if (!hasColumn(c, offers, column)) st.executeUpdate("ALTER TABLE " + offers + " ADD COLUMN " + column + " " + d.key(32));
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + friends + " (shop BIGINT NOT NULL, player " + d.key(36)
                    + " NOT NULL, PRIMARY KEY (shop, player))");
            Database.index(c, "flux_commerce_chestshop_offers_place", offers, "world, x, y, z", true);
        } catch (SQLException e) {
            log.severe("Could not create the shop tables: " + e.getMessage());
        }
    }

    /** Every shop with its friends, by id. A shop whose item cannot be read any more is skipped (and logged). */
    Map<Long, Shop> load() {
        Map<Long, Shop> out = new LinkedHashMap<>();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + offers)) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    ItemStack item = decode(rs.getString("item"));
                    if (item == null) {
                        log.warning("Shop " + id + " skipped: its item cannot be read (kept in the database)");
                        continue;
                    }
                    Shop s = new Shop(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                    s.id = id;
                    String owner = rs.getString("owner");
                    s.owner = owner == null || owner.isBlank() ? null : UUID.fromString(owner);
                    s.ownerName = rs.getString("owner_name");
                    s.item = item;
                    s.bundle = Math.max(1, rs.getInt("bundle"));
                    s.price = rs.getDouble("price");
                    s.buying = rs.getInt("buying") != 0;
                    s.unlimited = rs.getInt("unlimited") != 0;
                    String options = rs.getString("options");
                    if (options != null) for (String o : options.split(",")) if (!o.isBlank()) s.options.add(o.trim());
                    s.stock = rs.getInt("stock");
                    s.created = rs.getLong("created");
                    s.source = rs.getString("source");
                    String look = rs.getString("look");
                    if (look != null) s.look = look;
                    String wood = rs.getString("wood");
                    if (wood != null) s.wood = wood;
                    String face = rs.getString("sign_face");
                    try {
                        s.signFace = face == null ? null : org.bukkit.block.BlockFace.valueOf(face);
                    } catch (IllegalArgumentException e) {
                        s.signFace = null;
                    }
                    out.put(id, s);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT shop, player FROM " + friends)) {
                while (rs.next()) {
                    Shop s = out.get(rs.getLong(1));
                    if (s != null) s.friends.add(UUID.fromString(rs.getString(2)));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.severe("Could not load the shops: " + e.getMessage());
        }
        return out;
    }

    /** Writes a new shop and gives it its id; false when the database refused it. */
    boolean insert(Shop s) {
        String sql = "INSERT INTO " + offers + " (world, x, y, z, owner, owner_name, item, bundle, price, buying, unlimited,"
                + " options, stock, look, wood, sign_face, created, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, s.world);
            ps.setInt(2, s.x);
            ps.setInt(3, s.y);
            ps.setInt(4, s.z);
            fill(ps, 5, s);
            ps.setLong(17, s.created);
            ps.setString(18, s.source);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) return false;
                s.id = keys.getLong(1);
            }
            for (UUID f : s.friends) addFriend(s.id, f);
            return true;
        } catch (SQLException e) {
            log.severe("Could not save a new shop at " + s.key() + ": " + e.getMessage());
            return false;
        }
    }

    /** Writes the settings of a shop (owner, item, price, mode, options, stock). */
    void update(Shop s) {
        String sql = "UPDATE " + offers + " SET owner = ?, owner_name = ?, item = ?, bundle = ?, price = ?, buying = ?,"
                + " unlimited = ?, options = ?, stock = ?, look = ?, wood = ?, sign_face = ? WHERE id = ?";
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            fill(ps, 1, s);
            ps.setLong(13, s.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("Could not save shop " + s.id + ": " + e.getMessage());
        }
    }

    /** Owner, owner name, item, bundle, price, buying, unlimited, options, stock, look, wood, sign face from {@code first}. */
    private static void fill(PreparedStatement ps, int first, Shop s) throws SQLException {
        int i = first;
        ps.setString(i++, s.owner == null ? null : s.owner.toString());
        ps.setString(i++, s.ownerName);
        ps.setString(i++, encode(s.item));
        ps.setInt(i++, s.bundle);
        ps.setDouble(i++, s.price);
        ps.setInt(i++, s.buying ? 1 : 0);
        ps.setInt(i++, s.unlimited ? 1 : 0);
        ps.setString(i++, s.options.isEmpty() ? null : String.join(",", s.options));
        ps.setInt(i++, s.stock);
        ps.setString(i++, s.look);
        ps.setString(i++, s.wood);
        ps.setString(i, s.signFace == null ? null : s.signFace.name());
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, null)) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }

    /** Stock counts of several shops (background thread). */
    void saveStocks(Map<Long, Integer> stocks) {
        if (stocks.isEmpty()) return;
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("UPDATE " + offers + " SET stock = ? WHERE id = ?")) {
            for (Map.Entry<Long, Integer> e : stocks.entrySet()) {
                ps.setInt(1, e.getValue());
                ps.setLong(2, e.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warning("Could not save the stock of " + stocks.size() + " shop(s): " + e.getMessage());
        }
    }

    void delete(long id) {
        try (Connection c = Database.connection()) {
            for (String table : List.of(offers, friends)) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE " + (table.equals(offers) ? "id" : "shop") + " = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.severe("Could not delete shop " + id + ": " + e.getMessage());
        }
    }

    void addFriend(long shop, UUID player) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().insertIgnore() + " " + friends + " (shop, player) VALUES (?, ?)")) {
            ps.setLong(1, shop);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("Could not save a friend of shop " + shop + ": " + e.getMessage());
        }
    }

    void removeFriend(long shop, UUID player) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + friends + " WHERE shop = ? AND player = ?")) {
            ps.setLong(1, shop);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("Could not remove a friend of shop " + shop + ": " + e.getMessage());
        }
    }

    /** Sources of the imported shops ({@code quickshop:<id>}), to import each one once. */
    List<String> sources() {
        List<String> out = new ArrayList<>();
        try (Connection c = Database.connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT source FROM " + offers + " WHERE source IS NOT NULL")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            log.warning("Could not read the imported shops: " + e.getMessage());
        }
        return out;
    }

    static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack decode(String data) {
        try {
            return data == null ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
