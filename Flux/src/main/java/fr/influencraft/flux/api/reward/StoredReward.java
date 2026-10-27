package fr.influencraft.flux.api.reward;

/** A reward kept by Flux for a player, waiting to be claimed. */
public record StoredReward(long id, long created, Reward reward) {
}
