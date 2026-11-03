package fr.influencraft.fluxcommerce.trade;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Completed trades in Flux's database, table {@code flux_commerce_trade_history}: who traded with whom, when, and what each one
 * gave, written as text for the staff (items with their name, custom id and enchantments, experience points, money).
 * Writes and reads run off the main thread; results come back on it.
 */
final class TradeHistory {

    /** One trade, seen from nobody in particular. */
    record Entry(long at, UUID aId, String aName, UUID bId, String bName, String aGave, String bGave) {
    }

    private final TradeModule plugin;
    private String table;
    private boolean ready;

    TradeHistory(TradeModule plugin) {
        this.plugin = plugin;
    }

    void open() {
        table = Database.table("flux_commerce_trade_history");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            // name before FluxCommerce (2026-11-01)
            Database.renameTable(c, Database.table("flux_trade_history"), table, "flux_trade_history_a", "flux_trade_history_b");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (id " + d.autoId() + ", at BIGINT NOT NULL,"
                    + " a_uuid " + d.key(36) + " NOT NULL, a_name " + d.key(32) + ", b_uuid " + d.key(36) + " NOT NULL,"
                    + " b_name " + d.key(32) + ", a_gave TEXT, b_gave TEXT)");
            Database.index(c, "flux_commerce_trade_history_a", table, "a_uuid, at", false);
            Database.index(c, "flux_commerce_trade_history_b", table, "b_uuid, at", false);
            ready = true;
        } catch (SQLException e) {
            ready = false;
            plugin.getLogger().severe("Trade history unavailable: " + e.getMessage());
        }
        long days = plugin.getConfig().getLong("history.keep-days", 90);
        if (ready && days > 0) {
            long before = System.currentTimeMillis() - days * 86_400_000L;
            async(() -> {
                try (Connection c = Database.connection();
                     PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE at < ?")) {
                    ps.setLong(1, before);
                    int n = ps.executeUpdate();
                    if (n > 0) plugin.getLogger().info(n + " trade(s) older than " + days + " days removed from the history.");
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not clean the trade history: " + e.getMessage());
                }
            });
        }
    }

    void record(Trade.Side a, List<ItemStack> aGave, Trade.Side b, List<ItemStack> bGave) {
        if (!ready || !plugin.getConfig().getBoolean("history.enabled", true)) return;
        long at = System.currentTimeMillis();
        String aText = describe(aGave, a), bText = describe(bGave, b);
        async(() -> {
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                         + " (at, a_uuid, a_name, b_uuid, b_name, a_gave, b_gave) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, at);
                ps.setString(2, a.id.toString());
                ps.setString(3, a.name);
                ps.setString(4, b.id.toString());
                ps.setString(5, b.name);
                ps.setString(6, aText);
                ps.setString(7, bText);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not record the trade " + a.name + " - " + b.name + ": " + e.getMessage());
            }
        });
    }

    /** Trades of a player, most recent first ({@code page} from 0); {@code done} runs on the main thread. */
    void of(UUID player, int page, int perPage, Consumer<List<Entry>> done) {
        if (!ready) {
            done.accept(List.of());
            return;
        }
        async(() -> {
            List<Entry> out = new ArrayList<>();
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table
                         + " WHERE a_uuid = ? OR b_uuid = ? ORDER BY at DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                ps.setInt(3, perPage);
                ps.setInt(4, Math.max(0, page) * perPage);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Entry(rs.getLong("at"), UUID.fromString(rs.getString("a_uuid")), rs.getString("a_name"),
                                UUID.fromString(rs.getString("b_uuid")), rs.getString("b_name"), rs.getString("a_gave"),
                                rs.getString("b_gave")));
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().warning("Could not read the trade history: " + e.getMessage());
            }
            Bukkit.getScheduler().runTask(plugin.host(), () -> done.accept(out));
        });
    }

    private void async(Runnable task) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin.host(), task);
        else task.run();
    }

    /**
     * What one side gave, for the staff: {@code 64x diamond; 1x diamond_sword "Excalibur" [fluxtools:x]
     * {sharpness 5}; 1 395 xp; 12 500 money}. "-" when nothing.
     */
    static String describe(List<ItemStack> items, Trade.Side side) {
        List<String> parts = new ArrayList<>();
        for (ItemStack item : items) parts.add(describe(item));
        if (side.xp > 0) parts.add(side.xp + " xp" + (side.xpLevels > 0 ? " (" + side.xpLevels + " levels)" : ""));
        if (side.money > 0) parts.add(side.money + " money");
        return parts.isEmpty() ? "-" : String.join("; ", parts);
    }

    static String describe(ItemStack item) {
        StringBuilder sb = new StringBuilder().append(item.getAmount()).append("x ").append(item.getType().getKey().getKey());
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            sb.append(" \"").append(Texts.plain(meta.displayName())).append('"');
        }
        String id = CustomItems.id(item);
        if (id != null) sb.append(" [").append(id).append(']');
        if (meta != null && !meta.getEnchants().isEmpty()) {
            List<String> enchants = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                enchants.add(e.getKey().getKey().getKey() + " " + e.getValue());
            }
            sb.append(" {").append(String.join(", ", enchants)).append('}');
        }
        return sb.toString();
    }
}
