package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * 2026-10-30, Influence kept by Flux: PlayerPoints balances ({@code plugins/PlayerPoints/playerpoints.db}, table
 * {@code playerpoints_points}, SQLite on Labs and production) credited to Flux's {@code influence} currency. A player
 * who already has Influence in Flux is left as he is. Run with PlayerPoints removed (else /points still goes to
 * PlayerPoints). Remove once production runs it.
 */
public final class PlayerPointsToInfluence implements Migration {

    private final FluxMigration plugin;

    public PlayerPointsToInfluence(FluxMigration plugin) {
        this.plugin = plugin;
    }

    private File source() {
        return new File(plugin.getDataFolder().getParentFile(), "PlayerPoints/playerpoints.db");
    }

    @Override
    public String id() {
        return "2026-10-30_playerpoints_to_influence";
    }

    @Override
    public String description() {
        return "PlayerPoints balances -> Flux currency influence";
    }

    @Override
    public boolean ready() {
        Currency influence = Economy.get("influence");
        return source().isFile() && influence != null && influence.available()
                && !Bukkit.getPluginManager().isPluginEnabled("PlayerPoints");
    }

    @Override
    public String run() throws Exception {
        Currency influence = Economy.get("influence");
        Origin origin = Origin.of(plugin, "playerpoints");
        int copied = 0, kept = 0;
        long total = 0;
        try (Connection c = fr.influencraft.flux.api.data.Database.readSqlite(source());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, points FROM playerpoints_points WHERE points > 0")) {
            while (rs.next()) {
                var player = Bukkit.getOfflinePlayer(UUID.fromString(rs.getString(1)));
                if (influence.balance(player) > 0) {
                    kept++;
                    continue;
                }
                long points = rs.getLong(2);
                if (!influence.give(player, points, origin)) throw new IllegalStateException("influence not credited to " + player.getUniqueId());
                copied++;
                total += points;
            }
        }
        return copied + " balance(s) copied (" + total + " Influence), " + kept + " already in Flux";
    }
}
