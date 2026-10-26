package io.dofault.supermarket.shop;

import io.dofault.supermarket.integration.ItemsAdderHook;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Repose les coffres magasins absents de leur position (supprimés au WorldEdit, monde régénéré,
 * coffre posé dans une copie du monde...), avec le bloc mémorisé à l'assignation.
 * <p>
 * Lancé au premier tick après le démarrage (mondes chargés), à chaque chargement de monde et,
 * pour les blocs ItemsAdder, une fois les données d'ItemsAdder chargées : avant ça, ItemsAdder
 * ne connaît pas encore ses blocs et ne pourrait ni les reconnaître ni les poser.
 */
public class ShopBlockRestorer implements Listener {

    private static final String ITEMSADDER_PREFIX = "itemsadder:";

    private final JavaPlugin plugin;
    private final ShopRegistry registry;

    public ShopBlockRestorer(JavaPlugin plugin, ShopRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** Repose ce qui manque dans tous les mondes chargés. */
    public void restoreAll() {
        for (World world : Bukkit.getWorlds()) {
            restore(world);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        restore(event.getWorld());
    }

    private void restore(World world) {
        boolean itemsAdderReady = ItemsAdderHook.areItemsLoaded();
        for (Shop shop : registry.getShops()) {
            if (!shop.getWorld().equals(world.getName())) {
                continue;
            }
            if (shop.getBlock() == null) {
                // Coffre assigné avant la remise en place automatique : on mémorise le bloc
                // présent, s'il est d'un type autorisé, pour pouvoir le reposer à l'avenir.
                Block current = world.getBlockAt(shop.getX(), shop.getY(), shop.getZ());
                if ((itemsAdderReady || !ItemsAdderHook.isEnabled()) && registry.isAllowedBlock(current)) {
                    registry.rememberBlock(shop, current);
                }
                continue;
            }
            boolean itemsAdder = shop.getBlock().toLowerCase().startsWith(ITEMSADDER_PREFIX);
            if (itemsAdder && !itemsAdderReady) {
                continue; // reposé par le prochain appel, une fois ItemsAdder chargé
            }
            Block block = world.getBlockAt(shop.getX(), shop.getY(), shop.getZ());
            if (isInPlace(shop, block, itemsAdder)) {
                continue;
            }
            String previous = block.getType().name();
            if (place(shop, block, itemsAdder)) {
                plugin.getLogger().info("Coffre magasin " + shop.getId() + " reposé en " + world.getName() + " "
                        + shop.getX() + " " + shop.getY() + " " + shop.getZ() + " (il y avait : " + previous + ")");
            } else {
                plugin.getLogger().warning("Impossible de reposer le coffre magasin " + shop.getId() + " ("
                        + shop.getBlock() + ")");
            }
        }
    }

    private boolean isInPlace(Shop shop, Block block, boolean itemsAdder) {
        if (itemsAdder) {
            String id = ItemsAdderHook.blockId(block);
            return id != null && id.equalsIgnoreCase(shop.getBlock().substring(ITEMSADDER_PREFIX.length()));
        }
        BlockData data = parse(shop.getBlock());
        return data == null || block.getType() == data.getMaterial();
    }

    private boolean place(Shop shop, Block block, boolean itemsAdder) {
        if (itemsAdder) {
            return ItemsAdderHook.place(shop.getBlock().substring(ITEMSADDER_PREFIX.length()), block);
        }
        BlockData data = parse(shop.getBlock());
        if (data == null) {
            return false;
        }
        // Une moitié de coffre double reposée seule s'afficherait coupée en deux.
        if (data instanceof Chest chest) {
            chest.setType(Chest.Type.SINGLE);
        }
        block.setBlockData(data, false);
        return true;
    }

    private BlockData parse(String value) {
        try {
            return Bukkit.createBlockData(value);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("shops.yml : bloc illisible '" + value + "'");
            return null;
        }
    }
}
