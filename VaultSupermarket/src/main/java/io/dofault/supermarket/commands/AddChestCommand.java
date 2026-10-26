package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class AddChestCommand implements SupermarketCommand {

    private final ChestManager chestManager;
    private final LangManager lang;

    public AddChestCommand(LangManager lang, ChestManager chestManager) {
        this.lang = lang;
        this.chestManager = chestManager;
    }

    @Override
    public String getName() {
        return "addchest";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        try {
            boolean success = chestManager.addChest(player.getLocation().getBlock());

            if (success) {
                player.sendMessage(lang.get("shop-chest-added"));
            } else {
                player.sendMessage(lang.get("shop-command-addchest"));
            }
        } catch (Exception e) {
            player.sendMessage(e.getMessage());
            e.printStackTrace();
        }

        return true;
    }
}
