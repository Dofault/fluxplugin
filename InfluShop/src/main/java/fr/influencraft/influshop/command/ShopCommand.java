package fr.influencraft.influshop.command;

import fr.influencraft.influshop.gui.ShopMenu;
import fr.influencraft.influshop.model.SortMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShopCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final ShopMenu shopMenu;

    public ShopCommand(JavaPlugin plugin, ShopMenu shopMenu) {
        this.plugin = plugin;
        this.shopMenu = shopMenu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande reservee aux joueurs.");
            return true;
        }
        if (!player.hasPermission("influshop.use")) {
            return true;
        }
        // Deferred by a tick: this command is also triggered from inside a DeluxeMenus click
        // handler (its own inventory still technically "closing" that same tick) — opening our
        // inventory synchronously in that case silently failed to show up client-side.
        plugin.getServer().getScheduler().runTask(plugin, () -> shopMenu.openFresh(player, 0, SortMode.ALL, false));
        return true;
    }
}
