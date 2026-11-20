package fr.influencraft.fluxspawner;

import java.util.Locale;
import java.util.Map;

/**
 * One goal to reach the next level of a spawner (levels.yml). Players complete it bit by bit; what they gave is kept
 * per spawner under {@link #key}. Written as a map in the config:
 * <pre>
 *   - kills: 1h              # kills of this spawner's own mobs: an hour of its production (or a number)
 *   - money: 0.5h            # server money: half an hour of the reference income (or a number)
 *   - xp-levels: 15          # vanilla experience: the points of levels 0 to 15
 *   - item: type             # the type's upgrade item (or any item id: DIAMOND, fluxpets:...)
 *     amount: 0.25h          # a quarter of an hour of optimal play, in that item (or a number)
 *   - currency: influence    # another Flux currency, a fixed number
 *     amount: 50
 * </pre>
 */
record Requirement(Kind kind, String item, String currency, Amount amount) {

    enum Kind { KILLS, MONEY, XP, ITEM, CURRENCY }

    /** The item meaning "the upgrade item of the spawner's type". */
    static final String TYPE_ITEM = "type";

    /** Key of the progress kept for this goal, the type's item resolved. */
    String key(SpawnerType type) {
        return switch (kind) {
            case KILLS -> "kills";
            case MONEY -> "money";
            case XP -> "xp";
            case ITEM -> "item:" + resolvedItem(type);
            case CURRENCY -> "currency:" + currency;
        };
    }

    String resolvedItem(SpawnerType type) {
        return TYPE_ITEM.equalsIgnoreCase(item) ? type.upgradeItem() : item;
    }

    /** Reads one entry; null when it names no known goal (logged by the caller). */
    static Requirement read(Map<?, ?> map) {
        if (map.containsKey("kills")) return new Requirement(Kind.KILLS, null, null, Amount.parse(map.get("kills")));
        if (map.containsKey("money")) return new Requirement(Kind.MONEY, null, null, Amount.parse(map.get("money")));
        if (map.containsKey("xp-levels")) return new Requirement(Kind.XP, null, null, Amount.parse(map.get("xp-levels")));
        if (map.containsKey("item")) {
            String item = String.valueOf(map.get("item")).trim();
            if (!item.contains(":") && !item.equalsIgnoreCase(TYPE_ITEM)) item = item.toUpperCase(Locale.ROOT);
            return new Requirement(Kind.ITEM, item, null, Amount.parse(map.get("amount")));
        }
        if (map.containsKey("currency")) {
            return new Requirement(Kind.CURRENCY, null, String.valueOf(map.get("currency")).trim(), Amount.parse(map.get("amount")));
        }
        return null;
    }
}
