package fr.influencraft.influpet.effects;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Chien de Garde : plus la vie du joueur est basse, plus il inflige de dégâts (corps à corps et projectiles).
 * Bonus nul à pleine vie, maximum (value du niveau, en %) à 1 point de vie, linéaire entre les deux ; sous
 * 1 point de vie il reste au maximum. L'absorption ne compte pas (seule la vraie vie).
 */
final class Rage extends PetEffect {

    Rage(PetEffects manager) {
        super(manager, "rage");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = switch (event.getDamager()) {
            case Player p -> p;
            case Projectile proj when proj.getShooter() instanceof Player p -> p;
            default -> null;
        };
        if (attacker == null || attacker == event.getEntity()) {
            return;
        }
        int level = level(attacker);
        if (level <= 0) {
            return;
        }
        AttributeInstance attr = attacker.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = attr == null ? 20 : attr.getValue();
        if (maxHealth <= 1) {
            return;
        }
        double missing = Math.clamp((maxHealth - attacker.getHealth()) / (maxHealth - 1), 0, 1);
        if (missing > 0) {
            event.setDamage(event.getDamage() * (1 + value(level) / 100 * missing));
        }
    }
}
