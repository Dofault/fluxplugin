package fr.influencraft.fluxoffering.integration;

import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.PlayerData;
import fr.influencraft.fluxoffering.util.ProgressCalculator;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Exposes read-only progress data for other plugins to build rankups/rewards on top of.
 * FluxOffering itself never grants anything — it only tracks what has been deposited.
 * <p>
 * %offrandes_&lt;collection&gt;%          -&gt; "true" or "false"
 * %offrandes_&lt;collection&gt;_progress% -&gt; "2/3" (item types completed / total types)
 * %offrandes_&lt;collection&gt;_percent%  -&gt; "73" (overall quantity completion, 0-100)
 */
public final class OffrandePlaceholderExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DataManager dataManager;

    public OffrandePlaceholderExpansion(JavaPlugin plugin, ConfigManager configManager, DataManager dataManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "offrandes";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Influencraft";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String collectionId;
        String mode;
        if (params.endsWith("_percent")) {
            collectionId = params.substring(0, params.length() - "_percent".length());
            mode = "percent";
        } else if (params.endsWith("_progress")) {
            collectionId = params.substring(0, params.length() - "_progress".length());
            mode = "progress";
        } else {
            collectionId = params;
            mode = "status";
        }

        CollectionDefinition collection = configManager.getCollection(collectionId);
        if (collection == null) {
            return null;
        }
        if (player == null) {
            return switch (mode) {
                case "percent" -> "0";
                case "progress" -> "0/" + ProgressCalculator.totalTypesWithMoney(collection);
                default -> "false";
            };
        }

        PlayerData data = dataManager.getOffline(player);
        return switch (mode) {
            case "percent" -> String.valueOf(ProgressCalculator.percent(data, collection));
            case "progress" -> ProgressCalculator.completedTypeCount(data, collection) + "/" + ProgressCalculator.totalTypesWithMoney(collection);
            default -> String.valueOf(ProgressCalculator.isComplete(data, collection));
        };
    }
}
