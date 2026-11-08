package fr.influencraft.fluxdungeons.combat;

import fr.influencraft.flux.api.items.ItemLooks;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * Attack cadence of the whole server (as Wynncraft): a weapon attacks at its own pace whatever the player clicks.
 * Carried by FluxDungeons because it serves the dungeons first, but it applies everywhere ({@code attack-cadence} of
 * FluxDungeons' config). In a dungeon it adds the aim assist ({@link Assist}).
 * <ul>
 *   <li>Every weapon (any item with an attack speed but mining tools: swords, axes, trident, mace) gets the vanilla item
 *   components {@code minimum_attack_charge} (the client refuses to attack before the weapon is recharged, so spam
 *   clicks do nothing) and {@code swing_animation} lasting the whole recharge (the weapon comes back slowly, slower
 *   for a slow weapon). Given through {@link ItemLooks#registerAny}, so vanilla items get it too.</li>
 *   <li>The server refuses an attack sent before the recharge (modified clients).</li>
 *   <li>The vanilla recharge indicator is hidden by the resource pack (empty sprites over those of the pack
 *   {@code influstatique}, {@code Flux/assets/gen_attack_indicator.py}).</li>
 *   <li>Every real swing (weapon recharged), on a target or in the air, shows a particle and plays a sound
 *   ({@code swing-particle}, {@code swing-sound}) where the swing reaches: the player's reach (its attribute, so
 *   bonuses of pets or effects count; the weapon's own attack range when it has one), stopped by the first block or
 *   creature on the way. The attack is seen and heard even when it hits nobody.</li>
 * </ul>
 * Hits always land at full charge: the damage is the vanilla one of a charged hit. Spears keep their own components.
 * Turned off ({@code combat.attack-cadence.enabled: false}), items lose the components at their next refresh.
 */
public final class AttackCadence implements Listener {

    private boolean enabled = true;
    private float minimumCharge = 1f;
    private boolean slowSwing = true;
    /**
     * Length of the swing as a share of the recharge: the client lets the next attack go a little before the end of a
     * swing as long as the recharge (seen around 80 %), which restarted the swing before the weapon was back in place.
     */
    private double swingShare = 0.8;
    private org.bukkit.Particle swingParticle = org.bukkit.Particle.SWEEP_ATTACK;
    private String swingSound = "minecraft:entity.player.attack.sweep";
    private float soundVolume = 0.7f, soundPitch = 1f;
    /** Tick of each player's last attack let through (its swing packet comes after, recharge already reset). */
    private final java.util.Map<java.util.UUID, Integer> attacked = new java.util.HashMap<>();
    /** Tick of each player's last swing shown (one particle per swing). */
    private final java.util.Map<java.util.UUID, Integer> shown = new java.util.HashMap<>();

    /** What the dungeons tell: who is fighting in one, and what he may hit. */
    public interface Assist {
        /** Monsters this player may hit, null when he is not in a dungeon (no assist). */
        java.util.function.Predicate<org.bukkit.entity.Entity> enemies(Player player);
    }

    private Assist assist = p -> null;
    private boolean assistOn = true;
    private double assistRadius = 1.2;

    public AttackCadence(Plugin plugin) {
        ItemLooks.registerAny(plugin, (id, item) -> enabled ? apply(item) : clear(item));
    }

    public void assist(Assist assist) {
        this.assist = assist;
    }

    /** Aim assist of the dungeons ({@code aim-assist} of the config). */
    public void loadAssist(ConfigurationSection c) {
        assistOn = c == null || c.getBoolean("enabled", true);
        assistRadius = c == null ? 1.2 : Math.max(0.2, c.getDouble("radius", 1.2));
    }

    public void load(ConfigurationSection c) {
        enabled = c == null || c.getBoolean("enabled", true);
        minimumCharge = c == null ? 1f : (float) Math.max(0, Math.min(1, c.getDouble("minimum-charge", 1)));
        slowSwing = c == null || c.getBoolean("slow-swing", true);
        swingShare = c == null ? 0.8 : Math.max(0.2, Math.min(1, c.getDouble("swing-share", 0.8)));
        String particle = c == null ? "SWEEP_ATTACK" : c.getString("swing-particle", "SWEEP_ATTACK");
        try {
            swingParticle = particle == null || particle.isBlank() || particle.equalsIgnoreCase("none") ? null
                    : org.bukkit.Particle.valueOf(particle.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            swingParticle = org.bukkit.Particle.SWEEP_ATTACK;
        }
        String sound = c == null ? null : c.getString("swing-sound.key", "minecraft:entity.player.attack.sweep");
        swingSound = c == null ? "minecraft:entity.player.attack.sweep" : sound == null || sound.isBlank() || sound.equalsIgnoreCase("none") ? null : sound;
        soundVolume = c == null ? 0.7f : (float) c.getDouble("swing-sound.volume", 0.7);
        soundPitch = c == null ? 1f : (float) c.getDouble("swing-sound.pitch", 1);
        ItemLooks.refreshOnline();
    }

    /** Attack speed of an item (attacks per second, vanilla base 4 plus its modifiers), -1 when it has none. */
    static double attackSpeed(ItemStack item) {
        double add = 0;
        boolean found = false;
        if (item.isDataOverridden(DataComponentTypes.ATTRIBUTE_MODIFIERS)) {
            ItemAttributeModifiers mods = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (mods != null) {
                for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                    if (!e.attribute().equals(Attribute.ATTACK_SPEED) || !mainHand(e.getGroup())) continue;
                    if (e.modifier().getOperation() != AttributeModifier.Operation.ADD_NUMBER) continue;
                    add += e.modifier().getAmount();
                    found = true;
                }
            }
        } else {
            for (Map.Entry<Attribute, AttributeModifier> e : item.getType().asItemType().getDefaultAttributeModifiers(EquipmentSlot.HAND).entries()) {
                if (!e.getKey().equals(Attribute.ATTACK_SPEED) || e.getValue().getOperation() != AttributeModifier.Operation.ADD_NUMBER) continue;
                add += e.getValue().getAmount();
                found = true;
            }
        }
        return found ? Math.max(0.1, 4 + add) : -1;
    }

    private static boolean mainHand(EquipmentSlotGroup g) {
        return g == EquipmentSlotGroup.MAINHAND || g == EquipmentSlotGroup.HAND || g == EquipmentSlotGroup.ANY;
    }

    /** Ticks a weapon takes to recharge. */
    static int rechargeTicks(double attackSpeed) {
        return (int) Math.max(4, Math.min(100, Math.round(20 / attackSpeed)));
    }

    /**
     * Mining tools (pickaxe, shovel, hoe) are left alone: their swing also plays while mining, it would look slow.
     * Spears keep their own components.
     */
    private static boolean excluded(ItemStack item) {
        String n = item.getType().name();
        return n.endsWith("_SPEAR") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE");
    }

    private boolean apply(ItemStack item) {
        if (excluded(item)) return clear(item);
        double speed = attackSpeed(item);
        if (speed < 0) return clear(item);
        boolean changed = false;
        Float charge = item.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE) ? item.getData(DataComponentTypes.MINIMUM_ATTACK_CHARGE) : null;
        if (charge == null || Math.abs(charge - minimumCharge) > 1e-4) {
            item.setData(DataComponentTypes.MINIMUM_ATTACK_CHARGE, minimumCharge);
            changed = true;
        }
        if (slowSwing) {
            int ticks = (int) Math.max(3, Math.round(20 / speed * swingShare));
            SwingAnimation swing = item.isDataOverridden(DataComponentTypes.SWING_ANIMATION) ? item.getData(DataComponentTypes.SWING_ANIMATION) : null;
            if (swing == null || swing.duration() != ticks || swing.type() != SwingAnimation.Animation.WHACK) {
                item.setData(DataComponentTypes.SWING_ANIMATION, SwingAnimation.swingAnimation().type(SwingAnimation.Animation.WHACK).duration(ticks).build());
                changed = true;
            }
        } else if (item.isDataOverridden(DataComponentTypes.SWING_ANIMATION)) {
            item.resetData(DataComponentTypes.SWING_ANIMATION);
            changed = true;
        }
        return changed;
    }

    /** Removes what {@link #apply} gave (rule turned off, or the item is no longer a weapon). */
    private static boolean clear(ItemStack item) {
        if (item.getType().name().endsWith("_SPEAR")) return false;
        boolean changed = false;
        if (item.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE)) {
            item.resetData(DataComponentTypes.MINIMUM_ATTACK_CHARGE);
            changed = true;
        }
        if (item.isDataOverridden(DataComponentTypes.SWING_ANIMATION)) {
            item.resetData(DataComponentTypes.SWING_ANIMATION);
            changed = true;
        }
        return changed;
    }

    /** A hit sent before the weapon is recharged is refused (the vanilla client never sends one). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (!enabled || !e.willAttack()) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty() || !hand.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE)) return;
        if (p.getAttackCooldown() + 0.1f < minimumCharge) e.setCancelled(true);
        else attacked.put(p.getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
    }

    /**
     * A swing with a recharged weapon (or the swing of an attack just let through): a particle in front of the
     * player, seen by everyone, even when the swing hits nobody.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e) {
        if (!enabled || swingParticle == null && swingSound == null || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty() || !hand.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE)) return;
        int now = org.bukkit.Bukkit.getCurrentTick();
        Integer hit = attacked.get(p.getUniqueId());
        boolean real = hit != null && now - hit <= 1 || p.getAttackCooldown() + 0.1f >= minimumCharge;
        Integer last = shown.get(p.getUniqueId());
        if (!real || last != null && now - last < 4) return;
        shown.put(p.getUniqueId(), now);
        org.bukkit.Location at = reachPoint(p, hand);
        // a swing in the air in a dungeon: aim assist hits the monster the player was aiming at
        boolean missed = hit == null || now - hit > 1;
        if (missed && assistOn) {
            org.bukkit.entity.LivingEntity target = assisted(p, hand);
            if (target != null) {
                p.attack(target);
                at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
            }
        }
        if (swingParticle != null) p.getWorld().spawnParticle(swingParticle, at, 1, 0, 0, 0, 0);
        if (swingSound != null) {
            float pitch = soundPitch * (0.9f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.2f);
            p.getWorld().playSound(at, swingSound, org.bukkit.SoundCategory.PLAYERS, soundVolume, pitch);
        }
    }

    /**
     * The monster the player was aiming at: within his reach, in front of him, whose body passes within
     * {@code aim-assist.radius} blocks of the aimed line, seen (no wall between); the closest to the line, then to the
     * player. The hitboxes are not enlarged: only a swing that hit nothing is read this way.
     */
    private org.bukkit.entity.LivingEntity assisted(Player p, ItemStack hand) {
        java.util.function.Predicate<org.bukkit.entity.Entity> enemy = assist.enemies(p);
        if (enemy == null) return null;
        org.bukkit.Location eye = p.getEyeLocation();
        org.bukkit.util.Vector origin = eye.toVector(), dir = eye.getDirection().normalize();
        double reach = reach(p, hand);
        org.bukkit.util.RayTraceResult wall = p.getWorld().rayTraceBlocks(eye, dir, reach, org.bukkit.FluidCollisionMode.NEVER, true);
        double limit = wall == null ? reach : wall.getHitPosition().distance(origin);
        org.bukkit.entity.LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(reach + 2, reach + 2, reach + 2)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le) || le.isDead() || !enemy.test(le)) continue;
            org.bukkit.util.BoundingBox box = le.getBoundingBox();
            org.bukkit.util.Vector center = box.getCenter();
            double half = Math.max(box.getWidthX(), box.getWidthZ()) / 2;
            double along = center.clone().subtract(origin).dot(dir);
            if (along <= 0 || along - half > limit) continue;
            org.bukkit.util.Vector closest = origin.clone().add(dir.clone().multiply(along));
            // distance from the aimed line to the body (its height counts: aiming at the legs or the head)
            double dy = Math.max(0, Math.max(box.getMinY() - closest.getY(), closest.getY() - box.getMaxY()));
            double dxz = Math.hypot(center.getX() - closest.getX(), center.getZ() - closest.getZ()) - half;
            double gap = Math.hypot(Math.max(0, dxz), dy);
            if (gap > assistRadius || !p.hasLineOfSight(le)) continue;
            double score = gap * 4 + along;
            if (score < bestScore) {
                bestScore = score;
                best = le;
            }
        }
        return best;
    }

    /** How far the player hits with this item: the weapon's attack range when it has one, else his reach attribute. */
    static double reach(Player p, ItemStack hand) {
        io.papermc.paper.datacomponent.item.AttackRange range = hand.getData(DataComponentTypes.ATTACK_RANGE);
        if (range != null && range.maxReach() > 0) {
            return p.getGameMode() == org.bukkit.GameMode.CREATIVE ? range.maxCreativeReach() : range.maxReach();
        }
        org.bukkit.attribute.AttributeInstance a = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        return a == null ? 3 : a.getValue();
    }

    /**
     * Where the swing reaches: the first creature or block in front of the player within his reach, else the end of
     * his reach; a little below the eyes, where the weapon passes.
     */
    private static org.bukkit.Location reachPoint(Player p, ItemStack hand) {
        org.bukkit.Location eye = p.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection();
        double reach = reach(p, hand);
        org.bukkit.util.RayTraceResult hit = p.getWorld().rayTrace(eye, dir, reach, org.bukkit.FluidCollisionMode.NEVER,
                true, 0.2, e -> e != p && e instanceof org.bukkit.entity.LivingEntity && !(e instanceof org.bukkit.entity.ArmorStand));
        double distance = reach;
        if (hit != null) {
            distance = hit.getHitPosition().distance(eye.toVector());
            // in front of a wall rather than inside it
            if (hit.getHitBlock() != null) distance = Math.max(0.8, distance - 0.3);
        }
        return eye.add(dir.multiply(distance)).subtract(0, 0.3, 0);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        attacked.remove(e.getPlayer().getUniqueId());
        shown.remove(e.getPlayer().getUniqueId());
    }
}
