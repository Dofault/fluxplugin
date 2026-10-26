package fr.influencraft.influshop.config;

/**
 * Layout/materials for the small "confirm this purchase?" inventory opened between clicking a
 * catalog item and the purchase actually going through (see {@link GuiSettings} for the main
 * shop grid's own settings, same split).
 */
public record ConfirmSettings(
        int size,
        int itemSlot,
        int confirmSlot,
        int cancelSlot,
        String titleFormat,
        String confirmItem,
        String cancelItem
) {
}
