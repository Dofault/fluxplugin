package fr.influencraft.flux.menu;

import java.util.List;

/**
 * Page d'un menu (section pages.<id>) : même fenêtre, nouveau fond et boutons en plus.
 * Les boutons du menu restent affichés ; ceux de la page passent devant sur une même case.
 *
 * @param frames titres joués l'un après l'autre avant d'afficher la page (animation),
 *               rejoués à l'envers en revenant au menu
 */
public record Page(String id, String title, List<String> frames, int frameInterval, List<MenuButton> buttons,
                   List<PathSpec> paths, List<ListSpec> lists) {
}
