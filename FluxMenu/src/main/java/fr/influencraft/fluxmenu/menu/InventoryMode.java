package fr.influencraft.fluxmenu.menu;

import java.util.Locale;

/** Ce que le joueur voit de son propre inventaire quand le menu est ouvert. */
public enum InventoryMode {
    /**
     * Inventaire masqué côté client (paquets réécrits, le vrai inventaire n'est jamais touché) :
     * la zone du bas n'affiche que les boutons du menu placés dans les cases 9 x rangées et plus.
     */
    HIDDEN,
    /** Inventaire visible mais figé (comportement DeluxeMenus). */
    VISIBLE,
    /** Inventaire visible, le joueur peut y ranger ses objets (jamais vers le menu). */
    INTERACTIVE;

    public static InventoryMode parse(String raw) {
        if (raw == null) return HIDDEN;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "visible", "shown", "locked" -> VISIBLE;
            case "interactive", "free" -> INTERACTIVE;
            default -> HIDDEN;
        };
    }
}
