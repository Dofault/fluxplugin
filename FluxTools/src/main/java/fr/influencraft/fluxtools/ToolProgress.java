package fr.influencraft.fluxtools;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tool XP: blocks mined, crops harvested and monsters killed with a levelled tool in hand.
 *
 * <p>Blocks broken on top by an ability (Filon, Tranchée, Récolte of EcoEnchants) do not count: they are broken while
 * the event of the struck block is handled (nested events), or on the same tick.</p>
 */
final class ToolProgress implements Listener {

    private final FluxTools plugin;
    private final Map<UUID, Integer> depth = new HashMap<>();
    private final Map<UUID, Integer> lastTick = new HashMap<>();

    ToolProgress(FluxTools plugin) {
        this.plugin = plugin;
        if (!fr.influencraft.flux.api.world.PlacedBlocks.available()) {
            plugin.getLogger().warning("No placed blocks tracker in Flux: blocks placed by players give tool XP.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void breakStart(BlockBreakEvent event) {
        depth.merge(event.getPlayer().getUniqueId(), 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void breakEnd(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        int d = depth.getOrDefault(id, 1);
        if (d <= 1) depth.remove(id);
        else depth.put(id, d - 1);
        if (event.isCancelled() || d > 1) return;
        int tick = Bukkit.getCurrentTick();
        Integer before = lastTick.put(id, tick);
        if (before != null && before == tick) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        Tool tool = plugin.tool(item);
        if (tool == null || !tool.hasLevels() || plugin.excluded(player.getWorld())) return;
        Block block = event.getBlock();
        boolean placed = fr.influencraft.flux.api.world.PlacedBlocks.playerPlaced(block);
        ToolState state = ToolState.read(item);
        if (!placed) state.blocks++;
        boolean useful = "houe".equals(tool.family) ? harvested(block, placed) : !placed;
        reward(player, item, tool, state, useful, xp(tool, "mine_block", block.getType().getKey().getKey()));
    }

    /**
     * Crops harvested at their maximum age (planted by players, so a placed one counts once ripe). Any other block,
     * sugar cane, cactus, melon or pumpkin included, counts when it was not placed by a player.
     */
    private static final java.util.Set<org.bukkit.Material> CROPS = java.util.EnumSet.of(org.bukkit.Material.WHEAT,
            org.bukkit.Material.CARROTS, org.bukkit.Material.POTATOES, org.bukkit.Material.BEETROOTS,
            org.bukkit.Material.NETHER_WART, org.bukkit.Material.COCOA, org.bukkit.Material.TORCHFLOWER_CROP,
            org.bukkit.Material.PITCHER_CROP, org.bukkit.Material.SWEET_BERRY_BUSH);

    private static boolean harvested(Block block, boolean placed) {
        if (!CROPS.contains(block.getType())) return !placed;
        return !(block.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void kill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player player = dead.getKiller();
        if (player == null || dead.fromMobSpawner() || plugin.excluded(player.getWorld())) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        Tool tool = plugin.tool(item);
        if (tool == null || !tool.hasLevels()) return;

        boolean melee = false, shot = false;
        EntityDamageEvent cause = dead.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent damage) {
            Entity source = damage.getDamager();
            melee = source.equals(player);
            shot = source instanceof Projectile projectile && player.equals(projectile.getShooter());
        }
        if (!melee && !shot) return;

        ToolState state = ToolState.read(item);
        state.kills++;
        boolean useful = "arc".equals(tool.family) ? shot : melee;
        reward(player, item, tool, state, useful, xp(tool, "kill", dead.getType().getKey().getKey()));
    }

    /**
     * Tool XP of one action: seconds of optimal play it is worth in FluxValue ({@code actionPoints / pointsPerSecond}),
     * so the level thresholds read as play time. The {@code points} of a tool file, when given, win.
     */
    static double xp(Tool tool, String trigger, String target) {
        Double forced = tool.points.get(target);
        if (forced != null) return forced;
        double perSecond = fr.influencraft.flux.api.value.Values.pointsPerSecond();
        if (perSecond <= 0) return 0;
        return fr.influencraft.flux.api.value.Values.actionPoints(trigger, target).orElse(0) / perSecond;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        depth.remove(event.getPlayer().getUniqueId());
        lastTick.remove(event.getPlayer().getUniqueId());
    }

    private void reward(Player player, ItemStack item, Tool tool, ToolState state, boolean useful, double xp) {
        boolean enchants = useful && gain(player, tool, state, xp);
        if (useful) enchants |= mastery(player, item, tool, state, xp);
        plugin.renderer().update(item, tool, state, enchants);
    }

    /** Tool at its maximum level: the XP also makes its mastery enchantments climb (FluxEnchant). */
    private static boolean mastery(Player player, ItemStack item, Tool tool, ToolState state, double xp) {
        var hook = fr.influencraft.fluxtools.api.Tools.mastery();
        if (hook == null || xp <= 0 || tool.level(state.xp) < tool.maxLevel()) return false;
        return hook.gain(player, item, FluxTools.info(tool, state), xp);
    }

    /**
     * Adds tool XP and tells the player when a level is reached.
     *
     * @return true when the level changed, the enchantments are to be written again
     */
    boolean gain(Player player, Tool tool, ToolState state, double xp) {
        if (xp <= 0) return false;
        int before = tool.level(state.xp);
        state.xp += xp;
        int after = tool.level(state.xp);
        if (after == before) return false;
        plugin.messages().send(player, "level-up", "%level%", String.valueOf(after));
        Component unlocked = null;
        for (String id : tool.levelledEnchantments.keySet()) {
            int value = tool.enchantmentLevel(id, after);
            Enchantment ench = Tool.enchantment(id);
            if (value == tool.enchantmentLevel(id, before) || ench == null) continue;
            Component name = Component.text().append(ench.description()).append(Component.text(" " + Format.roman(value))).build();
            player.sendMessage(Texts.colors(plugin.messages().raw("unlocked")).append(name.color(NamedTextColor.WHITE)));
            if (unlocked == null) unlocked = name;
        }
        player.showTitle(Title.title(Texts.colors(plugin.messages().raw("level-up-title", "%level%", String.valueOf(after))),
                unlocked == null ? Component.empty() : unlocked.color(TextColor.color(0xFFD6DE))));
        Sounds.cue(player, "complete");
        if (after >= tool.maxLevel()) {
            plugin.messages().send(player, "mastered");
            if (plugin.getConfig().getBoolean("announce-mastery", true)) {
                Bukkit.broadcast(Texts.colors(plugin.messages().raw("mastered-broadcast", "%player%", player.getName(), "%tool%", tool.name)));
            }
        }
        return true;
    }
}
