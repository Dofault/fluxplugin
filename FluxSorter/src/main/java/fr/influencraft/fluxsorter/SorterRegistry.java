package fr.influencraft.fluxsorter;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Liste des coffres de tri dans les chunks chargés, uniquement pour les particules :
 * toute décision de tri relit le bloc lui-même, jamais ce cache.
 */
public final class SorterRegistry implements Listener {

    private final FluxSorter plugin;
    /** Monde → clé de chunk → blocs coffres de tri. */
    private final Map<UUID, Map<Long, Set<Block>>> sorters = new HashMap<>();
    private BukkitTask particleTask;

    public SorterRegistry(FluxSorter plugin) {
        this.plugin = plugin;
    }

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) scan(chunk);
        }
        restartParticles();
    }

    public void stop() {
        if (particleTask != null) particleTask.cancel();
        sorters.clear();
    }

    public void restartParticles() {
        if (particleTask != null) particleTask.cancel();
        particleTask = null;
        long period = Math.max(2, plugin.conf().getLong("particles.interval-ticks", 10));
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnParticles, period, period);
    }

    public void add(Block block) {
        sorters.computeIfAbsent(block.getWorld().getUID(), w -> new HashMap<>())
                .computeIfAbsent(Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4), c -> new HashSet<>())
                .add(block);
    }

    public void remove(Block block) {
        Map<Long, Set<Block>> byChunk = sorters.get(block.getWorld().getUID());
        if (byChunk == null) return;
        long chunkKey = Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4);
        Set<Block> set = byChunk.get(chunkKey);
        if (set == null) return;
        set.remove(block);
        if (set.isEmpty()) byChunk.remove(chunkKey);
    }

    private void scan(Chunk chunk) {
        // Toutes les variantes : un coffre de tri harmonisé par vanilla garde sa marque et sera réparé.
        for (BlockState state : chunk.getTileEntities(b -> SorterListener.isCopperChest(b.getType()), false)) {
            if (plugin.sorterItem().hasMark(state)) add(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        scan(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Map<Long, Set<Block>> byChunk = sorters.get(event.getWorld().getUID());
        if (byChunk != null) byChunk.remove(event.getChunk().getChunkKey());
    }

    private void spawnParticles() {
        boolean particles = plugin.conf().getBoolean("particles.enabled", true);
        double range = plugin.conf().getDouble("particles.player-distance", 24);
        double rangeSq = range * range;
        for (Map<Long, Set<Block>> byChunk : sorters.values()) {
            for (Set<Block> set : byChunk.values()) {
                for (Block block : set) {
                    // Coffre de tri harmonisé par vanilla en une autre variante de coffre en cuivre
                    // (connexion en double coffre) : on lui rend sa variante et on le redéfait.
                    if (block.getType() != SorterItem.MATERIAL && plugin.sorterItem().repair(block)) {
                        SorterListener.unlink(block);
                    }
                    // Bloc disparu sans passer par nos événements (WorldEdit, casse par un plugin...).
                    if (block.getType() != SorterItem.MATERIAL) continue;
                    // Filet de sécurité : un coffre de tri rattaché en double coffre est redéfait.
                    if (SorterListener.isLinked(block)) SorterListener.unlink(block);
                    if (!particles) continue;
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    boolean seen = false;
                    for (Player p : block.getWorld().getPlayers()) {
                        if (p.getLocation().distanceSquared(center) <= rangeSq) {
                            seen = true;
                            break;
                        }
                    }
                    if (!seen) continue;
                    World world = block.getWorld();
                    world.spawnParticle(Particle.WAX_ON, center.clone().add(0, 0.55, 0), 2, 0.3, 0.05, 0.3, 0);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, center, 1, 0.45, 0.4, 0.45, 0);
                }
            }
        }
    }
}
