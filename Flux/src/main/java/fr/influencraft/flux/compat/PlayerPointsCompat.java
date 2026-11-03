// COMPAT 2026-10-30: PlayerPoints replaced by Flux's "influence" currency - remove when no config, menu or plugin
// uses "points give|take|set", "%playerpoints_*%" or the currency id "playerpoints" any more (grep the servers).
package fr.influencraft.flux.compat;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.economy.CurrencyService;
import fr.influencraft.flux.economy.StoredCurrency;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Influence used to be PlayerPoints. Hundreds of rewards (pass, collections, crates) run {@code points give %player% N}
 * and menus read {@code %playerpoints_points%}: while PlayerPoints is not installed, Flux answers both with its
 * {@code influence} currency, so none of those files had to change. The currency id {@code playerpoints} is always
 * accepted as {@code influence}.
 */
public final class PlayerPointsCompat {

    private PlayerPointsCompat() {
    }

    /** Called by Flux once every plugin is enabled. */
    public static void start(JavaPlugin flux, CurrencyService currencies) {
        Economy.alias("playerpoints", "influence");
        StoredCurrency influence = currencies.stored("influence");
        if (influence == null) return;
        if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            flux.getLogger().warning("PlayerPoints is still installed: Influence is now kept by Flux (currency influence), "
                    + "remove PlayerPoints once its balances are migrated (FluxMigration), else /points and %playerpoints_points% "
                    + "show the former balances.");
            return;
        }
        Commands.create(flux, "points").description("Influence (former PlayerPoints command).").suggest(false)
                .executor((sender, label, args) -> currencies.currencyCommand(influence, sender, args))
                .completer((sender, args) -> currencies.currencyComplete(influence, sender, args))
                .register();
        FluxApi.registerPlaceholders(flux, "playerpoints", (player, params) -> {
            if (player == null) return "";
            double v = influence.balance(player);
            return switch (params) {
                case "points" -> String.valueOf((long) v);
                case "points_formatted" -> Format.number(v);
                case "points_shorthand" -> Economy.compact(v);
                default -> null;
            };
        });
        flux.getLogger().info("PlayerPoints compatibility: /points and %playerpoints_*% served by the influence currency");
    }
}
