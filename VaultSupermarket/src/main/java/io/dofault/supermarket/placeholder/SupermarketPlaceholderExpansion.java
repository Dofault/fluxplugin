package io.dofault.supermarket.placeholder;

import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.TpShopCooldownManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SupermarketPlaceholderExpansion extends PlaceholderExpansion {

    private final PlayerListManager playerListManager;
    private final TpShopCooldownManager tpShopCooldownManager;

    public SupermarketPlaceholderExpansion(PlayerListManager playerListManager,
            TpShopCooldownManager tpShopCooldownManager) {
        this.playerListManager = playerListManager;
        this.tpShopCooldownManager = tpShopCooldownManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vaultsupermarket";
    }

    @Override
    public @NotNull String getAuthor() {
        return "dofault";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return null;
        if (params.equals("player_sell")) {
            return String.valueOf(playerListManager.hasSold(player.getUniqueId()));
        }
        if (params.equals("player_buy")) {
            return String.valueOf(playerListManager.hasBought(player.getUniqueId()));
        }
        if (params.equals("tpshop_cooldown")) {
            return String.valueOf(tpShopCooldownManager.getRemainingSeconds(player.getUniqueId()));
        }
        if (params.equals("tpshop_cooldown_formatted")) {
            return TpShopCooldownManager.formatDuration(tpShopCooldownManager.getRemainingSeconds(player.getUniqueId()));
        }
        if (params.equals("tpshop_available")) {
            return String.valueOf(tpShopCooldownManager.getRemainingSeconds(player.getUniqueId()) == 0);
        }
        return null;
    }
}
