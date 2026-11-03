package fr.influencraft.fluxcommerce;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Players banned from commerce until a date: a banned player can neither trade with another player, nor list, buy or
 * bid at the auction house, nor create a chest shop or trade with one. What he already put up for sale stays (his
 * listings and shops keep selling). Table {@code flux_commerce_bans} (formerly {@code flux_auctionhouse_bans}, the
 * auction house's own bans), loaded at start, written by one background thread.
 */
public final class Bans {

    public record Ban(UUID player, String name, long until, String reason, String by) {
    }

    private final FluxCommerce host;
    private final Map<UUID, Ban> bans = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "FluxCommerce-bans"));
    private String table;

    Bans(FluxCommerce host) {
        this.host = host;
    }

    void open() throws SQLException {
        table = Database.table("flux_commerce_bans");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.renameTable(c, Database.table("flux_auctionhouse_bans"), table);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (player " + Database.dialect().key(36) + " PRIMARY KEY,"
                    + " name TEXT, until_at BIGINT NOT NULL, reason TEXT, by_name TEXT)");
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("player"));
                    bans.put(id, new Ban(id, rs.getString("name"), rs.getLong("until_at"), rs.getString("reason"), rs.getString("by_name")));
                }
            }
        }
    }

    void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) host.getLogger().warning("Some ban writes were still queued at shutdown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The ban of this player, null when he is free (an expired ban is removed). */
    public Ban of(UUID player) {
        Ban b = bans.get(player);
        if (b != null && b.until() <= System.currentTimeMillis()) {
            bans.remove(player);
            delete(player);
            return null;
        }
        return b;
    }

    /** True (and the player is told why) when this player is banned from commerce. */
    public boolean refused(Player p) {
        Ban b = of(p.getUniqueId());
        if (b == null) return false;
        host.messages().send(p, "banned", "%duration%", Format.duration((b.until() - System.currentTimeMillis()) / 1000),
                "%reason%", b.reason() == null || b.reason().isBlank() ? "-" : b.reason());
        Sounds.cue(p, "error");
        return true;
    }

    public Collection<Ban> all() {
        bans.keySet().forEach(this::of);
        return List.copyOf(bans.values());
    }

    public void ban(UUID player, String name, long seconds, String reason, String by) {
        Ban b = new Ban(player, name, System.currentTimeMillis() + seconds * 1000, reason, by);
        bans.put(player, b);
        write("save ban", c -> {
            var columns = List.of("player", "name", "until_at", "reason", "by_name");
            try (PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(table, columns, List.of("player"), columns.subList(1, 5)))) {
                ps.setString(1, b.player().toString());
                ps.setString(2, b.name());
                ps.setLong(3, b.until());
                ps.setString(4, b.reason());
                ps.setString(5, b.by());
                ps.executeUpdate();
            }
        });
    }

    /** False when the player was not banned. */
    public boolean pardon(UUID player) {
        if (bans.remove(player) == null) return false;
        delete(player);
        return true;
    }

    private void delete(UUID player) {
        write("delete ban", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE player = ?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
        });
    }

    private interface Write {
        void run(Connection c) throws SQLException;
    }

    private void write(String what, Write w) {
        writer.execute(() -> {
            try (Connection c = Database.connection()) {
                w.run(c);
            } catch (SQLException e) {
                host.getLogger().severe("Commerce bans: could not " + what + ": " + e.getMessage());
            }
        });
    }
}
