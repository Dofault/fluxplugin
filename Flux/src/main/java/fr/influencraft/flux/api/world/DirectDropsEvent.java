package fr.influencraft.flux.api.world;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops about to go straight into a player's inventory without touching the ground (telekinesis of an enchantment),
 * fired by Flux's adapter for the plugin that delivers them (eco today). Change {@link #items()} to take some away.
 */
public final class DirectDropsEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final List<ItemStack> items;

    public DirectDropsEvent(Player player, List<ItemStack> items) {
        this.player = player;
        this.items = new ArrayList<>(items);
    }

    public Player player() { return player; }

    /** Mutable: what stays in it is delivered. */
    public List<ItemStack> items() { return items; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
