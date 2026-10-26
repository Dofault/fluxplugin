package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.ShopManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class DifferenceCommand implements SupermarketCommand {

    private final ShopManager shopManager;
    private final PlayerListManager playerListManager;
    private final LangManager lang;

    public DifferenceCommand(LangManager lang, ShopManager shopManager, PlayerListManager playerListManager) {
        this.lang = lang;
        this.shopManager = shopManager;
        this.playerListManager = playerListManager;
    }

    @Override
    public String getName() {
        return "difference";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(lang.get("shop-must-be-inside"));

            return true;
        }

        shopManager.showInventoryDifference(player);
        return true;
    }
}
