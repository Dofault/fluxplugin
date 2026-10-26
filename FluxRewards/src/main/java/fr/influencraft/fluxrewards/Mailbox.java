package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.PendingReward;
import fr.influencraft.flux.api.reward.RewardSource;
import fr.influencraft.flux.api.reward.Rewards;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Rewards queued for players by other plugins or configs ({@link Rewards#queue}, {@code /rewards queue}): label,
 * icon, slots needed and console commands, in SQLite ({@code plugins/FluxRewards/mailbox.db}). A reward is removed
 * <b>before</b> its commands run, so a double claim can never deliver it twice. Main thread only.
 */
final class Mailbox implements Rewards.Mailbox, RewardSource {

    private static final String SEPARATOR = "\n";

    private record Entry(long id, String source, String label, String icon, int slots, List<String> commands) {
    }

    private final FluxRewards plugin;
    private final Connection db;

    Mailbox(FluxRewards plugin) throws SQLException {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        db = DriverManager.getConnection("jdbc:sqlite:" + new File(plugin.getDataFolder(), "mailbox.db").getAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS rewards (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL,"
                    + " source TEXT NOT NULL, label TEXT NOT NULL, icon TEXT NOT NULL, slots INTEGER NOT NULL,"
                    + " commands TEXT NOT NULL, created INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS rewards_uuid ON rewards (uuid, id)");
        }
    }

    void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // server stopping
        }
    }

    @Override
    public void queue(OfflinePlayer player, String source, String label, String icon, int slots, List<String> commands) {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO rewards (uuid, source, label, icon, slots, commands, created) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, source);
            ps.setString(3, label);
            ps.setString(4, icon);
            ps.setInt(5, slots);
            ps.setString(6, String.join(SEPARATOR, commands));
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Reward NOT queued for " + player.getUniqueId() + " (" + label + ", " + commands + "): " + e);
            return;
        }
        Rewards.changed(player);
    }

    private List<Entry> entries(UUID uuid) {
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, source, label, icon, slots, commands FROM rewards WHERE uuid = ? ORDER BY id")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                            Arrays.asList(rs.getString(6).split(SEPARATOR))));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read the mailbox of " + uuid + ": " + e);
        }
        return out;
    }

    private boolean remove(long id) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM rewards WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not remove mailbox reward " + id + ": " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- as a reward source

    @Override
    public String id() {
        return "mailbox";
    }

    @Override
    public CompletableFuture<List<PendingReward>> pending(Player player) {
        List<PendingReward> out = new ArrayList<>();
        for (Entry e : entries(player.getUniqueId())) out.add(new PendingReward(e.source(), e.label(), e.icon(), 1, e.slots()));
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletableFuture<ClaimResult> claim(Player player, int freeSlots) {
        int delivered = 0, remaining = 0, room = freeSlots;
        for (Entry e : entries(player.getUniqueId())) {
            if (e.slots() > room) {
                remaining++;
                continue;
            }
            if (!remove(e.id())) {
                remaining++;
                continue;
            }
            room -= e.slots();
            plugin.run(player, e.commands());
            delivered++;
        }
        return CompletableFuture.completedFuture(new ClaimResult(delivered, remaining));
    }
}
