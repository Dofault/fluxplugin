package io.dofault.supermarket.commands;

import io.dofault.supermarket.autosell.AutoSellItemManager;
import io.dofault.supermarket.managers.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class AutoSellCommand implements SupermarketCommand {

    private final LangManager lang;
    private final AutoSellItemManager autoSellItemManager;

    public AutoSellCommand(LangManager lang, AutoSellItemManager autoSellItemManager) {
        this.lang = lang;
        this.autoSellItemManager = autoSellItemManager;
    }

    @Override
    public String getName() {
        return "autosell";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        // /supermarket autosell -> le joueur reçoit son propre objet.
        if (args.length == 1) {
            giveSelf(player);
            return true;
        }

        // /supermarket autosell <joueur> -> un admin donne l'objet à un autre joueur.
        if (args.length == 2) {
            giveOther(player, args[1]);
            return true;
        }

        return true;
    }

    private void giveSelf(Player player) {
        // Reserve aux OP (meme permission que giveOther ci-dessous) : un joueur normal ne doit
        // plus pouvoir se donner l'objet lui-meme via "/supermarket autosell" sans argument —
        // seuls un OP/la console (via un plugin de shop qui l'appelle en op) peuvent le faire.
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return;
        }

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(lang.get("shop-autosell-inventory-full"));
            return;
        }

        player.getInventory().addItem(autoSellItemManager.create(player));
        player.sendMessage(lang.get("shop-autosell-given"));
    }

    /**
     * Public (not just called from {@link #execute}): also invoked directly by Main's command
     * dispatcher when the sender is the console, since {@link SupermarketCommand#execute}
     * requires a {@link Player}. {@link CommandSender#hasPermission} and {@code getName()} both
     * work fine on a console sender (console always has every permission), so nothing else
     * here needs to change for that caller.
     */
    public void giveOther(CommandSender sender, String targetName) {
        if (!sender.hasPermission("supermarket.admin")) {
            sender.sendMessage(lang.get("shop-no-permission"));
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(lang.get("shop-player-not-found", Map.of("player", targetName)));
            return;
        }

        // firstEmpty() ne regarde que les 36 slots hotbar+inventaire, jamais l'armure ni l'off-hand.
        if (target.getInventory().firstEmpty() == -1) {
            sender.sendMessage(lang.get("shop-autosell-target-inventory-full", Map.of("player", target.getName())));
            return;
        }

        ItemStack item = autoSellItemManager.create(target);
        target.getInventory().addItem(item);

        sender.sendMessage(lang.get("shop-autosell-given-to", Map.of("player", target.getName())));
        target.sendMessage(lang.get("shop-autosell-given"));
    }
}
