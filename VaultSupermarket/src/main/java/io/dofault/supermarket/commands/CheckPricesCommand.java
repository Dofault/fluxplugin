package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PriceManager;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class CheckPricesCommand implements SupermarketCommand {

    private final LangManager lang;
    private final PriceManager priceManager;

    public CheckPricesCommand(LangManager lang, PriceManager priceManager) {
        this.lang = lang;
        this.priceManager = priceManager;
    }

    @Override
    public String getName() {
        return "checkprices";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        List<String> invalid = priceManager.getInvalidItemIds();
        if (invalid.isEmpty()) {
            player.sendMessage(lang.get("shop-checkprices-none"));
            return true;
        }

        player.sendMessage(lang.get("shop-checkprices-header", Map.of("count", String.valueOf(invalid.size()))));
        for (String itemID : invalid) {
            player.sendMessage(lang.get("shop-checkprices-line", Map.of("item", itemID)));
        }

        return true;
    }
}
