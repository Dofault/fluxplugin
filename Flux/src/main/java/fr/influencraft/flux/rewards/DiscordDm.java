package fr.influencraft.flux.rewards;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Private Discord message to a player, through DiscordSRV when it is installed: its account link gives the Discord
 * account, its bot sends the message. Read by reflection (no compile dependency); without DiscordSRV, or for a
 * player without a linked account, nothing is sent. Call it off the main thread (the link may be read in a database).
 */
final class DiscordDm {

    private final Logger log;
    private boolean warned;

    DiscordDm(Logger log) {
        this.log = log;
    }

    /** True when the message left (linked account found). */
    boolean send(UUID player, String message) {
        Plugin dsrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
        if (dsrv == null || !dsrv.isEnabled()) return false;
        try {
            ClassLoader loader = dsrv.getClass().getClassLoader();
            Class<?> main = Class.forName("github.scarsz.discordsrv.DiscordSRV", true, loader);
            Object plugin = main.getMethod("getPlugin").invoke(null);
            Object links = main.getMethod("getAccountLinkManager").invoke(plugin);
            if (links == null) return false;
            Object id = links.getClass().getMethod("getDiscordId", UUID.class).invoke(links, player);
            if (id == null) return false;
            Class<?> util = Class.forName("github.scarsz.discordsrv.util.DiscordUtil", true, loader);
            Object user = util.getMethod("getUserById", String.class).invoke(null, id);
            if (user == null) return false;
            for (Method m : util.getMethods()) {
                if (m.getName().equals("privateMessage") && m.getParameterCount() == 2 && m.getParameterTypes()[1] == String.class) {
                    m.invoke(null, user, message);
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                log.warning("Discord private message through DiscordSRV failed (API changed?): " + e);
            }
        }
        return false;
    }
}
