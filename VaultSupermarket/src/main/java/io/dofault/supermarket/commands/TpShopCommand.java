package io.dofault.supermarket.commands;

import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.ShopManager;
import io.dofault.supermarket.managers.TpShopCooldownManager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TpShopCommand implements SupermarketCommand {

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final ShopManager shopManager;
    private final ChestManager chestManager;
    private final PlayerListManager playerListManager;
    private final TpShopCooldownManager cooldownManager;
    private final long warmupSeconds;
    private final Set<UUID> pendingTeleports = new HashSet<>();

    public TpShopCommand(JavaPlugin plugin, LangManager lang, ShopManager shopManager, ChestManager chestManager,
            PlayerListManager playerListManager, TpShopCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.lang = lang;
        this.shopManager = shopManager;
        this.chestManager = chestManager;
        this.playerListManager = playerListManager;
        this.cooldownManager = cooldownManager;
        this.warmupSeconds = plugin.getConfig().getLong("tpshop-warmup-seconds", 3);
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

        if (!isAdmin && pendingTeleports.contains(player.getUniqueId())) {
            player.sendMessage(lang.get("shop-tpshop-already-teleporting"));
            return;
        }

        // Les admins se téléportent instantanément, sans warmup.
        if (isAdmin || warmupSeconds <= 0) {
            performSelfTeleport(player, isAdmin);
            return;
        }

        startWarmup(player);
    }

    private void startWarmup(Player player) {
        UUID uuid = player.getUniqueId();
        pendingTeleports.add(uuid);
        Location startLocation = player.getLocation();

        player.sendMessage(lang.get("shop-tpshop-warmup-start",
                Map.of("time", String.valueOf(warmupSeconds))));

        new BukkitRunnable() {
            long remaining = warmupSeconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    pendingTeleports.remove(uuid);
                    cancel();
                    return;
                }

                if (hasMoved(player, startLocation)) {
                    pendingTeleports.remove(uuid);
                    player.sendMessage(lang.get("shop-tpshop-warmup-cancelled"));
                    cancel();
                    return;
                }

                if (remaining <= 0) {
                    pendingTeleports.remove(uuid);
                    cancel();
                    performSelfTeleport(player, false);
                    return;
                }

                player.sendActionBar(lang.get("shop-tpshop-warmup-actionbar",
                        Map.of("time", String.valueOf(remaining))));
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private boolean hasMoved(Player player, Location start) {
        Location current = player.getLocation();
        if (current.getWorld() == null || start.getWorld() == null
                || !current.getWorld().equals(start.getWorld())) {
            return true;
        }
        return current.distanceSquared(start) > 0.09; // ~0.3 bloc de tolérance
    }

    private void performSelfTeleport(Player player, boolean isAdmin) {
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
