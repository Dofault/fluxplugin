package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.shop.Shop;
import io.dofault.supermarket.shop.ShopRegistry;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Assignation des blocs, toujours sur le bloc regardé par l'admin : {@code addchest} /
 * {@code removechest} (coffre magasin) et {@code addsellblock} / {@code removesellblock} (bloc de
 * vente). Les objets d'un coffre se gèrent dans son éditeur (accroupi + clic droit).
 */
public class ShopAdminCommand implements SupermarketCommand {

    public enum Action {
        ADD_CHEST("addchest"),
        REMOVE_CHEST("removechest"),
        ADD_SELL_BLOCK("addsellblock"),
        REMOVE_SELL_BLOCK("removesellblock");

        private final String command;

        Action(String command) {
            this.command = command;
        }
    }

    private static final int TARGET_RANGE = 6;

    private final Action action;
    private final LangManager lang;
    private final ShopRegistry registry;

    public ShopAdminCommand(Action action, LangManager lang, ShopRegistry registry) {
        this.action = action;
        this.lang = lang;
        this.registry = registry;
    }

    @Override
    public String getName() {
        return action.command;
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }
        Block block = player.getTargetBlockExact(TARGET_RANGE);
        if (block == null) {
            player.sendMessage(lang.get("shop-admin-no-target"));
            return true;
        }

        switch (action) {
            case ADD_CHEST -> addChest(player, block);
            case REMOVE_CHEST -> {
                Shop shop = requireChest(player, block);
                if (shop != null) {
                    registry.removeChest(shop);
                    player.sendMessage(lang.get("shop-admin-chest-removed"));
                }
            }
            case ADD_SELL_BLOCK -> {
                if (registry.isAssigned(block)) {
                    player.sendMessage(lang.get("shop-admin-already-assigned"));
                } else {
                    registry.addSellBlock(block);
                    player.sendMessage(lang.get("shop-admin-sellblock-added"));
                }
            }
            case REMOVE_SELL_BLOCK -> player.sendMessage(lang.get(registry.removeSellBlock(block)
                    ? "shop-admin-sellblock-removed"
                    : "shop-admin-not-a-sellblock"));
        }
        return true;
    }

    private void addChest(Player player, Block block) {
        if (registry.isAssigned(block)) {
            player.sendMessage(lang.get("shop-admin-already-assigned"));
            return;
        }
        if (!registry.isAllowedBlock(block)) {
            player.sendMessage(lang.get("shop-admin-block-not-allowed", Map.of(
                    "types", String.join(", ", registry.getBlockTypes()))));
            return;
        }
        registry.addChest(block);
        player.sendMessage(lang.get("shop-admin-chest-added"));
    }

    private Shop requireChest(Player player, Block block) {
        Shop shop = registry.getShop(block);
        if (shop == null) {
            player.sendMessage(lang.get("shop-admin-not-a-chest"));
        }
        return shop;
    }
}
