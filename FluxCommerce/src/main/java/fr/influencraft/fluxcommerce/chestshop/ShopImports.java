package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.fluxcommerce.api.Shops;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

/** {@link Shops} backend: shops of a former plugin added once each (by source, and never on a block that has one). */
final class ShopImports implements Shops.Backend {

    private final ChestShopModule plugin;
    private Set<String> imported;

    ShopImports(ChestShopModule plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean importShop(Shops.Import i) {
        if (imported == null) imported = new HashSet<>(plugin.store().sources());
        if (i.source() != null && imported.contains(i.source())) return false;
        for (Shop other : plugin.registry().shops()) {
            if (other.x == i.x() && other.y == i.y() && other.z == i.z() && other.world.equals(i.world())) return false;
        }
        Shop s = new Shop(i.world(), i.x(), i.y(), i.z());
        s.owner = i.owner();
        s.ownerName = i.ownerName();
        ItemStack item = i.item().clone();
        s.bundle = Math.max(1, Math.min(item.getAmount(), item.getMaxStackSize()));
        item.setAmount(1);
        s.item = item;
        s.price = Math.max(0, i.price());
        s.buying = i.buying();
        s.unlimited = i.unlimited() || i.owner() == null;
        if (i.friends() != null) for (var f : i.friends()) if (!f.equals(i.owner())) s.friends.add(f);
        if (i.options() != null) s.options.addAll(i.options());
        s.created = i.created() > 0 ? i.created() : System.currentTimeMillis();
        s.source = i.source();
        if (!plugin.registry().add(s)) throw new IllegalStateException("database refused shop " + i.source());
        if (i.source() != null) imported.add(i.source());
        if (s.loaded()) {
            plugin.registry().count(s);
            plugin.displays().update(s);
        }
        return true;
    }
}
