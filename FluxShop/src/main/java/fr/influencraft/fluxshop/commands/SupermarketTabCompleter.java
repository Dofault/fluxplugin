package fr.influencraft.fluxshop.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class SupermarketTabCompleter implements TabCompleter {

    private final CommandManager commandManager;

    public SupermarketTabCompleter(CommandManager commandManager) {
        this.commandManager = commandManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(commandManager.getCommandNames().stream(), args[0]);
        }

        String name = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (name) {
                case "setprice" -> materials(args[1]);
                case "tpshop", "autosell" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), args[1]);
                default -> Collections.emptyList();
            };
        }
        return Collections.emptyList();
    }

    private static List<String> materials(String current) {
        return filter(Arrays.stream(Material.values()).filter(Material::isItem).map(Material::name), current);
    }

    private static List<String> filter(Stream<String> values, String current) {
        String prefix = current.toLowerCase();
        return values.filter(value -> value.toLowerCase().startsWith(prefix)).sorted().toList();
    }
}
