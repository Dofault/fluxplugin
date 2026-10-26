package fr.influencraft.influshop.gui;

import fr.influencraft.influshop.model.SortMode;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;

public final class ShopMenuHolder implements InfluShopHolder {

    private Inventory inventory;
    private int page = 0;
    private SortMode sortMode = SortMode.ALL;
    private boolean editorMode = false;

    /**
     * Rebuilt on every {@code populate()} call: which catalog entry id sits behind each item
     * slot on the currently displayed page. Looking this up from the displayed ItemStack
     * itself (like InfluOffrande does for plain materials/ItemsAdder ids) doesn't work here —
     * several entries can share the exact same underlying Material/model, so the slot index
     * is the only reliable key.
     */
    private final Map<Integer, String> slotToEntryId = new HashMap<>();

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public int page() {
        return page;
    }

    public void page(int page) {
        this.page = page;
    }

    public SortMode sortMode() {
        return sortMode;
    }

    public void sortMode(SortMode sortMode) {
        this.sortMode = sortMode;
    }

    public boolean editorMode() {
        return editorMode;
    }

    public void editorMode(boolean editorMode) {
        this.editorMode = editorMode;
    }

    public Map<Integer, String> slotToEntryId() {
        return slotToEntryId;
    }
}
