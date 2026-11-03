package fr.influencraft.flux.items;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /fluxitems}: every item of the suite, whatever plugin defines it.
 * <pre>
 *   give &lt;player&gt; &lt;id&gt; [amount]   a bare id works when it is unique
 *   list [filter]                  the known ids
 *   id                             id of the item in hand
 * </pre>
 */
public final class ItemsCommand {

    private final Supplier<Messages> messages;

    public ItemsCommand(Supplier<Messages> messages) {
        this.messages = messages;
    }

    public boolean run(CommandSender sender, String label, String[] args) {
        switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "list" -> list(sender, args);
            case "id" -> held(sender);
            default -> messages.get().send(sender, "items-usage");
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.get().send(sender, "items-usage");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            messages.get().send(sender, "items-no-player", "%player%", args[1]);
            return;
        }
        int amount = 1;
        if (args.length > 3) {
            try {
                amount = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException e) {
                messages.get().send(sender, "items-usage");
                return;
            }
        }
        String id = CustomItems.resolve(args[2]);
        if (id == null || !CustomItems.give(player, id, amount)) {
            messages.get().send(sender, "items-unknown", "%id%", args[2]);
            return;
        }
        messages.get().send(sender, "items-given", "%player%", player.getName(), "%id%", id, "%amount%", String.valueOf(amount));
    }

    private void list(CommandSender sender, String[] args) {
        String filter = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        List<String> ids = CustomItems.ids().stream().filter(id -> id.contains(filter)).sorted().toList();
        messages.get().send(sender, "items-list", "%count%", String.valueOf(ids.size()), "%ids%", String.join(", ", ids));
    }

    private void held(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "items-usage");
            return;
        }
        String id = CustomItems.id(player.getInventory().getItemInMainHand());
        if (id == null) messages.get().send(sender, "items-held-none");
        else messages.get().send(sender, "items-held", "%id%", id);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("give", "list", "id"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(CustomItems.ids());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains(last)).limit(100).toList();
    }
}
