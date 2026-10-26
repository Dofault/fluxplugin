package fr.influencraft.flux.api.list;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One element of a list shown in a Flux menu. Its values fill the {@code %entry_<key>%} placeholders of the
 * list's button template; {@code %entry_id%} is its identifier and {@code material: entry} shows its item.
 *
 * <pre>
 *   ListEntry.of(shop.id()).item(shop.item()).set("price", Format.number(shop.price())).set("owner", shop.owner())
 * </pre>
 */
public final class ListEntry {

    private final String id;
    private final Map<String, String> values = new LinkedHashMap<>();
    private ItemStack item;

    private ListEntry(String id) {
        this.id = id;
        values.put("id", id);
    }

    public static ListEntry of(String id) {
        return new ListEntry(id);
    }

    public ListEntry set(String key, Object value) {
        values.put(key, value == null ? "" : String.valueOf(value));
        return this;
    }

    /** Item shown by {@code material: entry} (copied, marked as a menu button by Flux). */
    public ListEntry item(ItemStack item) {
        this.item = item;
        return this;
    }

    public String id() {
        return id;
    }

    public Map<String, String> values() {
        return values;
    }

    public ItemStack item() {
        return item;
    }

    /** Text searched by the list's search ({@code [list] <id> search}): every value, lower case. */
    public String searchText() {
        return String.join(" ", values.values()).toLowerCase(java.util.Locale.ROOT);
    }
}
