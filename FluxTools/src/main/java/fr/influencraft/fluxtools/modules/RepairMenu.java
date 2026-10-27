package fr.influencraft.fluxtools.modules;

import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.menu.MenuEventHandler;
import com.willfp.eco.core.gui.menu.events.CaptiveItemChangeEvent;
import com.willfp.eco.core.gui.slot.Slot;
import com.willfp.eco.util.NumberUtils;
import com.willfp.eco.util.StringUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu de réparation `/repair` — remplace l'alias CMI du même nom (désactivé dans
 * `CMI/Settings/Alias.yml`), qui réparait l'objet en main sans interface et dont la
 * tarification (BasePrice + % de worth + % d'enchantements) ne collait pas au besoin :
 * ici, un prix unique configurable (`repair.cost`, config.yml du plugin).
 *
 * <p>Le joueur dépose un objet dans la case captive, le bouton d'à côté se met à jour
 * tout seul (objet manquant / non réparable / déjà comme neuf / fonds insuffisants /
 * prêt) et le clic gauche facture puis répare.</p>
 *
 * <p>Deux effets sur l'objet, indissociables (demande utilisateur) :</p>
 * <ul>
 *   <li>durabilité remise à plein ({@link Damageable#setDamage(int)} à 0) ;</li>
 *   <li><b>coût d'enclume remis à zéro</b> ({@link Repairable#setRepairCost(int)}) — c'est
 *       le "poids" (RepairCost) qui grimpe à chaque passage à l'enclume et finit par
 *       afficher "Trop cher !" en vanilla. Sans cette remise à zéro, un objet réparé ici
 *       resterait définitivement inutilisable à l'enclume.</li>
 * </ul>
 *
 * <p>Refuse les objets portant le tag de lore "Irréparable" — même marqueur que
 * {@link AnvilRestrictionListener}, sinon `/repair` serait une porte dérobée qui annule
 * la règle de gameplay des outils de caisse non réparables.</p>
 */
public final class RepairMenu implements CommandExecutor {

    // Case où le joueur dépose son objet (slot captif) et case du bouton d'action.
    private static final int INPUT_ROW = 2;
    private static final int INPUT_COLUMN = 4;
    private static final int BUTTON_ROW = 2;
    private static final int BUTTON_COLUMN = 6;
    private static final int HEADER_ROW = 1;
    private static final int HEADER_COLUMN = 5;
    private static final int BACK_ROW = 3;
    private static final int BACK_COLUMN = 5;

    // Index brut de la case captive dans l'inventaire (menu de 3 lignes, 9 colonnes) :
    // seule source de vérité au moment du clic, la carte de captifs d'eco n'étant
    // rafraîchie que sur ses propres events.
    private static final int INPUT_RAW_SLOT = (INPUT_ROW - 1) * 9 + (INPUT_COLUMN - 1);

    // ItemsAdder (namespace influitems) : custom_model_data sur PAPER, cf. CLAUDE.md.
    private static final int ICON_ACCEPT = 10020;
    private static final int ICON_LOCK = 10026;
    private static final int ICON_CHECKMARK = 10032;
    private static final int ICON_UNKNOWN = 10181;
    private static final int ICON_BITBLOCK = 10086;
    private static final int ICON_BACK = 10028;

    private enum State {
        EMPTY, NOT_REPAIRABLE, ALREADY_NEW, TOO_POOR, READY
    }

    private final JavaPlugin plugin;
    private final Menu menu;

    public RepairMenu(JavaPlugin plugin) {
        this.plugin = plugin;
        this.menu = buildMenu();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Cette commande est réservée aux joueurs.");
            return true;
        }
        if (!player.hasPermission("influbridge.repair")) {
            player.sendMessage(StringUtils.format(
                    "&#FF5C5CTu n'as pas accès à la réparation.", player));
            return true;
        }
        menu.open(player);
        return true;
    }

    // ── Construction du menu ────────────────────────────────────────────────

    private Menu buildMenu() {
        MenuBuilder builder = Menu.builder(3)
                .setTitle(StringUtils.format("&#FFD94DRéparation"))
                // Sans ça, eco rend TOUTE la barre d'action intouchable pendant que le menu
                // est ouvert : GUIListener annule le clic sur l'item tenu en main
                // (preventMovingHeld), les clics touche-numéro (preventNumberKey) et même le
                // changement de slot sélectionné (PlayerItemHeldEvent). Un joueur ne pouvait
                // donc pas déposer un outil rangé dans sa barre d'action — le cas le plus
                // courant, puisque c'est là qu'on garde ses outils. L'opt-in est prévu pour
                // les menus, comme celui-ci, où le joueur doit pouvoir manipuler n'importe
                // lequel de ses objets.
                .allowChangingHeldItem();

        builder.setSlot(HEADER_ROW, HEADER_COLUMN, Slot.builder((player, openMenu) -> icon(
                        Material.ANVIL, 0,
                        "&#FFD94DRéparation",
                        List.of(
                                "&#B376E5Dépose une arme, un outil,",
                                "&#B376E5une armure ou une canne",
                                "&#B376E5dans la case de gauche.",
                                "",
                                "&#B376E5Remet aussi le coût d'enclume",
                                "&#B376E5à zéro (fini le « Trop cher ! »)."),
                        player))
                .build());

        // Case captive : le seul endroit du menu où le joueur peut poser/reprendre un objet.
        // PIÈGE eco : setCaptive() sans argument appelle setCaptive(false), et ce booléen
        // n'est PAS "captif ou non" (setCaptive(boolean) force captive = true dans les deux
        // cas) mais captiveFromEmpty. À false, RenderedInventory.render() redessine l'item
        // du slot à chaque rendu et le dépôt dans une case vide est refusé : le joueur ne
        // peut alors littéralement rien poser. Il faut donc setCaptive(true).
        builder.setSlot(INPUT_ROW, INPUT_COLUMN, Slot.builder().setCaptive(true).build());

        builder.setSlot(BUTTON_ROW, BUTTON_COLUMN, Slot.builder(this::buildButton)
                .onLeftClick((event, slot, clickedMenu) -> {
                    Player player = (Player) event.getWhoClicked();
                    repair(player, event.getView().getTopInventory(), clickedMenu);
                })
                .build());

        // Retour : ferme le menu (le onClose rend l'objet déposé) puis rouvre /menu,
        // même enchaînement que le bouton Retour de JobBlockBreakdownMenu.
        builder.setSlot(BACK_ROW, BACK_COLUMN, Slot.builder((player, openMenu) -> icon(
                        Material.PAPER, ICON_BACK,
                        "&r&7%img_left_click%&#FFD94D Retour",
                        List.of(), player))
                .onLeftClick((event, slot, clickedMenu) -> {
                    Player clicker = (Player) event.getWhoClicked();
                    clicker.closeInventory();
                    Bukkit.dispatchCommand(clicker, "menu");
                })
                .build());

        // Fond en vitres noires, posé UNIQUEMENT sur les cases laissées libres (jamais en
        // fond plein « par-dessous »). PIÈGE eco : deux setSlot sur la même case ne se
        // remplacent pas — les composants sont empilés par couche et, à couche égale, le
        // premier posé l'emporte. Un fond plein posé en premier masque donc tous les
        // boutons ajoutés ensuite. La case de dépôt reste vide pour rester visible.
        for (int row = 1; row <= 3; row++) {
            for (int column = 1; column <= 9; column++) {
                if (isUsedSlot(row, column)) {
                    continue;
                }
                builder.setSlot(row, column, Slot.builder((player, openMenu) -> icon(
                                Material.BLACK_STAINED_GLASS_PANE, 0, " ", List.of(), player))
                        .build());
            }
        }

        // Le bouton d'action décrit l'objet déposé : il doit se redessiner dès que la case
        // captive change. Le refresh est repoussé d'un tick, l'event étant émis pendant le
        // traitement du clic (l'inventaire n'a pas encore son état final).
        builder.onEvent(new MenuEventHandler<>(CaptiveItemChangeEvent.class) {
            @Override
            public void handle(Player player, Menu openMenu, CaptiveItemChangeEvent event) {
                Bukkit.getScheduler().runTask(plugin, () -> openMenu.refresh(player));
            }
        });

        // eco ne rend jamais les objets captifs à la fermeture (confirmé par décompilation
        // de EcoMenu.handleClose) : sans ça, l'objet déposé disparaîtrait purement et
        // simplement. Le surplus tombe au sol plutôt que d'être perdu.
        builder.onClose((event, closedMenu) -> {
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            for (ItemStack item : closedMenu.getCaptiveItems(player)) {
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        });

        return builder.build();
    }

    /** Cases tenues par un bouton ou par la case de dépôt : le fond ne doit pas y aller. */
    private static boolean isUsedSlot(int row, int column) {
        return (row == HEADER_ROW && column == HEADER_COLUMN)
                || (row == INPUT_ROW && column == INPUT_COLUMN)
                || (row == BUTTON_ROW && column == BUTTON_COLUMN)
                || (row == BACK_ROW && column == BACK_COLUMN);
    }

    private ItemStack buildButton(Player player, Menu openMenu) {
        ItemStack item = openMenu.getCaptiveItem(player, INPUT_ROW, INPUT_COLUMN);
        double cost = getCost();
        State state = evaluate(player, item, cost);

        String costLine = "&#B376E5Coût &8► &f" + NumberUtils.format(cost) + " &r%img_bitblock%";

        return switch (state) {
            case EMPTY -> icon(Material.PAPER, ICON_UNKNOWN,
                    "&#FFD94DAucun objet déposé",
                    List.of(
                            "&#B376E5Place l'objet à réparer",
                            "&#B376E5dans la case de gauche.",
                            "",
                            costLine),
                    player);
            case NOT_REPAIRABLE -> icon(Material.PAPER, ICON_LOCK,
                    "&#FF5C5CObjet non réparable",
                    List.of(
                            "&#B376E5Seuls les objets qui s'usent",
                            "&#B376E5(armes, outils, armures, cannes)",
                            "&#B376E5peuvent être réparés ici."),
                    player);
            case ALREADY_NEW -> icon(Material.PAPER, ICON_CHECKMARK,
                    "&#2ECF7BObjet déjà comme neuf",
                    List.of(
                            "&#B376E5Durabilité pleine et coût",
                            "&#B376E5d'enclume déjà à zéro.",
                            "",
                            "&#B376E5Rien à te facturer."),
                    player);
            case TOO_POOR -> icon(Material.PAPER, ICON_BITBLOCK,
                    "&#FF5C5CFonds insuffisants",
                    List.of(
                            costLine,
                            "&#B376E5Solde &8► &f" + NumberUtils.format(getBalance(player))
                                    + " &r%img_bitblock%"),
                    player);
            case READY -> icon(Material.PAPER, ICON_ACCEPT,
                    "&r&7%img_left_click%&#FFD94D Réparer",
                    List.of(
                            costLine,
                            "",
                            "&#B376E5Restaure la durabilité",
                            "&#B376E5et remet le coût d'enclume à zéro."),
                    player);
        };
    }

    // ── Logique ─────────────────────────────────────────────────────────────

    private double getCost() {
        return plugin.getConfig().getDouble("modules.repair.cost", 500.0);
    }

    private State evaluate(Player player, ItemStack item, double cost) {
        if (item == null || item.getType().isAir()) {
            return State.EMPTY;
        }
        // Un objet qui s'use ne s'empile pas : une pile est forcément autre chose.
        if (item.getAmount() > 1 || item.getType().getMaxDurability() <= 0) {
            return State.NOT_REPAIRABLE;
        }
        if (AnvilRestrictionListener.isMarkedUnrepairable(item)) {
            return State.NOT_REPAIRABLE;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return State.NOT_REPAIRABLE;
        }
        int repairCost = meta instanceof Repairable repairable ? repairable.getRepairCost() : 0;
        // Les cannes CustomFishing ont leur propre compteur de durabilité (jusqu'à 160 sur
        // une FISHING_ROD plafonnée à 64 côté vanilla) : la barre vanilla n'en est qu'une
        // projection arrondie, qui peut encore afficher "neuf" alors que la canne est usée.
        boolean customFishingDamaged = CustomFishingDurability.isCustomDurabilityItem(item)
                && CustomFishingDurability.isDamaged(item);
        if (damageable.getDamage() <= 0 && repairCost <= 0 && !customFishingDamaged) {
            return State.ALREADY_NEW;
        }
        if (getBalance(player) < cost) {
            return State.TOO_POOR;
        }
        return State.READY;
    }

    private void repair(Player player, Inventory topInventory, Menu openMenu) {
        ItemStack item = topInventory.getItem(INPUT_RAW_SLOT);
        double cost = getCost();
        State state = evaluate(player, item, cost);

        if (state != State.READY) {
            player.sendMessage(StringUtils.format(switch (state) {
                case EMPTY -> "&#FF5C5CDépose d'abord un objet dans la case de gauche.";
                case NOT_REPAIRABLE -> "&#FF5C5CCet objet ne peut pas être réparé.";
                case ALREADY_NEW -> "&#FF5C5CCet objet est déjà comme neuf.";
                default -> "&#FF5C5CIl te faut &f" + NumberUtils.format(cost)
                        + " &r%img_bitblock%&#FF5C5C pour réparer cet objet.";
            }, player));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        Economy economy = getEconomy();
        if (economy == null || !economy.withdrawPlayer(player, cost).transactionSuccess()) {
            player.sendMessage(StringUtils.format(
                    "&#FF5C5CLe paiement a échoué, ton objet n'a pas été touché.", player));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ItemMeta meta = item.getItemMeta();
        ((Damageable) meta).setDamage(0);
        if (meta instanceof Repairable repairable) {
            repairable.setRepairCost(0);
        }
        item.setItemMeta(meta);

        // Canne CustomFishing : la remise à zéro vanilla ci-dessus ne fait que remplir la
        // barre, le vrai compteur (cur_dur) vit dans le NBT du plugin. Appliqué APRÈS le
        // setItemMeta, qui réécrirait sinon l'objet par-dessus.
        if (CustomFishingDurability.isCustomDurabilityItem(item)) {
            CustomFishingDurability.restore(player, item);
        }

        topInventory.setItem(INPUT_RAW_SLOT, item);
        openMenu.refresh(player);

        // Convention de notification de perte de BitBlock (docs/notifications/bitblock.md) :
        // verbe générique "perdu", flèche rouge, source entre crochets en petites capitales.
        player.sendMessage(StringUtils.format(
                "&r&7%img_bitblock% &#B376E5Tu as perdu &f" + NumberUtils.format(cost)
                        + " &#B376E5Bitblock &#FF5C5C▼&7 [ʀᴇᴘᴀʀᴀᴛɪᴏɴ]", player));
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f);
    }

    private double getBalance(Player player) {
        Economy economy = getEconomy();
        return economy == null ? 0 : economy.getBalance(player);
    }

    /**
     * Résolu à chaque appel plutôt que mis en cache au démarrage : CMIEInjector hooke
     * CMIEconomy dans Vault plusieurs secondes après le onEnable des plugins (cf.
     * {@link LateVaultEconomyBinder}), un cache pris trop tôt resterait null à vie.
     */
    private static Economy getEconomy() {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    // ── Rendu ───────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation") // custom_model_data entier : format lu par le pack ItemsAdder
    private static ItemStack icon(Material material, int modelData, String name,
                                  List<String> lore, Player player) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(StringUtils.format(name, player));
            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }
            List<String> formatted = new ArrayList<>(lore.size());
            for (String line : lore) {
                formatted.add(StringUtils.format(line, player));
            }
            meta.setLore(fr.influencraft.flux.api.text.Texts.wrapLines(formatted));
            icon.setItemMeta(meta);
        }
        return icon;
    }
}
