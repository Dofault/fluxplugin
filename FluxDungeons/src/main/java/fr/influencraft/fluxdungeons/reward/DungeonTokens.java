package fr.influencraft.fluxdungeons.reward;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.world.Coins;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dungeon tokens (2026-11-14): a Flux currency ({@code tokens.currency}, {@code dungeon_tokens}) every player of the
 * party gets for each monster killed in a run, by its rank ({@code tokens.per-rank}) times the difficulty's reward,
 * spent at the potion merchant of the rpg lobby.
 * <p>
 * The tokens spring out of the monster and fly to the nearest player of the party (Flux's {@link Coins}). Only a show:
 * the tokens are already in the account.
 */
public final class DungeonTokens {

    private final FluxPlugin plugin;
    private String currency = "dungeon_tokens";
    /** Tokens of a monster of a rank: a random amount from min to max (both included). */
    private record Range(int min, int max) {
        int roll() {
            return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        }

        @Override
        public String toString() {
            return min >= max ? String.valueOf(min) : min + "-" + max;
        }
    }

    private final Map<String, Range> perRank = new HashMap<>();
    private int maxCoins = 6;

    public DungeonTokens(FluxPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection c) {
        currency = c == null ? "dungeon_tokens" : c.getString("currency", "dungeon_tokens");
        perRank.clear();
        perRank.putAll(Map.of("TRASH", new Range(1, 3), "ELITE", new Range(3, 5), "MINIBOSS", new Range(8, 12),
                "BOSS", new Range(35, 45)));
        ConfigurationSection ranks = c == null ? null : c.getConfigurationSection("per-rank");
        // a rank is a number (always that many) or [min, max]
        if (ranks != null) for (String k : ranks.getKeys(false)) {
            List<Integer> pair = ranks.getIntegerList(k);
            Range r = pair.size() >= 2 ? new Range(Math.max(0, pair.get(0)), Math.max(pair.get(0), pair.get(1)))
                    : new Range(Math.max(0, ranks.getInt(k)), Math.max(0, ranks.getInt(k)));
            perRank.put(k.toUpperCase(Locale.ROOT), r);
        }
        maxCoins = c == null ? 6 : Math.max(1, c.getInt("max-coins-shown", 6));
    }

    public Currency currency() {
        return Economy.get(currency);
    }

    public String currencyId() {
        return currency;
    }

    /**
     * Tokens of a monster of this rank at this difficulty reward: a roll in the rank's range, times the reward (random
     * rounding: 1.5 is 1 or 2).
     */
    public int amount(String rank, double reward) {
        double base = range(rank == null ? "TRASH" : rank).roll() * Math.max(0, reward);
        int n = (int) base;
        if (ThreadLocalRandom.current().nextDouble() < base - n) n++;
        return n;
    }

    /** The base amount of a rank as shown in menus ("1-3", or "5" when fixed). */
    public String base(String rank) {
        return range(rank).toString();
    }

    private Range range(String rank) {
        return perRank.getOrDefault(rank.toUpperCase(Locale.ROOT), new Range(1, 1));
    }

    /** Gives {@code amount} to each player of the party and throws the coins out of the monster. */
    public void award(Location at, List<Player> party, int amount, Map<java.util.UUID, Integer> earned) {
        Currency money = currency();
        if (amount <= 0 || party.isEmpty()) return;
        if (money != null) {
            for (Player p : party) {
                money.give(p, amount, Origin.of(plugin, "dungeon_kill"));
                earned.merge(p.getUniqueId(), amount, Integer::sum);
            }
        }
        ItemStack coin = Items.of("itemsadder:fluxdonjon:jeton_donjon", (Player) null);
        if (coin != null) Coins.drop(coin).burst(at, Math.min(maxCoins, amount), party);
    }
}
