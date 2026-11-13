package fr.influencraft.flux.settings;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.PlayerSettings;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings window ({@code /parametres}, command {@code settings}): one check box per option of
 * {@code settings.yml}, its description shown when the mouse is over its name. Saving runs the {@code on} or
 * {@code off} lines of each option that changed ({@code [console] ...} by default, {@code [player] ...} as the
 * player, {@code %player%} = name).
 *
 * <p>The state of an option comes from its {@code state} placeholder when the plugin behind it gives one
 * ({@code true}/{@code yes}/{@code oui}/{@code 1} = on), else from the last choice saved by Flux
 * ({@link PlayerSettings}, key {@code flux:<option>}), else from its {@code default}.</p>
 *
 * <p>An option with {@code choices} (key: label) is a drop-down list instead of a check box: the key chosen is saved
 * (read by Flux, e.g. the HUD's corners), {@code default} is a key, and {@code change} lines run when it changes
 * ({@code %value%} = the key).</p>
 */
public final class SettingsService {

    private record Option(String id, String label, String description, boolean byDefault, String state,
                          List<String> on, List<String> off, Map<String, String> choices, String defaultChoice,
                          List<String> change) {
        boolean choice() {
            return !choices.isEmpty();
        }
    }

    private final JavaPlugin flux;
    private final List<Option> options = new ArrayList<>();
    private YamlConfiguration config = new YamlConfiguration();

    public SettingsService(JavaPlugin flux) {
        this.flux = flux;
    }

    public void start() {
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
            Map<String, String> choices = new java.util.LinkedHashMap<>();
            ConfigurationSection cs = o.getConfigurationSection("choices");
            if (cs != null) for (String k : cs.getKeys(false)) choices.put(k, cs.getString(k, k));
            String defaultChoice = choices.isEmpty() ? "" : o.getString("default", choices.keySet().iterator().next());
            options.add(new Option(id, o.getString("label", id), o.getString("description", ""), o.getBoolean("default", true),
                    o.getString("state", ""), o.getStringList("on"), o.getStringList("off"), choices, defaultChoice,
                    o.getStringList("change")));
        }
    }

    public void open(Player player) {
        Map<String, Boolean> saved = saved(player);
        Map<String, Boolean> before = new HashMap<>();
        Map<String, String> chosenBefore = new HashMap<>();
        Dialogs.Form form = Dialogs.form(player, config.getString("title", "Settings"))
                .buttons(config.getString("save", "Save"), config.getString("close", "Close"));
        for (String line : config.getStringList("text")) form.body(line);
        for (Option o : options) {
            if (o.choice()) {
                String current = PlayerSettings.get(player, key(o.id()), o.defaultChoice());
                if (!o.choices().containsKey(current)) current = o.defaultChoice();
                chosenBefore.put(o.id(), current);
                form.choice(o.id(), o.label(), List.copyOf(o.choices().values()), o.choices().get(current));
                continue;
            }
            boolean on = current(player, o, saved);
            before.put(o.id(), on);
            form.toggle(o.id(), o.label(), on, o.description());
        }
        form.onSubmit((p, answer) -> {
            int changed = 0;
            for (Option o : options) {
                if (o.choice()) {
                    String picked = answer.text(o.id());
                    String chosen = o.choices().entrySet().stream().filter(e -> e.getValue().equals(picked)).map(Map.Entry::getKey)
                            .findFirst().orElse(chosenBefore.get(o.id()));
                    if (chosen.equals(chosenBefore.get(o.id()))) continue;
                    PlayerSettings.set(p, key(o.id()), chosen);
                    run(p, o.change().stream().map(l -> l.replace("%value%", chosen)).toList());
                    changed++;
                    continue;
                }
                boolean on = answer.toggle(o.id());
                if (on == before.get(o.id())) continue;
                run(p, on ? o.on() : o.off());
                PlayerSettings.set(p, key(o.id()), on);
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

    // Saved in PlayerSettings (one settings table for the suite). Before 2026-10-31 this class wrote its own columns in
    // the same table, which PlayerSettings had already created with others: nothing was ever saved, nothing to move.
    private static String key(String option) {
        return "flux:" + option;
    }

    private Map<String, Boolean> saved(Player player) {
        Map<String, Boolean> out = new HashMap<>();
        for (Option o : options) {
            if (o.choice()) continue;
            if (PlayerSettings.has(player, key(o.id()))) out.put(o.id(), PlayerSettings.flag(player, key(o.id()), o.byDefault()));
        }
        return out;
    }
}
