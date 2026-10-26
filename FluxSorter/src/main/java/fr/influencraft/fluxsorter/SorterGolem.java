package fr.influencraft.fluxsorter;

import io.papermc.paper.world.WeatheringCopperState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Golem de cuivre décoratif qui sort du coffre de tri pendant le tri, fait le geste
 * de prendre chaque pile, se tourne vers le coffre cible et la lance, puis redescend.
 *
 * Purement visuel : sans IA, invulnérable, non persistant, sans aucun drop. La pile qu'il
 * tient est une copie d'affichage (le transfert réel est déjà fait), jamais lâchée.
 */
public final class SorterGolem implements Listener {

    /** Hauteur de sortie au-dessus du bas du coffre, et nombre de ticks pour sortir/rentrer. */
    private static final double RISE = 0.35;
    private static final int RISE_TICKS = 12;
    /** Délai entre le geste de prise et le lancer de la pile. */
    public static final int THROW_DELAY = 8;

    private final FluxSorter plugin;
    private final NamespacedKey key;

    public SorterGolem(FluxSorter plugin) {
        this.plugin = plugin;
        this.key = FluxSorter.key("golem_de_tri");
    }

    public boolean enabled() {
        return plugin.conf().getBoolean("golem.enabled", true);
    }

    /** Fait sortir un golem du coffre de tri. Renvoie null si l'apparition est refusée. */
    public CopperGolem spawn(Block sorter) {
        Location base = sorter.getLocation().add(0.5, 0.05, 0.5);
        CopperGolem golem = sorter.getWorld().spawn(base, CopperGolem.class, g -> {
            g.setPersistent(false);
            g.setAI(false);
            g.setGravity(false);
            g.setInvulnerable(true);
            g.setSilent(true);
            g.setCollidable(false);
            g.setCanPickupItems(false);
            g.setRemoveWhenFarAway(false);
            g.setWeatheringState(WeatheringCopperState.OXIDIZED);
            g.setOxidizing(CopperGolem.Oxidizing.waxed());
            g.getEquipment().setItemInMainHandDropChance(0f);
            g.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        if (!golem.isValid()) return null;
        sorter.getWorld().playSound(base, Sound.ENTITY_COPPER_GOLEM_SPAWN, 0.6f, 1.2f);
        sorter.getWorld().spawnParticle(Particle.WAX_OFF, base.clone().add(0, 0.9, 0), 8, 0.3, 0.2, 0.3, 0);
        move(golem, base, base.clone().add(0, RISE, 0), null);
        return golem;
    }

    /** Geste de prise dans le coffre, puis rotation vers la cible avec la pile en main. */
    public void grab(CopperGolem golem, Location target, ItemStack shown) {
        if (golem == null || !golem.isValid()) return;
        golem.setGolemState(CopperGolem.State.GETTING_ITEM);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!golem.isValid()) return;
            golem.getEquipment().setItemInMainHand(shown);
            face(golem, target);
        }, THROW_DELAY / 2);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!golem.isValid()) return;
            golem.swingMainHand();
            golem.getWorld().playSound(golem.getLocation(), Sound.ENTITY_COPPER_GOLEM_ITEM_DROP, 0.5f, 1.1f);
            golem.getEquipment().setItemInMainHand(null);
            golem.setGolemState(CopperGolem.State.IDLE);
        }, THROW_DELAY);
    }

    /** Point de départ du vol : la main du golem, ou le haut du coffre sans golem. */
    public Location hand(CopperGolem golem, Block sorter) {
        if (golem == null || !golem.isValid()) return sorter.getLocation().add(0.5, 0.9, 0.5);
        return golem.getLocation().add(0, 0.7, 0);
    }

    /** Fait rentrer le golem dans le coffre puis le supprime. */
    public void despawn(CopperGolem golem) {
        if (golem == null || !golem.isValid()) return;
        Location from = golem.getLocation();
        move(golem, from, from.clone().subtract(0, RISE, 0), golem::remove);
    }

    private void move(CopperGolem golem, Location from, Location to, Runnable after) {
        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!golem.isValid()) {
                task.cancel();
                return;
            }
            tick[0]++;
            double t = Math.min(1.0, (double) tick[0] / RISE_TICKS);
            Location pos = from.clone().add(to.clone().subtract(from).multiply(t));
            pos.setYaw(golem.getLocation().getYaw());
            golem.teleport(pos);
            if (t >= 1.0) {
                task.cancel();
                if (after != null) after.run();
            }
        }, 1L, 1L);
    }

    private static void face(CopperGolem golem, Location target) {
        Location from = golem.getLocation();
        double dx = target.getX() - from.getX(), dz = target.getZ() - from.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        golem.setRotation(yaw, 0f);
        golem.setBodyYaw(yaw);
    }

    public boolean isSorterGolem(Entity entity) {
        return entity instanceof CopperGolem
                && entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- protections

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isSorterGolem(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (isSorterGolem(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        if (!isSorterGolem(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    /** Filet de sécurité : un golem de tri ne devrait jamais être sauvegardé, sinon on le retire. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (isSorterGolem(entity)) entity.remove();
        }
    }
}
