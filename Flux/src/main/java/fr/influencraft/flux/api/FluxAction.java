package fr.influencraft.flux.api;

import org.bukkit.entity.Player;

/**
 * A button action type provided by another plugin, written {@code [type] argument} in a menu file
 * (see {@link FluxApi#registerAction}). Runs on the main thread, one tick after the click.
 */
@FunctionalInterface
public interface FluxAction {

    /**
     * @param view     the menu the click came from, or null (open/close actions run outside a menu)
     * @param argument text after {@code [type]}, placeholders already resolved
     */
    void run(Player player, MenuView view, String argument);
}
