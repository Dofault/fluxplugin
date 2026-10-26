package fr.influencraft.fluxpets.pets.event;

import fr.influencraft.fluxpets.pets.Pet;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Un familier monte de niveau (level = nouveau niveau). */
public final class PetLevelUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Pet pet;
    private final int level;


    public PetLevelUpEvent(Player player, Pet pet, int level) {
        this.player = player;
        this.pet = pet;
        this.level = level;
    }

    public Player getPlayer() {
        return player;
    }

    public Pet getPet() {
        return pet;
    }

    public int getLevel() {
        return level;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
