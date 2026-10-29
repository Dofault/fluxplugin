package fr.influencraft.flux.settings;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The settings window ({@code /parametres}, command {@code settings}): one check box per option of
 * {@code settings.yml}, its description shown when the mouse is over its name. Saving runs the {@code on} or
 * {@code off} lines of each option that changed ({@code [console] ...} by default, {@code [player] ...} as the
 * player, {@code %player%} = name).
 *
 * <p>The state of an option comes from its {@code state} placeholder when the plugin behind it gives one
 * ({@code true}/{@code yes}/{@code oui}/{@code 1} = on), else from the last choice saved by Flux (table
 * {@code flux_settings}), else from its {@code default}.</p>
 */
public final class SettingsService {

    private record Option(String id, String label, String description, boolean byDefault, String state,
                          List<String> on, List<String> off) {
    }

    private final JavaPlugin flux;
    private final List<Option> options = new ArrayList<>();
    private YamlConfiguration config = new YamlConfiguration();
    private String table;

    public SettingsService(JavaPlugin flux) {
        this.flux = flux;
    }

    public void start() {
        table = Database.table("flux_settings");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + Database.dialect().key(36)
                    + " NOT NULL, setting VARCHAR(64) NOT NULL, enabled INT NOT NULL, PRIMARY KEY (uuid, setting))");
        } catch (SQLException e) {
            flux.getLogger().severe("Could not create " + table + ": " + e.getMessage());
        }
        load();
        Commands.create(flux, "settings").aliases("parametres", "reglages")
                .description("Tes réglages : affichage et options de jeu")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null) open(p);
                    return true;
                })
                .register();
    }

    public void load() {
        File file = new File(flux.getDataFolder(), "settings.yml");
        if (!file.exists()) flux.saveResource("settings.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
        options.clear();
        ConfigurationSection s = config.getConfigurationSection("options");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection o = s.getConfigurationSection(id);
            if (o == null) continue;
            options.add(new Option(id, o.getString("label", id), o.getString("description", ""), o.getBoolean("default", true),
                    o.getString("state", ""), o.getStringList("on"), o.getStringList("off")));
        }
    }

    public void open(Player player) {
        Map<String, Boolean> saved = saved(player.getUniqueId());
        Map<String, Boolean> before = new HashMap<>();
        Dialogs.Form form = Dialogs.form(player, config.getString("title", "Settings"))
                .buttons(config.getString("save", "Save"), config.getString("close", "Close"));
        for (String line : config.getStringList("text")) form.body(line);
        for (Option o : options) {
            boolean on = current(player, o, saved);
            before.put(o.id(), on);
            form.toggle(o.id(), o.label(), on, o.description());
        }
        form.onSubmit((p, answer) -> {
            int changed = 0;
            for (Option o : options) {
                boolean on = answer.toggle(o.id());
                if (on == before.get(o.id())) continue;
                run(p, on ? o.on() : o.off());
                save(p.getUniqueId(), o.id(), on);
                changed++;
            }
            Sounds.cue(p, changed > 0 ? "success" : "click");
        }).open();
    }

    private boolean current(Player player, Option o, Map<String, Boolean> saved) {
        if (!o.state().isBlank()) {
            String v = Texts.placeholders(o.state(), player).trim().toLowerCase(java.util.Locale.ROOT);
            if (!v.isEmpty() && v.indexOf('%') < 0) return v.equals("true") || v.equals("yes") || v.equals("oui") || v.equals("1");
        }
        return saved.getOrDefault(o.id(), o.byDefault());
    }

    private void run(Player player, List<String> lines) {
        for (String raw : lines) {
            String line = raw.replace("%player%", player.getName()).replace("%player_name%", player.getName()).trim();
            if (line.startsWith("[player]")) player.performCommand(strip(line.substring(8)));
            else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), strip(line.startsWith("[console]") ? line.substring(9) : line));
        }
    }

    private static String strip(String command) {
        String c = command.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    // ------------------------------------------------------------------ storage

    private Map<String, Boolean> saved(UUID player) {
        Map<String, Boolean> out = new HashMap<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT setting, enabled FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getInt(2) != 0);
            }
        } catch (SQLException e) {
            flux.getLogger().warning("Could not read the settings of " + player + ": " + e.getMessage());
        }
        return out;
    }

    private void save(UUID player, String setting, boolean enabled) {
        String sql = Database.dialect().upsert(table, List.of("uuid", "setting", "enabled"), List.of("uuid", "setting"), List.of("enabled"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, setting);
            ps.setInt(3, enabled ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            flux.getLogger().warning("Could not save the setting " + setting + " of " + player + ": " + e.getMessage());
        }
    }
}
