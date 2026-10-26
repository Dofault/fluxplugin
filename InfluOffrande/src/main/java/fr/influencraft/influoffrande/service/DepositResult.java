package fr.influencraft.influoffrande.service;

/**
 * Outcome of a single deposit attempt on one item slot of one collection.
 * {@code deposited}/{@code newTotal}/{@code required} are in requirement units (items, or
 * enchantment levels for an "enchant:" requirement); {@code consumedItems} is how many
 * physical items were taken from the inventory (books, for an enchantment).
 */
public record DepositResult(
        int deposited,
        int consumedItems,
        int newTotal,
        int required,
        boolean itemJustCompleted,
        boolean collectionJustCompleted
) {

    public static DepositResult noop(int current, int required) {
        return new DepositResult(0, 0, current, required, false, false);
    }

    public boolean isNoop() {
        return deposited == 0;
    }
}
