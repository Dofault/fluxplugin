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
 * - /pets (alias /pet, /familiers) : lance pet-settings.menu-command (InfluPet ne fournit aucun menu de
 *   joueur : chez nous, le menu FluxMenu) ; « activate <familier> », « deactivate [familier] » (sans familier :
 *   tous ; « activer » / « desactiver » acceptés aussi).
 * - Sous-commandes utilisées par les boutons du menu (PetView) : « toggle <familier> » (l'activer, ou le ranger
 *   s'il est actif), « filter next|previous », « page next|previous », « select <familier> » (familier consulté,
 *   %influpet_selected_...%), « view reset » (page 1, sans filtre), « visibility » (cacher / montrer).
 * - /influpet pet give|take|givexp|givecurrentxp|reset|setlevel|activate|deactivate <joueur> ... :
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
            openMenu(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "activate", "activer" -> {
                Pet pet = args.length > 1 ? Pets.get(args[1]) : null;
                if (pet == null || !PetData.has(player, pet)) {
                    PetMessages.send(player, "invalid-pet");
                } else if (!pet.canActivate(player)) {
                    PetMessages.send(player, "cannot-activate-pet", "%pet%", pet.name());
                } else {
                    tell(player, pet, PetData.activate(player, pet));
                }
            }
            case "deactivate", "desactiver", "ranger" -> {
                // sans familier précisé : tous les familiers actifs
                Pet pet = args.length > 1 ? Pets.get(args[1]) : null;
                List<Pet> done = pet != null ? (PetData.deactivate(player, pet) ? List.of(pet) : List.of())
                        : PetData.deactivateAll(player);
                if (done.isEmpty()) {
                    PetMessages.send(player, "no-pet-active");
                }
                done.forEach(p -> PetMessages.send(player, "deactivated-pet", "%pet%", p.name()));
            }
            case "toggle" -> {
                Pet pet = args.length > 1 ? Pets.get(args[1]) : null;
                if (pet == null || !PetData.has(player, pet)) {
                    return true;
                }
                if (PetData.isActive(player, pet)) {
                    if (PetData.deactivate(player, pet)) {
                        PetMessages.send(player, "deactivated-pet", "%pet%", pet.name());
                    }
                } else if (!pet.canActivate(player)) {
                    PetMessages.send(player, "cannot-activate-pet", "%pet%", pet.name());
                } else {
                    PetData.Activation result = PetData.activate(player, pet);
                    if (result != PetData.Activation.OK) {
                        tell(player, pet, result);
                    }
                }
                PetView.invalidate(player);
            }
            case "filter" -> PetView.stepFilter(player, args.length > 1 && args[1].toLowerCase(Locale.ROOT).startsWith("prev") ? -1 : 1);
            case "page" -> PetView.stepPage(player, args.length > 1 && args[1].toLowerCase(Locale.ROOT).startsWith("prev") ? -1 : 1);
            case "select" -> {
                Pet pet = args.length > 1 ? Pets.get(args[1]) : null;
                if (pet != null && PetData.has(player, pet)) {
                    PetView.select(player, pet);
                }
            }
            case "view" -> PetView.reset(player);
            case "visibility" -> PetData.setHidden(player, !PetData.hidden(player));
            default -> openMenu(player);
        }
        return true;
    }

    /** /pets sans argument : commande console pet-settings.menu-command (%player% = nom du joueur), sinon rien. */
    private static void openMenu(Player player) {
        String command = Pets.settings().getString("menu-command");
        if (command == null || command.isBlank()) {
            PetMessages.send(player, "no-menu");
            return;
        }
        String line = command.startsWith("/") ? command.substring(1) : command;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.replace("%player%", player.getName()));
    }

    private static boolean isActivate(String arg) {
        return arg.equalsIgnoreCase("activate") || arg.equalsIgnoreCase("activer");
    }

    private static boolean isDeactivate(String arg) {
        return arg.equalsIgnoreCase("deactivate") || arg.equalsIgnoreCase("desactiver") || arg.equalsIgnoreCase("ranger");
    }

    /** Message d'une activation (commande, menu). */
    static void tell(Player player, Pet pet, PetData.Activation result) {
        switch (result) {
            case OK -> PetMessages.send(player, "activated-pet", "%pet%", pet.name());
            case ALREADY -> PetMessages.send(player, "pet-already-active");
            case FULL -> PetMessages.send(player, "max-pets-reached", "%max%",
                    String.valueOf(PetData.maxActive(player)));
            case CANCELLED -> PetMessages.send(player, "cancelled-swap");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("activate", "deactivate"));
        } else if (args.length == 2 && isActivate(args[0]) && sender instanceof Player player) {
            for (Pet pet : Pets.values()) {
                if (PetData.has(player, pet) && !PetData.isActive(player, pet)) {
                    options.add(pet.id());
                }
            }
        } else if (args.length == 2 && isDeactivate(args[0]) && sender instanceof Player player) {
            PetData.actives(player).forEach(p -> options.add(p.id()));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(last)).toList();
    }

    // ------------------------------------------------------------------ administration (/influpet pet ...)

    /** Message d'administration (messages.admin.<key>), sans préfixe. */
    private static void admin(CommandSender to, String key, String... replacements) {
        to.sendMessage(Texts.of(PetMessages.format("admin." + key, replacements)));
    }

    /** args : sous-commande et ses arguments (sans « pet »). */
    public static void admin(CommandSender sender, String[] args) {
        if (!Pets.enabled()) {
            admin(sender, "unavailable");
            return;
        }
        if (args.length < 2) {
            admin(sender, "usage");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
        }
        if (target == null) {
            admin(sender, "unknown-player", "%player%", args[1]);
            return;
        }
        Player online = target.getPlayer();
        String who = target.getName();
        switch (sub) {
            case "deactivate" -> {
                // sans familier précisé : tous ses familiers actifs
                Pet one = args.length > 2 ? Pets.get(args[2]) : null;
                if (online == null) {
                    admin(sender, "offline", "%player%", who);
                } else if (one != null ? PetData.deactivate(online, one) : !PetData.deactivateAll(online).isEmpty()) {
                    admin(sender, "deactivated", "%player%", who);
                } else {
                    admin(sender, "nothing-to-deactivate", "%player%", who);
                }
                return;
            }
            case "givecurrentxp" -> {
                Pet active = online == null ? null : PetData.active(online);
                Double amount = args.length > 2 ? parse(args[2]) : null;
                if (active == null) {
                    admin(sender, "no-active-pet", "%player%", who);
                } else if (amount == null) {
                    admin(sender, "invalid-number");
                } else {
                    PetData.giveXp(online, active, amount, false);
                    admin(sender, "xp-given", "%xp%", Pet.nice(amount), "%pet%", active.name(), "%player%", who);
                }
                return;
            }
            default -> {
                // les autres sous-commandes prennent un familier
            }
        }
        Pet pet = args.length > 2 ? Pets.get(args[2]) : null;
        if (pet == null) {
            admin(sender, "unknown-pet", "%id%", args.length > 2 ? args[2] : "");
            return;
        }
        String name = pet.name();
        switch (sub) {
            case "give" -> admin(sender, PetData.adopt(target, pet) ? "adopted" : "already-adopted",
                    "%player%", who, "%pet%", name);
            case "take" -> {
                PetData.take(target, pet);
                admin(sender, "taken", "%player%", who, "%pet%", name);
            }
            case "reset" -> {
                if (!PetData.has(target, pet)) {
                    admin(sender, "not-owned", "%player%", who, "%pet%", name);
                    return;
                }
                PetData.setXp(target, pet, 0);
                PetData.setLevel(target, pet, 1);
                admin(sender, "level-set", "%player%", who, "%pet%", name, "%level%", "1");
            }
            case "setlevel" -> {
                Double level = args.length > 3 ? parse(args[3]) : null;
                if (!PetData.has(target, pet)) {
                    admin(sender, "not-owned", "%player%", who, "%pet%", name);
                } else if (level == null || level < 1) {
                    admin(sender, "invalid-number");
                } else {
                    PetData.setXp(target, pet, 0);
                    PetData.setLevel(target, pet, (int) Math.round(level));
                    admin(sender, "level-set", "%player%", who, "%pet%", name,
                            "%level%", String.valueOf(PetData.level(target, pet)));
                }
            }
            case "givexp" -> {
                Double amount = args.length > 3 ? parse(args[3]) : null;
                if (amount == null) {
                    admin(sender, "invalid-number");
                } else if (online == null) {
                    admin(sender, "offline", "%player%", who);
                } else if (!PetData.has(target, pet)) {
                    admin(sender, "not-owned", "%player%", who, "%pet%", name);
                } else {
                    PetData.giveXp(online, pet, amount, false);
                    admin(sender, "xp-given", "%xp%", Pet.nice(amount), "%pet%", name, "%player%", who);
                }
            }
            case "activate" -> {
                if (online == null) {
                    admin(sender, "offline", "%player%", who);
                } else if (!PetData.has(target, pet)) {
                    admin(sender, "not-owned", "%player%", who, "%pet%", name);
                } else {
                    PetData.Activation result = PetData.activate(online, pet);
                    if (result == PetData.Activation.OK) {
                        admin(sender, "activated", "%player%", who, "%pet%", name);
                    } else {
                        admin(sender, "activation-failed", "%player%", who, "%pet%", name,
                                "%reason%", result.name(), "%max%", String.valueOf(PetData.maxActive(online)));
                    }
                }
            }
            default -> admin(sender, "usage");
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
