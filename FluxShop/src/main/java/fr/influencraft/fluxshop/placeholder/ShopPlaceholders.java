package fr.influencraft.fluxshop.placeholder;

import fr.influencraft.flux.api.PlaceholderProvider;
import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.TpShopCooldownManager;
import fr.influencraft.fluxshop.shop.ShopActivity;
import fr.influencraft.fluxshop.shop.ShopEditor;
import org.bukkit.entity.Player;

/**
 * {@code %fluxshop_<params>%}:
 * <ul>
 * <li>{@code player_sell}, {@code player_buy}: the player sold / bought something recently (quests);</li>
 * <li>{@code player_near}: within {@code shop.near-distance} blocks of a shop chest (quests: "visit the shop");</li>
 * <li>{@code tpshop_cooldown}, {@code tpshop_cooldown_formatted}, {@code tpshop_available};</li>
 * <li>{@code editor_count}: items of the shop chest being edited;</li>
 * <li>{@code lang_<key>}: a text of {@code lang/fr.yml}, for the menu files.</li>
 * </ul>
 */
public final class ShopPlaceholders implements PlaceholderProvider {

    private final LangManager lang;
    private final ShopActivity activity;
    private final TpShopCooldownManager tpShopCooldownManager;
    private final ShopEditor editor;
    private final fr.influencraft.fluxshop.shop.ShopRegistry shops;
    private final java.util.function.DoubleSupplier nearDistance;

    public ShopPlaceholders(LangManager lang, ShopActivity activity, TpShopCooldownManager tpShopCooldownManager, ShopEditor editor,
                            fr.influencraft.fluxshop.shop.ShopRegistry shops, java.util.function.DoubleSupplier nearDistance) {
        this.shops = shops;
        this.nearDistance = nearDistance;
        this.lang = lang;
        this.activity = activity;
        this.tpShopCooldownManager = tpShopCooldownManager;
        this.editor = editor;
    }

    @Override
    public String request(Player player, String params) {
        if (params.startsWith("lang_")) return lang.get(params.substring(5));
        if (player == null) return null;
        return switch (params) {
            case "player_sell" -> String.valueOf(activity.hasSold(player.getUniqueId()));
            case "player_buy" -> String.valueOf(activity.hasBought(player.getUniqueId()));
            case "tpshop_cooldown" -> String.valueOf(tpShopCooldownManager.getRemainingSeconds(player.getUniqueId()));
            case "tpshop_cooldown_formatted" -> TpShopCooldownManager.formatDuration(tpShopCooldownManager.getRemainingSeconds(player.getUniqueId()));
            case "tpshop_available" -> String.valueOf(tpShopCooldownManager.getRemainingSeconds(player.getUniqueId()) == 0);
            case "editor_count" -> String.valueOf(editor.count(player));
            case "player_near" -> String.valueOf(near(player));
            default -> null;
        };
    }

    /** Within the near distance of a shop chest of his world. */
    private boolean near(Player player) {
        org.bukkit.Location at = player.getLocation();
        double max = nearDistance.getAsDouble();
        String world = player.getWorld().getName();
        for (fr.influencraft.fluxshop.shop.Shop s : shops.getShops()) {
            if (!s.getWorld().equals(world)) continue;
            double dx = s.getX() + 0.5 - at.getX(), dy = s.getY() - at.getY(), dz = s.getZ() + 0.5 - at.getZ();
            if (dx * dx + dy * dy + dz * dz <= max * max) return true;
        }
        return false;
    }
}
