package fr.influencraft.flux;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commands suggested to players when they type "/" ({@code command-suggestions} of Flux's config): every command is
 * hidden but those of the lists the player may see, whatever plugin they come from (CMI, Lands, vanilla...). Paper
 * already drops the commands the player has no permission for, so a list only says what is worth showing: an
 * unranked player never sees {@code /fly} even when it is listed. Only the suggestions change, never what a player
 * may run. Players with the bypass permission see everything.
 */
final class CommandSuggestions implements Listener {

    /** A list of commands shown to the players having its permission (empty: everyone). */
    record Group(String permission, Map<String, String> commands) {
    }

    private volatile boolean enabled;
    private volatile String bypass = "flux.commands.all";
    private volatile List<Group> groups = List.of();

    void configure(ConfigurationSection section) {
        enabled = section != null && section.getBoolean("enabled", false);
        if (section == null) return;
        bypass = section.getString("bypass-permission", "flux.commands.all");
        List<Group> read = new ArrayList<>();
        ConfigurationSection lists = section.getConfigurationSection("lists");
        if (lists != null) {
            for (String id : lists.getKeys(false)) {
                ConfigurationSection list = lists.getConfigurationSection(id);
                if (list == null) continue;
                Map<String, String> commands = new LinkedHashMap<>();
                ConfigurationSection cmds = list.getConfigurationSection("commands");
                if (cmds != null) {
                    for (String c : cmds.getKeys(false)) commands.put(c.toLowerCase(Locale.ROOT), cmds.getString(c, ""));
                }
                read.add(new Group(list.getString("permission", ""), commands));
            }
        }
        groups = List.copyOf(read);
        // resend the command tree to the players online (after a reload)
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    /** Commands this player may be shown, with their description (for a help screen). */
    Map<String, String> visible(Player player) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Group g : groups) {
            if (g.permission().isEmpty() || player.hasPermission(g.permission())) out.putAll(g.commands());
        }
        return out;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSend(PlayerCommandSendEvent event) {
        if (!enabled || event.getPlayer().hasPermission(bypass)) return;
        Set<String> allowed = new HashSet<>(visible(event.getPlayer()).keySet());
        event.getCommands().removeIf(c -> !allowed.contains(c.toLowerCase(Locale.ROOT)));
    }
}
