package fr.influencraft.flux.adapters.quickshop;

import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.event.economy.ShopPurchaseEvent;
import com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent;
import com.ghostchu.quickshop.api.event.management.ShopPermissionCheckEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import fr.influencraft.flux.api.shops.ChestShop;
import fr.influencraft.flux.api.shops.ChestShopPurchaseEvent;
import fr.influencraft.flux.api.shops.ChestShopTradeEvent;
import fr.influencraft.flux.api.shops.ChestShops;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/** QuickShop (FluxChestShop) behind {@link ChestShops}: the only class of the suite using its API. */
public final class QuickShopShops implements ChestShops.Provider, Listener {

    @Override
    public String name() {
        return QuickShopAPI.getPluginInstance().getName();
    }

    static ChestShop of(Shop shop) {
        Location loc = shop.bukkitLocation();
        return new ChestShop(shop.getShopId(), loc == null ? null : loc.clone(), shop.getItem().clone(), shop.getPrice(),
                shop.isBuying(), shop.isUnlimited(), shop.getOwner().getUniqueIdIfRealPlayer().orElse(null),
                shop.getOwner().getUsernameOptional().orElse(shop.getOwner().getDisplay()), stock(shop, loc));
    }

    /** Stock (selling) or room (buying), only when the chunk is loaded: -1 otherwise. */
    private static int stock(Shop shop, Location loc) {
        if (shop.isUnlimited()) return Integer.MAX_VALUE;
        World w = loc == null ? null : loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return -1;
        try {
            return shop.isBuying() ? shop.getRemainingSpace() : shop.getRemainingStock();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    public List<ChestShop> all() {
        List<ChestShop> out = new ArrayList<>();
        for (Shop shop : QuickShopAPI.getInstance().getShopManager().getAllShops()) {
            try {
                out.add(of(shop));
            } catch (RuntimeException ignored) {
                // shop in an unloaded world
            }
        }
        return out;
    }

    @Override
    public ChestShop at(Location location) {
        Shop shop = QuickShopAPI.getInstance().getShopManager().getShop(location, true);
        return shop == null ? null : of(shop);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void beforePurchase(ShopPurchaseEvent e) {
        ChestShopPurchaseEvent event = new ChestShopPurchaseEvent(of(e.getShop()),
                e.getPurchaser().getUniqueIdIfRealPlayer().orElse(null), e.getTotal());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) e.setCancelled(true, event.reason());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterPurchase(ShopSuccessPurchaseEvent e) {
        Bukkit.getPluginManager().callEvent(new ChestShopTradeEvent(of(e.getShop()),
                e.getPurchaser().getUniqueIdIfRealPlayer().orElse(null),
                e.getPurchaser().getUsernameOptional().orElse(e.getPurchaser().getDisplay()),
                e.getAmount(), e.getBalanceWithoutTax(), e.getTax()));
    }

    /** The shop plugin only lets the owner remove a shop: the rules of {@link ChestShops#allowRemoval} add others. */
    @EventHandler(priority = EventPriority.HIGH)
    public void removal(ShopPermissionCheckEvent e) {
        if (e.hasPermission() || !BuiltInShopPermission.DELETE.getRawNode().equals(e.permissionNode())) return;
        if (!QuickShopAPI.getPluginInstance().getName().equals(e.pluginNamespace())) return;
        Shop shop = e.shop().orElse(null);
        if (shop != null && ChestShops.mayRemove(of(shop), e.playerUUID())) e.hasPermission(true);
    }
}
