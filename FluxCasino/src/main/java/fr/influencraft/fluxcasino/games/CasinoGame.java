package fr.influencraft.fluxcasino.games;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.fluxcasino.Casino;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Common part of the casino games played in a Flux menu: the bet is taken when the game starts, the gain is paid
 * when it ends (or right away if the menu closes: the outcome is always drawn beforehand), and every game exposes
 * {@code %<prefix>_last%}, {@code _count%} and {@code _erreur%} (reason of the last refused round, empty otherwise).
 */
abstract class CasinoGame {

    protected final Plugin plugin;
    private final String game;
    private final Map<UUID, String> last = new ConcurrentHashMap<>();
    private final Map<UUID, String> error = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> count = new ConcurrentHashMap<>();

    CasinoGame(Plugin plugin, String game) {
        this.plugin = plugin;
        this.game = game;
    }

    /** Registers the button action and the placeholders of the game. */
    void register(String action, String placeholderPrefix) {
        FluxApi.registerAction(plugin, action, this::act);
        FluxApi.registerPlaceholders(plugin, placeholderPrefix, this::placeholder);
    }

    /** Handles {@code [<action>] <argument>}. */
    abstract void act(Player player, MenuView view, String argument);

    /** Settles every round in progress (plugin stopping). */
    abstract void shutdown();

    /**
     * Takes the player's bet. Returns it, or -1 after telling the player (sound, error placeholder) that the
     * balance is too low.
     */
    long takeBet(Player player, MenuView view) {
        long bet = Casino.placeBet(player, game);
        if (bet < 0) {
            error.put(player.getUniqueId(), "Pas assez de jetons pour cette mise.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
            view.refreshButtons();
            return -1;
        }
        error.remove(player.getUniqueId());
        return bet;
    }

    /** Pays bet x multiplier (rounded down) and remembers the result shown by {@code %_last%}. Returns the gain. */
    long pay(Player player, long bet, double multiplier, String detail, String shown) {
        long gain = (long) Math.floor(bet * multiplier + 1e-9);
        if (bet > 0) Casino.payout(player, gain, game, detail);
        last.put(player.getUniqueId(), shown.replace("%gain%", String.valueOf(gain)));
        count.merge(player.getUniqueId(), 1, Integer::sum);
        return gain;
    }

    /** Game specific placeholders; null when unknown. */
    String extraPlaceholder(Player player, String params) {
        return null;
    }

    private String placeholder(Player player, String params) {
        if (player == null) return "";
        UUID id = player.getUniqueId();
        return switch (params) {
            case "last" -> last.getOrDefault(id, "aucun");
            case "count" -> String.valueOf(count.getOrDefault(id, 0));
            case "erreur" -> error.getOrDefault(id, "");
            default -> extraPlaceholder(player, params);
        };
    }

    static String format(double multiplier) {
        return multiplier == Math.floor(multiplier) ? String.valueOf((long) multiplier) : String.valueOf(multiplier);
    }
}
