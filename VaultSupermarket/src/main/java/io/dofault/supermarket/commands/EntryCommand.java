package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.ShopManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class EntryCommand implements SupermarketCommand {

    private final ShopManager shopManager;
    private final ChestManager chestManager;
    private final LangManager lang;

    public EntryCommand(LangManager lang, ShopManager shopManager, ChestManager chestManager) {
        this.lang = lang;
        this.shopManager = shopManager;
        this.chestManager = chestManager;
    }

    @Override
    public String getName() {
        return "entry";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        chestManager.reloadChests();
        shopManager.setEntryPosSetting(player.getLocation());
        player.sendMessage(lang.get("shop-entry-saved"));

        return true;
    }
}