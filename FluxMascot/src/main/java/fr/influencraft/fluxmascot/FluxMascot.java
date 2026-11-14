package fr.influencraft.fluxmascot;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Clients;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * FluxMascot: the welcome robot. Each player has his own (nobody else sees it): it appears in front of him, waves,
 * speaks with a voice coming from where it stands, then strolls on the town's streets like an inhabitant; a right
 * click makes it speak again. The plugin only knows HOW; who has a robot and when is decided outside (the Typewriter
 * page "robot_accueil" runs the commands for first-rank players).
 *
 * <pre>
 *   /fluxmascot greet &lt;player&gt;    the robot comes in front of him and speaks (starts over when it is already there)
 *   /fluxmascot roam &lt;player&gt;     the robot strolls near him without speech (nothing when he already has it)
 *   /fluxmascot stop &lt;player&gt;     his robot goes away
 *   /fluxmascot reload
 * </pre>
 * Bedrock players get the lines "bedrock" of the lang file instead: they would see no model and hear no voice.
 */
public final class FluxMascot extends FluxPlugin {

    private Settings settings;
    private Visits greetings;
    private Network network;

    @Override
    protected void enable() {
        settings = Settings.read(getConfig());
        loadNetwork();
        greetings = new Visits(this);
        Bukkit.getPluginManager().registerEvents(greetings, this);
        command("fluxmascot")
                .description("The welcome robot: make it greet a player, stop it, reload.")
                .permission("fluxmascot.admin")
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 ? List.of("greet", "roam", "stop", "reload")
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
        loadNetwork();
    }

    Settings settings() {
        return settings;
    }

    Network network() {
        return network;
    }

    private void loadNetwork() {
        try {
            network = Network.load(new java.io.File(getDataFolder(), settings.network()));
        } catch (java.io.IOException | RuntimeException e) {
            getLogger().warning("Street network " + settings.network() + " unreadable: " + e);
            try {
                network = Network.load(new java.io.File(getDataFolder(), ".none"));
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        if (settings.roam() && network.empty()) {
            getLogger().warning("No street network (" + settings.network() + "): the robot leaves after its speech.");
        }
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (args.length < 2 || !(sub.equals("greet") || sub.equals("roam") || sub.equals("stop"))) {
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
            if (sub.equals("greet")) messages().send(target, "bedrock");
            return true;
        }
        if (sub.equals("roam")) {                                  // silent when it cannot: no streets in this world
            if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) greetings.stroll(target);
            return true;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel") || !greetings.greet(target)) {
            messages().send(sender, "no-model", "%model%", settings.model());
            return true;
        }
        messages().send(sender, "started", "%player%", target.getName());
        return true;
    }
}
