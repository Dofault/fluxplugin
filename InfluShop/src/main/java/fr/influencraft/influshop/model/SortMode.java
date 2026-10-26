package fr.influencraft.influshop.model;

/**
 * Cycle order for the sort button: Tout -&gt; Prix croissant -&gt; Prix decroissant -&gt; Tout.
 * Deliberately no "by plugin"/"by source" mode: a player doesn't know or care what plugin an
 * item comes from, so that distinction never surfaces here — ALL just means the catalog's
 * natural (shop.yml) order.
 */
public enum SortMode {
    ALL, PRICE_ASC, PRICE_DESC;

    public SortMode next() {
        return switch (this) {
            case ALL -> PRICE_ASC;
            case PRICE_ASC -> PRICE_DESC;
            case PRICE_DESC -> ALL;
        };
    }
}
