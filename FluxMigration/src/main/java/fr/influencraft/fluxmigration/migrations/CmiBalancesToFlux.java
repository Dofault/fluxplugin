package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-14, the server's money kept by Flux: CMI's balances credited to the Flux currency that is now the Vault
 * economy ({@code currencies.<id>.vault: true}, BitBlock).
 *
 * <p>Source: CMI's own storage, read without CMI ({@code plugins/CMI/Settings/DataBaseInfo.yml}): the SQLite file
 * {@code plugins/CMI/cmi.sqlite.db} (Labs, table {@code users}) or its MySQL database (production, table
 * {@code <tablePrefix>users}), columns {@code player_uuid} and {@code Balance}. CMI writes its balances when the
 * server stops: run this at the first start after a clean stop.</p>
 *
 * <p>Every balance is read first (a source that cannot be read changes nothing), then credited one player at a time;
 * each credited player is noted in {@code flux_migration_cmi_balances}, so a run interrupted half-way resumes without
 * paying anybody twice, and money earned in Flux before the migration ran is kept on top. Remove once production
 * runs it.</p>
 */
public final class CmiBalancesToFlux implements Migration {

    private static final String DONE = Database.table("flux_migration_cmi_balances");

    private final FluxMigration plugin;

    public CmiBalancesToFlux(FluxMigration plugin) {
        this.plugin = plugin;
    }

    private File cmi() {
        return new File(plugin.getDataFolder().getParentFile(), "CMI");
    }

    @Override
    public String id() {
        return "2026-11-14_cmi_balances_to_flux";
    }

    @Override
    public String description() {
        return "CMI balances -> the money kept by Flux (Vault economy)";
    }

    /** The server's money when Flux keeps it itself, else null (another plugin's economy: nothing to migrate). */
    private static Currency money() {
        Currency main = Economy.get("vault");
        // kept by another plugin, the Vault currency is called "vault" itself
        return main == null || main.id().equals("vault") || !main.available() ? null : main;
    }

    @Override
    public boolean ready() {
        return money() != null && new File(cmi(), "Settings/DataBaseInfo.yml").isFile();
    }

    private Connection source(YamlConfiguration info) throws SQLException {
        if ("mysql".equalsIgnoreCase(info.getString("storage.method", "sqlite"))) {
            String url = "jdbc:mysql://" + info.getString("mysql.hostname", "localhost:3306") + "/" + info.getString("mysql.database")
                    + "?useSSL=" + info.getBoolean("mysql.useSSL", false) + "&allowPublicKeyRetrieval=true";
            return DriverManager.getConnection(url, info.getString("mysql.username"), info.getString("mysql.password"));
        }
        File file = new File(cmi(), "cmi.sqlite.db");
        if (!file.isFile()) throw new IllegalStateException("CMI's SQLite file is missing: " + file);
        return Database.readSqlite(file);
    }

    @Override
    public String run() throws Exception {
        Currency money = money();
        YamlConfiguration info = YamlConfiguration.loadConfiguration(new File(cmi(), "Settings/DataBaseInfo.yml"));
        boolean mysql = "mysql".equalsIgnoreCase(info.getString("storage.method", "sqlite"));
        String table = mysql ? info.getString("mysql.tablePrefix", "CMI_") + "users" : "users";

        Map<UUID, Double> balances = new LinkedHashMap<>();
        int unreadable = 0;
        try (Connection c = source(info); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT player_uuid, Balance FROM " + table + " WHERE Balance > 0")) {
            while (rs.next()) {
                try {
                    balances.merge(UUID.fromString(rs.getString(1)), rs.getDouble(2), Double::sum);
                } catch (IllegalArgumentException | NullPointerException e) {
                    unreadable++;
                }
            }
        }

        Set<UUID> done = new HashSet<>();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + DONE + " (uuid " + Database.dialect().key(36) + " NOT NULL PRIMARY KEY, amount DOUBLE NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT uuid FROM " + DONE)) {
                while (rs.next()) done.add(UUID.fromString(rs.getString(1)));
            }
        }

        Origin origin = Origin.of(plugin, "cmi_balances");
        int copied = 0;
        double total = 0;
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            if (done.contains(e.getKey())) continue;
            double amount = Math.floor(e.getValue() * 100 + 1e-6) / 100;
            if (amount <= 0) continue;
            // noted first: a crash between the two lines must never pay twice (the report below tells what was paid)
            note(e.getKey(), amount);
            if (!money.give(Bukkit.getOfflinePlayer(e.getKey()), amount, origin)) {
                forget(e.getKey());
                throw new IllegalStateException(money.id() + " not credited to " + e.getKey() + " (" + amount + "), " + copied + " done before");
            }
            copied++;
            total += amount;
        }
        return copied + " balance(s) copied (" + Math.round(total) + " in all), " + done.size() + " already done"
                + (unreadable > 0 ? ", " + unreadable + " unreadable row(s)" : "");
    }

    private static void note(UUID uuid, double amount) throws SQLException {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(DONE, List.of("uuid", "amount"), List.of("uuid"), List.of("amount")))) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, amount);
            ps.executeUpdate();
        }
    }

    private static void forget(UUID uuid) throws SQLException {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + DONE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }
}
