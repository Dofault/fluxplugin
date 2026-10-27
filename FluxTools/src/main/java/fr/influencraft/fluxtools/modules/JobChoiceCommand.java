package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.jobs.Jobs;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Backend of the jobs menu: join/leave ({@code /metier rejoindre|quitter <job>}) through the jobs service of Flux,
 * which lets the job plugin charge its own price (never charge it here too); progression and blocks only open a
 * screen.
 */
public final class JobChoiceCommand implements CommandExecutor, TabCompleter {

    private final JobBlockBreakdownMenu blockBreakdownMenu;

    public JobChoiceCommand(org.bukkit.plugin.Plugin plugin) {
        this.blockBreakdownMenu = new JobBlockBreakdownMenu(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Cette commande est réservée aux joueurs.");
            return true;
        }

        if (args.length == 0) {
            org.bukkit.Bukkit.dispatchCommand(player, "metiers");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "rejoindre" -> {
                if (args.length != 2) {
                    send(player, "&7Usage : &f/metier rejoindre <métier>");
                    return true;
                }
                rejoindre(player, args[1]);
            }
            case "quitter" -> {
                if (args.length != 2) {
                    send(player, "&7Usage : &f/metier quitter <métier>");
                    return true;
                }
                quitter(player, args[1]);
            }
            case "progression" -> {
                if (args.length != 2) {
                    send(player, "&7Usage : &f/metier progression <métier>");
                    return true;
                }
                progression(player, args[1]);
            }
            case "blocs" -> {
                if (args.length < 2) {
                    send(player, "&7Usage : &f/metier blocs <métier> [page]");
                    return true;
                }
                int page = 1;
                if (args.length >= 3) {
                    try {
                        page = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {
                        // page invalide, repli sur 1
                    }
                }
                blockBreakdownMenu.open(player, args[1], page);
            }
            default -> send(player, "&7Usage : &f/metier <rejoindre|quitter|progression|blocs>");
        }
        return true;
    }

    private void rejoindre(Player player, String jobId) {
        Jobs.Provider jobs = Jobs.get();
        String name = jobs.displayName(jobId);
        switch (jobs.join(player, jobId)) {
            case DONE -> send(player, "&fTu as rejoint &7" + name + "&f !");
            case ALREADY -> send(player, "&7Tu exerces déjà &f" + name + "&7.");
            case NOT_ALLOWED -> send(player, "&cTu as atteint ta limite de métiers actifs pour ton rang.");
            case NO_MONEY -> send(player, "&cTu n'as pas assez de BitBlock pour rejoindre &7" + name + "&c.");
            case UNKNOWN_JOB -> send(player, "&cMétier inconnu : &7" + jobId);
            case FAILED -> send(player, "&cImpossible de rejoindre &7" + name + "&c, aucun BitBlock n'a été prélevé.");
        }
    }

    private void quitter(Player player, String jobId) {
        Jobs.Provider jobs = Jobs.get();
        String name = jobs.displayName(jobId);
        switch (jobs.leave(player, jobId)) {
            case DONE -> send(player, "&7Tu as quitté &f" + name + "&7.");
            case ALREADY -> send(player, "&7Tu n'exerces pas &f" + name + "&7.");
            case NO_MONEY -> send(player, "&cTu n'as pas assez de BitBlock pour quitter &7" + name + "&c.");
            case UNKNOWN_JOB -> send(player, "&cMétier inconnu : &7" + jobId);
            default -> send(player, "&cImpossible de quitter &7" + name + "&c, aucun BitBlock n'a été prélevé.");
        }
    }

    private void progression(Player player, String jobId) {
        if (!Jobs.get().openLevels(player, jobId)) send(player, "&cMétier inconnu : &7" + jobId);
    }

    private void send(Player player, String message) {
        fr.influencraft.flux.api.text.Texts.send(player, message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("rejoindre", "quitter", "progression", "blocs").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            return Jobs.get().ids().stream()
                    .filter(id -> id.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
