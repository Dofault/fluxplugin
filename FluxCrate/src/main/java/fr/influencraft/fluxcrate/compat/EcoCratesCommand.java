// COMPAT 2026-10-29: « ecocrates give <player> key <crate> physical <n> » written in other plugins' configs (votes,
// pass, shop, sponsorship, fishing contests) - remove when no config uses « ecocrates give » any more.
package fr.influencraft.fluxcrate.compat;

import org.bukkit.Bukkit;

import java.util.Locale;

/**
 * Once EcoCrates is removed, FluxCrate answers its give command: {@code ecocrates give <player> key <crate>
 * [physical|virtual] [amount]} gives FluxCrate keys (virtual keys never existed on the server: given as items too).
 * Registered only when EcoCrates is absent.
 */
public final class EcoCratesCommand {

    private EcoCratesCommand() {
    }

    public static boolean available() {
        return Bukkit.getPluginManager().getPlugin("EcoCrates") == null;
    }

    /** {@code give} receives (player name or *, crate id, amount). */
    public static boolean run(String[] args, TriConsumer give) {
        if (args.length < 4 || !args[0].equalsIgnoreCase("give") || !args[2].equalsIgnoreCase("key")) return false;
        int amount = 1;
        String last = args[args.length - 1];
        if (args.length >= 5) {
            try {
                amount = Math.max(1, Integer.parseInt(last));
            } catch (NumberFormatException ignored) {
                // « physical » without an amount
            }
        }
        give.accept(args[1], args[3].toLowerCase(Locale.ROOT), amount);
        return true;
    }

    @FunctionalInterface
    public interface TriConsumer {
        void accept(String player, String crate, int amount);
    }
}
