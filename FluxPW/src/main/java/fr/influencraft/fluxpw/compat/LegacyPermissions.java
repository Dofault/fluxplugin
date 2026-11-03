// COMPAT 2026-10-30: permission nodes of the former warp plugin (playerwarps.*) still granted - remove when the rank
// groups of production use fluxpw.* (docs/technique/flux-mise-en-prod.md, FluxPW) and no warning showed for a week.
package fr.influencraft.fluxpw.compat;

import fr.influencraft.flux.api.staff.Staff;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.TreeSet;

/**
 * Stop sign of the permission switch: FluxPW only checks {@code fluxpw.*}. A player who joins with a
 * {@code playerwarps.*} node means a rank group was not moved over (his warps limits and settings are then missing):
 * the console and the online staff are told which nodes, once per node and server start. Those nodes are never
 * honoured, so a forgotten switch shows at once instead of half working.
 */
public final class LegacyPermissions implements Listener {

    private final Plugin plugin;
    private final Set<String> reported = new TreeSet<>();

    public LegacyPermissions(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Set<String> fresh = new TreeSet<>();
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(java.util.Locale.ROOT);
            if (info.getValue() && node.startsWith("playerwarps.") && reported.add(node)) fresh.add(node);
        }
        if (fresh.isEmpty()) return;
        String nodes = String.join(", ", fresh);
        plugin.getLogger().warning(p.getName() + " still has former warp permissions (" + nodes
                + "): move the rank groups to fluxpw.* (same suffixes), FluxPW ignores playerwarps.*");
        Staff.alert("fluxpw-permissions", "FluxPW", "Permissions playerwarps.* encore données (" + nodes
                + ", vu sur " + p.getName() + ") : basculer les groupes LuckPerms vers fluxpw.*");
    }
}
