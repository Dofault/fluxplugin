package fr.influencraft.influbridge;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le client Minecraft envoie un paquet ADVANCEMENTS aussi bien à l'ouverture
 * qu'à la fermeture de l'écran de succès vanilla (touche L), sans exposer
 * facilement l'action (OPENED_TAB / CLOSED_SCREEN) via l'API générique de
 * ProtocolLib pour ce paquet précis. On suit donc un état ouvert/fermé par
 * joueur et on ne redirige que sur la transition fermé → ouvert.
 * <p>
 * Dès la redirection, on émet un {@link PlayerCommandPreprocessEvent} pour
 * {@code command} avant de le dispatcher — c'est exactement le chemin que
 * prend le serveur quand le joueur tape la commande dans le chat
 * (CraftPlayer#chat), donc tout plugin qui écoute cet event (dont Typewriter,
 * cf. son entry "on command run" branchée sur ce même event) le voit comme
 * une commande réellement tapée par le joueur, cancellation comprise. Un
 * {@code Bukkit.dispatchCommand(player, command)} direct court-circuitait cet
 * event et le rendait invisible à ces plugins. Le dispatch final se fait en
 * tant que joueur : le client remplace l'écran actuellement affiché dès
 * qu'il reçoit le paquet d'ouverture d'inventaire déclenché par cette
 * commande (ex: /menu via DeluxeMenus).
 * <p>
 * Best-effort : jamais testé en jeu sur ce serveur avant déploiement.
 */
public final class AdvancementRedirectListener extends PacketAdapter {

    private final String command;
    private final Set<UUID> openState = ConcurrentHashMap.newKeySet();

    public AdvancementRedirectListener(JavaPlugin plugin, String command) {
        super(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.ADVANCEMENTS);
        this.command = command;
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean justOpened = openState.add(uuid);
        if (!justOpened) {
            // était déjà ouvert -> ce paquet correspond à la fermeture
            openState.remove(uuid);
            return;
        }

        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            PlayerCommandPreprocessEvent preprocessEvent =
                    new PlayerCommandPreprocessEvent(player, "/" + command);
            Bukkit.getPluginManager().callEvent(preprocessEvent);
            if (!preprocessEvent.isCancelled()) {
                Bukkit.dispatchCommand(player, preprocessEvent.getMessage().substring(1));
            }
        });
    }
}
