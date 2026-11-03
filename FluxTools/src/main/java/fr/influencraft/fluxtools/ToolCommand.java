package fr.influencraft.fluxtools;

import fr.influencraft.flux.api.items.ItemLooks;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * {@code /tools} (admin): {@code reload}, and for the tool in hand {@code info}, {@code xp <n>}. Tools are given by
 * {@code /fluxitems give <player> fluxtools:<tool>}, like every item of the suite.
 */
final class ToolCommand {

    private static final List<String> SUBCOMMANDS = List.of("reload", "info", "xp");

    private final FluxTools plugin;

    ToolCommand(FluxTools plugin) {
        this.plugin = plugin;
    }

    boolean run(CommandSender sender, String label, String[] args) {
        switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.loadTools();
                ItemLooks.refreshOnline();
                plugin.messages().send(sender, "reloaded", "%count%", String.valueOf(plugin.tools().size()));
            }
            case "info", "xp" -> held(sender, args);
            default -> plugin.messages().send(sender, "usage");
        }
        return true;
    }

    private void held(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        Tool tool = plugin.tool(item);
        if (tool == null) {
            plugin.messages().send(player, "no-tool");
            return;
        }
        ToolState state = ToolState.read(item);
        if (args[0].equalsIgnoreCase("info")) {
            plugin.messages().send(player, "info", "%tool%", tool.id, "%uid%", state.uid,
                    "%level%", String.valueOf(tool.level(state.xp)), "%xp%", Format.number(state.xp),
                    "%blocks%", Format.number(state.blocks), "%kills%", Format.number(state.kills));
            return;
        }
        double xp;
        try {
            xp = args.length > 1 ? Double.parseDouble(args[1]) : 0;
        } catch (NumberFormatException e) {
            plugin.messages().send(player, "usage");
            return;
        }
        boolean enchants = tool.hasLevels() && plugin.progress().gain(player, tool, state, xp);
        plugin.renderer().update(item, tool, state, enchants);
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
