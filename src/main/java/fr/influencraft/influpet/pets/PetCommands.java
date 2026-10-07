package fr.influencraft.influpet.pets;

import fr.influencraft.influpet.Texts;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Commandes des familiers (reprises d'EcoPets).
 * - /familiers (alias /pets, /pet) : menu ; « activer <familier> », « desactiver ».
 * - /influpet familier give|take|givexp|givecurrentxp|reset|setlevel|activate|deactivate <joueur> ... :
 *   administration (console comprise). « reset » remet le familier au niveau 1 sans le retirer (le reset
 *   d'EcoPets le faisait passer au niveau 0, ce qui le retirait).
 */
public final class PetCommands implements TabExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            PetMessages.send(sender, "player-only");
            return true;
        }
        if (!Pets.enabled()) {
            return true;
        }
        if (args.length == 0) {
            PetsMenu.open(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "activer", "activate" -> {
                Pet pet = args.length > 1 ? Pets.get(args[1]) : null;
                if (pet == null || !PetData.has(player, pet)) {
                    PetMessages.send(player, "invalid-pet");
                } else if (pet.equals(PetData.active(player))) {
                    PetMessages.send(player, "pet-already-active");
                } else if (!pet.canActivate(player)) {
                    PetMessages.send(player, "cannot-activate-pet", "%pet%", pet.name());
                } else if (PetData.setActive(player, pet)) {
                    PetMessages.send(player, "activated-pet", "%pet%", pet.name());
                } else {
                    PetMessages.send(player, "cancelled-swap");
                }
            }
            case "desactiver", "deactivate", "ranger" -> {
                Pet pet = PetData.active(player);
                if (pet == null) {
                    PetMessages.send(player, "no-pet-active");
                } else if (PetData.setActive(player, null)) {
                    PetMessages.send(player, "deactivated-pet", "%pet%", pet.name());
                }
            }
            default -> PetsMenu.open(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("activer", "desactiver"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("activer") && sender instanceof Player player) {
            for (Pet pet : Pets.values()) {
                if (PetData.has(player, pet)) {
                    options.add(pet.id());
                }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(last)).toList();
    }

    // ------------------------------------------------------------------ administration (/influpet familier ...)

    private static void raw(CommandSender to, String text) {
        to.sendMessage(Texts.of(text));
    }

    private static final String USAGE = "&#FF7A59/influpet familier <give|take|givexp|givecurrentxp|reset|setlevel|"
            + "activate|deactivate> <joueur> [familier] [valeur]";

    /** args : sous-commande et ses arguments (sans « familier »). */
    public static void admin(CommandSender sender, String[] args) {
        if (!Pets.enabled()) {
            raw(sender, "&#D9534FSystème de familiers inactif (cf. logs du démarrage).");
            return;
        }
        if (args.length < 2) {
            raw(sender, USAGE);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
        }
        if (target == null) {
            raw(sender, "&#D9534FJoueur inconnu : " + args[1]);
            return;
        }
        Player online = target.getPlayer();
        String who = target.getName();
        switch (sub) {
            case "deactivate" -> {
                if (online == null) {
                    raw(sender, "&#D9534F" + who + " n'est pas connecté.");
                } else if (PetData.setActive(online, null)) {
                    raw(sender, "&#EADBC8Familier de " + who + " rangé.");
                }
                return;
            }
            case "givecurrentxp" -> {
                Pet active = online == null ? null : PetData.active(online);
                Double amount = args.length > 2 ? parse(args[2]) : null;
                if (active == null || amount == null) {
                    raw(sender, "&#D9534F" + (active == null ? who + " n'a pas de familier actif (ou n'est pas connecté)."
                            : "Montant invalide."));
                    return;
                }
                PetData.giveXp(online, active, amount, false);
                raw(sender, "&#EADBC8" + Pet.nice(amount) + " XP donnée au familier actif de " + who + ".");
                return;
            }
            default -> {
                // les autres sous-commandes prennent un familier
            }
        }
        Pet pet = args.length > 2 ? Pets.get(args[2]) : null;
        if (pet == null) {
            raw(sender, "&#D9534FFamilier inconnu." + (args.length > 2 ? " (" + args[2] + ")" : ""));
            return;
        }
        String name = pet.name() + "&#EADBC8";
        switch (sub) {
            case "give" -> raw(sender, PetData.adopt(target, pet)
                    ? "&#EADBC8" + who + " adopte " + name + "."
                    : "&#D9534F" + who + " a déjà ce familier (ou adoption annulée).");
            case "take" -> {
                PetData.take(target, pet);
                raw(sender, "&#EADBC8" + name + " retiré à " + who + ".");
            }
            case "reset" -> {
                if (!PetData.has(target, pet)) {
                    raw(sender, "&#D9534F" + who + " n'a pas ce familier.");
                    return;
                }
                PetData.setXp(target, pet, 0);
                PetData.setLevel(target, pet, 1);
                raw(sender, "&#EADBC8" + name + " de " + who + " remis au niveau 1.");
            }
            case "setlevel" -> {
                Double level = args.length > 3 ? parse(args[3]) : null;
                if (level == null || level < 1 || !PetData.has(target, pet)) {
                    raw(sender, "&#D9534F" + (PetData.has(target, pet) ? "Niveau invalide." : who + " n'a pas ce familier."));
                    return;
                }
                PetData.setXp(target, pet, 0);
                PetData.setLevel(target, pet, (int) Math.round(level));
                raw(sender, "&#EADBC8" + name + " de " + who + " au niveau " + PetData.level(target, pet) + ".");
            }
            case "givexp" -> {
                Double amount = args.length > 3 ? parse(args[3]) : null;
                if (amount == null || online == null || !PetData.has(target, pet)) {
                    raw(sender, "&#D9534F" + (amount == null ? "Montant invalide."
                            : online == null ? who + " n'est pas connecté." : who + " n'a pas ce familier."));
                    return;
                }
                PetData.giveXp(online, pet, amount, false);
                raw(sender, "&#EADBC8" + Pet.nice(amount) + " XP donnée à " + name + " de " + who + ".");
            }
            case "activate" -> {
                if (online == null || !PetData.has(target, pet)) {
                    raw(sender, "&#D9534F" + (online == null ? who + " n'est pas connecté." : who + " n'a pas ce familier."));
                } else if (PetData.setActive(online, pet)) {
                    raw(sender, "&#EADBC8" + name + " activé pour " + who + ".");
                }
            }
            default -> raw(sender, USAGE);
        }
    }

    public static List<String> adminTab(String[] args) {
        return switch (args.length) {
            case 1 -> List.of("give", "take", "givexp", "givecurrentxp", "reset", "setlevel", "activate", "deactivate");
            case 2 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            case 3 -> Pets.ids();
            default -> List.of();
        };
    }

    private static Double parse(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
