package fr.influencraft.fluxmigration;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * FluxMigration: the one place for the one-time data migrations of the suite (former plugins to Flux plugins). Every
 * migration ready and not yet done runs once when the server starts, before players can join; its id
 * is then recorded in Flux's database ({@code fluxmigration_done}). Deploy it for a migration, remove the jar (and
 * delete the migrations that ran everywhere) afterwards: no migration code lives in the other plugins.
 *
 * <pre>
 *   /fluxmigration status | run &lt;id&gt;    (admin)
 * </pre>
 */
public final class FluxMigration extends FluxPlugin {

    private List<Migration> migrations = List.of();
    private String table;

    @Override
    protected void enable() {
        table = Database.table("fluxmigration_done");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (id " + Database.dialect().key(100)
                    + " PRIMARY KEY, done_at BIGINT NOT NULL, report TEXT)");
        } catch (SQLException e) {
            getLogger().severe("Could not create " + table + ": " + e.getMessage());
            return;
        }
        migrations = Migrations.all(this);
        command("fluxmigration")
                .description("One-time data migrations.")
                .permission("fluxmigration.admin")
                .usage(messages().raw("usage"))
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 ? List.of("status", "run")
                        : args.length == 2 ? migrations.stream().map(Migration::id).toList() : List.of())
                .register();
        // now, before any player can join (the targets are enabled first: softdepend), then once more for the
        // targets enabled later
        runPending();
        Bukkit.getScheduler().runTaskLater(this, this::runPending, 100L);
    }

    private void runPending() {
        for (Migration m : migrations) {
            if (done(m.id()) || !m.ready()) continue;
            run(m, Bukkit.getConsoleSender());
        }
    }

    private void run(Migration m, CommandSender sender) {
        getLogger().info("Migration " + m.id() + "...");
        try {
            String report = m.run();
            record(m.id(), report);
            getLogger().info("Migration " + m.id() + " done: " + report);
            if (sender != Bukkit.getConsoleSender()) messages().send(sender, "done", "%id%", m.id(), "%report%", report);
        } catch (Exception e) {
            getLogger().severe("Migration " + m.id() + " failed (retried at next start): " + e);
            messages().send(sender, "failed", "%id%", m.id(), "%error%", String.valueOf(e.getMessage()));
        }
    }

    private boolean done(String id) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            getLogger().warning("Could not read " + table + ": " + e.getMessage());
            return true; // unknown: never run twice
        }
    }

    private void record(String id, String report) throws SQLException {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().insertIgnore() + " " + table + " (id, done_at, report) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, report);
            ps.executeUpdate();
        }
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("run") && args.length > 1) {
            for (Migration m : migrations) {
                if (m.id().equalsIgnoreCase(args[1])) {
                    run(m, sender);
                    return true;
                }
            }
            messages().send(sender, "unknown");
            return true;
        }
        if (!sub.equals("status")) {
            messages().send(sender, "usage");
            return true;
        }
        for (Migration m : migrations) {
            String state = done(m.id()) ? "state-done" : m.ready() ? "state-pending" : "state-waiting";
            messages().send(sender, "status-line", "%state%", messages().raw(state), "%id%", m.id(), "%description%", m.description());
        }
        return true;
    }
}
