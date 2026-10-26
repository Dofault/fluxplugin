package fr.influencraft.influshop.model;

import org.bukkit.Material;

import java.util.List;

/**
 * One catalog line. Every entry works the same way regardless of what plugin the item
 * originally came from: {@code displayMaterial}/{@code displayModelData}/{@code displayName}/
 * {@code displayLore} are a one-time snapshot of the real item's own look, captured the moment
 * an admin drops it into the shop's editor (works for a vanilla item, an ItemsAdder item, or
 * anything else — nothing plugin-specific to hook). {@code command} is whatever the admin
 * typed to fulfil a purchase, run at buy time with {@code %player%} substituted.
 * <p>
 * Deliberately no live "rebuild the real item via some other plugin's API" — that turned out
 * fragile (wrong API guessed, wrong item category, stale slots) and is not worth the
 * complexity here. If the source plugin's item changes later, the snapshot has to be
 * re-captured by hand (drop the updated item in again).
 */
public final class ShopEntry {

    private final String id;
    private String command;
    private CommandRunAs runAs;
    private Material displayMaterial;
    private int displayModelData;
    private String displayName;
    private List<String> displayLore;
    private int price;

    public ShopEntry(String id, String command, CommandRunAs runAs, Material displayMaterial,
                      int displayModelData, String displayName, List<String> displayLore, int price) {
        this.id = id;
        this.command = command;
        this.runAs = runAs;
        this.displayMaterial = displayMaterial;
        this.displayModelData = displayModelData;
        this.displayName = displayName;
        this.displayLore = displayLore;
        this.price = price;
    }

    public String id() {
        return id;
    }

    public String command() {
        return command;
    }

    public CommandRunAs runAs() {
        return runAs;
    }

    public Material displayMaterial() {
        return displayMaterial;
    }

    public int displayModelData() {
        return displayModelData;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> displayLore() {
        return displayLore;
    }

    public int price() {
        return price;
    }

    public void price(int price) {
        this.price = price;
    }
}
