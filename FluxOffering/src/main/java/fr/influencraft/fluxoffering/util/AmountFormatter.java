package fr.influencraft.fluxoffering.util;

import fr.influencraft.fluxoffering.config.MessagesManager;
import fr.influencraft.fluxoffering.model.ItemKey;

import java.util.Map;

/**
 * Turns a raw amount ("10000") into the human readable "(156 x64 + 16)" form used
 * in item names for quantities that don't fit in a single displayed stack.
 */
public final class AmountFormatter {

    private AmountFormatter() {
    }

    /**
     * Formats a raw amount with a space as thousands separator ("10 000 000"), used for
     * BitBlock amounts — plain quantities, not stack-based like item amounts.
     */
    public static String grouped(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sb.append(digits.charAt(i));
            count++;
            if (count % 3 == 0 && i != 0) {
                sb.append(' ');
            }
        }
        String grouped = sb.reverse().toString();
        return value < 0 ? "-" + grouped : grouped;
    }

    /**
     * Formats a requirement amount for an item name: "(4 x64)", "(1 x64 + 36)", or a plain
     * "(30)" when the item doesn't stack or the amount fits in less than one stack. An
     * enchantment requirement is counted in levels ("(100 niveaux)"), never in stacks.
     */
    public static String format(MessagesManager messages, ItemKey key, int amount) {
        if (key.isEnchantment()) {
            return messages.raw("format.amount-levels", Map.of("amount", String.valueOf(amount)));
        }
        return format(messages, amount, key.maxStackSize());
    }

    public static String format(MessagesManager messages, int amount, int maxStackSize) {
        int stacks = maxStackSize <= 1 ? 0 : amount / maxStackSize;
        int remainder = maxStackSize <= 1 ? amount : amount % maxStackSize;
        Map<String, String> placeholders = Map.of(
                "stacks", String.valueOf(stacks),
                "remainder", String.valueOf(remainder),
                "stack_size", String.valueOf(maxStackSize)
        );
        if (stacks > 0 && remainder > 0) {
            return messages.raw("format.amount-both", placeholders);
        } else if (stacks > 0) {
            return messages.raw("format.amount-stacks-only", placeholders);
        } else {
            return messages.raw("format.amount-remainder-only", placeholders);
        }
    }
}
