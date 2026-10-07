package fr.influencraft.influpet.gui;

import fr.influencraft.influpet.InfluPet;
import fr.influencraft.influpet.Texts;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Saisie de texte dans le tchat pour le menu d'édition (« annuler » pour abandonner, 60 s max). */
public final class ChatPrompt implements Listener {

    private record Pending(Consumer<String> onAnswer, Runnable onCancel, BukkitTask timeout) {
    }

    private final InfluPet plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(InfluPet plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String question, Consumer<String> onAnswer, Runnable onCancel) {
        Pending old = pending.remove(player.getUniqueId());
        if (old != null) {
            old.timeout().cancel();
        }
        BukkitTask timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) != null && player.isOnline()) {
                plugin.send(player, "prompt-timeout");
            }
        }, 20L * 60);
        pending.put(player.getUniqueId(), new Pending(onAnswer, onCancel, timeout));
        player.sendMessage(Texts.of(plugin.getConfig().getString("messages.prefix", "") + question));
        plugin.send(player, "prompt-hint");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        p.timeout().cancel();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
                plugin.send(event.getPlayer(), "prompt-cancelled");
                p.onCancel().run();
            } else {
                p.onAnswer().accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p != null) {
            p.timeout().cancel();
        }
    }
}
