package fr.influencraft.fluxjobs;

import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /metier [join|leave|levels|actions] <job>} (French sub-commands {@code rejoindre}, {@code quitter},
 * {@code progression}, {@code blocs} are what the menus run); no argument opens the Careers menu.
 */
final class JobChoiceCommand {

    private static final List<String> SUBCOMMANDS = List.of("rejoindre", "quitter", "progression", "blocs");

    private final FluxJobs plugin;
    private final JobBlockBreakdownMenu actionsMenu;

    JobChoiceCommand(FluxJobs plugin) {
        this.plugin = plugin;
        this.actionsMenu = new JobBlockBreakdownMenu(plugin);
    }

    boolean run(CommandSender sender, String label, String[] args) {
        Messages m = plugin.messages();
        if (!(sender instanceof Player player)) {
            m.send(sender, "players-only");
            return true;
        }
        if (args.length == 0) {
            Bukkit.dispatchCommand(player, "metiers");
            return true;
        }
        String sub = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join", "rejoindre" -> "join";
            case "leave", "quitter" -> "leave";
            case "levels", "progression" -> "levels";
            case "actions", "blocs" -> "actions";
            default -> "";
        };
        if (sub.isEmpty() || args.length < 2 || !sub.equals("actions") && args.length != 2) {
            m.send(player, "usage", "%label%", label);
            return true;
        }
        String job = args[1];
        switch (sub) {
            case "join" -> join(player, job);
            case "leave" -> leave(player, job);
            case "levels" -> {
                if (!Jobs.get().openLevels(player, job)) m.send(player, "unknown-job", "%job%", job);
            }
            default -> actionsMenu.open(player, job, args.length >= 3 ? page(args[2]) : 1);
        }
        return true;
    }

    private static int page(String text) {
        try {
            return Math.max(1, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void join(Player player, String job) {
        String name = Jobs.get().displayName(job);
        String key = switch (Jobs.get().join(player, job)) {
            case DONE -> "join.done";
            case ALREADY -> "join.already";
            case NOT_ALLOWED -> "join.limit";
            case NO_MONEY -> "join.no-money";
            case UNKNOWN_JOB -> "unknown-job";
            case FAILED -> "join.failed";
        };
        plugin.messages().send(player, key, "%job%", key.equals("unknown-job") ? job : name);
    }

    private void leave(Player player, String job) {
        String name = Jobs.get().displayName(job);
        String key = switch (Jobs.get().leave(player, job)) {
            case DONE -> "leave.done";
            case ALREADY -> "leave.not-in";
            case NO_MONEY -> "leave.no-money";
            case UNKNOWN_JOB -> "unknown-job";
            default -> "leave.failed";
        };
        plugin.messages().send(player, key, "%job%", key.equals("unknown-job") ? job : name);
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(o -> o.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            return Jobs.get().ids().stream().filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
