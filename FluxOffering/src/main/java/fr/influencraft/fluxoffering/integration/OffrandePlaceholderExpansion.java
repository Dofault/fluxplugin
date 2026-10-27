package fr.influencraft.fluxoffering.integration;

import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.PlayerData;
import fr.influencraft.fluxoffering.util.ProgressCalculator;
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
public final class OffrandePlaceholderExpansion {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DataManager dataManager;
    private fr.influencraft.fluxoffering.gui.OfferingMenu menu;

    public void menu(fr.influencraft.fluxoffering.gui.OfferingMenu offeringMenu) {
        this.menu = offeringMenu;
    }

    public OffrandePlaceholderExpansion(JavaPlugin plugin, ConfigManager configManager, DataManager dataManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    /**
     * {@code %offrandes_<collection>[_percent|_progress]%}, and for the menu {@code %offrandes_current_name%},
     * {@code %offrandes_current_percent%}, {@code %offrandes_current_progress%}, {@code %offrandes_current_complete%},
     * {@code %offrandes_editing%} (collection the player looks at). Registered with Flux.
     */
    public String onRequest(OfflinePlayer player, String params) {
        if (params.startsWith("current_") || params.equals("editing")) {
            if (!(player instanceof org.bukkit.entity.Player online) || menu == null) return "";
            if (params.equals("editing")) return String.valueOf(menu.editing(online));
            CollectionDefinition current = menu.selected(online);
            if (current == null) return "";
            PlayerData d = dataManager.get(online);
            return switch (params) {
                case "current_name" -> current.displayName();
                case "current_id" -> current.id();
                case "current_percent" -> String.valueOf(ProgressCalculator.percent(d, current));
                case "current_progress" -> ProgressCalculator.completedTypeCount(d, current) + "/" + ProgressCalculator.totalTypesWithMoney(current);
                case "current_complete" -> String.valueOf(ProgressCalculator.isComplete(d, current));
                default -> null;
            };
        }
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
