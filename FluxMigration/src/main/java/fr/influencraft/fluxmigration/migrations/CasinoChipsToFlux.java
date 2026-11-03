package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 2026-10-30, casino chips kept by Flux: the {@code chips} column of FluxCasino's {@code flux_casino_balances} (formerly {@code casino_balances}) (same
 * database) credited to Flux's {@code chips} currency. A player who already has chips in Flux is left as he is.
 * FluxCasino keeps only the bets in that table. Remove once production runs it.
 */
public final class CasinoChipsToFlux implements Migration {

    private final FluxMigration plugin;

    public CasinoChipsToFlux(FluxMigration plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "2026-10-30_casino_chips_to_flux";
    }

    @Override
    public String description() {
        return "FluxCasino chips -> Flux currency chips";
    }

    @Override
    public boolean ready() {
        Currency chips = Economy.get("chips");
        return chips != null && chips.available() && Database.available();
    }

    @Override
    public String run() throws Exception {
        Currency chips = Economy.get("chips");
        Origin origin = Origin.of(plugin, "casino");
        List<Object[]> rows = new ArrayList<>();
        // read first, write after: SQLite has one connection, the currency writes need it
        try (Connection c = Database.connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, chips FROM " + balances(c) + " WHERE chips > 0")) {
            while (rs.next()) rows.add(new Object[]{rs.getString(1), rs.getLong(2)});
        } catch (SQLException noTable) {
            return "no casino table, nothing to copy";
        }
        int copied = 0, kept = 0;
        for (Object[] row : rows) {
            var player = Bukkit.getOfflinePlayer(UUID.fromString((String) row[0]));
            if (chips.balance(player) > 0) {
                kept++;
                continue;
            }
            if (!chips.give(player, (Long) row[1], origin)) throw new IllegalStateException("chips not credited to " + row[0]);
            copied++;
        }
        return copied + " balance(s) copied, " + kept + " already in Flux";
    }

    /** FluxCasino's table, under its name since 2026-10-31 or its former one (FluxCasino not started yet). */
    private static String balances(java.sql.Connection c) throws java.sql.SQLException {
        String now = Database.table("flux_casino_balances");
        return Database.tableExists(c, now) ? now : Database.table("casino_balances");
    }
}
