package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Sales of the market (table {@code flux_commerce_sales} of Flux's database, {@code flux_auctionhouse_sales} before 2026-11-01): direct sales and auctions of the
 * auction house, trades of the chest shops. Read by {@code /hdv history} and the market price ({@code /hdv average},
 * menus). Column names are the former French ones: the table already holds data.
 *
 * <p>Writes on a thread of their own; reads on the main thread, cached a few seconds (a menu is drawn again every
 * second).</p>
 */
final class History {

    /** Sources of a sale (column {@code source}). */
    static final String SALE = "vente", AUCTION = "enchere", SHOP = "echoppe", SHOP_BUYING = "rachat";

    /** One sale. {@code total} = paid by the buyer, {@code tax} = kept from the seller. */
    record Sale(long date, String source, String ref, UUID seller, String sellerName, UUID buyer, String buyerName,
                String key, String name, int amount, double total, double tax, byte[] item, String town) {
        double unit() {
            return amount > 0 ? total / amount : total;
        }

        boolean auctionHouse() {
            return SALE.equals(source) || AUCTION.equals(source);
        }

        ItemStack itemStack() {
            try {
                return item == null ? null : ItemStack.deserializeBytes(item);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Prices really paid for a key over the period, average weighted by the amount. Sales at an absurd price (more than
     * {@code average.outlier} times above or below the median price: a typing mistake bought anyway) are left out of
     * every figure, silently.
     *
     * @param auctionHouse average of the auction house only, -1 without sale there
     * @param shops        average of the chest shops only, -1 without sale there
     */
    record Average(int sales, long amount, double total, double min, double max, double auctionHouse, double shops) {
        double unit() {
            return amount > 0 ? total / amount : 0;
        }
    }

    private final AuctionHouseModule plugin;
    private final Logger log;
    private final String table = Database.table("flux_commerce_sales");
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "FluxCommerce-sales"));
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>(); // key -> {expiry, value}

    History(AuctionHouseModule plugin) throws SQLException {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement s = c.createStatement()) {
            Database.renameTable(c, Database.table("flux_auctionhouse_sales"), table, "flux_auctionhouse_sales_key",
                    "flux_auctionhouse_sales_seller", "flux_auctionhouse_sales_buyer");
            s.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + " id " + d.autoId() + ","
                    + " date BIGINT NOT NULL, source " + d.key(32) + " NOT NULL, ref TEXT,"
                    + " vendeur " + d.key(36) + ", vendeur_nom TEXT, acheteur " + d.key(36) + ", acheteur_nom TEXT,"
                    + " cle " + d.key(191) + " NOT NULL, nom TEXT, quantite INTEGER NOT NULL,"
                    + " total DOUBLE NOT NULL, taxe DOUBLE NOT NULL, objet " + (d == Database.Dialect.SQLITE ? "BLOB" : "MEDIUMBLOB") + ", ville TEXT)");
            Database.index(c, "flux_commerce_sales_key", table, "cle, date", false);
            Database.index(c, "flux_commerce_sales_seller", table, "vendeur, date", false);
            Database.index(c, "flux_commerce_sales_buyer", table, "acheteur, date", false);
        }
    }

    void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- writing

    void record(Sale v) {
        cache.clear();
        writer.execute(() -> {
            try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (date, source, ref,"
                    + " vendeur, vendeur_nom, acheteur, acheteur_nom, cle, nom, quantite, total, taxe, objet, ville) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, v.date());
                ps.setString(2, v.source());
                ps.setString(3, v.ref());
                ps.setString(4, v.seller() == null ? null : v.seller().toString());
                ps.setString(5, v.sellerName());
                ps.setString(6, v.buyer() == null ? null : v.buyer().toString());
                ps.setString(7, v.buyerName());
                ps.setString(8, v.key());
                ps.setString(9, v.name());
                ps.setInt(10, v.amount());
                ps.setDouble(11, v.total());
                ps.setDouble(12, v.tax());
                ps.setBytes(13, v.item());
                ps.setString(14, v.town());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("Sale not recorded in the history: " + e.getMessage());
            }
            cache.clear();
        });
    }

    // ---------------------------------------------------------------- reading

    @SuppressWarnings("unchecked")
    private <T> T memo(String key, long ms, Supplier<T> compute) {
        Object[] c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && (long) c[0] > now) return (T) c[1];
        T v = compute.get();
        cache.put(key, new Object[]{now + ms, v});
        return v;
    }

    /** Sales and purchases of a player, latest first. filter: 0 everything, 1 sales, 2 purchases. */
    List<Sale> of(UUID player, int filter, int limit) {
        return memo("p:" + player + ":" + filter, 5000, () -> {
            String where = switch (filter) {
                case 1 -> "vendeur = ?";
                case 2 -> "acheteur = ?";
                default -> "(vendeur = ? OR acheteur = ?)";
            };
            List<Sale> l = new ArrayList<>();
            try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + table + " WHERE " + where + " ORDER BY date DESC LIMIT ?")) {
                int i = 1;
                ps.setString(i++, player.toString());
                if (filter == 0) ps.setString(i++, player.toString());
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) l.add(read(rs));
                }
            } catch (SQLException e) {
                log.warning("History not read: " + e.getMessage());
            }
            return l;
        });
    }

    /** Sales of a player's auction house listings since a date (what happened while he was away). */
    List<Sale> soldSince(UUID seller, long since) {
        List<Sale> l = new ArrayList<>();
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + table + " WHERE vendeur = ? AND date > ? AND (source = ? OR source = ?) ORDER BY date")) {
            ps.setString(1, seller.toString());
            ps.setLong(2, since);
            ps.setString(3, SALE);
            ps.setString(4, AUCTION);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) l.add(read(rs));
            }
        } catch (SQLException e) {
            log.warning("History not read: " + e.getMessage());
        }
        return l;
    }

    /** Market price of a key over the last {@code days} days (null without any sale). */
    Average average(String key, int days) {
        Object[] wrap = memo("a:" + key + ":" + days, 30000, () -> new Object[]{computeAverage(key, days)});
        return (Average) wrap[0];
    }

    private Average computeAverage(String key, int days) {
        long since = System.currentTimeMillis() - days * 86_400_000L;
        List<double[]> rows = new ArrayList<>(); // {unit price, amount, total, 1 = auction house}
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT source, quantite, total FROM " + table + " WHERE cle = ? AND date >= ? AND quantite > 0")) {
            ps.setString(1, key);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String source = rs.getString(1);
                    long amount = rs.getLong(2);
                    double total = rs.getDouble(3);
                    rows.add(new double[]{total / amount, amount, total, SALE.equals(source) || AUCTION.equals(source) ? 1 : 0});
                }
            }
        } catch (SQLException e) {
            log.warning("Market price not read: " + e.getMessage());
            return null;
        }
        return average(rows, plugin.getConfig().getDouble("average.outlier", 3));
    }

    /** The figures of {@link Average} for these sales, without those beyond {@code outlier} times the median (0 = all). */
    static Average average(List<double[]> rows, double outlier) {
        if (rows.isEmpty()) return null;
        double median = weightedMedian(rows);
        int sales = 0;
        long amount = 0, ahAmount = 0, shopAmount = 0;
        double total = 0, ahTotal = 0, shopTotal = 0, min = Double.MAX_VALUE, max = 0;
        for (double[] r : rows) {
            if (outlier > 1 && median > 0 && (r[0] > median * outlier || r[0] < median / outlier)) continue;
            sales++;
            amount += (long) r[1];
            total += r[2];
            min = Math.min(min, r[0]);
            max = Math.max(max, r[0]);
            if (r[3] == 1) {
                ahAmount += (long) r[1];
                ahTotal += r[2];
            } else {
                shopAmount += (long) r[1];
                shopTotal += r[2];
            }
        }
        if (sales == 0) return null;
        return new Average(sales, amount, total, min, max, ahAmount > 0 ? ahTotal / ahAmount : -1, shopAmount > 0 ? shopTotal / shopAmount : -1);
    }

    /** Unit price in the middle of the items sold (each item counts, not each sale). */
    private static double weightedMedian(List<double[]> rows) {
        List<double[]> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingDouble(r -> r[0]));
        double half = sorted.stream().mapToDouble(r -> r[1]).sum() / 2, cumul = 0;
        for (double[] r : sorted) {
            cumul += r[1];
            if (cumul >= half) return r[0];
        }
        return sorted.getLast()[0];
    }

    private static Sale read(ResultSet rs) throws SQLException {
        String v = rs.getString("vendeur"), a = rs.getString("acheteur");
        return new Sale(rs.getLong("date"), rs.getString("source"), rs.getString("ref"),
                v == null ? null : UUID.fromString(v), rs.getString("vendeur_nom"),
                a == null ? null : UUID.fromString(a), rs.getString("acheteur_nom"),
                rs.getString("cle"), rs.getString("nom"), rs.getInt("quantite"), rs.getDouble("total"),
                rs.getDouble("taxe"), rs.getBytes("objet"), rs.getString("ville"));
    }
}
