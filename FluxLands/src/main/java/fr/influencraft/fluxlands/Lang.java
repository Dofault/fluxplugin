package fr.influencraft.fluxlands;

/**
 * Texts of FluxLands ({@code lang/<language>.yml}). Chat messages keep Lands' theme tokens ({@code <t:texte>}...,
 * docs/notifications/villes.md), the lang file holds them as they are.
 */
final class Lang {

    private static volatile FluxLands plugin;

    private Lang() {
    }

    static void bind(FluxLands p) {
        plugin = p;
    }

    /** The text of {@code key}, the key itself when missing (logged once by Flux's lang check). */
    static String t(String key) {
        FluxLands p = plugin;
        if (p == null || p.messages() == null) return key;
        String raw = p.messages().raw(key);
        return raw.isEmpty() ? key : raw;
    }
}
