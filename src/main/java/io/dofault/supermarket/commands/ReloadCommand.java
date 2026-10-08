package io.dofault.supermarket.commands;

import io.dofault.supermarket.autosell.AutoSellFilter;
import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PriceManager;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Recharge à chaud prices.yml (le principal cas d'usage : changer un prix sans redémarrer le
 * serveur), config.yml, lang/fr.yml et chest.yml — sans recompiler/redéployer le plugin. Les
 * champs mis en cache une seule fois à l'activation (ex. buy_block/sell_block matérialisés dans
 * ShopRestrictionListener) ne sont PAS repris par ce reload, un redémarrage reste nécessaire
 * pour ceux-là.
 */
public class ReloadCommand implements SupermarketCommand {

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ChestManager chestManager;
    private final AutoSellFilter autoSellFilter;

    public ReloadCommand(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ChestManager chestManager,
            AutoSellFilter autoSellFilter) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.chestManager = chestManager;
        this.autoSellFilter = autoSellFilter;
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }

        plugin.reloadConfig();
        priceManager.reload();
        priceManager.logInvalidItems();
        lang.reload();
        chestManager.reloadChests();
        autoSellFilter.reload();

        int invalid = priceManager.getInvalidItemIds().size();
        player.sendMessage(lang.get("shop-reload-success", Map.of(
                "items", String.valueOf(priceManager.getItemIds().size()),
                "invalid", String.valueOf(invalid))));

        return true;
    }
}
