package fr.influencraft.flux.api;

import org.bukkit.entity.Player;

/**
 * Placeholders {@code %<prefix>_<params>%} provided by a plugin (see {@link FluxApi#registerPlaceholders}).
 * Resolved by Flux in every menu text, with or without PlaceholderAPI; when PlaceholderAPI is installed,
 * Flux also exposes them there under the same prefix.
 */
@FunctionalInterface
public interface PlaceholderProvider {

    /** Value for {@code params} (the part after "prefix_"), or null when unknown. Player may be null. */
    String request(Player player, String params);
}
