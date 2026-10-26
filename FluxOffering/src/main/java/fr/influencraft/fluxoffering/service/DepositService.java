package fr.influencraft.fluxoffering.service;

import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.config.Settings;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.integration.ItemsAdderHook;
import fr.influencraft.fluxoffering.integration.VaultEconomyHook;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.ItemKey;
import fr.influencraft.fluxoffering.model.PlayerData;
import fr.influencraft.fluxoffering.util.ProgressCalculator;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Scans a player's inventory, consumes what matches a requested item and records the
 * deposit. Everything here runs synchronously on the main thread (triggered from an
 * inventory click), so there is no race window between "check what the player has" and
 * "take it away" — nothing can be duplicated or lost.
 */
public final class DepositService {

    private final ConfigManager configManager;
    private final DataManager dataManager;

    public DepositService(ConfigManager configManager, DataManager dataManager) {
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    /**
     * Deposits as much of this item as the player has (up to what's still needed).
     *
     * @return the result of the attempt, or null if the key isn't part of the collection.
     */
    public DepositResult deposit(Player player, CollectionDefinition collection, ItemKey key) {
        return deposit(player, collection, key, Integer.MAX_VALUE);
    }

    /**
     * @param maxToConsume caps how many items are taken in this single call (e.g. 1 for a
     *                      "deposit just one" right-click), regardless of how much the
     *                      player carries or still needs. For an enchantment requirement
     *                      this counts books, not levels.
     * @return the result of the attempt, or null if the key isn't part of the collection.
     */
    public DepositResult deposit(Player player, CollectionDefinition collection, ItemKey key, int maxToConsume) {
        Integer required = collection.items().get(key);
        if (required == null) {
            return null;
        }
        if (key.isEnchantment()) {
            return depositEnchantment(player, collection, key, required, maxToConsume);
        }
        PlayerData data = dataManager.get(player);
        int current = data.getDeposited(collection.id(), key);
        int remaining = required - current;
        if (remaining <= 0) {
            return DepositResult.noop(current, required);
        }

        boolean wasCollectionComplete = ProgressCalculator.isComplete(data, collection);
        Settings settings = configManager.settings();

        ItemStack[] contents = player.getInventory().getStorageContents();
        int available = 0;
        for (ItemStack stack : contents) {
            if (stack != null && key.matches(stack) && isAcceptable(stack, settings)) {
                available += stack.getAmount();
            }
        }
        if (available <= 0) {
            return DepositResult.noop(current, required);
        }

        int toConsume = Math.min(Math.min(available, remaining), Math.max(0, maxToConsume));
        if (toConsume <= 0) {
            return DepositResult.noop(current, required);
        }
        int stillNeeded = toConsume;
        for (int i = 0; i < contents.length && stillNeeded > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !key.matches(stack) || !isAcceptable(stack, settings)) {
                continue;
            }
            int take = Math.min(stack.getAmount(), stillNeeded);
            int newAmount = stack.getAmount() - take;
            contents[i] = newAmount <= 0 ? null : withAmount(stack, newAmount);
            stillNeeded -= take;
        }
        player.getInventory().setStorageContents(contents);

        data.addDeposited(collection.id(), key, toConsume);
        dataManager.saveAsync(data);

        int newTotal = current + toConsume;
        boolean itemJustCompleted = newTotal >= required && current < required;
        boolean nowComplete = ProgressCalculator.isComplete(data, collection);
        boolean collectionJustCompleted = nowComplete && !wasCollectionComplete;

        return new DepositResult(toConsume, toConsume, newTotal, required, itemJustCompleted, collectionJustCompleted);
    }

    /**
     * Enchantment requirement: each enchanted book carrying the enchantment adds its level
     * to the counter. Books are picked best-fit (highest level that doesn't overshoot what's
     * still needed, else the lowest one available) so the last few levels don't eat a
     * Sharpness V when a Sharpness I would do. A book carrying several enchantments also
     * credits the other enchantment requirements of the same collection it holds, since the
     * whole book is consumed anyway. Overshoot on the final book is capped, not refunded.
     */
    private DepositResult depositEnchantment(Player player, CollectionDefinition collection, ItemKey key,
                                             int required, int maxBooks) {
        PlayerData data = dataManager.get(player);
        int current = data.getDeposited(collection.id(), key);
        if (current >= required) {
            return DepositResult.noop(current, required);
        }

        boolean wasCollectionComplete = ProgressCalculator.isComplete(data, collection);
        Settings settings = configManager.settings();
        ItemStack[] contents = player.getInventory().getStorageContents();

        int credited = 0;
        int books = 0;
        while (books < maxBooks && current + credited < required) {
            int remaining = required - current - credited;
            int bestSlot = -1;
            int bestLevel = 0;
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || !key.matches(stack) || !isAcceptable(stack, settings)) {
                    continue;
                }
                int level = key.unitsPerItem(stack);
                if (level <= 0) {
                    continue;
                }
                boolean better = bestSlot < 0
                        || (level <= remaining && (bestLevel > remaining || level > bestLevel))
                        || (level > remaining && bestLevel > remaining && level < bestLevel);
                if (better) {
                    bestSlot = i;
                    bestLevel = level;
                }
            }
            if (bestSlot < 0) {
                break;
            }

            ItemStack book = contents[bestSlot];
            creditOtherEnchantments(data, collection, key, book);
            int newAmount = book.getAmount() - 1;
            contents[bestSlot] = newAmount <= 0 ? null : withAmount(book, newAmount);
            credited += Math.min(bestLevel, remaining);
            books++;
        }
        if (books == 0) {
            return DepositResult.noop(current, required);
        }
        player.getInventory().setStorageContents(contents);

        data.addDeposited(collection.id(), key, credited);
        dataManager.saveAsync(data);

        int newTotal = current + credited;
        boolean nowComplete = ProgressCalculator.isComplete(data, collection);
        return new DepositResult(credited, books, newTotal, required, newTotal >= required,
                nowComplete && !wasCollectionComplete);
    }

    private void creditOtherEnchantments(PlayerData data, CollectionDefinition collection, ItemKey target, ItemStack book) {
        for (var entry : collection.items().entrySet()) {
            ItemKey other = entry.getKey();
            if (!other.isEnchantment() || other.equals(target) || !other.matches(book)) {
                continue;
            }
            int otherCurrent = data.getDeposited(collection.id(), other);
            int otherRemaining = entry.getValue() - otherCurrent;
            if (otherRemaining > 0) {
                data.addDeposited(collection.id(), other, Math.min(other.unitsPerItem(book), otherRemaining));
            }
        }
    }

    /**
     * Deposits BitBlock (Vault economy) toward a collection's money requirement.
     *
     * @param maxToConsume caps how much is withdrawn in this single call (e.g. the amount
     *                      the player typed in chat); the actual amount is also clamped to
     *                      what's still needed and to the player's balance.
     * @return the result of the attempt, or null if this collection has no money requirement.
     */
    public MoneyDepositResult depositMoney(Player player, CollectionDefinition collection, long maxToConsume) {
        long required = collection.money();
        if (required <= 0) {
            return null;
        }
        PlayerData data = dataManager.get(player);
        long current = data.getMoneyDeposited(collection.id());
        long remaining = required - current;
        if (remaining <= 0) {
            return new MoneyDepositResult(0, current, required, false, false);
        }
        if (!VaultEconomyHook.isAvailable()) {
            return new MoneyDepositResult(0, current, required, false, false);
        }

        long balance = (long) Math.floor(VaultEconomyHook.getBalance(player));
        long toConsume = Math.min(Math.min(remaining, Math.max(0, maxToConsume)), balance);
        if (toConsume <= 0) {
            return new MoneyDepositResult(0, current, required, false, false);
        }

        boolean wasCollectionComplete = ProgressCalculator.isComplete(data, collection);
        if (!VaultEconomyHook.withdraw(player, toConsume)) {
            return new MoneyDepositResult(0, current, required, false, false);
        }

        data.addMoneyDeposited(collection.id(), toConsume);
        dataManager.saveAsync(data);

        long newTotal = current + toConsume;
        boolean moneyJustCompleted = newTotal >= required && current < required;
        boolean nowComplete = ProgressCalculator.isComplete(data, collection);
        boolean collectionJustCompleted = nowComplete && !wasCollectionComplete;

        return new MoneyDepositResult(toConsume, newTotal, required, moneyJustCompleted, collectionJustCompleted);
    }

    private static ItemStack withAmount(ItemStack stack, int amount) {
        ItemStack clone = stack.clone();
        clone.setAmount(amount);
        return clone;
    }

    private boolean isAcceptable(ItemStack stack, Settings settings) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return true;
        }
        // A custom item (ItemsAdder-tagged, or just carrying CustomModelData like a
        // CustomFishing catch reusing an ItemsAdder model) always has its own display name
        // as part of its definition — that's not a player renaming it at an anvil, so
        // accept-renamed-items must not apply to it (only to plain vanilla items the player
        // actually renamed, which can never have CustomModelData set).
        boolean isCustomItem = ItemsAdderHook.getNamespacedId(stack) != null || meta.hasCustomModelData();
        if (!isCustomItem && !settings.acceptRenamedItems() && meta.hasDisplayName()) {
            return false;
        }
        if (!settings.acceptDamagedTools() && meta instanceof Damageable damageable && damageable.getDamage() > 0) {
            return false;
        }
        return true;
    }
}
