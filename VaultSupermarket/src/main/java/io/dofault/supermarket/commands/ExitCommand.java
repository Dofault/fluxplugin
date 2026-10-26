package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.ShopManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class ExitCommand implements SupermarketCommand {

    private final ShopManager shopManager;
    private final LangManager lang;

    public ExitCommand(LangManager lang, ShopManager shopManager) {
        this.lang = lang;
        this.shopManager = shopManager;
    }

    @Override
    public String getName() {
        return "exit";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        shopManager.setExitPosSetting(player.getLocation());
        player.sendMessage(lang.get("shop-exit-saved"));

        return true;
    }
}
