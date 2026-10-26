package fr.influencraft.fluxoffering.command;

import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.ItemKey;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class FluxOfferingAdminTabCompleter implements TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reset", "set", "money", "check", "reload", "create", "permission", "rename", "remove");

    private final ConfigManager configManager;

    public FluxOfferingAdminTabCompleter(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("offrandes.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return TabCompleteUtil.filter(SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2 && (sub.equals("reset") || sub.equals("set") || sub.equals("check"))) {
            return TabCompleteUtil.filter(onlinePlayerNames(), args[1]);
        }
        if (args.length == 3 && sub.equals("reset")) {
            List<String> options = new ArrayList<>(configManager.collections().keySet());
            options.add("all");
            return TabCompleteUtil.filter(options, args[2]);
        }
        if (args.length == 2 && (sub.equals("permission") || sub.equals("rename") || sub.equals("remove") || sub.equals("money"))) {
            return TabCompleteUtil.filter(new ArrayList<>(configManager.collections().keySet()), args[1]);
        }
        if (args.length == 3 && (sub.equals("set") || sub.equals("check"))) {
            return TabCompleteUtil.filter(new ArrayList<>(configManager.collections().keySet()), args[2]);
        }
        if (args.length == 3 && sub.equals("permission")) {
            return TabCompleteUtil.filter(List.of("clear"), args[2]);
        }
        if (args.length == 4 && sub.equals("set")) {
            CollectionDefinition collection = configManager.getCollection(args[2]);
            if (collection == null) {
                return List.of();
            }
            List<String> options = new ArrayList<>();
            if (collection.hasMoneyRequirement()) {
                options.add("money");
            }
            for (ItemKey key : collection.items().keySet()) {
                options.add(key.raw());
            }
            return TabCompleteUtil.filter(options, args[3]);
        }
        return List.of();
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }
}
