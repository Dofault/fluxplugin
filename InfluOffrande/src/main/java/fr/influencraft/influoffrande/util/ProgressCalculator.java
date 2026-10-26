package fr.influencraft.influoffrande.util;

import fr.influencraft.influoffrande.model.CollectionDefinition;
import fr.influencraft.influoffrande.model.ItemKey;
import fr.influencraft.influoffrande.model.PlayerData;

import java.util.Map;

/**
 * Central place for progress math so the GUI, commands and PAPI expansion all agree
 * on what "complete" and "percent" mean.
 */
public final class ProgressCalculator {

    private ProgressCalculator() {
    }

    public static boolean isItemComplete(PlayerData data, CollectionDefinition collection, ItemKey key, int required) {
        return data.getDeposited(collection.id(), key) >= required;
    }

    public static boolean isComplete(PlayerData data, CollectionDefinition collection) {
        if (collection.hasMoneyRequirement() && data.getMoneyDeposited(collection.id()) < collection.money()) {
            return false;
        }
        for (Map.Entry<ItemKey, Integer> entry : collection.items().entrySet()) {
            if (data.getDeposited(collection.id(), entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return number of item types fully completed, plus one more if this collection has a
     * money requirement and it's fully deposited (money counts as one extra "type").
     */
    public static int completedTypeCount(PlayerData data, CollectionDefinition collection) {
        int count = 0;
        for (Map.Entry<ItemKey, Integer> entry : collection.items().entrySet()) {
            if (data.getDeposited(collection.id(), entry.getKey()) >= entry.getValue()) {
                count++;
            }
        }
        if (collection.hasMoneyRequirement() && data.getMoneyDeposited(collection.id()) >= collection.money()) {
            count++;
        }
        return count;
    }

    /**
     * @return total item types, plus one more if this collection has a money requirement
     * — matches what {@link #completedTypeCount} counts against.
     */
    public static int totalTypesWithMoney(CollectionDefinition collection) {
        return collection.totalItemTypes() + (collection.hasMoneyRequirement() ? 1 : 0);
    }

    /**
     * @return overall completion percent (0-100). Without a money requirement this is the
     * total quantity deposited vs required, same as before. With one, it's the average of
     * the item ratio and the money ratio — kept separate so a huge BitBlock requirement
     * doesn't swamp the item progress in a single shared unit pool.
     */
    public static int percent(PlayerData data, CollectionDefinition collection) {
        long total = 0;
        long deposited = 0;
        for (Map.Entry<ItemKey, Integer> entry : collection.items().entrySet()) {
            int required = entry.getValue();
            total += required;
            deposited += Math.min(required, data.getDeposited(collection.id(), entry.getKey()));
        }
        double itemRatio = total <= 0 ? 1.0 : Math.min(1.0, deposited / (double) total);

        if (!collection.hasMoneyRequirement()) {
            return (int) Math.min(100, Math.round(itemRatio * 100));
        }
        double moneyRatio = Math.min(1.0, data.getMoneyDeposited(collection.id()) / (double) collection.money());
        if (total <= 0) {
            return (int) Math.min(100, Math.round(moneyRatio * 100));
        }
        return (int) Math.min(100, Math.round(((itemRatio + moneyRatio) / 2.0) * 100));
    }
}
