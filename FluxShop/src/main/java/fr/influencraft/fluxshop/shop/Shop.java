package fr.influencraft.fluxshop.shop;

import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Un coffre magasin : une position, le bloc à y reposer s'il disparaît ({@link #getBlock()}) et
 * la liste ordonnée des objets vendus (une rangée de menu par objet, d'où la limite à {@link #MAX_ITEMS}).
 */
public final class Shop {

    /** Un menu de coffre fait au plus 6 rangées. */
    public static final int MAX_ITEMS = 6;

    private final String id;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private String block;
    private final List<ShopEntry> items;

    public Shop(String id, String world, int x, int y, int z, String block, List<ShopEntry> items) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
        this.items = new ArrayList<>(items);
    }

    public static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public String key() {
        return key(world, x, y, z);
    }

    public String getId() {
        return id;
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    /**
     * @return le bloc posé à l'assignation : BlockData vanilla ({@code minecraft:chest[facing=north,...]})
     * ou {@code itemsadder:namespace:id}, {@code null} si inconnu.
     */
    public String getBlock() {
        return block;
    }

    public void setBlock(String block) {
        this.block = block;
    }

    public List<ShopEntry> getItems() {
        return items;
    }
}
