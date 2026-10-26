package fr.influencraft.influoffrande.command;

import fr.influencraft.influoffrande.config.ConfigManager;
import fr.influencraft.influoffrande.config.MessagesManager;
import fr.influencraft.influoffrande.data.DataManager;
import fr.influencraft.influoffrande.model.CollectionDefinition;
import fr.influencraft.influoffrande.model.ItemKey;
import fr.influencraft.influoffrande.model.PlayerData;
import fr.influencraft.influoffrande.util.AmountFormatter;
import fr.influencraft.influoffrande.util.ProgressCalculator;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin-only "/influoffrande" command (reset/set/check/reload). Kept separate from the
 * player-facing "/offrande" so a collection can never be named "reset", "set", "check" or
 * "reload" and become unreachable — and so players never see admin subcommands autocomplete.
 */
public final class InfluOffrandeAdminCommand implements CommandExecutor {

    private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9_-]+");

    private final ConfigManager configManager;
    private final MessagesManager messages;
    private final DataManager dataManager;

    public InfluOffrandeAdminCommand(ConfigManager configManager, MessagesManager messages, DataManager dataManager) {
        this.configManager = configManager;
        this.messages = messages;
        this.dataManager = dataManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "admin.usage-root", sender instanceof Player p ? p : null);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "reset" -> handleReset(sender, args);
            case "set" -> handleSet(sender, args);
            case "money" -> handleMoney(sender, args);
            case "check" -> handleCheck(sender, args);
            case "create" -> handleCreate(sender, args);
            case "permission" -> handlePermission(sender, args);
            case "rename" -> handleRename(sender, args);
            case "remove" -> handleRemove(sender, args);
            default -> messages.send(sender, "admin.usage-root", sender instanceof Player p ? p : null);
        }
        return true;
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.send(sender, "admin.usage-remove", sender instanceof Player p ? p : null);
            return;
        }
        CollectionDefinition collection = configManager.getCollection(args[1]);
        if (collection == null) {
            messages.send(sender, "general.unknown-collection", Map.of("collection", args[1]), sender instanceof Player p ? p : null);
            return;
        }
        configManager.collections().remove(args[1]);
        configManager.saveCollections();

        messages.send(sender, "admin.remove-success", Map.of("collection", args[1]), sender instanceof Player p ? p : null);
    }

    private void handleRename(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "admin.usage-rename", sender instanceof Player p ? p : null);
            return;
        }
        CollectionDefinition collection = configManager.getCollection(args[1]);
        if (collection == null) {
            messages.send(sender, "general.unknown-collection", Map.of("collection", args[1]), sender instanceof Player p ? p : null);
            return;
        }
        String newName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        collection.displayName(newName);
        configManager.saveCollections();

        messages.send(sender, "admin.rename-success", Map.of("collection", args[1], "name", newName), sender instanceof Player p ? p : null);
    }

    private void handlePermission(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages.send(sender, "admin.usage-permission", sender instanceof Player p ? p : null);
            return;
        }
        CollectionDefinition collection = configManager.getCollection(args[1]);
        if (collection == null) {
            messages.send(sender, "general.unknown-collection", Map.of("collection", args[1]), sender instanceof Player p ? p : null);
            return;
        }
        boolean clear = args[2].equalsIgnoreCase("clear") || args[2].equalsIgnoreCase("none") || args[2].equals("-");
        collection.permission(clear ? "" : args[2]);
        configManager.saveCollections();

        Map<String, String> ph = Map.of(
                "collection", collection.displayName(),
                "permission", clear ? "-" : args[2]
        );
        messages.send(sender, clear ? "admin.permission-cleared" : "admin.permission-set", ph, sender instanceof Player p ? p : null);
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin.usage-create", sender instanceof Player p ? p : null);
            return;
        }
        String id = args[1];
        if (!VALID_ID.matcher(id).matches()) {
            messages.send(sender, "admin.invalid-collection-id", Map.of("collection", id), sender instanceof Player p ? p : null);
            return;
        }
        if (configManager.getCollection(id) != null) {
            messages.send(sender, "admin.collection-already-exists", Map.of("collection", id), sender instanceof Player p ? p : null);
            return;
        }

        String displayName = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "&#FFD94D" + id;

        CollectionDefinition collection = new CollectionDefinition(id);
        collection.displayName(displayName);
        collection.permission("");
        configManager.collections().put(id, collection);
        configManager.saveCollections();

        messages.send(sender, "admin.create-success", Map.of("collection", id), sender instanceof Player p ? p : null);
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("offrandes.admin")) {
            return true;
        }
        messages.send(sender, "general.no-permission", sender instanceof Player p ? p : null);
        return false;
    }

    private void handleReload(CommandSender sender) {
        configManager.reload();
        messages.load();
        messages.send(sender, "admin.reload-success", sender instanceof Player p ? p : null);
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages.send(sender, "admin.usage-reset", sender instanceof Player p ? p : null);
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[1]);
        if (target == null) {
            return;
        }
        PlayerData data = dataManager.getOffline(target);
        String collectionArg = args[2];
        Map<String, String> ph = Map.of("player", displayName(target), "collection", collectionArg);

        if (collectionArg.equalsIgnoreCase("all")) {
            data.resetAll();
            dataManager.saveOffline(data);
            messages.send(sender, "admin.reset-all-success", ph, sender instanceof Player p ? p : null);
            return;
        }
        CollectionDefinition collection = configManager.getCollection(collectionArg);
        if (collection == null) {
            messages.send(sender, "general.unknown-collection", Map.of("collection", collectionArg), sender instanceof Player p ? p : null);
            return;
        }
        data.resetCollection(collection.id());
        dataManager.saveOffline(data);
        messages.send(sender, "admin.reset-success", Map.of("player", displayName(target), "collection", collection.displayName()),
                sender instanceof Player p ? p : null);
    }

    /**
     * "/influoffrande money <collection> <montant>" — sets the BitBlock requirement
     * directly (0 clears it), the command-line equivalent of the in-game editor's money
     * button. Handy to seed the 11 rank offrandes from a script without going through chat.
     */
    private void handleMoney(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages.send(sender, "admin.usage-money", sender instanceof Player p ? p : null);
            return;
        }
        CollectionDefinition collection = configManager.getCollection(args[1]);
        if (collection == null) {
            messages.send(sender, "general.unknown-collection", Map.of("collection", args[1]), sender instanceof Player p ? p : null);
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            messages.send(sender, "admin.invalid-number", sender instanceof Player p ? p : null);
            return;
        }
        collection.money(Math.max(0, amount));
        configManager.saveCollections();

        Map<String, String> ph = Map.of("collection", collection.displayName(), "amount", AmountFormatter.grouped(collection.money()));
        messages.send(sender, "admin.money-set", ph, sender instanceof Player p ? p : null);
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length != 5) {
            messages.send(sender, "admin.usage-set", sender instanceof Player p ? p : null);
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[1]);
        if (target == null) {
            return;
        }
        CollectionDefinition collection = configManager.getCollection(args[2]);
        if (collection == null) {
            messages.send(sender, "general.unknown-collection", Map.of("collection", args[2]), sender instanceof Player p ? p : null);
            return;
        }
        if (args[3].equalsIgnoreCase("money")) {
            handleSetMoney(sender, target, collection, args[4]);
            return;
        }
        ItemKey key;
        try {
            key = ItemKey.parse(args[3]);
        } catch (IllegalArgumentException ex) {
            messages.send(sender, "admin.unknown-item", Map.of("item", args[3]), sender instanceof Player p ? p : null);
            return;
        }
        Integer required = collection.items().get(key);
        if (required == null) {
            messages.send(sender, "admin.item-not-in-collection", Map.of("item", args[3], "collection", collection.displayName()),
                    sender instanceof Player p ? p : null);
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            messages.send(sender, "admin.invalid-number", sender instanceof Player p ? p : null);
            return;
        }
        int clamped = Math.max(0, Math.min(amount, required));

        PlayerData data = dataManager.getOffline(target);
        data.setDeposited(collection.id(), key, clamped);
        dataManager.saveOffline(data);

        Map<String, String> ph = new HashMap<>();
        ph.put("player", displayName(target));
        ph.put("collection", collection.displayName());
        ph.put("item", collection.displayNameFor(key));
        ph.put("amount", String.valueOf(clamped));
        ph.put("required", String.valueOf(required));
        messages.send(sender, "admin.set-success", ph, sender instanceof Player p ? p : null);
    }

    /**
     * "/influoffrande set <joueur> <collection> money <montant>" — forces a player's
     * deposited BitBlock amount, mirroring the item-forcing branch of {@link #handleSet}.
     */
    private void handleSetMoney(CommandSender sender, OfflinePlayer target, CollectionDefinition collection, String rawAmount) {
        long amount;
        try {
            amount = Long.parseLong(rawAmount);
        } catch (NumberFormatException ex) {
            messages.send(sender, "admin.invalid-number", sender instanceof Player p ? p : null);
            return;
        }
        long clamped = Math.max(0, Math.min(amount, collection.money()));

        PlayerData data = dataManager.getOffline(target);
        data.setMoneyDeposited(collection.id(), clamped);
        dataManager.saveOffline(data);

        Map<String, String> ph = new HashMap<>();
        ph.put("player", displayName(target));
        ph.put("collection", collection.displayName());
        ph.put("item", "Argent");
        ph.put("amount", AmountFormatter.grouped(clamped));
        ph.put("required", AmountFormatter.grouped(collection.money()));
        messages.send(sender, "admin.set-success", ph, sender instanceof Player p ? p : null);
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin.usage-check", sender instanceof Player p ? p : null);
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[1]);
        if (target == null) {
            return;
        }
        PlayerData data = dataManager.getOffline(target);
        Player ctx = sender instanceof Player p ? p : null;

        if (args.length >= 3) {
            CollectionDefinition collection = configManager.getCollection(args[2]);
            if (collection == null) {
                messages.send(sender, "general.unknown-collection", Map.of("collection", args[2]), ctx);
                return;
            }
            messages.send(sender, "admin.check-header", Map.of("player", displayName(target), "collection", collection.displayName()), ctx);
            for (var entry : collection.items().entrySet()) {
                int deposited = Math.min(entry.getValue(), data.getDeposited(collection.id(), entry.getKey()));
                Map<String, String> ph = Map.of(
                        "item", collection.displayNameFor(entry.getKey()),
                        "deposited", String.valueOf(deposited),
                        "required", String.valueOf(entry.getValue())
                );
                messages.send(sender, "admin.check-line", ph, ctx);
            }
            if (collection.hasMoneyRequirement()) {
                long deposited = Math.min(collection.money(), data.getMoneyDeposited(collection.id()));
                Map<String, String> ph = Map.of(
                        "item", "Argent",
                        "deposited", AmountFormatter.grouped(deposited),
                        "required", AmountFormatter.grouped(collection.money())
                );
                messages.send(sender, "admin.check-line", ph, ctx);
            }
            return;
        }

        messages.send(sender, "admin.check-header-all", Map.of("player", displayName(target)), ctx);
        for (CollectionDefinition collection : configManager.collections().values()) {
            Map<String, String> ph = Map.of(
                    "collection", collection.displayName(),
                    "percent", String.valueOf(ProgressCalculator.percent(data, collection)),
                    "progress", ProgressCalculator.completedTypeCount(data, collection) + "/" + ProgressCalculator.totalTypesWithMoney(collection)
            );
            messages.send(sender, "admin.check-collection-line", ph, ctx);
        }
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer resolvePlayer(CommandSender sender, String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            messages.send(sender, "admin.unknown-player", Map.of("player", name), sender instanceof Player p ? p : null);
            return null;
        }
        return target;
    }

    private String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString();
    }
}
