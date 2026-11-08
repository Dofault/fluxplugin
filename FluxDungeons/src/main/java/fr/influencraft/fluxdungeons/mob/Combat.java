package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;

/**
 * Dungeon combat, after the model EliteMobs plays and tests (its LevelScaling, WeaponOffenseCalculator and
 * ArmorDefenseCalculator, rewritten here): vanilla weapon damage and armour are replaced by formulas on levels.
 * <ul>
 *   <li>Monster life: {@code 2.1875 × 2^(level / 5) × health multiplier}.</li>
 *   <li>Player hit: {@code life(level) / 3 × attack speed factor × skill × weapon × charge × sweep × potions}:
 *   3 hits kill a normal monster of his level with a weapon of that level, fully charged.</li>
 *   <li>Monster hit: {@code max health / 5 × skill × gear × damage multiplier × potions}: 5 hits kill a player of
 *   its level wearing armour of that level; never more than his max health minus 1 in one hit.</li>
 *   <li>Skill: {@code 2^(gap / 7.5)}, the gap being class level against monster level, both ways.</li>
 *   <li>Weapon and gear: item level against monster level, from ×0.5 (none) to ×1 (same level) to ×1.25 (10 levels
 *   above) for weapons, and from ×2 to ×1 to ×0.5 damage taken for armour.</li>
 * </ul>
 * Dungeon items carry their level ({@link #ITEM_LEVEL}, the level of the run that dropped them); other items get the
 * level of their vanilla material, as EliteMobs does (iron sword 6, diamond armour 5...).
 */
public final class Combat {

    public static final NamespacedKey ITEM_LEVEL = new NamespacedKey("fluxdungeons", "item_level");

    private static final double BASE_MOB_HP = 2.1875, LEVELS_PER_HP_DOUBLE = 5, HITS_TO_KILL_MOB = 3,
            HITS_TO_KILL_PLAYER = 5, SKILL_RATE = 7.5, REFERENCE_ATTACK_SPEED = 1.6, DPS_PER_LEVEL = 1.6;
    private static final double ITEM_BASE = 0.5, ITEM_PER_LEVEL = 0.025, ITEM_MAX = 0.75;
    private static final double SWEEP = 0.25, POTION_STEP = 0.2;

    private static double damageToPlayer = 1, damageToMob = 1, blocking = 0.8, abilityReference = 6;
    private static int graceTicks = 10;

    private static final Map<Material, Integer> ARMOR_LEVELS = Map.ofEntries(
            Map.entry(Material.LEATHER_HELMET, 1), Map.entry(Material.LEATHER_CHESTPLATE, 1),
            Map.entry(Material.LEATHER_LEGGINGS, 1), Map.entry(Material.LEATHER_BOOTS, 1),
            Map.entry(Material.GOLDEN_HELMET, 2), Map.entry(Material.GOLDEN_CHESTPLATE, 2),
            Map.entry(Material.GOLDEN_LEGGINGS, 2), Map.entry(Material.GOLDEN_BOOTS, 2),
            Map.entry(Material.COPPER_HELMET, 2), Map.entry(Material.COPPER_CHESTPLATE, 2),
            Map.entry(Material.COPPER_LEGGINGS, 2), Map.entry(Material.COPPER_BOOTS, 2),
            Map.entry(Material.CHAINMAIL_HELMET, 3), Map.entry(Material.CHAINMAIL_CHESTPLATE, 3),
            Map.entry(Material.CHAINMAIL_LEGGINGS, 3), Map.entry(Material.CHAINMAIL_BOOTS, 3),
            Map.entry(Material.IRON_HELMET, 4), Map.entry(Material.IRON_CHESTPLATE, 4),
            Map.entry(Material.IRON_LEGGINGS, 4), Map.entry(Material.IRON_BOOTS, 4), Map.entry(Material.TURTLE_HELMET, 4),
            Map.entry(Material.DIAMOND_HELMET, 5), Map.entry(Material.DIAMOND_CHESTPLATE, 5),
            Map.entry(Material.DIAMOND_LEGGINGS, 5), Map.entry(Material.DIAMOND_BOOTS, 5),
            Map.entry(Material.NETHERITE_HELMET, 7), Map.entry(Material.NETHERITE_CHESTPLATE, 7),
            Map.entry(Material.NETHERITE_LEGGINGS, 7), Map.entry(Material.NETHERITE_BOOTS, 7));

    public enum DamageType { MELEE, PROJECTILE, EXPLOSION, FIRE, OTHER }

    private Combat() {
    }

    public static void load(ConfigurationSection c) {
        if (c == null) return;
        damageToPlayer = c.getDouble("damage-to-player-multiplier", 1);
        damageToMob = c.getDouble("damage-to-monster-multiplier", 1);
        blocking = c.getDouble("shield-reduction", 0.8);
        abilityReference = Math.max(0.1, c.getDouble("ability-reference-hit", 6));
        graceTicks = Math.max(0, c.getInt("player-grace-ticks", 10));
    }

    public static int graceTicks() {
        return graceTicks;
    }

    // ---------------------------------------------------------------- monsters

    /** Life of a monster of this level (health multiplier 1). */
    public static double health(int level) {
        return BASE_MOB_HP * Math.pow(2, Math.max(0, level) / LEVELS_PER_HP_DOUBLE);
    }

    /** Life of a monster of this level and health multiplier. */
    public static double health(int level, double healthMultiplier) {
        return Math.max(1, health(level) * healthMultiplier);
    }

    /** {@code 2^(gap / 7.5)}. */
    public static double skill(int stronger, int weaker) {
        return Math.pow(2, (stronger - weaker) / SKILL_RATE);
    }

    /**
     * A monster's hit on a player before blocking: {@code max health / 5 × skill × gear × multipliers × potions},
     * at least 1 before the multipliers, as EliteMobs.
     */
    public static double hitOnPlayer(Player player, LivingEntity mob, int mobLevel, int classLevel, double damageMultiplier, DamageType type) {
        double max = maxHealth(player);
        double scaled = max / HITS_TO_KILL_PLAYER * skill(mobLevel, classLevel) * gearAdjustment(gearScore(player, type), mobLevel);
        return Math.max(scaled, 1) * damageMultiplier * damageToPlayer * potions(mob, player);
    }

    /** One hit never takes more than max health minus 1 (a player at full life survives any single hit). */
    public static double capOneShot(Player player, double damage) {
        return Math.min(damage, Math.max(0, maxHealth(player) - 1));
    }

    public static double shieldFactor() {
        return 1 - blocking;
    }

    // ---------------------------------------------------------------- players

    /** Base damage of a player's normal hit on a monster of this level: its life (multiplier 1) / 3. */
    public static double baseHit(int mobLevel) {
        return health(mobLevel) / HITS_TO_KILL_MOB;
    }

    /**
     * A player's melee or bow hit: base × attack speed factor × skill × weapon × charge × sweep × potions, at least 1.
     *
     * @param charge 0 to 1: attack charge (melee) or arrow speed (bow)
     */
    public static double playerHit(Player player, LivingEntity mob, int mobLevel, int classLevel, ItemStack weapon,
                                   double charge, boolean ranged, boolean sweep) {
        double speedFactor = ranged ? 1 : attackSpeedFactor(weapon, attackSpeed(player));
        double weaponLevel = ranged ? weaponLevel(player, weapon) : weaponLevel(player, weapon);
        double d = baseHit(mobLevel) * speedFactor * skill(classLevel, mobLevel) * weaponAdjustment(weaponLevel, mobLevel)
                * Math.max(0, Math.min(1, charge)) * (sweep ? SWEEP : 1) * potions(player, mob);
        return Math.max(d, 1) * damageToMob;
    }

    /**
     * A class ability's hit: its {@code power} counts in hits of {@code ability-reference-hit} (6 = an iron sword),
     * normalized like a melee hit (skill, weapon in hand, potions), never charged nor swept.
     */
    public static double abilityHit(Player player, LivingEntity mob, int mobLevel, int classLevel, ItemStack weapon, double power) {
        double d = baseHit(mobLevel) * (power / abilityReference) * skill(classLevel, mobLevel)
                * weaponAdjustment(weaponLevel(player, weapon), mobLevel) * potions(player, mob);
        return Math.max(d, 1) * damageToMob;
    }

    /** Attack speed factor: slow weapons hit harder; axes, maces and tridents only partly (EliteMobs' damping). */
    public static double attackSpeedFactor(ItemStack weapon, double attackSpeed) {
        if (!Double.isFinite(attackSpeed) || attackSpeed <= 0) return 1;
        double f = REFERENCE_ATTACK_SPEED / attackSpeed;
        String type = weapon == null || weapon.getType().isAir() ? "" : weapon.getType().name();
        return type.endsWith("_AXE") || type.equals("MACE") || type.equals("TRIDENT") ? Math.sqrt(f) : f;
    }

    /** Ticks for a full attack charge with the weapon in hand. */
    public static double rechargeTicks(Player player) {
        double s = attackSpeed(player);
        return s > 0 ? 20 / s : 20;
    }

    // ---------------------------------------------------------------- items

    /** ×0.5 without weapon, ×1 at the monster's level, up to ×1.25 ten levels above. */
    public static double weaponAdjustment(double weaponLevel, int mobLevel) {
        return 0.5 + itemBonus(weaponLevel, mobLevel);
    }

    /** ×2 naked, ×1 at the monster's level, down to ×0.5 ten levels above. */
    public static double gearAdjustment(double gearScore, int mobLevel) {
        return 2 * (1 - itemBonus(gearScore, mobLevel));
    }

    private static double itemBonus(double level, int mobLevel) {
        int m = Math.max(1, mobLevel);
        double b = level <= m ? ITEM_BASE * (level / m) : ITEM_BASE + (level - m) * ITEM_PER_LEVEL;
        return Math.max(0, Math.min(ITEM_MAX, b));
    }

    /** Level stored on a dungeon item, or -1. */
    public static int storedLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(ITEM_LEVEL, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    /** Weapon level: stored, else from the damage per second of what is in hand (iron sword = 6), bows 4. */
    public static double weaponLevel(Player player, ItemStack weapon) {
        int stored = storedLevel(weapon);
        if (stored >= 0) return stored;
        if (weapon == null || weapon.getType().isAir()) return 0;
        if (weapon.getType() == Material.BOW) return Math.round(6 * 1.0 / DPS_PER_LEVEL);
        if (weapon.getType() == Material.CROSSBOW) return Math.round(7 * 0.8 / DPS_PER_LEVEL);
        return Math.max(0, Math.round(attackDamage(player) * attackSpeed(player) / DPS_PER_LEVEL));
    }

    /** Armour level: stored, else the material's (leather 1 ... netherite 7). */
    public static double armorLevel(ItemStack piece) {
        int stored = storedLevel(piece);
        if (stored >= 0) return stored;
        return piece == null ? 0 : ARMOR_LEVELS.getOrDefault(piece.getType(), 0);
    }

    /** Average armour level of the four pieces, plus a third of a level per protection enchantment level. */
    public static double gearScore(Player player, DamageType type) {
        double levels = 0, enchants = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            levels += armorLevel(piece);
            enchants += protection(piece, type);
        }
        enchants += protection(player.getInventory().getItemInOffHand(), type);
        return levels / 4 + enchants / 3 / 4;
    }

    private static int protection(ItemStack item, DamageType type) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta m = item.getItemMeta();
        int total = m.getEnchantLevel(Enchantment.PROTECTION);
        switch (type) {
            case PROJECTILE -> total += m.getEnchantLevel(Enchantment.PROJECTILE_PROTECTION);
            case EXPLOSION -> total += m.getEnchantLevel(Enchantment.BLAST_PROTECTION);
            case FIRE -> total += m.getEnchantLevel(Enchantment.FIRE_PROTECTION);
            default -> {
            }
        }
        return total;
    }

    public static DamageType type(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> DamageType.MELEE;
            case PROJECTILE -> DamageType.PROJECTILE;
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> DamageType.EXPLOSION;
            case FIRE, FIRE_TICK -> DamageType.FIRE;
            default -> DamageType.OTHER;
        };
    }

    // ---------------------------------------------------------------- helpers

    /** Strength and weakness of the source, resistance of the target: 20 % per level each. */
    public static double potions(LivingEntity source, LivingEntity target) {
        double out = 1 + POTION_STEP * (level(source, PotionEffectType.STRENGTH) - level(source, PotionEffectType.WEAKNESS));
        double in = 1 - POTION_STEP * level(target, PotionEffectType.RESISTANCE);
        return Math.max(0, out) * Math.max(0, in);
    }

    private static int level(LivingEntity e, PotionEffectType type) {
        if (e == null) return 0;
        PotionEffect pe = e.getPotionEffect(type);
        return pe == null ? 0 : pe.getAmplifier() + 1;
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    /** Attack damage of the player without the class bonuses (base 1 + weapon). */
    private static double attackDamage(Player p) {
        return withoutClass(p.getAttribute(Attribute.ATTACK_DAMAGE), 1);
    }

    /** Attack speed of the player without the class bonuses (base 4 + weapon). */
    private static double attackSpeed(Player p) {
        return withoutClass(p.getAttribute(Attribute.ATTACK_SPEED), 4);
    }

    private static double withoutClass(AttributeInstance a, double fallback) {
        if (a == null) return fallback;
        double v = a.getValue();
        for (AttributeModifier m : a.getModifiers()) {
            if (!m.getKey().getKey().startsWith("class_")) continue;
            if (m.getOperation() == AttributeModifier.Operation.ADD_NUMBER) v -= m.getAmount();
        }
        return v;
    }
}
