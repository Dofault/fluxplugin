package fr.influencraft.fluxtopdown;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.client.Clients;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * FluxTopDown (experiment): a hack and slash played from above, in the spirit of action rpgs. The camera hangs over
 * the player (Flux's {@link Camera}), the mouse moves a pointer, a click walks there or attacks the monster under it,
 * keys 1 to 4 aim a spell whose zone shows on the ground and the left click casts it. Fights go through
 * FluxDungeons' rpg combat engine; "/hackslash play" starts a dungeon run and turns the view on inside it.
 *
 * <pre>
 *   /hackslash              view on or off, where you stand
 *   /hackslash play [id]    start a dungeon (config "dungeon" by default), the view turns on inside
 *   /hackslash reload
 * </pre>
 * Java only: Bedrock has no camera packet.
 */
public final class FluxTopDown extends FluxPlugin {

    private Settings settings;
    private Sessions sessions;

    @Override
    protected void enable() {
        settings = Settings.read(getConfig());
        sessions = new Sessions(this);
        Bukkit.getPluginManager().registerEvents(sessions, this);
        command("hackslash")
                .description("Hack and slash view from above (experiment): on, off, or play a dungeon with it.")
                .permission("fluxtopdown.use")
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 ? List.of("jouer", "on", "off", "debug", "reload") : List.of())
                .register();
    }

    @Override
    protected void disable() {
        if (sessions != null) sessions.stopAll();
    }

    @Override
    public void reload() {
        super.reload();
        settings = Settings.read(getConfig());
    }

    Settings settings() {
        return settings;
    }

    Sessions sessions() {
        return sessions;
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player p)) {
            messages().send(sender, "usage");
            return true;
        }
        boolean on = sessions.active(p);
        switch (sub) {
            case "" -> {
                if (on) sessions.cancel(p);
                else if (allowed(p)) sessions.start(p);
            }
            case "on" -> {
                if (!on && allowed(p)) sessions.start(p);
            }
            case "off" -> sessions.cancel(p);
            case "debug" -> messages().send(p, sessions.toggleDebug(p) ? "debug-on" : "debug-off");
            case "jouer", "play" -> {
                if (allowed(p)) sessions.play(p, args.length > 1 ? args[1] : settings.dungeon());
            }
            default -> messages().send(p, "usage");
        }
        return true;
    }

    private boolean allowed(Player p) {
        if (Clients.bedrock(p)) {
            messages().send(p, "bedrock");
            return false;
        }
        if (!Camera.available()) {
            messages().send(p, "unavailable");
            return false;
        }
        return true;
    }
}
