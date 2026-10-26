package fr.influencraft.influpet.pets.event;

import fr.influencraft.influpet.pets.Pet;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Un joueur adopte un familier (annulable). */
public final class PetAdoptEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final OfflinePlayer player;
    private final Pet pet;
    private boolean cancelled;

    public PetAdoptEvent(OfflinePlayer player, Pet pet) {
        this.player = player;
        this.pet = pet;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public Pet getPet() {
        return pet;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
