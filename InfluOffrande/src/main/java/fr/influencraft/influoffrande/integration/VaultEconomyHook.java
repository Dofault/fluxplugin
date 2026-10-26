package fr.influencraft.influoffrande.integration;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Thin static wrapper around the Vault {@link Economy} service — the same BitBlock
 * balance backed by EssentialsXEco elsewhere on the server. Soft dependency: if Vault or
 * an economy plugin isn't present, {@link #isAvailable()} stays false and the money
 * deposit feature fails gracefully (the rest of the plugin, item deposits included,
 * doesn't need it and keeps working).
 */
public final class VaultEconomyHook {

    private static Economy economy;

    private VaultEconomyHook() {
    }

    public static boolean setup(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public static boolean isAvailable() {
        return economy != null;
    }

    public static double getBalance(OfflinePlayer player) {
        return economy == null ? 0 : economy.getBalance(player);
    }

    public static boolean withdraw(Player player, long amount) {
        if (economy == null || amount <= 0) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, (double) amount);
        return response != null && response.transactionSuccess();
    }
}
