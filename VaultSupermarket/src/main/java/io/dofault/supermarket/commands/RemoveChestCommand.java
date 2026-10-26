package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;
import org.bukkit.entity.Player;

public class RemoveChestCommand implements SupermarketCommand {

    private final ChestManager chestManager;
    private final LangManager lang;

    public RemoveChestCommand(LangManager lang, ChestManager chestManager) {
        this.lang = lang;
        this.chestManager = chestManager;
    }

    @Override
    public String getName() {
        return "removechest";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        chestManager.removeChest(player);
        return true;
    }
}
