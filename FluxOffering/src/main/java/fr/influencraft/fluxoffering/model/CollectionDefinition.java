package fr.influencraft.fluxoffering.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A collection definition as configured in collections.yml.
 * Item order is preserved (LinkedHashMap) so the GUI layout is stable and predictable.
 */
public final class CollectionDefinition {

    private final String id;
    private String displayName;
    private String permission;
    private long money;
    private final LinkedHashMap<ItemKey, Integer> items = new LinkedHashMap<>();
    private final Map<ItemKey, String> customNames = new LinkedHashMap<>();

    public CollectionDefinition(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public void displayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * @return the LuckPerms/Bukkit permission node required to access this collection,
     * or null/blank if it is open to anyone with offrandes.use.
     */
    public String permission() {
        return permission;
    }

    public void permission(String permission) {
        this.permission = permission;
    }

    public boolean hasPermissionRequirement() {
        return permission != null && !permission.isBlank();
    }

    /**
     * @return the amount of BitBlock required to complete this offrande, or 0 if none is
     * requested (in which case the money slot isn't shown to players at all).
     */
    public long money() {
        return money;
    }

    public void money(long money) {
        this.money = Math.max(0, money);
    }

    public boolean hasMoneyRequirement() {
        return money > 0;
    }

    public LinkedHashMap<ItemKey, Integer> items() {
        return items;
    }

    public Map<ItemKey, String> customNames() {
        return customNames;
    }

    public String displayNameFor(ItemKey key) {
        String custom = customNames.get(key);
        return custom != null ? custom : key.fallbackDisplayName();
    }

    public int totalItemTypes() {
        return items.size();
    }

    public long totalRequired() {
        return items.values().stream().mapToLong(Integer::longValue).sum();
    }
}
