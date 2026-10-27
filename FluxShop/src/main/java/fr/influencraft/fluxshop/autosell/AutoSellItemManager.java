package fr.influencraft.fluxshop.autosell;

import fr.influencraft.fluxshop.managers.LangManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * The physical "Vente Automatique" toggle item: a non-stackable dye that swaps between
 * {@code LIGHT_BLUE_DYE} (inactive) and {@code CYAN_DYE} (active) on right-click. Its state
 * lives entirely on the ItemStack's persistent data — there is no per-player setting to keep
 * in sync, the item itself is the setting, so it stays correct if it's dropped, traded, or the
 * player logs out.
 */
public class AutoSellItemManager {

    private final LangManager lang;
    private final NamespacedKey markerKey;
    private final NamespacedKey activeKey;

    public AutoSellItemManager(JavaPlugin plugin, LangManager lang) {
        this.lang = lang;
        this.markerKey = new NamespacedKey(plugin, "autosell_marker");
        this.activeKey = new NamespacedKey(plugin, "autosell_active");
    }

    public boolean isAutoSellItem(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    public boolean isActive(ItemStack stack) {
        if (!isAutoSellItem(stack)) {
            return false;
        }
        Byte value = stack.getItemMeta().getPersistentDataContainer().get(activeKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    /**
     * Supplies the lore placeholders describing the holder's exception setup ({@code %profile%},
     * {@code %exceptions%}). Plugged in after construction by whoever owns the per-player
     * profiles; without it the lore falls back to the server default.
     */
    public interface LoreInfo {
        Map<String, String> placeholders(Player player);
    }

    private LoreInfo loreInfo;

    public void setLoreInfo(LoreInfo loreInfo) {
        this.loreInfo = loreInfo;
    }

    private Map<String, String> placeholders(Player player) {
        return loreInfo == null ? Map.of() : loreInfo.placeholders(player);
    }

    /**
     * @return a fresh, inactive copy of the toggle item, its lore describing {@code owner}'s
     * exception setup ({@code null} = server default).
     */
    public ItemStack create(Player owner) {
        return build(false, placeholders(owner));
    }

    public ItemStack build(boolean active, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(active ? Material.CYAN_DYE : Material.LIGHT_BLUE_DYE);
        ItemMeta meta = stack.getItemMeta();
        meta.setMaxStackSize(1);
        meta.setDisplayName(lang.get(active ? "autosell-item-name-on" : "autosell-item-name-off"));
        meta.setLore(fr.influencraft.flux.api.text.Texts.wrapLines(lang.getList(active ? "autosell-item-lore-on" : "autosell-item-lore-off", placeholders)));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(activeKey, PersistentDataType.BYTE, (byte) (active ? 1 : 0));
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * @return a fresh stack with the opposite state of the given one. Deliberately doesn't
     * mutate {@code stack} in place: since 1.20.5's data-component ItemStack rework, a stack
     * obtained from an interact event isn't reliably the same live object backing the
     * inventory slot — {@code setType}/{@code setItemMeta} on it can silently fail to show up
     * in the player's hand. Callers must write the result back via
     * {@code PlayerInventory#setItemInMainHand(ItemStack)} (or the equivalent slot setter).
     */
    public ItemStack toggled(Player player, ItemStack stack) {
        return build(!isActive(stack), placeholders(player));
    }

    /**
     * Rewrites every toggle item the player carries (storage + off-hand) so its lore reflects
     * their current exception setup, keeping each item's active state. Same "write a fresh stack
     * back" rule as {@link #toggled}.
     */
    public void refreshLore(Player player) {
        Map<String, String> placeholders = placeholders(player);
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (isAutoSellItem(contents[i])) {
                player.getInventory().setItem(i, build(isActive(contents[i]), placeholders));
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isAutoSellItem(offHand)) {
            player.getInventory().setItemInOffHand(build(isActive(offHand), placeholders));
        }
    }

    public boolean hasAny(Player player) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isAutoSellItem(stack)) {
                return true;
            }
        }
        return isAutoSellItem(player.getInventory().getItemInOffHand());
    }

    public boolean hasActiveIn(Player player) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isAutoSellItem(stack) && isActive(stack)) {
                return true;
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return isAutoSellItem(offHand) && isActive(offHand);
    }
}
