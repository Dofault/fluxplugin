package fr.influencraft.fluxoffering.command;

import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.util.AccessUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * "/offrande &lt;collection&gt;" : le 1er argument est un id de collection (celles accessibles
 * ou deja terminees pour un joueur, toutes pour un admin). Seule exception, la sous-commande
 * "depot", qui decale la collection en 2e argument — elle n'est pas suggeree au 1er rang,
 * elle n'est pas faite pour etre tapee a la main mais cliquee depuis le menu.
 */
public final class OffrandeTabCompleter implements TabCompleter {

    private final ConfigManager configManager;
    private final DataManager dataManager;

    public OffrandeTabCompleter(ConfigManager configManager, DataManager dataManager) {
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean deposit = args.length >= 2 && args[0].equalsIgnoreCase(OffrandeCommand.DEPOSIT_SUBCOMMAND);
        if (args.length != 1 && !(deposit && args.length == 2)) {
            return List.of();
        }
        String current = deposit ? args[1] : args[0];
        if (!deposit && OffrandeCommand.SIDEBAR_SUBCOMMAND.startsWith(current.toLowerCase(java.util.Locale.ROOT))) {
            List<String> withSidebar = new ArrayList<>(List.of(OffrandeCommand.SIDEBAR_SUBCOMMAND));
            for (CollectionDefinition c : configManager.collections().values()) {
                if (c.id().startsWith(current.toLowerCase(java.util.Locale.ROOT))) withSidebar.add(c.id());
            }
            return withSidebar;
        }
        boolean admin = sender.hasPermission("fluxoffering.admin");
        List<String> options = new ArrayList<>();
        for (CollectionDefinition collection : configManager.collections().values()) {
            if (admin || !(sender instanceof Player player) || AccessUtil.hasAccess(player, collection, dataManager)) {
                options.add(collection.id());
            }
        }
        return TabCompleteUtil.filter(options, current);
    }
}
