package fr.influencraft.fluxcommerce.trade;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.reward.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Copy in the database of what each player offers in a running trade: the items are out of his inventory while the
 * trade lasts, a crash would lose them. Written at every change of an offer, removed when the trade ends (items
 * handed over or back). Rows still there at start-up come from a crash: their items go back to their owner through
 * {@code /recompenses}.
 */
final class TradeEscrow {

    private final Logger log;
    private String table;

    TradeEscrow(Logger log) {
        this.log = log;
    }

    void open() throws SQLException {
        table = Database.table("flux_commerce_trade_escrow");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + Database.dialect().key(36)
                    + " NOT NULL PRIMARY KEY, name TEXT NOT NULL, items TEXT NOT NULL, updated BIGINT NOT NULL)");
        }
    }

    /** Gives back what a crash left in the escrow (to /recompenses: the owner may be offline). */
    void recover(String label) {
        List<UUID> done = new ArrayList<>();
        try (Connection c = Database.connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, items FROM " + table)) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString(1));
                List<ItemStack> items = Arrays.asList(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(rs.getString(3))));
                Rewards.store(Bukkit.getOfflinePlayer(id), "trade", label, items);
                log.warning("Trade interrupted by a stop: " + items.size() + " stack(s) of " + rs.getString(2) + " given back in /recompenses.");
                done.add(id);
            }
        } catch (SQLException | RuntimeException e) {
            log.severe("Could not read the trade escrow (kept as it is): " + e);
            return;
        }
        for (UUID id : done) clear(id);
    }

    /** What this side offers now (empty: the row goes). */
    void save(Trade.Side side) {
        List<ItemStack> items = side.stacks();
        if (items.isEmpty()) {
            clear(side.id);
            return;
        }
        String bytes = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
        try (Connection c = Database.connection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                del.setString(1, side.id.toString());
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (uuid, name, items, updated) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, side.id.toString());
                ps.setString(2, side.name);
                ps.setString(3, bytes);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.severe("Trade escrow of " + side.name + " not written: " + e);
        }
    }

    void clear(UUID player) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("Trade escrow of " + player + " not cleared: " + e);
        }
    }
}
