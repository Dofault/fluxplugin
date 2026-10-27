package fr.influencraft.flux.api.jobs;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A player reached a new job level (fired by Flux once the new state is readable). */
public final class JobLevelUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String job;
    private final int level;
    private final double xp;
    private final double required;

    public JobLevelUpEvent(Player player, String job, int level, double xp, double required) {
        this.player = player;
        this.job = job;
        this.level = level;
        this.xp = xp;
        this.required = required;
    }

    public Player player() { return player; }

    public String job() { return job; }

    public int level() { return level; }

    public double xp() { return xp; }

    public double required() { return required; }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
