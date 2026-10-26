package fr.influencraft.flux.session;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Identifiant réseau de la fenêtre ouverte (containerId côté serveur), lu par réflexion
 * (Paper tourne en noms Mojang depuis 1.20.5). Sans lui on retombe sur « toute fenêtre
 * autre que l'inventaire », ce qui reste correct puisqu'un joueur n'a qu'une fenêtre ouverte.
 */
final class ContainerIds {

    private static Method getHandle;
    private static Field containerMenu;
    private static Field containerId;
    private static boolean broken;

    private ContainerIds() {
    }

    static int of(Player player, Logger logger) {
        if (broken) return -1;
        try {
            if (getHandle == null) getHandle = player.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(player);
            if (containerMenu == null) containerMenu = field(handle.getClass(), "containerMenu");
            Object menu = containerMenu.get(handle);
            if (containerId == null) containerId = field(menu.getClass(), "containerId");
            return containerId.getInt(menu);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            logger.warning("Identifiant de fenêtre illisible (" + e + "), repli sur le filtrage par fenêtre non nulle.");
            return -1;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // classe parente
            }
        }
        throw new NoSuchFieldException(name);
    }
}
