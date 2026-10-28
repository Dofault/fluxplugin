package fr.influencraft.flux.adapters.itemsadder;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.FontImages.FontImageWrapper;
import dev.lone.itemsadder.api.ItemsAdder;
import fr.influencraft.flux.api.items.CustomItems;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** ItemsAdder behind {@link CustomItems}: the only class of the suite that talks to ItemsAdder. */
public final class ItemsAdderItems implements CustomItems.Provider, Listener {

    @EventHandler
    public void onLoad(dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent event) {
        CustomItems.fireLoaded();
    }

    @Override
    public String name() {
        return "ItemsAdder";
    }

    @Override
    public boolean loaded() {
        try {
            return ItemsAdder.areItemsLoaded();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String id(ItemStack item) {
        CustomStack s = CustomStack.byItemStack(item);
        return s == null ? null : s.getNamespacedID();
    }

    @Override
    public ItemStack item(String id) {
        CustomStack s = CustomStack.getInstance(id);
        return s == null ? null : s.getItemStack().clone();
    }

    @Override
    public boolean exists(String id) {
        return CustomStack.isInRegistry(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> ids() {
        List<String> out = new ArrayList<>();
        for (Object o : (Iterable<Object>) CustomStack.getNamespacedIdsInRegistry()) out.add(String.valueOf(o));
        return out;
    }

    @Override
    public String glyphs(String text) {
        return FontImageWrapper.replaceFontImages(text);
    }

    @Override
    public boolean glyphExists(String id) {
        try {
            return new FontImageWrapper(id).exists();
        } catch (IllegalArgumentException e) {
            return false; // ItemsAdder throws for an unknown id instead of answering false
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> glyphIds() {
        List<String> out = new ArrayList<>();
        for (Object o : (Iterable<Object>) FontImageWrapper.getNamespacedIdsInRegistry()) out.add(String.valueOf(o));
        return out;
    }

    @Override
    public int glyphWidth(String id) {
        if (!glyphExists(id)) return 0;
        return new FontImageWrapper(id).getWidth();
    }

    @Override
    public String blockId(Block block) {
        CustomBlock b = CustomBlock.byAlreadyPlaced(block);
        return b == null ? null : b.getNamespacedID();
    }

    @Override
    public boolean placeBlock(String id, Location location) {
        return CustomBlock.place(id, location) != null;
    }
}
