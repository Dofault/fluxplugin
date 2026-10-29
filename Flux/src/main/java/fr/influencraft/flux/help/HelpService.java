package fr.influencraft.flux.help;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Players;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The help window ({@code help.yml} of Flux): the server's links and shortcuts (in-game menu, settings, commands),
 * shown two ways from the same file:
 * <ul>
 *   <li>{@code /aide} (command {@code help}): a dialog built on the fly ({@link Dialogs#links});</li>
 *   <li>the pause menu (Escape): a button opening the same window. The client only shows dialogs of the server's
 *   registry there (tag {@code minecraft:pause_screen_additions}), so Flux writes them as a data pack
 *   ({@code <main world>/datapacks/flux}); a change of {@code help.yml} reaches the pause menu after a restart.
 *   Its command buttons run {@code /fluxaide <command>}: the client would go back to the pause menu (a command
 *   answering in the chat stayed hidden), Flux closes the screen first.</li>
 * </ul>
 */
public final class HelpService {

    private static final String NAMESPACE = "influencraft";
    private static final String DIALOG = "aide";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final JavaPlugin flux;
    private YamlConfiguration config = new YamlConfiguration();

    public HelpService(JavaPlugin flux) {
        this.flux = flux;
    }

    public void start() {
        load();
        Commands.create(flux, "help").aliases("aide")
                .description("Liens du serveur et raccourcis")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null) open(p);
                    return true;
                })
                .register();
        // command buttons of the pause menu window: the client goes back to the pause menu after a button, so the
        // screen is closed first (a container close packet closes any screen), then the command runs as the player
        Commands.create(flux, "fluxaide")
                .description("Bouton de la fenêtre d'aide : ferme l'écran puis lance la commande")
                .suggest(false)
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p == null || args.length == 0) return true;
                    p.closeInventory();
                    String command = String.join(" ", args);
                    Bukkit.getScheduler().runTask(flux, () -> p.performCommand(command));
                    return true;
                })
                .register();
        // after the worlds are loaded
        Bukkit.getScheduler().runTask(flux, this::writeDataPack);
    }

    public void load() {
        File file = new File(flux.getDataFolder(), "help.yml");
        if (!file.exists()) flux.saveResource("help.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void open(Player player) {
        Dialogs.links(player, config.getString("title", "Help"), config.getStringList("text"), links(), config.getInt("columns", 2));
    }

    private List<Dialogs.Link> links() {
        List<Dialogs.Link> out = new ArrayList<>();
        for (Map<?, ?> m : config.getMapList("buttons")) {
            out.add(new Dialogs.Link(string(m, "label"), string(m, "description"), string(m, "url"), string(m, "command")));
        }
        return out;
    }

    private static String string(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    // ------------------------------------------------------------------ pause menu (data pack)

    /** Writes the data pack when its content changed; the pause menu shows it after the next restart. */
    public void writeDataPack() {
        if (Bukkit.getWorlds().isEmpty()) return;
        File pack = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "datapacks/flux");
        File dialog = new File(pack, "data/" + NAMESPACE + "/dialog/" + DIALOG + ".json");
        File tag = new File(pack, "data/minecraft/tags/dialog/pause_screen_additions.json");
        String dialogJson = GSON.toJson(dialogJson());
        try {
            if (dialog.exists() && Files.readString(dialog.toPath()).equals(dialogJson)) return;
            dialog.getParentFile().mkdirs();
            tag.getParentFile().mkdirs();
            Files.writeString(new File(pack, "pack.mcmeta").toPath(), GSON.toJson(meta(pack.getParentFile())), StandardCharsets.UTF_8);
            Files.writeString(dialog.toPath(), dialogJson, StandardCharsets.UTF_8);
            JsonObject values = new JsonObject();
            JsonArray list = new JsonArray();
            list.add(NAMESPACE + ":" + DIALOG);
            values.add("values", list);
            Files.writeString(tag.toPath(), GSON.toJson(values), StandardCharsets.UTF_8);
            flux.getLogger().info("Help window of the pause menu written in " + pack.getPath() + ": shown after the next restart.");
        } catch (IOException e) {
            flux.getLogger().warning("Could not write the pause menu data pack: " + e.getMessage());
        }
    }

    /** pack.mcmeta with the formats of the server (copied from the data pack Paper keeps for plugins). */
    private static JsonObject meta(File datapacks) {
        JsonObject pack;
        try {
            pack = JsonParser.parseString(Files.readString(new File(datapacks, "bukkit/pack.mcmeta").toPath()))
                    .getAsJsonObject().getAsJsonObject("pack").deepCopy();
        } catch (IOException | RuntimeException e) {
            pack = new JsonObject();
        }
        pack.addProperty("description", "Flux: help window of the pause menu");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    private JsonObject dialogJson() {
        String title = config.getString("title", "Help");
        JsonObject d = new JsonObject();
        d.addProperty("type", "minecraft:multi_action");
        d.add("title", text(Dialogs.color("title") + title));
        d.add("external_title", text(Dialogs.color("title") + config.getString("pause-button", title)));
        JsonArray body = new JsonArray();
        for (String line : config.getStringList("text")) {
            JsonObject b = new JsonObject();
            b.addProperty("type", "minecraft:plain_message");
            b.add("contents", text(Dialogs.color("text") + line));
            b.addProperty("width", 300);
            body.add(b);
        }
        d.add("body", body);
        d.addProperty("can_close_with_escape", true);
        d.addProperty("pause", false);
        d.addProperty("after_action", "close");
        JsonArray actions = new JsonArray();
        for (Dialogs.Link l : links()) {
            JsonObject a = new JsonObject();
            a.add("label", text(Dialogs.color("option") + l.label()));
            if (l.description() != null && !l.description().isBlank()) a.add("tooltip", text(Dialogs.hover(l.description())));
            a.addProperty("width", 150);
            JsonObject action = new JsonObject();
            if (l.url() != null && !l.url().isBlank()) {
                action.addProperty("type", "minecraft:open_url");
                action.addProperty("url", l.url());
            } else {
                action.addProperty("type", "minecraft:run_command");
                action.addProperty("command", "/fluxaide " + (l.command().startsWith("/") ? l.command().substring(1) : l.command()));
            }
            a.add("action", action);
            actions.add(a);
        }
        d.add("actions", actions);
        d.addProperty("columns", Math.max(1, config.getInt("columns", 2)));
        return d;
    }

    private static JsonElement text(String raw) {
        return GsonComponentSerializer.gson().serializeToTree(Texts.parse(raw));
    }
}
