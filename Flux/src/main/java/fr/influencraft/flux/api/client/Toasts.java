package fr.influencraft.flux.api.client;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The game's own notification sliding in at the top right (an advancement toast): an item and a line of text, shown
 * while the player keeps playing. Made from a hidden advancement loaded for the moment, granted to the players, then
 * removed: nothing stays in their advancements. The header of the toast is the game's ("Goal Reached!" for
 * {@link Frame#GOAL}), only the line under it is ours.
 *
 * <pre>
 *   Toasts.show(plugin, players, Material.IRON_ORE, Component.text("Rush: iron ore"), Toasts.Frame.GOAL);
 * </pre>
 * Bedrock players get it through Geyser as the client allows (a plain message at worst): tell important things in
 * the chat as well.
 */
public final class Toasts {

    /** Look and header of the toast: task (yellow), goal (rounded), challenge (purple, with its fanfare). */
    public enum Frame { TASK, GOAL, CHALLENGE }

    private static final AtomicInteger NEXT = new AtomicInteger();

    private Toasts() {
    }

    /** Shows a toast to every player given; false when the server refused the advancement. Main thread. */
    public static boolean show(Plugin owner, Collection<? extends Player> players, Material icon, Component title, Frame frame) {
        if (players.isEmpty()) return true;
        NamespacedKey key = new NamespacedKey(owner, "toast_" + NEXT.incrementAndGet());
        JsonObject display = new JsonObject();
        JsonObject item = new JsonObject();
        item.addProperty("id", (icon == null ? Material.PAPER : icon).getKey().toString());
        display.add("icon", item);
        display.add("title", GsonComponentSerializer.gson().serializeToTree(title));
        display.add("description", GsonComponentSerializer.gson().serializeToTree(Component.empty()));
        display.addProperty("frame", (frame == null ? Frame.GOAL : frame).name().toLowerCase(Locale.ROOT));
        display.addProperty("show_toast", true);
        display.addProperty("announce_to_chat", false);
        display.addProperty("hidden", true);
        JsonObject criteria = new JsonObject();
        JsonObject impossible = new JsonObject();
        impossible.addProperty("trigger", "minecraft:impossible");
        criteria.add("shown", impossible);
        JsonObject json = new JsonObject();
        json.add("criteria", criteria);
        json.add("display", display);
        Advancement advancement;
        try {
            advancement = Bukkit.getUnsafe().loadAdvancement(key, json.toString());
        } catch (RuntimeException e) {
            owner.getLogger().warning("Toast not shown: " + e.getMessage());
            return false;
        }
        if (advancement == null) return false;
        List<? extends Player> to = List.copyOf(players);
        for (Player p : to) if (p.isOnline()) p.getAdvancementProgress(advancement).awardCriteria("shown");
        // gone again once the toast is on its way: nobody keeps it, nothing is left on the server
        Bukkit.getScheduler().runTaskLater(owner, () -> {
            for (Player p : to) if (p.isOnline()) p.getAdvancementProgress(advancement).revokeCriteria("shown");
            Bukkit.getUnsafe().removeAdvancement(key);
        }, 10L);
        return true;
    }
}
