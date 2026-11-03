package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.shops.ChestShop;
import fr.influencraft.flux.api.shops.ChestShops;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every shop, by id, by block and by chunk, and the Flux view of them ({@link ChestShops.Provider}). Stock counts are
 * read from the container whenever it is loaded and kept for the unloaded ones (lists of other plugins, market);
 * changed counts are written in the background ({@link #flushStocks}). Main thread.
 */
final class ShopRegistry implements ChestShops.Provider {

    private final ChestShopModule plugin;
    private final ShopStore store;
    private final Map<Long, Shop> byId = new LinkedHashMap<>();
    private final Map<String, Shop> byBlock = new HashMap<>();
    private final Map<String, List<Shop>> byChunk = new HashMap<>();
    private final Map<Long, Integer> dirtyStocks = new ConcurrentHashMap<>();

    ShopRegistry(ChestShopModule plugin, ShopStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void load() {
        byId.clear();
        byBlock.clear();
        byChunk.clear();
        for (Shop s : store.load().values()) index(s);
    }

    private void index(Shop s) {
        byId.put(s.id, s);
        byBlock.put(s.key(), s);
        byChunk.computeIfAbsent(s.chunkKey(), k -> new ArrayList<>()).add(s);
    }

    Collection<Shop> shops() {
        return byId.values();
    }

    Shop get(long id) {
        return byId.get(id);
    }

    /** The shop of this block, or of the other half of its double chest. */
    Shop at(Block block) {
        if (block == null) return null;
        Shop s = byBlock.get(Shop.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        if (s != null || !plugin.isContainer(block)) return s;
        for (Block half : Containers.halves(block)) {
            s = byBlock.get(Shop.key(half.getWorld().getName(), half.getX(), half.getY(), half.getZ()));
            if (s != null) return s;
        }
        return null;
    }

    List<Shop> inChunk(String world, int chunkX, int chunkZ) {
        return byChunk.getOrDefault(Shop.chunkKey(world, chunkX, chunkZ), List.of());
    }

    List<Shop> ownedBy(UUID owner) {
        List<Shop> out = new ArrayList<>();
        for (Shop s : byId.values()) if (s.isOwner(owner)) out.add(s);
        return out;
    }

    /** Saves a new shop and indexes it; false when the database refused it. */
    boolean add(Shop s) {
        if (!store.insert(s)) return false;
        index(s);
        return true;
    }

    void remove(Shop s) {
        byId.remove(s.id);
        byBlock.remove(s.key());
        List<Shop> chunk = byChunk.get(s.chunkKey());
        if (chunk != null) {
            chunk.remove(s);
            if (chunk.isEmpty()) byChunk.remove(s.chunkKey());
        }
        dirtyStocks.remove(s.id);
        store.delete(s.id);
    }

    void save(Shop s) {
        dirtyStocks.remove(s.id);
        store.update(s);
    }

    /**
     * Counts the stock (selling) or room (buying) of a shop whose chunk is loaded; keeps the former count otherwise.
     * @return the count, -1 when unknown
     */
    int count(Shop s) {
        if (s.unlimited) return Integer.MAX_VALUE;
        if (!s.loaded()) return s.stock;
        Inventory inv = Containers.inventory(s.block());
        if (inv == null) return s.stock;
        int now = s.buying ? Containers.room(inv, s) : Containers.count(inv, s);
        if (now != s.stock) {
            s.stock = now;
            dirtyStocks.put(s.id, now);
        }
        return now;
    }

    /** Writes the changed stock counts (called every few seconds and at shutdown). */
    void flushStocks(boolean now) {
        if (dirtyStocks.isEmpty()) return;
        Map<Long, Integer> batch = new HashMap<>(dirtyStocks);
        batch.forEach(dirtyStocks::remove);
        if (now) store.saveStocks(batch);
        else plugin.host().getServer().getScheduler().runTaskAsynchronously(plugin.host(), () -> store.saveStocks(batch));
    }

    // ---------------------------------------------------------------- ChestShops.Provider (Flux)

    @Override
    public List<ChestShop> all() {
        List<ChestShop> out = new ArrayList<>(byId.size());
        for (Shop s : byId.values()) {
            if (s.bukkitWorld() == null) continue;
            out.add(s.view());
        }
        return out;
    }

    @Override
    public ChestShop at(Location location) {
        Shop s = location == null || location.getWorld() == null ? null : at(location.getBlock());
        if (s == null) return null;
        count(s);
        return s.view();
    }

    @Override
    public void setOption(long shopId, String option, boolean on) {
        Shop s = byId.get(shopId);
        if (s == null || !(on ? s.options.add(option) : s.options.remove(option))) return;
        save(s);
        plugin.displays().update(s);
    }

    @Override
    public void refresh(long shopId) {
        Shop s = byId.get(shopId);
        if (s != null) plugin.displays().update(s);
    }
}
