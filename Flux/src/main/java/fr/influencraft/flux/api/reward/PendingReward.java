package fr.influencraft.flux.api.reward;

/**
 * A reward waiting to be claimed, as shown in the rewards menu.
 *
 * @param source source id ({@link RewardSource#id()})
 * @param label  what it is, coloured text ("&fCrate key &7x3")
 * @param icon   item of the menu button (Flux material syntax: {@code TRIPWIRE_HOOK}, {@code itemsadder:ns:id})
 * @param amount how many of it (shown as the button amount)
 * @param slots  inventory slots it needs once claimed (0 for money or commands that give no item)
 */
public record PendingReward(String source, String label, String icon, int amount, int slots) {
}
