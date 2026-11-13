package fr.influencraft.fluxoffering.api;

import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.model.PlayerData;

import java.util.Map;
import java.util.UUID;

/** Offering progress of the players, for imports (FluxMigration). */
public final class Offerings {

    private static volatile DataManager data;

    private Offerings() {
    }

    /** Called by FluxOffering. */
    public static void bind(DataManager manager) {
        data = manager;
    }

    public static boolean available() {
        return data != null;
    }

    /** Whether FluxOffering already has this player (an import leaves him as he is). */
    public static boolean known(UUID player) {
        DataManager d = data;
        return d == null || d.known(player);
    }

    /**
     * Writes a player's whole progress: items deposited per collection (raw item keys of collections.yml) and money
     * deposited per collection.
     */
    public static void put(UUID player, String name, String filter, Map<String, Map<String, Integer>> items, Map<String, Long> money) {
        DataManager d = data;
        if (d == null) return;
        PlayerData p = new PlayerData(player, name);
        if (filter != null) p.menuFilterMode(filter);
        items.forEach((col, map) -> p.raw().computeIfAbsent(col, k -> new java.util.concurrent.ConcurrentHashMap<>()).putAll(map));
        p.rawMoney().putAll(money);
        d.write(p);
    }
}
