package fr.influencraft.fluxoffering.command;

import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.config.MessagesManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.gui.CollectionMenu;
import fr.influencraft.fluxoffering.integration.PlaceholderHook;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.util.AccessUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Player-facing "/offrande" command only. Admin subcommands (create/reset/set/check/reload)
 * live under the separate "/influoffrande" command — kept apart so a collection can never
 * accidentally collide with a subcommand name.
 * <p>
 * There is no "browse every collection" screen: a player only ever reaches a specific
 * collection, either the one matching their current rank (bare "/offrande") or one named
 * explicitly ("/offrande citoyen") — typically one they already completed at a past rank,
 * which stays reachable via {@link AccessUtil} even without the permission for it anymore.
 * <p>
 * Single exception to "first argument = collection id" : "/offrande depot &lt;collection&gt;
 * &lt;montant&gt;", la commande suggeree au clic sur la case argent du menu. Elle existe
 * uniquement pour que le montant soit saisi dans une commande et non dans le tchat : une
 * invite "ecris le montant dans le tchat" laissait le nombre s'afficher publiquement, les
 * plugins de formatage de tchat rediffusant leur propre copie du message avant meme que
 * l'evenement soit annule.
 */
public final class OffrandeCommand implements CommandExecutor {

    public static final String DEPOSIT_SUBCOMMAND = "depot";

    private final ConfigManager configManager;
    private final MessagesManager messages;
    private final DataManager dataManager;
    private final CollectionMenu collectionMenu;

    public OffrandeCommand(ConfigManager configManager, MessagesManager messages, DataManager dataManager,
                            CollectionMenu collectionMenu) {
        this.configManager = configManager;
        this.messages = messages;
        this.dataManager = dataManager;
        this.collectionMenu = collectionMenu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            openByRank(sender);
        } else if (args.length >= 2 && args[0].equalsIgnoreCase(DEPOSIT_SUBCOMMAND)) {
            depositMoney(sender, args);
        } else {
            openCollection(sender, args[0]);
        }
        return true;
    }

    /**
     * Bare "/offrande" is equivalent to "/offrande <grade du joueur>" — never a generic list.
     */
    private void openByRank(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.players-only", null);
            return;
        }
        // Track "standard" rather than the primary group: a staff group (helper, moderateur...)
        // outweighs the rank, so the primary group would resolve to the staff group instead.
        String group = PlaceholderHook.apply("%luckperms_current_group_on_track_standard%", player);
        if (group == null || group.isBlank() || group.contains("%")) {
            messages.send(player, "general.rank-unresolved", player);
            return;
        }
        openCollection(sender, group);
    }

    /**
     * "/offrande depot &lt;collection&gt; &lt;montant&gt;" — depot d'argent, normalement
     * atteint en cliquant sur la commande suggeree dans le tchat plutot qu'en la tapant.
     */
    private void depositMoney(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.players-only", null);
            return;
        }
        if (!player.hasPermission("offrandes.use")) {
            messages.send(player, "general.no-permission", player);
            return;
        }
        CollectionDefinition collection = configManager.getCollection(args[1]);
        if (collection == null) {
            messages.send(player, "general.unknown-collection", Map.of("collection", args[1]), player);
            return;
        }
        if (!AccessUtil.hasAccess(player, collection, dataManager)) {
            messages.send(player, "general.no-access-collection", Map.of("collection", collection.displayName()), player);
            return;
        }
        if (!collection.hasMoneyRequirement()) {
            messages.send(player, "gui.money-not-required", player);
            return;
        }
        if (args.length < 3) {
            messages.send(player, "gui.money-usage", Map.of("collection", collection.id()), player);
            return;
        }
        long amount;
        try {
            // Le montant arrive d'un clic sur une commande suggeree, donc tel que le joueur
            // l'a tape : on tolere les espaces de milliers ("1 000 000") comme le faisait
            // l'ancienne invite de tchat.
            amount = Long.parseLong(String.join("", java.util.Arrays.copyOfRange(args, 2, args.length)));
        } catch (NumberFormatException ex) {
            messages.send(player, "gui.money-invalid", player);
            return;
        }
        if (amount <= 0) {
            messages.send(player, "gui.money-invalid", player);
            return;
        }
        collectionMenu.handleMoneyCommandDeposit(player, collection, amount);
    }

    private void openCollection(CommandSender sender, String collectionId) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.players-only", null);
            return;
        }
        if (!player.hasPermission("offrandes.use")) {
            messages.send(player, "general.no-permission", player);
            return;
        }
        CollectionDefinition collection = configManager.getCollection(collectionId);
        if (collection == null) {
            messages.send(player, "general.unknown-collection", Map.of("collection", collectionId), player);
            return;
        }
        if (!AccessUtil.hasAccess(player, collection, dataManager)) {
            messages.send(player, "general.no-access-collection", Map.of("collection", collection.displayName()), player);
            return;
        }
        collectionMenu.open(player, collection, false);
    }
}
