package fr.influencraft.flux.api.reward;

/**
 * What a {@link RewardSource} delivered during a claim.
 *
 * @param delivered rewards given
 * @param remaining rewards still waiting (not enough room, or failed: the source keeps them)
 */
public record ClaimResult(int delivered, int remaining) {

    public static final ClaimResult NONE = new ClaimResult(0, 0);

    public ClaimResult plus(ClaimResult other) {
        return new ClaimResult(delivered + other.delivered, remaining + other.remaining);
    }
}
