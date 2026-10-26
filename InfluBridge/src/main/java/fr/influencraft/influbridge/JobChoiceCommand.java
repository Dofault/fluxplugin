package fr.influencraft.influbridge;

import com.willfp.eco.core.price.ConfiguredPrice;
import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Backend du menu DeluxeMenus /métiers (gui_menus/metiers/) — rejoindre()/quitter() sont les
 * seules actions qui mutent un état (appelées depuis les boutons "Rejoindre"/"Confirmer" des
 * menus détail/confirmation, `[player] metier rejoindre <job>` / `[player] metier quitter
 * <job>`), progression()/blocs() ne font qu'ouvrir un écran. Existent uniquement parce
 * qu'EcoJobs ne facture jamais depuis une commande (confirmé par décompilation :
 * EcoJobsAPI.joinJob/leaveJob ne font que muter l'état, Price.pay() n'est appelé que depuis
 * les GUI internes du plugin) — rejoindre()/quitter() reproduisent donc juste ce que fait le
 * clic natif, avec le vrai prix du job (job.getJoinPrice()/getLeavePrice(), configuré dans
 * EcoJobs/jobs/<job>.yml, jamais dupliqué ici).
 */
public final class JobChoiceCommand implements CommandExecutor, TabCompleter {

    private final JobBlockBreakdownMenu blockBreakdownMenu = new JobBlockBreakdownMenu();

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
        Job job = Jobs.getByID(jobId);
        if (job == null) {
            send(player, "&cMétier inconnu : &7" + jobId);
            return;
        }

        if (!EcoJobsAPI.canJoinJob(player, job)) {
            if (EcoJobsAPI.hasJobActive(player, job)) {
                send(player, "&7Tu exerces déjà &f" + job.getName() + "&7.");
            } else {
                send(player, "&cTu as atteint ta limite de métiers actifs pour ton rang.");
            }
            return;
        }

        ConfiguredPrice price = job.getJoinPrice();
        if (!price.canAfford(player, 1.0)) {
            send(player, "&cTu n'as pas assez de BitBlock pour rejoindre &7" + job.getName() + "&c.");
            return;
        }

        price.pay(player, 1.0);
        EcoJobsAPI.joinJob(player, job);
        send(player, "&fTu as rejoint &7" + job.getName() + "&f !");
    }

    private void quitter(Player player, String jobId) {
        Job job = Jobs.getByID(jobId);
        if (job == null) {
            send(player, "&cMétier inconnu : &7" + jobId);
            return;
        }

        if (!EcoJobsAPI.hasJobActive(player, job)) {
            send(player, "&7Tu n'exerces pas &f" + job.getName() + "&7.");
            return;
        }

        ConfiguredPrice price = job.getLeavePrice();
        if (!price.canAfford(player, 1.0)) {
            send(player, "&cTu n'as pas assez de BitBlock pour quitter &7" + job.getName() + "&c.");
            return;
        }

        price.pay(player, 1.0);
        EcoJobsAPI.leaveJob(player, job);
        send(player, "&7Tu as quitté &f" + job.getName() + "&7.");
    }

    private void progression(Player player, String jobId) {
        Job job = Jobs.getByID(jobId);
        if (job == null) {
            send(player, "&cMétier inconnu : &7" + jobId);
            return;
        }
        job.getLevelGUI().open(player);
    }

    private void send(Player player, String message) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("rejoindre", "quitter", "progression", "blocs").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            return Jobs.values().stream()
                    .map(Job::getId)
                    .filter(id -> id.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
