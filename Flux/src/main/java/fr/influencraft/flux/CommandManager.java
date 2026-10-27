package fr.influencraft.flux;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Commands seen by players ({@code command-suggestions} of Flux's config, lists in {@code commands.yml}): when a
 * player types "/", every command is hidden but the suggested ones (suite commands, menu commands, {@code others}),
 * whatever plugin they come from. The server still drops what the player has no permission for: an unranked player
 * never sees {@code /fly}. Only suggestions change, a hidden command can still be run. {@code /aide} lists the same
 * commands with their description.
 */
final class CommandManager implements Listener {

    private static final int PER_PAGE = 10;

    private volatile boolean enabled;
    private volatile boolean showAliases;
    private volatile String bypass = "flux.commands.all";

    void configure(ConfigurationSection section) {
        enabled = section != null && section.getBoolean("enabled", false);
        if (section != null) {
            bypass = section.getString("bypass-permission", "flux.commands.all");
            showAliases = section.getBoolean("show-aliases", false);
        }
        Commands.reload();
        // resend the command tree to the players online (after a reload)
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    /** Suggested commands this player may run, in the order of commands.yml. */
    List<Commands.Shown> visible(Player player) {
        List<Commands.Shown> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Commands.Shown s : Commands.shown()) {
            if (s.permission() != null && !s.permission().isEmpty() && !player.hasPermission(s.permission())) continue;
            Command command = Bukkit.getCommandMap().getCommand(s.label());
            if (command == null || !command.testPermissionSilent(player)) continue;
            if (seen.add(s.label())) out.add(s);
        }
        return out;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSend(PlayerCommandSendEvent event) {
        if (!enabled || event.getPlayer().hasPermission(bypass)) return;
        Set<String> allowed = new HashSet<>();
        for (Commands.Shown s : Commands.shown()) {
            if (s.permission() != null && !s.permission().isEmpty() && !event.getPlayer().hasPermission(s.permission())) continue;
            allowed.add(s.label());
            if (showAliases) for (String a : s.aliases()) allowed.add(a.toLowerCase(Locale.ROOT));
        }
        event.getCommands().removeIf(c -> !allowed.contains(c.toLowerCase(Locale.ROOT)));
    }

    /** {@code /aide [page]}: the commands the player can use, a click writes one in the chat bar. */
    boolean help(org.bukkit.command.CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        List<Commands.Shown> all = visible(player);
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int page = 1;
        if (args.length > 0) {
            try {
                page = Math.max(1, Math.min(pages, Integer.parseInt(args[0])));
            } catch (NumberFormatException ignored) {
                // not a number: first page
            }
        }
        player.sendMessage(Texts.parse("&#FFD94D&lCommandes &8(" + page + "/" + pages + ") &7Clique pour l'écrire."));
        for (Commands.Shown s : all.subList((page - 1) * PER_PAGE, Math.min(all.size(), page * PER_PAGE))) {
            Component line = Texts.parse("&#FFD94D/" + s.label() + (s.description().isEmpty() ? "" : " &8» &#B376E5" + s.description()))
                    .clickEvent(ClickEvent.suggestCommand("/" + s.label() + " "))
                    .hoverEvent(HoverEvent.showText(Texts.parse("&7Clique pour écrire &f/" + s.label())));
            player.sendMessage(line);
        }
        if (pages > 1) {
            Component nav = Component.empty();
            if (page > 1) nav = nav.append(Texts.parse("&#FFD94D« Page précédente").clickEvent(ClickEvent.runCommand("/" + label + " " + (page - 1))));
            if (page > 1 && page < pages) nav = nav.append(Texts.parse("&8   |   "));
            if (page < pages) nav = nav.append(Texts.parse("&#FFD94DPage suivante »").clickEvent(ClickEvent.runCommand("/" + label + " " + (page + 1))));
            player.sendMessage(nav);
        }
        return true;
    }
}
