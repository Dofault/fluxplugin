package com.ghostchu.quickshop.api.inventory;

import com.ghostchu.quickshop.api.QuickShopAPI;

import com.google.common.collect.MapMaker;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class InventoryWrapperRegistry {

  private final Map<String, InventoryWrapperManager> registry = new MapMaker().makeMap();

  @Nullable
  public String find(final InventoryWrapperManager manager) {

    for(final Map.Entry<String, InventoryWrapperManager> entry : registry.entrySet()) {
      if(entry.getValue() == manager || entry.getValue().equals(manager)) {
        return entry.getKey();
      }
    }
    return null;
  }

  @Nullable
  public InventoryWrapperManager get(final String pluginName) {

    return registry.get(pluginName);
  }

  public void register(@NotNull final Plugin plugin, @NotNull final InventoryWrapperManager manager) {

    // InfluEchoppe : nom de données figé (colonne inv_wrapper de la base), cf. QuickShopAPI#dataName.
    final String name = QuickShopAPI.dataName(plugin);
    if(registry.containsKey(name)) {
      plugin.getLogger().warning("Nag Author: Plugin " + plugin.getName() + " already have a registered InventoryWrapperManager: "
                                 + registry.get(name).getClass().getName() +
                                 " but trying register another new manager: " + manager.getClass().getName() +
                                 ". This may cause unexpected behavior! Replacing with new instance...");
    }
    registry.put(name, manager);
  }

  public void unregister(@NotNull final Plugin plugin) {

    registry.remove(QuickShopAPI.dataName(plugin));
  }

}
