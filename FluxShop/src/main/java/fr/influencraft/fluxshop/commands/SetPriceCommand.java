package fr.influencraft.fluxshop.commands;

import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;

import java.util.Map;

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
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        if (args.length != 4) {
            player.sendMessage(lang.get("shop-setprice-usage"));

            return true;
        }

        Material material = Material.matchMaterial(args[1].toUpperCase());
        if (material == null) {
            player.sendMessage(lang.get("shop-invalid-item"));

            return true;
        }

        double buy;
        double sell;
        try {
            buy = Double.parseDouble(args[2]);
            sell = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(lang.get("shop-invalid-price"));

            return true;
        }

        priceManager.setPrice(material, buy, sell);
        player.sendMessage(lang.get("shop-price-set", Map.of(
                "material", material.name(),
                "buy", String.valueOf(buy),
                "sell", String.valueOf(sell))));
        return true;
    }
}
