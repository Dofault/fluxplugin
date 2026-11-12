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
 * Attack cadence of the whole server (as Wynncraft), the one swing of swords and axes, vanilla and rpg alike: a
 * weapon attacks at its own pace whatever the player clicks. Carried by FluxDungeons because it serves the dungeons
 * first, but it applies everywhere ({@code attack-cadence} of FluxDungeons' config). Fists and every other item stay
 * vanilla.
 * <ul>
 *   <li>Swords and axes get the vanilla item components {@code minimum_attack_charge} (the client neither swings nor
 *   attacks before the weapon is recharged: a double click is one swing) and {@code swing_animation} lasting most of
 *   the recharge (the weapon comes back slowly, slower for a slow weapon). Given through {@link ItemLooks#registerAny};
 *   rpg weapons get them from the rpg (its attack speed is the player's stat, rpg.RpgPlayers#cadence).</li>
 *   <li>A swing is the arm swing the client sends (on a creature, a block or in the air: one signal for all), checked
 *   against the server's own recharge of the player (modified clients, Bedrock players: no spam).</li>
 *   <li>Every real swing shows a particle and plays one sound ({@code swing-particle}, {@code swing-sound}, same
 *   volume for every weapon) where the swing reaches: the player's reach (its attribute, so bonuses of pets or effects
 *   count; the weapon's own attack range when it has one), stopped by the first block or creature on the way.</li>
 *   <li>In the rpg the swing is the class's basic attack ({@link Melee}, set by FluxDungeons); in a dungeon a swing in
 *   the air hits the monster aimed at ({@link Assist}).</li>
 *   <li>The vanilla recharge indicator is hidden by the resource pack (empty sprites over those of the pack
 *   {@code influstatique}, {@code Flux/assets/gen_attack_indicator.py}).</li>
 * </ul>
 * Vanilla hits always land at full charge. Spears keep their own components. Turned off
 * ({@code attack-cadence.enabled: false}), items lose the components at their next refresh.
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

    /** What the dungeons tell: who is fighting in one, and what he may hit. */
    public interface Assist {
        /** Monsters this player may hit, null when he is not in a dungeon (no assist). */
        java.util.function.Predicate<org.bukkit.entity.Entity> enemies(Player player);
    }

    private Assist assist = p -> null;
    private boolean assistOn = true;
    /** Largest angle between where the player looks and a monster that still counts as aiming at it (degrees). */
    private double assistAngle = 35;

    /** The cadence in use: its swing sound is the one sound of every weapon swing (rpg skills included). */
    private static volatile AttackCadence current;

    /**
     * Plays the swing sound of weapons where a swing reaches: the same sound, volume, pitch and category for every
     * weapon, vanilla or rpg ({@code swing-sound} of the config), so none sounds louder than another.
     */
    public static void playSwing(org.bukkit.Location at) {
        AttackCadence c = current;
        String sound = c == null ? "minecraft:entity.player.attack.sweep" : c.swingSound;
        if (sound == null) return;
        float volume = c == null ? 0.7f : c.soundVolume, pitch = c == null ? 1f : c.soundPitch;
        at.getWorld().playSound(at, sound, org.bukkit.SoundCategory.PLAYERS, volume,
                pitch * (0.9f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.2f));
    }

    private final Plugin plugin;

    public AttackCadence(Plugin plugin) {
        this.plugin = plugin;
        current = this;
        // items of the rpg universe get the same cadence from the rpg layer (rpg.RpgPlayers#cadence: the pace depends
        // on each player's attack speed stat, not on the item): left untouched here
        ItemLooks.registerAny(plugin, (id, item) -> !rpg(item) && (enabled ? apply(item) : clear(item)));
    }

    private static boolean rpg(ItemStack item) {
        return !fr.influencraft.flux.api.universe.Universes.VANILLA.equals(fr.influencraft.flux.api.universe.Universes.universeOf(item));
    }

    public void assist(Assist assist) {
        this.assist = assist;
    }

    /** Aim assist of the dungeons ({@code aim-assist} of the config). */
    public void loadAssist(ConfigurationSection c) {
        assistOn = c == null || c.getBoolean("enabled", true);
        assistAngle = c == null ? 35 : Math.max(1, Math.min(90, c.getDouble("angle", 35)));
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
        // swords and axes only (2026-11-11): bows, tridents, maces, tools and every other item stay vanilla
        String n = item.getType().name();
        return !(n.endsWith("_SWORD") || n.endsWith("_AXE") && !n.endsWith("_PICKAXE"));
    }

    private boolean apply(ItemStack item) {
        if (excluded(item)) return clear(item);
        double speed = attackSpeed(item);
        if (speed < 0) return clear(item);
        return pace(item, speed);
    }

    /**
     * Gives a sword or an axe the components of the cadence for an attack speed (attacks per second): the charge it
     * needs and its slow swing. The rpg calls it with the player's own attack speed. True when the item changed.
     */
    public static boolean pace(ItemStack item, double speed) {
        AttackCadence c = current;
        float minimumCharge = c == null ? 1f : c.minimumCharge;
        boolean slowSwing = c == null || c.slowSwing;
        double swingShare = c == null ? 0.8 : c.swingShare;
        boolean changed = false;
        Float charge = item.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE) ? item.getData(DataComponentTypes.MINIMUM_ATTACK_CHARGE) : null;
        if (charge == null || Math.abs(charge - minimumCharge) > 1e-4) {
            item.setData(DataComponentTypes.MINIMUM_ATTACK_CHARGE, minimumCharge);
            changed = true;
        }
        if (slowSwing) {
            int ticks = (int) Math.max(3, Math.round(20 / Math.max(0.1, speed) * swingShare));
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

    // ------------------------------------------------------------------ the swing

    /** Ticks a swing may come early (the client's own recharge, network jitter): never a lost swing. */
    private static final int TOLERANCE = 3;
    /** Ticks a dig is remembered when its end never came. */
    private static final int DIG_MEMORY = 100;

    /** True when this item is paced: a sword or an axe carrying the minimum attack charge (vanilla or rpg). */
    public static boolean paced(ItemStack item) {
        return item != null && !item.isEmpty() && !excluded(item) && item.isDataOverridden(DataComponentTypes.MINIMUM_ATTACK_CHARGE);
    }

    /** What the rpg does with a swing of its players (the class's basic attack). */
    public interface Melee {
        /**
         * A real swing of {@code player} with a paced weapon, on {@code clicked} or in the air (null). True when the
         * swing was the rpg's (it drew the swing and dealt the hits); false lets the vanilla swing happen.
         */
        boolean swing(Player player, org.bukkit.entity.Entity clicked);
    }

    private Melee melee = (p, clicked) -> false;

    public void melee(Melee melee) {
        this.melee = melee;
    }

    /** Tick of each player's last real swing: the recharge counts from it. */
    private final java.util.Map<java.util.UUID, Integer> lastSwing = new java.util.HashMap<>();
    /** Tick of each player's last action that also swings the arm but is no attack (drop, right click). */
    private final java.util.Map<java.util.UUID, Integer> otherAction = new java.util.HashMap<>();
    /** Tick of each player's swing waiting for the end of its tick: a click on a creature may still come with it. */
    private final java.util.Map<java.util.UUID, Integer> pending = new java.util.HashMap<>();
    /** Tick at which each player started digging a block (survival), while he holds the button on it. */
    private final java.util.Map<java.util.UUID, Integer> digging = new java.util.HashMap<>();

    /** Ticks the weapon in hand takes to recharge, from the player's attack speed (the weapon's modifier included). */
    private static int recharge(Player p) {
        org.bukkit.attribute.AttributeInstance a = p.getAttribute(Attribute.ATTACK_SPEED);
        double speed = a == null ? 4 : a.getValue();
        return (int) Math.round(20 / Math.max(0.1, speed));
    }

    private boolean ready(Player p, int now) {
        Integer last = lastSwing.get(p.getUniqueId());
        return last == null || now - last >= recharge(p) - TOLERANCE;
    }

    /**
     * A click on a creature, sent along with the swing of the same click (before or after it, whatever the order): the
     * swing is played from here, with its target, and the swing packet is then the same swing. Too early (modified
     * client): no hit.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!paced(hand)) return;
        int now = org.bukkit.Bukkit.getCurrentTick();
        // the swing packet came first: this hit is that swing
        pending.remove(p.getUniqueId());
        Integer last = lastSwing.get(p.getUniqueId());
        // a hit made by the swing of this tick (aim assist, a model's hit box handing the hit to its creature)
        if (last != null && last == now) return;
        if (!ready(p, now)) {
            e.setCancelled(true);
            return;
        }
        lastSwing.put(p.getUniqueId(), now);
        if (melee.swing(p, e.getAttacked())) {
            e.setCancelled(true);
            return;
        }
        // a vanilla hit always lands at full charge (the server's recharge may lag the client's by a tick or two)
        if (!e.isCancelled() && e.willAttack()) fullCharge(p);
        draw(p, hand, reachPoint(p, hand));
    }

    /**
     * Every swing of the arm the client sends. With a paced weapon the client only swings when the weapon is
     * recharged (spam clicks are neither shown nor sent), on a creature, a block or in the air: each one is a real
     * swing, drawn and heard where it reaches. Swings that are no attack are left out: an item dropped (A), a right
     * click that swung (door, villager), the swings of a block being dug.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e) {
        if (!enabled || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!paced(hand)) return;
        java.util.UUID id = p.getUniqueId();
        int now = org.bukkit.Bukkit.getCurrentTick();
        Integer last = lastSwing.get(id), other = otherAction.get(id), dig = digging.get(id);
        // the swing of a hit already played (the hit comes first, same tick or the next)
        if (last != null && now - last <= 1) return;
        if (other != null && now - other <= 1) return;
        // the first swing on a block is a swing; the next ones, button held, are digging (forgotten after 5 s in
        // case the end of the digging never came)
        if (dig != null && now - dig > 1 && now - dig <= DIG_MEMORY) return;
        if (!ready(p, now)) return;
        // played at the end of the tick: a click on a creature sent with it (after it) makes it a hit instead
        pending.put(id, now);
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            Integer at = pending.get(id);
            if (at == null || at != now) return;
            pending.remove(id);
            if (p.isOnline() && !p.isDead() && paced(p.getInventory().getItemInMainHand())) swingInAir(p);
        });
    }

    /** A swing that hit no creature: drawn where it reaches; in a dungeon, the aim assist may still find one. */
    private void swingInAir(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        lastSwing.put(p.getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
        if (melee.swing(p, null)) return;
        org.bukkit.Location at = reachPoint(p, hand);
        // a swing in the air in a dungeon: aim assist hits the monster the player was aiming at
        if (assistOn) {
            org.bukkit.entity.LivingEntity target = assisted(p, hand);
            if (target != null) {
                fullCharge(p);
                p.attack(target);
                at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
            }
        }
        draw(p, hand, at);
    }

    /** The particle and the sound of a swing, where it reaches. */
    private void draw(Player p, ItemStack hand, org.bukkit.Location at) {
        if (swingParticle != null) p.getWorld().spawnParticle(swingParticle, at, 1, 0, 0, 0, 0);
        playSwing(at);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        otherAction.put(e.getPlayer().getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRightClick(org.bukkit.event.player.PlayerInteractEvent e) {
        org.bukkit.event.block.Action a = e.getAction();
        if (a == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || a == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            otherAction.put(e.getPlayer().getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRightClickEntity(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        otherAction.put(e.getPlayer().getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigStart(org.bukkit.event.block.BlockDamageEvent e) {
        if (!e.getInstaBreak()) digging.put(e.getPlayer().getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigStop(org.bukkit.event.block.BlockDamageAbortEvent e) {
        digging.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        digging.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigEnd(org.bukkit.event.block.BlockBreakEvent e) {
        digging.remove(e.getPlayer().getUniqueId());
    }

    /** The server's own recharge set full: the vanilla damage of the hit is the one of a charged hit. */
    private static java.lang.reflect.Field ticker;
    private static boolean tickerMissing;

    private static void fullCharge(Player p) {
        if (tickerMissing) return;
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            if (ticker == null) {
                for (Class<?> c = handle.getClass(); c != null && ticker == null; c = c.getSuperclass()) {
                    try {
                        ticker = c.getDeclaredField("attackStrengthTicker");
                        ticker.setAccessible(true);
                    } catch (NoSuchFieldException ignored) {
                        // higher up
                    }
                }
                if (ticker == null) throw new NoSuchFieldException("attackStrengthTicker");
            }
            ticker.setInt(handle, 100_000);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            tickerMissing = true;
            org.bukkit.Bukkit.getLogger().warning("[FluxDungeons] Attack charge not reachable, hits keep the server's own charge: " + ex);
        }
    }

    /**
     * The monster the player was aiming at: a swing in the air counts as a click on the monster closest to where he
     * looks, if it lies within {@code aim-assist.angle} degrees of his view (35: a monster 35 degrees aside still
     * counts, 36 no longer does) and within his reach, with no wall between. Nothing is enlarged: only the aim of a
     * swing that hit nothing is read.
     */
    private org.bukkit.entity.LivingEntity assisted(Player p, ItemStack hand) {
        java.util.function.Predicate<org.bukkit.entity.Entity> enemy = assist.enemies(p);
        if (enemy == null) return null;
        org.bukkit.Location eye = p.getEyeLocation();
        org.bukkit.util.Vector origin = eye.toVector(), look = eye.getDirection().normalize();
        double reach = reach(p, hand);
        double maxAngle = Math.toRadians(assistAngle);
        org.bukkit.entity.LivingEntity best = null;
        double bestAngle = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(reach + 2, reach + 2, reach + 2)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le) || le.isDead() || !enemy.test(le)) continue;
            org.bukkit.util.BoundingBox box = le.getBoundingBox();
            // the point of its body closest to the player's eyes: reach and angle are measured to it
            org.bukkit.util.Vector near = new org.bukkit.util.Vector(clamp(origin.getX(), box.getMinX(), box.getMaxX()),
                    clamp(origin.getY(), box.getMinY(), box.getMaxY()), clamp(origin.getZ(), box.getMinZ(), box.getMaxZ()));
            if (near.distanceSquared(origin) > reach * reach) continue;
            org.bukkit.util.Vector to = box.getCenter().subtract(origin);
            if (to.lengthSquared() < 1.0E-6) return le;
            double angle = look.angle(to);
            if (angle > maxAngle || angle >= bestAngle || !p.hasLineOfSight(le)) continue;
            bestAngle = angle;
            best = le;
        }
        return best;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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
        java.util.UUID id = e.getPlayer().getUniqueId();
        lastSwing.remove(id);
        pending.remove(id);
        otherAction.remove(id);
        digging.remove(id);
    }
}
