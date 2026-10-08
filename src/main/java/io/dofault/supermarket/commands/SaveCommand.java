package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.ShopManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class SaveCommand implements SupermarketCommand {

    private final ShopManager shopManager;
    private final LangManager lang;

    public SaveCommand(LangManager lang, ShopManager shopManager) {
        this.lang = lang;
        this.shopManager = shopManager;
    }

    @Override
    public String getName() {
        return "save";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        if (shopManager.saveShop()) {
            player.sendMessage(lang.get("shop-saved"));

        } else {
            player.sendMessage(lang.get("shop-define-entry-exit-first"));

        }
        return true;
    }
}
