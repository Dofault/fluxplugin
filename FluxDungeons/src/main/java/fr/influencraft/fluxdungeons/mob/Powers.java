package fr.influencraft.fluxdungeons.mob;

import fr.influencraft.fluxdungeons.mob.MobTemplate.Ability;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in powers named in {@code powers:} (EliteMobs' names, so imported monsters keep them), made of our own simple
 * parts: damage a monster ignores, effects of its hits, permanent effects, and abilities cast in combat. Unknown
 * names change nothing.
 */
final class Powers {

    /** What a list of powers gives a monster. */
    record Kit(Set<EntityDamageEvent.DamageCause> immune, boolean noKnockback, List<PotionEffect> permanent,
               List<OnHit> onHit, List<Ability> abilities) {
    }

    /** Something a monster's hit does to the player. */
    record OnHit(String type, PotionEffectType effect, int ticks, int amplifier) {
    }

    private Powers() {
    }

    static Kit of(List<String> names) {
        Set<EntityDamageEvent.DamageCause> immune = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        boolean noKnockback = false;
        List<PotionEffect> permanent = new ArrayList<>();
        List<OnHit> onHit = new ArrayList<>();
        List<Ability> abilities = new ArrayList<>();
        for (String raw : names) {
            String n = raw.toLowerCase(Locale.ROOT).replace(".yml", "");
            switch (n) {
                case "invulnerability_fire" -> immune.addAll(List.of(EntityDamageEvent.DamageCause.FIRE,
                        EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.HOT_FLOOR));
                case "invulnerability_arrow" -> immune.add(EntityDamageEvent.DamageCause.PROJECTILE);
                case "invulnerability_fall_damage" -> immune.add(EntityDamageEvent.DamageCause.FALL);
                case "invulnerability_fireworks" -> immune.add(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
                case "invulnerability_knockback" -> noKnockback = true;
                case "invisibility" -> permanent.add(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
                case "movement_speed" -> permanent.add(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false));
                case "attack_blinding" -> onHit.add(new OnHit("effect", PotionEffectType.BLINDNESS, 60, 0));
                case "attack_weakness" -> onHit.add(new OnHit("effect", PotionEffectType.WEAKNESS, 100, 0));
                case "attack_wither" -> onHit.add(new OnHit("effect", PotionEffectType.WITHER, 60, 0));
                case "attack_poison" -> onHit.add(new OnHit("effect", PotionEffectType.POISON, 80, 0));
                case "attack_freeze" -> onHit.add(new OnHit("freeze", PotionEffectType.SLOWNESS, 60, 2));
                case "attack_confusing" -> onHit.add(new OnHit("effect", PotionEffectType.NAUSEA, 100, 0));
                case "attack_gravity" -> onHit.add(new OnHit("effect", PotionEffectType.LEVITATION, 30, 1));
                case "attack_fire" -> onHit.add(new OnHit("fire", null, 60, 0));
                case "attack_web" -> onHit.add(new OnHit("web", null, 60, 0));
                case "attack_lightning" -> onHit.add(new OnHit("lightning", null, 0, 0));
                case "attack_push" -> onHit.add(new OnHit("push", null, 0, 0));
                case "attack_vacuum" -> onHit.add(new OnHit("pull", null, 0, 0));
                case "attack_arrow", "skeleton_tracking_arrow" -> abilities.add(ability("arrow", 4, Map.of("power", 1.0)));
                case "arrow_rain", "arrow_fireworks", "bullet_hell", "gold_shotgun" -> abilities.add(ability("volley", 9, Map.of("count", 6.0, "power", 0.7)));
                case "attack_fireball", "plasma_blaster", "fireworks_barrage" -> abilities.add(ability("fireball", 6, Map.of("speed", 1.2)));
                case "flamethrower" -> abilities.add(ability("cone", 10, Map.of("power", 0.6, "range", 6.0, "fire", 1.0)));
                case "frost_cone" -> abilities.add(ability("cone", 10, Map.of("power", 0.6, "range", 6.0, "freeze", 1.0)));
                case "taze" -> abilities.add(ability("taze", 8, Map.of("power", 0.8)));
                case "lightning_bolts", "thunderstorm" -> abilities.add(ability("lightning", 10, Map.of("count", 3.0, "power", 1.0)));
                case "implosion" -> abilities.add(ability("implosion", 12, Map.of("radius", 8.0, "power", 0.8)));
                case "gold_explosion", "ground_pound" -> abilities.add(ability("slam", 10, Map.of("radius", 4.0, "power", 1.2, "relative", 1.0)));
                case "shield_wall" -> abilities.add(ability("shield", 14, Map.of("duration", 4.0)));
                case "summon_the_returned" -> abilities.add(ability("summon_vanilla", 20, Map.of("entity", "SKELETON", "count", 2.0)));
                default -> {
                    // unknown or replaced by scripts: nothing
                }
            }
        }
        return new Kit(immune, noKnockback, permanent, onHit, abilities);
    }

    private static Ability ability(String type, double every, Map<String, Object> params) {
        return new Ability(type, every, 100, params);
    }

    /** Applies one on-hit effect to a player hit by the monster. */
    static void hit(OnHit h, Player p, org.bukkit.entity.LivingEntity by, double baseDamage) {
        switch (h.type()) {
            case "effect" -> p.addPotionEffect(new PotionEffect(h.effect(), h.ticks(), h.amplifier()));
            case "freeze" -> {
                p.addPotionEffect(new PotionEffect(h.effect(), h.ticks(), h.amplifier()));
                p.setFreezeTicks(Math.max(p.getFreezeTicks(), 140));
            }
            case "fire" -> p.setFireTicks(Math.max(p.getFireTicks(), h.ticks()));
            case "web" -> {
                var block = p.getLocation().getBlock();
                if (block.getType().isAir()) {
                    block.setType(Material.COBWEB, false);
                    org.bukkit.Bukkit.getScheduler().runTaskLater(org.bukkit.Bukkit.getPluginManager().getPlugin("FluxDungeons"), () -> {
                        if (block.getType() == Material.COBWEB) block.setType(Material.AIR, false);
                    }, h.ticks());
                }
            }
            case "lightning" -> {
                p.getWorld().strikeLightningEffect(p.getLocation());
                p.damage(baseDamage * 0.5, by);
            }
            case "push" -> p.setVelocity(p.getLocation().toVector().subtract(by.getLocation().toVector()).setY(0).normalize().multiply(1.2).setY(0.4));
            case "pull" -> p.setVelocity(by.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0).normalize().multiply(0.9).setY(0.2));
            default -> {
            }
        }
    }
}
