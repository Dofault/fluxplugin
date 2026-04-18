package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PriceManager;

import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class SetPriceCommand implements SupermarketCommand {

    private final PriceManager priceManager;
    private final LangManager lang;

    public SetPriceCommand(LangManager lang, PriceManager priceManager) {
        this.lang = lang;
        this.priceManager = priceManager;
    }

    @Override
    public String getName() {
        return "setprice";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (args.length != 3) {
            player.sendMessage(lang.get("shop-setprice-usage"));

            return true;
        }

        Material material = Material.matchMaterial(args[1].toUpperCase());
        if (material == null) {
            player.sendMessage(lang.get("shop-invalid-item"));

            return true;
        }

        double price;
        try {
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(lang.get("shop-invalid-price"));

            return true;
        }

        priceManager.setPrice(material, price);
        player.sendMessage(lang.get("shop-price-set", Map.of(
                "material", material.name(),
                "price", String.valueOf(price))));
        return true;
    }
}
