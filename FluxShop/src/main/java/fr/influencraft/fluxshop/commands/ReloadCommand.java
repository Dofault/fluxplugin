package fr.influencraft.fluxshop.commands;

import fr.influencraft.fluxshop.autosell.AutoSellFilter;
import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import fr.influencraft.fluxshop.shop.ShopRegistry;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Recharge à chaud prices.yml (le principal cas d'usage : changer un prix sans redémarrer le
 * serveur), config.yml, lang/fr.yml et shops.yml, sans recompiler/redéployer le plugin.
 */
public class ReloadCommand implements SupermarketCommand {

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ShopRegistry shopRegistry;
    private final AutoSellFilter autoSellFilter;

    public ReloadCommand(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ShopRegistry shopRegistry,
            AutoSellFilter autoSellFilter) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.shopRegistry = shopRegistry;
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
        shopRegistry.reload();
        autoSellFilter.reload();

        int invalid = priceManager.getInvalidItemIds().size();
        player.sendMessage(lang.get("shop-reload-success", Map.of(
                "items", String.valueOf(priceManager.getItemIds().size()),
                "invalid", String.valueOf(invalid))));

        return true;
    }
}
