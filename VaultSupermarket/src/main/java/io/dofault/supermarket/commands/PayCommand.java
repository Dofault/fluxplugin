package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.ShopManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class PayCommand implements SupermarketCommand {

    private final ShopManager shopManager;
    private final PlayerListManager playerListManager;
    private final LangManager lang;

    public PayCommand(LangManager lang, ShopManager shopManager, PlayerListManager playerListManager) {
        this.lang = lang;
        this.shopManager = shopManager;
        this.playerListManager = playerListManager;
    }

    @Override
    public String getName() {
        return "pay";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(lang.get("shop-must-be-inside"));

            return true;
        }

        shopManager.payUser(player);
        return true;
    }
}
