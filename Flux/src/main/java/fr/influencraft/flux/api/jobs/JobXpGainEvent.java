package fr.influencraft.flux.api.jobs;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player gained job XP (fired by Flux once the gain is final, every bonus included).
 *
 * @see Jobs
 */
public final class JobXpGainEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String job;
    private final double gained;
    private final double bonusPercent;
    private final int level;
    private final double xp;
    private final double required;

    public JobXpGainEvent(Player player, String job, double gained, double bonusPercent, int level, double xp, double required) {
        this.player = player;
        this.job = job;
        this.gained = gained;
        this.bonusPercent = bonusPercent;
        this.level = level;
        this.xp = xp;
        this.required = required;
    }

    public Player player() { return player; }

    /** Job id. */
    public String job() { return job; }

    /** XP gained, final. */
    public double gained() { return gained; }

    /** Every XP bonus of the player (permissions, potions, pets...), in percent (0 = none). */
    public double bonusPercent() { return bonusPercent; }

    /** Level and XP after the gain, XP needed for the next level. */
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
