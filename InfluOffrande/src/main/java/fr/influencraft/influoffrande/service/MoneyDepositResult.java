package fr.influencraft.influoffrande.service;

/**
 * Outcome of a single BitBlock deposit attempt on a collection's money requirement.
 * Mirrors {@link DepositResult} but for the economy instead of an item stack.
 */
public record MoneyDepositResult(
        long deposited,
        long newTotal,
        long required,
        boolean moneyJustCompleted,
        boolean collectionJustCompleted
) {

    public boolean isNoop() {
        return deposited == 0;
    }
}
