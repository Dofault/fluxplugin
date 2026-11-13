package fr.influencraft.fluxproxy;

import com.google.inject.Inject;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hides the proxy's commands from the "/" suggestions (velocity, cb, slp, planproxy...), but those listed in
 * {@code commandes.txt}. Velocity merges its commands into every server's command tree, after the server (and
 * Flux's filter) built it, so they can only be removed here. Only suggestions change, permissions still apply.
 * <p>Also keeps Bedrock players (Geyser + Floodgate) on the servers listed in {@code bedrock.txt} (Labs by default):
 * the other servers are not ready for them yet. A Floodgate player's UUID has its upper half at zero.</p>
 */
public final class FluxProxy {

    private final ProxyServer proxy;
    private final Path file;
    private final Path bedrockFile;
    private Map<String, String> allowed = Map.of();
    private long loadedAt = -1;
    private List<String> bedrockServers = List.of("labs");
    private long bedrockLoadedAt = -1;

    @Inject
    public FluxProxy(ProxyServer proxy, @DataDirectory Path folder) {
        this.proxy = proxy;
        this.file = folder.resolve("commandes.txt");
        this.bedrockFile = folder.resolve("bedrock.txt");
    }

    private static boolean bedrock(Player player) {
        return player.getUniqueId().getMostSignificantBits() == 0;
    }

    /** A Bedrock player starts on the first server of bedrock.txt. */
    @Subscribe
    public void onInitial(PlayerChooseInitialServerEvent event) {
        if (!bedrock(event.getPlayer())) return;
        for (String name : bedrockServers()) {
            var server = proxy.getServer(name);
            if (server.isPresent()) {
                event.setInitialServer(server.get());
                return;
            }
        }
        event.setInitialServer(null);
        event.getPlayer().disconnect(Component.text("Bedrock : aucun serveur n'est encore ouvert.", TextColor.color(0xFF5C5C)));
    }

    /** A Bedrock player never reaches a server outside bedrock.txt. */
    @Subscribe
    public void onConnect(ServerPreConnectEvent event) {
        if (!bedrock(event.getPlayer())) return;
        RegisteredServer target = event.getOriginalServer();
        if (bedrockServers().contains(target.getServerInfo().getName().toLowerCase(Locale.ROOT))) return;
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        event.getPlayer().sendMessage(Component.text("Ce serveur n'est pas encore ouvert aux joueurs Bedrock.", TextColor.color(0xFF5C5C)));
    }

    /** Servers open to Bedrock players, one per line (read again when the file changed). */
    private synchronized List<String> bedrockServers() {
        try {
            if (!Files.exists(bedrockFile)) {
                Files.createDirectories(bedrockFile.getParent());
                try (InputStream in = getClass().getResourceAsStream("/bedrock.txt")) {
                    Files.copy(in, bedrockFile);
                }
            }
            long modified = Files.getLastModifiedTime(bedrockFile).toMillis();
            if (modified != bedrockLoadedAt) {
                List<String> read = new ArrayList<>();
                for (String line : Files.readAllLines(bedrockFile)) {
                    line = line.trim().toLowerCase(Locale.ROOT);
                    if (!line.isEmpty() && !line.startsWith("#")) read.add(line);
                }
                bedrockServers = read;
                bedrockLoadedAt = modified;
            }
        } catch (IOException | RuntimeException e) {
            // unreadable list: keep the last one
        }
        return bedrockServers;
    }

    @Subscribe
    public void onCommands(PlayerAvailableCommandsEvent event) {
        Map<String, String> allowed = allowed();
        Player player = event.getPlayer();
        List<String> hidden = new ArrayList<>();
        for (CommandNode<?> node : event.getRootNode().getChildren()) {
            String name = node.getName().toLowerCase(Locale.ROOT);
            if (!proxy.getCommandManager().hasCommand(name)) continue; // a server's command
            String permission = allowed.get(name);
            boolean shown = allowed.containsKey(name) && (permission.isEmpty() || player.hasPermission(permission));
            if (!shown) hidden.add(node.getName());
        }
        hidden.forEach(event.getRootNode()::removeChildByName);
    }

    /** The list, read again when the file changed. */
    private synchronized Map<String, String> allowed() {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = getClass().getResourceAsStream("/commandes.txt")) {
                    Files.copy(in, file);
                }
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified != loadedAt) {
                Map<String, String> read = new HashMap<>();
                for (String line : Files.readAllLines(file)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\s+", 2);
                    read.put(parts[0].toLowerCase(Locale.ROOT).replace("/", ""), parts.length > 1 ? parts[1] : "");
                }
                allowed = read;
                loadedAt = modified;
            }
        } catch (IOException | RuntimeException e) {
            // unreadable list: keep the last one
        }
        return allowed;
    }
}
