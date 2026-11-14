package fr.influencraft.flux.api.client;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's chat kept off his screen while something else needs it (FluxDungeons' class showcase): what is already
 * shown is pushed out, the messages sent to him meanwhile are held back by Flux and given to him when the hold ends
 * (the last {@value #KEPT} of them), so nothing is lost. Action bar texts still show. Thread-safe.
 */
public final class ChatHold {

    private static final int KEPT = 100;
    /** Lines of chat a client shows while its chat is closed, at most. */
    private static final int SHOWN = 10;
    private static final Map<UUID, List<Component>> HELD = new ConcurrentHashMap<>();

    private ChatHold() {
    }

    /** Starts ({@code true}) or ends the hold; ending it sends him what he missed. */
    public static void hold(Player player, boolean held) {
        UUID id = player.getUniqueId();
        if (held) {
            if (HELD.containsKey(id)) return;
            for (int i = 0; i < SHOWN; i++) player.sendMessage(Component.empty());
            HELD.put(id, new ArrayList<>());
            return;
        }
        List<Component> missed = HELD.remove(id);
        if (missed == null || !player.isOnline()) return;
        synchronized (missed) {
            for (Component c : missed) player.sendMessage(c);
        }
    }

    public static boolean held(UUID player) {
        return HELD.containsKey(player);
    }

    /** Called by the adapter with a message it held back. */
    public static void keep(UUID player, Component message) {
        List<Component> list = HELD.get(player);
        if (list == null || message == null) return;
        synchronized (list) {
            if (list.size() >= KEPT) list.remove(0);
            list.add(message);
        }
    }

    /** Called by Flux when a player leaves. */
    public static void forget(UUID player) {
        HELD.remove(player);
    }
}
