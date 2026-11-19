package fr.influencraft.fluxjobs;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.jobs.JobXpGainEvent;
import fr.influencraft.flux.api.util.PlayerSettings;
import fr.influencraft.flux.api.world.Coins;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A coin of the server's money springs out of what a job just paid for (the broken block, the monster, the fishing
 * hook, the furnace) and flies to the player: he sees that the action earns money. Seen by him only (Flux's
 * {@link Coins#pop}); the money itself is paid by the job plugin.
 * <p>
 * The job service tells that an action was paid ({@link JobXpGainEvent}), not where: the place is the last thing the
 * player did in the same tick, remembered here at the lowest priority, before the job plugin reads the same event. A
 * catch paid at the end of a fishing game, ticks after the bite, comes out of the hook. An
 * action without a place of its own (smelting, brewing, enchanting) uses the block he looks at.
 */
final class JobCoinsListener implements Listener {

    /**
     * Per-player toggles, on by default: the coin and its sound, each on its own. The keys are those of the settings
     * window of Flux (options {@code job_coins} and {@code job_coins_sound} of settings.yml), read here directly.
     */
    static final String SETTING = "flux:job_coins";
    static final String SOUND_SETTING = "flux:job_coins_sound";

    private record Mark(Location at, int tick) {
    }

    private final String icon;
    private final Map<UUID, Mark> marks = new HashMap<>();
    private final Map<UUID, Mark> shown = new HashMap<>();
    /** Where each player's hook last was: a custom fishing game ends long after the last fishing event. */
    private final Map<UUID, Mark> hooks = new HashMap<>();
    private static final int HOOK_TICKS = 600;

    JobCoinsListener(String icon) {
        this.icon = icon;
    }

    boolean isEnabled(OfflinePlayer player) {
        return PlayerSettings.flag(player, SETTING, true);
    }

    void setEnabled(Player player, boolean enabled) {
        PlayerSettings.set(player, SETTING, enabled);
    }

    private void mark(Player player, Location at) {
        marks.put(player.getUniqueId(), new Mark(at, Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        mark(event.getPlayer(), event.getBlock().getLocation().toCenterLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        mark(event.getPlayer(), event.getHarvestedBlock().getLocation().toCenterLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        mark(event.getPlayer(), above(event.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageByEntityEvent event) {
        Entity by = event.getDamager();
        if (by instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) by = shooter;
        if (by instanceof Player player) mark(player, middle(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) mark(killer, middle(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFish(PlayerFishEvent event) {
        Location hook = event.getHook().getLocation().add(0, 0.3, 0);
        mark(event.getPlayer(), hook);
        // the cast itself is not a place: the hook is still at the player's hand
        if (event.getState() != PlayerFishEvent.State.FISHING) {
            hooks.put(event.getPlayer().getUniqueId(), new Mark(hook, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        marks.remove(event.getPlayer().getUniqueId());
        shown.remove(event.getPlayer().getUniqueId());
        hooks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPaid(JobXpGainEvent event) {
        Player player = event.player();
        if (event.gained() <= 0) return;
        boolean coins = isEnabled(player);
        boolean sound = PlayerSettings.flag(player, SOUND_SETTING, true);
        if (!coins) {
            // the sound alone: he hears each paid action
            if (sound) Coins.chime(player, Coins.POP_VOLUME);
            return;
        }
        int now = Bukkit.getCurrentTick();
        Mark mark = marks.get(player.getUniqueId());
        Location at;
        if (mark != null && now - mark.tick() <= 1 && mark.at().getWorld() == player.getWorld()) {
            at = mark.at();
        } else if (hook(player, now) instanceof Location water) {
            at = water;
        } else {
            Block looked = player.getTargetBlockExact(5);
            if (looked == null) return;
            at = above(looked);
        }
        // one action paying two jobs is one coin
        Mark last = shown.put(player.getUniqueId(), new Mark(at, now));
        if (last != null && last.tick() == now && last.at().equals(at)) return;
        ItemStack coin = Items.of(icon, player);
        if (coin != null) Coins.pop(coin).pickupVolume(sound ? Coins.POP_VOLUME : 0).burst(at, 1, List.of(player));
    }

    /** The hook in the water, or where it was when the fish bit (used once), else null. */
    private Location hook(Player player, int now) {
        Mark last = hooks.remove(player.getUniqueId());
        if (player.getFishHook() != null && player.getFishHook().isValid()) {
            return player.getFishHook().getLocation().add(0, 0.3, 0);
        }
        return last != null && now - last.tick() <= HOOK_TICKS && last.at().getWorld() == player.getWorld() ? last.at() : null;
    }

    private static Location above(Block block) {
        return block.getLocation().add(0.5, 1.15, 0.5);
    }

    private static Location middle(Entity entity) {
        return entity.getLocation().add(0, entity.getHeight() / 2, 0);
    }
}
