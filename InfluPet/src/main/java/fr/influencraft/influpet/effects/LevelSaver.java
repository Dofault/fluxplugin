package fr.influencraft.influpet.effects;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.enchantment.EnchantItemEvent;

/**
 * Grimoire : chance qu'un enchantement à la table ne coûte pas de niveaux. En survie la table retire
 * « bouton + 1 » niveaux juste après l'événement : on les rend le tick suivant. Rien en créatif, où la table
 * n'en retire pas (dont_consume_xp_chance de libreforge les rendait quand même : niveaux gratuits).
 */
final class LevelSaver extends PetEffect {

    LevelSaver(PetEffects manager) {
        super(manager, "level-saver");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (player.getGameMode() == GameMode.CREATIVE || !roll(level(player))) {
            return;
        }
        int cost = event.whichButton() + 1;
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            if (player.isOnline()) {
                player.giveExpLevels(cost);
            }
        });
    }
}
