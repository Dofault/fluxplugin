package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Listings, bids and imports in Flux's database (SQLite or MySQL/MariaDB, chosen in Flux), named
 * {@code flux_auctionhouse_*} before 2026-11-01:
 * <ul>
 *   <li>{@code flux_commerce_ah_listings}: one row per listing, the item as Paper's item bytes in Base64;</li>
 *   <li>{@code flux_commerce_ah_bids}: every bid of the auctions still known;</li>
 *   <li>{@code flux_commerce_ah_imports}: what a migration already brought (never imported twice).</li>
 * </ul>
 * Bans are shared by all commerce ({@code fluxcommerce.Bans}).
 * Everything is loaded at start; changes are queued to one writer thread, in order, so the main thread never waits.
 * The sales history has its own class ({@link History}).
 */
final class Store {

    private final Logger log;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "FluxCommerce-market"));
    private String listings, bids, imports;

    Store(Logger log) {
        this.log = log;
    }

    void open() throws SQLException {
        listings = Database.table("flux_commerce_ah_listings");
        bids = Database.table("flux_commerce_ah_bids");
        imports = Database.table("flux_commerce_ah_imports");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            // names before FluxCommerce (2026-11-01)
            Database.renameTable(c, Database.table("flux_auctionhouse_listings"), listings, "flux_auctionhouse_listings_seller");
            Database.renameTable(c, Database.table("flux_auctionhouse_bids"), bids, "flux_auctionhouse_bids_listing");
            Database.renameTable(c, Database.table("flux_auctionhouse_imports"), imports);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + listings + " (id " + d.key(36) + " PRIMARY KEY, seller " + d.key(36)
                    + " NOT NULL, seller_name TEXT, item TEXT NOT NULL, auction INT NOT NULL DEFAULT 0, price DOUBLE NOT NULL,"
                    + " created BIGINT NOT NULL, start_at BIGINT NOT NULL, end_at BIGINT NOT NULL, amount_left INT NOT NULL,"
                    + " state " + d.key(16) + " NOT NULL, reason TEXT)");
            Database.index(c, "flux_commerce_ah_listings_seller", listings, "seller", false);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + bids + " (id " + d.autoId() + ", listing " + d.key(36) + " NOT NULL,"
                    + " bidder " + d.key(36) + " NOT NULL, bidder_name TEXT, amount DOUBLE NOT NULL, date BIGINT NOT NULL)");
            Database.index(c, "flux_commerce_ah_bids_listing", bids, "listing", false);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + imports + " (source " + d.key(191) + " PRIMARY KEY, date BIGINT NOT NULL)");
        }
    }

    void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) log.warning("Some auction house writes were still queued at shutdown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- loading (start-up, main thread)

    Map<UUID, Listing> loadListings() throws SQLException {
        Map<UUID, Listing> out = new LinkedHashMap<>();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + listings + " ORDER BY created")) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    ItemStack item = decode(rs.getString("item"));
                    if (item == null) {
                        log.warning("Listing " + id + " skipped: its item cannot be read (kept in the database)");
                        continue;
                    }
                    try {
                        Listing l = new Listing(UUID.fromString(id), UUID.fromString(rs.getString("seller")), rs.getString("seller_name"),
                                item, rs.getInt("auction") != 0, rs.getDouble("price"), rs.getLong("created"), rs.getLong("start_at"),
                                rs.getLong("end_at"));
                        l.left = rs.getInt("amount_left");
                        l.state = Listing.State.valueOf(rs.getString("state"));
                        l.reason = rs.getString("reason");
                        out.put(l.id, l);
                    } catch (IllegalArgumentException e) {
                        log.warning("Listing " + id + " skipped: " + e.getMessage());
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + bids + " ORDER BY date, id")) {
                while (rs.next()) {
                    Listing l = out.get(UUID.fromString(rs.getString("listing")));
                    if (l != null) l.bids.add(new Listing.Bid(UUID.fromString(rs.getString("bidder")), rs.getString("bidder_name"),
                            rs.getDouble("amount"), rs.getLong("date")));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- writes (queued)

    /** Writes the listing as it is now (insert or update). */
    void save(Listing l) {
        String item = encode(l.item);
        Object[] row = {l.id.toString(), l.seller.toString(), l.sellerName, item, l.auction ? 1 : 0, l.price, l.created, l.start, l.end,
                l.left, l.state.name(), l.reason};
        run("save listing " + l.id, c -> {
            List<String> columns = List.of("id", "seller", "seller_name", "item", "auction", "price", "created", "start_at", "end_at",
                    "amount_left", "state", "reason");
            try (PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(listings, columns, List.of("id"),
                    columns.subList(1, columns.size())))) {
                for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                ps.executeUpdate();
            }
        });
    }

    void delete(Listing l) {
        String id = l.id.toString();
        run("delete listing " + id, c -> {
            try (PreparedStatement a = c.prepareStatement("DELETE FROM " + listings + " WHERE id = ?");
                 PreparedStatement b = c.prepareStatement("DELETE FROM " + bids + " WHERE listing = ?")) {
                a.setString(1, id);
                a.executeUpdate();
                b.setString(1, id);
                b.executeUpdate();
            }
        });
    }

    void addBid(Listing l, Listing.Bid b) {
        String id = l.id.toString();
        run("save bid on " + id, c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + bids + " (listing, bidder, bidder_name, amount, date) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, b.bidder().toString());
                ps.setString(3, b.name());
                ps.setDouble(4, b.amount());
                ps.setLong(5, b.date());
                ps.executeUpdate();
            }
        });
    }

    /** Records an import; false when this source was already imported (main thread, direct: imports run at start). */
    boolean markImported(String source) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(
                Database.dialect().insertIgnore() + " " + imports + " (source, date) VALUES (?, ?)")) {
            ps.setString(1, source);
            ps.setLong(2, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Import not recorded: " + e.getMessage(), e);
        }
    }

    private interface Write {
        void run(Connection c) throws SQLException;
    }

    private void run(String what, Write write) {
        writer.execute(() -> {
            try (Connection c = Database.connection()) {
                write.run(c);
            } catch (SQLException e) {
                log.severe("Auction house database: could not " + what + ": " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- items

    static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack decode(String text) {
        try {
            return text == null ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(text));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
