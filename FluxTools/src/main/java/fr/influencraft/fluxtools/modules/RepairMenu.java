package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Repair menu {@code /repair}, a Flux menu ({@code repair}, plugins/FluxTools/menus/) with one deposit slot: the
 * button next to it (one-item list {@code repair_button}) describes the item (missing, not repairable, already new,
 * not enough money, ready) and a left click charges the price ({@code modules.repair.cost}) then repairs.
 *
 * <p>Two effects, together: full durability, and the anvil cost back to zero (the "Too expensive!" of vanilla).
 * Items marked "Irréparable" are refused, like at the anvil ({@link AnvilRestrictionListener}).</p>
 */
public final class RepairMenu implements CommandExecutor {

    public static final String MENU = "repair";
    private static final String BUTTON = "repair_button";

    private enum State { EMPTY, NOT_REPAIRABLE, ALREADY_NEW, TOO_POOR, READY }

    private final JavaPlugin plugin;

    public RepairMenu(JavaPlugin plugin) {
        this.plugin = plugin;
        Lists.registerItem(plugin, BUTTON, this::button);
        FluxApi.registerAction(plugin, "repair", (player, view, argument) -> {
            if (argument.trim().equalsIgnoreCase("fix")) repair(player, view);
            else refresh(player, view);
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!player.hasPermission("influbridge.repair")) {
            Texts.send(player, "&#FF5C5CTu n'as pas accès à la réparation.");
            return true;
        }
        Lists.invalidate(player, BUTTON);
        FluxApi.open(player, MENU);
        return true;
    }

    private void refresh(Player player, MenuView view) {
        Lists.invalidate(player, BUTTON);
        if (view != null) view.refreshButtons();
    }

    private static ItemStack deposited(MenuView view) {
        if (view == null) return null;
        for (ItemStack item : view.inputs().values()) if (item != null && !item.getType().isAir()) return item;
        return null;
    }

    private ItemStack button(Player player) {
        ItemStack item = deposited(FluxApi.view(player));
        double cost = cost();
        String costLine = "&#B376E5Coût &8► &f" + money(player, cost);
        return switch (evaluate(player, item, cost)) {
            case EMPTY -> icon(Material.PAPER, "&#FFD94DAucun objet déposé",
                    List.of("&#B376E5Place l'objet à réparer dans la case de gauche.", "", costLine));
            case NOT_REPAIRABLE -> icon(Material.BARRIER, "&#FF5C5CObjet non réparable",
                    List.of("&#B376E5Seuls les objets qui s'usent (armes, outils, armures, cannes) peuvent être réparés ici."));
            case ALREADY_NEW -> icon(Material.LIME_DYE, "&#2ECF7BObjet déjà comme neuf",
                    List.of("&#B376E5Durabilité pleine et coût d'enclume déjà à zéro.", "", "&#B376E5Rien à te facturer."));
            case TOO_POOR -> icon(Material.GOLD_NUGGET, "&#FF5C5CFonds insuffisants",
                    List.of(costLine, "&#B376E5Solde &8► &f" + money(player, Economy.main().balance(player))));
            case READY -> icon(Material.ANVIL, "&r&7%img_left_click%&#FFD94D Réparer",
                    List.of(costLine, "", "&#B376E5Restaure la durabilité et remet le coût d'enclume à zéro."));
        };
    }

    private double cost() {
        return plugin.getConfig().getDouble("modules.repair.cost", 500.0);
    }

    private State evaluate(Player player, ItemStack item, double cost) {
        if (item == null || item.getType().isAir()) return State.EMPTY;
        // an item that wears out never stacks
        if (item.getAmount() > 1 || item.getType().getMaxDurability() <= 0) return State.NOT_REPAIRABLE;
        if (AnvilRestrictionListener.isMarkedUnrepairable(item)) return State.NOT_REPAIRABLE;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return State.NOT_REPAIRABLE;
        int repairCost = meta instanceof Repairable repairable ? repairable.getRepairCost() : 0;
        // rods with their own durability: the vanilla bar may look full while the rod is worn
        boolean customDamaged = CustomFishingDurability.isCustomDurabilityItem(item) && CustomFishingDurability.isDamaged(item);
        if (damageable.getDamage() <= 0 && repairCost <= 0 && !customDamaged) return State.ALREADY_NEW;
        if (Economy.main().balance(player) < cost) return State.TOO_POOR;
        return State.READY;
    }

    private void repair(Player player, MenuView view) {
        ItemStack item = deposited(view);
        double cost = cost();
        State state = evaluate(player, item, cost);
        if (state != State.READY) {
            Texts.send(player, switch (state) {
                case EMPTY -> "&#FF5C5CDépose d'abord un objet dans la case de gauche.";
                case NOT_REPAIRABLE -> "&#FF5C5CCet objet ne peut pas être réparé.";
                case ALREADY_NEW -> "&#FF5C5CCet objet est déjà comme neuf.";
                default -> "&#FF5C5CIl te faut &f" + money(player, cost) + "&#FF5C5C pour réparer cet objet.";
            });
            Sounds.cue(player, "error");
            return;
        }
        if (!Economy.main().take(player, cost)) {
            Texts.send(player, "&#FF5C5CLe paiement a échoué, ton objet n'a pas été touché.");
            Sounds.cue(player, "error");
            return;
        }
        // the deposit zone now belongs to the repair: the item goes back to the player repaired
        List<ItemStack> taken = view.takeInputs();
        List<ItemStack> back = new ArrayList<>();
        for (ItemStack stack : taken) {
            if (stack.isSimilar(item) && stack.getAmount() == item.getAmount()) {
                ItemMeta meta = stack.getItemMeta();
                ((Damageable) meta).setDamage(0);
                if (meta instanceof Repairable repairable) repairable.setRepairCost(0);
                stack.setItemMeta(meta);
                // rods with their own durability: their real counter, after the meta (setItemMeta would overwrite it)
                if (CustomFishingDurability.isCustomDurabilityItem(stack)) CustomFishingDurability.restore(player, stack);
            }
            back.add(stack);
        }
        Inventories.giveOrDrop(player, back);
        refresh(player, view);
        // money loss notification convention (docs/notifications/bitblock.md)
        Texts.send(player, "&r&7%img_bitblock% &#B376E5Tu as perdu &f" + fr.influencraft.flux.api.util.Format.decimal(cost)
                + " &#B376E5Bitblock &#FF5C5C▼&7 [ʀᴇᴘᴀʀᴀᴛɪᴏɴ]");
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f);
    }

    private static String money(Player player, double amount) {
        Currency c = Economy.main();
        return Texts.placeholders(c == null ? fr.influencraft.flux.api.util.Format.decimal(amount) : c.format(amount), player);
    }

    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Texts.parse(name));
            meta.lore(Texts.lore(lore, null));
            icon.setItemMeta(meta);
        }
        return icon;
    }
}
