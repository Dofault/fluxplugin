package fr.influencraft.flux.api.reward;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Rewards a plugin keeps for a player until he claims them (vote keys on the website, career boosters, referral
 * bonuses...). Each plugin keeps its own storage; the rewards plugin (FluxRewards) gathers every source behind one
 * command and one menu. A source never gives more than the room it is offered and never drops anything.
 *
 * <p>Futures may complete on any thread; the caller goes back to the main thread itself.</p>
 */
public interface RewardSource {

    /** Identifier ({@code votes}, {@code boosters}...), also the order key in the rewards config. */
    String id();

    /** Rewards waiting for this player, for the menu and the reminders. */
    CompletableFuture<List<PendingReward>> pending(Player player);

    /**
     * Delivers what fits in {@code freeSlots} storage slots (called on the main thread with the current room).
     * Rewards needing no slot are always delivered.
     */
    CompletableFuture<ClaimResult> claim(Player player, int freeSlots);
}
