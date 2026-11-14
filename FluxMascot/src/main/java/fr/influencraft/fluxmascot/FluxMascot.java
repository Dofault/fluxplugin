package fr.influencraft.fluxmascot;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Clients;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * FluxMascot: the welcome robot. It appears in front of one player (nobody else sees it), waves, speaks with a voice
 * coming from where it stands, waves again and walks away. The plugin only knows HOW; who is greeted and when is
 * decided outside (the Typewriter page "robot_accueil" runs the command for first-rank players typing /spawn).
 *
 * <pre>
 *   /fluxmascot greet &lt;player&gt;    the robot visits him (starts over when it is already there)
 *   /fluxmascot stop &lt;player&gt;
 *   /fluxmascot reload
 * </pre>
 * Bedrock players get the lines "bedrock" of the lang file instead: they would see no model and hear no voice.
 */
public final class FluxMascot extends FluxPlugin {

    private Settings settings;
    private Greetings greetings;

    @Override
    protected void enable() {
        settings = Settings.read(getConfig());
        greetings = new Greetings(this);
        Bukkit.getPluginManager().registerEvents(greetings, this);
        command("fluxmascot")
                .description("The welcome robot: make it greet a player, stop it, reload.")
                .permission("fluxmascot.admin")
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 ? List.of("greet", "stop", "reload")
                        : args.length == 2 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of())
                .register();
    }

    @Override
    protected void disable() {
        if (greetings != null) greetings.stopAll();
    }

    @Override
    public void reload() {
        super.reload();
        settings = Settings.read(getConfig());
    }

    Settings settings() {
        return settings;
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (args.length < 2 || !(sub.equals("greet") || sub.equals("stop"))) {
            messages().send(sender, "usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages().send(sender, "unknown-player");
            return true;
        }
        if (sub.equals("stop")) {
            if (greetings.stop(target)) messages().send(sender, "stopped", "%player%", target.getName());
            return true;
        }
        if (Clients.bedrock(target)) {
            messages().send(target, "bedrock");
            return true;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel") || !greetings.start(target)) {
            messages().send(sender, "no-model", "%model%", settings.model());
            return true;
        }
        messages().send(sender, "started", "%player%", target.getName());
        return true;
    }
}
