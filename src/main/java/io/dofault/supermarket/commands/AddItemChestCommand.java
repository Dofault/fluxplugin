package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;

import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class AddItemChestCommand implements SupermarketCommand {

    private final ChestManager chestManager;
    private final LangManager lang;

    public AddItemChestCommand(LangManager lang, ChestManager chestManager) {
        this.chestManager = chestManager;
        this.lang = lang;
    }

    @Override
    public String getName() {
        return "additemchest";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        if (args.length != 3) {
            player.sendMessage(lang.get("shop-additemchest-usage"));

            return false;
        }

        Material material = Material.matchMaterial(args[1].toUpperCase());
        if (material == null) {
            player.sendMessage(lang.get("shop-invalid-item"));

            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(lang.get("shop-invalid-number"));

            return false;
        }

        try {
            boolean success = chestManager.addItemToChest(player.getLocation(), material, amount);

            if (!success) {
                throw new IllegalStateException(lang.get("shop-add-item-failed"));
            }

            player.sendMessage(lang.get("shop-add-item-chest", Map.of(
                    "amount", String.valueOf(amount),
                    "material", material.name())));
        } catch (Exception e) {
            player.sendMessage(e.getMessage());
            e.printStackTrace();
        }

        return true;
    }
}
