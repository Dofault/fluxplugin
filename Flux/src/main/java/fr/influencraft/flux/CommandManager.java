package fr.influencraft.flux;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commands seen by players ({@code command-suggestions} of Flux's config, lists in {@code commands.yml}): when a
 * player types "/", every command is hidden but the suggested ones (suite commands, menu commands, {@code others}),
 * whatever plugin they come from. The server still drops what the player has no permission for: an unranked player
 * never sees {@code /fly}. Only suggestions change, a hidden command can still be run. {@code /aide} lists the same
 * commands with their description.
 * <p>An alias has no permission of its own ({@code /meule} = {@code cmi grindstone} in the server's commands.yml,
 * {@code /don} in CMI's CustomAlias.yml): it is shown only if the player may run the command it points to, so a
 * rank perk stays hidden until the rank is reached.
 */
final class CommandManager implements Listener {

    private static final int PER_PAGE = 10;

    private volatile boolean enabled;
    private volatile boolean showAliases;
    private volatile String bypass = "flux.commands.all";
    /** CMI custom aliases (label -> commands run), read from CMI's CustomAlias.yml at each reload. */
    private volatile Map<String, List<String>> cmiAliases = Map.of();

    void configure(ConfigurationSection section) {
        enabled = section != null && section.getBoolean("enabled", false);
        if (section != null) {
            bypass = section.getString("bypass-permission", "flux.commands.all");
            showAliases = section.getBoolean("show-aliases", false);
        }
        Commands.reload();
        cmiAliases = readCmiAliases();
        // resend the command tree to the players online (after a reload)
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    /** Suggested commands this player may run, in the order of commands.yml. */
    List<Commands.Shown> visible(Player player) {
        List<Commands.Shown> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Commands.Shown s : Commands.shown()) {
            if (s.permission() != null && !s.permission().isEmpty() && !player.hasPermission(s.permission())) continue;
            if (!permitted(player, s.label(), 0)) continue;
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
            if (!permitted(event.getPlayer(), s.label(), 0)) continue;
            allowed.add(s.label());
            if (showAliases) for (String a : s.aliases()) allowed.add(a.toLowerCase(Locale.ROOT));
        }
        event.getCommands().removeIf(c -> !allowed.contains(c.toLowerCase(Locale.ROOT)));
    }

    /** Whether the player may run this command, following aliases to the command they run. */
    private boolean permitted(Player player, String label, int depth) {
        label = label.toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) label = label.substring(1);
        if (depth > 4) return false;
        List<String> targets = cmiAliases.get(label);
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (targets == null && command instanceof FormattedCommandAlias alias) targets = formats(alias);
        if (targets == null) return command != null && command.testPermissionSilent(player);
        // CMI's "perm:x!" / "perm:x@!" conditions pick one branch: any branch the player can run is enough
        for (String line : targets) {
            String cmd = line.trim();
            if (cmd.startsWith("perm:")) {
                int bang = cmd.indexOf("! ");
                if (bang < 0) continue;
                String perm = cmd.substring(5, bang);
                boolean negated = perm.endsWith("@");
                if (negated) perm = perm.substring(0, perm.length() - 1);
                if (player.hasPermission(perm) == negated) continue;
                cmd = cmd.substring(bang + 2).trim();
            }
            if (cmd.startsWith("delay!") || cmd.isEmpty()) continue;
            String[] words = cmd.split(" ");
            String target = words[0];
            int colon = target.indexOf(':');
            if (colon >= 0) {
                // namespaced (powerfly:fly): that plugin's own command, never an alias of the same name
                Command direct = Bukkit.getCommandMap().getCommand(target.toLowerCase(Locale.ROOT));
                if (target.substring(colon + 1).equalsIgnoreCase("cmi")) target = "cmi";
                else if (direct != null && direct.testPermissionSilent(player)) return true;
                else continue;
            }
            // CMI checks cmi.command.<sub> for "cmi <sub>", the /cmi command itself is open to everyone
            if (target.equalsIgnoreCase("cmi") && words.length > 1) {
                if (player.hasPermission("cmi.command." + words[1].toLowerCase(Locale.ROOT))) return true;
            } else if (permitted(player, target, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> formats(FormattedCommandAlias alias) {
        try {
            Field field = FormattedCommandAlias.class.getDeclaredField("formatStrings");
            field.setAccessible(true);
            return List.of((String[]) field.get(alias));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, List<String>> readCmiAliases() {
        File file = new File(Bukkit.getPluginsFolder(), "CMI/CustomAlias/CustomAlias.yml");
        if (!file.isFile()) return Map.of();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("CustomAlias");
        if (root == null) return Map.of();
        Map<String, List<String>> out = new HashMap<>();
        for (String key : root.getKeys(false)) {
            // Perm: true makes CMI require cmi.customalias.<alias>
            List<String> cmds = new ArrayList<>(root.getStringList(key + ".Cmds"));
            if (root.getBoolean(key + ".Perm", false)) cmds.replaceAll(c -> "perm:cmi.customalias." + key.toLowerCase(Locale.ROOT) + "! " + c);
            out.put(key.toLowerCase(Locale.ROOT), cmds);
        }
        return out;
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
