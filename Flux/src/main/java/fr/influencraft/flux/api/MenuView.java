package fr.influencraft.flux.api;

import org.bukkit.entity.Player;

/** A Flux menu currently open by a player, as seen by other plugins. Main thread only. */
public interface MenuView {

    Player player();

    /** Menu identifier (file name without .yml). */
    String menuId();

    /** Displayed page, or null for the menu itself. */
    String pageId();

    /** False once the player closed it or opened another menu: stop any animation then. */
    boolean isOpen();

    /** Full title currently computed for the menu (background, paths, layers), base of title animations. */
    String title();

    /** Where {@link #title()} leaves the cursor, in px from the left edge: start of a {@link TitleOverlay}. */
    int titleCursor();

    /** Shows a title right away (animation frame); clicks stay enabled. */
    void showTitle(String title);

    /** Recomputes the buttons (placeholders, conditions) without touching the displayed title. */
    void refreshButtons();

    /** Recomputes the whole menu, title included. */
    void refresh();

    void close();
}
