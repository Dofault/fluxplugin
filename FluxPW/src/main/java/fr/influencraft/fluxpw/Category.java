package fr.influencraft.fluxpw;

import org.bukkit.command.CommandSender;

/**
 * A warp category of the config ({@code categories.<key>}). The {@code all} one (flag {@code all: true}) lists every
 * warp and is the category of new warps; a warp whose category was removed from the config counts as in it.
 *
 * @param permission needed to open the category and visit its warps, null for everyone
 */
record Category(String key, String name, String icon, String description, String permission, boolean all) {

    boolean allowed(CommandSender sender) {
        return permission == null || permission.isBlank() || sender.hasPermission(permission)
                || sender.hasPermission("fluxpw.admin");
    }
}
