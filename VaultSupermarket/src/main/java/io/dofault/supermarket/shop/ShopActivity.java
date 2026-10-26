package io.dofault.supermarket.shop;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Qui a acheté / vendu au magasin depuis sa connexion, pour les placeholders
 * {@code %vaultsupermarket_player_buy%} et {@code %vaultsupermarket_player_sell%} (lus par les
 * quêtes Typewriter). Mémoire seulement : remis à zéro à la déconnexion.
 */
public class ShopActivity implements Listener {

    private final Set<UUID> bought = new HashSet<>();
    private final Set<UUID> sold = new HashSet<>();

    public void markBought(UUID uuid) {
        bought.add(uuid);
    }

    public void markSold(UUID uuid) {
        sold.add(uuid);
    }

    public boolean hasBought(UUID uuid) {
        return bought.contains(uuid);
    }

    public boolean hasSold(UUID uuid) {
        return sold.contains(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bought.remove(event.getPlayer().getUniqueId());
        sold.remove(event.getPlayer().getUniqueId());
    }
}
