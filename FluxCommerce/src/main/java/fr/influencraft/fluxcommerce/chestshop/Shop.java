package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.shops.ChestShop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A chest shop: a container (chest, barrel...) that sells an item to players, or buys it from them, by bundles of
 * {@link #bundle} items for {@link #price}. Its stock is what the container holds; nothing is kept outside the
 * container. Main thread only.
 */
final class Shop {

    long id;
    final String world;
    final int x, y, z;
    UUID owner;
    String ownerName;
    /** The item traded, amount 1. */
    ItemStack item;
    int bundle = 1;
    /** Price of one bundle. */
    double price;
    /** Buys from players instead of selling to them. */
    boolean buying;
    /** Admin shop: endless stock or room, no owner paid or paying. */
    boolean unlimited;
    final Set<UUID> friends = new LinkedHashSet<>();
    /** Options offered by other plugins and turned on (FluxLands' town shop...). */
    final Set<String> options = new LinkedHashSet<>();
    /** Items in stock (selling) or room left in items (buying), as last counted; -1 never counted. */
    int stock = -1;
    long created;
    /** Where an imported shop came from ({@code quickshop:<id>}), null for a shop made here. */
    String source;
    /** How it is shown: {@link #SIGN} (a sign on the container) or {@link #FLOATING} (item and text above it). */
    String look = SIGN;
    /** Wood of the sign ({@code OAK}, {@code CHERRY}...: {@code <wood>_WALL_SIGN}). */
    String wood = "OAK";
    /** Side of the container the sign hangs on, null while no sign was placed. */
    org.bukkit.block.BlockFace signFace;

    static final String SIGN = "sign", FLOATING = "floating";

    boolean hasSign() {
        return SIGN.equals(look);
    }

    /** The block of the sign, or null (no sign yet, world not loaded). */
    Block signBlock() {
        Block b = block();
        return b == null || signFace == null ? null : b.getRelative(signFace);
    }

    /** Price of one item. */
    double unitPrice() {
        return price / Math.max(1, bundle);
    }

    Shop(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    /** The container block, or null when its world is not loaded. */
    Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    Location location() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x, y, z);
    }

    boolean loaded() {
        World w = bukkitWorld();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    boolean isOwner(UUID player) {
        return player != null && player.equals(owner);
    }

    /** Owner or friend: may open the container to fill or empty it. */
    boolean mayStock(UUID player) {
        return isOwner(player) || friends.contains(player);
    }

    /** Is this item the one traded (same item exactly: name, enchantments, durability, plugin data). */
    boolean matches(ItemStack other) {
        return other != null && !other.getType().isAir() && item.isSimilar(other);
    }

    ChestShop view() {
        ItemStack shown = item.clone();
        shown.setAmount(Math.max(1, Math.min(bundle, shown.getMaxStackSize())));
        return new ChestShop(id, location(), shown, price, buying, unlimited, owner, ownerName,
                unlimited ? Integer.MAX_VALUE : stock, Set.copyOf(options));
    }

    /** Key of a block position, for the index. */
    static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    String key() {
        return key(world, x, y, z);
    }

    static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    String chunkKey() {
        return chunkKey(world, x >> 4, z >> 4);
    }
}
