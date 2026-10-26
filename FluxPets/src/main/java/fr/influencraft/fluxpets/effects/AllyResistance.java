package fr.influencraft.fluxpets.effects;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Écuyer : Résistance I (-20 % de dégâts, comme la potion) à tous les joueurs dans un rayon de value(niveau)
 * blocs (arrondi, comme la description). Le porteur n'en profite pas lui-même. Effet court renouvelé toutes les
 * « interval-ticks » : il disparaît peu après qu'on s'éloigne. addPotionEffect suit la règle vanilla : une
 * résistance plus forte ou plus longue déjà présente n'est pas écrasée, et deux Écuyers ne s'additionnent pas
 * (même niveau I). Les joueurs invisibles aux autres (vanish) et les spectateurs sont ignorés.
 */
final class AllyResistance extends PetEffect {

    private int elapsed;

    AllyResistance(PetEffects manager) {
        super(manager, "ally-resistance");
        Bukkit.getScheduler().runTaskTimer(manager.plugin(), this::tick, 60L, 20L);
    }

    private int interval() {
        return Math.max(20, getInt("interval-ticks", 40));
    }

    private void tick() {
        elapsed += 20;
        if (petId == null || elapsed < interval()) {
            return;
        }
        elapsed = 0;
        int duration = interval() + 30;
        for (Player owner : Bukkit.getOnlinePlayers()) {
            int level = level(owner);
            if (level <= 0 || owner.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            double radius = Math.round(value(level));
            for (Player other : owner.getWorld().getNearbyPlayers(owner.getLocation(), radius)) {
                if (other != owner && other.getGameMode() != org.bukkit.GameMode.SPECTATOR && owner.canSee(other)) {
                    other.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, duration, 0, true, false, true));
                }
            }
        }
    }
}
