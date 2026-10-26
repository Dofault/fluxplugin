package fr.influencraft.flux.menu;

import java.util.List;

/**
 * Définition d'un menu (un fichier menus/<id>.yml).
 *
 * <p>Numérotation des cases : 0 à 9 x rangées - 1 pour le coffre, puis on continue dans la
 * zone de l'inventaire du joueur (mode hidden uniquement). Pour 6 rangées : 54-80 = les trois
 * rangées de l'inventaire, 81-89 = la barre d'action.
 *
 * <p>title-loop (facultatif) : titres joués en boucle tant que le menu est ouvert, un tous les
 * {@code interval} ticks (animation continue : roue qui tourne...), sans bloquer les clics.
 *
 * <p>title-layers (facultatif) : calques ajoutés au titre selon des conditions, cf. {@link TitleLayer}.
 */
public record Menu(
        String id,
        String title,
        int rows,
        List<String> commands,
        boolean overrideCommands,
        String permission,
        InventoryMode inventoryMode,
        boolean vanillaBackground,
        int updateInterval,
        List<String> openActions,
        List<String> closeActions,
        List<MenuButton> buttons,
        java.util.Map<String, Page> pages,
        List<PathSpec> paths,
        List<String> loopTitles,
        int loopInterval,
        List<TitleLayer> titleLayers,
        List<ListSpec> lists) {

    public Page page(String id) {
        return id == null ? null : pages.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    /** List of this id shown by the menu or one of its pages, or null. */
    public ListSpec list(String listId) {
        for (ListSpec l : lists) if (l.id().equals(listId)) return l;
        for (Page p : pages.values()) for (ListSpec l : p.lists()) if (l.id().equals(listId)) return l;
        return null;
    }

    /** Nombre de cases du coffre. */
    public int topSize() {
        return rows * 9;
    }

    /** Nombre total de cases adressables (coffre + inventaire du joueur si masqué). */
    public int totalSlots() {
        return inventoryMode == InventoryMode.HIDDEN ? topSize() + 36 : topSize();
    }
}
