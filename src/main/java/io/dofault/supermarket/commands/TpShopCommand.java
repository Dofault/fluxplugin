package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.ShopManager;
import io.dofault.supermarket.managers.TpShopCooldownManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public class TpShopCommand implements SupermarketCommand {

    private final LangManager lang;
    private final ShopManager shopManager;
    private final ChestManager chestManager;
    private final PlayerListManager playerListManager;
    private final TpShopCooldownManager cooldownManager;

    public TpShopCommand(LangManager lang, ShopManager shopManager, ChestManager chestManager,
            PlayerListManager playerListManager, TpShopCooldownManager cooldownManager) {
        this.lang = lang;
        this.shopManager = shopManager;
        this.chestManager = chestManager;
        this.playerListManager = playerListManager;
        this.cooldownManager = cooldownManager;
    }

    @Override
    public String getName() {
        return "tpshop";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        // /supermarket tpshop -> le joueur se téléporte lui-même dans le shop.
        if (args.length == 1) {
            selfTeleport(player);
            return true;
        }

        // /supermarket tpshop <joueur> -> un admin téléporte un autre joueur.
        if (args.length == 2) {
            adminTeleportOther(player, args[1]);
            return true;
        }

        player.sendMessage(lang.get("shop-tpshop-usage"));
        return true;
    }

    private void selfTeleport(Player player) {
        boolean isAdmin = player.hasPermission("supermarket.admin");

        if (!isAdmin && !player.hasPermission("supermarket.tpshop")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return;
        }

        if (playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(lang.get("shop-tpshop-already-in-shop", Map.of("player", player.getName())));
            return;
        }

        if (!isAdmin && cooldownManager.isOnCooldown(player)) {
            player.sendMessage(lang.get("shop-tpshop-cooldown", Map.of(
                    "time", TpShopCooldownManager.formatDuration(cooldownManager.getRemainingSeconds(player)))));
            return;
        }

        playerListManager.setReturnLocation(player, player.getLocation());

        if (!playerListManager.enterShop(player)) {
            playerListManager.clearReturnLocation(player.getUniqueId());
            player.sendMessage(lang.get("shop-tpshop-failed", Map.of("player", player.getName())));
            return;
        }

        chestManager.reloadChests();
        player.teleport(shopManager.getExitLocation(player));

        if (!isAdmin) {
            cooldownManager.markUsed(player);
        }

        player.sendMessage(lang.get("shop-tpshop-self-success"));
    }

    private void adminTeleportOther(Player player, String targetName) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage(lang.get("shop-player-not-found", Map.of("player", targetName)));
            return;
        }

        if (playerListManager.isInShop(target.getUniqueId())) {
            player.sendMessage(lang.get("shop-tpshop-already-in-shop", Map.of("player", target.getName())));
            return;
        }

        playerListManager.setReturnLocation(target, target.getLocation());

        if (!playerListManager.enterShop(target)) {
            playerListManager.clearReturnLocation(target.getUniqueId());
            player.sendMessage(lang.get("shop-tpshop-failed", Map.of("player", target.getName())));
            return;
        }

        chestManager.reloadChests();
        target.teleport(shopManager.getExitLocation(target));

        player.sendMessage(lang.get("shop-tpshop-success", Map.of("player", target.getName())));
        target.sendMessage(lang.get("shop-tpshop-target-notice", Map.of("player", player.getName())));
    }
}
