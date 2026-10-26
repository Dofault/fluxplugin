package fr.influencraft.influshop.command;

import fr.influencraft.influshop.config.ConfigManager;
import fr.influencraft.influshop.config.MessagesManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ShopAdminCommand implements CommandExecutor {

    private final ConfigManager configManager;
    private final MessagesManager messages;

    public ShopAdminCommand(ConfigManager configManager, MessagesManager messages) {
        this.configManager = configManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("influshop.admin")) {
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("Usage: /influshop reload");
            return true;
        }
        configManager.reload();
        messages.load();
        Player asPlayer = sender instanceof Player p ? p : null;
        messages.send(sender, "shop.reload-success", asPlayer);
        return true;
    }
}
